"""Foreground ADR-0013 fixture: private MySQL, real control/workers and paid=false HTTP model."""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import traceback
import concurrent.futures
import socket
import time

from p2_verify import Handler, eventually
from session_event_verify import SessionExperiment

READ, SEARCH, TRACE = 'session_event_read', 'session_event_search', 'session_event_trace'


class Model(Handler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        context = json.loads(body['messages'][-1]['content'])
        assert context['version'] == 3
        assert [t['function']['name'] for t in body['tools']] == [READ, SEARCH, TRACE]
        replies = [json.loads(m['content']) for m in body['messages'] if m['role'] == 'tool']
        plan = self.server.plans[goal]
        at = len(replies)
        batch = plan[at] if at < len(plan) else None
        message = {'role': 'assistant', 'content': 'public marker54 trace receipt.'}
        if batch is not None:
            calls = []
            for i, (name, args) in enumerate(batch if isinstance(batch, list) else [batch]):
                if isinstance(args, dict):
                    args = dict(args)
                    if args.get('seq') == '$receipt':
                        args['seq'] = int(self.server.receipt) if hasattr(self.server, 'receipt') else 3
                    if args.get('seq') == '$outside': args['seq'] = context['upper_seq'] + 1
                    if args.get('session_id') == '$current': args['session_id'] = context['session_id']
                    args = json.dumps(args)
                calls.append({'id': 'slot_' + str(at+i), 'type': 'function', 'function': {'name': name, 'arguments': args}})
            message['tool_calls'] = calls
        message['reasoning_content'] = 'HIDDEN_TRACE_REASONING /private/trace'
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({'goal': goal, 'body': body, 'replies': replies, 'context': context})
        self.send(200, {'id': 'local-trace-v3', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if batch is not None else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


class TraceExperiment(SessionExperiment):
    def prepare(self):
        super().prepare()
        self.stop(self.control)
        self.fixture.RequestHandlerClass = Model
        for table in ('invocation_session_search', 'invocation_session_trace'):
            self.sql("GRANT INSERT,UPDATE ON jimu." + table + " TO 'runtime'@'%';")
        root = Path(__file__).resolve().parents[1] / 'src/main/resources/session/v3'
        for name, version in ((READ, '0.3.0'), (SEARCH, '0.2.0'), (TRACE, '0.1.0')):
            digest = hashlib.sha256((root / (name+'-'+version) / 'output.schema.json').read_bytes()).hexdigest()
            for tenant in ('tenant-a', 'tenant-b'):
                self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('"+tenant+"','"+name+"','"+version+"','"+digest+"') ON DUPLICATE KEY UPDATE plugin_version=VALUES(plugin_version),plugin_digest=VALUES(plugin_digest);")
        self.common.update({'jimu.session-event-search.enabled': 'true', 'jimu.session-event-trace.enabled': 'true',
                            'jimu.model.profiles.a.input-limit.max-tokens': '65536'})
        self.control = self.start('control-trace-v3', 'control')
        self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;') == '21', 'V21 installed')
        self.assert_that(self.http('GET', '/api/capabilities')[1] == [READ, SEARCH, TRACE, 'web_fetch'], 'three pinned tools discovered')
        (self.evidence/'show-create-read.tsv').write_text(self.sql('SHOW CREATE TABLE invocation_session_read;'))
        (self.evidence/'show-create-trace.tsv').write_text(self.sql('SHOW CREATE TABLE invocation_session_trace;'))

    def task(self, goal, plan, subject='a', **fields):
        self.fixture.plans[goal] = plan
        status, value = self.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': [READ, SEARCH, TRACE], **fields},
            key=hashlib.sha256(goal.encode()).hexdigest(), subject=subject)
        self.assert_that(status == 202, 'accept '+goal, status=status, value=value)
        self.task_subjects[value['taskId']] = subject
        return value['taskId']

    def trace(self, task, args, subject='a', status=200):
        actual, value = self.http('POST', '/api/tasks/'+task+'/session-history/trace', args, subject=subject)
        self.assert_that(actual == status, 'owner trace', expected=status, actual=actual, value=value)
        return value

    def raw(self, task, raw, subject='a'):
        connection = http.client.HTTPConnection('127.0.0.1', self.control['port'], timeout=5)
        try:
            connection.request('POST', '/api/tasks/'+task+'/session-history/trace', raw,
                {'Authorization': 'Bearer '+self.token(subject), 'Content-Type': 'application/json'})
            response = connection.getresponse()
            return response.status, json.loads(response.read()), dict(response.getheaders())
        finally: connection.close()

    def normal(self):
        task = self.task('T01 needle54 Unicode 🧱', [(SEARCH, {'query': 'needle54'}), (READ, {'seq': 0}),
            (TRACE, {'seq': 0}), (READ, {'seq': 2, 'before': 2, 'after': 4}), (TRACE, {'seq': 0})])
        workers = [self.start('trace-worker-'+str(i), 'worker') for i in range(2)]
        self.state(task, 'SUCCEEDED')
        for worker in workers: self.stop(worker)
        outputs = self.outputs(task)
        native = [json.loads(line) for line in self.sql("SELECT event_snapshot FROM session_public_event WHERE task_id='"+task+"' ORDER BY seq;").splitlines()]
        receipts = [e for e in native if e['type'] == 'session.read']
        self.assert_that(outputs[2]['derived_event_seqs'] == [receipts[0]['seq']], 'T01 actual read receipt referenced by trace')
        self.assert_that(outputs[4]['derived_event_seqs'] == [e['seq'] for e in receipts[:2]], 'T02 multiple direct reverse receipts ascending')
        self.assert_that(outputs[2]['replacement_policy'] == 'unsupported' and outputs[2]['source_event_seqs'] == [], 'T02 explicit unsupported and no guessed sources')
        for receipt in receipts:
            proof = json.loads(self.sql("SELECT read_sources_snapshot FROM invocation_session_read WHERE invocation_id='"+receipt['source']['invocation_id']+"';"))
            self.assert_that(receipt['source_event_seqs'] == [p['seq'] for p in proof], 'T02 captured seq projection exact')
            for p in proof:
                digest = self.sql("SELECT event_digest FROM session_public_event WHERE task_id='"+task+"' AND seq="+str(p['seq'])+';')
                self.assert_that(p['event_sha256'] == digest, 'T01 independent read source digest')
        receipt = receipts[1]
        value = self.trace(task, {'seq': receipt['seq']})
        self.assert_that(value['source_event_seqs'] == receipt['source_event_seqs'] and len(value['source_event_seqs']) == 7, 'T02 actual clipped window')
        self.assert_that(self.trace(task, {'seq': 1})['target']['text'] == '', 'T02 structural summary empty')
        before = self.sql("SELECT state,models_used,tools_used,next_event_seq FROM task WHERE id='"+task+"';")
        self.trace(task, {'seq': 0}); self.event(task, 0)
        self.assert_that(before == self.sql("SELECT state,models_used,tools_used,next_event_seq FROM task WHERE id='"+task+"';"), 'T01 owner queries leave task unchanged')
        self.assert_that('HIDDEN_TRACE_REASONING' not in json.dumps(native) and '/private/trace' not in json.dumps(native), 'T08 hidden reasoning absent')
        (self.evidence/'normal-protocol.json').write_text(json.dumps(self.fixture.requests, indent=2))
        (self.evidence/'normal-native.json').write_text(json.dumps(native, indent=2))
        (self.evidence/'explain.tsv').write_text(self.sql("EXPLAIN SELECT * FROM session_public_event FORCE INDEX (PRIMARY) WHERE tenant_id='tenant-a' AND task_id='"+task+"' AND seq BETWEEN 0 AND 10 ORDER BY seq LIMIT 128;"))
        self.normal_task = task
        self.accounting_worker = workers[0]

    def inputs(self):
        task = self.normal_task
        invalid = [b'{}', b'{"seq":-0}', b'{"seq":1.0}', b'{"seq":1e0}', b'{"seq":9007199254740992}', b'{"seq":0,"seq":1}',
            b'{"seq":0,"depth":1}', b'{"seq":"0"}', b'{"seq":0}{}', b'{"seq":0,"session_id":"\\uD800"}', b'\xc3\x28']
        for raw in invalid:
            status, value, headers = self.raw(task, raw)
            self.assert_that(status == 400 and value['code'] == 'TOOL_INPUT_INVALID', 'T06 strict raw input', raw=raw.hex(), status=status)
            self.assert_that(headers.get('Cache-Control') == 'no-store', 'T06 no-store')
        raw = b'{"seq":0}'
        self.assert_that(self.raw(task, raw+b' '*(1024-len(raw)))[0] == 200, 'T06 exact 1024')
        self.assert_that(self.raw(task, raw+b' '*(1025-len(raw)))[0] == 400, 'T06 1025 refused')
        self.trace(task, {'seq': 9007199254740991}, status=404)
        self.trace(task, {'seq': 0}, subject='b', status=404)
        self.trace(task, {'seq': 0}, subject='c', status=404)
        self.trace(task, {'seq': 0, 'session_id': '00000000-0000-4000-8000-000000000000'}, status=404)
        self.assert_that(self.raw(task, b'bad', 'b')[0] == 404, 'T05 authorization before body errors')

    def integrity(self):
        task = self.normal_task
        receipt = self.sql("SELECT source_invocation_id,seq FROM session_public_event WHERE task_id='"+task+"' AND type='session.read' ORDER BY seq LIMIT 1;").split('\t')
        original = self.sql("SELECT read_sources_snapshot,read_sources_digest,read_sources_bytes FROM invocation_session_read WHERE invocation_id='"+receipt[0]+"';").split('\t')
        for proof in ([{'seq': int(receipt[1]), 'event_sha256': '0'*64}], [{'seq': 0, 'event_sha256': '0'*64}],
                      [{'seq': 0, 'event_sha256': '0'*64}, {'seq': 0, 'event_sha256': '0'*64}]):
            raw = json.dumps(proof, separators=(',', ':'))
            self.sql("UPDATE invocation_session_read SET read_sources_snapshot=CONVERT(UNHEX('"+raw.encode().hex()+"') USING utf8mb4),read_sources_digest='"+hashlib.sha256(raw.encode()).hexdigest()+"',read_sources_bytes="+str(len(raw))+" WHERE invocation_id='"+receipt[0]+"';")
            try:
                self.assert_that(self.trace(task, {'seq': 0}, status=409)['code'] == 'SESSION_HISTORY_CORRUPT', 'T05 corrupt self/future/digest/duplicate source refused')
            finally:
                self.sql("UPDATE invocation_session_read SET read_sources_snapshot=CONVERT(UNHEX('"+original[0].encode().hex()+"') USING utf8mb4),read_sources_digest='"+original[1]+"',read_sources_bytes="+original[2]+" WHERE invocation_id='"+receipt[0]+"';")
        for field in ('event_digest', 'source_digest'):
            original = self.sql("SELECT "+field+" FROM session_public_event WHERE task_id='"+task+"' AND seq=0;")
            self.sql("UPDATE session_public_event SET "+field+"='"+'f'*64+"' WHERE task_id='"+task+"' AND seq=0;")
            try: self.trace(task, {'seq': 1}, status=409)
            finally: self.sql("UPDATE session_public_event SET "+field+"='"+original+"' WHERE task_id='"+task+"' AND seq=0;")
        self.sql("UPDATE session_public_event SET seq=127 WHERE task_id='"+task+"' AND seq=0;")
        try: self.trace(task, {'seq': 1}, status=409)
        finally: self.sql("UPDATE session_public_event SET seq=0 WHERE task_id='"+task+"' AND seq=127;")
        self.sql('RENAME TABLE session_public_event TO trace_provider_unavailable;')
        try: self.assert_that(self.trace(task, {'seq': 0}, status=503)['code'] == 'SESSION_QUERY_UNAVAILABLE', 'T07 real provider failure no fallback')
        finally: self.sql('RENAME TABLE trace_provider_unavailable TO session_public_event;')
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            locked = pool.submit(self.sql, "START TRANSACTION; SELECT id FROM task WHERE id='"+task+"' FOR UPDATE; DO SLEEP(4); ROLLBACK;")
            eventually(lambda: bool(self.sql("SELECT trx_id FROM information_schema.innodb_trx WHERE trx_query='DO SLEEP(4)';")), 'task lock held')
            started=time.monotonic()
            self.trace(task, {'seq': 0}, status=504)
            self.assert_that(time.monotonic()-started<2.7, 'T06 authorization lock bounded by shared deadline')
            locked.result(timeout=8)
        sock=socket.create_connection(('127.0.0.1', self.control['port']),timeout=4)
        started=time.monotonic()
        try:
            sock.sendall(('POST /api/tasks/'+task+'/session-history/trace HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer '+self.token('a')+'\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{').encode())
            data=b''
            while b'SESSION_QUERY_TIMEOUT' not in data:
                part=sock.recv(65536)
                if not part: break
                data+=part
            self.assert_that(b'504' in data and b'SESSION_QUERY_TIMEOUT' in data and time.monotonic()-started<2.7, 'T06 slow body absolute deadline')
        finally: sock.close()

    def boundaries(self):
        task=self.task('T03 same response prefix', [[(READ, {'seq': 0}), (TRACE, {'seq': 0}), (SEARCH, {'query': 'prefix'})],
            None, None, (TRACE, {'seq': 0})])
        worker=self.start('same-response-worker','worker'); self.state(task,'SUCCEEDED'); self.stop(worker)
        outputs=self.outputs(task)
        self.assert_that(outputs[0]['upper_seq']==outputs[1]['upper_seq']==outputs[2]['upper_seq']==0 and outputs[1]['derived_event_seqs']==[], 'T03 same response cannot see prior slot receipt')
        self.assert_that(len(outputs[3]['derived_event_seqs'])==1, 'T03 next step sees committed receipt')
        task=self.task('T02 full nine nodes', [(READ, {'seq': 0}), (READ, {'seq': 0}), (READ, {'seq': 0}), (READ, {'seq': 4, 'before': 4, 'after': 4})])
        worker=self.start('nine-source-worker','worker'); self.state(task,'SUCCEEDED'); self.stop(worker)
        self.assert_that(len(self.trace(task, {'seq': 12})['source_event_seqs'])==9, 'T02 actual nine node window')
        task=self.task('T02 failed read no relationship', [(READ, {'seq': 9007199254740991})])
        worker=self.start('failed-read-worker','worker'); self.state(task,'FAILED'); self.stop(worker)
        self.assert_that(self.sql("SELECT read_sources_snapshot FROM invocation_session_read WHERE task_id='"+task+"';")=='[]' and self.trace(task,{'seq':0})['derived_event_seqs']==[], 'T02 failed read captures no edges')
        self.failed_read_task=task
        worker=self.start('strict-trace-model','worker')
        try:
            for index,raw in enumerate(('{"seq":-0}','{"seq":0,"seq":1}','{"seq":0,"unknown":1}', '{"seq":0,"session_id":"'+self.normal_task+'"}')):
                invalid=self.task('T06 invalid trace model '+str(index),[(TRACE,raw)])
                view=self.state(invalid,'FAILED')
                self.assert_that(view['code'] in ('TOOL_INPUT_INVALID','SESSION_EVENT_NOT_FOUND') and self.sql("SELECT tools_used FROM task WHERE id='"+invalid+"';")=='0' and self.sql("SELECT total_tokens FROM model_dispatch WHERE task_id='"+invalid+"';")=='150','T06 rejected model input settles usage without tool consumption')
            goal='T08 public trace Bearer SYNTHETIC_TOKEN /private/trace next'
            redacted=self.task(goal,[(READ,{'seq':0}),(TRACE,{'seq':0})]); self.state(redacted,'SUCCEEDED')
            value=self.trace(redacted,{'seq':0})
            self.assert_that(value['target']['redacted'] and 'SYNTHETIC_TOKEN' not in json.dumps(value) and '/private/' not in json.dumps(value),'T08 trace summarizes captured public projection only')
            summary=self.task('T07 '+('🧱'*300),[(TRACE,{'seq':0})]); self.state(summary,'SUCCEEDED')
            value=self.trace(summary,{'seq':0})
            self.assert_that(len(value['target']['text'])==256 and len(value['target']['text'].encode())<=1024 and value['target']['summary_truncated'],'T07 deterministic Unicode summary bound')
        finally: self.stop(worker)

    def recovery(self):
        for point in ('M1','M2','SESSION_TRACE_COMMIT_BEFORE','SESSION_TRACE_COMMIT_AFTER'):
            task=self.task('T04 recover '+point, [(TRACE,{'seq':0}),(READ,{'seq':0}),(TRACE,{'seq':0})])
            worker=self.start('before-'+point,'worker',point); self.barrier(worker)
            original=self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;")
            if point=='SESSION_TRACE_COMMIT_AFTER': self.ledger_scenario(worker,task,'replay')
            self.stop(worker,kill=True); self.expire(task)
            resumed=self.start('after-'+point,'worker'); self.state(task,'SUCCEEDED'); self.stop(resumed)
            outputs=self.outputs(task)
            self.assert_that(original==self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE task_id='"+task+"' ORDER BY ordinal LIMIT 1;"),'T04 immutable M1 boundary '+point)
            self.assert_that(outputs[0]['derived_event_seqs']==[] and len(outputs[2]['derived_event_seqs'])==1,'T03 old trace remains original after real later read '+point)
            self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='3' and self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='tool.completed';")=='3','T04 one charge/receipt per original slot '+point)
        for point in ('before','after'):
            task=self.task('T04 uncertain '+point,[(TRACE,{'seq':0})])
            worker=self.start('uncertain-'+point,'worker','SESSION_TRACE_COMMIT_BEFORE'); self.barrier(worker)
            self.ledger_scenario(worker,task,'uncertain-'+point)
            self.stop(worker,kill=True); self.expire(task)
            resumed=self.start('uncertain-resume-'+point,'worker'); self.state(task,'SUCCEEDED'); self.stop(resumed)
            self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='1','T04 lost acknowledgement settles once '+point)
        task=self.task('T04 failed bound trace',[(TRACE,{'seq':0})])
        worker=self.start('trace-failure-before','worker','M2'); self.barrier(worker)
        self.ledger_scenario(worker,task,'trace-failed-replay')
        self.stop(worker,kill=True); self.expire(task)
        resumed=self.start('trace-failure-after','worker'); self.state(task,'FAILED'); self.stop(resumed)
        for mode in ('cancel','deadline'):
            task=self.task('T04 '+mode,[(TRACE,{'seq':0})])
            worker=self.start('trace-'+mode,'worker','SESSION_TRACE_COMMIT_BEFORE'); self.barrier(worker)
            if mode=='cancel': self.http('POST','/api/tasks/'+task+'/cancel')
            else: self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
            (worker['folder']/'release').write_text('continue')
            self.state(task,'CANCELLED' if mode=='cancel' else 'FAILED'); self.stop(worker)
            self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='0','T04 '+mode+' prevents trace commit')

    def retention(self):
        task=self.normal_task
        self.ledger_scenario(self.accounting_worker,task,'accounting')
        self.ledger_scenario(self.accounting_worker,task,'purge-rollback')
        blocked=self.failed_read_task
        self.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a','"+blocked+":unknown','"+blocked+"','MODEL',1,'HELD_UNKNOWN','UNKNOWN');")
        self.sql("UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+blocked+"';")
        self.stop(self.control)
        self.control=self.start('trace-flagoff-control','control',extra={'jimu.session-event-trace.enabled':'false'})
        self.trace(task,{'seq':0})
        status,value=self.http('POST','/api/tasks',{'goal':'trace-disabled','capabilityIds':[READ,SEARCH,TRACE]},key='trace-disabled')
        self.assert_that(status==403 and value['code']=='SESSION_TRACE_DISABLED','T04 flagoff rejects model/new tasks, retained owner trace works')
        second=self.start('trace-second-cleanup','control',extra={'jimu.session-event-trace.enabled':'false'})
        before=int(self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
        self.sql("UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+task+"';")
        self.trace(task,{'seq':0},status=410)
        eventually(lambda:self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+task+"';")=='PURGED','v3 safely purged')
        metadata=int(self.sql("SELECT metadata_bytes FROM task_session_history WHERE task_id='"+task+"';"))
        after=int(self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
        self.assert_that(before-after==8388608-metadata,'T10 exact retained metadata charge/release')
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation_session_read WHERE task_id='"+task+"' AND (read_sources_snapshot IS NOT NULL OR content_state<>'PURGED');")=='0','T10 all read source bodies purged')
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation_session_trace WHERE task_id='"+task+"' AND content_state<>'PURGED';")=='0','T10 trace tombstones purged')
        receipt=self.sql("SELECT cleanup_receipt,held_bytes,metadata_bytes,body_bytes FROM task_session_history WHERE task_id='"+task+"';")
        self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='session_history.content_deleted';")=='1','T10 one deletion under dual controls')
        self.assert_that(self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+blocked+"';")=='PRESENT' and self.sql("SELECT read_sources_snapshot FROM invocation_session_read WHERE task_id='"+blocked+"';")=='[]','T10 UNKNOWN prevents source body release')
        time.sleep(3)
        self.assert_that(receipt==self.sql("SELECT cleanup_receipt,held_bytes,metadata_bytes,body_bytes FROM task_session_history WHERE task_id='"+task+"';") and after==int(self.sql("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")),'T10 repeated cleanup no second release')
        self.stop(second); self.stop(self.control)
        self.ledger_scenario(self.accounting_worker,task,'accounting')

    def cleanup(self):
        try:
            for child in reversed(self.children): self.stop({'process':child,'name':'trace foreground collection'})
            if self.schema_ready:
                for table in ('invocation_session_trace','invocation_session_search'):
                    (self.evidence/(table+'.tsv')).write_text(self.sql('SELECT * FROM '+table+';'))
        finally: super().cleanup()

    def observations(self):
        task=self.task('T01 SSE real trace observation',[(READ,{'seq':0}),(TRACE,{'seq':0})])
        worker=self.start('observation-worker','worker'); self.state(task,'SUCCEEDED'); self.stop(worker)
        status,sse=self.http('GET','/api/tasks/'+task+'/events')
        rows=[json.loads(line[5:]) for line in sse.splitlines() if line.startswith('data:')]
        self.assert_that(status==200 and [row['seq'] for row in rows]==list(range(1,len(rows)+1)),'T01 real v3 SSE ordered committed replay')
        last=rows[-3:]
        status,resumed=self.http('GET','/api/tasks/'+task+'/events',headers={'Last-Event-ID':rows[-4]['eventId']})
        self.assert_that(status==200 and [json.loads(line[5:]) for line in resumed.splitlines() if line.startswith('data:')]==last,'T01 SSE reconnect resumes exact suffix')
        self.assert_that('HIDDEN_TRACE_REASONING' not in sse and '/private/trace' not in sse,'T08 safe SSE projection')
        (self.evidence/'trace.sse').write_text(sse)
        upper=self.metadata(task)['upper_seq']
        plan=self.sql("EXPLAIN ANALYZE SELECT * FROM session_public_event FORCE INDEX (PRIMARY) WHERE tenant_id='tenant-a' AND task_id='"+task+"' AND seq BETWEEN 0 AND "+str(upper)+" ORDER BY seq LIMIT 128;")
        self.assert_that('using PRIMARY' in plan and 'rows='+str(upper+1)+' loops=1' in plan,'T07 actual bounded PK range rows examined')
        (self.evidence/'trace-explain-analyze.tsv').write_text(plan)
        self.stop(self.control); self.control=self.start('observation-reconnected','control')
        output=self.trace(task,{'seq':0})
        self.assert_that(output['derived_event_seqs']==[3],'T01 reconnect retains actual read relationship')

    def legacy_v2(self):
        root=Path(__file__).resolve().parents[1]/'src/main/resources/session/v2'
        for name,version in ((READ,'0.2.0'),(SEARCH,'0.1.0')):
            digest=hashlib.sha256((root/(name+'-'+version)/'output.schema.json').read_bytes()).hexdigest()
            self.sql("UPDATE installation SET plugin_version='"+version+"',plugin_digest='"+digest+"' WHERE tenant_id='tenant-a' AND plugin_id='"+name+"';")
        status,value=self.http('POST','/api/tasks',{'goal':'T09 legacy v2 trace unavailable','capabilityIds':[READ,SEARCH]},key='legacy-v2-trace')
        self.assert_that(status==202,'T09 explicit legacy v2 task accepted')
        task=value['taskId']
        self.assert_that(self.metadata(task)['version']==2,'T09 retained v2 wire')
        value=self.trace(task,{'seq':0},status=409)
        self.assert_that(value['code']=='SESSION_TRACE_UNAVAILABLE','T09 v2 trace unavailable without invented sources')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', required=True)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--case',choices=['all','observations','legacy-v2'],default='all')
    args = parser.parse_args()
    evidence = Path(args.evidence).resolve(); evidence.mkdir(parents=True, exist_ok=True)
    experiment = TraceExperiment(Path(args.jar), evidence)
    try:
        experiment.prepare()
        if args.case=='observations': experiment.observations()
        elif args.case=='legacy-v2': experiment.legacy_v2()
        else:
            experiment.normal(); experiment.inputs(); experiment.integrity(); experiment.boundaries(); experiment.recovery(); experiment.retention()
    except Exception:
        (evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()


if __name__ == '__main__': main()
