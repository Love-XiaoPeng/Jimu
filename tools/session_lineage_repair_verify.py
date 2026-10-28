"""Bounded repair probes in a fresh private fixture; never reruns A03 implicitly."""
import argparse
import json
import signal
import traceback
from session_lineage_verify import LineageExperiment
from session_lineage_budget_verify import quota_state


def admission(e):
    chain = []
    for ordinal in range(9):
        task = e.task('repair-chain-' + str(ordinal), chain,
                      chain[-1] if chain else None, plan=[])
        chain.append(task)
    e.stop(e.worker)
    before = quota_state(e)
    replay = e.task('repair-chain-8', list(reversed(chain[:8])), chain[7], plan=[])
    after = quota_state(e)
    e.assert_that(replay == chain[-1] and before == after,
        'nine-node current-state idempotency creates no task history reservation or event')
    (e.evidence / 'admission-tasks.json').write_text(json.dumps(chain, indent=2))


if __name__ == '__main__':
    from pathlib import Path
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('bounded deadline')))
    e = LineageExperiment(args.jar, args.evidence)
    try:
        e.prepare()
        admission(e)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
