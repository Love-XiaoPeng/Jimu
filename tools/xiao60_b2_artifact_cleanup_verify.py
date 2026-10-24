"""Affected retention slice: exact old/new JAR, real private MySQL and product object deletion."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback
from p2_verify import Experiment, eventually


def verify(e, old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() ==
                  '1ef2696fbf351536c0865de87d2cd428369b892f840860bccb0f3193b42915a0',
                  'exact accepted f347569 baseline JAR')
    task = e.submit('artifact-retention-slice')
    foreign = e.submit('artifact-retention-foreign', subject='b')
    worker = e.start('retention-producer', 'worker')
    view = e.state(task, 'SUCCEEDED')
    other = eventually(lambda: (lambda r: r[1] if r[0] == 200 and r[1]['state'] == 'SUCCEEDED' else False)(
        e.http('GET', '/api/tasks/' + foreign, subject='b')), 'actual foreign publication', 90)
    e.stop(worker)
    e.stop(e.control)
    e.assert_that(len(view['artifacts']) == len(other['artifacts']) == 1, 'actual product artifacts supply fixtures')
    artifact = view['artifacts'][0]['artifactId']
    foreign_id = other['artifacts'][0]['artifactId']
    foreign_key = e.sql("SELECT snapshot_key FROM artifact WHERE tenant_id='tenant-b' AND id='" + foreign_id + "';")
    foreign_path = e.root / 'objects' / hashlib.sha256(b'tenant-b').hexdigest() / foreign_key
    foreign_bytes = foreign_path.read_bytes()
    prior = e.root / 'prior.jar'
    shutil.copyfile(old, prior)
    before = (dict(e.fixture.models), dict(e.fixture.tools), dict(e.fixture.effects))
    outcomes, meters = {}, {}
    for tag, binary in [('old', prior), ('new', e.jar)]:
        barrier = e.evidence / (tag + '-barrier')
        barrier.mkdir()
        command = [e.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ArtifactRetentionScenario',
                   '-Dloader.path=' + str(Path(__file__).resolve().parents[1] / 'target/test-classes'),
                   '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                   str(e.control['folder'] / 'runtime.properties'), task, artifact, str(barrier)]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            with (e.evidence / (tag + '.log')).open('w') as log:
                child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
                try:
                    def reached(name):
                        if child.poll() is not None:
                            raise AssertionError('retention driver exited before ' + name + '; see ' + tag + '.log')
                        return (barrier / name).exists()
                    eventually(lambda: reached('waiting'), 'budget lock product barrier', 100)
                    waits = eventually(lambda: e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'), 'actual retention budget lock wait', 5)
                    (e.evidence / (tag + '-budget-lock-waits.tsv')).write_text(waits)
                    (barrier / 'release').touch()
                    eventually(lambda: reached('deleting'), 'two external deletion contenders', 30)
                    samples = []
                    def quiet():
                        active = e.sql('SELECT trx_id,trx_mysql_thread_id,trx_state,trx_query FROM information_schema.innodb_trx;')
                        samples.append(active)
                        return not active
                    eventually(quiet, 'two storage callbacks reach DB quiet point', 5)
                    (e.evidence / (tag + '-external-delete-transactions.json')).write_text(json.dumps(samples, indent=2))
                    e.assert_that(samples[-1] == '', 'two concurrent storage callbacks hold no DB transaction ' + tag)
                    (barrier / 'delete-release').touch()
                    e.assert_that(child.wait(timeout=40) == 0, 'retention product paths ' + tag)
                finally:
                    if child.poll() is None:
                        child.kill()
                    child.wait()
            lines = (e.evidence / (tag + '.log')).read_text().splitlines()
            outcomes[tag] = [s for s in lines if s.startswith('RESULT ')]
            meters[tag] = {s.split(' ', 2)[1]: json.loads(s.split(' ', 2)[2]) for s in lines if s.startswith('QUERY ')}
            e.assert_that(foreign_path.read_bytes() == foreign_bytes, 'other tenant retained object unchanged ' + tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence / (tag + '-transactions.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(outcomes['old'] == outcomes['new'] and len(outcomes['new']) == 11, '11 exact old/new retention business outcomes')
    e.assert_that(meters['old'] == meters['new'], 'same input retention physical result meters', values=meters)
    e.assert_that(before == (dict(e.fixture.models), dict(e.fixture.tools), dict(e.fixture.effects)), 'no provider replay during cleanup differential')
    (e.evidence / 'differential.json').write_text(json.dumps({'outcomes': outcomes, 'meters': meters,
        'concurrencyBoundary': 'Two real cleanup contenders, deterministic serialized physical LocalArtifactStore delete in fixture only; general list/delete FS races not claimed closed'}, indent=2))


def main():
    signal.signal(signal.SIGTERM, lambda s, f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = p.parse_args()
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
