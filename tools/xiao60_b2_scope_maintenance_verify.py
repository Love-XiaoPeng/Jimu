"""Only affected scope scheduling/current authority paths, private MySQL and native dual workers."""
import argparse,hashlib,json,shutil,signal,subprocess,traceback
from pathlib import Path
from prior_session_verify import PriorExperiment
from p2_verify import eventually

def verify(e,old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest()=='376f5974f36527bf1b0443fc91fd38e62770843a508254346a7d1d32d3839b29','exact accepted baseline JAR')
    source=e.prior_task('nebula scope maintenance source')
    one=e.start('scope-source-worker','worker');e.state(source,'SUCCEEDED');e.quiet(source)
    caller=e.prior_task('caller scope maintenance evidence',[source])
    two=e.start('scope-caller-worker','worker');e.state(caller,'SUCCEEDED');e.quiet(caller)
    e.assert_that(one['process'].poll() is None and two['process'].poll() is None,'two real scope workers observed live')
    e.prior_task('dependent source denied',[caller],expected=404)
    e.prior_task('foreign owner source denied',[source],subject='c',expected=404)
    e.prior_task('foreign tenant source denied',[source],subject='b',expected=404)
    foreign=e.prior_task('foreign independent retained scope',subject='b');e.state(foreign,'SUCCEEDED');e.quiet(foreign)
    # Failed admissions do not leave caller task/scope records; normal runtime uses the same eligibility service.
    output=e.search(caller,{'query':'nebula'})
    e.assert_that(any(v['session_id']==source for v in output['items']),'native runtime and owner query validate nonempty whole scope')
    for child in (one,two,e.control):e.stop(child)
    before=len(e.fixture.requests);prior=e.root/'prior.jar';shutil.copyfile(old,prior)
    outcomes={};meters={}
    for tag,binary in [('old',prior),('new',e.jar)]:
        barrier=e.evidence/(tag+'-barrier');barrier.mkdir()
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ScopeMaintenanceScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),caller,str(barrier),source]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            with (e.evidence/(tag+'.log')).open('w') as log:
                child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
                try:
                    def reached():
                        if child.poll() is not None:raise AssertionError('driver exited before barrier; see '+tag+'.log')
                        return (barrier/'waiting').exists()
                    eventually(reached,'scope maintenance contention observation barrier',360)
                    eventually(lambda:int(e.sql('SELECT COUNT(DISTINCT REQUESTING_THREAD_ID) FROM performance_schema.data_lock_waits;'))>=2,'two actual scope budget lock waits',1)
                    (e.evidence/(tag+'-lock-waits.tsv')).write_text(e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'))
                    (barrier/'release').touch();e.assert_that(child.wait(timeout=40)==0,'actual scope maintenance product driver '+tag)
                finally:
                    if child.poll() is None:child.kill()
                    child.wait()
            lines=(e.evidence/(tag+'.log')).read_text().splitlines()
            outcomes[tag]=[s for s in lines if s.startswith('RESULT ')]
            meters[tag]={s.split(' ',2)[1]:json.loads(s.split(' ',2)[2]) for s in lines if s.startswith('QUERY ')}
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(outcomes['old']==outcomes['new'] and len(outcomes['new'])==9,'nine exact old/new maintenance outcome groups')
    e.assert_that(before==len(e.fixture.requests),'maintenance rollback/recovery replays no provider or effects')
    (e.evidence/'differential.json').write_text(json.dumps({'outcomes':outcomes,'meters':meters,'boundary':'Only tested whole-scope, final scope beforeCommit and observed budget contenders; core final gate gap and general lock permutations remain OPEN'},indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);args=p.parse_args();e=PriorExperiment(args.jar,args.evidence)
    try:verify(e,args.old_jar)
    except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
