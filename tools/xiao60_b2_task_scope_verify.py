"""Changed get/events/cancel scope branches; accurate old/new controls on one private database."""
import argparse,hashlib,json,shutil,signal,subprocess,traceback
from pathlib import Path
from prior_session_verify import PriorExperiment

def verify(e,old):
    e.prepare();e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest()=='148bbc7af10a34c279063d23fe588428216b04aeca6730d8ef7d1f33791e9df5','exact accepted baseline')
    prior=e.root/'prior.jar';shutil.copyfile(old,prior);current=e.jar;results={}
    e.stop(e.control)
    for tag,binary in [('old',prior),('new',current)]:
        e.jar=binary;e.control=e.start('scope-control-'+tag,'control');values=[]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            # Empty prior set is a real admitted immutable scope, no synthetic authority bypass.
            caller=e.prior_task('scope observation '+tag)
            status,view=e.http('GET','/api/tasks/'+caller)
            e.assert_that(status==200 and view['code'] is None,'valid scope initial public view '+tag)
            values.append({'case':'valid','state':view['state'],'code':view['code']})
            e.sql("UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';")
            try:
                status,view=e.http('GET','/api/tasks/'+caller)
                e.assert_that(status==200 and view['code']=='SESSION_SCOPE_UNAVAILABLE' and view['summary'] is None and view['artifacts']==[],'get redacts actor authority loss '+tag)
                e.assert_that(e.sql("SELECT invalidated_at IS NOT NULL FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id='"+caller+"';")=='1','get afterCompletion invalidation committed '+tag)
                values.append({'case':'actor-get','status':status,'code':view['code']})
            finally:e.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice';")
            status,view=e.http('GET','/api/tasks/'+caller)
            e.assert_that(status==200 and view['code']=='SESSION_SCOPE_UNAVAILABLE','scope never resurrects after actor restore '+tag)
            values.append({'case':'restored-get','status':status,'code':view['code']})
            status,body=e.http('GET','/api/tasks/'+caller+'/events')
            e.assert_that(status==404 and body['code']=='SESSION_SCOPE_UNAVAILABLE','events refuse invalidated scope '+tag)
            values.append({'case':'invalid-events','status':status,'code':body['code']})
            status,view=e.http('POST','/api/tasks/'+caller+'/cancel')
            e.assert_that(status==200 and view['state']=='CANCELLED' and view['code']=='SESSION_SCOPE_UNAVAILABLE','cancel invalid scope retains state and redaction '+tag)
            values.append({'case':'invalid-cancel','status':status,'state':view['state'],'code':view['code']})
            # Events first observes authority loss: refusal transaction rolls back, post-record persists.
            other=e.prior_task('scope events rollback '+tag)
            # Stop the controller's legitimate periodic scope validator before revocation.
            # Only the direct product events call may observe the loss in this transaction proof.
            e.stop(e.control)
            e.sql("UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';")
            before=e.sql("SELECT state,next_event_seq FROM task WHERE tenant_id='tenant-a' AND id='"+other+"';")
            try:
                command=[e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TaskOperationsScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(e.jar),'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),'scope-refusal',str(e.evidence),other]
                child=subprocess.run(command,text=True,capture_output=True,timeout=60)
                (e.evidence/(tag+'-direct-events-refusal.log')).write_text(child.stdout+child.stderr)
                e.assert_that(child.returncode==0 and 'PASS direct scope events rollback' in child.stdout,'direct events transaction refuses then persists invalidation '+tag)
                e.control=e.start('scope-after-direct-'+tag,'control')
                status,body=e.http('GET','/api/tasks/'+other+'/events')
                e.assert_that(status==403 and body['code']=='ACCESS_REVOKED','events preserve actor rejection before replay '+tag)
                e.assert_that(e.sql("SELECT invalidated_at IS NOT NULL FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id='"+other+"';")=='1','events observed authority loss survives refusal rollback '+tag)
                e.assert_that(before==e.sql("SELECT state,next_event_seq FROM task WHERE tenant_id='tenant-a' AND id='"+other+"';"),'events refusal writes no task/event mutation '+tag)
                values.append({'case':'events-rollback','status':status,'code':body['code'],'afterCompletion':True})
            finally:e.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice';")
            e.http('POST','/api/tasks/'+other+'/cancel');results[tag]=values
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-transactions.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;');e.stop(e.control)
    e.jar=current
    e.assert_that(results['old']==results['new'],'six exact old/new scope/public/refusal outcomes')
    e.assert_that(not e.fixture.models,'no provider calls for scope observation differential')
    (e.evidence/'differential.json').write_text(json.dumps(results,indent=2))

def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();e=PriorExperiment(a.jar,a.evidence)
    try:verify(e,a.old_jar)
    except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
if __name__=='__main__':main()
