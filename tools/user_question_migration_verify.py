"""Real V15 binary seeds byte-pinned old tasks; V16 migrates and resumes them."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import traceback

from p2_verify import Experiment, eventually
from mcp_verify import McpExperiment


class MigrationExperiment(McpExperiment):
    def __init__(self, new_jar, old_jar, evidence, model):
        super().__init__(old_jar, evidence)
        self.current_jar = new_jar.resolve()
        self.fixture.RequestHandlerClass = model
        self.fixture.plans, self.fixture.requests = {}, []

    def raw_snapshot(self):
        # Byte hashes and actual text survive the migration, not reserialized JSON.
        return {table: self.sql(query) for table, query in {
            'task': "SELECT id,SHA2(CAST(request_snapshot AS BINARY),256),SHA2(CAST(tools_snapshot AS BINARY),256),SHA2(CAST(model_snapshot AS BINARY),256),request_snapshot,tools_snapshot,model_snapshot FROM task ORDER BY id;",
            'step': "SELECT id,SHA2(CAST(request_snapshot AS BINARY),256),SHA2(CAST(response_snapshot AS BINARY),256),request_snapshot,response_snapshot FROM step ORDER BY id;",
            'invocation': "SELECT id,SHA2(CAST(input_snapshot AS BINARY),256),SHA2(CAST(output_snapshot AS BINARY),256),input_snapshot,output_snapshot FROM invocation ORDER BY id;"
        }.items()}

    def wait_state(self, task, wanted):
        return eventually(lambda: self.http('GET', '/api/tasks/' + task)[1]['state'] == wanted, 'V15/V16 task ' + wanted)

    def rejected_old(self, old_jar, role):
        folder = self.root / ('old-refused-' + role); folder.mkdir()
        properties = folder / 'runtime.properties'
        config = dict(self.common, **{'jimu.role': role})
        properties.write_text('\n'.join(k + '=' + str(v) for k, v in config.items()) + '\n')
        os.chmod(properties, 0o600)
        for path in [folder, properties]: os.chown(path, 65534, 65534)
        log = self.evidence / ('old-refused-' + role + '.log')
        with log.open('w') as output:
            child = subprocess.Popen(['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', self.java, '-Xmx256m', '-jar', str(old_jar), '--spring.config.additional-location=file:' + str(properties)], stdout=output, stderr=subprocess.STDOUT)
        self.children.append(child)
        try:
            code = child.wait(timeout=45)
            raw = log.read_text()
            self.assert_that(code != 0 and 'Unsupported schema version' in raw and 'Tomcat started on port' not in raw, 'old V15 ' + role + ' fails startup schema gate before readiness', exit=code)
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=5)

    def run(self):
        Experiment.prepare(self)
        old_jar = self.jar
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;') == '15', 'old binary creates actual V15 schema')
        self.fixture.plans['legacy-complete-v15'] = [{'url': self.fixture.origin + '/page/v15-complete'}]
        complete = self.submit('legacy-complete-v15')
        worker = self.start('old-worker-complete', 'worker')
        self.wait_state(complete, 'SUCCEEDED'); self.stop(worker)
        self.fixture.plans['legacy-resume-v15'] = [{'url': self.fixture.origin + '/page/v15-resume'}]
        resume = self.submit('legacy-resume-v15')
        worker = self.start('old-worker-paused-at-T1', 'worker', boundary='T1')
        self.barrier(worker)
        self.assert_that(self.sql("SELECT state FROM invocation WHERE task_id='" + resume + "';") == 'SUCCEEDED', 'V15 unfinished task has successful tool in responded checkpoint')
        self.stop(worker, kill=True)
        self.fixture.plans['legacy-queued-v15'] = []
        queued = self.submit('legacy-queued-v15')
        self.assert_that(self.http('GET', '/api/tasks/' + queued)[1]['state'] == 'QUEUED', 'V15 queue survives until migration')
        before = self.raw_snapshot()
        (self.evidence / 'before-v15.json').write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding='utf-8')
        self.stop(self.control)
        self.jar = self.root / 'jimu-v16.jar'; shutil.copyfile(self.current_jar, self.jar)
        self.control = self.start('control-v16-migration', 'control')
        after = self.raw_snapshot()
        (self.evidence / 'after-v16.json').write_text(json.dumps(after, ensure_ascii=False, indent=2), encoding='utf-8')
        self.assert_that(before == after, 'V16 upgrade preserves all V15 request/model/tool/checkpoint/invocation raw bytes')
        self.assert_that(self.sql('SELECT COUNT(*) FROM task WHERE question_binding IS NOT NULL;') == '0', 'migration never grants question binding to legacy tasks')
        counts = (self.sql('SELECT COUNT(*) FROM task_attempt;'), dict(self.fixture.models), self.sql('SELECT COUNT(*) FROM event;'))
        self.rejected_old(old_jar, 'worker'); self.rejected_old(old_jar, 'control')
        self.assert_that(counts == (self.sql('SELECT COUNT(*) FROM task_attempt;'), dict(self.fixture.models), self.sql('SELECT COUNT(*) FROM event;')), 'old V15 binaries claim nothing and make zero model/tool calls on V16')
        self.start('new-worker-resumes-v15', 'worker')
        self.wait_state(resume, 'SUCCEEDED'); self.wait_state(queued, 'SUCCEEDED')
        self.assert_that(self.fixture.tools['/page/v15-resume'] == 1 and self.fixture.models['legacy-resume-v15'] == 2, 'V16 resumes old successful slot without replaying its effect or model')
        (self.evidence / 'migration-builds.json').write_text(json.dumps({'legacySource': '0e630f28e39c157788886694e2b16dc0831bb571', 'legacyJarSha256': hashlib.sha256(old_jar.read_bytes()).hexdigest(), 'currentJarSha256': hashlib.sha256(self.jar.read_bytes()).hexdigest()}, indent=2))

    def cleanup(self):
        try:
            for child in reversed(self.children): self.stop({'process': child, 'name': 'migration collection'})
            (self.evidence / 'model-wire.json').write_text(json.dumps(self.fixture.requests, ensure_ascii=False, indent=2), encoding='utf-8')
        finally: Experiment.cleanup(self)


def verify_migration(new_jar, old_jar, evidence, model):
    experiment = MigrationExperiment(new_jar, old_jar, evidence, model)
    try: experiment.run()
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally: experiment.cleanup()
