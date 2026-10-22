"""Build native bundle v2 offline from its pinned sources, including both C executables.

Preparation only; never called by the application. Use a dedicated Ubuntu 24.04
build host and run under `unshare -n` to independently enforce offline operation.
"""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tarfile
import tomllib


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_inputs(inputs, manifest):
    # Explicit checks also run with python -O. Check everything before extracting or compiling.
    for item in manifest['inputs']:
        path = inputs / item['file']
        if not path.is_file() or path.stat().st_size != item['size'] or digest(path) != item['sha256']:
            raise ValueError('Missing or mismatched native input: ' + item['file'])


def unpack(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as package:
        package.extractall(destination, filter='data')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if os.uname().sysname != 'Linux' or os.uname().machine != 'x86_64':
        raise ValueError('Linux x86_64 only')
    repo = Path(__file__).resolve().parents[1]
    source = repo / 'workers/search'
    manifest = json.loads((source / 'v2/source-inputs.json').read_text())
    inputs, work, output = args.inputs.resolve(), args.work.resolve(), args.output.resolve()
    if work.exists() or output.exists() or work == output:
        raise ValueError('Work and output must be distinct new directories')
    verify_inputs(inputs, manifest)
    # Pair the renamed local Ubuntu archives with the exact .dsc source checksums.
    dsc = (inputs / 'bwrap.dsc').read_text()
    for local, upstream in [('bwrap.orig.tar.xz', 'bubblewrap_0.9.0.orig.tar.xz'),
                            ('bwrap.debian.tar.xz', 'bubblewrap_0.9.0-1ubuntu0.3.debian.tar.xz')]:
        row = digest(inputs / local) + ' ' + str((inputs / local).stat().st_size) + ' ' + upstream
        if row not in dsc:
            raise ValueError('Ubuntu corresponding-source .dsc mismatch: ' + local)
    work.mkdir(parents=True)
    output.mkdir(parents=True, mode=0o755)
    for name in ['bin', 'sources', 'evidence', 'notices']:
        (output / name).mkdir()
    # No global compiler/Cargo configuration or inherited build injection.
    env = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'TZ': 'UTC', 'SOURCE_DATE_EPOCH': '1750896000'}
    commands = []

    def run(command, cwd=work, build_env=None):
        command = [str(x) for x in command]
        print(' '.join(shlex.quote(x) for x in command), flush=True)
        result = subprocess.run(command, cwd=cwd, env=build_env or env, capture_output=True)
        stem = 'evidence/step-' + str(len(commands)).zfill(3)
        (output / (stem + '.stdout')).write_bytes(result.stdout)
        (output / (stem + '.stderr')).write_bytes(result.stderr)
        commands.append({'command': command, 'cwd': str(cwd), 'environment': build_env or env,
                         'exit': result.returncode, 'stdout': stem + '.stdout', 'stderr': stem + '.stderr'})
        (output / 'evidence/commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        result.check_returncode()
        return result.stdout.decode()

    sysroot, rust = work / 'sysroot', work / 'rust'
    sysroot.mkdir()
    for item in manifest['inputs']:
        if item['file'].endswith('.deb'):
            run(['dpkg-deb', '-x', inputs / item['file'], sysroot])
    # Ubuntu's packages use merged /usr. GCC's --sysroot also looks through /lib.
    (sysroot / 'lib').symlink_to('usr/lib')
    (sysroot / 'lib64').symlink_to('usr/lib64')
    gcc = sysroot / 'usr/bin/x86_64-linux-gnu-gcc-13'
    cc = sysroot / 'usr/bin/cc'
    cc_command = [gcc, '--sysroot=' + str(sysroot),
                  '-B' + str(sysroot / 'usr/lib/gcc/x86_64-linux-gnu/13') + '/',
                  '-B' + str(sysroot / 'usr/libexec/gcc/x86_64-linux-gnu/13') + '/']
    cc.write_text('#!/bin/sh\nexec ' + ' '.join(shlex.quote(str(x)) for x in cc_command) + ' "$@"\n')
    cc.chmod(0o755)
    env.update(PATH=str(sysroot / 'usr/bin') + ':' + str(rust / 'bin') + ':/usr/bin:/bin',
               LD_LIBRARY_PATH=str(sysroot / 'usr/lib/x86_64-linux-gnu'))
    for name in ['rustc.tar.xz', 'cargo.tar.xz', 'rust-std-gnu.tar.xz', 'rust-std.tar.xz']:
        stage = work / ('unpack-' + name)
        unpack(inputs / name, stage)
        run(['bash', next(stage.glob('*/install.sh')), '--prefix=' + str(rust), '--disable-ldconfig'])
    # Native PCRE2/jemalloc use headers and compiler built from matching musl 1.2.3.
    # Rust's official musl std supplies its pinned target CRT/libc for the final link.
    unpack(inputs / 'musl-source.tar.gz', work)
    musl_source, musl = work / 'musl-1.2.3', work / 'musl'
    env['CC'] = str(cc)
    run(['./configure', '--prefix=' + str(musl), '--disable-shared'], cwd=musl_source)
    run(['make', '-j4'], cwd=musl_source)
    run(['make', 'install'], cwd=musl_source)
    env['REALGCC'] = str(cc)
    unpack(inputs / 'ripgrep-source.tar.gz', work)
    rg = work / 'ripgrep-3a612f88b8'
    if (rg / 'Cargo.lock').read_bytes() != (inputs / 'ripgrep-Cargo.lock').read_bytes():
        raise ValueError('Source and pinned Cargo.lock differ')
    vendor = work / 'vendor'
    for item in manifest['inputs']:
        if not item['file'].startswith('crates/'):
            continue
        archive = inputs / item['file']
        unpack(archive, vendor)
        package = vendor / archive.name.removesuffix('.crate')
        files = {str(f.relative_to(package)): digest(f) for f in package.rglob('*') if f.is_file()}
        (package / '.cargo-checksum.json').write_text(json.dumps({'package': item['sha256'], 'files': files}))
    (rg / '.cargo/config.toml').write_text('[source.crates-io]\nreplace-with="local"\n'
        '[source.local]\ndirectory=' + json.dumps(str(vendor)) + '\n')
    env.update(CARGO_HOME=str(work / 'cargo-home'), CARGO_NET_OFFLINE='true',
        CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=str(cc),
        CC_x86_64_unknown_linux_musl=str(musl / 'bin/musl-gcc'),
        RUSTFLAGS='-C target-feature=+crt-static --remap-path-prefix=' + str(work) + '=/jimu-native-v2'
                  + ' -C link-arg=-Wl,-Map=' + str(output / 'evidence/rg.map'),
        CFLAGS='-ffile-prefix-map=' + str(work) + '=/jimu-native-v2',
        CARGO_PROFILE_RELEASE_DEBUG='0', CARGO_PROFILE_RELEASE_STRIP='symbols',
        RIPGREP_BUILD_GIT_HASH='3a612f88b8', PCRE2_SYS_STATIC='1', PCRE2_SYS_DISABLE_PKG_CONFIG='1')
    target = 'x86_64-unknown-linux-musl'
    cargo = ['cargo', '--frozen', '--offline', '--locked']
    # Cargo's artifact records identify what this target actually compiled, including features.
    artifacts = run([*cargo, 'build', '--release', '--target', target, '--features', 'pcre2',
                     '--bin', 'rg', '-j4', '--message-format=json'], cwd=rg)
    (output / 'evidence/cargo-artifacts.jsonl').write_text(artifacts)
    metadata = json.loads(run([*cargo, 'metadata', '--format-version=1'], cwd=rg))
    tree = run([*cargo, 'tree', '--target', target, '--features', 'pcre2', '--edges', 'normal,build'], cwd=rg)
    (output / 'evidence/cargo-tree.txt').write_text(tree)
    shutil.copyfile(rg / ('target/' + target + '/release/rg'), output / 'bin/rg')
    # Preserve both upstream and Ubuntu sources, applying the exact packaged patch series.
    unpack(inputs / 'bwrap.orig.tar.xz', work)
    bwrap = work / 'bubblewrap-0.9.0'
    unpack(inputs / 'bwrap.debian.tar.xz', bwrap)
    patches = []
    for line in (bwrap / 'debian/patches/series').read_text().splitlines():
        if line.strip() and not line.startswith('#'):
            patches.append(line)
            run(['patch', '-p1', '--batch', '-i', bwrap / 'debian/patches' / line], cwd=bwrap)
    # Equivalent executable/config inputs to upstream meson on this Linux host:
    # selinux auto-enabled (>=2.3), require_userns=false. No setuid installation.
    config = '#define PACKAGE_STRING "bubblewrap 0.9.0"\n#define HAVE_SELINUX 1\n#define HAVE_SELINUX_2_3 1\n'
    (bwrap / 'config.h').write_text(config)
    (output / 'evidence/bwrap-config.h').write_text(config)
    cflags = ['-O2', '-fPIE', '-pie', '-Wl,-z,relro,-z,now', '-static-libgcc',
              '-ffile-prefix-map=' + str(work) + '=/jimu-native-v2',
              '-Wl,-rpath-link,' + str(sysroot / 'usr/lib/x86_64-linux-gnu')]
    run([cc, *cflags, '-D_GNU_SOURCE', '-I.', '-Wl,-Map=' + str(output / 'evidence/bwrap.map'),
         '-o', output / 'bin/bwrap', 'bubblewrap.c', 'bind-mount.c', 'network.c', 'utils.c', '-lcap', '-lselinux'], cwd=bwrap)
    shutil.copyfile(source / 'launcher.c', output / 'launcher.c')
    run([cc, *cflags, '-Wall', '-Wextra', '-Werror', '-Wl,-Map=' + str(output / 'evidence/launcher.map'),
         '-o', output / 'bin/jimu-search-launcher', output / 'launcher.c'])
    for file in (output / 'bin').iterdir():
        file.chmod(0o755)
    inspections = {}
    for name in ['rg', 'bwrap', 'jimu-search-launcher']:
        binary = output / 'bin' / name
        headers = run(['readelf', '-lWd', binary])
        if name == 'rg' and ('INTERP' in headers or '(NEEDED)' in headers):
            raise ValueError('rg must have no ELF interpreter or dynamic dependencies')
        inspections[name] = {'sha256': digest(binary), 'elf': headers}
        (output / ('evidence/' + name + '-elf.txt')).write_text(headers)
    versions = {name: run([output / 'bin' / name, '--version']) for name in ['rg', 'bwrap']}
    # Exact target compilation inventory; uncompiled lock entries are only offline resolution inputs.
    packages = {p['id']: p for p in metadata['packages']}
    units, components, selected = [], [], {}
    for line in artifacts.splitlines():
        event = json.loads(line)
        if event['reason'] != 'compiler-artifact':
            continue
        package = packages[event['package_id']]
        scope = 'target' if any('/target/' + target + '/' in f for f in event['filenames']) else 'build-only'
        units.append({'package': package['name'], 'version': package['version'], 'scope': scope,
                      'features': event['features'], 'kind': event['target']['kind'],
                      'artifacts': {f: digest(Path(f)) for f in event['filenames']}})
        selected[(package['id'], scope)] = package
    for (package_id, scope), package in selected.items():
        components.append({'type': 'library', 'name': package['name'], 'version': package['version'],
            'licenses': [{'expression': package['license']}],
            'properties': [{'name': 'jimu:scope', 'value': scope}, {'name': 'jimu:cargo-id', 'value': package_id}]})
    (output / 'evidence/compilation-units.json').write_text(json.dumps(units, indent=2) + '\n')
    # All text comes from the same verified sources, not npm's umbrella MIT declaration.
    sections = []
    visited = set()
    for package in selected.values():
        if package['id'] in visited:
            continue
        visited.add(package['id'])
        folder = Path(package['manifest_path']).parent
        candidates = folder.iterdir() if folder == rg else folder.rglob('*')
        for file in sorted(candidates):
            if file.is_file() and re.match(r'^(LICENSE|COPYING|COPYRIGHT|UNLICENSE|NOTICE)([.\-_]|$)', file.name, re.I):
                sections.append('\n===== ' + package['name'] + ': ' + str(file.relative_to(folder)) + ' =====\n'
                                + file.read_text(errors='strict'))
    for archive_name, label in [('musl-source.tar.gz', 'musl 1.2.3'), ('bwrap.orig.tar.xz', 'bubblewrap 0.9.0')]:
        with tarfile.open(inputs / archive_name) as archive:
            for member in archive.getmembers():
                if member.isfile() and member.name.count('/') == 1 and re.match(r'^(COPYING|COPYRIGHT|LICENSE)', Path(member.name).name):
                    sections.append('\n===== ' + label + ': ' + member.name + ' =====\n' + archive.extractfile(member).read().decode())
    # Official Rust's library HTML is not a sufficient per-target external-crate
    # inventory. Bind the actual linked std archives to the matching source lock.
    link_map = (output / 'evidence/rg.map').read_text()
    std_paths = sorted(set(re.findall(r'(/[^\s()]+/rust/lib/rustlib/[^\s()]+[.]rlib)', link_map)))
    with tarfile.open(inputs / 'rust-src.tar.xz') as archive:
        lock_name = 'rust-src-1.88.0/rust-src/lib/rustlib/src/rust/library/Cargo.lock'
        std_lock = tomllib.loads(archive.extractfile(lock_name).read().decode())
    std_packages = {p['name'].replace('-', '_'): p for p in std_lock['package'] if p.get('checksum')}
    std_units = []
    used_std_inputs = set()
    for archive_path in std_paths:
        name = Path(archive_path).name[3:].rsplit('-', 1)[0]
        unit = {'archive': archive_path, 'sha256': digest(Path(archive_path)), 'crate': name}
        if name in std_packages:
            package = std_packages[name]
            file = 'rust-std-crates/' + package['name'] + '-' + package['version'] + '.crate'
            if digest(inputs / file) != package['checksum']:
                raise ValueError('Rust std corresponding-source mismatch: ' + file)
            used_std_inputs.add(file)
            unpack(inputs / file, work / 'std-vendor')
            folder = work / 'std-vendor' / Path(file).name.removesuffix('.crate')
            meta = tomllib.loads((folder / 'Cargo.toml').read_text())['package']
            unit.update(version=package['version'], source=file, sourceSha256=package['checksum'])
            components.append({'type': 'library', 'name': package['name'], 'version': package['version'],
                'licenses': [{'expression': meta['license']}],
                'hashes': [{'alg': 'SHA-256', 'content': package['checksum']}],
                'properties': [{'name': 'jimu:scope', 'value': 'target via official Rust std'},
                               {'name': 'jimu:linked-archive-sha256', 'value': unit['sha256']}]})
            for file_path in sorted(folder.rglob('*')):
                if file_path.is_file() and re.match(r'^(LICEN[CS]E|COPYING|COPYRIGHT|UNLICENSE|NOTICE)([.\-_]|$)', file_path.name, re.I):
                    sections.append('\n===== Rust std: ' + package['name'] + ': ' + str(file_path.relative_to(folder))
                                    + ' =====\n' + file_path.read_text())
        else:
            unit.update(version='1.88.0', source='rust-src.tar.xz (in-tree Rust library)')
        std_units.append(unit)
    if used_std_inputs != {i['file'] for i in manifest['inputs'] if i['file'].startswith('rust-std-crates/')}:
        raise ValueError('Rust std source inventory differs from actual linked archives')
    (output / 'evidence/rust-std-units.json').write_text(json.dumps(std_units, indent=2) + '\n')
    # rust-pcre2's crate omits the canonical PCRE2 licence file. Supply it from
    # the matching tag, while retaining differences from that tag as evidence.
    pcre_source = vendor / 'pcre2-sys-0.2.10/upstream'
    comparisons, changes = [], []
    with tarfile.open(inputs / 'pcre2-source.tar.gz') as archive:
        original = {m.name.split('/', 1)[1]: m for m in archive.getmembers() if m.isfile() and '/' in m.name}
        for name in ['LICENCE.md', 'COPYING']:
            sections.append('\n===== PCRE2 10.45: ' + name + ' =====\n' + archive.extractfile(original[name]).read().decode())
        for file in sorted(pcre_source.rglob('*')):
            if not file.is_file():
                continue
            name = str(file.relative_to(pcre_source))
            row = {'file': name, 'compiledSourceSha256': digest(file)}
            if name in original:
                before = archive.extractfile(original[name]).read()
                row.update(upstreamSha256=hashlib.sha256(before).hexdigest(), identical=before == file.read_bytes())
                if not row['identical']:
                    changes.extend(difflib.unified_diff(before.decode().splitlines(True), file.read_text().splitlines(True),
                                                       fromfile='PCRE2-10.45/' + name, tofile='pcre2-sys-0.2.10/' + name))
            else:
                row['origin'] = 'pcre2-sys crate: generated config/table/header or vendored SLJIT; exact source supplied in crate'
            comparisons.append(row)
    for name in ['src/pcre2_compile.c', 'deps/sljit/sljit_src/sljitLir.c']:
        text = (pcre_source / name).read_text()
        header = text.split('#ifdef HAVE_CONFIG_H', 1)[0] if name.startswith('src/') else text.split('*/', 1)[0] + '*/\n'
        sections.append('\n===== Actual vendored source copyright: ' + name + ' =====\n' + header)
    (output / 'evidence/pcre2-source-comparison.json').write_text(json.dumps(comparisons, indent=2) + '\n')
    (output / 'evidence/pcre2-vendor-diff.patch').write_text(''.join(changes))
    # Rust supplies the full library-specific third-party attribution for this official std build.
    for name in ['COPYRIGHT-library.html', 'LICENSE-APACHE', 'LICENSE-MIT']:
        original = (rust / 'share/doc/rust' / name) if name.endswith('.html') else next(
            (work / 'unpack-rustc.tar.xz').glob('*/' + name))
        shutil.copyfile(original, output / 'notices' / ('Rust-' + name))
    for name in ['libc6', 'libcap2', 'libselinux1', 'libpcre2-8-0', 'gcc-13-base', 'linux-libc-dev']:
        file = sysroot / 'usr/share/doc' / name / 'copyright'
        shutil.copyfile(file, output / 'notices' / (name + '-copyright'))
    shutil.copyfile(bwrap / 'debian/copyright', output / 'notices/bubblewrap-Ubuntu-copyright')
    shutil.copyfile(repo / 'docs/verification/XIAO-43/Harness-LICENSE.txt', output / 'notices/Harness-LICENSE.txt')
    (output / 'THIRD_PARTY_NOTICES.txt').write_text('Jimu native v2 actual compiled source notices.\n'
        'See notices/ for Rust library, Ubuntu/system and Harness attribution.\n' + ''.join(sections))
    components.extend([
        {'type': 'library', 'name': 'PCRE2', 'version': '10.45 (pcre2-sys 0.2.10 vendored source)',
         'licenses': [{'expression': 'BSD-3-Clause WITH PCRE2-exception'}]},
        {'type': 'library', 'name': 'SLJIT', 'version': '0.95 (pcre2-sys 0.2.10 vendored source)',
         'licenses': [{'expression': 'BSD-2-Clause'}]},
        {'type': 'library', 'name': 'jemalloc', 'version': '5.3.0-e13ca993', 'licenses': [{'expression': 'BSD-2-Clause'}]},
        {'type': 'library', 'name': 'Rust standard libraries', 'version': '1.88.0',
         'licenses': [{'expression': 'MIT OR Apache-2.0'}], 'properties': [{'name': 'jimu:attribution', 'value': 'notices/Rust-COPYRIGHT-library.html; actual rlib members in evidence/rg.map'}]},
        {'type': 'library', 'name': 'musl', 'version': '1.2.3', 'licenses': [{'expression': 'MIT'}]},
        {'type': 'library', 'name': 'LLVM libunwind/compiler builtins', 'version': 'Rust 1.88.0',
         'licenses': [{'expression': 'Apache-2.0 WITH LLVM-exception'}]},
        {'type': 'application', 'name': 'bubblewrap', 'version': '0.9.0-1ubuntu0.3+jimu.1',
         'licenses': [{'expression': 'LGPL-2.0-or-later'}],
         'hashes': [{'alg': 'SHA-256', 'content': digest(output / 'bin/bwrap')}]}])
    (output / 'sbom.cdx.json').write_text(json.dumps({'bomFormat': 'CycloneDX', 'specVersion': '1.6',
        'version': 1, 'metadata': {'component': {'type': 'application', 'name': 'Jimu native search', 'version': manifest['version']}},
        'components': components}, indent=2) + '\n')
    # Supply the complete pinned offline inputs, including toolchain and corresponding sources.
    for item in manifest['inputs']:
        target_path = output / 'sources' / item['file']
        target_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(inputs / item['file'], target_path)
    shutil.copyfile(source / 'v2/source-inputs.json', output / 'source-inputs.json')
    shutil.copyfile(source / 'v2/README.md', output / 'README.md')
    shutil.copyfile(Path(__file__), output / 'native_rebuild.py')
    runtime_env = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'}
    dependencies = {name: run(['ldd', output / 'bin' / name], build_env=runtime_env)
                    for name in ['bwrap', 'jimu-search-launcher']}
    dynamic_files = sorted(set(re.findall(r'(/[^\s()]+)', '\n'.join(dependencies.values()))))
    dynamic_hashes = {}
    for name in dynamic_files:
        actual = Path(name).resolve()
        # All non-kernel dynamic prerequisites in this build have matching pinned packages.
        if digest(actual) != digest(sysroot / name.lstrip('/')):
            raise ValueError('Build-host library differs from pinned sysroot: ' + name)
        dynamic_hashes[str(actual)] = digest(actual)
    receipt = {'version': manifest['version'], 'sourceInputsSha256': digest(source / 'v2/source-inputs.json'),
        'recipeSha256': digest(Path(__file__)), 'launcherSourceSha256': digest(source / 'launcher.c'),
        'launcher': str(output / 'bin/jimu-search-launcher'),
        'launcherSha256': digest(output / 'bin/jimu-search-launcher'), 'rg': str(output / 'bin/rg'),
        'bubblewrap': str(output / 'bin/bwrap'), 'binaries': inspections, 'versions': versions,
        'bubblewrapPatches': patches, 'commands': 'evidence/commands.json',
        'buildHostFiles': {str(Path(shutil.which(name, path=env['PATH'])).resolve()):
                           digest(Path(shutil.which(name, path=env['PATH'])).resolve())
                           for name in ['python3', 'ld', 'as', 'ar', 'ranlib', 'patch', 'perl', 'sed', 'awk', 'bash', 'dpkg-deb']},
        'hostPackages': run(['dpkg-query', '-W', 'python3', 'binutils', 'perl', 'sed', 'gawk', 'dpkg', 'patch',
                            'libgmp10', 'libmpfr6', 'libzstd1', 'zlib1g']),
        'dynamicDependencies': dependencies, 'dynamicPrerequisiteFiles': dynamic_hashes}
    receipt['files'] = {str(f.relative_to(output)): digest(f) for f in sorted(output.rglob('*')) if f.is_file()}
    (output / 'bundle.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({name: info['sha256'] for name, info in inspections.items()}))


if __name__ == '__main__':
    main()
