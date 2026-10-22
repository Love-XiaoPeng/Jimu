"""Package the reviewed handoff; exclude local checkouts, compiled classes and classpaths."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile

root=Path(__file__).resolve().parent
target=root.parent/'xiao50-architecture-v1.zip'
selected=[]
for p in sorted(root.rglob('*')):
    if not p.is_file():
        continue
    rel=p.relative_to(root)
    if rel.parts[0] in {'review-source','classes','__pycache__'}:
        continue
    if rel.name in {'jimu-master.zip','classpath-local.txt','PACKAGE-MANIFEST.json'}:
        continue
    selected.append((p,rel.as_posix()))
manifest={
 'version':1,'createdAt':datetime.now(timezone.utc).isoformat(),
 'issue':'XIAO-50','master':'777a2e2ae47c3d5be7ac545648c0d90483124ff2',
 'tree':'6bfa20648fdf7124ff8a32f4c6c90ce0cfdfae95',
 'scope':'Architecture handoff only; no product runtime or migration acceptance',
 'selfExcluded':'PACKAGE-MANIFEST.json',
 'files':[{'path':rel,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p,rel in selected]
}
mp=root/'PACKAGE-MANIFEST.json'
mp.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p,rel in selected:
        z.write(p,rel)
    z.write(mp,mp.name)
with zipfile.ZipFile(target) as z:
    assert z.testzip() is None
    m=json.loads(z.read('PACKAGE-MANIFEST.json'))
    assert len(z.namelist())==len(m['files'])+1
    for f in m['files']:
        b=z.read(f['path'])
        assert len(b)==f['bytes'] and hashlib.sha256(b).hexdigest()==f['sha256'],f['path']
    assert not any(s in p for p in z.namelist() for s in ['review-source/','classes/','classpath-local.txt'])
print(json.dumps({'file':target.name,'entries':len(selected)+1,'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'crcAndManifest':'PASS'},ensure_ascii=False))
