"""Affected native v3 trace original slots; exact old/new binaries, private local fixtures."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment

def verify(e,old,version):
    e.prepare()
    results={}
    for tag,binary in [('old',old),('new',e.jar)]:
        goal='native trace '+version+' '+tag+' Unicode 🧱'
        inputs=[{'seq':0},{'seq':'$receipt'},{'seq':127}]
        plan=[('session_event_read',{'seq':0}),[('session_event_trace',i) for i in inputs]]
        task=e.task(goal,plan)
        worker=e.start(tag+'-worker','worker',boundary='SESSION_TRACE_COMMIT_BEFORE');e.barrier(worker)
        before=len(e.fixture.requests)
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.NativeTraceScenario',
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
            e.assert_that(len(results[tag]['outcomes'])==4,'reverse/source/missing and stale fence outcomes '+tag)
            e.assert_that(all(m=={'reads':0,'bytes':0,'operations':0} for m in results[tag]['meters']),
                'original unmetered explicit query envelope retained '+tag)
            state=e.sql("SELECT CONCAT(i.state,':',b.outcome,':',COALESCE(b.output_bytes,'NULL'),':',COALESCE(b.scan_rows,'NULL')) FROM invocation i JOIN invocation_session_trace b ON b.tenant_id=i.tenant_id AND b.invocation_id=i.id WHERE i.tenant_id='tenant-a' AND i.task_id='"+task+"' AND i.tool='session_event_trace' ORDER BY i.ordinal;")
            (e.evidence/(tag+'-committed.tsv')).write_text(state)
            e.assert_that(len(state.splitlines())==3 and all(line.startswith('SUCCEEDED:SUCCEEDED:') for line in state.splitlines()[:2]) and
                state.splitlines()[2]=='FAILED:FAILED:0:NULL',
                'native success and known failure persist NULL failed scan metadata '+tag,state=state)
            e.assert_that(e.sql("SELECT tools_used FROM task WHERE tenant_id='tenant-a' AND id='"+task+"';")=='4','one prerequisite read plus exactly three trace consumptions '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;');e.stop(worker,kill=True)
        e.http('POST','/api/tasks/'+task+'/cancel')
    e.assert_that(results['old']==results['new'],'old/new native original-slot outcomes and explicit envelope agree')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,
        'boundary':'Only three native trace original slots following one prerequisite read per binary. Does not close complete worker/search/trace, cold-lock or full scope/maintenance/file lock matrices; core UoW final deadline gap remains OPEN.'},indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--only',choices=['v3'],default='v3');a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='ff9e1936ab17f8522332c3c4bafbb671bb5eb41207acc10b2fc7c15b63d5cfac'
    for name,klass in [('v3',TraceExperiment)]:
        if a.only not in ('all',name):continue
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
