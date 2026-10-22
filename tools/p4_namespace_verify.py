"""R1: real live outer PID, same/foreign observer JVM after actual native readiness."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from p4_proc_verify import identity


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--test-classes', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    assert os.getuid() == 0, 'driver owns private PID/mount namespaces'
    assert not args.evidence.exists(), 'preserve prior evidence'
    args.evidence.mkdir(parents=True)
    bundle = json.loads(args.bundle.read_text())
    before = subprocess.check_output(['findmnt', '-n', '-o', 'OPTIONS', '/proc'], text=True)
    for mode in ['same', 'foreign']:
        fixture = Path(tempfile.mkdtemp(prefix='jimu-r1-'))
        os.chown(fixture, 65534, 65534)
        target = subprocess.Popen(['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', 'sleep', '90'])
        record = {'mode': mode, 'jarSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest()}
        try:
            recorded = identity(target.pid)
            command = ['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', os.environ['JIMU_IT_JAVA'],
                '-Dloader.path=' + str(args.test_classes.resolve()),
                '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.runtime.worker.NativeStopScenario',
                '-cp', str(args.jar.resolve()), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                str(fixture), bundle['launcher'], bundle['launcherSha256'], bundle['rg'], bundle['bubblewrap'], *recorded]
            if mode == 'foreign':
                # PID1 remains a parent, as in the supported non-root proc fixture.
                command = ['unshare', '--mount', '--pid', '--fork', '--mount-proc', 'python3', '-c',
                    'import subprocess,sys;sys.exit(subprocess.call(sys.argv[1:]))', *command]
            result = subprocess.run(command, input=('live' if mode == 'same' else 'foreign') + '\n',
                capture_output=True, text=True, timeout=30)
            status = Path('/proc', str(target.pid), 'status').read_text()
            record.update(command=command, identity=recorded, exit=result.returncode, stdout=result.stdout,
                stderr=result.stderr, targetStillAlive=target.poll() is None, targetStatus=status)
            assert result.returncode == 0 and 'READY' in result.stdout, record
            assert target.poll() is None and 'State:\tS' in status, record
            assert ('PASS live: no STOPPED, no refund' if mode == 'same' else
                'PASS foreign observer: no signal, no STOPPED, no refund') in result.stdout, record
        finally:
            if target.poll() is None:
                target.kill()
            target.wait(timeout=10)
            shutil.rmtree(fixture)
            record['cleanup'] = {'targetReaped': target.poll() is not None, 'fixtureRemoved': not fixture.exists()}
            (args.evidence / (mode + '.json')).write_text(json.dumps(record, indent=2))
    after = subprocess.check_output(['findmnt', '-n', '-o', 'OPTIONS', '/proc'], text=True)
    assert before == after, 'host proc mount changed'
    (args.evidence / 'host-mount.json').write_text(json.dumps({'before': before, 'after': after}))
    print('PASS R1 same/foreign observer, live outer target, readiness, supervisor STOPPED/refund negatives')


if __name__ == '__main__':
    main()
