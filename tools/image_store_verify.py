"""Real POSIX image namespace checks, foreground child with finite timeout and cleanup."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('jar', type=Path)
    parser.add_argument('test_classes', type=Path)
    parser.add_argument('evidence', type=Path)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='jimu-image-store-it-'))
    receipt = {'jarSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest()}
    try:
        with zipfile.ZipFile(args.jar) as jar:
            for name in jar.namelist():
                if name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                    jar.extract(name, root)
        shutil.copytree(args.test_classes / 'io/github/lovexiaopeng/jimu/adapters/storage', root / 'test/io/github/lovexiaopeng/jimu/adapters/storage')
        shutil.copytree(args.test_classes / 'io/github/lovexiaopeng/jimu/adapters/runtime/media', root / 'test/io/github/lovexiaopeng/jimu/adapters/runtime/media')
        java = os.environ.get('JIMU_IT_JAVA') or shutil.which('java')
        receipt['java'] = subprocess.run([java, '-version'], text=True, capture_output=True, check=True).stderr
        os.chown(root, 65534, 65534)
        receipt['uid'] = 65534
        command = ['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', java, '-Xmx256m', '-cp', ':'.join(str(root / p) for p in ('test', 'BOOT-INF/classes', 'BOOT-INF/lib/*')),
                   'io.github.lovexiaopeng.jimu.adapters.storage.ImageStoreScenario', str(root)]
        with (args.evidence / 'store.log').open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=60)
        receipt['exit'] = result.returncode
        if result.returncode == 0:
            command[-2:] = ['io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageRuntimeScenario']
            with (args.evidence / 'decoder.log').open('w') as log:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=60)
            receipt['decoderExit'] = result.returncode
            print((args.evidence / 'decoder.log').read_text(), flush=True)
        print((args.evidence / 'store.log').read_text(), flush=True)
        return result.returncode
    finally:
        shutil.rmtree(root)
        receipt['nativeRootRemoved'] = not root.exists()
        (args.evidence / 'cleanup.json').write_text(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    raise SystemExit(main())
