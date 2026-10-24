"""Changed task observation/cancel/events paths only: exact old/new JAR, actual private MySQL."""
import argparse,hashlib,json,shutil,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Experiment,eventually
def verify(e,old):
    e.prepare();e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest()=='148bbc7af10a34c279063d23fe588428216b04aeca6730d8ef7d1f33791e9df5','exact accepted 2874444 baseline')
    prior=e.root/'prior.jar';shutil.copyfile(old,prior)
    published=e.submit('task-get-artifact-metadata');worker=e.start('task-artifact-producer','worker');e.state(published,'SUCCEEDED');e.stop(worker)
    provider_before=(dict(e.fixture.models),dict(e.fixture.tools));e.stop(e.control)
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    (e.evidence/'database.txt').write_text(e.sql('SELECT VERSION(),@@server_uuid,DATABASE();SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;'))
    results={};meters={};fixed={}
    # Fixed database input, including tenant budget: no earlier scenario may change these meters.
    for tag,binary in [('old',prior),('new',e.jar)]:
        cmd=[e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TaskOperationsScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),'fixed-meter',str(e.evidence),published]
        child=subprocess.run(cmd,text=True,capture_output=True,timeout=60)
        (e.evidence/(tag+'-fixed-input.log')).write_text(child.stdout+child.stderr)
        e.assert_that(child.returncode==0,'fixed published-task projection/meter '+tag)
        fixed[tag]=[line for line in child.stdout.splitlines() if line.startswith(('QUERY fixed-','PUBLIC_VIEW ','PUBLIC_EVENTS '))]
    e.assert_that(fixed['old']==fixed['new'],'same task/artifact/event/budget input: exact public values and physical meters')
    for tag,binary in [('old',prior),('new',e.jar)]:
        barrier=e.evidence/(tag+'-barrier');barrier.mkdir()
        cmd=[e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TaskOperationsScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),tag,str(barrier)]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            with (e.evidence/(tag+'.log')).open('w') as log:
                child=subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT)
                try:
                    def waiting():
                        if child.poll() is not None:raise AssertionError('driver exited before barrier; see '+tag+'.log')
                        return (barrier/'waiting').exists()
                    eventually(waiting,'product cancel budget barrier',120)
                    waits=eventually(lambda:e.sql('SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;'),'actual two-connection lock wait',5)
                    (e.evidence/(tag+'-lock-waits.tsv')).write_text(waits)
                    (barrier/'release').touch();e.assert_that(child.wait(timeout=40)==0,'actual task operations '+tag)
                finally:
                    if child.poll() is None:child.kill()
                    child.wait()
            lines=(e.evidence/(tag+'.log')).read_text().splitlines();results[tag]=[s for s in lines if s.startswith('RESULT ')];meters[tag]=[s for s in lines if s.startswith('QUERY ')]
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-transactions.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(results['old']==results['new'] and len(results['new'])==10,'10 exact old/new business outcomes')
    # Independent mutating runs retain question storage. Their later events reads truthfully meter
    # different budget scalars; never assert equal bytes across different database input.
    e.assert_that(provider_before==(dict(e.fixture.models),dict(e.fixture.tools)),'no provider/effect call from direct observation/cancellation differential')
    (e.evidence/'differential.json').write_text(json.dumps({'outcomes':results,'fixedInput':fixed,'independentScenarioMeters':meters,'scope':'byte equality asserted only on identical DB input'},indent=2))
def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();e=Experiment(a.jar,a.evidence)
    try:verify(e,a.old_jar)
    except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
if __name__=='__main__':main()
