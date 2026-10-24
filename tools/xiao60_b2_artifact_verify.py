"""Actual download boundary on exact prior/current JARs; private DB/files/provider only."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import time
import traceback
from p2_verify import Experiment, eventually


def verify(e, old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() ==
        '5dbaf82e4d132c15239c2fd9f5696b7ab5cedd6802474052415f5c84e78f343c', 'exact c5aeb1c baseline JAR')
    task=e.submit('artifact-download-slice')
    worker=e.start('artifact-producer','worker')
    view=e.state(task,'SUCCEEDED'); e.stop(worker)
    e.assert_that(len(view['artifacts'])==1,'actual product publication supplies download fixture')
    artifact=view['artifacts'][0]; aid=artifact['artifactId']
    metadata=e.sql("SELECT snapshot_key,size,sha256 FROM artifact WHERE tenant_id='tenant-a' AND id='"+aid+"';").split('\t')
    path=e.root/'objects'/hashlib.sha256(b'tenant-a').hexdigest()/metadata[0]
    original=path.read_bytes()
    frozen=e.sql("SELECT tenant_id,task_id,producer_invocation,upload_id,production_fence,sha256,size,media_type,record_version FROM artifact WHERE tenant_id='tenant-a' AND id='"+aid+"';")
    e.stop(e.control)
    current=e.jar; prior=e.root/'prior.jar'; shutil.copyfile(old,prior)
    results={}; meters={}
    for tag,binary in [('old',prior),('new',current)]:
        e.jar=binary; e.control=e.start('download-'+tag,'control')
        e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
        values=[]
        def check(name,status,subject='a'):
            actual,body=e.http('GET','/api/artifacts/'+aid+'/download',subject=subject)
            e.assert_that(actual==status,'download '+tag+' '+name,status=actual,expected=status)
            value=hashlib.sha256(body.encode()).hexdigest() if actual==200 else body.get('code')
            if actual==200:e.assert_that(value==artifact['sha256'],'download immutable bytes '+tag+' '+name)
            values.append({'case':name,'status':actual,'value':value})
        try:
            check('published',200);check('other-tenant',404,'b');check('other-owner',404,'c')
            e.sql("UPDATE artifact SET state='EXPIRED',expires_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 DAY WHERE tenant_id='tenant-a' AND id='"+aid+"';")
            check('expired-state-future-time-original-semantics',200)
            e.sql("UPDATE artifact SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY WHERE tenant_id='tenant-a' AND id='"+aid+"';")
            check('expired-time',410)
            e.sql("UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
            check('revoked-before-expiry',403)
            e.sql("UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='web_fetch'; UPDATE artifact SET state='STAGED',expires_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 DAY WHERE tenant_id='tenant-a' AND id='"+aid+"';")
            check('unpublished',404)
            e.sql("UPDATE artifact SET state='PUBLISHED' WHERE tenant_id='tenant-a' AND id='"+aid+"'; UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';")
            check('actor-revoked',403)
            e.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'; UPDATE installation SET epoch=epoch+1 WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
            check('pin-epoch-revoked',403)
            e.sql("UPDATE installation SET epoch=epoch-1 WHERE tenant_id='tenant-a' AND plugin_id='web_fetch'; UPDATE artifact SET size=size+1 WHERE tenant_id='tenant-a' AND id='"+aid+"';")
            check('length-mismatch',503)
            e.sql("UPDATE artifact SET size="+metadata[1]+",sha256='"+('0'*64)+"' WHERE tenant_id='tenant-a' AND id='"+aid+"';")
            check('digest-mismatch',503)
            e.sql("UPDATE artifact SET sha256='"+metadata[2]+"' WHERE tenant_id='tenant-a' AND id='"+aid+"';")
            saved=path.with_name(path.name+'.private-backup');path.rename(saved)
            try:check('missing-object',503)
            finally:saved.rename(path)
            check('restored',200)
            e.stop(e.control)
            barrier=e.evidence/(tag+'-barrier');barrier.mkdir()
            command=[e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ArtifactDownloadScenario',
                '-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),
                'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),aid,str(barrier)]
            with (e.evidence/(tag+'-meter-lock.log')).open('w') as log:
                child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
                try:
                    eventually(lambda:(barrier/'waiting').exists() or (child.poll() is not None and (_ for _ in ()).throw(AssertionError('driver exited'))),'download lock barrier',40)
                    waits=eventually(lambda:e.sql("SELECT REQUESTING_ENGINE_TRANSACTION_ID,REQUESTING_THREAD_ID,BLOCKING_ENGINE_TRANSACTION_ID,BLOCKING_THREAD_ID FROM performance_schema.data_lock_waits;"),'actual actor lock wait',5)
                    (e.evidence/(tag+'-lock-waits.tsv')).write_text(waits)
                    (barrier/'release').touch()
                    e.assert_that(child.wait(timeout=30)==0,'actual metering/deadline/two-connection lock '+tag)
                finally:
                    if child.poll() is None:child.kill()
                    child.wait()
            results[tag]=values
            meters[tag]=json.loads(next(line.removeprefix('QUERY ') for line in (e.evidence/(tag+'-meter-lock.log')).read_text().splitlines() if line.startswith('QUERY ')))
            e.assert_that(frozen==e.sql("SELECT tenant_id,task_id,producer_invocation,upload_id,production_fence,sha256,size,media_type,record_version FROM artifact WHERE tenant_id='tenant-a' AND id='"+aid+"';"),'no ledger rewrite '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-transactions.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
            e.stop(e.control)
    e.jar=current
    e.assert_that(results['old']==results['new'] and len(results['new'])==13,'13 exact prior/current download outcomes')
    e.assert_that(meters['old']==meters['new'],'same complete physical download projection metering',values=meters)
    e.assert_that(path.read_bytes()==original,'private snapshot unchanged after fault probes')
    (e.evidence/'differential.json').write_text(json.dumps(results,indent=2))


def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('artifact supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();e=Experiment(a.jar,a.evidence)
    try:verify(e,a.old_jar)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()

if __name__=='__main__':main()
