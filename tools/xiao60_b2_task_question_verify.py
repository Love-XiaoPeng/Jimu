"""Changed shared terminal/question-close chain, actual HTTP+two private worker JVMs."""
import argparse,json,signal,traceback
from pathlib import Path
from p2_verify import eventually
from user_question_verify import QuestionsExperiment,batch

def verify(e):
    e.prepare();e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
    task=e.task('changed-cancel-question',[batch()]);q=e.question(task);e.stopped_slot(task)
    e.assert_that(e.worker['process'].poll() is None and e.other['process'].poll() is None,'both real workers remain live')
    eventually(lambda:e.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"' AND quiescent=FALSE;")=='0','writer receipt before immediate question cancellation')
    before=e.sql("SELECT tool_used FROM tenant_budget WHERE tenant_id='tenant-a';")
    status,view=e.http('POST','/api/tasks/'+task+'/cancel');e.assert_that(status==200,'owner cancellation accepted')
    e.state(task,'CANCELLED')
    e.assert_that(e.sql("SELECT phase,close_code FROM user_question WHERE task_id='"+task+"';")=='CLOSED\tCANCELLED','real open question closed')
    e.assert_that(e.sql("SELECT state FROM invocation WHERE task_id='"+task+"';")=='CANCELLED','waiting invocation cancelled')
    e.assert_that(e.sql("SELECT state,actual FROM reservation WHERE task_id='"+task+"' AND category='TOOL';")=='SETTLED\t1','actual tool settlement once')
    frozen=e.sql("SELECT state,revision,next_event_seq,cancel_requested_at,fence FROM task WHERE id='"+task+"';SELECT id,state,actual FROM reservation WHERE task_id='"+task+"' ORDER BY id;")
    e.http('POST','/api/tasks/'+task+'/cancel')
    e.assert_that(frozen==e.sql("SELECT state,revision,next_event_seq,cancel_requested_at,fence FROM task WHERE id='"+task+"';SELECT id,state,actual FROM reservation WHERE task_id='"+task+"' ORDER BY id;"),'idempotent task/fence/reservation/events')
    e.assert_that(before==e.sql("SELECT tool_used FROM tenant_budget WHERE tenant_id='tenant-a';"),'closing preserves admitted tool charge')
    e.assert_that(e.respond(task,q)[1]['code']=='QUESTION_CLOSED','closed question refuses response')
    status,stream=e.http('GET','/api/tasks/'+task+'/events')
    e.assert_that(status==200 and 'question.closed' in stream and 'task.cancelled' in stream,'public SSE replay includes close and terminal')
    e.assert_that(e.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND fact_id='cancel';")=='1','unique cancel fact')
    e.sql("SET GLOBAL general_log='OFF';")
    (e.evidence/'transactions.tsv').write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
    (e.evidence/'business.json').write_text(json.dumps({'task':task,'question':q['requestId'],'syntheticLocalProviderOnly':True,'twoActualWorkers':True,'exactFrozenState':frozen},indent=2))

def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();e=QuestionsExperiment(a.jar,a.evidence)
    try:verify(e)
    except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
if __name__=='__main__':main()
