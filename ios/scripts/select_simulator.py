"""Select one installed iPhone simulator without hard-coding a moving runtime name."""
import json,re,subprocess
data=json.loads(subprocess.check_output(['xcrun','simctl','list','devices','available','--json']))
def version(text):
    parts=re.findall(r'\d+',text)
    return tuple((list(map(int,parts))+[0,0,0])[:3])
sdk=version(subprocess.check_output(['xcrun','--sdk','iphonesimulator','--show-sdk-version'],text=True))
for runtime,devices in sorted(data['devices'].items(),key=lambda item:version(item[0]),reverse=True):
    if 'iOS' not in runtime or version(runtime)>sdk or version(runtime)[0]!=sdk[0]:continue
    for device in devices:
        if device.get('isAvailable') and device['name'].startswith('iPhone'):
            print(device['udid']);raise SystemExit(0)
raise SystemExit(f'No available iPhone simulator matching the installed SDK {sdk}')
