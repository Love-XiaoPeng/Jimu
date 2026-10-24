"""Affected shared native readRows only, same actual persisted facts and fixed old/new JARs."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Experiment
from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment

def verify(e,old,version):
    e.prepare();inputs=[{'seq':0,'before':1,'after':2},{'seq':127}]
    plan=[inputs] if version=='v1' else [[('session_event_read',i) for i in inputs]]
    task=e.task('shared native window '+version+' Unicode 🧱',plan)
    worker=e.start('window-worker','worker',boundary='SESSION_COMMIT_BEFORE');e.barrier(worker)
    # Collect the owned control first and paused child second, so no timed maintenance or
    # interrupted worker handler changes the immutable facts during exact-JAR comparison.
    for child in reversed(e.children):
        if child is not worker['process']:e.stop({'process':child,'name':'private window foreground collection'})
    Experiment.stop(e,worker,kill=True)
    provider=len(e.fixture.requests);results={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SharedReadWindowScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('driver-'+tag+'.log')
            with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text();e.assert_that(run.returncode==0,'shared window exact-JAR driver '+tag,exit=run.returncode,tail=output[-5000:])
            results[tag]={'outcomes':[(line.split(' ',2)[1],json.loads(line.split(' ',2)[2])) for line in output.splitlines() if line.startswith('RESULT ')],
                          'meters':[(line.split(' ',2)[1],json.loads(line.split(' ',2)[2])) for line in output.splitlines() if line.startswith('QUERY ')]}
            e.assert_that(len(results[tag]['outcomes'])==3 and len(results[tag]['meters'])==3,'window success404fault and meters '+tag)
            e.assert_that(output.count('PASS all captured native facts unchanged after outer rollback')==3,'three complete fact rollbacks '+tag)
            e.assert_that(provider==len(e.fixture.requests),'window trials do not call provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(results['old']==results['new'],'same-fact shared window results agree')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'boundary':'Actual immutable worker user/step/model events; direct shared JdbcSessionRecords.read via queryView/sessionTransaction, original history lock, three-row output/404/injected SELECT fault, each outer rollback. Product2s deadline unchanged, no retries, extra commits or provider replay. Not actual slot settlement/replay/commit acknowledgement matrix, whole-task success, independent invalidation or all lock arrangements.'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='9f84771e14de2e04935a6dde96dbcd6f9546ecd640e8be1b08100c4b39ca1587'
    for name,klass in [('v1',SessionExperiment),('v2',SearchExperiment),('v3',TraceExperiment)]:
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
