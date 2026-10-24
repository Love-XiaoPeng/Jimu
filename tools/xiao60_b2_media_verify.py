"""Directed media lock regression. Synthetic paid ledger seeds, private MySQL, loopback only.

Paid vision prepare remains rejected: seeded admitted states are not proof of live paid reachability.
The unchanged driver runs against f5fc481 and the current packaged binary.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import traceback

from p4_image_verify import ImageExperiment, png


def verify(e, old_jar, baseline_only):
    e.prepare()
    e.stop(e.control)
    free = 'jimu.model.profiles.image-local.'
    paid = 'jimu.model.profiles.image-paid.'
    e.common.update({paid + key[len(free):]: value for key, value in list(e.common.items()) if key.startswith(free)})
    e.common.update({paid + 'profile': 'image-paid', paid + 'paid': 'true', paid + 'max-output-tokens': '512',
                     paid + 'price.version': 'synthetic-media-v1', paid + 'price.currency': 'CNY',
                     paid + 'price.input-micros-per-million': '0', paid + 'price.output-micros-per-million': '0',
                     paid + 'price.fixed-micros': '50000', paid + 'price.input-token-upper': '65536',
                     paid + 'price.bound-evidence': 'synthetic-private-only-not-live-vision-proof',
                     'jimu.model.tenant-profiles.synthetic-paid': 'image-paid',
                     'jimu.model.paid-dispatch-enabled': 'true', 'jimu.model.evaluation-run-id': 'media-run'})
    e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','media-run'),('tenant-b','media-run');")
    e.sql("GRANT PROCESS ON *.* TO 'runtime'@'%'; GRANT SELECT ON performance_schema.* TO 'runtime'@'%';")
    e.sql("UPDATE tenant_budget SET model_limit=1000,token_limit=16777216,storage_limit=1073741824;")
    e.control = e.start('media-control', 'control')
    body = {'goal': 'image-call', 'capabilityIds': ['read', 'read_image', 'compose_report', 'present'],
            'images': [{'filePath': 'inputs/images/sample.png', 'dataBase64': base64.b64encode(png()).decode()}]}
    status, view = e.http('POST', '/api/tasks', body, key='media-local-smoke')
    e.assert_that(status == 202, 'free image admitted via real HTTP', response=view)
    worker = e.start('media-smoke-worker', 'worker')
    done = e.state(view['taskId'], 'SUCCEEDED')
    e.stop(worker)
    e.assert_that(len(e.fixture.requests) == 5 and any(r['media'] for r in e.fixture.requests),
                  'controlled loopback provider received real normalized image and completed report')
    before = len(e.fixture.requests)
    settings = {'jimu.model.default-profile-ref': 'image-paid'}
    paid_control = e.start('paid-control', 'control', extra=settings)
    status, paid_view = e.http('POST', '/api/tasks', dict(body, goal='paid-image-refusal'), key='paid-image-refusal', instance=paid_control)
    e.assert_that(status == 202, 'synthetic paid profile passes real admission', response=paid_view)
    paid_worker = e.start('paid-worker', 'worker', extra=settings)
    failed = e.state(paid_view['taskId'], 'FAILED')
    e.stop(paid_worker)
    e.stop(paid_control)
    e.assert_that(failed['code'] == 'MODEL_INPUT_BOUND_UNAVAILABLE' and len(e.fixture.requests) == before,
                  'paid vision remains refused before provider dispatch', response=failed)
    # The directed JDBC drivers own all live work from here; exclude maintenance interference.
    e.stop(e.control)
    classes = e.root / 'media-test-classes'
    shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
    image = e.root / 'sample.png'
    image.write_bytes(png())
    e.assert_that(hashlib.sha256(old_jar.read_bytes()).hexdigest() ==
                  '8ca6e5b032a6b771430fc9394f18c05dc4bfe1dbff1403b969c16021b6bcd585', 'exact f5fc481 baseline binary')
    variants = [('baseline-deadlock', old_jar, True), ('baseline-timeout', old_jar, False)]
    if not baseline_only:
        variants += [('new-deadlock', e.jar, True), ('new-timeout', e.jar, False)]
    serial = {}
    for label, binary, detect in variants:
        e.sql("SET GLOBAL innodb_deadlock_detect=" + ('ON' if detect else 'OFF') + "; SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
        try:
            result = subprocess.run([e.java, '-Xmx256m',
                '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.MediaLockScenario',
                '-Dloader.path=' + str(classes), '-cp', str(binary),
                'org.springframework.boot.loader.launch.PropertiesLauncher',
                str(e.control['folder'] / 'runtime.properties'), label, str(image), str(e.evidence / (label + '-trace.json'))],
                text=True, capture_output=True, timeout=180)
            (e.evidence / (label + '.log')).write_text(result.stdout + result.stderr)
            e.assert_that(result.returncode == 0 and 'PASS media lock ' + label in result.stdout,
                          'actual product media lock/accounting/refusal ' + label, exit=result.returncode)
            serial[label] = [line for line in result.stdout.splitlines() if line.startswith('SERIAL ')]
            e.assert_that(len(serial[label]) == 2, 'free/null-run and synthetic paid serial accounting captured', variant=label)
        finally:
            e.sql("SET GLOBAL general_log='OFF'; SET GLOBAL innodb_deadlock_detect=ON;")
            (e.evidence / (label + '-runtime-sql.tsv')).write_text(e.sql(
                "SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(all(rows == next(iter(serial.values())) for rows in serial.values()),
                  'exact old/new free and synthetic paid media byte/accounting snapshots match')
    (e.evidence / 'serial-differential.json').write_text(json.dumps(serial, indent=2))
    (e.evidence / 'evaluation.tsv').write_text(e.sql('SELECT * FROM evaluation_budget;'))
    (e.evidence / 'binaries.json').write_text(json.dumps({label: {'sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
        'bytes': binary.stat().st_size} for label, binary in [('f5fc481', old_jar), ('current', e.jar)]}, indent=2))
    (e.evidence / 'scope.json').write_text(json.dumps({'freeSmoke': done, 'paidRefusal': failed,
        'seeds': 'Actual free admitted read_image invocations rebound to synthetic paid snapshot/run; no paid model dispatch or real billing',
        'barrier': 'After real first SELECT FOR UPDATE, observe data_lock_waits before release; 3 repeats per media method and mode',
        'ci': 'not run'}, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--old-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--baseline-only', action='store_true')
    args = parser.parse_args()
    e = ImageExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar.resolve(), args.baseline_only)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
