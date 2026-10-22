"""QA-01: real private proc mounts, one non-root product JVM across policy changes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time


def identity(pid):
    proc = Path('/proc') / str(pid)
    return [str(pid), proc.joinpath('stat').read_text().rsplit(') ', 1)[1].split()[19],
            Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
            str(proc.joinpath('ns/pid').stat().st_ino),
            str(Path('/proc/self/ns/pid').stat().st_ino)]


def inner(args):
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='jimu-qa01-'))
    os.chown(root, 65534, 65534)
    children = []
    transcript = bytearray()
    record = {'kill': args.kill, 'jarSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              'observations': []}
    try:
        sleeper = subprocess.Popen(['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups',
            'python3', '-c', 'import ctypes,time; assert ctypes.CDLL(None).prctl(4,0)==0; print("ready",flush=True); time.sleep(120)'],
            stdout=subprocess.PIPE, text=True)
        children.append(sleeper)
        assert sleeper.stdout.readline().strip() == 'ready'
        target = identity(sleeper.pid)
        denied = subprocess.Popen(['setpriv', '--reuid=65533', '--regid=65533', '--clear-groups', 'sleep', '120'])
        children.append(denied)
        bundle = json.loads(args.bundle.read_text())
        java = os.environ['JIMU_IT_JAVA']
        command = ['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', java,
            '-Dloader.path=' + str(args.test_classes.resolve()),
            '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.runtime.worker.NativeStopScenario',
            '-cp', str(args.jar.resolve()), 'org.springframework.boot.loader.launch.PropertiesLauncher',
            str(root), bundle['launcher'], bundle['launcherSha256'], bundle['rg'], bundle['bubblewrap'], *target]
        record.update(command=command, identity=target)
        product = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        children.append(product)

        def receive(expected):
            start = len(transcript)
            until = time.monotonic() + 30
            while expected.encode() not in transcript[start:]:
                assert time.monotonic() < until, 'product response timed out'
                if select.select([product.stdout], [], [], 1)[0]:
                    data = os.read(product.stdout.fileno(), 65536)
                    assert data, 'product exited before ' + expected
                    transcript.extend(data)

        def send(command, expected):
            product.stdin.write((command + '\n').encode())
            product.stdin.flush()
            receive(expected)

        receive('READY')
        for mode in [0, 1, 2, 0]:
            subprocess.run(['mount', '-o', 'remount,hidepid=' + str(mode), '/proc'], check=True)
            send('live', 'PASS live: no STOPPED, no refund')
            assert sleeper.poll() is None
            status = Path('/proc', str(sleeper.pid), 'status').read_text()
            assert 'State:\tS' in status and 'Uid:\t65534' in status
            record['observations'].append({'hidepid': mode, 'status': status,
                'mount': subprocess.check_output(['findmnt', '-n', '-o', 'OPTIONS', '/proc'], text=True)})
        send('denied ' + str(denied.pid), 'PASS EPERM unproven')
        sleeper.send_signal(signal.SIGKILL if args.kill == 'KILL' else signal.SIGTERM)
        sleeper.wait(timeout=10)
        send('exited', 'PASS exited: one STOPPED, no early refund')
        product.stdin.close()
        assert product.wait(timeout=15) == 0
        record['exit'] = 0
    finally:
        for child in reversed(children):
            if child.poll() is None:
                child.kill()
            child.wait(timeout=10)
        shutil.rmtree(root)
        record['cleanup'] = {'allOwnedChildrenExited': all(p.poll() is not None for p in children),
                             'temporaryRootRemoved': not root.exists()}
        (evidence / ('proc-' + args.kill + '.json')).write_text(json.dumps(record, indent=2))
        (evidence / ('proc-' + args.kill + '.log')).write_bytes(transcript)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--test-classes', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--inner', action='store_true')
    parser.add_argument('--kill', choices=['TERM', 'KILL'], default='TERM')
    args = parser.parse_args()
    if args.inner:
        inner(args)
        return
    assert os.getuid() == 0, 'private mount/PID namespace test supervisor requires root'
    assert not args.evidence.exists(), 'use a new evidence directory; preserve earlier attempts'
    args.evidence.mkdir(parents=True)
    before = subprocess.check_output(['findmnt', '-n', '-o', 'OPTIONS', '/proc'], text=True)
    for how in ['TERM', 'KILL']:
        command = ['unshare', '--mount', '--pid', '--fork', '--mount-proc', sys.executable,
                   str(Path(__file__).resolve()), *sys.argv[1:], '--inner', '--kill', how]
        subprocess.run(command, check=True, timeout=150)
    after = subprocess.check_output(['findmnt', '-n', '-o', 'OPTIONS', '/proc'], text=True)
    assert before == after, 'host proc mount changed'
    (args.evidence / 'host-mount.json').write_text(json.dumps({'before': before, 'after': after}))
    print('PASS QA-01 hidepid=0/1/2 dynamic, same UID nondumpable, EPERM, TERM/KILL, identity and ledger boundaries')


if __name__ == '__main__':
    main()
