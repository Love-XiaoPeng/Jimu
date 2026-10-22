"""Real V18 -> V19 upgrade, exact old snapshots and old binary startup refusal."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import traceback

from p2_verify import Experiment
from session_event_verify import SessionExperiment
from working_directory_migration_verify import Model
from user_question_migration_verify import MigrationExperiment
from user_question_verify import batch
from todo_verify import items
from p3_model_verify import config


class SessionMigration(SessionExperiment):
    raw_snapshot = MigrationExperiment.raw_snapshot

    def rejected_old(self, old, role):
        folder = self.root/('v18-refused-'+role)
        folder.mkdir()
        properties = folder/'runtime.properties'
        settings = dict(self.common, **{'jimu.role':role})
        properties.write_text('\n'.join(k+'='+str(v) for k,v in settings.items())+'\n')
        for path in (folder, properties): os.chown(path,65534,65534)
        os.chmod(properties,0o600)
        log = self.evidence/('v18-refused-'+role+'.log')
        with log.open('w') as output:
            child = subprocess.Popen(['setpriv','--reuid=65534','--regid=65534','--clear-groups',self.java,'-Xmx256m','-jar',str(old),'--spring.config.additional-location=file:'+str(properties)], stdout=output,stderr=subprocess.STDOUT)
        self.children.append(child)
        code = child.wait(timeout=45)
        text = log.read_text()
        self.assert_that(code != 0 and 'Unsupported schema version' in text and 'Tomcat started on port' not in text, 'exact V18 '+role+' refuses V19 before readiness',exit=code)

    def run(self, current):
        self.fixture.RequestHandlerClass = Model
        self.fixture.plans, self.fixture.requests = {}, []
        self.fixture.final_entered, self.fixture.final_release = threading.Event(), threading.Event()
        import evaluation.application_grants as grants
        original = grants.APPLICATION_TABLES
        grants.APPLICATION_TABLES = tuple(t for t in original if t not in ('task_session_history', 'session_public_event', 'invocation_session_read'))
        try: Experiment.prepare(self)
        finally: grants.APPLICATION_TABLES = original
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;') == '18', 'exact old V18 artifact creates actual schema18')
        self.sql("GRANT INSERT,UPDATE ON jimu.task_todo_projection TO 'runtime'@'%'; GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
        resources = Path(__file__).resolve().parents[1] / 'src/main/resources'
        for name, folder in (('todo_write', 'todo'), ('ask_user_question', 'interaction')):
            digest = hashlib.sha256((resources / folder / (name+'-0.1.0') / 'output.schema.json').read_bytes()).hexdigest()
            self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+name+"','0.1.0','"+digest+"');")
        self.stop(self.control)
        settings = config({'a': self.fixture}, paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        os.environ['JIMU_SESSION_MIGRATION_MODEL_V1'] = self.fixture.credentials['tenant-a']
        settings.update({'jimu.todos.enabled': 'true', 'jimu.questions.enabled': 'true',
            'jimu.model.profiles.a.auth-mode': 'bearer',
            'jimu.model.profiles.a.credential-ref': 'env:JIMU_SESSION_MIGRATION_MODEL_V1',
            'jimu.model.profiles.a.credential-version': '1',
            'jimu.model.profiles.a.model': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.revision': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.response-bytes': '65536',
            'jimu.model.profiles.a.input-limit.version': '1',
            'jimu.model.profiles.a.input-limit.max-tokens': '8192',
            'jimu.model.profiles.a.input-limit.proof': 'test-whole-request-utf8-v1'})
        self.common.update(settings)
        self.control = self.start('v18-configured-control', 'control')

        def submit(goal, name, plan):
            self.fixture.plans[goal] = plan
            code, value = self.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': [name]}, key=goal)
            self.assert_that(code == 202, 'V18 accepts '+goal, code=code, response=value)
            return value['taskId']

        worker = self.start('v18-seed-worker', 'worker')
        complete = submit('legacy-v18-complete', 'web_fetch', [])
        self.state(complete, 'SUCCEEDED')
        todo = submit('v18-todo-complete', 'todo_write', [items(('old progress', 'completed'))])
        self.state(todo, 'SUCCEEDED')
        waiting = submit('v18-question-waiting', 'ask_user_question', [batch()])
        self.state(waiting, 'WAITING_USER')
        self.stop(worker)
        queued = submit('legacy-v18-queued', 'web_fetch', [])
        self.stop(self.control)
        before = self.raw_snapshot()
        checksums = self.sql('SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank;')
        (self.evidence/'before-v18.json').write_text(json.dumps(before, indent=2))
        old = self.jar
        self.jar = self.root/'jimu-v19.jar'
        shutil.copyfile(current, self.jar)
        self.control = self.start('v19-migration-control', 'control')
        after = self.raw_snapshot()
        (self.evidence/'after-v19.json').write_text(json.dumps(after, indent=2))
        self.assert_that(before == after, 'V19 preserves exact old task/model/tool/checkpoint/invocation bytes')
        self.assert_that(checksums == self.sql('SELECT version,checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)<=18 ORDER BY installed_rank;'), 'V1-V18 checksums unchanged')
        self.assert_that(self.sql('SELECT COUNT(*) FROM task_session_history;') == '0', 'legacy tasks have no backfilled native history')
        code, value = self.http('GET', '/api/tasks/'+complete+'/session-history')
        self.assert_that(code == 409 and value['code'] == 'SESSION_HISTORY_UNAVAILABLE', 'legacy history is explicitly unavailable')
        counts = (self.sql('SELECT COUNT(*) FROM task_attempt;'), dict(self.fixture.models))
        for role in ('worker', 'control'): self.rejected_old(old, role)
        self.assert_that(counts == (self.sql('SELECT COUNT(*) FROM task_attempt;'), dict(self.fixture.models)), 'old V18 control/worker claim no work on V19')
        worker = self.start('v19-legacy-resume-worker', 'worker')
        self.state(queued, 'SUCCEEDED')
        self.stop(worker)
        self.assert_that(self.http('GET', '/api/tasks/'+complete)[1]['state'] == 'SUCCEEDED', 'flag-off V19 retains original terminal task history')
        self.assert_that(self.http('GET','/api/tasks/'+todo+'/todos')[0] == 200 and self.http('GET','/api/tasks/'+waiting+'/questions')[0] == 200, 'V19 preserves old todo and pending question APIs')
        (self.evidence/'binaries.json').write_text(json.dumps({'oldSource': '270a5d15611f5d7e59bbbd1d96c94d07567fc858',
            'v18JarSha256': hashlib.sha256(old.read_bytes()).hexdigest(), 'v19JarSha256': hashlib.sha256(self.jar.read_bytes()).hexdigest()}, indent=2))

    def cleanup(self):
        Experiment.cleanup(self)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--v18-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    experiment = SessionMigration(args.v18_jar, args.evidence)
    try: experiment.run(args.jar.resolve())
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally: experiment.cleanup()
