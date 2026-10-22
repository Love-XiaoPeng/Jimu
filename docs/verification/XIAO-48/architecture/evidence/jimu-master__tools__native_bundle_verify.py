"""Verify an offline v2 bundle against repository pins and all bound build/source files."""
import argparse
import json
from pathlib import Path

from native_rebuild import digest, verify_inputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--receipt-sha256', required=True,
                        help='Expected bundle.json digest from the independent handoff, not from the bundle itself')
    args = parser.parse_args()
    root = args.bundle.resolve()
    if digest(root / 'bundle.json') != args.receipt_sha256:
        raise ValueError('Native receipt identity mismatch')
    source = Path(__file__).resolve().parents[1] / 'workers/search/v2'
    pins = json.loads((source / 'pins.json').read_text())
    receipt = json.loads((root / 'bundle.json').read_text())
    if receipt['version'] != pins['version']:
        raise ValueError('Native version mismatch')
    for name, expected in pins['binaries'].items():
        if digest(root / 'bin' / name) != expected:
            raise ValueError('Native binary pin mismatch: ' + name)
    if digest(root / 'source-inputs.json') != digest(source / 'source-inputs.json'):
        raise ValueError('Native source inventory mismatch')
    verify_inputs(root / 'sources', json.loads((source / 'source-inputs.json').read_text()))
    for name, expected in receipt['files'].items():
        path = root / name
        if Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink():
            raise ValueError('Nonordinary bundle member: ' + name)
        if not path.is_file() or digest(path) != expected:
            raise ValueError('Native build/file identity mismatch: ' + name)
    recipe_hash = digest(Path(__file__).with_name('native_rebuild.py'))
    if digest(root / 'native_rebuild.py') != receipt['recipeSha256'] or recipe_hash != receipt['recipeSha256']:
        raise ValueError('Native recipe binding mismatch')
    launcher_hash = digest(source.parent / 'launcher.c')
    if digest(root / 'launcher.c') != receipt['launcherSourceSha256'] or launcher_hash != receipt['launcherSourceSha256']:
        raise ValueError('Native launcher source binding mismatch')
    print(json.dumps({'version': receipt['version'], 'verifiedFiles': len(receipt['files']),
                      'bundleSha256': digest(root / 'bundle.json'), 'binaries': pins['binaries']}))


if __name__ == '__main__':
    main()
