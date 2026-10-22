"""Foreground session history acceptance: private MySQL, real JVMs and paid=false HTTP model."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import traceback
import uuid
import concurrent.futures

from p2_verify import Experiment, Handler, eventually
from mcp_verify import McpExperiment
from p3_model_verify import config

NAME = 'session_event_read'


class Model(Handler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        context = json.loads(body['messages'][-1]['content'])
        assert context['kind'] == 'session_event_context'
        assert {t['function']['name'] for t in body['tools']} == {NAME}
        replies = [json.loads(m['content']) for m in body['messages'] if m['role'] == 'tool']
        plans = self.server.plans[goal]
        if goal.startswith('transport-provider-failure'):
            self.send(500, {'error':'SYNTHETIC_PROVIDER_SECRET /private/provider'})
            return
        at = len(replies)
        batch = plans[at] if at < len(plans) else None
        message = {'role': 'assistant', 'content': 'Verified retained session history.'}
        if batch is not None:
            calls = []
            for i, args in enumerate(batch if isinstance(batch, list) else [batch]):
                if isinstance(args, dict):
                    args = dict(args)
                    if args.get('session_id') == '$current': args['session_id'] = context['session_id']
                    if args.get('seq') == '$upper': args['seq'] = context['upper_seq']
                    if args.get('seq') == '$outside': args['seq'] = context['upper_seq'] + 1
                    args = json.dumps(args, ensure_ascii=True)
                calls.append({'id': 'slot_' + str(at+i), 'type': 'function', 'function': {'name': NAME, 'arguments': args}})
            message.update({'content': 'Read the earlier public event.', 'tool_calls': calls})
            if goal.startswith('transport-invalid-slot'): message['tool_calls'][0]['id'] = 'slot/private'
        if goal.startswith('unsafe-model'):
            message['content'] += ' Bearer SYNTHETIC_TOKEN /private/sentinel next CONFIG_SENTINEL_52'
        message['reasoning_content'] = 'HIDDEN_REASONING_SENTINEL_52 /private/reasoning'
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({'goal': goal, 'body': body, 'replies': replies, 'context': context})
        self.send(200, {'id': 'session-fixture', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if batch is not None else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


class SessionExperiment(Experiment):
    start = McpExperiment.start
    state = McpExperiment.state
    outputs = McpExperiment.outputs

    def stop(self, instance, kill=False):
        child = instance['process']
        if kill and child.poll() is None:
            child.kill()
            eventually(lambda: self.sql("SELECT stopped FROM worker_process WHERE pid="+str(child.pid)+";") == '1', 'supervisor proves killed session worker stopped')
        Experiment.stop(self, instance)

    def prepare(self):
        self.fixture.RequestHandlerClass = Model
        self.fixture.plans, self.fixture.requests = {}, []
        super().prepare()
        for table in ('task_session_history', 'session_public_event', 'invocation_session_read'):
            self.sql("GRANT INSERT,UPDATE ON jimu." + table + " TO 'runtime'@'%';")
        source = Path(__file__).resolve().parents[1] / 'src/main/resources/session/session_event_read-0.1.0'
        digest = hashlib.sha256((source / 'output.schema.json').read_bytes()).hexdigest()
        self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES"
            + ','.join("('" + t + "','" + NAME + "','0.1.0','" + digest + "')" for t in ('tenant-a', 'tenant-b')) + ';')
        self.assert_that(NAME not in self.http('GET', '/api/capabilities')[1], 'default flag hides session tool')
        status, value = self.http('POST', '/api/tasks', {'goal': 'disabled', 'capabilityIds': [NAME]}, key='disabled')
        self.assert_that(status == 403 and value['code'] == 'SESSION_READ_DISABLED', 'default flag rejects session admission', status=status)
        self.stop(self.control)
        settings = config({'a': self.fixture}, paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        settings.update({'jimu.model.profiles.a.model': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.revision': 'local-byte-accounted-v1',
            'jimu.model.profiles.a.response-bytes': '16384',
            'jimu.model.profiles.a.input-limit.version': '1',
            'jimu.model.profiles.a.input-limit.max-tokens': '8192',
            'jimu.model.profiles.a.input-limit.proof': 'test-whole-request-utf8-v1',
            'jimu.session-event-read.enabled': 'true',
            'jimu.private.synthetic-setting': 'CONFIG_SENTINEL_52',
            'jimu.web.mode': 'public', 'jimu.web.allowed-domains': 'fixture.example',
            'jimu.web.public-dispatch-enabled': 'true'})
        self.common.update(settings)
        self.control = self.start('control-session', 'control')
        self.sql('UPDATE tenant_budget SET history_storage_limit=536870912,model_limit=2000,tool_limit=2000,token_limit=67108864;')
        self.task_subjects = {}

    def task(self, goal, plan, subject='a', **fields):
        self.fixture.plans[goal] = plan
        status, value = self.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': [NAME], **fields}, key=hashlib.sha256(goal.encode()).hexdigest(), subject=subject)
        self.assert_that(status == 202, 'accept ' + goal, status=status, value=value)
        self.task_subjects[value['taskId']] = subject
        return value['taskId']

    def metadata(self, task, subject='a', status=200):
        actual, value = self.http('GET', '/api/tasks/' + task + '/session-history', subject=subject)
        assert actual == status, (actual, value)
        return value

    def event(self, task, seq, query='', subject='a', status=200):
        actual, value = self.http('GET', '/api/tasks/' + task + '/session-history/' + str(seq) + query, subject=subject)
        assert actual == status, (actual, value)
        return value

    def ledger_scenario(self, worker, task, mode):
        classes = self.root / 'session-test-classes'
        if not classes.exists():
            shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
        command = [self.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionLedgerScenario',
            '-Dloader.path=' + str(classes), '-cp', str(self.jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher',
            str(worker['folder'] / 'runtime.properties'), task, mode]
        run = subprocess.run(command, text=True, capture_output=True, timeout=30)
        (self.evidence / ('ledger-' + task + '-' + mode + '.log')).write_text(run.stdout + run.stderr)
        self.assert_that(run.returncode == 0 and 'PASS' in run.stdout,
            'real ledger ' + mode, task=task, exit=run.returncode, stderr=run.stderr[-3000:])

    def normal(self):
        task = self.task('H01 public history Unicode 🧱', [
            {'seq': 0, 'before': 4, 'after': 4},
            {'seq': 2, 'session_id': '$current', 'before': 2, 'after': 4},
            {'seq': 3, 'before': 1, 'after': 1}])
        self.assert_that(self.metadata(task)['upper_seq'] == 0, 'native seq zero at acceptance')
        workers = [self.start('session-worker-' + str(i), 'worker') for i in range(2)]
        self.state(task, 'SUCCEEDED')
        for w in workers: self.stop(w)
        self.ledger_scenario(workers[0], task, 'accounting')
        outputs = self.outputs(task)
        self.assert_that(len(outputs) == 3 and outputs[0]['upper_seq'] == 0 and outputs[0]['target']['seq'] == 0
            and outputs[0]['before'] == [] and outputs[0]['after'] == [], 'first step freezes pre-step user-only boundary')
        self.assert_that(outputs[1]['upper_seq'] == 3 and outputs[1]['target']['type'] == 'model.response'
            and [e['seq'] for e in outputs[1]['before']] == [0, 1] and [e['seq'] for e in outputs[1]['after']] == [3], 'complete earlier model response and clipped ordered neighbors')
        receipt = outputs[2]['target']
        self.assert_that(receipt['type'] == 'session.read' and 'output_sha256' in receipt['data']
            and 'target' not in receipt['data'] and 'output' not in receipt['data'], 'read receipt never embeds or expands prior output')
        native = [json.loads(line) for line in self.sql("SELECT event_snapshot FROM session_public_event WHERE task_id='"+task+"' ORDER BY seq;").splitlines()]
        for output in outputs:
            self.assert_that(output['target'] == native[output['target']['seq']], 'complete target equals authoritative stored public event', seq=output['target']['seq'])
        self.assert_that([e['seq'] for e in native] == list(range(len(native))) and native[-1]['type'] == 'turn.ended', 'continuous native log and one terminal fact')
        self.assert_that(self.sql("SELECT COUNT(*) FROM artifact WHERE task_id='"+task+"';") == '0', 'read-only profile creates no artifact')
        before = self.sql("SELECT models_used,tools_used,next_event_seq FROM task WHERE id='"+task+"';")
        for _ in range(2):
            meta = self.metadata(task)
            self.event(task, 2, '?before=2&after=4')
        self.assert_that(before == self.sql("SELECT models_used,tools_used,next_event_seq FROM task WHERE id='"+task+"';"), 'owner reads do not consume history or budgets')
        self.assert_that(meta['readable'] and meta['upper_seq'] == len(native)-1, 'owner reads terminal history')
        for subject in ('b', 'c'):
            self.metadata(task, subject, 404)
            self.event(task, 0, subject=subject, status=404)
        self.assert_that(True, 'cross tenant and cross owner return uniform 404')
        self.stop(self.control)
        self.control = self.start('control-session-restart', 'control')
        self.assert_that(self.event(task, 0)['target'] == native[0], 'restart preserves public bytes and source')
        self.primary_task = task

    def recovery(self):
        for point in ('M1', 'M2', 'SESSION_COMMIT_BEFORE', 'SESSION_COMMIT_AFTER'):
            goal = 'H03 recover ' + point
            task = self.task(goal, [{'seq': 0, 'before': 4, 'after': 4}])
            worker = self.start('before-' + point, 'worker', point)
            self.barrier(worker)
            original = self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;")
            output = self.sql("SELECT output_digest FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;")
            if point == 'SESSION_COMMIT_AFTER':
                self.ledger_scenario(worker, task, 'replay')
            self.stop(worker, kill=True)
            self.expire(task)
            resumed = self.start('after-' + point, 'worker')
            self.state(task, 'SUCCEEDED')
            self.stop(resumed)
            self.assert_that(original == self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;"), point + ' preserves original M1 boundary and context')
            self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '1'
                and self.sql("SELECT COUNT(*) FROM session_public_event WHERE task_id='"+task+"' AND type='session.read';") == '1', point + ' exactly one tool and receipt')
            result = self.outputs(task)[0]
            self.assert_that(result['upper_seq'] == 0 and result['target']['seq'] == 0 and result['after'] == [], point + ' recovery excludes later facts')
            if point == 'SESSION_COMMIT_AFTER':
                self.assert_that(output == self.sql("SELECT output_digest FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;"), 'postcommit recovery returns exact original output digest')
            self.ledger_scenario(resumed, task, 'accounting')
        for point in ('before', 'after'):
            task = self.task('H04 commit uncertainty ' + point, [{'seq': 0, 'after': 4}])
            worker = self.start('uncertain-' + point, 'worker', 'SESSION_COMMIT_BEFORE')
            self.barrier(worker)
            self.ledger_scenario(worker, task, 'uncertain-' + point)
            self.stop(worker, kill=True)
            self.expire(task)
            resumed = self.start('uncertain-resume-' + point, 'worker')
            self.state(task, 'SUCCEEDED')
            self.stop(resumed)
            self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '1', 'new worker resolves lost commit acknowledgement once ' + point)

    def redaction(self):
        task = self.task('unsafe-model Bearer SYNTHETIC_TOKEN /private/goal next 🧱 '+self.common['jimu.identity.key']+' '+self.password, [{'seq': 0}, {'seq': 2}])
        worker = self.start('redaction-worker', 'worker')
        # The final unsafe summary is deliberately rejected; already admitted native responses remain public projections.
        self.state(task, 'FAILED')
        self.stop(worker)
        for seq in (0, 2):
            event = self.event(task, seq)['target']
            raw = json.dumps(event)
            self.assert_that(event['redacted'] and all(secret not in raw for secret in
                ('SYNTHETIC_TOKEN', '/private/', 'CONFIG_SENTINEL_52', 'HIDDEN_REASONING_SENTINEL_52', self.password, self.common['jimu.identity.key'])),
                'capture-time public projection and redacted flag', seq=seq)
        public = self.http('GET', '/api/tasks/'+task)[1]
        self.assert_that(public['summary'] is None and public['code'] == 'SESSION_EVENT_UNSAFE', 'unsafe summary fails closed without public private-text copy')
        sse = self.http('GET', '/api/tasks/'+task+'/events')[1]
        self.assert_that(all(secret not in sse for secret in ('SYNTHETIC_TOKEN','CONFIG_SENTINEL_52','HIDDEN_REASONING_SENTINEL_52',self.password,self.common['jimu.identity.key'])), 'reliable SSE carries no model content or sensitive sentinel')
        task = self.task('H08 ' + '🧱'*257, [])
        worker = self.start('summary-prefix-worker', 'worker')
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        summary = self.event(task, 1, '?before=1')['before'][0]
        self.assert_that(summary['summary_truncated'] and len(summary['text']) == 256 and len(summary['text'].encode()) <= 1024,
            'real stored neighbor summary clips complete Unicode code points with explicit flag')

    def authorization(self):
        task = self.task('H04 ordered same-response slots', [[{'seq': 0}, {'seq': 3}]])
        worker = self.start('ordered-worker', 'worker')
        self.state(task, 'FAILED')
        self.stop(worker)
        self.assert_that(self.sql("SELECT upper_seq,outcome FROM invocation_session_read WHERE task_id='"+task+"' ORDER BY invocation_id;").splitlines().count('0\tSUCCEEDED') == 1
            and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '2', 'same response second slot cannot see first slot receipt')
        for label in ('cancel', 'deadline', 'flag'):
            task = self.task('H06 stopped ' + label, [{'seq': 0}])
            worker = self.start('stopped-' + label, 'worker', 'M2')
            self.barrier(worker)
            if label == 'cancel': self.http('POST', '/api/tasks/'+task+'/cancel')
            if label == 'deadline': self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
            if label == 'flag':
                self.stop(worker, kill=True)
                self.expire(task)
                worker = self.start('stopped-flag-disabled', 'worker', extra={'jimu.session-event-read.enabled': 'false'})
            else: (worker['folder']/'release').touch()
            view = self.state(task, 'CANCELLED' if label == 'cancel' else 'FAILED')
            self.stop(worker)
            expected = {'cancel':'CANCEL_REQUESTED', 'deadline':'DEADLINE_EXCEEDED', 'flag':'SESSION_READ_DISABLED'}[label]
            self.assert_that((label == 'cancel' or view['code'] == expected)
                and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '0', 'stop after accepted model prevents tool disclosure ' + label)
            self.assert_that(self.sql("SELECT total_tokens FROM model_dispatch WHERE task_id='"+task+"';") == '150', 'stop preserves known real model usage ' + label)

    def transport(self):
        task = self.task('H05 forged original slot fields', [{'seq':0}])
        worker = self.start('original-slot-fields', 'worker', 'SESSION_COMMIT_AFTER')
        self.barrier(worker)
        self.ledger_scenario(worker,task,'replay')
        (worker['folder']/'release').touch()
        self.state(task,'SUCCEEDED')
        self.stop(worker)
        for goal, expected in [('transport-invalid-slot', 'MODEL_RESPONSE_INVALID'),
            ('transport-provider-failure', 'MODEL_HTTP_ERROR')]:
            task = self.task(goal, [{'seq':0}])
            worker = self.start(goal, 'worker')
            view = self.state(task, 'FAILED')
            self.stop(worker)
            self.assert_that(view['code'] == expected and view['summary'] is None, 'safe model transport rejection', code=view['code'])
            self.assert_that(self.sql("SELECT COUNT(*) FROM session_public_event WHERE task_id='"+task+"' AND type='model.response';") == '0', 'rejected transport does not manufacture accepted native response')
            if goal == 'transport-invalid-slot':
                self.assert_that(self.sql("SELECT state,actual,usage_quality FROM reservation WHERE task_id='"+task+"' AND category='MODEL';") == 'SETTLED\t150\tKNOWN', 'invalid wire slot preserves actual known usage')
        command = ['docker','stop',self.cid]
        subprocess.run(command,check=True,capture_output=True,text=True,timeout=30)
        try:
            code, result = self.http('GET','/api/tasks/'+task+'/session-history')
            self.assert_that(code == 503 and result['code'] == 'SESSION_QUERY_UNAVAILABLE', 'history provider failure has safe 503 with no fallback')
        finally:
            subprocess.run(['docker','start',self.cid],check=True,capture_output=True,text=True,timeout=30)
            eventually(lambda: self.database_ready(), 'private history provider restored')

    def failures(self):
        plans = [({'seq': '$outside'}, 'SESSION_EVENT_NOT_FOUND'),
            ({'seq': 9007199254740991}, 'SESSION_EVENT_NOT_FOUND'),
            ({'seq': 0, 'session_id': '00000000-0000-4000-8000-000000000052'}, 'SESSION_EVENT_NOT_FOUND'),
            ('{"seq":1.0}', 'TOOL_INPUT_INVALID'), ('{"seq":0,"seq":1}', 'TOOL_INPUT_INVALID'),
            ('{"seq":0,"before":5}', 'TOOL_INPUT_INVALID'), ('{"seq":0,"x":1}', 'TOOL_INPUT_INVALID')]
        for i, (input, code) in enumerate(plans):
            task = self.task('H07 reject ' + str(i), [input])
            worker = self.start('reject-worker-' + str(i), 'worker')
            view = self.state(task, 'FAILED')
            self.stop(worker)
            self.assert_that(view['code'] == code, 'strict or bounded not-found rejection', case=i, code=view['code'])
            used = self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")
            self.assert_that(used == ('1' if code == 'SESSION_EVENT_NOT_FOUND' else '0'), 'determinate query failure binds once; invalid input has no tool charge', case=i)
        task = self.primary_task
        for query in ('?before=5', '?before=1&before=1', '?x=1', '?after=1.0', '?after=-1', '?after=1e0'):
            self.event(task, 0, query, status=400)
        self.event(task, 9007199254740991, status=404)
        self.assert_that(True, 'strict owner query grammar and bounded large seq')

    def retention(self):
        task = self.primary_task
        blockers = []
        for index in range(17):
            blocked = self.task('H11 unsafe prefix ' + str(index), [])
            self.http('POST', '/api/tasks/'+blocked+'/cancel')
            blockers.append(blocked)
            if index % 2 == 0:
                self.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a','"+blocked+":unknown','"+blocked+"','MODEL',1,'HELD_UNKNOWN','UNKNOWN');")
            else:
                self.sql("INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state,quiescent) VALUES('tenant-a','"+str(uuid.uuid4())+"','"+blocked+"','synthetic-unconfirmed-writer',1,'EXPIRED',FALSE);")
        ids = ','.join("'"+item+"'" for item in blockers)
        self.sql("UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 2 DAY WHERE task_id IN ("+ids+");")
        self.sql("UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+task+"';")
        self.event(task, 0, status=410)
        eventually(lambda: self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+task+"';") == 'PURGED', 'safe retained history purge')
        self.assert_that(self.sql("SELECT body_bytes,held_bytes=metadata_bytes,cleanup_receipt IS NOT NULL FROM task_session_history WHERE task_id='"+task+"';") == '0\t1\t1', 'purge removes all body bytes and charges metadata tombstone')
        self.assert_that(self.sql("SELECT COUNT(*) FROM session_public_event WHERE task_id='"+task+"' AND event_snapshot IS NOT NULL;") == '0'
            and self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"' AND (input_snapshot<>'' OR output_snapshot IS NOT NULL);") == '0'
            and self.sql("SELECT COUNT(*) FROM step WHERE task_id='"+task+"' AND (request_snapshot<>'' OR response_snapshot IS NOT NULL OR request_cleanup_state<>'SESSION_PURGED');") == '0', 'all authoritative and recovery body copies purged')
        self.assert_that(not self.metadata(task)['readable'] and self.metadata(task)['recent'] == [], 'purged metadata is a readable tombstone without content')
        self.assert_that(self.sql("SELECT COUNT(*) FROM task_session_history WHERE task_id IN ("+ids+") AND content_state='PRESENT';") == '17', 'unsafe prefix does not starve safe candidate or release UNKNOWN/unconfirmed writers')
        held = self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")
        time.sleep(2)
        self.assert_that(held == self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"), 'repeated maintenance releases reservation only once')

    def diagnostics(self):
        task = self.primary_task
        old = self.sql("SELECT event_digest,source_digest FROM session_public_event WHERE task_id='"+task+"' AND seq=0;").split('\t')
        for field, value, code in [('event_digest', '0'*64, 'SESSION_HISTORY_CORRUPT'),
            ('source_digest', '0'*64, 'SESSION_HISTORY_CORRUPT'), ('event_version', '2', 'SESSION_HISTORY_VERSION_UNSUPPORTED')]:
            # Only this root-owned private fixture connection may inject a future row version;
            # normal writers are additionally protected by the V19 CHECK(version=1).
            setup = 'SET SESSION check_constraint_checks=OFF; ' if field == 'event_version' else ''
            self.sql(setup+"UPDATE session_public_event SET "+field+"='"+value+"' WHERE task_id='"+task+"' AND seq=0;")
            try:
                actual, response = self.http('GET', '/api/tasks/'+task+'/session-history/0')
                self.assert_that(actual == 409 and response['code'] == code, 'reject persisted corruption/version ' + field)
            finally:
                original = '1' if field == 'event_version' else old[0 if field == 'event_digest' else 1]
                self.sql("UPDATE session_public_event SET "+field+"='"+original+"' WHERE task_id='"+task+"' AND seq=0;")
        plan = self.sql("EXPLAIN SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id='"+task+"' AND seq BETWEEN 0 AND 8 ORDER BY seq LIMIT 9;")
        (self.evidence/'pk-explain.tsv').write_text(plan)
        self.assert_that('PRIMARY' in plan and '\tALL\t' not in plan, 'native window query uses bounded primary key range')
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            lock = executor.submit(self.sql, "START TRANSACTION; SELECT id FROM task WHERE id='"+task+"' FOR UPDATE; DO SLEEP(4); ROLLBACK;")
            eventually(lambda: bool(self.sql("SELECT trx_id FROM information_schema.innodb_trx WHERE trx_query='DO SLEEP(4)';")), 'private task row locked')
            started = time.monotonic()
            status, response = self.http('GET', '/api/tasks/'+task+'/session-history/0')
            elapsed = time.monotonic()-started
            self.assert_that(status == 504 and response['code'] == 'SESSION_QUERY_TIMEOUT' and elapsed < 2.5,
                'bounded lock timeout and safe public error', elapsedSeconds=elapsed)
            lock.result(timeout=8)

    def cleanup(self):
        try:
            for child in reversed(self.children): self.stop({'process': child, 'name': 'session foreground collection'})
            if self.schema_ready:
                for table in ('task_session_history', 'session_public_event', 'invocation_session_read'):
                    (self.evidence/(table+'.tsv')).write_text(self.sql('SELECT * FROM '+table+';'))
                (self.evidence/'model-wire.json').write_text(json.dumps(self.fixture.requests, ensure_ascii=True, indent=2))
        finally:
            super().cleanup()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--case', choices=['normal', 'core', 'recovery', 'redaction', 'authorization', 'transport', 'all'], default='normal')
    args = parser.parse_args()
    experiment = SessionExperiment(args.jar, args.evidence)
    try:
        experiment.prepare()
        if args.case in ('normal', 'core', 'all'): experiment.normal()
        if args.case in ('recovery', 'all'): experiment.recovery()
        if args.case in ('redaction', 'all'): experiment.redaction()
        if args.case in ('authorization', 'all'): experiment.authorization()
        if args.case == 'transport': experiment.transport()
        if args.case in ('core', 'all'):
            experiment.failures()
            experiment.diagnostics()
            experiment.retention()
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
