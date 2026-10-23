"""Exact V19 -> V20 upgrade, immutable old bytes and old-binary refusal before readiness."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import traceback

from session_event_verify import SessionExperiment
from user_question_migration_verify import MigrationExperiment


class SearchMigration(SessionExperiment):
    def snapshots(self):
        values=MigrationExperiment.raw_snapshot(self)
        values['native']=self.sql('SELECT tenant_id,task_id,seq,event_version,HEX(event_snapshot),event_digest,source_digest FROM session_public_event ORDER BY tenant_id,task_id,seq;')
        values['binding']=self.sql('SELECT tenant_id,task_id,history_version,HEX(binding_snapshot),binding_digest FROM task_session_history ORDER BY tenant_id,task_id;')
        return values

    def run(self,current):
        self.prepare()
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;') == '19','exact old artifact creates V19')
        task=self.task('S10 legacy v1 complete',[{'seq':0}])
        worker=self.start('v19-seed-worker','worker'); self.state(task,'SUCCEEDED'); self.stop(worker)
        queued=self.task('S10 legacy v1 queued',[{'seq':0}])
        self.stop(self.control)
        before=self.snapshots()
        checksums=self.sql('SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank;')
        old=self.jar
        self.jar=self.root/'jimu-v20.jar'; shutil.copyfile(current,self.jar)
        self.control=self.start('v20-upgrade-control','control')
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;') == '20','real V20 migration')
        after=self.snapshots()
        (self.evidence/'before-v19.json').write_text(json.dumps(before,indent=2))
        (self.evidence/'after-v20.json').write_text(json.dumps(after,indent=2))
        self.assert_that(before == after,'all old request/model/tools/checkpoint/invocation/binding/native bytes unchanged')
        self.assert_that(checksums == self.sql('SELECT version,checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)<=19 ORDER BY installed_rank;'),'V1-V19 checksums unchanged')
        self.assert_that(self.metadata(task)['version'] == 1 and self.event(task,0)['target']['version'] == 1,'v1 owner keeps original wire version')
        original=self.sql("SELECT plugin_digest FROM installation WHERE tenant_id='tenant-a' AND plugin_id='session_event_read';")
        resource=Path(__file__).resolve().parents[1]/'src/main/resources/session/v2/session_event_read-0.2.0/output.schema.json'
        v2digest=hashlib.sha256(resource.read_bytes()).hexdigest()
        self.sql("UPDATE installation SET plugin_version='0.2.0',plugin_digest='"+v2digest+"' WHERE tenant_id='tenant-a' AND plugin_id='session_event_read';")
        try:
            self.assert_that(self.http('GET','/api/tasks/'+task+'/session-history')[0] == 403,'explicit new read installation revokes old task pin')
        finally:
            self.sql("UPDATE installation SET plugin_version='0.1.0',plugin_digest='"+original+"' WHERE tenant_id='tenant-a' AND plugin_id='session_event_read';")
        self.event(task,0)
        status,value=self.http('POST','/api/tasks/'+task+'/session-history/search',{'query':'legacy'})
        self.assert_that(status == 409 and value['code'] == 'SESSION_SEARCH_UNAVAILABLE','v1 cannot acquire search authority')
        worker=self.start('v20-resume-v1-worker','worker'); self.state(queued,'SUCCEEDED'); self.stop(worker)
        self.ledger_scenario(worker,queued,'accounting')
        self.stop(self.control)
        self.control=self.start('v20-flagoff-control','control',extra={'jimu.session-event-search.enabled':'false'})
        self.event(task,0)
        self.assert_that('session_event_search' not in self.http('GET','/api/capabilities')[1],'understanding V20 with flagoff is supported rollback')
        # Reuse the established private unprivileged old-binary gate launcher.
        for role in ('control','worker'):
            folder=self.root/('v19-refused-'+role); folder.mkdir()
            properties=folder/'runtime.properties'
            properties.write_text('\n'.join(k+'='+str(v) for k,v in dict(self.common,**{'jimu.role':role}).items())+'\n')
            for path in (folder,properties): os.chown(path,65534,65534)
            os.chmod(properties,0o600)
            log=self.evidence/('v19-refused-'+role+'.log')
            with log.open('w') as output:
                child=subprocess.Popen(['setpriv','--reuid=65534','--regid=65534','--clear-groups',self.java,'-Xmx256m','-jar',str(old),'--spring.config.additional-location=file:'+str(properties)],stdout=output,stderr=subprocess.STDOUT)
            self.children.append(child)
            code=child.wait(timeout=45); text=log.read_text()
            self.assert_that(code != 0 and 'Unsupported schema version' in text and 'Tomcat started on port' not in text,
                'exact V19 '+role+' refuses20 before readiness',exit=code)
        self.results.append({'oldJarSha256':hashlib.sha256(old.read_bytes()).hexdigest(),
            'newJarSha256':hashlib.sha256(self.jar.read_bytes()).hexdigest()})


if __name__ == '__main__':
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser=argparse.ArgumentParser()
    parser.add_argument('--jar',type=Path,required=True); parser.add_argument('--old-jar',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args(); experiment=SearchMigration(args.old_jar,args.evidence)
    try: experiment.run(args.jar.resolve())
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc()); raise
    finally: experiment.cleanup()
