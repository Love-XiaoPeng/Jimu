"""Affected history retention slice: real private MySQL/control/two workers and exact old/new JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback
from session_event_verify import SessionExperiment
from prior_session_verify import PriorExperiment
from session_search_verify import SearchExperiment
from p2_verify import eventually


def verify(e, old):
    e.prepare()
    for table in ('invocation_session_search','invocation_session_trace','task_session_scope','task_session_scope_target','step_session_scope','invocation_session_scope_query'):
        e.sql("GRANT INSERT,UPDATE ON jimu.`"+table+"` TO 'runtime'@'%';")
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() == '9da6affa7aa45fd284abcf0c5bc14052248962e1d19053346b8a7624816b2bb0','exact accepted 09567eb JAR')
    if isinstance(e, PriorExperiment):
        source=e.prior_task('nebula retained source')
        e.worker=e.start('history-source-worker','worker')
        e.state(source,'SUCCEEDED')
        task=e.prior_task('caller retained history', [source])
        foreign=e.prior_task('foreign retained history', subject='b')
        e.other=e.start('history-caller-worker','worker')
    else:
        plan=[('session_event_search',{'query':'history'}),('session_event_read',{'seq':0})] if isinstance(e,SearchExperiment) else [{'seq':0}]
        task=e.task('history retained Unicode 中文😀',plan)
        foreign=e.task('foreign retained history',[],subject='b')
        e.worker=e.start('history-worker-one','worker');e.other=e.start('history-worker-two','worker')
    e.state(task,'SUCCEEDED');e.state(foreign,'SUCCEEDED')
    e.assert_that(e.worker['process'].poll() is None and e.other['process'].poll() is None,'two real session workers live')
    e.stop(e.worker);e.stop(e.other);e.stop(e.control)
    before=len(e.fixture.requests)
    prior = e.root / 'prior.jar'
    shutil.copyfile(old, prior)
    outcomes, meters = {}, {}
    for tag, binary in [('old', prior), ('new', e.jar)]:
        barrier = e.evidence / (tag + '-barrier')
        barrier.mkdir()
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.HistoryRetentionScenario',
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
                            raise AssertionError('history cleanup driver exited before barrier; see ' + tag + '.log')
                        return (barrier / 'waiting').exists()
                    eventually(reached, 'two real budget contenders barrier', 240)
                    def waiting():
                        return int(e.sql('SELECT COUNT(DISTINCT REQUESTING_THREAD_ID) FROM performance_schema.data_lock_waits;')) >= 2
                    eventually(waiting, 'two actual cleanup transaction lock waits', 8)
                    (e.evidence / (tag + '-lock-waits.tsv')).write_text(e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'))
                    (e.evidence / (tag + '-transactions-at-barrier.tsv')).write_text(e.sql('SELECT trx_id,trx_mysql_thread_id,trx_state,trx_query FROM information_schema.innodb_trx;'))
                    (barrier / 'release').touch()
                    e.assert_that(child.wait(timeout=45) == 0, 'actual history retention product paths ' + tag)
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
    e.assert_that(outcomes['old'] == outcomes['new'] and len(outcomes['new']) == (10 if isinstance(e,PriorExperiment) else 9), 'exact old/new history retention outcome groups')
    e.assert_that(all(v['reclaimed']['reads'] > 0  for v in meters.values()), 'explicit actual result meters retained; MP body projections may differ', values=meters)
    e.assert_that(before == len(e.fixture.requests), 'zero provider/effect replay during cleanup and recovery')
    (e.evidence / 'differential.json').write_text(json.dumps({'outcomes': outcomes, 'meters': meters,
        'fixture': 'Real control/two workers -> loopback model -> actual session read and prior-session search/read commits, scoped caller and foreign tenant. Private lifecycle/error cases only; no paid acceptance.',
        'concurrencyBoundary': 'Two independent product ledger connections both observed waiting after candidate scans at original budget prefix; one atomic release/event set. General scope/maintenance permutations remain OPEN.'}, indent=2))


def main():
    signal.signal(signal.SIGTERM, lambda s, f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--profile', choices=['all', 'read', 'search', 'scope'], default='all')
    args = p.parse_args()
    for label, kind in [('read',SessionExperiment),('search',SearchExperiment),('scope',PriorExperiment)]:
        if args.profile in ('all', label):
            run(kind(args.jar, args.evidence / label), args.old_jar)


def run(e, old):
    try:
        verify(e, old)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
