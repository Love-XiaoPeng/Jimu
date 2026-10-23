"""QA-52-01: exact post-purge accounting through real control maintenance."""
import argparse
from pathlib import Path
import signal
import time
import traceback

from p2_verify import eventually
from session_event_verify import SessionExperiment


def verify(experiment, controls):
    e = experiment
    task = e.task('QA-52-01 purge accounting', [] if controls == 1 else
                  [{'seq': 0}, {'seq': 2}, {'seq': 3}])
    worker = e.start('cleanup-accounting-worker', 'worker')
    e.state(task, 'SUCCEEDED')
    e.stop(worker)
    e.ledger_scenario(worker, task, 'accounting')
    second = e.start('cleanup-competing-control', 'control') if controls == 2 else None
    before = int(e.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
    e.sql("UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='" + task + "';")
    eventually(lambda: e.sql("SELECT content_state FROM task_session_history WHERE task_id='" + task + "';") == 'PURGED', 'normal maintenance purges history')
    query = "SELECT cleanup_receipt,metadata_bytes,held_bytes,body_bytes FROM task_session_history WHERE task_id='" + task + "';"
    receipt = e.sql(query)
    metadata = int(e.sql("SELECT metadata_bytes FROM task_session_history WHERE task_id='" + task + "';"))
    after = int(e.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
    e.assert_that(before - after == 8388608 - metadata,
                  'exact tenant storage release', before=before, after=after, metadata=metadata)
    e.assert_that(e.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND type='session_history.content_deleted';") == '1',
                  'one content deletion event')
    e.assert_that(e.sql("SELECT actual FROM reservation WHERE task_id='" + task + "' AND category='SESSION_HISTORY_STORAGE';") == str(metadata),
                  'reservation actual equals metadata')
    time.sleep(3)
    e.assert_that(e.sql(query) == receipt and int(e.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")) == after,
                  'repeated maintenance preserves receipt and exact counters', controls=controls)
    if second:
        e.stop(second)
    e.stop(e.control)
    # Recompute from every actual stored scalar after all maintenance has stopped.
    e.ledger_scenario(worker, task, 'accounting')
    e.assert_that(e.sql("SELECT body_bytes,held_bytes=metadata_bytes FROM task_session_history WHERE task_id='" + task + "';") == '0\t1',
                  'all body copies removed and held equals metadata')


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--controls', type=int, choices=[1, 2], required=True)
    args = parser.parse_args()
    experiment = SessionExperiment(args.jar, args.evidence)
    try:
        experiment.prepare()
        verify(experiment, args.controls)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
