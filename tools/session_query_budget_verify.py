"""Private foreground MySQL fault probes, before the single A03 acceptance run."""
import argparse
import json
from pathlib import Path
import signal
import shutil
import subprocess
import traceback

from session_lineage_verify import LineageExperiment


def verify(e):
    e.prepare()
    e.stop(e.worker)
    goal = 'query-budget-fault-caller'
    e.fixture.plans[goal] = []
    status, body = e.http('POST', '/api/session-trace/tasks',
        {'goal': goal, 'history_session_ids': []}, key='query-budget-fault-caller')
    e.assert_that(status == 202, 'isolated queued fault caller', status=status, body=body)
    config = e.control['folder'] / 'runtime.properties'
    e.stop(e.control)
    classes = e.root / 'test-classes'
    shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
    command = [e.java, '-Xmx384m',
        '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionQueryBudgetScenario',
        '-Dloader.path=' + str(classes), '-cp', str(e.jar),
        'org.springframework.boot.loader.launch.PropertiesLauncher', str(config), body['taskId']]
    (e.evidence / 'driver-command.json').write_text(json.dumps(command, indent=2))
    with (e.evidence / 'driver.log').open('w') as log:
        driver = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=160)
    output = (e.evidence / 'driver.log').read_text()
    records = [json.loads(line[6:]) for line in output.splitlines() if line.startswith('FAULT ')]
    (e.evidence / 'fault-results.json').write_text(json.dumps(records, indent=2))
    e.assert_that(driver.returncode == 0 and 'COMPLETE exact real write-point rollback probes' in output,
        'real prepare M1 three-slot M2 write failures rollback and ordinary usage refusal commits',
        exit=driver.returncode, trials=len(records), tail=output[-6000:])


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = p.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    e = LineageExperiment(args.jar, args.evidence)
    try:
        verify(e)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
