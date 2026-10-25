"""Native trace execution only: same real PENDING slots, exact old/new JARs and rolled-back trials."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Experiment
from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment

def verify(e,old,version):
    e.prepare();plan=[('session_event_read',{'seq':0}),[('session_event_trace',{'seq':0})]]
    task=e.task('shared native window '+version+' Unicode 🧱',plan)
    worker=e.start('window-worker','worker',boundary='SESSION_TRACE_COMMIT_BEFORE');e.barrier(worker)
    # Collect the owned control first and paused child second, so no timed maintenance or
    # interrupted worker handler changes the immutable facts during exact-JAR comparison.
    for child in reversed(e.children):
        if child is not worker['process']:e.stop({'process':child,'name':'private window foreground collection'})
    Experiment.stop(e,worker,kill=True)
    provider=len(e.fixture.requests);results={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionTraceExecutionScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('driver-'+tag+'.log')
            with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text();e.assert_that(run.returncode==0,'native trace exact-JAR driver '+tag,exit=run.returncode,tail=output[-5000:])
            results[tag]={'outcomes':[(line.split(' ',2)[1],json.loads(line.split(' ',2)[2])) for line in output.splitlines() if line.startswith('RESULT ')],
                          'meters':[(line.split(' ',2)[1],json.loads(line.split(' ',2)[2])) for line in output.splitlines() if line.startswith('QUERY ')],
                          'proofs':{line.split(' ',2)[1]:json.loads(line.split(' ',2)[2]) for line in output.splitlines() if line.startswith('PROOF ')}}
            e.assert_that(len(results[tag]['outcomes'])==7 and len(results[tag]['meters'])==0,'seven owner/tool outcomes '+tag)
            e.assert_that(output.count('PASS all captured native trace facts restored after outer rollback')==7,'seven complete fact rollbacks '+tag)
            e.assert_that(provider==len(e.fixture.requests),'native trace trials do not call provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    # Map.of iteration salt is per JVM. Verify each stored raw proof in its original JVM,
    # then compare only JSON values of these two maps; preserve all original raw/digests.
    compared={}
    for tag in ['old','new']:
        compared[tag]=json.loads(json.dumps(results[tag]))
        for proof in compared[tag]['proofs'].values():
            if proof['outcome']=='SUCCEEDED':
                del proof['scan_digest'];del proof['relations_digest']
        raw={line.split(' ',2)[1]:json.loads(line.split(' ',2)[2]) for line in (e.evidence/('driver-'+tag+'.log')).read_text().splitlines() if line.startswith('RAWPROOF ')}
        e.assert_that(len(raw)==1,'one original Map.of proof pair '+tag)
        compared[tag]['originalMapValues']={trial:{name:json.loads(value) for name,value in pair.items()} for trial,pair in raw.items()}
    e.assert_that(compared['old']==compared['new'],'same-fact native trace values and individually proven original digests agree')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'boundary':'Actual worker immutable PENDING trace slot; JdbcLedger.sessionTrace owner/source/missing and actual traceOrResult/MapperSessionTraces success, single scan SELECT and relation SELECT faults and known corrupt settlement, each full captured-fact outer rollback. Fixture lease/deadline UPDATE per tool trial and event digest tamper all rolled back. Original2s deadline; no retries, extra commits/provider replay. Does not repeat historical read original-slot/acknowledgement/replay matrix, whole-task success, independent invalidation or all lock arrangements.'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='046569c6844da8e502afbf5f020d639a4cc9d699202fa52df58d86fbfe89034e'
    for name,klass in [('v3',TraceExperiment)]:
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
