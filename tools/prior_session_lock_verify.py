"""REVIEW-55-02: unchanged six-caller flow plus observed, deterministic budget contention."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import threading
import time
import traceback

from prior_session_read_verify import ReadExperiment


class LockExperiment(ReadExperiment):
    def prepare(self):
        super().prepare()
        self.sql("UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME IN ('events_statements_history','events_statements_history_long');")
        self.halt = threading.Event()
        self.monitor = threading.Thread(target=self.sample, name='private-lock-observer')
        self.monitor.start()

    def sample(self):
        with (self.evidence / 'lock-samples.jsonl').open('w') as log:
            while not self.halt.is_set():
                try:
                    # Record the same graph's IDs before fetching history: connections can close
                    # between statements. history_long preserves already-completed holder SQL.
                    waits = self.sql("SELECT JSON_OBJECT('waiter',w.REQUESTING_THREAD_ID,'holder',w.BLOCKING_THREAD_ID,'wait_trx',w.REQUESTING_ENGINE_TRANSACTION_ID,'hold_trx',w.BLOCKING_ENGINE_TRANSACTION_ID,'table',l.OBJECT_NAME,'index',l.INDEX_NAME,'lock',l.LOCK_DATA,'holder_connection',t.PROCESSLIST_ID,'holder_current_sql',c.SQL_TEXT) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID LEFT JOIN performance_schema.threads t ON t.THREAD_ID=w.BLOCKING_THREAD_ID LEFT JOIN performance_schema.events_statements_current c ON c.THREAD_ID=w.BLOCKING_THREAD_ID;")
                    if waits:
                        ids = ','.join(str(value) for row in waits.splitlines() for value in (json.loads(row)['holder'], json.loads(row)['waiter']))
                        history = self.sql("SELECT JSON_OBJECT('thread',THREAD_ID,'event',EVENT_ID,'sql',SQL_TEXT,'timer_ps',TIMER_WAIT,'error',MYSQL_ERRNO) FROM performance_schema.events_statements_history_long WHERE THREAD_ID IN (" + ids + ") ORDER BY THREAD_ID,EVENT_ID;")
                        transactions = self.sql("SELECT JSON_OBJECT('id',trx_id,'state',trx_state,'start',trx_started,'waiting',trx_wait_started,'connection',trx_mysql_thread_id,'query',trx_query,'rows_locked',trx_rows_locked) FROM information_schema.innodb_trx;")
                        log.write(json.dumps({'at': time.time(), 'waits': waits, 'history': history, 'transactions': transactions}) + '\n')
                        log.flush()
                except Exception as error:
                    log.write(json.dumps({'at': time.time(), 'observer_error': type(error).__name__}) + '\n')
                    log.flush()
                self.halt.wait(.1)

    def lock_scenario(self, worker, task, jar, mode):
        classes = self.root / 'context-lock-classes'
        if not classes.exists():
            shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
        command = [self.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionContextLockScenario', '-Dloader.path=' + str(classes), '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(worker['folder'] / 'runtime.properties'), task, mode]
        result = subprocess.run(command, text=True, capture_output=True, timeout=25)
        (self.evidence / ('context-lock-' + mode + '.log')).write_text(result.stdout + result.stderr)
        self.assert_that(result.returncode == 0, 'deterministic context lock ' + mode, exit=result.returncode, output=result.stdout)

    def run(self):
        super().run()
        task = self.prior_task('source controlled context contention')
        worker = self.start('context-lock-worker', 'worker', boundary='M0')
        self.barrier(worker)
        consumption_sql = "SELECT models_used,tools_used,next_event_seq FROM task WHERE id='" + task + "'; SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "'; SELECT COUNT(*) FROM session_public_event WHERE task_id='" + task + "';"
        before = self.sql(consumption_sql)
        original_sha = hashlib.sha256(self.original_jar.read_bytes()).hexdigest()
        self.lock_scenario(worker, task, self.original_jar, 'original')
        self.lock_scenario(worker, task, self.jar, 'fixed')
        # Each scenario also performs a successful context read after releasing its blocker.
        # fenced() legitimately advances revision on those reads; revision is not consumption.
        after = self.sql(consumption_sql)
        (self.evidence / 'context-lock-consumption.json').write_text(json.dumps({'before': before, 'after': after}, indent=2))
        self.assert_that(before == after, 'timed-out context and subsequent reads consume no model/tool/event/receipt')
        self.assert_that(hashlib.sha256(self.original_jar.read_bytes()).hexdigest() == original_sha, 'original contention artifact unchanged', jarSha256=original_sha)
        (worker['folder'] / 'release').touch()
        self.state(task, 'SUCCEEDED')
        self.quiet(task)
        self.stop(worker)

    def cleanup(self):
        if hasattr(self, 'halt'):
            self.halt.set()
            self.monitor.join(30)
            assert not self.monitor.is_alive(), 'private lock observer must finish before cleanup'
        super().cleanup()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--original-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    experiment = LockExperiment(args.jar, args.evidence)
    experiment.expect_bug = False
    experiment.original_jar = args.original_jar.resolve()
    try:
        experiment.run()
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
