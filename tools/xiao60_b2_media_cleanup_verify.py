"""Affected media retention slice: accepted old/new JAR, actual images, private MySQL only."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback
from p4_image_verify import ImageExperiment, png
from p2_verify import eventually


def verify(e, old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() ==
                  '6c3c1575948ef47e51cdf027e0acb9cf0f0cf9aca8504119ad8f9d3625a51897',
                  'exact accepted 68767c0 baseline JAR')
    body = {'goal': 'image-call', 'capabilityIds': ['read', 'read_image', 'compose_report', 'present'],
            'images': [{'filePath': 'inputs/images/sample.png', 'dataBase64': base64.b64encode(png()).decode()}]}
    status, view = e.http('POST', '/api/tasks', body, key='media-cleanup')
    e.assert_that(status == 202, 'actual free image admitted', response=view)
    task = view['taskId']
    status, foreign = e.http('POST', '/api/tasks', body, key='media-cleanup-foreign', subject='b')
    e.assert_that(status == 202, 'actual foreign tenant image admitted', response=foreign)
    worker = e.start('media-cleanup-producer', 'worker')
    done = e.state(task, 'SUCCEEDED')
    other = eventually(lambda: (lambda r: r[1] if r[0] == 200 and r[1]['state'] == 'SUCCEEDED' else False)(
        e.http('GET', '/api/tasks/' + foreign['taskId'], subject='b')), 'actual foreign image publication', 90)
    e.stop(worker)
    e.stop(e.control)
    e.assert_that(len(done['artifacts']) == len(other['artifacts']) == 1 and len(e.fixture.requests) == 10,
                  'two real image tasks and controlled local provider supply checkpoints/objects')
    e.assert_that(any(r['media'] for r in e.fixture.requests), 'actual normalized image HTTP parts decoded')
    foreign_files = {str(p): p.read_bytes() for p in (e.root / 'images').glob(hashlib.sha256(b'tenant-b').hexdigest() + '-*')}
    e.assert_that(bool(foreign_files), 'actual foreign secure media exists')
    prior = e.root / 'prior.jar'
    shutil.copyfile(old, prior)
    calls = len(e.fixture.requests)
    outcomes, meters = {}, {}
    for tag, binary in [('old', prior), ('new', e.jar)]:
        barrier = e.evidence / (tag + '-barrier')
        barrier.mkdir()
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.MediaRetentionScenario',
                   '-Dloader.path=' + str(Path(__file__).resolve().parents[1] / 'target/test-classes'),
                   '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                   str(e.control['folder'] / 'runtime.properties'), task, str(barrier)]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            with (e.evidence / (tag + '.log')).open('w') as log:
                child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
                try:
                    def reached(name):
                        if child.poll() is not None:
                            raise AssertionError('media cleanup driver exited before ' + name + '; see ' + tag + '.log')
                        return (barrier / name).exists()
                    eventually(lambda: reached('waiting'), 'budget lock product barrier', 100)
                    waits = eventually(lambda: e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'), 'actual media cleanup budget lock wait', 5)
                    (e.evidence / (tag + '-budget-lock-waits.tsv')).write_text(waits)
                    (barrier / 'release').touch()
                    eventually(lambda: reached('deleting'), 'two external deletion contenders', 30)
                    samples = []
                    def quiet():
                        active = e.sql('SELECT trx_id,trx_mysql_thread_id,trx_state,trx_query FROM information_schema.innodb_trx;')
                        samples.append(active)
                        return not active
                    eventually(quiet, 'two media callbacks reach DB quiet point', 5)
                    (e.evidence / (tag + '-external-delete-transactions.json')).write_text(json.dumps(samples, indent=2))
                    e.assert_that(samples[-1] == '', 'two actual media deletion callbacks hold no DB transaction ' + tag)
                    (barrier / 'delete-release').touch()
                    e.assert_that(child.wait(timeout=40) == 0, 'media cleanup product paths ' + tag)
                finally:
                    if child.poll() is None:
                        child.kill()
                    child.wait()
            lines = (e.evidence / (tag + '.log')).read_text().splitlines()
            outcomes[tag] = [s for s in lines if s.startswith('RESULT ')]
            meters[tag] = {s.split(' ', 2)[1]: json.loads(s.split(' ', 2)[2]) for s in lines if s.startswith('QUERY ')}
            e.assert_that(all(Path(p).read_bytes() == data for p, data in foreign_files.items()),
                          'foreign tenant secure media bytes unchanged ' + tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence / (tag + '-transactions.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(outcomes['old'] == outcomes['new'] and len(outcomes['new']) == 13, '13 exact old/new media retention business outcomes')
    e.assert_that(meters['old'] == meters['new'], 'same fixture physical result meters', values=meters)
    e.assert_that(len(e.fixture.requests) == calls, 'no provider replay in cleanup/recovery differential')
    (e.evidence / 'differential.json').write_text(json.dumps({'outcomes': outcomes, 'meters': meters,
        'fixture': 'Real free read_image tasks; private lifecycle/hold values only. Zero actual, nonnull actual and null fallback; not paid vision acceptance.',
        'concurrencyBoundary': 'Two real cleanup contenders, serialized SecureImageStore unlink in fixture only; all filesystem/maintenance permutations remain OPEN'}, indent=2))


def main():
    signal.signal(signal.SIGTERM, lambda s, f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = p.parse_args()
    e = ImageExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
