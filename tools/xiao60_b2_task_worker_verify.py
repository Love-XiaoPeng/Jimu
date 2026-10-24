"""Affected ordinary Invocation assembly: two workers, successful write cache recovery and download."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import traceback
from p2_verify import eventually
from p3_report_verify import ReportExperiment


def verify(e):
    e.prepare()
    tasks=[e.submit('task-intake-dual-one'),e.submit('task-intake-dual-two')]
    e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
    workers=[e.start('task-intake-one','worker','T1'),e.start('task-intake-two','worker','T1')]
    markers=[e.barrier(worker).split() for worker in workers]
    e.assert_that({marker[1] for marker in markers}==set(tasks)
        and len({marker[2] for marker in markers})==2,'two actual worker PIDs own distinct tasks at controlled no-transaction barriers')
    for worker in workers: (worker['folder']/'release').touch()
    for task in tasks:
        view=e.state(task,'SUCCEEDED')
        e.assert_that(len(view['artifacts'])==1,'dual workers publish one report per task')
        artifact=view['artifacts'][0]
        status,body=e.http('GET','/api/artifacts/'+artifact['artifactId']+'/download')
        e.assert_that(status==200 and hashlib.sha256(body.encode()).hexdigest()==artifact['sha256'],'actual authorized download digest')
        e.assert_that(e.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='4' and
            e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"' AND state='SUCCEEDED';")=='4','unique admitted effects, one settlement each')
        e.assert_that(e.http('GET','/api/artifacts/'+artifact['artifactId']+'/download',subject='b')[0]==404,'cross-tenant refusal')
    for worker in workers: e.stop(worker)
    task=e.submit('task-intake-recovery-write')
    worker=e.start('task-intake-cache','worker','W1');e.barrier(worker)
    before=len(e.tls.requests);snapshot=e.receipts(task)
    worker['process'].kill()
    eventually(lambda: e.sql("SELECT quiescent FROM task_attempt WHERE task_id='"+task+"';")=='1','real killed worker receipt before parent reap',20)
    e.stop(worker);e.expire(task)
    worker=e.start('task-intake-resume','worker');view=e.state(task,'SUCCEEDED');e.stop(worker)
    after=e.receipts(task)
    e.assert_that(before==len(e.tls.requests) and snapshot['files']==after['files'] and after['files']['reports/shared.md']['version']==1,
        'confirmed successful fetch/read/write survive process recovery without replay or second CAS')
    e.assert_that(len(view['artifacts'])==1 and e.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='4','recovery no double charge/publication')
    e.sql("SET GLOBAL general_log='OFF';")
    (e.evidence/'runtime-transactions.tsv').write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
    (e.evidence/'business.json').write_text(json.dumps({'dualTasks':tasks,'recoveryTask':task,'scope':'local provider/TLS only; not business quality acceptance'},indent=2))


def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('Invocation supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();e=ReportExperiment(a.jar,a.evidence,Path(__file__).resolve().parents[1]/'target/test-classes')
    try: verify(e)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally: e.cleanup()


if __name__=='__main__': main()
