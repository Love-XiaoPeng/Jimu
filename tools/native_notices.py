"""Offline source-license inventory for the pinned search bundle; never downloads or executes code."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile
import tomllib
import subprocess
import io


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('inputs',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args(); args.output.mkdir(parents=True,exist_ok=True)
    inputs=args.inputs; sections=[]; components=[]; sources=[]
    def source(name,url):
        path=inputs/name
        sources.append({'file':name,'url':url,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    def licenses(archive,label):
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                if member.isfile() and re.match(r'^(LICENSE|COPYING|COPYRIGHT|UNLICENSE|NOTICE)([.\-_]|$)',Path(member.name).name,re.I):
                    sections.append('\n===== '+label+': '+member.name+' =====\n'+tar.extractfile(member).read().decode('utf-8',errors='replace'))
    lock=tomllib.loads((inputs/'ripgrep-Cargo.lock').read_text())
    licenses(inputs/'rg.tgz','Microsoft npm package attribution')
    for item in lock['package']:
        name,version=item['name'],item['version']
        if not item.get('checksum'): continue
        path=inputs/'crates'/(name+'-'+version+'.crate')
        assert hashlib.sha256(path.read_bytes()).hexdigest()==item['checksum'],path.name
        with tarfile.open(path) as tar:
            cargo=next(m for m in tar.getmembers() if m.name==name+'-'+version+'/Cargo.toml')
            meta=tomllib.loads(tar.extractfile(cargo).read().decode())['package']
        components.append({'type':'library','name':name,'version':version,'purl':'pkg:cargo/'+name+'@'+version,
            'hashes':[{'alg':'SHA-256','content':item['checksum']}],
            'licenses':[{'expression':meta.get('license','LicenseRef-See-Notices')}],
            'properties':[{'name':'jimu:inventory-scope','value':'upstream locked source graph; includes target/build/test-only packages'}]})
        licenses(path,name+' '+version)
        source('crates/'+path.name,'https://static.crates.io/crates/'+name+'/'+path.name)
    for name,label,url in [
        ('ripgrep-source.tar.gz','ripgrep workspace','https://codeload.github.com/BurntSushi/ripgrep/tar.gz/3a612f88b8'),
        ('rust-src.tar.xz','Rust 1.88.0 standard library / libunwind','https://static.rust-lang.org/dist/rust-src-1.88.0.tar.xz'),
        ('rust-std.tar.xz','Rust 1.88.0 musl std distribution','https://static.rust-lang.org/dist/rust-std-1.88.0-x86_64-unknown-linux-musl.tar.xz'),
        ('musl-source.tar.gz','musl 1.2.3','https://musl.libc.org/releases/musl-1.2.3.tar.gz'),
        ('bwrap.orig.tar.xz','bubblewrap 0.9.0','https://archive.ubuntu.com/ubuntu/pool/main/b/bubblewrap/bubblewrap_0.9.0.orig.tar.xz'),
        ('bwrap.debian.tar.xz','Ubuntu bubblewrap patches 0.9.0-1ubuntu0.3','https://archive.ubuntu.com/ubuntu/pool/main/b/bubblewrap/bubblewrap_0.9.0-1ubuntu0.3.debian.tar.xz')]:
        licenses(inputs/name,label);source(name,url)
    for name in ['LGPL-2','LGPL-2.1','GPL-2']:
        sections.append('\n===== '+name+' =====\n'+(Path('/usr/share/common-licenses')/name).read_text())
    with tarfile.open(fileobj=io.BytesIO(subprocess.run(['dpkg-deb','--fsys-tarfile',str(inputs/'bwrap.deb')],capture_output=True,check=True).stdout)) as deb:
        sections.append('\n===== Ubuntu bubblewrap package copyright =====\n'+deb.extractfile('./usr/share/doc/bubblewrap/copyright').read().decode())
    for path,label in [('/usr/share/doc/libc6/copyright','test host glibc (system prerequisite; not redistributed by bundle)'),
                       ('/usr/share/doc/gcc-14-base/copyright','Host GCC runtime exception and compiler notices (includes GPL Runtime Library Exception 3.1)'),
                       ('/usr/share/doc/libcap2/copyright','test host libcap (system prerequisite; not redistributed by bundle)')]:
        sections.append('\n===== '+label+' =====\n'+Path(path).read_text())
    for name,url in [('ripgrep-Cargo.lock','https://raw.githubusercontent.com/BurntSushi/ripgrep/3a612f88b8/Cargo.lock'),
        ('rg.tgz','https://registry.npmjs.org/@vscode/ripgrep-linux-x64/-/ripgrep-linux-x64-1.18.0.tgz'),
        ('bwrap.deb','https://archive.ubuntu.com/ubuntu/pool/main/b/bubblewrap/bubblewrap_0.9.0-1ubuntu0.3_amd64.deb'),
        ('bwrap.dsc','https://archive.ubuntu.com/ubuntu/pool/main/b/bubblewrap/bubblewrap_0.9.0-1ubuntu0.3.dsc'),
        ('vendor-patch.diff','https://raw.githubusercontent.com/microsoft/ripgrep-prebuilt/5c302c331f59f578fd90e024a8f374012c40e8b4/patches/0001-resolve-binskim-issues.patch')]: source(name,url)
    for name in ['vendor-main.yml','vendor-linux.yml','vendor-build.sh']:
        source(name,'https://github.com/microsoft/ripgrep-prebuilt/tree/5c302c331f59f578fd90e024a8f374012c40e8b4')
    (args.output/'THIRD_PARTY_NOTICES.txt').write_text('Search native source notices. See README.md for inventory scope and redistribution obligations.\n'+''.join(sections))
    (args.output/'source-inputs.json').write_text(json.dumps(sources,indent=2)+'\n')
    components += [
        {'type':'application','name':'ripgrep','version':'15.0.0 (3a612f88b8)','hashes':[{'alg':'SHA-256','content':'193906679498de4d939345b937fa24e0e69a03c244bd70c859f5e41232713f21'}],'licenses':[{'expression':'MIT OR Unlicense'}]},
        {'type':'application','name':'bubblewrap','version':'0.9.0-1ubuntu0.3','hashes':[{'alg':'SHA-256','content':'e318903862396f96de3df57264e0158682b952fd3fb53ac23d876413e7b30f71'}],'licenses':[{'expression':'LGPL-2.0-or-later'}]},
        {'type':'library','name':'PCRE2 (vendored in pcre2-sys)','version':'10.45','licenses':[{'expression':'BSD-3-Clause'}]},
        {'type':'library','name':'jemalloc (vendored in tikv-jemalloc-sys)','version':'5.3.0-e13ca993','licenses':[{'expression':'BSD-2-Clause'}]},
        {'type':'library','name':'Rust std','version':'1.88.0','licenses':[{'expression':'MIT OR Apache-2.0'}]},
        {'type':'library','name':'musl','version':'1.2.3','licenses':[{'expression':'MIT'}]},
        {'type':'library','name':'LLVM libunwind/compiler runtime','version':'Rust 1.88.0 sources','licenses':[{'expression':'Apache-2.0 WITH LLVM-exception'}]}
    ]
    (args.output/'sbom.cdx.json').write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.6','version':1,
        'metadata':{'component':{'type':'application','name':'Jimu optional Linux x86_64 search bundle','version':'p4-v1'},
        'properties':[{'name':'jimu:provenance','value':'Vendor binary hash verified; conservative source-license closure, not a vendor attestation of linked features. System glibc/libcap and locally built launcher recorded by bundle.json.'}]},
        'components':components},indent=2)+'\n')


if __name__=='__main__':main()
