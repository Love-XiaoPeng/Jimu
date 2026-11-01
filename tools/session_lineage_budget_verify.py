"""Isolated default-quota and fresh-JVM probes; optional full A03/nine-node run."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import shutil
import subprocess
import traceback

from session_lineage_verify import LineageExperiment


def default_quota(e):
    # Only lower an unused private tenant, before any history reservation exists.
    initial = e.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")
    e.assert_that(initial == '0', 'quota phase starts with no history held', held=initial)
    default = e.sql("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='jimu' AND TABLE_NAME='tenant_budget' AND COLUMN_NAME='history_storage_limit';")
    e.assert_that(default == '67108864', 'product schema default is exactly64MiB', default=default)
    e.sql("UPDATE tenant_budget SET history_storage_limit=67108864 WHERE tenant_id='tenant-a' AND history_storage_held=0;")
    roots = [e.task('default-quota-root-' + str(i), plan=[]) for i in range(7)]
    # A small independent three-slot response is enough for the remaining replay probe.
    replay = e.task('default-quota-replay', plan=[[('session_trace', {}),
        ('session_trace', {}), ('session_trace', {})]])
    roots.append(replay)
    budget = e.sql("SELECT history_storage_limit,history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")
    e.assert_that(budget == '67108864\t67108864', 'eight intact reservations reach default64MiB', budget=budget)
    e.stop(e.worker)
    before = quota_state(e)
    refusal = e.task('default-quota-ninth-refused', roots, roots[-1], plan=[], expected=429)
    after = quota_state(e)
    e.assert_that(refusal['code'] == 'SESSION_HISTORY_LIMIT' and before == after,
        'default64MiB ninth admission refuses atomically with no residual', code=refusal['code'])
    (e.evidence / 'default-quota.json').write_text(json.dumps({
        'initialHeld': initial, 'schemaDefault': default, 'tasks': roots,
        'before': before, 'after': after, 'refusal': refusal}, indent=2))
    return replay


def quota_state(e):
    return {table: e.sql('SELECT * FROM ' + table + ' ORDER BY 1,2;') for table in
        ('tenant_budget', 'task', 'reservation', 'event', 'task_session_history',
         'task_session_scope', 'task_session_scope_target', 'task_session_lineage')}


def recovery(e, task):
    # Both service JVMs are stopped before the independent recovery JVM is started.
    e.stop(e.worker)
    config = e.control['folder'] / 'runtime.properties'
    e.stop(e.control)
    slots = e.sql("SELECT slot,output_snapshot FROM invocation WHERE task_id='" + task + "' ORDER BY ordinal;")
    original = e.evidence / 'original-slots.bin'
    original.write_bytes(slots.encode('utf-8'))
    before = replay_state(e, task)
    classes = e.root / 'test-classes'
    shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
    command = [e.java, '-Xmx384m',
        '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionLineageRecoveryScenario',
        '-Dloader.path=' + str(classes), '-cp', str(e.jar),
        'org.springframework.boot.loader.launch.PropertiesLauncher', str(config), task]
    (e.evidence / 'recovery-command.json').write_text(json.dumps(command, indent=2))
    with (e.evidence / 'recovery.log').open('w') as log:
        run = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=40)
    output = (e.evidence / 'recovery.log').read_text()
    after = replay_state(e, task)
    current_slots = e.sql("SELECT slot,output_snapshot FROM invocation WHERE task_id='" + task + "' ORDER BY ordinal;")
    (e.evidence / 'recovery-db.json').write_text(json.dumps({'task': task, 'before': before,
        'after': after, 'originalSlotsSha256': hashlib.sha256(original.read_bytes()).hexdigest(),
        'slotsUnchanged': slots == current_slots, 'exit': run.returncode}, indent=2))
    e.assert_that(run.returncode == 0 and 'RECOVERY ' in output and slots == current_slots,
        'fresh JVM new real fence returns original bytes once', exit=run.returncode, tail=output[-6000:])
    e.assert_that(before['frozen'] == after['frozen'] and before['consumption'] == after['consumption']
        and before['slots'] == after['slots'], 'exact DB frozen cut slots and consumption unchanged', task=task)


def replay_state(e, task):
    where = " WHERE tenant_id='tenant-a' AND task_id='" + task + "'"
    return {
        'frozen': e.sql("SELECT * FROM step_session_scope WHERE tenant_id='tenant-a' AND caller_task_id='" + task + "' ORDER BY step_id;"),
        'slots': e.sql('SELECT * FROM invocation' + where + ' ORDER BY ordinal;'),
        'consumption': e.sql('SELECT tools_used FROM task' + where.replace('task_id=', 'id=') + ';') + '\n'
            + e.sql('SELECT * FROM reservation' + where + " AND category='TOOL' ORDER BY id;") + '\n'
            + e.sql('SELECT * FROM event' + where + " AND type='tool.completed' ORDER BY seq;"),
        'task': e.sql('SELECT state,fence,current_attempt_id,tools_used FROM task' + where.replace('task_id=', 'id=') + ';'),
        'attempts': e.sql('SELECT * FROM task_attempt' + where + ' ORDER BY fence,id;'),
        'history': e.sql('SELECT * FROM task_session_history' + where + ';')}


def verify(e, remaining_only=False):
    e.prepare()
    replay = default_quota(e)
    if remaining_only:
        recovery(e, replay)
        return
    # Raising the private limit is legal; held is never overwritten or cleared.
    e.sql("UPDATE tenant_budget SET history_storage_limit=536870912 WHERE tenant_id='tenant-a';")
    e.worker = e.start('worker-budget', 'worker')
    e.core(a03_only=True)
    chain = []
    for ordinal in range(8):
        chain.append(e.task('budget-chain-' + str(ordinal), chain, chain[-1] if chain else None, plan=[]))
    plan = [[('session_trace', {'session_id': chain[0]}),
             ('session_trace', {'session_id': chain[3]}), ('session_trace', {})]]
    end = e.task('budget-depth-eight', chain, chain[-1], plan=plan)
    outputs = e.outputs(end)
    e.assert_that(len(outputs) == 3 and len(outputs[2]['ancestors']) == 8
        and len(outputs[0]['descendants']) == 8
        and [node['depth'] for node in outputs[0]['descendants']] == list(range(1, 9)),
        'nine nodes depth eight three traces in one response', task=end, sources=chain)
    branch = e.task('budget-branch-nine', chain, chain[0], plan=plan)
    branch_outputs = e.outputs(branch)
    e.assert_that([node['node']['session_id'] for node in branch_outputs[0]['descendants']] == chain[1:] + [branch]
        and len(branch_outputs[2]['ancestors']) == 1, 'nine-node stable branch DFS', task=branch)
    for task in (end, branch):
        e.assert_that(e.sql("SELECT COUNT(DISTINCT step_id),COUNT(DISTINCT context_digest),COUNT(*) FROM invocation_session_lineage_trace WHERE caller_task_id='" + task + "';") == '1\t1\t3',
            'same response and frozen cut for nine-node three trace slots', task=task)
    recovery(e, end)
    (e.evidence / 'budget-tasks.json').write_text(json.dumps({'chain': chain, 'end': end, 'branch': branch}, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--remaining-only', action='store_true',
        help='Only default64MiB admission and independent small original-slot replay; no A03 rerun')
    args = p.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    e = LineageExperiment(args.jar, args.evidence)
    try:
        verify(e, remaining_only=args.remaining_only)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
