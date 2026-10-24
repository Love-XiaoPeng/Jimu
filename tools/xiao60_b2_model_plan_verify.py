"""Only changed B2 model plan/accounting and material post-effect refusals; no full legacy matrix."""
import argparse
from pathlib import Path
import traceback

from p3_evaluation_plan_verify import MaterialExperiment, verify


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args()
    e=MaterialExperiment(args.jar,args.evidence,Path(__file__).resolve().parents[1]/'target/test-classes')
    try:
        e.prepare()
        verify(e,model_cutover=True)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__=='__main__':
    main()
