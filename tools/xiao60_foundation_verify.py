"""B0/B1 only: private schema22, actual product assembly, mixed API/Mapper transactions and rollback binary."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import traceback

from p2_verify import Experiment


def verify(experiment, old_jar):
    experiment.prepare()
    evidence = experiment.evidence
    experiment.assert_that(hashlib.sha256(old_jar.read_bytes()).hexdigest() ==
        '4e0a485e997354197f625999d0836b7c75087666bedd678a8f90fe20aab6cb5e', 'exact accepted old binary')
    (evidence / 'database.txt').write_text(experiment.sql(
        'SELECT VERSION(),@@server_uuid,DATABASE(); SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;'))
    classes = experiment.root / 'foundation-test-classes'
    shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
    scenario = subprocess.run([experiment.java,
        '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.PersistenceFoundationScenario',
        '-Dloader.path=' + str(classes), '-cp', str(experiment.jar),
        'org.springframework.boot.loader.launch.PropertiesLauncher',
        str(experiment.control['folder'] / 'runtime.properties')], text=True, capture_output=True, timeout=50)
    (evidence / 'transactions.log').write_text(scenario.stdout + scenario.stderr)
    experiment.assert_that(scenario.returncode == 0 and 'PASS foundation MySQL' in scenario.stdout,
        'real MySQL UoW/BaseMapper probe', exit=scenario.returncode)
    core = {}
    for tag, binary in [('accepted', old_jar), ('current', experiment.jar)]:
        result = subprocess.run([experiment.java,
            '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.CoreSettlementScenario',
            '-Dloader.path=' + str(classes), '-cp', str(binary),
            'org.springframework.boot.loader.launch.PropertiesLauncher',
            str(experiment.control['folder'] / 'runtime.properties'), tag], text=True, capture_output=True, timeout=50)
        (evidence / ('core-' + tag + '.log')).write_text(result.stdout + result.stderr)
        experiment.assert_that(result.returncode == 0 and 'PASS core settlement differential' in result.stdout,
            'actual ledger core settlement ' + tag, exit=result.returncode)
        core[tag] = [line for line in result.stdout.splitlines() if line.startswith('RESULT ')]
    experiment.assert_that(len(core['accepted']) == 8 and core['accepted'] == core['current'],
        'eight exact old/new ledger settlement snapshots: tokens/UNKNOWN/proof commit/missing receipt/CAS/fence/recovery/cancel/receipts')
    (evidence / 'core-differential.json').write_text(json.dumps(core, indent=2))
    workflows = {}
    for tag, binary in [('accepted', old_jar), ('current', experiment.jar)]:
        if tag == 'current':
            experiment.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
        try:
            result = subprocess.run([experiment.java,
                '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.CoreWorkflowScenario',
                '-Dloader.path=' + str(classes), '-cp', str(binary),
                'org.springframework.boot.loader.launch.PropertiesLauncher',
                str(experiment.control['folder'] / 'runtime.properties'), tag], text=True, capture_output=True, timeout=100)
            (evidence / ('workflow-' + tag + '.log')).write_text(result.stdout + result.stderr)
            experiment.assert_that(result.returncode == 0 and 'PASS core workflow differential' in result.stdout,
                'actual core workflow ' + tag, exit=result.returncode)
            workflows[tag] = [line for line in result.stdout.splitlines() if line.startswith('RESULT ')]
        finally:
            if tag == 'current':
                experiment.sql("SET GLOBAL general_log='OFF';")
                (evidence / 'workflow-runtime-sql.tsv').write_text(experiment.sql(
                    "SELECT event_time,thread_id,command_type,argument FROM mysql.general_log "
                    "WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
    experiment.assert_that(len(workflows['accepted']) == 22 and workflows['accepted'] == workflows['current'],
        '22 exact accepted/new checkpoint/prepare/dispatch/responded/admit/success/finish and concurrent snapshots')
    (evidence / 'workflow-differential.json').write_text(json.dumps(workflows, indent=2))
    current_jar = experiment.jar
    task = experiment.submit('b1-from-new')
    old_current = experiment.http('GET', '/api/tasks/' + task)
    checksums = experiment.sql('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;')
    experiment.stop(experiment.control)
    # No mixed workers: both product binaries share the private schema only sequentially.
    experiment.jar = old_jar
    experiment.control = experiment.start('old-control', 'control')
    experiment.assert_that(experiment.http('GET', '/api/tasks/' + task) == old_current,
        'accepted old binary reads exact new task API')
    experiment.assert_that(experiment.submit('b1-from-new') == task, 'old binary replays same idempotent task')
    legacy = experiment.submit('b1-from-old')
    old_legacy = experiment.http('GET', '/api/tasks/' + legacy)
    experiment.stop(experiment.control)
    experiment.jar = current_jar
    experiment.control = experiment.start('new-control', 'control')
    experiment.assert_that(experiment.http('GET', '/api/tasks/' + legacy) == old_legacy,
        'new binary reads exact old task API')
    experiment.assert_that(experiment.sql('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;') == checksums,
        'binary switch preserves all V1-V22 checksums')
    with concurrent.futures.ThreadPoolExecutor(4) as executor:
        duplicates = list(executor.map(lambda _: experiment.http('POST', '/api/tasks',
            {'goal': 'b1-from-new'}, key='b1-from-new'), range(4)))
    experiment.assert_that(all(status == 202 and value['taskId'] == task for status, value in duplicates),
        'new UoW concurrent duplicate admission remains one task')
    experiment.assert_that(experiment.http('GET', '/api/tasks/' + task, subject='b')[0] == 404,
        'cross tenant task rejected')
    experiment.assert_that(experiment.http('GET', '/api/tasks/' + task, subject='c')[0] == 404,
        'other owner task rejected')
    one = experiment.start('worker-one', 'worker')
    two = experiment.start('worker-two', 'worker')
    for item in (task, legacy):
        view = experiment.state(item, 'SUCCEEDED')
        status, result = experiment.http('GET', '/api/artifacts/' + view['artifacts'][0]['artifactId'] + '/download')
        experiment.assert_that(status == 200 and hashlib.sha256(result.encode()).hexdigest() == view['artifacts'][0]['sha256'],
            'new ArtifactService actual download matches frozen artifact hash')
        experiment.assert_that(experiment.http('GET', '/api/artifacts/' + view['artifacts'][0]['artifactId'] + '/download', subject='b')[0] == 404,
            'cross tenant download rejected')
    experiment.stop(two)
    experiment.stop(one)
    status, events = experiment.http('GET', '/api/tasks/' + task + '/events')
    experiment.assert_that(status == 200 and 'task.completed' in events, 'TaskService owner check preserves SSE replay')
    cancelled = experiment.submit('b1-cancel-queued')
    status, value = experiment.http('POST', '/api/tasks/' + cancelled + '/cancel')
    experiment.assert_that(status == 200 and value['state'] == 'CANCELLED', 'TaskService cancel preserves terminal response')
    experiment.assert_that(experiment.sql("SELECT COUNT(*) FROM task WHERE id IN ('" + task + "','" + legacy + "');") == '2',
        'two workers produce no duplicate tasks')
    (evidence / 'business.json').write_text(json.dumps({'newTask': task, 'oldTask': legacy,
        'scope': 'B0/B1 assembly and changed admission/download/UoW only; no B2-B5 acceptance'}, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--old-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    experiment = Experiment(args.jar, args.evidence)
    try:
        verify(experiment, args.old_jar.resolve())
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()


if __name__ == '__main__':
    main()
