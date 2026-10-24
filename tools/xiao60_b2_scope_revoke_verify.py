"""B2 revoke actual affected public/transaction paths, immutable old/new JARs, private MySQL only."""
import argparse,hashlib,json,shutil,signal,subprocess,traceback
from pathlib import Path
from prior_session_verify import PriorExperiment
from p2_verify import eventually

def verify(e,old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest()=='8c7d6567d01b567322b49de19a34bec7f4328df53029c56261a4be4c3beb8f5a','exact accepted baseline JAR')
    source=e.prior_task('nebula revoke source')
    one=e.start('revoke-source-worker','worker');e.state(source,'SUCCEEDED');e.quiet(source)
    terminal=e.prior_task('caller revoke evidence',[source])
    two=e.start('revoke-caller-worker','worker');e.state(terminal,'SUCCEEDED');e.quiet(terminal)
    e.assert_that(one['process'].poll() is None and two['process'].poll() is None,'dual actual workers observe nonempty runtime scope')
    e.search(terminal,{'query':'nebula'})
    for child in (one,two):e.stop(child)
    queued=e.prior_task('accepted queued revoke',[source])
    foreign=e.prior_task('foreign revoke isolation',subject='b')
    e.assert_that(e.http('DELETE','/api/tasks/'+queued+'/session-search-scope?x=1',subject='c')[0]==404,'public owner rejection precedes malformed revoke')
    e.assert_that(e.http('DELETE','/api/tasks/'+queued+'/session-search-scope?x=1')[0]==400,'public owner malformed revoke rejects input')
    e.assert_that(e.http('DELETE','/api/tasks/'+queued+'/session-search-scope',subject='b')[0]==404,'public cross tenant revoke refused')
    e.stop(e.control);prior=e.root/'prior.jar';shutil.copyfile(old,prior)
    outcomes={};meters={};before=len(e.fixture.requests)
    for tag,binary in [('old',prior),('new',e.jar)]:
        barrier=e.evidence/(tag+'-barrier');barrier.mkdir()
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ScopeRevocationScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),terminal,str(barrier),queued]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            with (e.evidence/(tag+'.log')).open('w') as log:
                child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
                try:
                    def reached():
                        if child.poll() is not None:raise AssertionError('driver exited before barrier; see '+tag+'.log')
                        return (barrier/'waiting').exists()
                    eventually(reached,'revoke contention observation barrier',360)
                    eventually(lambda:int(e.sql('SELECT COUNT(DISTINCT REQUESTING_THREAD_ID) FROM performance_schema.data_lock_waits;'))>=2,'two actual revoke budget lock waits',1)
                    (e.evidence/(tag+'-lock-waits.tsv')).write_text(e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'))
                    (barrier/'release').touch();e.assert_that(child.wait(timeout=45)==0,'actual revoke product driver '+tag)
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
    e.assert_that(outcomes['old']==outcomes['new'] and len(outcomes['new'])==8,'eight exact old/new revoke outcome groups')
    e.assert_that(before==len(e.fixture.requests),'revoke/refusal/rollback/recovery replays no provider')
    e.control=e.start('public-revoke','control')
    e.assert_that(e.http('DELETE','/api/tasks/'+queued+'/session-search-scope')[0]==204,'public revoke returns original204')
    e.assert_that(e.http('DELETE','/api/tasks/'+queued+'/session-search-scope')[0]==204,'public repeat revoke idempotent204')
    e.assert_that(e.sql("SELECT state FROM task WHERE tenant_id='tenant-a' AND id='"+queued+"';")=='CANCELLED','public queued revoke commits cancellation')
    (e.evidence/'differential.json').write_text(json.dumps({'outcomes':outcomes,'meters':meters,'boundary':'Only changed revoke/public/cancellation paths and observed contenders; full lock permutations/core UoW final gap remain OPEN'},indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();e=PriorExperiment(a.jar,a.evidence)
    try:verify(e,a.old_jar)
    except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
