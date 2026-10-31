"""Bounded, foreground repair probes; a failed A03 is never retried."""
import argparse
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback
from session_lineage_verify import LineageExperiment
from session_lineage_budget_verify import default_quota, quota_state, recovery


def scenario(e, name, arguments, timeout=40):
    classes = e.root / 'test-classes'
    package = Path('io/github/lovexiaopeng/jimu/adapters/persistence')
    destination = classes / package
    destination.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).resolve().parents[1] / 'target/test-classes' / package
    for file in source.glob(name + '*.class'):
        shutil.copyfile(file, destination / file.name)
    command = [e.java, '-Xmx384m',
        '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.' + name,
        '-Dloader.path=' + str(classes), '-cp', str(e.jar),
        'org.springframework.boot.loader.launch.PropertiesLauncher',
        str(e.control['folder'] / 'runtime.properties'), *arguments]
    with (e.evidence / (name + '.log')).open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    e.assert_that(result.returncode == 0, name, exit=result.returncode)


def admission(e):
    chain = []
    for ordinal in range(8):
        task = e.task('repair-chain-' + str(ordinal), chain,
                      chain[-1] if chain else None, plan=[])
        chain.append(task)
        (e.evidence / 'admission-tasks.json').write_text(json.dumps(chain, indent=2))
    plan = [[('session_trace', {'session_id': chain[0]}),
             ('session_trace', {'session_id': chain[3]}), ('session_trace', {})]]
    end = e.task('repair-chain-8', chain, chain[-1], plan=plan)
    outputs = e.outputs(end)
    e.assert_that(len(outputs) == 3 and len(outputs[2]['ancestors']) == 8
        and [node['depth'] for node in outputs[0]['descendants']] == list(range(1, 9)),
        'nine-node depth eight and same-response three trace', task=end)
    branch = e.task('repair-branch', chain, chain[0], plan=plan)
    output = e.outputs(branch)
    e.assert_that([node['node']['session_id'] for node in output[0]['descendants']]
        == chain[1:] + [branch] and len(output[2]['ancestors']) == 1,
        'nine-node stable branch', task=branch)
    e.stop(e.worker)
    # The current-state admission guard legitimately touches task.updated_at.
    before = quota_state(e)
    stable_sql = 'SELECT id,request_digest,state,current_attempt_id,fence,models_used,tools_used FROM task ORDER BY id;'
    stable = e.sql(stable_sql)
    replay = e.task('repair-chain-8', list(reversed(chain)), chain[-1], plan=plan)
    after = quota_state(e)
    e.assert_that(replay == end and all(before[k] == after[k] for k in before if k != 'task')
        and stable == e.sql(stable_sql),
        'idempotent replay preserves request state counters history reservation and events')
    return end


def verify(e, focused=False):
    e.prepare()
    if focused:
        replay = e.task('repair-diagnostic-root', plan=[])
        e.stop(e.worker)
    else:
        replay = default_quota(e)
    e.stop(e.control)
    scenario(e, 'SessionOwnerPhaseScenario', [replay], timeout=25)
    e.sql("UPDATE tenant_budget SET history_storage_limit=536870912 WHERE tenant_id='tenant-a';")
    e.control = e.start('control-repair', 'control')
    e.worker = e.start('worker-repair', 'worker')
    first = None
    end = None
    try:
        e.core(a03_only=True)
        end = admission(e)
    except BaseException as error:
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            raise
        first = error
        (e.evidence / 'first-functional-failure.txt').write_text(traceback.format_exc())
    e.stop(e.worker)
    e.stop(e.control)
    ids = e.sql("SELECT id FROM task WHERE tenant_id='tenant-a' AND state='SUCCEEDED' ORDER BY created_at,id;").splitlines()
    # These independent diagnostics remain useful after a functional failure.
    for name, arguments in (
        ('SessionIndependentBalanceScenario', [','.join(ids)]),
        ('SessionQueryBudgetScenario', [','.join(ids[:4]), 'admission', ids[0]])):
        try:
            scenario(e, name, arguments)
        except BaseException as error:
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                raise
            (e.evidence / (name + '-failure.txt')).write_text(traceback.format_exc())
            if first is None:
                first = error
    if first is None:
        # Preserve the existing diagnostic classes and let recovery make its own copy.
        (e.root / 'test-classes').rename(e.root / 'phase-test-classes')
        recovery(e, end or replay)
    if first is not None:
        raise first


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--focused', action='store_true',
        help='Skip the separately completed default quota control; never retries a previous A03')
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('bounded deadline')))
    e = LineageExperiment(args.jar, args.evidence)
    try:
        verify(e, focused=args.focused)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
