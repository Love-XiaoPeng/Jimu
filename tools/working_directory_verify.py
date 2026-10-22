"""Foreground local directory acceptance; private database and supervised real JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import time
import traceback
import uuid
from concurrent.futures import ThreadPoolExecutor

from p2_verify import Experiment, Handler, eventually
from mcp_verify import McpExperiment
from p3_model_verify import config

CAPS = ['working_directory', 'read', 'write', 'edit']


def call(name, **arguments):
    return (name, arguments)


class Model(Handler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        replies = [json.loads(m['content']) for m in body['messages'] if m['role'] == 'tool']
        if goal.startswith('R1-legacy-'):
            assert {t['function']['name'] for t in body['tools']} == {'read', 'write', 'edit'}
            assert not any('working_directory_context' in m['content'] for m in body['messages'])
        else:
            assert {t['function']['name'] for t in body['tools']} == set(CAPS)
            assert all(m['role'] != 'system' for m in body['messages'])
            context = json.loads(body['messages'][-1]['content'])
            assert context['kind'] == 'working_directory_context' and context['version'] == 1
            assert context['cwd'].startswith('/') and not context['cwd'].startswith('/tmp/')
        at = len(replies)
        batch = None
        for planned in self.server.plans[goal]:
            if at == 0:
                batch = planned
                break
            at -= len(planned)
        message = {'role': 'assistant', 'content': 'x'*8193 if goal == 'D09-summary' else 'Verified local logical directory result.'}
        if batch is not None:
            message['content'] = ''
            message['tool_calls'] = [{'id': 'slot-' + str(len(replies)+i), 'type': 'function',
                'function': {'name': name, 'arguments': args if isinstance(args, str) else json.dumps(args, ensure_ascii=True)}}
                for i, (name, args) in enumerate(batch)]
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0)+1
            self.server.requests.append({'goal': goal, 'body': body, 'replies': replies})
        response = {'id': 'directory-fixture', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if batch is not None else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}}
        if body['stream']:
            delta = dict(message)
            for i, value in enumerate(delta.get('tool_calls', [])):
                value['index'] = i
            data = ('data: '+json.dumps({'choices': [{'index': 0, 'delta': delta,
                'finish_reason': response['choices'][0]['finish_reason']}]})+'\n\n'
                +'data: '+json.dumps({'choices': [], 'usage': response['usage']})+'\n\n'
                +'data: [DONE]\n\n').encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send(200, response)


class DirectoryExperiment(Experiment):
    start = McpExperiment.start
    state = McpExperiment.state

    def stop(self, instance, kill=False):
        child = instance['process']
        if kill and child.poll() is None:
            child.kill()
            eventually(lambda: self.sql('SELECT stopped FROM worker_process WHERE pid='+str(child.pid)+';') == '1', 'supervisor confirms exact killed worker')
        Experiment.stop(self, instance)

    def prepare(self):
        self.fixture.RequestHandlerClass = Model
        self.fixture.plans, self.fixture.requests = {}, []
        super().prepare()
        for table in ('task_directory', 'invocation_path_binding'):
            self.sql("GRANT INSERT,UPDATE ON jimu."+table+" TO 'runtime'@'%';")
        source = Path(__file__).resolve().parents[1]/'src/main/resources'
        for name in CAPS:
            resource = source/('directory/working_directory-0.1.0' if name == 'working_directory' else 'plugins/builtin/'+name)
            digest = hashlib.sha256((resource/'output.schema.json').read_bytes()).hexdigest()
            self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES"
                +','.join("('"+tenant+"','"+name+"','0.1.0','"+digest+"')" for tenant in ('tenant-a', 'tenant-b'))+';')
        self.assert_that('working_directory' not in self.http('GET', '/api/capabilities')[1], 'default disabled hides working_directory')
        status, error = self.http('POST', '/api/tasks', {'goal': 'disabled', 'capabilityIds': CAPS}, key='disabled')
        self.assert_that(status == 403 and error['code'] == 'DIRECTORY_NOT_AUTHORIZED', 'default disabled rejects profile')
        self.stop(self.control)
        workspace = self.root/'workspace'
        workspace.mkdir(mode=0o700)
        settings = config({'a': self.fixture}, paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        settings.update({'jimu.model.profiles.a.model': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.revision': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.response-bytes': '65536', 'jimu.model.profiles.a.input-limit.version': '1',
            'jimu.model.profiles.a.input-limit.max-tokens': '8192',
            'jimu.model.profiles.a.input-limit.proof': 'test-whole-request-utf8-v1',
            'jimu.working-directory.enabled': 'true', 'jimu.workspace-root': str(workspace),
            'jimu.web.mode': 'public', 'jimu.web.allowed-domains': 'fixture.example',
            'jimu.web.public-dispatch-enabled': 'true'})
        self.common.update(settings)
        self.control = self.start('control-directory', 'control')
        self.sql('UPDATE tenant_budget SET directory_storage_limit=268435456,model_limit=1000,tool_limit=1000,token_limit=16777216;')
        self.subjects = {}
        self.task_subjects = self.subjects

    def task(self, goal, plan, subject='a', **fields):
        self.fixture.plans[goal] = plan
        status, value = self.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': CAPS, **fields}, key=goal, subject=subject)
        self.assert_that(status == 202, 'accept '+goal, status=status, value=value)
        self.subjects[value['taskId']] = subject
        return value['taskId']

    def view(self, task, subject=None):
        status, value = self.http('GET', '/api/tasks/'+task+'/working-directory', subject=subject or self.subjects.get(task, 'a'))
        assert status == 200, (status, value)
        return value

    def normal(self):
        task = self.task('D01-normal', [
            [call('working_directory'), call('read', file_path='a/witness.txt'), call('write', file_path='a/witness.txt', content='first')],
            [call('working_directory', cd='a'), call('read', file_path='witness.txt'), call('write', file_path='witness.txt', content='second'), call('edit', file_path='witness.txt', old_string='second', new_string='final')],
            [call('working_directory')]])
        self.assert_that(self.view(task)['cwd'] == '/' and self.view(task)['revision'] == 0, 'initial logical root')
        first = self.start('worker-directory-one', 'worker')
        second = self.start('worker-directory-two', 'worker')
        self.state(task, 'SUCCEEDED')
        view = self.view(task)
        self.assert_that(view['cwd'] == '/a' and view['revision'] == 1, 'logical cd persisted', view=view)
        inputs = self.sql("SELECT tool,input_snapshot FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal;")
        self.assert_that('witness.txt' in inputs, 'original relative provider input retained')
        outputs = self.sql("SELECT output_snapshot FROM invocation WHERE task_id='"+task+"' AND tool='edit';")
        self.assert_that(json.loads(outputs)['filePath'] == 'a/witness.txt' and json.loads(outputs)['sha256'] == hashlib.sha256(b'final').hexdigest(), 'relative write and edit hit canonical file', output=json.loads(outputs))
        self.assert_that(self.sql("SELECT tools_used,models_used FROM task WHERE id='"+task+"';") == '8\t4', 'exact tool and model charges')
        self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='working_directory.changed';") == '1', 'read does not change revision or emit transition')
        for subject in ('b', 'c'):
            self.assert_that(self.http('GET', '/api/tasks/'+task+'/working-directory', subject=subject)[0] == 404, 'owner and tenant isolation '+subject)
        self.stop(first)
        self.stop(second)
        self.stop(self.control)
        self.control = self.start('control-reconnected', 'control')
        self.assert_that(self.view(task) == view, 'control restart and owner reconnect preserve snapshot')
        self.complete = task

    @staticmethod
    def sentinels():
        return [[call('read', file_path='a/x'), call('write', file_path='a/x', content='sentinel-A'),
                 call('read', file_path='b/x'), call('write', file_path='b/x', content='sentinel-B')],
                [call('working_directory', cd='a'), call('read', file_path='x'),
                 call('working_directory', cd='../b'), call('read', file_path='x')]]

    def ordered(self):
        for stream in (False, True):
            mode = 'stream' if stream else 'call'
            # A task pins its model profile at acceptance; both control and worker agree.
            self.stop(self.control)
            self.common.update({'jimu.model.profiles.a.stream': str(stream).lower(),
                'jimu.model.profiles.a.capabilities.stream-text': 'true',
                'jimu.model.profiles.a.capabilities.stream-tools': 'true',
                'jimu.model.profiles.a.capabilities.usage-stream': 'true'})
            self.control = self.start('D02-control-'+mode, 'control')
            task = self.task('D02-'+mode, self.sentinels())
            worker = self.start('D02-worker-'+mode, 'worker')
            self.state(task, 'SUCCEEDED')
            rows = self.sql("SELECT i.output_snapshot FROM invocation i JOIN step s ON s.tenant_id=i.tenant_id AND s.id=i.step_id WHERE i.task_id='"+task+"' AND s.ordinal=2 ORDER BY i.ordinal;")
            outputs = [json.loads(row) for row in rows.splitlines()]
            self.assert_that(outputs[0]['cwd'] == '/a' and outputs[2]['cwd'] == '/b' and
                             outputs[1]['filePath'] == 'a/x' and outputs[1]['sha256'] == hashlib.sha256(b'sentinel-A').hexdigest() and
                             outputs[3]['filePath'] == 'b/x' and outputs[3]['sha256'] == hashlib.sha256(b'sentinel-B').hexdigest(),
                             'interleaved duplicate tool names follow response index '+mode, outputs=outputs)
            frozen = self.sql("SELECT base_cwd,canonical_paths_snapshot FROM invocation_path_binding f JOIN invocation i ON i.tenant_id=f.tenant_id AND i.id=f.invocation_id JOIN step s ON s.tenant_id=i.tenant_id AND s.id=i.step_id WHERE i.task_id='"+task+"' AND s.ordinal=2 AND f.mode='FILES' ORDER BY i.ordinal;")
            self.assert_that(frozen == '/a\t["a/x"]\n/b\t["b/x"]', 'canonical paths frozen after preceding cd '+mode, frozen=frozen)
            self.assert_that(self.view(task)['revision'] == 2 and self.view(task)['cwd'] == '/b', 'two durable changes '+mode)
            self.stop(worker)

    def recovery(self):
        for boundary in ('M2', 'DIRECTORY_FROZEN', 'DIRECTORY_COMMIT_BEFORE', 'DIRECTORY_COMMIT_AFTER'):
            goal = 'D03-'+boundary
            task = self.task(goal, self.sentinels())
            worker = self.start(goal+'-paused', 'worker', boundary)
            self.barrier(worker)
            checkpoint = self.sql("SELECT id,request_snapshot,response_snapshot,request_digest,response_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal;")
            original = self.sql("SELECT id,slot,provider_id,input_snapshot,input_digest FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal;")
            self.stop(worker, kill=True)
            self.expire(task)
            resumed = self.start(goal+'-resumed', 'worker')
            self.state(task, 'SUCCEEDED')
            self.assert_that(self.sql("SELECT id,request_snapshot,response_snapshot,request_digest,response_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal;").startswith(checkpoint),
                             'original checkpoint and response survive '+boundary)
            self.assert_that(set(original.splitlines()).issubset(set(self.sql("SELECT id,slot,provider_id,input_snapshot,input_digest FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal;").splitlines())),
                             'original invocation identities survive '+boundary)
            view = self.view(task)
            self.assert_that(view['cwd'] == '/b' and view['revision'] == 2 and
                             self.sql("SELECT tools_used,models_used,fence FROM task WHERE id='"+task+"';") == '8\t3\t2',
                             'fenced recovery without duplicate charges '+boundary, view=view)
            self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='working_directory.changed';") == '2',
                             'exactly one event per committed transition '+boundary)
            self.stop(resumed)

    def invalid_paths(self):
        worker = self.start('D05-worker', 'worker')
        for index, (args, expected) in enumerate((
            ('{"cd":"/","cd":"a"}', 'TOOL_INPUT_INVALID'), ('{} {}', 'TOOL_INPUT_INVALID'),
            ('{"cd":null}', 'TOOL_INPUT_INVALID'), ('{"cd":""}', 'TOOL_INPUT_INVALID'),
            ('{"unknown":1}', 'TOOL_INPUT_INVALID'), ('{"cd":"\\ud800"}', 'TOOL_INPUT_INVALID'),
            ({'cd': '..'}, 'FS_PATH_DENIED'), ({'cd': 'a//b'}, 'FS_PATH_DENIED'),
            ({'cd': 'C:/x'}, 'FS_PATH_DENIED'), ({'cd': 'missing'}, 'FS_NOT_FOUND'),
        )):
            task = self.task('D05-invalid-'+str(index), [[('working_directory', args)]])
            self.state(task, 'FAILED')
            code = self.sql("SELECT code FROM task WHERE id='"+task+"';")
            self.assert_that(code == expected and self.view(task)['revision'] == 0 and self.view(task)['cwd'] == '/',
                             'invalid directory has no projection effect '+str(index), code=code)
        task = self.task('D05-file-cd', [[call('read', file_path='a'), call('write', file_path='a', content='file')], [call('working_directory', cd='a')]])
        self.state(task, 'FAILED')
        self.assert_that(self.sql("SELECT code FROM task WHERE id='"+task+"';") == 'FS_NOT_DIRECTORY' and self.view(task)['revision'] == 0,
                         'file is never a directory')
        for first, second in (('a', 'a/b'), ('a/b', 'a')):
            task = self.task('D05-conflict-'+first.replace('/', '-'), [[call('read', file_path=first), call('write', file_path=first, content='original')],
                             [call('read', file_path=second), call('write', file_path=second, content='conflict')]])
            self.state(task, 'FAILED')
            self.assert_that(self.sql("SELECT code FROM task WHERE id='"+task+"';") == 'FS_NAMESPACE_CONFLICT', 'file namespace conflict both orders '+first)
        self.stop(worker)

    def observation(self):
        worker = self.start('D06-worker', 'worker')
        task = self.task('D06-isolated-observation', [[call('read', file_path='a/x'), call('write', file_path='a/x', content='sentinel-A'),
                         call('read', file_path='b/other'), call('write', file_path='b/other', content='sentinel-B')],
                         [call('working_directory', cd='a'), call('read', file_path='x'), call('working_directory', cd='../b'),
                          call('write', file_path='x', content='must-not-write')]])
        # b exists through b/other; b/x has never been observed.
        self.state(task, 'FAILED')
        self.assert_that(self.sql("SELECT code FROM task WHERE id='"+task+"';") == 'FS_NOT_OBSERVED', 'observation never transfers between directories')
        task = self.task('D06-canonical-alias', [self.sentinels()[0],
                         [call('working_directory', cd='a'), call('read', file_path='./x'),
                          call('write', file_path='/a/./x', content='alias-update')]])
        self.state(task, 'SUCCEEDED')
        output = json.loads(self.sql("SELECT output_snapshot FROM invocation WHERE task_id='"+task+"' AND tool='write' ORDER BY ordinal DESC LIMIT 1;"))
        self.assert_that(output['filePath'] == 'a/x' and output['sha256'] == hashlib.sha256(b'alias-update').hexdigest(), 'canonical aliases share observation and CAS')
        self.stop(worker)
        self.storage_probe('boundaries')
        task = self.task('D06-missing-ready-state', [self.sentinels()[0], [call('working_directory', cd='a')]])
        worker = self.start('D06-missing-ready-worker', 'worker', 'DIRECTORY_FROZEN')
        self.barrier(worker)
        state = self.root/'workspace'/(hashlib.sha256(('tenant-a:'+task).encode()).hexdigest()+'.json')
        state.unlink()
        (worker['folder']/'release').touch()
        self.state(task, 'FAILED')
        self.assert_that(not state.exists() and self.view(task)['revision'] == 0 and
                         self.sql("SELECT code FROM task WHERE id='"+task+"';") == 'FS_BINDING_UNPROVEN', 'missing READY workspace fails without reinitializing root')
        self.stop(worker)

    def storage_probe(self, mode):
        if not hasattr(self, 'probe_classes'):
            self.probe_classes = self.root/'probe-classes'
            shutil.copytree(Path(__file__).resolve().parents[1]/'target/test-classes', self.probe_classes)
        command = ['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', self.java,
                   '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.storage.DirectoryStorageScenario',
                   '-Dloader.path='+str(self.probe_classes), '-cp', str(self.jar),
                   'org.springframework.boot.loader.launch.PropertiesLauncher', str(self.root/'workspace'), mode]
        result = subprocess.run(command, text=True, capture_output=True, timeout=30)
        (self.evidence/('storage-'+mode+'.json')).write_text(json.dumps({'command':command, 'exit':result.returncode,'stdout':result.stdout,'stderr':result.stderr}, indent=2))
        self.assert_that(result.returncode == 0 and 'VERIFIED '+mode in result.stdout, 'real unprivileged POSIX storage '+mode, exit=result.returncode, stdout=result.stdout, stderr=result.stderr)

    def probe_command(self, task, worker, mode='commit', slot=0):
        if not hasattr(self, 'probe_classes'):
            self.probe_classes = self.root/'probe-classes'
            shutil.copytree(Path(__file__).resolve().parents[1]/'target/test-classes', self.probe_classes)
        return [self.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.DirectoryLedgerScenario',
                '-Dloader.path='+str(self.probe_classes), '-cp', str(self.jar),
                'org.springframework.boot.loader.launch.PropertiesLauncher',
                str(worker['folder']/'runtime.properties'), task, mode, str(slot)]

    def probe(self, task, worker, mode='commit', slot=0):
        result = subprocess.run(self.probe_command(task, worker, mode, slot), text=True, capture_output=True, timeout=20)
        with (self.evidence/('probe-'+mode+'.jsonl')).open('a') as out:
            out.write(json.dumps({'task': task, 'slot': slot, 'exit': result.returncode,
                                  'stdout': result.stdout, 'stderr': result.stderr})+'\n')
        assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
        output = json.loads(result.stdout.strip().splitlines()[-1])
        for line in result.stderr.splitlines():
            if mode == 'timed' and line.startswith('DIRECTORY_OPERATION_MILLIS='):
                output['operationMillis'] = int(line.split('=', 1)[1])
        return output

    def transaction(self):
        task = self.task('D04-fixed-slots', [[call('working_directory'), call('working_directory'), call('working_directory')]])
        worker = self.start('D04-paused', 'worker', 'M2')
        self.barrier(worker)
        for mode, expected in (('old-fence', 'FENCE_REJECTED'), ('actor', 'INVOCATION_MISMATCH'),
                               ('step', 'NOT_FOUND'), ('slot', 'NOT_FOUND'), ('provider', 'INVOCATION_MISMATCH'),
                               ('input', 'INVOCATION_MISMATCH'), ('namespace', 'INVOCATION_MISMATCH'),
                               ('admit', 'DIRECTORY_COMMIT_REQUIRED'), ('succeed', 'DIRECTORY_COMMIT_REQUIRED'),
                               ('uncertain', 'DIRECTORY_COMMIT_REQUIRED')):
            result = self.probe(task, worker, mode)
            self.assert_that(result['code'] == expected and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '0',
                             'reject forged slot or generic bypass '+mode, result=result)
        self.assert_that(self.probe(task, worker, slot=1)['code'] == 'DIRECTORY_SLOT_ORDER', 'later directory slot cannot execute first')
        self.sql("DELIMITER $$\nCREATE TRIGGER directory_test_rollback BEFORE INSERT ON reservation FOR EACH ROW BEGIN IF NEW.category='TOOL' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test rollback'; END IF; END$$\nDELIMITER ;\n")
        try:
            self.assert_that(self.probe(task, worker)['code'] == 'UNCONFIRMED' and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '0', 'SQL rollback leaves no charge or directory result')
        finally:
            self.sql('DROP TRIGGER directory_test_rollback;')
        self.assert_that(self.probe(task, worker, 'lost-ack')['code'] == 'UNCONFIRMED', 'COMMIT succeeded but acknowledgement was lost')
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda _: self.probe(task, worker), range(2)))
        self.assert_that(all(r == {'code': 'OK', 'result': {'cwd': '/'}} for r in results) and
                         self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '1', 'competing replay returns original result with one TOOL settlement')
        self.sql("UPDATE invocation SET output_digest=REPEAT('0',64) WHERE task_id='"+task+"' AND state='SUCCEEDED';")
        self.assert_that(self.probe(task, worker)['code'] == 'SNAPSHOT_CORRUPT', 'replay rejects forged original result digest')
        self.sql("UPDATE invocation SET output_digest=SHA2(CAST(output_snapshot AS BINARY),256) WHERE task_id='"+task+"' AND state='SUCCEEDED';")
        for slot in (1, 2):
            self.assert_that(self.probe(task, worker, slot=slot)['code'] == 'OK', 'new no-op invocation charged independently '+str(slot))
        self.assert_that(self.view(task)['revision'] == 0 and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '3', 'three no-op calls keep revision zero')
        (worker['folder']/'release').touch()
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        self.assert_that(self.probe(task, worker)['code'] == 'FENCE_REJECTED', 'terminal task cannot revive original directory invocation')

        task = self.task('D04-live-writer', [[call('working_directory')]])
        old = self.start('D04-live-writer-paused', 'worker', 'M2')
        self.barrier(old)
        successor = self.start('D04-live-writer-successor', 'worker')
        self.expire(task)
        time.sleep(1.2)
        self.assert_that(self.sql("SELECT fence FROM task WHERE id='"+task+"';") == '1' and
                         self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"';") == '1',
                         'expired lease alone cannot fence an unconfirmed live writer')
        self.stop(old, kill=True)
        self.expire(task)
        self.state(task, 'SUCCEEDED')
        self.assert_that(self.sql("SELECT fence FROM task WHERE id='"+task+"';") == '2' and
                         self.probe(task, old, 'old-fence')['code'] == 'FENCE_REJECTED',
                         'confirmed stop permits recovery but old fence remains rejected')
        self.stop(successor)
        task = self.task('D04-running-file', [[call('read', file_path='not-yet-read')]])
        old = self.start('D04-running-file-paused', 'worker', 'T0')
        self.barrier(old)
        self.stop(old, kill=True)
        self.expire(task)
        successor = self.start('D04-running-file-successor', 'worker')
        self.state(task, 'NEEDS_RECONCILIATION')
        self.assert_that(self.sql("SELECT state FROM invocation WHERE task_id='"+task+"';") == 'OUTCOME_UNKNOWN', 'generic file RUNNING crash retains UNKNOWN')
        self.stop(successor)

        task = self.task('D04-old-success', [self.sentinels()[0], [call('working_directory', cd='a'), call('working_directory', cd='../b')]])
        worker = self.start('D04-old-success-paused', 'worker', 'DIRECTORY_FROZEN')
        self.barrier(worker)
        for slot in (4, 5):
            self.assert_that(self.probe(task, worker, slot=slot)['code'] == 'OK', 'ordered changed invocation '+str(slot))
        original = self.probe(task, worker, slot=4)
        self.assert_that(original == {'code': 'OK', 'result': {'cwd': '/a'}} and self.view(task)['cwd'] == '/b' and self.view(task)['revision'] == 2,
                         'old success replays original cwd without replacing newer revision')
        (worker['folder']/'release').touch()
        self.state(task, 'SUCCEEDED')
        self.stop(worker)

        self.claim_fairness()

    def claim_fairness(self):
        self.sql("UPDATE tenant_budget SET running_limit=2 WHERE tenant_id='tenant-a';")
        task = self.task('R1-live-writer', [[call('working_directory')]])
        old = self.start('R1-paused', 'worker', 'M2')
        self.barrier(old)
        self.expire(task)
        successor = self.start('R1-successor', 'worker')
        for phase in ('expired-lease', 'expired-deadline'):
            if phase == 'expired-deadline':
                self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
            other = self.task('R1-other-'+phase, [[call('working_directory')]], subject='b')
            directory = self.task('R1-directory-'+phase, self.sentinels())
            legacy = self.task('R1-legacy-'+phase, [[call('read', file_path='legacy.txt'),
                call('write', file_path='legacy.txt', content='legacy-before'),
                call('edit', file_path='legacy.txt', old_string='before', new_string='after')]],
                capabilityIds=['read', 'write', 'edit'])
            self.state(other, 'SUCCEEDED')
            for candidate in (directory, legacy):
                self.state(candidate, 'SUCCEEDED')
                self.assert_that(self.sql("SELECT fence FROM task WHERE id='"+candidate+"';") == '1',
                                 'same-tenant runnable task claimed behind live writer '+phase, task=candidate)
            self.assert_that(self.view(directory)['cwd'] == '/b' and self.view(directory)['revision'] == 2 and
                             self.sql("SELECT COUNT(*) FROM task_directory WHERE task_id='"+legacy+"';") == '0',
                             'directory and legacy profiles execute independently '+phase)
            self.assert_that(old['process'].poll() is None and
                             self.sql('SELECT stopped FROM worker_process WHERE pid='+str(old['process'].pid)+';') == '0' and
                             self.sql("SELECT state,fence,tools_used FROM task WHERE id='"+task+"';") == 'RUNNING\t1\t0' and
                             self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"';") == '1' and
                             self.fixture.models['R1-live-writer'] == 1,
                             'fairness never takes over unconfirmed writer '+phase, oldPid=old['process'].pid)
            (self.evidence/('R1-'+phase+'.tsv')).write_text(self.sql(
                "SELECT tenant_id,id,goal,state,fence,models_used,tools_used FROM task ORDER BY tenant_id,ready_at,id;"))
        self.stop(old, kill=True)
        self.expire(task)
        self.state(task, 'FAILED')
        self.assert_that(self.sql("SELECT code FROM task WHERE id='"+task+"';") == 'DEADLINE_EXCEEDED' and
                         self.probe(task, old, 'old-fence')['code'] == 'FENCE_REJECTED',
                         'only supervisor-confirmed stop permits expired task finalization; old fence rejected')
        self.stop(successor)

    def authorization(self):
        task = self.task('D07-owner', [[call('working_directory')]])
        worker = self.start('D07-owner-worker', 'worker')
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        for subject in ('b', 'c'):
            status, value = self.http('GET', '/api/tasks/'+task+'/working-directory', subject=subject)
            self.assert_that(status == 404 and value['code'] == 'NOT_FOUND', 'owner isolation hides directory '+subject)
        for suffix, body in (('?extra=1', None), ('', {})):
            self.assert_that(self.http('GET', '/api/tasks/'+task+'/working-directory'+suffix, body)[0] == 400, 'GET refuses query and body')
        for mode, sql, restore, expected in (
            ('actor', "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';", "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice';", 'ACCESS_REVOKED'),
            ('installation', "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='working_directory';", "UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='working_directory';", 'CAPABILITY_REVOKED'),
            ('epoch', "UPDATE installation SET epoch=epoch+1 WHERE tenant_id='tenant-a' AND plugin_id='working_directory';", "UPDATE installation SET epoch=epoch-1 WHERE tenant_id='tenant-a' AND plugin_id='working_directory';", 'PLUGIN_PIN_MISMATCH'),
            ('pin', "UPDATE installation SET plugin_version='99' WHERE tenant_id='tenant-a' AND plugin_id='working_directory';", "UPDATE installation SET plugin_version='0.1.0' WHERE tenant_id='tenant-a' AND plugin_id='working_directory';", 'PLUGIN_PIN_MISMATCH')):
            task = self.task('D07-'+mode, [[call('working_directory')]])
            worker = self.start('D07-paused-'+mode, 'worker', 'M2')
            self.barrier(worker)
            self.assert_that(self.probe(task, worker, 'flag-off')['code'] == 'DIRECTORY_FEATURE_DISABLED', 'feature flag gates new execution')
            self.sql(sql)
            try:
                result = self.probe(task, worker)
                self.assert_that(result['code'] == expected and self.http('GET', '/api/tasks/'+task+'/working-directory')[0] in (403, 409), 'commit and GET recheck '+mode, result=result)
                (worker['folder']/'release').touch()
                eventually(lambda: self.sql("SELECT state FROM task WHERE id='"+task+"';") == 'FAILED', 'revoked execution fails '+mode)
                self.stop(worker)
            finally:
                self.sql(restore)
            self.assert_that(self.view(task)['revision'] == 0, 'revoked execution has no directory transition '+mode)

        task = self.task('D07-deadline-during-commit', [[call('working_directory')]])
        worker = self.start('D07-final-fence-worker', 'worker', 'M2')
        self.barrier(worker)
        self.sql("CREATE TRIGGER directory_final_fence BEFORE INSERT ON reservation FOR EACH ROW UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id AND NEW.category='TOOL';")
        try:
            result = self.probe(task, worker)
            self.assert_that(result['code'] == 'FENCE_REJECTED' and self.view(task)['revision'] == 0 and
                             self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '0', 'final DB-time fence rolls back tool result events and charge after deadline changes inside commit', result=result)
        finally:
            self.sql('DROP TRIGGER directory_final_fence;')
        (worker['folder']/'release').touch()
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        for mode in ('cancel', 'deadline'):
            task = self.task('D07-'+mode, [[call('working_directory')]])
            worker = self.start('D07-paused-'+mode, 'worker', 'M2')
            self.barrier(worker)
            if mode == 'cancel': self.http('POST', '/api/tasks/'+task+'/cancel')
            else: self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
            result = self.probe(task, worker)
            self.assert_that(result['code'] in ('CANCEL_REQUESTED', 'DEADLINE_EXCEEDED', 'FENCE_REJECTED') and self.view(task)['revision'] == 0, 'cancel or deadline refuses commit but retains history '+mode, result=result)
            (worker['folder']/'release').touch()
            eventually(lambda: self.sql("SELECT state FROM task WHERE id='"+task+"';") in ('CANCELLED', 'FAILED'), 'terminal after '+mode)
            self.stop(worker)

    def events(self):
        task = self.task('D08-sse', [self.sentinels()[0], [call('working_directory', cd='a'), call('working_directory', cd='.'), call('working_directory')]])
        worker = self.start('D08-paused', 'worker', 'DIRECTORY_FROZEN')
        self.barrier(worker)
        initial = self.view(task)
        before = self.sql("SELECT next_event_seq,models_used,tools_used FROM task WHERE id='"+task+"';")
        start = time.monotonic()
        status, wire = self.http('GET', '/api/tasks/'+task+'/events', headers={'Last-Event-ID':task+':'+str(initial['asOfSeq'])})
        self.assert_that(status == 200 and wire == '' and 24 <= time.monotonic()-start < 35, 'idle SSE really closes after 25 seconds')
        for _ in range(5): self.assert_that(self.view(task) == initial, 'repeated GET is inert')
        self.assert_that(self.sql("SELECT next_event_seq,models_used,tools_used FROM task WHERE id='"+task+"';") == before, 'GET and idle SSE create no calls or events')
        (worker['folder']/'release').touch()
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        status, wire = self.http('GET', '/api/tasks/'+task+'/events', headers={'Last-Event-ID':task+':'+str(initial['asOfSeq'])})
        (self.evidence/'sse-replay.txt').write_text(wire)
        self.assert_that(status == 200 and wire.count('"type":"working_directory.changed"') == 1 and 'sentinel-A' not in wire and '"cwd"' not in wire, 'GET to subscribe race replays one path-free invalidation')
        self.assert_that(self.view(task)['revision'] == 1 and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '7', 'no-op SET and READ charge once without changed event')
        for cursor in (task+':999999', str(uuid.uuid4())+':1'):
            self.assert_that(self.http('GET', '/api/tasks/'+task+'/events', headers={'Last-Event-ID':cursor})[0] == 400, 'future or foreign SSE cursor rejected')
        self.sql("UPDATE event SET occurred_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE task_id='"+task+"';")
        self.assert_that(self.http('GET', '/api/tasks/'+task+'/events', headers={'Last-Event-ID':task+':'+str(initial['asOfSeq'])})[0] == 410 and self.view(task)['cwd'] == '/a', 'expired cursor recovers through latest GET')

    def budgets(self):
        worker = self.start('D09-worker', 'worker')
        for goal, plan, budget, expected in (
            ('tight-batch', [[call('working_directory'), call('working_directory')]], {'maxToolInvocations':1}, 'TOOL_BUDGET_EXCEEDED'),
            ('five-slots', [[call('working_directory')]*5], {}, 'MODEL_RESPONSE_TOO_LARGE'),
            ('summary', [], {}, 'DIRECTORY_STORAGE_LIMIT'),
            ('eight-models', [[call('working_directory')]]*8, {}, 'DIRECTORY_EXECUTION_LIMIT'),
            ('twenty-tools', [[call('working_directory')]*4]*6, {}, 'DIRECTORY_EXECUTION_LIMIT')):
            task = self.task('D09-'+goal, plan, budget=budget)
            self.state(task, 'FAILED')
            facts = self.sql("SELECT code,tools_used,models_used FROM task WHERE id='"+task+"';")
            self.assert_that(facts.split('\t')[0] == expected, 'fixed execution cap '+goal, facts=facts)
            if goal in ('tight-batch', 'five-slots'):
                self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"';") == '0' and
                                 self.sql("SELECT SUM(actual),GROUP_CONCAT(DISTINCT usage_quality),GROUP_CONCAT(DISTINCT state) FROM reservation WHERE task_id='"+task+"' AND category='MODEL';") == '150\tKNOWN\tSETTLED', 'rejected M2 batch preserves usage without partial executable slots '+goal)
        self.stop(worker)
        task = self.task('D09-bounded-locks', [[call('working_directory')]])
        worker = self.start('D09-lock-worker', 'worker', 'M2')
        self.barrier(worker)
        locker = subprocess.Popen(self.probe_command(task, worker, 'lock'), text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(locker)
        try:
            assert locker.stdout.readline().strip() == 'LOCKED'
            start = time.monotonic()
            result = self.probe(task, worker, 'timed')
            elapsed = time.monotonic()-start
            self.assert_that(result['code'] == 'UNCONFIRMED' and 1500 <= result['operationMillis'] < 3000, 'real InnoDB lock has two-second bound', result=result, processElapsedSeconds=elapsed)
        finally:
            locker.communicate('\n', timeout=10)
        import fcntl
        scope = hashlib.sha256(('tenant-a:'+task).encode()).hexdigest()
        with (self.root/'workspace'/(scope+'.lock')).open('r+') as lock:
            fcntl.lockf(lock, fcntl.LOCK_EX)
            try:
                start = time.monotonic()
                result = self.probe(task, worker, 'timed')
                elapsed = time.monotonic()-start
                self.assert_that(result['code'] == 'WORKSPACE_UNAVAILABLE' and 1500 <= result['operationMillis'] < 3000, 'real POSIX file lock has two-second bound', result=result, processElapsedSeconds=elapsed)
            finally:
                fcntl.lockf(lock, fcntl.LOCK_UN)
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '0', 'lock rejection never consumes a tool')
        (worker['folder']/'release').touch()
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        held = self.sql("SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")
        self.sql("UPDATE tenant_budget SET directory_storage_limit="+held+" WHERE tenant_id='tenant-a';")
        status, error = self.http('POST', '/api/tasks', {'goal':'D09-full-tenant', 'capabilityIds': CAPS}, key='D09-full-tenant')
        self.assert_that(status == 429 and error['code'] == 'DIRECTORY_STORAGE_LIMIT', 'independent tenant directory cap')
        other = self.task('D09-other-tenant', [[call('working_directory')]], subject='b')
        worker = self.start('D09-other-worker', 'worker')
        self.state(other, 'SUCCEEDED')
        self.stop(worker)
        self.assert_that(self.view(other)['cwd'] == '/', 'other tenant still runs under full tenant-a directory cap')
        self.storage_probe('limits')

    def retention(self):
        task = self.task('D10-retained', self.sentinels())
        worker = self.start('D10-worker', 'worker')
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        self.sql("UPDATE tenant_budget SET directory_storage_limit=1073741824 WHERE tenant_id='tenant-a';")
        blockers = []
        for index in range(64):
            blocked = self.task('D10-blocked-'+str(index), [])
            status, _ = self.http('POST', '/api/tasks/'+blocked+'/cancel')
            assert status == 200
            blockers.append(blocked)
            if index < 32:
                self.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a','"+blocked+":unknown','"+blocked+"','MODEL',1,'HELD_UNKNOWN','UNKNOWN');")
            else:
                self.sql("INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state,quiescent) VALUES('tenant-a','"+str(uuid.uuid4())+"','"+blocked+"','unconfirmed-historical-writer',1,'EXPIRED',FALSE);")
        ids = ','.join("'"+value+"'" for value in blockers)
        other = self.task('D10-unexpired-other-tenant', [], subject='b')
        self.http('POST', '/api/tasks/'+other+'/cancel', subject='b')
        self.stop(self.control)
        scope = hashlib.sha256(('tenant-a:'+task).encode()).hexdigest()
        files = list((self.root/'workspace').glob(scope+'*'))
        self.assert_that(any(p.suffix == '.json' for p in files), 'real terminal workspace exists before retention')
        before = self.view_at_rest = self.sql("SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")
        self.sql("DELIMITER $$\nCREATE TRIGGER directory_cleanup_pause BEFORE UPDATE ON task_directory FOR EACH ROW BEGIN IF NEW.content_state='PURGED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test cleanup final commit interruption'; END IF; END$$\nDELIMITER ;\n")
        self.sql("UPDATE task_directory SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+task+"';")
        self.sql("UPDATE task_directory SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 2 DAY WHERE task_id IN ("+ids+");")
        prefix = self.sql("SELECT task_id FROM task_directory WHERE content_state<>'PURGED' AND retain_until<=CURRENT_TIMESTAMP(6) ORDER BY retain_until,tenant_id,task_id LIMIT 32;").splitlines()
        self.assert_that(len(prefix) == 32 and task not in prefix, 'unsafe prefix fills a full unfiltered cleanup batch')
        self.control = self.start('D10-purge-control', 'control')
        eventually(lambda: self.view(task)['state'] == 'PURGING' and not list((self.root/'workspace').glob(scope+'*.json')), 'files removed before interrupted final DB commit', 35)
        self.assert_that(self.sql("SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';") == before, 'PURGING has not released quota')
        self.assert_that(self.view(task)['cwd'] is None and self.view(task)['source']['outputSha256'], 'PURGING exposes retained provenance without cwd')
        self.sql('DROP TRIGGER directory_cleanup_pause;')
        eventually(lambda: self.view(task)['state'] == 'PURGED', 'retry finalizes exact persisted filesystem inventory', 35)
        self.assert_that(int(self.sql("SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")) == int(before)-8388608 and
                         self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='working_directory.content_deleted';") == '1', 'exactly one eight MiB release and deletion event')
        self.assert_that(self.sql("SELECT COUNT(*) FROM task_directory WHERE task_id IN ("+ids+") AND content_state='PRESENT';") == '64' and self.view(other)['state'] == 'PRESENT', 'cleanup retains UNKNOWN, all unconfirmed writers and unexpired other tenant')
        self.assert_that(not list((self.root/'workspace').glob(scope+'*.json')) and not list((self.root/'workspace').glob(scope+'*.part')), 'workspace body and temporary copies removed')
        bodies = self.sql("SELECT request_snapshot,goal,summary FROM task WHERE id='"+task+"';")+self.sql("SELECT request_snapshot,response_snapshot,directory_cwd FROM step WHERE task_id='"+task+"';")+self.sql("SELECT input_snapshot,output_snapshot FROM invocation WHERE task_id='"+task+"';")+self.sql("SELECT base_cwd,target_cwd,canonical_paths_snapshot,directory_evidence_snapshot FROM invocation_path_binding WHERE task_id='"+task+"';")
        self.assert_that('sentinel-A' not in bodies and 'sentinel-B' not in bodies and 'DIRECTORY_PURGED' in bodies, 'all duplicated content bodies tombstoned')
        status, error = self.http('POST', '/api/tasks', {'goal':'D10-retained','capabilityIds':CAPS}, key='D10-retained')
        self.assert_that(status == 410 and error['code'] == 'DIRECTORY_CONTENT_EXPIRED', 'purged idempotency key cannot create a new workspace')

    def regressions(self):
        before = self.sql('SELECT COUNT(*) FROM task_directory;')
        for index, capabilities in enumerate((['working_directory'], CAPS+['glob'], CAPS+['grep'], CAPS+['present'], CAPS+['todo_write'])):
            status, error = self.http('POST', '/api/tasks', {'goal':'D12-invalid-'+str(index),'capabilityIds':capabilities}, key='D12-invalid-'+str(index))
            self.assert_that(status == 400 and error['code'] == 'INVALID_CAPABILITIES', 'mixed directory profile rejected '+str(index))
        self.assert_that(self.sql('SELECT COUNT(*) FROM task_directory;') == before and self.sql('SELECT COUNT(*) FROM native_search_run;') == '0', 'invalid profiles create no binding or native process')
        environment = dict(os.environ, JIMU_IT_JAVA=self.java)
        result = subprocess.run(['python3', '-B', '-u', str(Path(__file__).with_name('workspace_verify.py')), str(self.jar),
                                 str(Path(__file__).resolve().parents[1]/'target/test-classes'), str(self.evidence/'legacy-workspace')],
                                env=environment, text=True, capture_output=True, timeout=300)
        (self.evidence/'legacy-workspace-command.json').write_text(json.dumps({'exit':result.returncode,'stdout':result.stdout,'stderr':result.stderr}, indent=2))
        self.assert_that(result.returncode == 0, 'existing native POSIX workspace read write edit CAS recovery regression', stdout=result.stdout, stderr=result.stderr)
        from mcp_verify import NAMES
        mcp = McpExperiment(self.jar, self.evidence/'legacy-mcp')
        try:
            mcp.prepare()
            worker = mcp.start('D12-mcp-worker', 'worker')
            mcp.state(mcp.legacy_task, 'SUCCEEDED')
            task = mcp.task('D12-mcp-resources', [(NAMES[0], {'server':'controlled'}), (NAMES[1], {'server':'controlled'}),
                            (NAMES[2], {'server':'controlled','uri':'jimu-fixture://tenant-a/docs/text'})])
            mcp.state(task, 'SUCCEEDED')
            mcp.assert_that(len(mcp.outputs(task)) == 3, 'three unchanged MCP resource methods through real task')
            mcp.stop(worker)
        finally:
            mcp.cleanup()
        self.assert_that(True, 'targeted legacy MCP and original NULL-binding task regression')

    def cleanup(self):
        try:
            for child in reversed(self.children):
                self.stop({'process': child, 'name': 'directory foreground collection'})
            if self.schema_ready:
                for table in ('task_directory', 'invocation_path_binding'):
                    (self.evidence/(table+'.tsv')).write_text(self.sql('SELECT * FROM '+table+';'))
                (self.evidence/'model-wire.json').write_text(json.dumps(self.fixture.requests, ensure_ascii=True, indent=2))
        finally:
            super().cleanup()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground driver terminated')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--case', choices=['D'+str(i).zfill(2) for i in range(1,13)]+['R1'], default='D01')
    parser.add_argument('--v17-jar', type=Path)
    args = parser.parse_args()
    if args.case == 'D11':
        if args.v17_jar is None or not args.v17_jar.is_file(): parser.error('D11 requires --v17-jar with exact V17 artifact')
        from working_directory_migration_verify import DirectoryMigration
        experiment = DirectoryMigration(args.v17_jar, args.evidence)
    else:
        experiment = DirectoryExperiment(args.jar, args.evidence)
    try:
        if args.case == 'D11':
            experiment.run(args.v17_jar, args.jar)
        else:
            experiment.prepare()
            {'D01': experiment.normal, 'D02': experiment.ordered, 'D03': experiment.recovery,
             'D04': experiment.transaction, 'D05': experiment.invalid_paths, 'D06': experiment.observation,
             'D07': experiment.authorization, 'D08': experiment.events, 'D09': experiment.budgets,
             'D10': experiment.retention, 'D12': experiment.regressions, 'R1': experiment.claim_fairness}[args.case]()
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
