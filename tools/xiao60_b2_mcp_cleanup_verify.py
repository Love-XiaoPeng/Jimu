"""Affected MCP retention slice: real private MCP/control/worker and exact old/new JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback
from mcp_verify import McpExperiment, NAMES
from p2_verify import eventually


def verify(e, old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() ==
                  '5d8081f22498062982e58a75af44165ad2ec9fd69c2891686ec9e381a49b26ac', 'exact accepted 289670a JAR')
    plan = [(NAMES[1], {'server': 'controlled'}), (NAMES[1], {'server': 'controlled'}),
            (NAMES[2], {'server': 'controlled', 'uri': 'jimu-fixture://tenant-a/docs/text'})]
    task = e.task('cleanup-mcp-real', plan)
    foreign = e.task('cleanup-mcp-foreign', [(NAMES[1], {'server': 'controlled'})], subject='b')
    worker = e.start('mcp-cleanup-producer', 'worker')
    e.state(task, 'SUCCEEDED')
    e.state(foreign, 'SUCCEEDED')
    e.state(e.legacy_task, 'SUCCEEDED')
    e.stop(worker)
    e.stop(e.control)
    before = (len(e.fixture.requests), len(e.wire()))
    e.assert_that(len(e.outputs(task)) == 3 and len(e.outputs(foreign)) == 1, 'two actual list roots plus read output and foreign cursor fixture')
    prior = e.root / 'prior.jar'
    shutil.copyfile(old, prior)
    outcomes, meters = {}, {}
    for tag, binary in [('old', prior), ('new', e.jar)]:
        barrier = e.evidence / (tag + '-barrier')
        barrier.mkdir()
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.McpRetentionScenario',
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
                            raise AssertionError('MCP cleanup driver exited before barrier; see ' + tag + '.log')
                        return (barrier / 'waiting').exists()
                    eventually(reached, 'two real budget contenders barrier', 100)
                    def waiting():
                        return int(e.sql('SELECT COUNT(DISTINCT REQUESTING_THREAD_ID) FROM performance_schema.data_lock_waits;')) >= 2
                    eventually(waiting, 'two actual cleanup transaction lock waits', 8)
                    (e.evidence / (tag + '-lock-waits.tsv')).write_text(e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'))
                    (e.evidence / (tag + '-transactions-at-barrier.tsv')).write_text(e.sql('SELECT trx_id,trx_mysql_thread_id,trx_state,trx_query FROM information_schema.innodb_trx;'))
                    (barrier / 'release').touch()
                    e.assert_that(child.wait(timeout=45) == 0, 'actual MCP retention product paths ' + tag)
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
    e.assert_that(outcomes['old'] == outcomes['new'] and len(outcomes['new']) == 10, '10 exact old/new MCP retention outcome groups')
    e.assert_that(meters['old'] == meters['new'], 'same fixture physical result meters', values=meters)
    e.assert_that(before == (len(e.fixture.requests), len(e.wire())), 'zero provider/MCP/effect replay during cleanup and recovery')
    (e.evidence / 'differential.json').write_text(json.dumps({'outcomes': outcomes, 'meters': meters,
        'fixture': 'Real control/worker -> loopback model -> private MCP list/read results/cursors. Private lifecycle/actual-zero/null-reserved states only, no paid acceptance.',
        'concurrencyBoundary': 'Two independent product ledger connections both observed waiting after candidate scans at original budget prefix; one atomic release/event set. General scope/maintenance permutations remain OPEN.'}, indent=2))


def main():
    signal.signal(signal.SIGTERM, lambda s, f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = p.parse_args()
    e = McpExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
