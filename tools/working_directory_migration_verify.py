"""Exact V17 artifact, real old tasks/stores, additive V18 and strict old binary gate."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import traceback

from p2_verify import Experiment, Handler
from working_directory_verify import DirectoryExperiment, Model as DirectoryModel
from todo_verify import Model as TodoModel, items
from user_question_verify import Model as QuestionModel, batch
from user_question_migration_verify import MigrationExperiment
from p3_model_verify import config


class Model(Handler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers['Content-Length']))
        names = {t['function']['name'] for t in json.loads(raw)['tools']}
        self.rfile = io.BytesIO(raw)
        target = DirectoryModel if 'working_directory' in names else TodoModel if 'todo_write' in names else QuestionModel if 'ask_user_question' in names else Handler
        target.do_POST(self)


class DirectoryMigration(DirectoryExperiment):
    raw_snapshot = MigrationExperiment.raw_snapshot

    def run(self, old, current):
        self.fixture.RequestHandlerClass = Model
        self.fixture.plans, self.fixture.requests = {}, []
        self.fixture.final_entered, self.fixture.final_release = threading.Event(), threading.Event()
        # The generic fixture must grant only tables that exist in the old binary.
        import evaluation.application_grants as grants
        original = grants.APPLICATION_TABLES
        grants.APPLICATION_TABLES = tuple(t for t in original if t not in ('task_directory', 'invocation_path_binding'))
        try:
            Experiment.prepare(self)
        finally:
            grants.APPLICATION_TABLES = original
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;') == '17', 'exact old artifact creates schema V17')
        self.sql("GRANT INSERT,UPDATE ON jimu.task_todo_projection TO 'runtime'@'%'; GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
        source = Path(__file__).resolve().parents[1]/'src/main/resources'
        for name, resource in (('todo_write', 'todo/todo_write-0.1.0'), ('ask_user_question', 'interaction/ask_user_question-0.1.0')):
            digest = hashlib.sha256((source/resource/'output.schema.json').read_bytes()).hexdigest()
            self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+name+"','0.1.0','"+digest+"');")
        self.stop(self.control)
        settings = config({'a': self.fixture}, paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        os.environ['JIMU_DIRECTORY_MIGRATION_MODEL_V1'] = self.fixture.credentials['tenant-a']
        settings.update({'jimu.todos.enabled': 'true', 'jimu.questions.enabled': 'true',
            'jimu.model.profiles.a.auth-mode':'bearer', 'jimu.model.profiles.a.credential-ref':'env:JIMU_DIRECTORY_MIGRATION_MODEL_V1',
            'jimu.model.profiles.a.credential-version':'1',
            'jimu.model.profiles.a.model':'local-byte-accounted-v1', 'jimu.model.profiles.a.revision':'local-byte-accounted-v1',
            'jimu.model.profiles.a.response-bytes':'65536', 'jimu.model.profiles.a.input-limit.version':'1',
            'jimu.model.profiles.a.input-limit.max-tokens':'8192', 'jimu.model.profiles.a.input-limit.proof':'test-whole-request-utf8-v1'})
        self.common.update(settings)
        self.control = self.start('v17-configured-control', 'control')
        self.subjects = {}

        def task(goal, capability, plan):
            self.fixture.plans[goal] = plan
            status, value = self.http('POST', '/api/tasks', {'goal':goal,'capabilityIds':[capability]}, key=goal)
            self.assert_that(status == 202, 'old acceptance '+goal, status=status, value=value)
            return value['taskId']

        worker = self.start('v17-seed-worker', 'worker')
        complete = task('legacy-v17-complete', 'web_fetch', [])
        self.state(complete, 'SUCCEEDED')
        todo = task('v17-todo-complete', 'todo_write', [items(('old progress', 'completed'))])
        self.state(todo, 'SUCCEEDED')
        waiting = task('v17-question-waiting', 'ask_user_question', [batch()])
        self.state(waiting, 'WAITING_USER')
        self.stop(worker)
        queued = task('legacy-v17-queued', 'web_fetch', [])
        self.stop(self.control)
        workspace = self.root/'workspace'
        workspace.mkdir(mode=0o700)
        classes = self.root/'probe-classes'
        shutil.copytree(Path(__file__).resolve().parents[1]/'target/test-classes', classes)
        for task_id, format3 in ((queued, False), (complete, True)):
            command = [self.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.storage.DirectoryMigrationSeed',
                       '-Dloader.path='+str(classes), '-cp', str(self.jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                       str(workspace), task_id, str(format3).lower()]
            run = subprocess.run(command, text=True, capture_output=True, timeout=30)
            self.assert_that(run.returncode == 0 and 'SEEDED' in run.stdout, 'old store creates actual historical format '+str(2+format3), exit=run.returncode, stderr=run.stderr)
        state_before = {p.name:p.read_bytes() for p in workspace.glob('*.json')}
        before = self.raw_snapshot()
        checksums = self.sql('SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank;')
        (self.evidence/'before-v17.json').write_text(json.dumps(before, ensure_ascii=True, indent=2))
        old_binary = self.jar
        self.jar = self.root/'v18.jar'
        shutil.copyfile(current.resolve(), self.jar)
        self.common['jimu.workspace-root'] = str(workspace)
        self.control = self.start('v18-migrate-control', 'control')
        self.upgraded = True
        self.assert_that(self.raw_snapshot() == before, 'V18 preserves exact old task tool model checkpoint and invocation bytes')
        self.assert_that(self.sql('SELECT version,checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)<=17 ORDER BY installed_rank;') == checksums, 'V1 through V17 migration checksums unchanged')
        self.assert_that({p.name:p.read_bytes() for p in workspace.glob('*.json')} == state_before, 'old workspace format2 and format3 bytes unchanged')
        self.assert_that(self.sql('SELECT COUNT(*) FROM task_directory;') == '0' and self.sql('SELECT COUNT(*) FROM invocation_path_binding;') == '0', 'old tasks acquire no new directory binding')
        self.assert_that(self.view(complete)['state'] == 'ABSENT' and self.view(todo)['state'] == 'ABSENT', 'old terminal profiles expose ABSENT with directory flag disabled')
        (self.evidence/'after-v18.json').write_text(json.dumps(self.raw_snapshot(), ensure_ascii=True, indent=2))
        for role in ('control', 'worker'):
            folder = self.root/('v17-refused-'+role)
            folder.mkdir()
            settings = dict(self.common, **{'jimu.role':role})
            properties = folder/'runtime.properties'
            properties.write_text('\n'.join(k+'='+str(v) for k,v in settings.items())+'\n')
            os.chmod(properties, 0o600)
            logfile = self.evidence/('v17-refused-'+role+'.log')
            with logfile.open('w') as out:
                child = subprocess.Popen([self.java, '-Xmx256m', '-jar', str(old_binary), '--spring.config.additional-location=file:'+str(properties)], stdout=out, stderr=subprocess.STDOUT)
            self.children.append(child)
            code = child.wait(timeout=45)
            raw = logfile.read_text()
            self.assert_that(code != 0 and 'Unsupported schema version' in raw and 'Tomcat started on port' not in raw, 'old V17 '+role+' rejects V18 before readiness', exit=code)
        worker = self.start('v18-resume-worker', 'worker')
        self.state(queued, 'SUCCEEDED')
        self.assert_that(self.http('GET', '/api/tasks/'+waiting+'/questions')[0] == 200, 'old pending question remains readable after upgrade')
        self.stop(worker)
        (self.evidence/'binaries.json').write_text(json.dumps({'v17Sha256':hashlib.sha256(old.read_bytes()).hexdigest(), 'v18Sha256':hashlib.sha256(current.read_bytes()).hexdigest()}, indent=2))

    def cleanup(self):
        if getattr(self, 'upgraded', False):
            super().cleanup()
        else:
            Experiment.cleanup(self)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--v17-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    experiment = DirectoryMigration(args.v17_jar, args.evidence)
    try:
        experiment.run(args.v17_jar, args.jar)
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
