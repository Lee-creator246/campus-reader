"""Select one installed iPhone simulator without hard-coding a moving runtime name."""
import json,subprocess
data=json.loads(subprocess.check_output(['xcrun','simctl','list','devices','available','--json']))
for runtime,devices in sorted(data['devices'].items(),reverse=True):
    if 'iOS' not in runtime:continue
    for device in devices:
        if device.get('isAvailable') and device['name'].startswith('iPhone'):
            print(device['udid']);raise SystemExit(0)
raise SystemExit('No installed iPhone Simulator runtime')
