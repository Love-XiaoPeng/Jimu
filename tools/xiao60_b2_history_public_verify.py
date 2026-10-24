"""Affected public control maintenance entry, on the supplied immutable JAR."""
import argparse
from pathlib import Path
import signal
import traceback
from session_cleanup_verify import verify
from session_event_verify import SessionExperiment


class RetentionExperiment(SessionExperiment):
    def prepare(self):
        super().prepare()
        # Existing application_grants contract; this legacy v1 fixture predates trace cleanup.
        self.sql("GRANT INSERT,UPDATE ON jimu.invocation_session_trace TO 'runtime'@'%';")


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    e = RetentionExperiment(args.jar, args.evidence)
    try:
        e.prepare()
        verify(e, 1)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
