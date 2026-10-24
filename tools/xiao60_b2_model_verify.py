"""B2 model cutover: exact 35f/current driver, private MySQL and controlled loopback provider only."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import threading
import traceback

from p2_verify import Experiment
from p3_model_verify import Models, config


def verify(e, old_jar):
    e.prepare()
    e.assert_that(hashlib.sha256(old_jar.read_bytes()).hexdigest() ==
        'ce8e8966643c00ef365b1b80549f5959531f9a90840bf911a9766ecaa885fea0', 'exact 35f8c3e continuation binary')
    (e.evidence / 'database.txt').write_text(e.sql('SELECT VERSION(),@@server_uuid,DATABASE(); SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;'))
    e.stop(e.control)
    e.sql('UPDATE tenant_budget SET model_limit=1000,token_limit=16777216,cost_limit=100000000;')
    classes = e.root / 'model-test-classes'
    shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
    # The unchanged accepted drivers cover actual free snapshot/dispatch/response/failure and recovery.
    for driver, expected in [('CoreSettlementScenario', 8), ('CoreWorkflowScenario', 22)]:
        results = {}
        for label, binary in [('accepted', old_jar), ('current', e.jar)]:
            result = subprocess.run([e.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.' + driver,
                '-Dloader.path=' + str(classes), '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                str(e.control['folder'] / 'runtime.properties'), label], text=True, capture_output=True, timeout=120)
            (e.evidence / (driver + '-' + label + '.log')).write_text(result.stdout + result.stderr)
            e.assert_that(result.returncode == 0, driver + ' actual product ' + label, exit=result.returncode)
            results[label] = [line for line in result.stdout.splitlines() if line.startswith('RESULT ')]
        e.assert_that(len(results['current']) == expected and results['accepted'] == results['current'],
            'exact old/new free model and affected workflow snapshots ' + driver)
        (e.evidence / (driver + '-differential.json')).write_text(json.dumps(results, indent=2))

    server = Models(e.fixture.origin)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    try:
        settings = config({'a': server}, paid=True, run_id='model-loopback')
        settings.update({'jimu.model.profiles.a.price.input-micros-per-million': '1000000',
                         'jimu.model.profiles.a.price.output-micros-per-million': '2000000',
                         'jimu.model.profiles.a.price.fixed-micros': '100'})
        e.control = e.start('model-config-control', 'control', extra=settings)
        e.stop(e.control)
        results = {}
        for label, binary in [('accepted', old_jar), ('current', e.jar)]:
            e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
            try:
                result = subprocess.run([e.java,
                    '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ModelDispatchScenario',
                    '-Dloader.path=' + str(classes), '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                    str(e.control['folder'] / 'runtime.properties'), label, str(e.evidence / (label + '-transactions.json'))],
                    text=True, capture_output=True, timeout=180)
                (e.evidence / ('paid-' + label + '.log')).write_text(result.stdout + result.stderr)
                e.assert_that(result.returncode == 0 and 'PASS model dispatch differential' in result.stdout,
                    'actual synthetic paid model branch driver ' + label, exit=result.returncode)
                results[label] = [line for line in result.stdout.splitlines() if line.startswith('RESULT ')]
            finally:
                e.sql("SET GLOBAL general_log='OFF';")
                (e.evidence / (label + '-runtime-sql.tsv')).write_text(e.sql(
                    "SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
                e.sql('TRUNCATE TABLE mysql.general_log;')
        e.assert_that(len(results['current']) == 15 and results['accepted'] == results['current'],
            '15 exact old/new paid ledger snapshots: actual/refund/UNKNOWN/bounds/proof/refusal/tenant/fence/two-connection race/atomic rollback')
        e.assert_that(not server.calls, 'synthetic direct ledger branch driver made zero provider calls')
        (e.evidence / 'paid-differential.json').write_text(json.dumps(results, indent=2))

        # Real product worker paths to an auth-free local substitute, no real key or external billing.
        for case in ['known', 'unknown', 'overrun', 'recovery']:
            run_id = 'loopback-' + case
            e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','" + run_id + "');")
            cfg = dict(settings, **{'jimu.model.evaluation-run-id': run_id})
            e.control = e.start('model-control-' + case, 'control', extra=cfg)
            goal = 'cost-' + ('known-recovery' if case == 'recovery' else case)
            task = e.submit(goal)
            one = e.start('model-worker-' + case + '-one', 'worker', 'M2' if case == 'recovery' else '', extra=cfg)
            if case == 'recovery':
                e.barrier(one)
                e.stop(one, kill=True)
                e.expire(task)
                one = e.start('model-worker-recovered', 'worker', extra=cfg)
            two = e.start('model-worker-' + case + '-two', 'worker', extra=cfg)
            e.state(task, 'SUCCEEDED' if case in ['known', 'recovery'] else 'FAILED')
            e.stop(one)
            e.stop(two)
            calls = [c for c in server.calls if c['goal'] == goal]
            e.assert_that(len(calls) == 1 and calls[0]['authorizationAbsent'], 'two workers/recovery never replay model call ' + case)
            expected = 'RESPONDED\t300\tKNOWN' if case in ['known', 'recovery'] else ('USAGE_UNKNOWN\tNULL\tUNKNOWN' if case == 'unknown' else 'BOUND_EXCEEDED\t9200\tKNOWN')
            e.assert_that(e.sql("SELECT state,actual_cost,cost_quality FROM model_dispatch WHERE tenant_id='tenant-a' AND task_id='" + task + "';") == expected,
                'actual local provider billing persisted ' + case)
            e.stop(e.control)
            if case in ['unknown', 'overrun']:
                # Persistence must survive a control restart; stopped runs cannot request again.
                e.control = e.start('model-control-restart-' + case, 'control', extra=cfg)
                following = e.submit(goal + '-after-restart')
                one = e.start('model-worker-after-' + case, 'worker', extra=cfg)
                e.state(following, 'FAILED')
                e.stop(one)
                e.assert_that(not any(c['goal'] == goal + '-after-restart' for c in server.calls), 'persisted stopped run blocks subsequent model request ' + case)
                e.stop(e.control)
        for table in ['evaluation_budget', 'model_dispatch']:
            (e.evidence / (table + '.tsv')).write_text(e.sql('SELECT * FROM ' + table + ';'))
    finally:
        (e.evidence / 'provider-calls.json').write_text(json.dumps(server.calls, indent=2))
        server.shutdown()
        server.server_close()
        thread.join(5)
        e.assert_that(not thread.is_alive(), 'controlled provider stopped')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--old-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    e = Experiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
