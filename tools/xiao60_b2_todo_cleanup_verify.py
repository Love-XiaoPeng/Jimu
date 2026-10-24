"""Affected todo retention slice: real private MySQL/control/two workers and exact old/new JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback
from todo_verify import TodoExperiment, items
from p2_verify import eventually


def verify(e, old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() == '6e29f4d33d03745f75d6c19c1a34696e41f0026ffbf364073e124a01480d4506','exact accepted f43e8a4 JAR')
    task=e.task('cleanup-todo-real',[items(('中文😀 pending','in_progress'),('next','pending')),items(('中文😀 pending','completed'),('next','in_progress')),items(('next','completed'))])
    foreign=e.task('cleanup-todo-foreign',[items(('foreign','completed'))],subject='b')
    e.worker=e.start('worker-todo-cleanup-one','worker');e.other=e.start('worker-todo-cleanup-two','worker')
    e.state(task,'SUCCEEDED');e.state(foreign,'SUCCEEDED')
    e.assert_that(e.view(task)['revision']==3,'three actual todo commits')
    e.assert_that(e.worker['process'].poll() is None and e.other['process'].poll() is None,'two actual todo workers live')
    e.stop(e.worker);e.stop(e.other);e.stop(e.control)
    before=len(e.fixture.requests)
    prior = e.root / 'prior.jar'
    shutil.copyfile(old, prior)
    outcomes, meters = {}, {}
    for tag, binary in [('old', prior), ('new', e.jar)]:
        barrier = e.evidence / (tag + '-barrier')
        barrier.mkdir()
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TodoRetentionScenario',
                   '-Dloader.path=' + str(Path(__file__).resolve().parents[1] / 'target/test-classes'),
                   '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                   str(e.control['folder'] / 'runtime.properties'), task, str(barrier)]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            with (e.evidence / (tag + '.log')).open('w') as log:
                child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
                try:
                    def reached():
                        if child.poll() is not None:
                            raise AssertionError('todo cleanup driver exited before barrier; see ' + tag + '.log')
                        return (barrier / 'waiting').exists()
                    eventually(reached, 'two real budget contenders barrier', 240)
                    def waiting():
                        return int(e.sql('SELECT COUNT(DISTINCT REQUESTING_THREAD_ID) FROM performance_schema.data_lock_waits;')) >= 2
                    eventually(waiting, 'two actual cleanup transaction lock waits', 8)
                    (e.evidence / (tag + '-lock-waits.tsv')).write_text(e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'))
                    (e.evidence / (tag + '-transactions-at-barrier.tsv')).write_text(e.sql('SELECT trx_id,trx_mysql_thread_id,trx_state,trx_query FROM information_schema.innodb_trx;'))
                    (barrier / 'release').touch()
                    e.assert_that(child.wait(timeout=45) == 0, 'actual todo retention product paths ' + tag)
                finally:
                    if child.poll() is None:
                        child.kill()
                    child.wait()
            lines = (e.evidence / (tag + '.log')).read_text().splitlines()
            outcomes[tag] = [s for s in lines if s.startswith('RESULT ')]
            meters[tag] = {s.split(' ', 2)[1]: json.loads(s.split(' ', 2)[2]) for s in lines if s.startswith('QUERY ')}
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence / (tag + '-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(outcomes['old'] == outcomes['new'] and len(outcomes['new']) == 11, '11 exact old/new todo retention outcome groups')
    e.assert_that(all(v['reclaimed']['reads'] > 0 and v['retained']['reads'] > 0 for v in meters.values()), 'explicit actual result meters retained; MP body projections may differ', values=meters)
    e.assert_that(before == len(e.fixture.requests), 'zero provider/effect replay during cleanup and recovery')
    (e.evidence / 'differential.json').write_text(json.dumps({'outcomes': outcomes, 'meters': meters,
        'fixture': 'Real control/two workers -> loopback model -> three actual owner todo commits/four model dispatches and one other tenant todo commit. Private lifecycle/error cases only; no paid acceptance.',
        'concurrencyBoundary': 'Two independent product ledger connections both observed waiting after candidate scans at original budget prefix; one atomic release/event set. General scope/maintenance permutations remain OPEN.'}, indent=2))


def main():
    signal.signal(signal.SIGTERM, lambda s, f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = p.parse_args()
    e = TodoExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
