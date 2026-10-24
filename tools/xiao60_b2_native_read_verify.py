"""Affected native v1/v2/v3 original slots; exact old/new binaries, private local fixtures."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment

def verify(e,old,version):
    e.prepare()
    results={}
    for tag,binary in [('old',old),('new',e.jar)]:
        goal='native read '+version+' '+tag+' Unicode 🧱'
        inputs=[{'seq':0,'before':1,'after':2},{'seq':127}]
        plan=[inputs] if version=='v1' else [[('session_event_read',i) for i in inputs]]
        task=e.task(goal,plan)
        worker=e.start(tag+'-worker','worker',boundary='SESSION_COMMIT_BEFORE');e.barrier(worker)
        before=len(e.fixture.requests)
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.NativeReadScenario',
            '-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),
            'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            logfile=e.evidence/('driver-'+tag+'.log')
            with logfile.open('w') as log:
                run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=logfile.read_text()
            e.assert_that(run.returncode==0,'actual native original-slot driver '+tag,exit=run.returncode,tail=output[-4500:])
            e.assert_that(before==len(e.fixture.requests),'fault rollback replay proof never call provider '+tag)
            results[tag]={'outcomes':[s for s in output.splitlines() if s.startswith('RESULT ')],
                'meters':[json.loads(s.split(' ',2)[2]) for s in output.splitlines() if s.startswith('QUERY ')]}
            e.assert_that(len(results[tag]['outcomes'])==3,'success missing and stale fence outcomes '+tag)
            e.assert_that(all(m=={'reads':0,'bytes':0,'operations':0} for m in results[tag]['meters']),
                'original unmetered explicit query envelope retained '+tag)
            state=e.sql("SELECT CONCAT(i.state,':',b.outcome,':',COALESCE(b.output_bytes,'NULL'),':',COALESCE(b.read_sources_snapshot,'NULL')) FROM invocation i JOIN invocation_session_read b ON b.tenant_id=i.tenant_id AND b.invocation_id=i.id WHERE i.tenant_id='tenant-a' AND i.task_id='"+task+"' ORDER BY i.ordinal;")
            (e.evidence/(tag+'-committed.tsv')).write_text(state)
            e.assert_that(len(state.splitlines())==2 and state.splitlines()[0].startswith('SUCCEEDED:SUCCEEDED:') and
                state.splitlines()[1]==('FAILED:FAILED:0:[]' if version=='v3' else 'FAILED:FAILED:0:NULL'),
                'native success and known failure persist NULL versus trace empty sources '+tag,state=state)
            e.assert_that(e.sql("SELECT tools_used FROM task WHERE tenant_id='tenant-a' AND id='"+task+"';")=='2','exactly two original tool consumptions '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;');e.stop(worker,kill=True)
        e.http('POST','/api/tasks/'+task+'/cancel')
    e.assert_that(results['old']==results['new'],'old/new native original-slot outcomes and explicit envelope agree')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,
        'boundary':'Only two native read original slots per binary. Does not close complete worker/search/trace, cold-lock or full scope/maintenance/file lock matrices; core UoW final deadline gap remains OPEN.'},indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--only',choices=['all','v1','v2','v3'],default='all');a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='d8a59303978d8a52405b51e45c312e248cbddc6048f315b42c7a68e9c4ca852c'
    for name,klass in [('v1',SessionExperiment),('v2',SearchExperiment),('v3',TraceExperiment)]:
        if a.only not in ('all',name):continue
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
