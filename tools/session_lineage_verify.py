"""Foreground v5 acceptance: private MySQL, real control/worker and paid=false HTTP model.

Run on Linux with JIMU_IT_JAVA pointing to an existing reviewed Java21. All children
and the labeled fixture container are collected before this command returns.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import traceback

from p2_verify import Experiment, Handler, eventually
from mcp_verify import McpExperiment
from p3_model_verify import config


class Model(Handler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        context = json.loads(body['messages'][-1]['content'])
        assert context['version'] == 5 and context['genesis_sha256'] and context['graph_sha256']
        assert {t['function']['name'] for t in body['tools']} == {'session_event_read', 'session_search', 'session_trace'}
        replies = [json.loads(m['content']) for m in body['messages'] if m['role'] == 'tool']
        plan = self.server.plans[goal]
        message = {'role': 'assistant', 'content': 'Verified retained constellation evidence.'}
        consumed = 0
        for entry in plan:
            calls = entry if isinstance(entry, list) else [entry]
            if len(replies) == consumed:
                wire = []
                for offset, (name, arguments) in enumerate(calls):
                    if arguments == '$read-hit':
                        hit = replies[0]['items'][0]
                        arguments = {'session_id': hit['session_id'], 'seq': hit['best_match']['seq']}
                    wire.append({'id': 'lineage_' + str(consumed + offset), 'type': 'function',
                        'function': {'name': name, 'arguments': json.dumps(arguments)}})
                message['tool_calls'] = wire
                break
            consumed += len(calls)
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({'goal': goal, 'body': body, 'context': context, 'replies': replies})
        self.send(200, {'id': 'lineage-controlled', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if 'tool_calls' in message else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


class LineageExperiment(Experiment):
    start = McpExperiment.start
    state = McpExperiment.state
    outputs = McpExperiment.outputs

    def cleanup(self):
        try:
            (self.evidence / 'model-wire.json').write_text(json.dumps(self.fixture.requests, ensure_ascii=False, indent=2))
            if self.schema_ready:
                for table in ('task_session_lineage', 'invocation_session_lineage_trace', 'task_session_scope', 'step_session_scope'):
                    (self.evidence / (table + '.tsv')).write_text(self.sql('SELECT * FROM ' + table + ';'))
        finally:
            super().cleanup()

    def prepare(self):
        self.fixture.RequestHandlerClass = Model
        self.fixture.plans, self.fixture.requests = {}, []
        original = self.jar
        if getattr(self,'v22',None):
            self.jar=self.v22
        super().prepare()
        if getattr(self,'v22',None):
            self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;')=='22','exact baseline binary creates22')
            checksums=self.sql('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;')
            self.stop(self.control)
            shutil.copyfile(original,self.jar)
            self.control=self.start('control-upgrade23','control')
            self.assert_that(self.sql('SELECT version,checksum,success FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)<=22 ORDER BY installed_rank;')==checksums,'V1 to22 checksums unchanged after23')
            properties=self.control['folder']/'runtime.properties'
            result=subprocess.run([self.java,'-jar',str(self.v22),'--spring.config.additional-location=file:'+str(properties)],text=True,capture_output=True,timeout=45)
            (self.evidence/'old22-refuses23.log').write_text(result.stdout+result.stderr)
            self.assert_that(result.returncode!=0 and 'Unsupported schema version' in result.stdout+result.stderr,'old22 refuses23 before readiness',exit=result.returncode)
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;') == '23', 'actual Flyway schema23')
        for table in ('task_session_history', 'session_public_event', 'invocation_session_read',
                      'invocation_session_search', 'invocation_session_trace', 'task_session_scope',
                      'task_session_scope_target', 'step_session_scope', 'invocation_session_scope_query',
                      'task_session_lineage', 'invocation_session_lineage_trace'):
            self.sql("GRANT INSERT,UPDATE ON jimu.`" + table + "` TO 'runtime'@'%';")
        root = Path(__file__).resolve().parents[1] / 'src/main/resources/session/v5'
        self.catalog = json.loads((root / 'catalog-v5.json').read_text())
        for tenant in ('tenant-a', 'tenant-b'):
            for tool in self.catalog['tools']:
                self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('" + tenant + "','" + tool['name'] + "','" + tool['version'] + "','" + tool['outputSha256'] + "');")
        self.assert_that('session_trace' not in self.http('GET', '/api/capabilities')[1], 'default discovery hides trace')
        status, output = self.http('POST', '/api/session-trace/tasks', {'goal': 'default disabled', 'history_session_ids': []}, key='disabled')
        self.assert_that(status == 403, 'default admission closed', status=status, output=output)
        self.stop(self.control)
        settings = config({'a': self.fixture}, paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        settings.update({'jimu.model.profiles.a.model': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.revision': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.response-bytes': '16384',
            'jimu.model.profiles.a.input-limit.version': '1',
            'jimu.model.profiles.a.input-limit.max-tokens': '65536',
            'jimu.model.profiles.a.input-limit.proof': 'test-whole-request-utf8-v1',
            'jimu.session-event-read.enabled': 'true', 'jimu.session-search.enabled': 'true',
            'jimu.session-trace.enabled': 'true'})
        self.common.update(settings)
        self.control = self.start('control-v5', 'control')
        self.sql('UPDATE tenant_budget SET history_storage_limit=536870912,model_limit=2000,tool_limit=2000,token_limit=67108864;')
        self.worker = self.start('worker-v5', 'worker')
        self.assert_that('session_trace' in self.http('GET', '/api/capabilities')[1], 'v5 discovery matches installed pins')

    def task(self, goal, sources=(), parent=None, plan=None, key=None, expected=202):
        self.fixture.plans[goal] = plan if plan is not None else [('session_trace', {})]
        body = {'goal': goal, 'history_session_ids': list(sources)}
        if parent is not None:
            body['parent_session_id'] = parent
        status, value = self.http('POST', '/api/session-trace/tasks', body, key=key or hashlib.sha256(goal.encode()).hexdigest())
        self.assert_that(status == expected, 'admission ' + goal, status=status, value=value)
        if status != 202:
            return value
        task = value['taskId']
        self.state(task, 'SUCCEEDED')
        eventually(lambda: self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='" + task + "' AND quiescent=FALSE;") == '0', 'writer quiescence')
        return task

    def trace(self, caller, target=None, expected=200, subject='a'):
        status, value = self.http('POST', '/api/tasks/' + caller + '/session-history/session-trace',
            {} if target is None else {'session_id': target}, subject=subject)
        self.assert_that(status == expected, 'owner trace', status=status, value=value)
        return value

    def core(self):
        a = self.task('constellation-a')
        b = self.task('constellation-b', [a], a)
        c = self.task('constellation-c', [a, b], b)
        d = self.task('constellation-d', [a], a)
        sources = [a, b, c, d]
        plan = [('session_search', {'query': 'constellation-c'}),
                [('session_trace', {'session_id': a}), ('session_trace', {'session_id': b}),
                 ('session_trace', {})], ('session_event_read', '$read-hit')]
        e = self.task('constellation-e', sources, plan=plan)
        outputs = self.outputs(e)
        self.assert_that(len(outputs) == 5 and outputs[0]['items'][0]['session_id'] == c
            and {n['node']['session_id'] for n in outputs[1]['descendants']} == {b, c, d}
            and [n['session_id'] for n in outputs[2]['ancestors']] == [a]
            and [n['node']['session_id'] for n in outputs[2]['descendants']] == [c]
            and outputs[3]['target']['kind'] == 'ROOT' and outputs[3]['ancestors'] == []
            and outputs[4]['session_id'] == c and outputs[4]['target']['data']['content'] == 'constellation-c',
            'A03 nonempty actual worker search trace read', tasks={'a': a, 'b': b, 'c': c, 'd': d, 'e': e})
        for task, parent in ((a, None), (b, a), (c, b), (d, a), (e, None)):
            row = json.loads(self.sql("SELECT genesis_snapshot FROM task_session_lineage WHERE task_id='" + task + "';"))
            self.assert_that(row.get('parent_session_id') == parent, 'original admission parent', task=task)
        original = self.trace(e, a)
        f = self.task('constellation-f', [a], a)
        self.assert_that(self.trace(e, a) == original and f not in json.dumps(original), 'frozen admission graph excludes later child')
        self.assert_that(self.task('constellation-e', list(reversed(sources)), plan=plan) == e, 'semantic sorted-source idempotency')
        self.task('constellation-e', sources, parent=a, plan=plan, expected=409)
        before = self.sql('SELECT COUNT(*) FROM task;')
        self.task('missing-closure', [b], b, expected=400)
        self.assert_that(self.sql('SELECT COUNT(*) FROM task;') == before, 'closure rejection rolls task genesis and reservation back')
        self.trace(e, f, expected=404)
        self.trace(e, a, expected=404, subject='b')
        self.trace(e, a, expected=404, subject='c')
        self.inputs(e)
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation_session_lineage_trace WHERE caller_task_id='" + e + "' AND outcome='SUCCEEDED';") == '3'
            and self.sql("SELECT tools_used FROM task WHERE id='" + e + "';") == '5', 'three trace commits and five exact tool charges')
        self.assert_that(self.sql("SELECT COUNT(DISTINCT step_id),COUNT(DISTINCT context_digest) FROM invocation_session_lineage_trace WHERE caller_task_id='" + e + "';") == '1\t1', 'three trace slots share one frozen response context')
        counts = self.sql("SELECT id,state,models_used,tools_used FROM task WHERE id IN ('" + "','".join(sources) + "') ORDER BY id;")
        self.trace(e, a)
        self.assert_that(self.sql("SELECT id,state,models_used,tools_used FROM task WHERE id IN ('" + "','".join(sources) + "') ORDER BY id;") == counts, 'read does not wake or consume sources')
        # Exact current source grant, without relying on maintenance having scanned it.
        self.sql("UPDATE task_session_scope SET grant_state='REVOKED',revoked_at=UTC_TIMESTAMP(6) WHERE caller_task_id='" + b + "';")
        self.trace(e, a, expected=404)
        self.assert_that(self.http('GET', '/api/tasks/' + e)[1].get('summary') is None, 'whole-scope failure seals public summary')

    def inputs(self, caller):
        def raw(value, suffix='', authenticated=True, content_type='application/json'):
            connection = http.client.HTTPConnection('127.0.0.1', self.control['port'], timeout=4)
            headers = {'Content-Type': content_type}
            if authenticated:
                headers['Authorization'] = 'Bearer ' + self.token()
            try:
                connection.request('POST', '/api/tasks/' + caller + '/session-history/session-trace' + suffix, value, headers)
                response = connection.getresponse()
                return response.status, response.read().decode()
            finally:
                connection.close()
        for value in (b'{"seq":0}', b'{"session_id":null}', b'[]', b'null',
                      b'{"session_id":"BAD"}', b'{"session_id":"a","session_id":"b"}',
                      b'{"session_id":"\\ud800"}', b'{"session_id":"\xc0\xaf"}', b'{} {}', b' ' * 1025):
            status, _ = raw(value)
            self.assert_that(status == 400, 'strict trace protocol', inputSha256=hashlib.sha256(value).hexdigest(), status=status)
        self.assert_that(raw(b'{}' + b' ' * 1022)[0] == 200, 'exact 1KiB input accepted')
        self.assert_that(raw(b'{', authenticated=False)[0] == 401, 'authentication precedes malformed input')
        self.assert_that(raw(b'{}', suffix='?depth=2')[0] == 400 and raw(b'{}', content_type='text/plain')[0] == 400, 'HTTP transport rejects query and wrong content type')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--v22-jar',type=Path)
    args = parser.parse_args()
    e = LineageExperiment(args.jar, args.evidence)
    e.v22=args.v22_jar
    def stop(signum, frame):
        raise RuntimeError('Interrupted foreground fixture')
    signal.signal(signal.SIGTERM, stop)
    try:
        e.prepare()
        e.core()
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
