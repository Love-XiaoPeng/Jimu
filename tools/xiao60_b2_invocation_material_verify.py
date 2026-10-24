"""Synthetic paid-run material branches only, no real key, billing, grading or visual authorization."""
import argparse
from pathlib import Path
import signal
import traceback
from p3_evaluation_plan_verify import MaterialExperiment, verify


def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('Invocation supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();e=MaterialExperiment(a.jar,a.evidence,Path(__file__).resolve().parents[1]/'target/test-classes')
    try:
        e.prepare();e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
        verify(e,invocation_cutover=True)
        e.sql("SET GLOBAL general_log='OFF';")
        (e.evidence/'runtime-transactions.tsv').write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally: e.cleanup()


if __name__=='__main__': main()
