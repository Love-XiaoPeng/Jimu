"""Native search execution only: same real PENDING slots, exact old/new JARs and rolled-back trials."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Experiment
from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment

def verify(e,old,version):
    e.prepare();inputs=[('session_event_search',{'query':'native'})]
    plan=[inputs]
    task=e.task('shared native window '+version+' Unicode 🧱',plan)
    worker=e.start('window-worker','worker',boundary='SESSION_SEARCH_COMMIT_BEFORE');e.barrier(worker)
    # Collect the owned control first and paused child second, so no timed maintenance or
    # interrupted worker handler changes the immutable facts during exact-JAR comparison.
    for child in reversed(e.children):
        if child is not worker['process']:e.stop({'process':child,'name':'private window foreground collection'})
    Experiment.stop(e,worker,kill=True)
    provider=len(e.fixture.requests);results={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionSearchExecutionScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('driver-'+tag+'.log')
            with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text();e.assert_that(run.returncode==0,'native search exact-JAR driver '+tag,exit=run.returncode,tail=output[-5000:])
            results[tag]={'outcomes':[(line.split(' ',2)[1],json.loads(line.split(' ',2)[2])) for line in output.splitlines() if line.startswith('RESULT ')],
                          'meters':[(line.split(' ',2)[1],json.loads(line.split(' ',2)[2])) for line in output.splitlines() if line.startswith('QUERY ')],
                          'proofs':{line.split(' ',2)[1]:json.loads(line.split(' ',2)[2]) for line in output.splitlines() if line.startswith('PROOF ')}}
            e.assert_that(len(results[tag]['outcomes'])==5 and len(results[tag]['meters'])==0,'five owner/tool outcomes '+tag)
            e.assert_that(output.count('PASS all captured native search facts restored after outer rollback')==5,'five complete fact rollbacks '+tag)
            e.assert_that(provider==len(e.fixture.requests),'native search trials do not call provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(results['old']==results['new'],'same-fact native search results and proofs agree')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'boundary':'Actual worker immutable PENDING search slot; JdbcLedger.sessionSearch owner+empty range and actual searchOrResult/MapperSessionSearches success, single scan SELECT fault and known corrupt settlement, each full captured-fact outer rollback. Fixture lease/deadline UPDATE per tool trial and event digest tamper all rolled back. Original2s deadline; no retries, extra commits/provider replay. Does not repeat historical read original-slot/acknowledgement/replay matrix, whole-task success, independent invalidation or all lock arrangements.'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='afbc33280d4edfed16482df39c4b9c310e0f3fce96b50aa60149467de95ba34c'
    for name,klass in [('v2',SearchExperiment),('v3',TraceExperiment)]:
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
