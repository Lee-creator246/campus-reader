"""Check the source allowlist, or all Git-tracked files; no network or device access."""
from pathlib import Path
import os,re,subprocess,sys,hashlib
ROOT=Path(__file__).resolve().parents[1]
ROOT_FILES={'settings.gradle','build.gradle','gradle.properties','gradlew','gradlew.bat','LICENSE','README.md','BUILDING.md','THIRD_PARTY_NOTICES.md','CONTRIBUTING.md','SECURITY.md','CHANGELOG.md','.gitignore','.gitattributes','.editorconfig','SOURCE_MANIFEST.json'}
DIRS={'campus-config','app','campus-auth','campus-extension','campus-portal','checks','gradle','docs','scripts','licenses','vendor','.github','ios','web'}
GENERATED={'build','.gradle','.git','dist','__pycache__','.build','DerivedData','xcuserdata','CampusReader.xcodeproj','SafariReaderSmoke.xcodeproj'}
WRAPPER='gradle/wrapper/gradle-wrapper.jar'
DENIED_EXT={'.apk','.aab','.xapk','.aar','.so','.dex','.zip','.jks','.keystore','.key','.pem','.p12','.pfx','.db','.sqlite','.sqlite3','.png','.jpg','.jpeg','.log','.pyc','.ipa','.mobileprovision','.provisionprofile'}
PATTERNS=[
    ('private key',re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')),
    ('GitHub token',re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b')),
    ('authorization value',re.compile(r'(?im)^\s*(?:Authorization:\s*Bearer|Cookie:)\s*\S{16,}')),
    ('local personal path',re.compile(r'[A-Za-z]:[\\/](?:Users[\\/][^\s"\']+|Codex_[A-Za-z]+)')),
    ('exported campus profile',re.compile(r'campus:[a-f0-9]{64}'))
]
def public_files():
    files=[]
    for name in sorted(ROOT_FILES):
        if (ROOT/name).is_file():files.append(ROOT/name)
    for name in sorted(DIRS):
        start=ROOT/name
        if not start.exists():continue
        for directory,children,names in os.walk(start,followlinks=False):
            base=Path(directory)
            children[:]=[d for d in children if d not in GENERATED and not (name=='vendor' and d=='sangfor')]
            for d in children:
                if (base/d).is_symlink():raise ValueError('Symlink directory: '+str((base/d).relative_to(ROOT)))
            files.extend(base/n for n in names if (base/n).relative_to(ROOT).as_posix()!='ios/CampusReader/Assets.xcassets/AppIcon.appiconset/AppIcon.png')
    return sorted(files)
def validate(files,staged=None):
    errors=[]
    for path in files:
        relative=path.relative_to(ROOT).as_posix()
        if path.is_symlink() or not path.is_file():errors.append(relative+': missing/nonregular file');continue
        parts=Path(relative).parts
        if (len(parts)==1 and relative not in ROOT_FILES) or (len(parts)>1 and parts[0] not in DIRS):errors.append(relative+': not allowlisted')
        if any(part in GENERATED for part in parts):errors.append(relative+': generated/private directory')
        if path.name.startswith('push-state.json') or path.name in {'feedback.ndjson','public-notices.json'}:
            errors.append(relative+': private server state or operator data');continue
        if path.suffix.lower() in DENIED_EXT or path.name in {'local.properties','campus.local.properties','keystore.properties','signing.properties'} or path.name.startswith('.env'):errors.append(relative+': excluded material');continue
        if relative==WRAPPER:continue
        if path.stat().st_size>2*1024*1024:errors.append(relative+': unexpected large file');continue
        try:text=(staged[relative].decode('utf-8') if staged is not None else path.read_text(encoding='utf-8'))
        except (UnicodeError,OSError):errors.append(relative+': unexpected binary');continue
        for label,pattern in PATTERNS:
            # A published adversarial fixture has a documented, synthetic hash of "123".
            if label=='exported campus profile' and relative=='checks/src/main/java/MediaSafetyChecks.java':continue
            if pattern.search(text):errors.append(relative+': possible '+label)
    if errors:raise ValueError('\n'.join(errors))
    if not (ROOT/'LICENSE').is_file():raise ValueError('Missing project license')
    return len(files)
def main():
    if '--tracked' in sys.argv:
        raw=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT)
        files=[ROOT/p.decode('utf-8') for p in raw.split(b'\0') if p]
        if not files:raise ValueError('No tracked files; stage files before running --tracked')
        staged={p.relative_to(ROOT).as_posix():subprocess.check_output(['git','show',':'+p.relative_to(ROOT).as_posix()],cwd=ROOT) for p in files}
    else:files=public_files();staged=None
    print('Public source checks passed:',validate(files,staged),'files')
if __name__=='__main__':
    try:main()
    except (ValueError,subprocess.CalledProcessError) as error:print(error,file=sys.stderr);sys.exit(1)
