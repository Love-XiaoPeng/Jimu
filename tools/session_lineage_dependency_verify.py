"""One foreground private MySQL batch for dependency writes; never runs A03/model/worker."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import time
import traceback

from p2_verify import Experiment


def main():
    started = time.monotonic()
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()

    def expired(signum, frame):
        raise RuntimeError('private dependency batch absolute 180s boundary')

    signal.signal(signal.SIGTERM, expired)
    signal.signal(signal.SIGALRM, expired)
    signal.alarm(150)  # business stop leaves 30s inside the same 180s cleanup budget
    experiment = Experiment(args.jar, args.evidence)
    phases = []
    failure = None
    try:
        experiment.prepare()
        phases.append({'phase': 'private-mysql-migration-control', 'seconds': time.monotonic()-started})
        experiment.stop(experiment.control)
        experiment.sql("GRANT INSERT,UPDATE ON jimu.task_session_history TO 'runtime'@'%';")
        package = Path('io/github/lovexiaopeng/jimu/adapters/persistence')
        classes = experiment.root / 'probe-classes'
        destination = classes / package
        destination.mkdir(parents=True)
        source = Path(__file__).resolve().parents[1] / 'target/test-classes' / package
        copied = []
        for file in source.glob('SessionDependencyScenario*.class'):
            shutil.copyfile(file, destination / file.name)
            copied.append({'name': file.name, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
        assert copied
        command = [experiment.java, '-Xmx384m',
            '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionDependencyScenario',
            '-Dloader.path=' + str(classes), '-cp', str(experiment.jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher',
            str(experiment.control['folder'] / 'runtime.properties')]
        with (experiment.evidence / 'dependency-scenario.log').open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                    timeout=min(50, max(1, 140-(time.monotonic()-started))))
        experiment.assert_that(result.returncode == 0, 'actual Mapper dependency scenario', exit=result.returncode)
        phases.append({'phase': 'mapper-scenario', 'seconds': time.monotonic()-started, 'classes': copied})
    except BaseException:
        failure = traceback.format_exc()
        (experiment.evidence / 'failure.txt').write_text(failure)
    finally:
        signal.alarm(max(1, int(180-(time.monotonic()-started))))
        experiment.cleanup()
        phases.append({'phase': 'cleanup-complete', 'seconds': time.monotonic()-started})
        signal.alarm(0)
        (experiment.evidence / 'phases.json').write_text(json.dumps(phases, indent=2))
    assert time.monotonic()-started < 180, 'entire batch including cleanup exceeded 180s'
    if failure:
        raise RuntimeError(failure)


if __name__ == '__main__':
    main()
