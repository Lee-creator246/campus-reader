"""Create an allowlisted source ZIP with a per-file manifest, never an APK/SDK bundle."""
from pathlib import Path
import hashlib,json,zipfile
from check_public_tree import ROOT,public_files,validate
files=[p for p in public_files() if p.name!='SOURCE_MANIFEST.json'];validate(files)
destination=ROOT/'dist';destination.mkdir(exist_ok=True)
name='campus-reader-0.5.0-source'
archive=destination/(name+'.zip')
manifest={}
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as output:
    for path in files:
        relative=path.relative_to(ROOT).as_posix();data=path.read_bytes()
        info=zipfile.ZipInfo(name+'/'+relative,date_time=(2026,10,31,0,0,0));info.create_system=3
        info.external_attr=((0o100755 if relative=='gradlew' else 0o100644)<<16)
        output.writestr(info,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
        manifest[relative]=hashlib.sha256(data).hexdigest()
    output.writestr(name+'/SOURCE_MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(archive) as check:
    assert check.testzip() is None
    assert len(check.namelist())==len(files)+1
    for relative,digest in manifest.items():assert hashlib.sha256(check.read(name+'/'+relative)).hexdigest()==digest
sha=hashlib.sha256(archive.read_bytes()).hexdigest()
(destination/(archive.name+'.sha256')).write_text(sha+'  '+archive.name+'\n',encoding='ascii')
print(json.dumps({'archive':str(archive),'files':len(files),'bytes':archive.stat().st_size,'sha256':sha},ensure_ascii=False))
