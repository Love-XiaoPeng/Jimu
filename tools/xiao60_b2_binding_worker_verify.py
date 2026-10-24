"""Affected shared binding worker paths: W1/W2 recovery and synthetic-qualified W3 only."""
import argparse
import signal
from pathlib import Path
import traceback

from p3_evaluation_plan_verify import MaterialExperiment, verify


def main():
    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt('Binding supervisor terminated')))
    p=argparse.ArgumentParser()
    p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--evidence',type=Path,required=True)
    args=p.parse_args()
    e=MaterialExperiment(args.jar,args.evidence,Path(__file__).resolve().parents[1]/'target/test-classes')
    try:
        e.prepare()
        verify(e,binding_cutover=True)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__=='__main__':
    main()
