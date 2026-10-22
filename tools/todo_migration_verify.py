"""Real V16 seeds -> V17 migration, old snapshots and strict old binary gate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import traceback

from p2_verify import Experiment, eventually
from todo_verify import TodoExperiment, Model
from user_question_verify import batch
from user_question_migration_verify import MigrationExperiment


class TodoMigration(TodoExperiment):
    raw_snapshot=MigrationExperiment.raw_snapshot

    def rejected_old(self, old_jar, role):
        folder=self.root/('v16-refused-'+role);folder.mkdir()
        properties=folder/'runtime.properties'
        settings=dict(self.common,**{'jimu.role':role})
        properties.write_text('\n'.join(k+'='+str(v) for k,v in settings.items())+'\n')
        os.chmod(properties,0o600)
        for path in (folder,properties):os.chown(path,65534,65534)
        log=self.evidence/('v16-refused-'+role+'.log')
        with log.open('w') as output:
            child=subprocess.Popen(['setpriv','--reuid=65534','--regid=65534','--clear-groups',self.java,'-Xmx256m','-jar',str(old_jar),'--spring.config.additional-location=file:'+str(properties)],stdout=output,stderr=subprocess.STDOUT)
        self.children.append(child)
        try:
            code=child.wait(timeout=45);raw=log.read_text()
            self.assert_that(code!=0 and 'Unsupported schema version' in raw and 'Tomcat started on port' not in raw,'exact V16 '+role+' rejects V17 schema before readiness',exit=code)
        finally:
            if child.poll() is None:child.kill();child.wait(timeout=5)

    def run(self, old, current):
        self.jar=old.resolve();self.fixture.RequestHandlerClass=Model
        self.fixture.plans,self.fixture.requests={},[]
        Experiment.prepare(self)
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;')=='16','exact old binary creates V16 schema')
        self.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
        source=Path(__file__).resolve().parents[1]/'src/main/resources/interaction/ask_user_question-0.1.0/output.schema.json'
        digest=hashlib.sha256(source.read_bytes()).hexdigest()
        self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','ask_user_question','0.1.0','"+digest+"');")
        self.stop(self.control);self.common['jimu.questions.enabled']='true'
        self.control=self.start('v16-questions-control','control')
        from user_question_verify import Model as QuestionModel
        self.fixture.RequestHandlerClass=QuestionModel
        self.fixture.final_entered,self.fixture.final_release=__import__('threading').Event(),__import__('threading').Event()
        self.subjects={}

        def submit(goal, capability):
            status,value=self.http('POST','/api/tasks',{'goal':goal,'capabilityIds':[capability]},key=goal)
            assert status==202,value
            return value['taskId']

        def pending(task):
            return eventually(lambda:self.http('GET','/api/tasks/'+task+'/questions')[1]['items'],'V16 question')[0]

        def answer(task):
            q=pending(task)
            status,result=self.http('POST','/api/tasks/'+task+'/questions/'+q['requestId']+'/answers',{'questionVersion':1,'expectedRevision':q['revision'],'answers':[{'id':'choice','selected':['A']}]},key='answer-'+task)
            assert status==202,result

        self.fixture.plans['question-complete']=[batch()]
        complete=submit('question-complete','ask_user_question')
        worker=self.start('v16-complete-worker','worker');self.state(complete,'WAITING_USER');answer(complete);self.state(complete,'SUCCEEDED');self.stop(worker)
        self.fixture.plans['question-waiting']=[batch()]
        waiting=submit('question-waiting','ask_user_question')
        worker=self.start('v16-waiting-worker','worker');self.state(waiting,'WAITING_USER');self.stop(worker)
        self.fixture.plans['legacy-responded']=[{'url':self.fixture.origin+'/page/old'}]
        responded=submit('legacy-responded','web_fetch')
        worker=self.start('v16-responded-worker','worker','T1');self.barrier(worker)
        self.assert_that(self.sql("SELECT state FROM invocation WHERE task_id='"+responded+"';")=='SUCCEEDED','old responded task contains actual successful tool')
        self.stop(worker,kill=True)
        self.fixture.plans['legacy-queued']=[]
        queued=submit('legacy-queued','web_fetch')
        self.assert_that(self.http('GET','/api/tasks/'+queued)[1]['state']=='QUEUED','actual V16 queued task')
        before=self.raw_snapshot();checksums=self.sql('SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank;')
        (self.evidence/'before-v16.json').write_text(json.dumps(before,ensure_ascii=False,indent=2))
        self.stop(self.control)
        old_binary=self.jar
        self.jar=self.root/'v17.jar';shutil.copyfile(current.resolve(),self.jar)
        self.control=self.start('v17-migration-control','control')
        self.assert_that(self.raw_snapshot()==before,'V17 preserves every original task model tool checkpoint invocation byte')
        self.assert_that(self.sql('SELECT version,checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)<=16 ORDER BY installed_rank;')==checksums,'V1-V16 checksums unchanged')
        self.assert_that(self.sql('SELECT COUNT(*) FROM task_todo_projection;')=='0' and self.sql('SELECT COUNT(*) FROM invocation WHERE todo_expected_revision IS NOT NULL;')=='0','new binding grants no todo capability to old rows')
        (self.evidence/'after-v17.json').write_text(json.dumps(self.raw_snapshot(),ensure_ascii=False,indent=2))
        for role in ('control','worker'):self.rejected_old(old_binary,role)
        self.expire(responded)
        worker=self.start('v17-resume-worker','worker')
        answer(waiting);self.state(waiting,'SUCCEEDED');self.state(responded,'SUCCEEDED');self.state(queued,'SUCCEEDED');self.stop(worker)
        self.assert_that(self.http('GET','/api/tasks/'+complete+'/todos')[1]['state']=='ABSENT','old question task has no todo projection')
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+responded+"';")=='1','old successful tool restored without duplicate invocation')
        (self.evidence/'binaries.json').write_text(json.dumps({'v16Sha256':hashlib.sha256(old.read_bytes()).hexdigest(),'v17Sha256':hashlib.sha256(current.read_bytes()).hexdigest()},indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--v16-jar',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();experiment=TodoMigration(args.v16_jar,args.evidence)
    try:experiment.run(args.v16_jar,args.jar)
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:experiment.cleanup()
