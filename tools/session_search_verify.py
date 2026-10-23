"""Foreground ADR-0012 acceptance with private MySQL, real JVMs and local paid=false model."""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import signal
import socket
import time
import traceback

from p2_verify import Handler, eventually
from session_event_verify import SessionExperiment

READ, SEARCH = 'session_event_read', 'session_event_search'


class Model(Handler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in request['messages'] if m['role'] == 'user')
        context = json.loads(request['messages'][-1]['content'])
        assert context['version'] == 2
        assert [t['function']['name'] for t in request['tools']] == [READ, SEARCH]
        replies = [json.loads(m['content']) for m in request['messages'] if m['role'] == 'tool']
        plan = self.server.plans[goal]
        at = len(replies)
        batch = plan[at] if at < len(plan) else None
        message = {'role': 'assistant', 'content': 'model marker53 public text.'}
        if batch is not None:
            calls = []
            for i, specification in enumerate(batch if isinstance(batch, list) else [batch]):
                name, arguments = specification
                if isinstance(arguments, dict):
                    arguments = dict(arguments)
                    if arguments.get('session_id') == '$current': arguments['session_id'] = context['session_id']
                    if arguments.get('seq') == '$hit':
                        arguments['seq'] = next(r['items'][0]['seq'] for r in reversed(replies) if r.get('items'))
                    arguments = json.dumps(arguments, ensure_ascii=True)
                calls.append({'id': 'slot_' + str(at+i), 'type': 'function',
                    'function': {'name': name, 'arguments': arguments}})
            message['tool_calls'] = calls
        message['reasoning_content'] = 'HIDDEN_SEARCH_REASONING_53 /private/hidden'
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({'goal': goal, 'body': request, 'replies': replies, 'context': context})
        self.send(200, {'id': 'local-session-search', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if batch is not None else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


class SearchExperiment(SessionExperiment):
    def prepare(self):
        super().prepare()
        self.stop(self.control)
        self.fixture.RequestHandlerClass = Model
        self.sql("GRANT INSERT,UPDATE ON jimu.invocation_session_search TO 'runtime'@'%';")
        root = Path(__file__).resolve().parents[1] / 'src/main/resources/session/v2'
        for name, version in ((READ, '0.2.0'), (SEARCH, '0.1.0')):
            digest = hashlib.sha256((root / (name+'-'+version) / 'output.schema.json').read_bytes()).hexdigest()
            for tenant in ('tenant-a', 'tenant-b'):
                self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('"+tenant+"','"+name+"','"+version+"','"+digest+"') ON DUPLICATE KEY UPDATE plugin_version=VALUES(plugin_version),plugin_digest=VALUES(plugin_digest);")
        self.common['jimu.session-event-search.enabled'] = 'true'
        self.common['jimu.model.profiles.a.input-limit.max-tokens'] = '65536'
        self.control = self.start('control-search-v2', 'control')
        self.assert_that(self.http('GET', '/api/capabilities')[1] == [READ, SEARCH, 'web_fetch'], 'two version-pinned session capabilities discovered')
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;') == '20', 'V20 installed')
        (self.evidence/'show-create-history.tsv').write_text(self.sql('SHOW CREATE TABLE task_session_history;'))
        (self.evidence/'show-create-events.tsv').write_text(self.sql('SHOW CREATE TABLE session_public_event;'))

    def task(self, goal, plan, subject='a', **fields):
        self.fixture.plans[goal] = plan
        status, value = self.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': [READ, SEARCH], **fields},
            key=hashlib.sha256(goal.encode()).hexdigest(), subject=subject)
        self.assert_that(status == 202, 'accept '+goal, status=status, value=value)
        self.task_subjects[value['taskId']] = subject
        return value['taskId']

    def search(self, task, arguments, subject='a', status=200, query=''):
        actual, value = self.http('POST', '/api/tasks/'+task+'/session-history/search'+query, arguments, subject=subject)
        self.assert_that(actual == status, 'owner search status', expected=status, actual=actual, value=value)
        return value

    def raw(self, task, raw, subject='a', path_suffix='', content_type='application/json'):
        connection = http.client.HTTPConnection('127.0.0.1', self.control['port'], timeout=5)
        try:
            connection.request('POST', '/api/tasks/'+task+'/session-history/search'+path_suffix, raw,
                {'Authorization':'Bearer '+self.token(subject), 'Content-Type':content_type})
            response = connection.getresponse()
            data = response.read()
            return response.status, json.loads(data), dict(response.getheaders())
        finally: connection.close()

    def normal(self):
        task = self.task('S01 needle53 中文 🧱', [(SEARCH, {'query':'needle53'}), (READ, {'seq':'$hit'}),
            (SEARCH, {'query':'model marker53', 'event_types':['model.response']}), (READ, {'seq':'$hit'})])
        if getattr(self,'takeover',False):
            first=self.start('search-worker-0','worker','SESSION_SEARCH_COMMIT_AFTER')
            self.barrier(first)
            workers=[first,self.start('search-worker-1','worker')]
            self.stop(first,kill=True); self.expire(task)
        else:
            workers = [self.start('search-worker-'+str(i), 'worker') for i in range(2)]
        self.state(task, 'SUCCEEDED')
        for worker in workers: self.stop(worker)
        outputs = self.outputs(task)
        self.assert_that(len(outputs) == 4 and outputs[0]['items'][0]['seq'] == 0 and outputs[0]['upper_seq'] == 0,
            'query finds actual user seq under M1 boundary')
        self.assert_that(outputs[1]['target']['seq'] == outputs[0]['items'][0]['seq']
            and hashlib.sha256(self.sql("SELECT event_snapshot FROM session_public_event WHERE task_id='"+task+"' AND seq=0;").encode()).hexdigest() == outputs[0]['items'][0]['event_sha256'],
            'read verifies exact search event digest')
        self.assert_that(outputs[2]['items'][0]['type'] == 'model.response' and outputs[3]['target']['type'] == 'model.response',
            'later real step searches and reads stored model content')
        self.assert_that(self.metadata(task)['version'] == 2 and self.event(task, 0)['history_version'] == 2, 'owner metadata/read select v2')
        self.assert_that(self.sql("SELECT COUNT(*) FROM session_public_event WHERE task_id='"+task+"' AND type='session.read';") == '2'
            and self.sql("SELECT COUNT(*) FROM invocation_session_search WHERE task_id='"+task+"' AND outcome='SUCCEEDED';") == '2',
            'search has own bindings and no fake native read receipt')
        self.ledger_scenario(workers[0], task, 'accounting')
        before = self.sql("SELECT revision,models_used,tools_used,next_event_seq FROM task WHERE id='"+task+"';")
        result = self.search(task, {'query':'needle53'})
        self.assert_that(result['boundary'] == 'owner-snapshot' and result['items'][0]['seq'] == 0, 'terminal owner search remains read-only')
        self.assert_that(before == self.sql("SELECT revision,models_used,tools_used,next_event_seq FROM task WHERE id='"+task+"';"), 'owner search does not mutate task or budget')
        self.primary_task, self.accounting_worker = task, workers[0]
        self.stop(self.control)
        self.control=self.start('owner-search-reconnected','control')
        self.assert_that(self.metadata(task)['version'] == 2 and self.search(task,{'query':'needle53'})['items'][0]['seq'] == 0,
            'same completed v2 history survives control restart and owner reconnect')
        models=dict(self.fixture.models)
        status,stream=self.http('GET','/api/tasks/'+task+'/events')
        envelopes=[json.loads(line[5:]) for line in stream.splitlines() if line.startswith('data:')]
        self.assert_that(status == 200 and [e['seq'] for e in envelopes] == list(range(1,len(envelopes)+1)),
            'v2 reliable SSE remains ordered and separate from native seq')
        cursor=envelopes[len(envelopes)//2]['eventId']
        status,resumed=self.http('GET','/api/tasks/'+task+'/events',headers={'Last-Event-ID':cursor})
        self.assert_that(status == 200 and cursor not in [json.loads(line[5:])['eventId'] for line in resumed.splitlines() if line.startswith('data:')],
            'v2 Last-Event-ID reconnect has no duplicate')
        self.assert_that(models == self.fixture.models,'v2 SSE reconnect does not execute model or tool')
        (self.evidence/'search-pk-explain.tsv').write_text(self.sql("EXPLAIN SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id='"+task+"' AND seq BETWEEN 0 AND 127 ORDER BY seq LIMIT 128;"))

    def inputs(self):
        task = self.primary_task
        for capabilities in ([SEARCH], [SEARCH,READ], [READ,SEARCH,SEARCH], [READ,SEARCH,'web_fetch']):
            status,value=self.http('POST','/api/tasks',{'goal':'invalid combo','capabilityIds':capabilities},key='combo-'+str(len(capabilities))+capabilities[0])
            self.assert_that(status == 400 and value['code'] == 'INVALID_CAPABILITIES','exact ordered search admission',capabilities=capabilities)
        for raw in (b'{"query":"a","query":"b"}', b'{"query":"a","seq_from":1.0}', b'{"query":"a","seq_to":1e0}',
            b'{"query":"a","seq_from":9007199254740992}', b'{"query":"a","x":1}', b'{"query":"\\ud800"}',
            b'{"query":"a","time_from":"2026-02-29T00:00:00Z"}', b'{"query":"a","time_from":"2026-01-01T00:00:00+18:01"}',
            b'{"query":"a","event_types":["user.message","user.message"]}', b'{"query":"a","surfaces":[]}',
            b'{"query":"a","surfaces":["current","current"]}', b'{"query":"a","seq_from":2,"seq_to":1}',
            b'{"query":"\xc0\xaf"}', b'{"query":"a"} {}', b' ' * 2049):
            status, value, headers = self.raw(task, raw)
            self.assert_that(status == 400 and value['code'] == 'TOOL_INPUT_INVALID', 'strict raw owner input', rawSha256=hashlib.sha256(raw).hexdigest(), status=status, value=value)
        raw = b'{"query":"needle53"}'
        self.assert_that(self.raw(task, raw+b' '*(2048-len(raw)))[0] == 200, 'owner input exactly2048 accepted')
        self.assert_that(self.raw(task, raw, path_suffix='?x=1')[0] == 400, 'extra querystring rejected')
        self.assert_that(self.raw(task, raw, content_type='text/plain')[0] == 400, 'owner content type enforced')
        self.assert_that(self.search(task, {'query':'a', 'surfaces':['current']}, status=400)['code'] == 'SESSION_SEARCH_FILTER_UNSUPPORTED', 'valid surface explicitly unsupported')
        for arguments in ({'query':'.*'}, {'query':'NEEDLE53'}, {'query':'absent'}, {'query':'needle53','seq_from':9007199254740991},
            {'query':'needle53','event_types':['step.started']}, {'query':'needle53','seq_from':1}, {'query':'needle53','time_to':'0001-01-01T00:00:00Z'}):
            value=self.search(task, arguments)
            self.assert_that(value['items'] == [] and not value['capped'], 'literal/filtered true empty result', arguments=arguments)
        event = self.event(task,0)['target']
        self.assert_that(self.search(task, {'query':'needle53','seq_from':0,'seq_to':0,'time_from':event['time'],
            'time_to':event['time'],'event_types':['user.message','model.response']})['items'][0]['seq'] == 0, 'combined inclusive microsecond/seq/type filters')
        self.assert_that(self.search(task, {'query':'a','session_id':'00000000-0000-4000-8000-000000000053'},status=404)['code'] == 'SESSION_EVENT_NOT_FOUND', 'body session cannot authorize another task')
        for subject in ('b','c'):
            self.assert_that(self.raw(task,b'{invalid',subject=subject)[0] == 404, 'authorize path before malformed body '+subject)
        started=time.monotonic()
        connection=http.client.HTTPConnection('127.0.0.1',self.control['port'],timeout=5)
        try:
            connection.putrequest('POST','/api/tasks/'+task+'/session-history/search')
            for key,value in {'Authorization':'Bearer '+self.token('a'),'Content-Type':'application/json','Content-Length':'100'}.items(): connection.putheader(key,value)
            connection.endheaders(); connection.send(b'{')
            response=connection.getresponse(); value=json.loads(response.read())
            elapsed=time.monotonic()-started
            self.assert_that(response.status == 504 and value['code'] == 'SESSION_QUERY_TIMEOUT' and elapsed < 2.7,
                'slow body obeys shared2second cutoff without reader thread',elapsedSeconds=elapsed,status=response.status,value=value)
        finally: connection.close()
        self.search(task, {'query':'needle53'})
        worker=self.start('strict-model-search-worker','worker')
        try:
            for index,raw in enumerate(('{"query":"a","query":"b"}', '{"query":"a","seq_from":1.0}',
                '{"query":"a","surfaces":["current"]}', '{"query":"a","unknown":1}')):
                invalid=self.task('S07 invalid model '+str(index),[(SEARCH,raw)])
                self.state(invalid,'FAILED')
                self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+invalid+"';") == '0'
                    and self.sql("SELECT total_tokens FROM model_dispatch WHERE task_id='"+invalid+"';") == '150',
                    'invalid model input preserves usage without tool consumption',index=index)
        finally: self.stop(worker)

    def recovery(self):
        for point in ('M1','M2','SESSION_SEARCH_COMMIT_BEFORE','SESSION_SEARCH_COMMIT_AFTER'):
            task=self.task('S04 recover needle53 '+point, [(SEARCH,{'query':'needle53'})])
            worker=self.start('before-'+point,'worker',point)
            self.barrier(worker)
            original=self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;")
            digest=self.sql("SELECT output_digest FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;")
            if point == 'SESSION_SEARCH_COMMIT_AFTER': self.ledger_scenario(worker,task,'replay')
            self.stop(worker,kill=True); self.expire(task)
            resumed=self.start('after-'+point,'worker')
            self.state(task,'SUCCEEDED'); self.stop(resumed)
            self.assert_that(original == self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;"),point+' preserves M1 boundary')
            output=self.outputs(task)[0]
            self.assert_that(output['upper_seq'] == 0 and output['items'][0]['seq'] == 0
                and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '1'
                and self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='tool.completed';") == '1',point+' original slot once')
            if point.endswith('AFTER'): self.assert_that(digest == self.sql("SELECT output_digest FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;"),'postcommit exact digest')
            self.ledger_scenario(resumed,task,'accounting')
        for point in ('before','after'):
            task=self.task('S04 commit uncertainty needle53 '+point,[(SEARCH,{'query':'needle53'})])
            worker=self.start('uncertain-'+point,'worker','SESSION_SEARCH_COMMIT_BEFORE'); self.barrier(worker)
            self.ledger_scenario(worker,task,'uncertain-'+point)
            self.stop(worker,kill=True); self.expire(task)
            resumed=self.start('uncertain-resume-'+point,'worker'); self.state(task,'SUCCEEDED'); self.stop(resumed)
            self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '1','lost acknowledgement settles once '+point)

    def boundaries(self):
        task=self.task('S04 frozen needle53', [[(SEARCH,{'query':'needle53'}),(READ,{'seq':0}),
            (SEARCH,{'query':'model marker53'})]])
        worker=self.start('multi-slot-worker','worker'); self.state(task,'SUCCEEDED'); self.stop(worker)
        values=self.outputs(task)
        self.assert_that(len(values) == 3 and all(v['upper_seq'] == 0 for v in values) and values[2]['items'] == [],
            'all response slots exclude current response and preceding read receipt')
        self.ledger_scenario(worker,task,'accounting')
        for label in ('cancel','deadline','flag','actor','installation'):
            task=self.task('S06 stop needle53 '+label,[(SEARCH,{'query':'needle53'})])
            worker=self.start('stop-'+label,'worker','M2'); self.barrier(worker)
            if label == 'cancel': self.http('POST','/api/tasks/'+task+'/cancel')
            elif label == 'deadline': self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
            elif label == 'actor': self.sql("UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';")
            elif label == 'installation': self.sql("UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='session_event_search';")
            if label == 'flag':
                self.stop(worker,kill=True); self.expire(task)
                worker=self.start('stop-flag-off','worker',extra={'jimu.session-event-search.enabled':'false'})
            else: (worker['folder']/'release').touch()
            expected='CANCELLED' if label == 'cancel' else 'FAILED'
            eventually(lambda: self.sql("SELECT state FROM task WHERE id='"+task+"';") == expected,'stop '+label)
            self.stop(worker)
            if label == 'actor': self.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice';")
            if label == 'installation': self.sql("UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='session_event_search';")
            self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '0'
                and self.sql("SELECT total_tokens FROM model_dispatch WHERE task_id='"+task+"';") == '150','stop rejects tool but preserves actual usage '+label)
        self.stop(self.control)
        self.control=self.start('owner-flag-off','control',extra={'jimu.session-event-search.enabled':'false'})
        self.search(self.primary_task,{'query':'needle53'})
        self.event(self.primary_task,0)
        self.assert_that(SEARCH not in self.http('GET','/api/capabilities')[1], 'flag off hides new task capability while owner history works')
        status,value=self.http('POST','/api/tasks',{'goal':'disabled-v2','capabilityIds':[READ,SEARCH]},key='disabled-v2')
        self.assert_that(status == 403 and value['code'] == 'SESSION_SEARCH_DISABLED','flag off rejects new v2 admission')
        self.stop(self.control); self.control=self.start('owner-flag-restored','control')

    def integrity(self):
        task=self.primary_task
        original=self.sql("SELECT event_digest,source_digest FROM session_public_event WHERE task_id='"+task+"' AND seq=0;").split('\t')
        for field,old in zip(('event_digest','source_digest'),original):
            self.sql("UPDATE session_public_event SET "+field+"='"+'f'*64+"' WHERE task_id='"+task+"' AND seq=0;")
            try:
                self.assert_that(self.search(task,{'query':'absent','event_types':['step.started']},status=409)['code'] == 'SESSION_HISTORY_CORRUPT', 'filters do not hide corrupt '+field)
            finally: self.sql("UPDATE session_public_event SET "+field+"='"+old+"' WHERE task_id='"+task+"' AND seq=0;")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            lock=executor.submit(self.sql,"START TRANSACTION; SELECT id FROM task WHERE id='"+task+"' FOR UPDATE; DO SLEEP(4); ROLLBACK;")
            eventually(lambda: bool(self.sql("SELECT trx_id FROM information_schema.innodb_trx WHERE trx_query='DO SLEEP(4)';")),'private row locked')
            started=time.monotonic(); status,value,_=self.raw(task,b'{"query":"needle53"}')
            self.assert_that(status == 504 and value['code'] == 'SESSION_QUERY_TIMEOUT' and time.monotonic()-started < 2.7,'owner authorization lock shares overall deadline')
            lock.result(timeout=8)
        task=self.task('S09 public needle53 Bearer SYNTHETIC_TOKEN /private/goal next',
            [(SEARCH,{'query':'SYNTHETIC_TOKEN /private/query next'}),(SEARCH,{'query':'HIDDEN_SEARCH_REASONING_53'})])
        worker=self.start('redaction-search-worker','worker'); self.state(task,'SUCCEEDED'); self.stop(worker)
        for seq in (0,2):
            value=self.event(task,seq)['target']; raw=json.dumps(value)
            self.assert_that(value['redacted'] and all(secret not in raw for secret in ('SYNTHETIC_TOKEN','/private/','HIDDEN_SEARCH_REASONING_53')),
                'content and only search query capture public projection',seq=seq)
        self.assert_that(all(value['items'] == [] for value in self.outputs(task)),'search does not match raw parameters or hidden reasoning')
        private=self.sql("SELECT input_snapshot FROM invocation WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;")
        self.assert_that('SYNTHETIC_TOKEN' in private, 'original recovery arguments preserved separately')

    def retention(self):
        task=self.primary_task
        before=int(self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
        second=self.start('search-competing-cleanup','control')
        self.sql("UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+task+"';")
        self.search(task,{'query':'needle53'},status=410)
        eventually(lambda: self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+task+"';") == 'PURGED','v2 safely purged')
        metadata=int(self.sql("SELECT metadata_bytes FROM task_session_history WHERE task_id='"+task+"';"))
        after=int(self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
        self.assert_that(before-after == 8388608-metadata,'exact storage release with retained search metadata',before=before,after=after,metadata=metadata)
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation_session_search WHERE task_id='"+task+"' AND (normalized_query_snapshot IS NOT NULL OR content_state<>'PURGED');") == '0','all normalized query copies purged')
        self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='session_history.content_deleted';") == '1','one cleanup receipt under dual controls')
        receipt=self.sql("SELECT cleanup_receipt,held_bytes,metadata_bytes,body_bytes FROM task_session_history WHERE task_id='"+task+"';")
        time.sleep(3)
        self.assert_that(receipt == self.sql("SELECT cleanup_receipt,held_bytes,metadata_bytes,body_bytes FROM task_session_history WHERE task_id='"+task+"';")
            and after == int(self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")), 'repeat cleanup cannot release twice')
        self.stop(second); self.stop(self.control)
        self.ledger_scenario(self.accounting_worker,task,'accounting')

    def cleanup(self):
        try:
            for child in reversed(self.children): self.stop({'process':child,'name':'search foreground collection'})
            if self.schema_ready:
                (self.evidence/'invocation_session_search.tsv').write_text(self.sql('SELECT * FROM invocation_session_search;'))
        finally: super().cleanup()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser=argparse.ArgumentParser()
    parser.add_argument('--jar',type=Path,required=True); parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--case',choices=['normal','lifecycle','core','recovery','boundaries','integrity','retention','all'],default='core')
    args=parser.parse_args(); experiment=SearchExperiment(args.jar,args.evidence)
    experiment.takeover=args.case == 'lifecycle'
    try:
        experiment.prepare(); experiment.normal()
        if args.case in ('core','all'): experiment.inputs()
        if args.case in ('recovery','all'): experiment.recovery()
        if args.case in ('boundaries','all'): experiment.boundaries()
        if args.case in ('integrity','all'): experiment.integrity()
        if args.case in ('retention','all'): experiment.retention()
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc()); raise
    finally: experiment.cleanup()
