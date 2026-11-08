"""Inspect an Apple app archive and optionally wrap an unsigned device .app as an IPA."""
import argparse
import copy
import hashlib
import json
import plistlib
import struct
import zipfile
from pathlib import Path


def slices(data):
    if data[:4] in (bytes.fromhex('cafebabe'), bytes.fromhex('cafebabf')):
        count, = struct.unpack_from('>I', data, 4)
        fat64 = data[:4] == bytes.fromhex('cafebabf')
        for index in range(count):
            position = 8 + index * (32 if fat64 else 20) + 8
            offset, size = struct.unpack_from('>QQ' if fat64 else '>II', data, position)
            yield data[offset:offset + size]
    else:
        yield data


def inspect_slice(data):
    if data[:4] != bytes.fromhex('cffaedfe'):
        raise ValueError('Expected a 64-bit little-endian Mach-O executable')
    cpu, _, file_type, count, size = struct.unpack_from('<5I', data, 4)
    if file_type != 2 or 32 + size > len(data):
        raise ValueError('Invalid Mach-O executable header')
    offset, platform, signature = 32, None, False
    for _ in range(count):
        command, length = struct.unpack_from('<II', data, offset)
        if length < 8 or offset + length > 32 + size:
            raise ValueError('Malformed load command')
        if command == 0x32:  # LC_BUILD_VERSION
            platform, = struct.unpack_from('<I', data, offset + 8)
        elif command == 0x1d:  # LC_CODE_SIGNATURE
            signature = True
        offset += length
    if platform not in (2, 7):
        raise ValueError(f'Cannot verify iOS target platform from LC_BUILD_VERSION: {platform}')
    return {'architecture': {0x100000c: 'arm64', 0x1000007: 'x86_64'}.get(cpu, hex(cpu)),
            'buildPlatform': platform, 'codeSignatureLoadCommand': signature}


def inspect(path):
    with zipfile.ZipFile(path) as archive:
        roots = [root for root in ('CampusReader.app/', 'Payload/CampusReader.app/')
                 if root + 'Info.plist' in archive.namelist()]
        if len(roots) != 1:
            raise ValueError('Expected one CampusReader app')
        root = roots[0]
        info = plistlib.loads(archive.read(root + 'Info.plist'))
        executable = info['CFBundleExecutable']
        if '/' in executable:
            raise ValueError('Invalid executable name')
        images = [inspect_slice(data) for data in slices(archive.read(root + executable))]
        sdk = info['DTPlatformName']
        target = {'iphoneos': (2, 'iPhoneOS'), 'iphonesimulator': (7, 'iPhoneSimulator')}[sdk]
        if not images or any(s['buildPlatform'] != target[0] for s in images) or info['CFBundleSupportedPlatforms'] != [target[1]]:
            raise ValueError('Platform mismatch between Mach-O and bundle metadata')
        signature = any(s['codeSignatureLoadCommand'] for s in images)
        profile = root + 'embedded.mobileprovision' in archive.namelist()
        resources = root + '_CodeSignature/CodeResources' in archive.namelist()
        return {'archive': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'root': root, 'bundleID': info['CFBundleIdentifier'], 'version': info['CFBundleShortVersionString'],
                'build': info['CFBundleVersion'], 'executable': executable, 'minOS': info['MinimumOSVersion'],
                'platform': sdk, 'sdk': info['DTSDKName'], 'slices': images,
                'hasProfile': profile, 'hasAppCodeResources': resources,
                'signing': 'unsigned' if not (signature or profile or resources) else 'requires codesign/profile verification',
                'team': None, 'expiration': None, 'authorizedDevices': None,
                'usageDescriptions': {k: v for k, v in info.items() if k.endswith('UsageDescription')},
                'backgroundModes': info.get('UIBackgroundModes', []),
                'ats': info.get('NSAppTransportSecurity', {})}


def wrap(source, destination, report):
    if report['platform'] != 'iphoneos' or report['signing'] != 'unsigned' or report['root'] != 'CampusReader.app/':
        raise ValueError('Wrap only a verified unsigned iphoneos app ZIP')
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(destination, 'w') as dst:
        for item in src.infolist():
            if item.filename.startswith(report['root']):
                entry = copy.copy(item)
                entry.filename = 'Payload/' + item.filename
                dst.writestr(entry, src.read(item))
    return inspect(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--platform', choices=['iphoneos', 'iphonesimulator'], required=True)
    parser.add_argument('--make-ipa', type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--commit')
    parser.add_argument('--run')
    args = parser.parse_args()
    report = inspect(args.archive)
    if report['platform'] != args.platform:
        raise ValueError('Not the requested platform')
    if args.make_ipa:
        report = wrap(args.archive, args.make_ipa, report)
    report.update(sourceCommit=args.commit, workflowRun=args.run)
    result = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.write_text(result + '\n', encoding='utf8')
    print(result)


if __name__ == '__main__':
    main()
