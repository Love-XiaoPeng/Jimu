"""XIAO-55 foreground acceptance with private MySQL, real dual workers and paid=false HTTP."""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import signal
import os
import shutil
import subprocess
import traceback
import time

from p2_verify import Handler, eventually
from session_event_verify import SessionExperiment


class Model(Handler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        context = json.loads(body['messages'][-1]['content'])
        assert context['version'] == 4
        assert {t['function']['name'] for t in body['tools']} == {'session_event_read', 'session_search'}
        replies = [json.loads(m['content']) for m in body['messages'] if m['role'] == 'tool']
        message = {'role': 'assistant', 'content': 'Verified public nebula evidence.'}
        if goal.startswith('caller multislot') and not replies:
            prior=context['prior_sessions'][0]['session_id']
            slots=[('session_search',{'query':'nebula'}),('session_event_read',{'seq':0}),('session_search',{'query':'caller multislot'}),('session_event_read',{'session_id':prior,'seq':0})]
            message['tool_calls']=[{'id':'multi_'+str(i),'type':'function','function':{'name':name,'arguments':json.dumps(arguments)}} for i,(name,arguments) in enumerate(slots)]
        elif goal.startswith('caller multislot'):
            assert len(replies)==4 and replies[1]['session_id']==context['session_id'] and replies[1]['upper_seq']==0 and replies[2]['items']==[]
        elif goal.startswith('caller') and not replies:
            message['tool_calls'] = [{'id': 'search_0', 'type': 'function', 'function': {'name': 'session_search', 'arguments': json.dumps({'query': 'nebula'})}}]
        elif goal.startswith('caller') and len(replies) == 1:
            hit = replies[0]['items'][0]
            message['tool_calls'] = [{'id': 'read_1', 'type': 'function', 'function': {'name': 'session_event_read', 'arguments': json.dumps({'session_id': hit['session_id'], 'seq': hit['best_match']['seq'], 'before': 1, 'after': 2})}}]
        elif len(replies) == 2:
            hit = replies[0]['items'][0]
            read = replies[1]
            assert read['session_id'] == hit['session_id']
            raw = json.dumps(read['target'], ensure_ascii=False, separators=(',', ':')).encode()
            assert hashlib.sha256(raw).hexdigest() == hit['best_match']['event_sha256']
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({'goal': goal, 'body': body, 'context': context, 'replies': replies})
        message['reasoning_content'] = 'HIDDEN_REASONING_SENTINEL_55 /private/reasoning'
        self.send(200, {'id': 'prior-session-fixture', 'choices': [{'index': 0, 'message': message, 'finish_reason': 'tool_calls' if 'tool_calls' in message else 'stop'}], 'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


class PriorExperiment(SessionExperiment):
    def prepare(self):
        self.input_jar = self.jar
        self.input_sha256 = hashlib.sha256(self.input_jar.read_bytes()).hexdigest()
        if getattr(self,'v21',None):
            self.jar = self.v21
        super().prepare()
        if getattr(self,'v21',None):
            self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=TRUE;')=='21','old exact-master binary creates actual V21 schema')
            old_checksums=self.sql('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;')
            self.stop(self.control)
            shutil.copyfile(self.input_jar,self.jar)
            self.assert_that(hashlib.sha256(self.jar.read_bytes()).hexdigest()==self.input_sha256,'upgraded private JAR matches immutable input',jarSha256=self.input_sha256)
            self.control=self.start('control-upgrade-v22','control')
            self.assert_that(self.sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=TRUE;')=='22','new binary migrates actual V21 to22')
            self.assert_that(self.sql('SELECT version,checksum,success FROM flyway_schema_history WHERE CAST(version AS UNSIGNED)<=21 ORDER BY installed_rank;')==old_checksums,'V1 through21 migration checksums preserved')
            for role in ('control','worker'):
                folder=self.root/('old-v21-'+role);folder.mkdir()
                config=(self.control['folder']/'runtime.properties').read_text().replace('jimu.role=control','jimu.role='+role)
                properties=folder/'runtime.properties';properties.write_text(config)
                result=subprocess.run([self.java,'-jar',str(self.v21),'--spring.config.additional-location=file:'+str(properties)],text=True,capture_output=True,timeout=35)
                (self.evidence/('old-v21-'+role+'.log')).write_text(result.stdout+result.stderr)
                self.assert_that(result.returncode!=0 and 'Unsupported schema version' in result.stdout+result.stderr,'old '+role+' refuses schema22 before readiness',exit=result.returncode)
        self.fixture.RequestHandlerClass = Model
        for table in ('invocation_session_search', 'invocation_session_trace', 'task_session_scope', 'task_session_scope_target', 'step_session_scope', 'invocation_session_scope_query'):
            self.sql("GRANT INSERT,UPDATE ON jimu.`"+table+"` TO 'runtime'@'%';")
        root = Path(__file__).resolve().parents[1]/'src/main/resources/session/v4'
        for tenant in ('tenant-a', 'tenant-b'):
            read = hashlib.sha256((root/'session_event_read-0.4.0/output.schema.json').read_bytes()).hexdigest()
            search = hashlib.sha256((root/'session_search-0.1.0/output.schema.json').read_bytes()).hexdigest()
            self.sql("UPDATE installation SET plugin_version='0.4.0',plugin_digest='"+read+"',epoch=epoch+1 WHERE tenant_id='"+tenant+"' AND plugin_id='session_event_read'; INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('"+tenant+"','session_search','0.1.0','"+search+"');")
        status, value = self.http('POST', '/api/session-search/tasks', {'goal': 'disabled prior', 'history_session_ids': []}, key='disabled-prior')
        self.assert_that(status == 403 and value['code'] == 'SESSION_SEARCH_DISABLED', 'v4 default flag rejects admission', status=status, value=value)
        self.stop(self.control)
        self.common['jimu.session-search.enabled'] = 'true'
        self.common['jimu.model.profiles.a.input-limit.max-tokens'] = '65536'
        self.control = self.start('control-prior', 'control')

    def cleanup(self):
        if self.schema_ready:
            for table in ('task_session_scope', 'task_session_scope_target', 'step_session_scope', 'invocation_session_scope_query'):
                try: (self.evidence/(table+'.tsv')).write_text(self.sql('SELECT * FROM '+table+' ORDER BY tenant_id;'))
                except Exception as failure: (self.evidence/(table+'-capture-error.txt')).write_text(type(failure).__name__)
        super().cleanup()
        self.assert_that(hashlib.sha256(self.input_jar.read_bytes()).hexdigest()==self.input_sha256,'input artifact unchanged by fixture',jarSha256=self.input_sha256)

    def scenario(self, worker, task, mode):
        classes=self.root/'prior-test-classes'
        if classes.exists(): shutil.rmtree(classes)
        shutil.copytree(Path(__file__).resolve().parents[1]/'target/test-classes',classes)
        result=subprocess.run([self.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.PriorSessionLedgerScenario','-Dloader.path='+str(classes),'-cp',str(self.jar),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task,mode],text=True,capture_output=True,timeout=40)
        (self.evidence/('scenario-'+task+'-'+mode+'.log')).write_text(result.stdout+result.stderr)
        self.assert_that(result.returncode==0 and 'PASS' in result.stdout,'real ledger '+mode,exit=result.returncode,output=result.stdout[-3000:])

    def prior_task(self, goal, sources=(), subject='a', expected=202, key=None):
        status, value = self.http('POST', '/api/session-search/tasks', {'goal': goal, 'history_session_ids': list(sources)}, key=key or hashlib.sha256(goal.encode()).hexdigest(), subject=subject)
        self.assert_that(status == expected, 'admit '+goal, status=status, value=value)
        if status == 202:
            self.task_subjects[value['taskId']] = subject
            return value['taskId']
        return value

    def search(self, task, value, expected=200, subject='a'):
        status, output = self.http('POST', '/api/tasks/'+task+'/session-history/session-search', value, subject=subject)
        self.assert_that(status == expected, 'owner prior search', status=status, value=output)
        return output

    def quiet(self, task):
        eventually(lambda: self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"' AND quiescent=FALSE;") == '0', 'source writer quiescent')

    def inputs(self, caller, source):
        path='/api/tasks/'+caller+'/session-history/session-search'
        def raw(body,subject='a',suffix='',content_type='application/json'):
            connection=http.client.HTTPConnection('127.0.0.1',self.control['port'],timeout=5)
            try:
                connection.request('POST',path+suffix,body,{'Authorization':'Bearer '+self.token(subject),'Content-Type':content_type})
                response=connection.getresponse()
                return response.status,json.loads(response.read())
            finally: connection.close()
        for body in (b'{"query":"a","query":"b"}',b'{"query":"a","event_seq_from":-0}',b'{"query":"a","event_seq_to":1.0}',b'{"query":"a","event_seq_from":1e0}',b'{"query":"a","event_seq_to":9007199254740992}',b'{"query":"a","x":1}',b'{"query":"\\ud800"}',b'{"query":"\xc0\xaf"}',b'{"query":"a"} {}',b'{"query":"a","event_types":[]}',b'{"query":"a","event_time_from":"2026-02-29T00:00:00Z"}',b'{"query":"a","event_seq_from":2,"event_seq_to":1}',b'{"query":"'+b'a'*257+b'"}',b' '*4097):
            status,value=raw(body)
            self.assert_that(status==400 and value['code']=='TOOL_INPUT_INVALID','strict v4 HTTP input',rawSha256=hashlib.sha256(body).hexdigest(),status=status,value=value)
        valid=b'{"query":"nebula"}'
        self.assert_that(raw(valid+b' '*(4096-len(valid)))[0]==200,'exact4096 owner input accepted')
        self.assert_that(raw(valid,content_type='text/plain')[0]==400 and raw(valid,suffix='?x=1')[0]==400,'owner JSON transport enforced')
        for subject in ('b','c'):
            self.assert_that(raw(b'{invalid',subject=subject)[0]==404,'owner authorization precedes malformed input '+subject)
        self.assert_that(self.http('DELETE','/api/tasks/'+caller+'/session-search-scope?x=1',subject='c')[0]==404 and self.http('DELETE','/api/tasks/'+caller+'/session-search-scope?x=1')[0]==400,'revoke owner authorization precedes malformed request')
        for endpoint in (path,'/api/session-search/tasks'):
            connection=http.client.HTTPConnection('127.0.0.1',self.control['port'],timeout=5)
            started=time.monotonic()
            try:
                connection.putrequest('POST',endpoint)
                for key,value in {'Authorization':'Bearer '+self.token('a'),'Content-Type':'application/json','Idempotency-Key':'slow-v4-admission','Content-Length':'100'}.items(): connection.putheader(key,value)
                connection.endheaders();connection.send(b'{')
                response=connection.getresponse();value=json.loads(response.read())
                elapsed=time.monotonic()-started
                self.assert_that(response.status==504 and value['code']=='SESSION_QUERY_TIMEOUT' and elapsed<2.7,'v4 overall slow-body cutoff',endpoint=endpoint,elapsedSeconds=elapsed,status=response.status)
            finally: connection.close()
        self.assert_that(self.sql("SELECT COUNT(*) FROM task WHERE idempotency_key='slow-v4-admission';")=='0','timed-out admission leaves no late task')
        event=self.event(source,0)['target']
        filters={'query':'nebula','session_ids':[source],'event_seq_from':0,'event_seq_to':0,'event_time_from':event['time'],'event_time_to':event['time'],'event_types':['user.message','model.response']}
        output=self.search(caller,filters)
        self.assert_that(len(output['items'])==1 and output['items'][0]['best_match']['seq']==0,'inclusive combined microsecond/seq/types/session filters')
        for fields in ({'event_types':['step.started']},{'event_seq_from':9007199254740991},{'query':'NEBULA'},{'query':'.*'}):
            output=self.search(caller,{'query':'nebula',**fields})
            self.assert_that(output['items']==[] and not output['capped'],'literal and filtered true empty')
        original=self.sql("SELECT event_digest FROM session_public_event WHERE task_id='"+source+"' AND seq=0;")
        self.sql("UPDATE session_public_event SET event_digest=REPEAT('f',64) WHERE task_id='"+source+"' AND seq=0;")
        self.assert_that(self.search(caller,{'query':'absent','event_types':['step.started']},409)['code']=='SESSION_HISTORY_CORRUPT','no-match filters still verify full source')
        self.sql("UPDATE session_public_event SET event_digest='"+original+"' WHERE task_id='"+source+"' AND seq=0;")
        self.search(caller,{'query':'nebula'})
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            lock=executor.submit(self.sql,"START TRANSACTION; SELECT id FROM task WHERE id='"+source+"' FOR UPDATE; DO SLEEP(4); ROLLBACK;")
            eventually(lambda:bool(self.sql("SELECT trx_id FROM information_schema.innodb_trx WHERE trx_query='DO SLEEP(4)';")),'prior source privately locked')
            started=time.monotonic()
            self.assert_that(self.search(caller,{'query':'nebula'},504)['code']=='SESSION_QUERY_TIMEOUT' and time.monotonic()-started<2.7,'whole scope source lock uses original absolute deadline')
            lock.result(timeout=8)
        self.search(caller,{'query':'nebula'})
        subprocess.run(['docker','pause',self.cid],check=True,capture_output=True,timeout=20)
        try:
            started=time.monotonic()
            status,value=self.http('POST',path,{'query':'nebula'})
            self.assert_that(status in (503,504) and value['code'] in ('SESSION_QUERY_UNAVAILABLE','SESSION_QUERY_TIMEOUT') and time.monotonic()-started<2.7,'provider loss is bounded error without empty fallback',status=status)
        finally:
            subprocess.run(['docker','unpause',self.cid],check=True,capture_output=True,timeout=20)
            eventually(lambda:self.database_ready(),'private source provider restored')
        self.search(caller,{'query':'nebula'})

    def run(self):
        self.prepare()
        one = self.prior_task('source one nebula nebula')
        two = self.prior_task('source two nebula')
        extra=[self.prior_task('source '+str(i)+' nebula') for i in range(3,9)]
        sources=[one,two,*extra]
        self.prior_task('active source request',[one],expected=404)
        a = self.start('worker-prior-a', 'worker')
        b = self.start('worker-prior-b', 'worker')
        for task in sources:
            self.state(task, 'SUCCEEDED'); self.quiet(task)
            self.assert_that(self.metadata(task)['version'] == 4, 'real independent v4 source', task=task)
        for seq in range(4):
            self.assert_that(self.event(one,seq)['target']['version']==4,'independent source complete v4 public event '+str(seq))
        self.stop(a); self.stop(b)
        caller = self.prior_task('caller verify prior nebula', [two, one])
        self.assert_that(caller == self.prior_task('caller verify prior nebula', [one, two]), 'canonical scope idempotency')
        conflict = self.prior_task('caller verify prior nebula', [], expected=409)
        self.assert_that(conflict['code'] == 'IDEMPOTENCY_CONFLICT', 'idempotency includes immutable scope')
        before = self.sql("SELECT tools_used,models_used,state FROM task WHERE id='"+caller+"';")
        output = self.search(caller, {'query': 'nebula'})
        self.assert_that(output['returned_count'] == 2 and not output['capped'] and {item['session_id'] for item in output['items']} == {one, two}, 'two real prior sessions with one representative each')
        self.assert_that(before == self.sql("SELECT tools_used,models_used,state FROM task WHERE id='"+caller+"';"), 'owner queries do not consume tools/models or mutate business state')
        self.assert_that(self.search(one, {'query': 'nebula'})['items'] == [], 'empty scope does not discover owner history')
        for field in ({'include_root_sessions': False}, {'parent_session_ids': [one]}, {'availability': ['live']}, {'event_surfaces': ['current']}):
            self.assert_that(self.search(caller, {'query': 'nebula', **field}, 400)['code'] == 'SESSION_SEARCH_FILTER_UNSUPPORTED', 'unsupported trusted facts explicit')
        for target in (caller, '00000000-0000-4000-8000-000000000099'):
            self.assert_that(self.search(caller, {'query': 'nebula', 'session_ids': [target]}, 404)['code'] == 'SESSION_SCOPE_UNAVAILABLE', 'input ID does not grant authority')
        self.search(caller, {'query': 'nebula'}, 404, subject='c')
        self.prior_task('foreign owner source request', [one], subject='c', expected=404)
        self.prior_task('foreign tenant source request', [one], subject='b', expected=404)
        self.prior_task('dependent source request', [caller], expected=404)
        self.inputs(caller,one)
        for size in (4,5,8):
            capped=self.prior_task('bounded scope '+str(size),sources[:size])
            value=self.search(capped,{'query':'nebula'})
            self.assert_that(value['returned_count']==4 and value['capped']==(size>4),'real representative cap '+str(size))
            self.http('DELETE','/api/tasks/'+capped+'/session-search-scope')
        status,value=self.http('POST','/api/session-search/tasks',{'goal':'ninth source','history_session_ids':sources+['00000000-0000-4000-8000-000000000099']},key='nine-sources')
        self.assert_that(status==400 and value['code']=='TOOL_INPUT_INVALID','ninth source rejected before discovery')
        (self.evidence/'scope-explain.tsv').write_text(self.sql("EXPLAIN SELECT source_task_id FROM task_session_scope_target FORCE INDEX(PRIMARY) WHERE tenant_id='tenant-a' AND caller_task_id='"+caller+"' ORDER BY ordinal LIMIT 9;"))
        (self.evidence/'event-explain.tsv').write_text(self.sql("EXPLAIN SELECT * FROM session_public_event FORCE INDEX(PRIMARY) WHERE tenant_id='tenant-a' AND task_id='"+one+"' AND seq BETWEEN 0 AND 127 ORDER BY seq LIMIT 128;"))
        a = self.start('worker-loop-a', 'worker')
        b = self.start('worker-loop-b', 'worker')
        self.state(caller, 'SUCCEEDED'); self.quiet(caller)
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+caller+"';") == '2', 'search and independent read each consumed exactly once')
        self.scenario(a,caller,'account')
        raw = self.outputs(caller)
        self.assert_that(len(raw) == 2, 'real model search/read outputs', outputs=raw)
        requests = [r for r in self.fixture.requests if r['goal'].startswith('caller')]
        self.assert_that(len(requests) == 3 and len(requests[-1]['replies']) == 2, 'model consumes prior response before independent read')
        for request in requests:
            serialized = json.dumps(request)
            self.assert_that('HIDDEN_REASONING_SENTINEL_55' not in serialized and '/private/reasoning' not in serialized, 'hidden reasoning absent from subsequent model request')
        self.stop(a); self.stop(b)
        self.stop(self.control)
        self.control = self.start('control-prior-restart', 'control')
        self.assert_that(self.search(caller, {'query': 'nebula'}) == output, 'restart keeps admission candidates and stable bytes')
        self.assert_that(self.metadata(caller)['scope_state'] == 'ACTIVE', 'v4 owner metadata with scope digest')
        status,stream=self.http('GET','/api/tasks/'+caller+'/events')
        envelopes=[json.loads(line[5:]) for line in stream.splitlines() if line.startswith('data:')]
        self.assert_that(status==200 and [e['seq'] for e in envelopes]==list(range(1,len(envelopes)+1)),'v4 reliable SSE ordered')
        cursor=envelopes[len(envelopes)//2]['eventId']
        status,resumed=self.http('GET','/api/tasks/'+caller+'/events',headers={'Last-Event-ID':cursor})
        self.assert_that(status==200 and cursor not in [json.loads(line[5:])['eventId'] for line in resumed.splitlines() if line.startswith('data:')],'v4 SSE reconnect no duplicate')
        status, _ = self.http('DELETE', '/api/tasks/'+caller+'/session-search-scope')
        self.assert_that(status == 204, 'owner revokes immutable scope')
        self.assert_that(self.http('DELETE', '/api/tasks/'+caller+'/session-search-scope')[0] == 204, 'scope revocation idempotent')
        self.search(caller, {'query': 'nebula'}, 404)
        self.metadata(caller, status=404)
        self.event(caller, 0, status=404)
        status, view = self.http('GET', '/api/tasks/'+caller)
        self.assert_that(status == 200 and view['summary'] is None and view['artifacts'] == [], 'scope loss removes summary and artifacts but preserves task state')
        self.assert_that(self.metadata(one)['readable'], 'revoking caller does not delete independent source')
        self.sql("UPDATE task_session_scope SET next_validation_at=CURRENT_TIMESTAMP(6) WHERE caller_task_id='"+caller+"';")
        eventually(lambda: self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+caller+"';")=='PURGED','revoked quiescent caller is cleaned before seven days')
        self.assert_that(self.sql("SELECT body_bytes=0 AND metadata_bytes=held_bytes FROM task_session_history WHERE task_id='"+caller+"';")=='1','new body copies cleared and metadata retained')
        self.assert_that(self.sql("SELECT COUNT(*) FROM task_session_scope_target WHERE caller_task_id='"+caller+"' AND (source_task_id IS NOT NULL OR target_snapshot IS NOT NULL);")=='0','scope target IDs and snapshots purged')
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation_session_scope_query WHERE caller_task_id='"+caller+"' AND (normalized_snapshot IS NOT NULL OR read_refs_snapshot IS NOT NULL);")=='0','query and read reference copies purged')
        self.assert_that(self.sql("SELECT h.metadata_bytes=r.actual FROM task_session_history h JOIN reservation r ON r.tenant_id=h.tenant_id AND r.id=CONCAT(h.task_id,':session-history') WHERE h.task_id='"+caller+"';")=='1','reservation metadata fixed point after scope purge')
        recovery=self.prior_task('caller recovery nebula',[one,two])
        worker=self.start('worker-original-pending','worker',boundary='PRIOR_SESSION_COMMIT_BEFORE')
        self.barrier(worker)
        self.scenario(worker,recovery,'fault')
        original_id,original_step=self.sql("SELECT id,step_id FROM invocation WHERE task_id='"+recovery+"' ORDER BY ordinal,id LIMIT 1;").split('\t')
        original_digest=self.sql("SELECT output_digest FROM invocation WHERE id='"+original_id+"';")
        original_cut=self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE id='"+original_step+"';")
        self.stop(worker,kill=True);self.expire(recovery)
        worker=self.start('worker-original-takeover','worker')
        self.state(recovery,'SUCCEEDED');self.quiet(recovery)
        recovered_digest=self.sql("SELECT output_digest FROM invocation WHERE id='"+original_id+"';")
        recovered_cut=self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE id='"+original_step+"';")
        self.assert_that(original_digest==recovered_digest and original_cut==recovered_cut,'supervised takeover retains original output and M1 cut',invocation=original_id,step=original_step,beforeDigest=original_digest,afterDigest=recovered_digest,beforeCut=original_cut,afterCut=recovered_cut)
        self.scenario(worker,recovery,'account')
        self.stop(worker)
        multi=self.prior_task('caller multislot nebula',[one,two])
        writer=self.start('worker-multislot','worker')
        self.state(multi,'SUCCEEDED');self.quiet(multi);self.stop(writer)
        values=self.outputs(multi)
        self.assert_that(len(values)==4 and values[1]['upper_seq']==0 and values[1]['session_id']==multi and values[2]['items']==[] and values[3]['session_id'] in (one,two),'same-response search and read freeze original caller and source cuts')
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+multi+"';")=='4','four same-response slots consume exactly four')
        unknown=self.prior_task('unknown cleanup guard',[extra[2]])
        self.http('POST','/api/tasks/'+unknown+'/cancel')
        self.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a','"+unknown+":unknown','"+unknown+"','MODEL',1,'HELD_UNKNOWN','UNKNOWN');")
        self.http('DELETE','/api/tasks/'+unknown+'/session-search-scope')
        expiry=self.prior_task('caller expiry nebula',[extra[0],extra[1]])
        writer=self.start('worker-expiry-copy','worker')
        self.state(expiry,'SUCCEEDED');self.quiet(expiry);self.stop(writer)
        self.assert_that(self.sql("SELECT h.retain_until=(SELECT MIN(sh.retain_until) FROM task_session_scope_target x JOIN task_session_history sh ON sh.tenant_id=x.tenant_id AND sh.task_id=x.source_task_id WHERE x.tenant_id=h.tenant_id AND x.caller_task_id=h.task_id) FROM task_session_history h WHERE h.task_id='"+expiry+"';")=='1','caller lifetime fixed to earliest source')
        self.sql("UPDATE task_session_scope SET next_validation_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 HOUR WHERE caller_task_id='"+expiry+"'; UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+extra[0]+"'; UPDATE task_session_scope SET next_validation_at=CURRENT_TIMESTAMP(6) WHERE caller_task_id='"+extra[0]+"';")
        eventually(lambda:self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+extra[0]+"';")=='PURGED','source-first private expiry cleanup')
        self.assert_that(self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+expiry+"';")=='PRESENT','source-first keeps caller copies private until its own cleanup')
        self.search(expiry,{'query':'nebula'},404)
        self.metadata(expiry,status=404);self.event(expiry,0,status=404)
        self.assert_that(self.http('GET','/api/tasks/'+expiry)[1]['summary'] is None,'expired source blocks already-copied summary')
        eventually(lambda:self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+expiry+"';")=='PURGED','expired caller safely purged after source')
        self.assert_that(self.sql("SELECT body_bytes,held_bytes,cleanup_receipt IS NULL FROM task_session_history WHERE task_id='"+unknown+"';").split('\t')[1:]==['8388608','1'],'UNKNOWN retains full reservation while later safe candidate cleans')
        revoked=self.prior_task('caller pending revoke nebula',[one,two])
        writer=self.start('worker-revoke-pending','worker',boundary='PRIOR_SESSION_COMMIT_BEFORE')
        self.barrier(writer)
        self.assert_that(self.http('DELETE','/api/tasks/'+revoked+'/session-search-scope')[0]==204,'revoke linearizes before original pending commit')
        self.assert_that(self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+revoked+"';")=='PRESENT','live writer keeps private body charged after revoke')
        (writer['folder']/'release').write_text('continue')
        self.state(revoked,'CANCELLED');self.quiet(revoked);self.stop(writer)
        self.sql("UPDATE task_session_scope SET next_validation_at=CURRENT_TIMESTAMP(6) WHERE caller_task_id='"+revoked+"';")
        eventually(lambda:self.sql("SELECT content_state FROM task_session_history WHERE task_id='"+revoked+"';")=='PURGED','pending revoked slot safely purged')
        self.assert_that(self.sql("SELECT CONCAT(state,':',q.content_state,':',outcome,':',COALESCE(native_read_seq,'NULL')) FROM invocation JOIN invocation_session_scope_query q ON q.invocation_id=invocation.id AND q.tenant_id=invocation.tenant_id WHERE task_id='"+revoked+"';")=='PENDING:PURGED:PENDING:NULL','pending purge invents no execution or receipt')
        self.stop(self.control)
        self.control=self.start('control-flag-off','control',extra={'jimu.session-search.enabled':'false'})
        self.search(recovery,{'query':'nebula'})
        self.assert_that('session_search' not in self.http('GET','/api/capabilities')[1],'flag off hides new capability while authorized owner history remains')
        self.assert_that(self.prior_task('flag-off admission',expected=403)['code']=='SESSION_SEARCH_DISABLED','flag off rejects new admission')
        self.stop(self.control);self.control=self.start('control-flag-restored','control')
        actor_source=self.prior_task('source carol nebula',subject='c')
        writer=self.start('worker-actor-loss','worker')
        self.state(actor_source,'SUCCEEDED');self.quiet(actor_source)
        actor_caller=self.prior_task('caller actor nebula',[actor_source],subject='c')
        self.state(actor_caller,'SUCCEEDED');self.quiet(actor_caller);self.stop(writer)
        self.sql("UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='carol';")
        self.assert_that(self.sql("SELECT enabled FROM actor_access WHERE tenant_id='tenant-a' AND actor_id='carol';")=='0','fixture actually revokes authenticated source owner')
        self.search(actor_caller,{'query':'nebula'},404,subject='c')
        self.assert_that(self.sql("SELECT invalidated_at IS NOT NULL FROM task_session_scope WHERE caller_task_id='"+actor_caller+"';")=='1','observed actor loss durably invalidates after rollback')
        self.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='carol';")
        self.search(actor_caller,{'query':'nebula'},404,subject='c')
        self.assert_that(self.http('GET','/api/tasks/'+actor_caller,subject='c')[1]['summary'] is None,'reenabled actor cannot revive derived body')
        self.sql("UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='session_search';")
        self.search(recovery,{'query':'nebula'},404)
        self.assert_that(self.sql("SELECT invalidated_at IS NOT NULL FROM task_session_scope WHERE caller_task_id='"+recovery+"';")=='1','observed pin loss durably invalidates after rollback')
        self.sql("UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='session_search';")
        self.search(recovery,{'query':'nebula'},404)
        self.assert_that(self.http('GET','/api/tasks/'+recovery)[1]['summary'] is None,'reenabled installation cannot revive derived body')
        self.sql('UPDATE tenant_budget SET history_storage_limit=67108864 WHERE tenant_id=\'tenant-b\';')
        for i in range(8): self.prior_task('default64 source '+str(i),subject='b')
        self.assert_that(self.prior_task('default64 ninth',subject='b',expected=429)['code']=='SESSION_HISTORY_LIMIT','real64MiB quota429 retained')



if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser=argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--v21-jar', type=Path)
    args=parser.parse_args()
    experiment=PriorExperiment(args.jar,args.evidence)
    experiment.v21=args.v21_jar.resolve() if args.v21_jar else None
    try: experiment.run()
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc()); raise
    finally: experiment.cleanup()
