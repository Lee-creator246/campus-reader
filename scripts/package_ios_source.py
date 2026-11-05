"""Build a standalone, deterministic iOS source ZIP from committed files.

Optional --evidence is a downloaded ios-build-evidence artifact ZIP. Only the
shared project files from its CampusReader.xcodeproj.zip are included.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tempfile
import zipfile

from check_public_tree import DENIED_EXT, GENERATED, PATTERNS, ROOT


FILE_LIMIT = 2 * 1024 * 1024
IOS_TEXT = {'.swift', '.plist', '.xcprivacy', '.json', '.yml', '.sh', '.py', '.md', '.txt'}
PROJECT_NAME = 'CampusReader.xcodeproj'
SHARED_PROJECT_FILES = {
    'project.pbxproj',
    'project.xcworkspace/contents.xcworkspacedata',
    'project.xcworkspace/xcshareddata/swiftpm/Package.resolved',
    'xcshareddata/xcschemes/CampusReader.xcscheme',
}


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def safe_path(name):
    # ZIP headers always use POSIX paths. Disallow Windows drives, ADS and
    # alias spellings even though no external entries are extracted to disk.
    parts = name.split('/')
    if (any(not piece or piece.endswith(('.', ' ')) for piece in parts)
            or re.search(r'[\\:<>"|?*\x00-\x1f]', name)):
        raise ValueError('Unsafe archive path')
    return PurePosixPath(name)


def public_text(name, data):
    if len(data) > FILE_LIMIT or b'\0' in data:
        raise ValueError('Binary or oversized source file: ' + name)
    value = data.decode('utf-8')
    for description, pattern in PATTERNS:
        if pattern.search(value):
            raise ValueError('Excluded ' + description + ' in ' + name)
    return value


def committed_files(commit):
    members = {}
    tree = git('ls-tree', '-r', '-l', '-z', commit, '--', 'ios', 'LICENSE')
    for record in tree.split(b'\0'):
        if not record:
            continue
        header, raw_name = record.split(b'\t', 1)
        mode, kind, oid, size = header.split()
        name = raw_name.decode('utf-8')
        path = safe_path(name)
        if name != 'LICENSE':
            if (path.parts[0] != 'ios' or any(part in GENERATED for part in path.parts)
                    or any(part.lower() in {'private', 'vendor', 'secrets', 'signing', 'xcuserdata'}
                           or part.lower().endswith(('.framework', '.xcframework', '.xcarchive')) for part in path.parts)
                    or path.suffix.lower() in DENIED_EXT or path.name.startswith('.env')
                    or (path.suffix.lower() not in IOS_TEXT and path.name != 'Package.resolved')):
                continue
        if kind != b'blob' or mode not in (b'100644', b'100755') or int(size) > FILE_LIMIT:
            raise ValueError('Expected small regular source file: ' + name)
        contents = git('cat-file', 'blob', oid.decode('ascii'))
        public_text(name, contents)
        members[name] = (contents, int(mode, 8))
    for required in ('LICENSE', 'ios/project.yml', 'ios/scripts/build.sh', 'ios/CampusReaderCore/Package.swift'):
        if required not in members:
            raise ValueError('Missing committed input: ' + required)
    return members


def safe_member(archive, entry, maximum):
    mode = entry.external_attr >> 16
    if (entry.flag_bits & 1 or stat.S_IFMT(mode) not in (0, stat.S_IFREG)
            or entry.file_size > maximum or entry.is_dir()):
        raise ValueError('Invalid ZIP member type or size')
    data = archive.read(entry)
    if len(data) > maximum:
        raise ValueError('Oversized entry')
    return data


def generated_project(evidence, commit):
    with zipfile.ZipFile(evidence) as artifact:
        candidates = []
        for entry in artifact.infolist():
            path = safe_path(entry.filename.rstrip('/'))
            if path.name == PROJECT_NAME + '.zip':
                candidates.append(entry)
        if len(candidates) != 1:
            raise ValueError('Expected one CampusReader.xcodeproj.zip in the evidence')
        generated = candidates[0]
        status_name = str(PurePosixPath(generated.filename).with_name('BUILD-STATUS.txt'))
        records = [entry for entry in artifact.infolist() if entry.filename == status_name]
        if len(records) != 1:
            raise ValueError('Missing or ambiguous BUILD-STATUS.txt beside the generated project')
        metadata = safe_member(artifact, records[0], 8 * 1024).decode('utf-8').splitlines()
        commits = [line[7:] for line in metadata if line.startswith('commit=')]
        runs = [line[4:] for line in metadata if line.startswith('run=')]
        if commits != [commit] or len(runs) != 1 or not re.fullmatch(r'[1-9][0-9]*', runs[0]):
            raise ValueError('Build evidence must record the current source commit and one CI run ID')
        payload = safe_member(artifact, generated, 4 * 1024 * 1024)
    output = {}
    with zipfile.ZipFile(io.BytesIO(payload)) as project:
        for entry in project.infolist():
            path = safe_path(entry.filename.rstrip('/'))
            if (entry.is_dir() or path.parts[0] != PROJECT_NAME
                    or '/'.join(path.parts[1:]) not in SHARED_PROJECT_FILES):
                continue
            name = 'ios/' + path.as_posix()
            if name in output:
                raise ValueError('Duplicate generated project entry: ' + name)
            data = safe_member(project, entry, FILE_LIMIT)
            value = public_text(name, data)
            if re.search(r'(?i)/Users/|file:///|xcuserdata/|\.mobileprovision\b|\.p12\b|'
                         r'DEVELOPMENT_TEAM\s*=\s*"?[A-Z0-9]{10}"?\s*;', value):
                raise ValueError('Personal machine or signing reference in ' + name)
            output[name] = (data, 0o100644)
    if any('ios/' + PROJECT_NAME + '/' + part not in output for part in
           ('project.pbxproj', 'xcshareddata/xcschemes/CampusReader.xcscheme')):
        raise ValueError('Missing shared app project or scheme')
    return output, runs[0]


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + '\n').encode('utf-8')


def write_verified(destination, name, files):
    manifest = {path: hashlib.sha256(data).hexdigest() for path, (data, _) in files.items()}
    members = {**files, 'SOURCE_MANIFEST.json': (json_bytes(manifest), 0o100644)}
    with zipfile.ZipFile(destination, 'w') as output:
        for path, (data, mode) in sorted(members.items()):
            info = zipfile.ZipInfo(name + '/' + path, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = mode << 16
            output.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    with zipfile.ZipFile(destination) as check:
        if check.testzip() is not None or len(check.namelist()) != len(members):
            raise ValueError('Invalid source archive')
        stored = json.loads(check.read(name + '/SOURCE_MANIFEST.json'))
        if stored != manifest or any(hashlib.sha256(check.read(name + '/' + path)).hexdigest() != digest
                                     for path, digest in stored.items()):
            raise ValueError('Source manifest verification failed')


def build(evidence=None):
    commit = git('rev-parse', '--verify', 'HEAD^{commit}').decode('ascii').strip()
    files = committed_files(commit)
    version = re.search(rb"(?m)^\s*MARKETING_VERSION:\s*['\"](\d+\.\d+\.\d+)['\"]\s*$", files['ios/project.yml'][0])
    if not version:
        raise ValueError('Missing iOS marketing version')
    name = 'campus-reader-ios-' + version[1].decode('ascii') + '-source'
    files['SOURCE_COMMIT'] = ((commit + '\n').encode('ascii'), 0o100644)
    if evidence:
        project_files, run_id = generated_project(evidence, commit)
        files.update(project_files)
        with evidence.open('rb') as input_file:
            evidence_hash = hashlib.file_digest(input_file, 'sha256').hexdigest()
        files['BUILD_EVIDENCE.json'] = (json_bytes({'artifactSHA256': evidence_hash, 'commit': commit, 'run': run_id,
            'included': 'Shared Xcode project metadata only'}), 0o100644)
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    final = dist / (name + '.zip')
    with tempfile.TemporaryDirectory(dir=dist) as directory:
        temporary = Path(directory) / final.name
        write_verified(temporary, name, files)
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        temporary.replace(final)
    final.with_suffix('.zip.sha256').write_bytes((digest + '  ' + final.name + '\n').encode('ascii'))
    return {'archive': str(final), 'commit': commit, 'files': len(files) + 1,
            'includesGeneratedProject': bool(evidence), 'sha256': digest}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, help='Downloaded GitHub ios-build-evidence artifact ZIP')
    try:
        print(json.dumps(build(parser.parse_args().evidence), ensure_ascii=False))
    except (OSError, ValueError, UnicodeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f'{error}\n')
