"""Prepare the optional fixed Linux x86_64 search bundle entirely from verified local inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--inputs',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--cc',default='cc')
    parser.add_argument('--cc-arg',action='append',default=[])
    args=parser.parse_args()
    assert os.name=='posix' and os.uname().machine=='x86_64','Linux x86_64 only'
    source=Path(__file__).resolve().parents[1]/'workers/search'
    output=args.output.resolve()
    assert not output.exists(),'output must be new, so existing bundles cannot be overwritten'
    inventory=json.loads((source/'source-inputs.json').read_text())
    for item in inventory:
        assert hashlib.sha256((args.inputs/item['file']).read_bytes()).hexdigest()==item['sha256'],item['file']
    output.mkdir(mode=0o755,parents=True)
    (output/'bin').mkdir();(output/'sources').mkdir()
    with tarfile.open(args.inputs/'rg.tgz') as package:
        data=package.extractfile('package/bin/rg').read()
        assert hashlib.sha256(data).hexdigest()=='193906679498de4d939345b937fa24e0e69a03c244bd70c859f5e41232713f21'
        (output/'bin/rg').write_bytes(data)
    with tempfile.TemporaryDirectory(prefix='jimu-native-unpack-') as temporary:
        subprocess.run(['dpkg-deb','-x',str(args.inputs/'bwrap.deb'),temporary],check=True)
        shutil.copyfile(Path(temporary)/'usr/bin/bwrap',output/'bin/bwrap')
    assert hashlib.sha256((output/'bin/bwrap').read_bytes()).hexdigest()=='e318903862396f96de3df57264e0158682b952fd3fb53ac23d876413e7b30f71'
    command=[args.cc,*args.cc_arg,'-static-libgcc','-O2','-Wall','-Wextra','-Werror','-o',str(output/'bin/jimu-search-launcher'),str(source/'launcher.c')]
    subprocess.run(command,check=True)
    for file in (output/'bin').iterdir():file.chmod(0o755)
    for name in ['THIRD_PARTY_NOTICES.txt','sbom.cdx.json','source-inputs.json','README.md','launcher.c']:
        shutil.copyfile(source/name,output/name)
    shutil.copyfile(source.parents[1]/'docs/verification/XIAO-43/Harness-LICENSE.txt',output/'Harness-LICENSE.txt')
    # Supply the corresponding upstream and Ubuntu patch sources with redistributable bundles.
    for item in inventory:
        name=item['file']
        if name in ['rg.tgz','bwrap.deb','rust-std.tar.xz']:continue
        target=output/'sources'/name;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(args.inputs/name,target)
    hashes={str(file.relative_to(output)):hashlib.sha256(file.read_bytes()).hexdigest() for file in output.rglob('*') if file.is_file()}
    manifest={'launcher':str(output/'bin/jimu-search-launcher'),'launcherSha256':hashes['bin/jimu-search-launcher'],
        'rg':str(output/'bin/rg'),'bubblewrap':str(output/'bin/bwrap'),'files':hashes,'buildCommand':command,
        'compiler':subprocess.run([args.cc,'--version'],capture_output=True,text=True,check=True).stdout,
        'dynamicDependencies':{name:subprocess.run(['ldd',str(output/'bin'/name)],capture_output=True,text=True,check=True).stdout for name in ['jimu-search-launcher','bwrap']}}
    (output/'bundle.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps({k:manifest[k] for k in ['launcher','launcherSha256','rg','bubblewrap']}))


if __name__=='__main__':main()
