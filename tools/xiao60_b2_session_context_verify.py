"""Affected context paths only: exact old/new binaries, private MySQL and paid=false fixture."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from prior_session_verify import PriorExperiment
from session_event_verify import SessionExperiment
from xiao60_b2_scoped_query_verify import admit

def driver(e,worker,task,binary,tag):
    command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionContextScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
    e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
    try:
        logfile=e.evidence/('context-'+tag+'.log')
        with logfile.open('w') as log:
            result=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=100)
        output=logfile.read_text()
        e.assert_that(result.returncode==0,'actual context driver '+tag,exit=result.returncode,tail=output[-5000:])
        context=json.loads(next(s[8:] for s in output.splitlines() if s.startswith('CONTEXT ')))
        outcomes=[s for s in output.splitlines() if s.startswith('RESULT ')]
        meter=json.loads(next(s[6:] for s in output.splitlines() if s.startswith('QUERY ')))
        metadata=int(next(s[9:] for s in output.splitlines() if s.startswith('METADATA ')))
        return {'context':context,'outcomes':outcomes,'meter':meter,'metadata':metadata}
    finally:
        e.sql("SET GLOBAL general_log='OFF';")
        (e.evidence/(tag+'-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
        e.sql('TRUNCATE TABLE mysql.general_log;')

def verify(e,old,scoped):
    e.prepare()
    if scoped:
        source=admit(e,'context source nebula')
        writer=e.start('source-worker','worker');e.state(source,'SUCCEEDED');e.quiet(source);e.stop(writer)
        task=admit(e,'caller context nebula',[source])
        worker=e.start('context-worker','worker',boundary='PRIOR_SESSION_COMMIT_BEFORE');e.barrier(worker)
    else:
        task=e.task('native context',[{'seq':0}])
        worker=e.start('context-worker','worker',boundary='SESSION_COMMIT_BEFORE');e.barrier(worker)
    before=len(e.fixture.requests)
    results={tag:driver(e,worker,task,binary,tag) for tag,binary in [('old',old),('new',e.jar)]}
    e.assert_that(results['old']['context']==results['new']['context'],'same task old/new context structure and values')
    e.assert_that(results['old']['outcomes']==results['new']['outcomes'],'same refusal rollback and recovery outcomes')
    e.assert_that(len(e.fixture.requests)==before,'context and fault/recovery never replay provider')
    e.assert_that(results['old']['metadata']==results['new']['metadata'],'same metadata scalars including NULL by independent information_schema oracle')
    command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionContextLockScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(e.jar),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task,'fixed']
    if scoped:command.append('warm')
    with (e.evidence/'lock.log').open('w') as log:
        result=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=45)
    e.assert_that(result.returncode==0,'actual bounded context budget lock and same lease recovery',exit=result.returncode)
    (e.evidence/'differential.json').write_text(json.dumps({'results':results,'boundary':'Native v1 and scoped v4 context; v2/v3 full worker, complete scope/maintenance/file lock arrangements, real paid, and core UoW final deadline gap remain OPEN.'},indent=2))
    e.stop(worker,kill=True)

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--only',choices=['both','native','scoped'],default='both');a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='b0e50866ce48b7af343f02bce0dce20f72b2bb5b74ec2ff68c62d47ab595044f'
    for name,klass in [('native',SessionExperiment),('scoped',PriorExperiment)]:
        if a.only not in ('both',name):continue
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name=='scoped')
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
