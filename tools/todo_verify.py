"""Foreground XIAO-50 slice: private MySQL, real owner HTTP and sole advisor loop."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import traceback
import uuid

from p2_verify import Experiment, Handler, eventually
from mcp_verify import McpExperiment
from p3_model_verify import config


class Model(Handler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        replies = [json.loads(m['content']) for m in body['messages'] if m['role'] == 'tool']
        assert all(m['role'] != 'system' for m in body['messages'])
        names = {t['function']['name'] for t in body['tools']}
        legacy = goal.startswith('legacy-')
        assert names == ({'web_fetch'} if legacy else {'todo_write'}), names
        plan = self.server.plans[goal]
        at = len(replies)
        if at < len(plan):
            batch = plan[at] if goal.startswith('batch-') else [plan[at]]
            calls = [{'id': 'todo-' + str(at+i), 'type': 'function', 'function': {
                'name': 'web_fetch' if legacy else 'todo_write',
                'arguments': value if isinstance(value, str) else json.dumps(value, ensure_ascii=True)}} for i, value in enumerate(batch)]
            message = {'role': 'assistant', 'content': '', 'tool_calls': calls}
        else:
            message = {'role': 'assistant', 'content': 'x'*8193 if goal=='summary-limit' else 'Recorded steps are untrusted progress data.'}
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0)+1
            self.server.requests.append({'goal': goal, 'body': body, 'replies': replies})
        response = {'id': 'controlled-todo', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if 'tool_calls' in message else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}}
        self.send(200, response)


def items(*pairs):
    return {'todos': [{'content': content, 'status': status} for content, status in pairs]}


class TodoExperiment(Experiment):
    start = McpExperiment.start
    def stop(self, instance, kill=False):
        child=instance['process']
        if kill and child.poll() is None:
            child.kill()
            eventually(lambda:self.sql('SELECT stopped FROM worker_process WHERE pid='+str(child.pid)+';')=='1','supervisor confirms killed child')
        Experiment.stop(self,instance)

    def prepare(self):
        self.fixture.RequestHandlerClass = Model
        self.fixture.plans, self.fixture.requests = {}, []
        super().prepare()
        self.sql("GRANT INSERT,UPDATE ON jimu.task_todo_projection TO 'runtime'@'%';")
        digest = hashlib.sha256((Path(__file__).resolve().parents[1] / 'src/main/resources/todo/todo_write-0.1.0/output.schema.json').read_bytes()).hexdigest()
        self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','todo_write','0.1.0','"+digest+"'),('tenant-b','todo_write','0.1.0','"+digest+"');")
        self.assert_that('todo_write' not in self.http('GET','/api/capabilities')[1], 'default flag hides todo')
        status, error = self.http('POST','/api/tasks',{'goal':'disabled','capabilityIds':['todo_write']},key='disabled')
        self.assert_that(status==403 and error['code']=='TODO_NOT_AUTHORIZED','default disabled rejects todo',status=status)
        self.stop(self.control)
        settings = config({'a': self.fixture}, paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        settings.update({'jimu.model.profiles.a.model':'local-byte-accounted-v1','jimu.model.profiles.a.revision':'local-byte-accounted-v1',
            'jimu.model.profiles.a.response-bytes':'65536','jimu.model.profiles.a.input-limit.version':'1',
            'jimu.model.profiles.a.input-limit.max-tokens':'8192','jimu.model.profiles.a.input-limit.proof':'test-whole-request-utf8-v1',
            'jimu.todos.enabled':'true'})
        self.common.update(settings)
        self.control=self.start('control-todo','control')
        self.assert_that('todo_write' in self.http('GET','/api/capabilities')[1], 'authorized bounded explicit paid=false profile advertises todo')
        self.sql('UPDATE tenant_budget SET todo_storage_limit=268435456,model_limit=1000,tool_limit=1000,token_limit=16777216;')
        self.subjects={}

    def task(self, goal, plan, subject='a', **fields):
        self.fixture.plans[goal]=plan
        status,value=self.http('POST','/api/tasks',{'goal':goal,'capabilityIds':['todo_write'],**fields},key=goal,subject=subject)
        self.assert_that(status==202,'accept '+goal,status=status,value=value)
        task=value['taskId'];self.subjects[task]=subject
        return task

    def state(self, task, wanted):
        def read():
            status,value=self.http('GET','/api/tasks/'+task,subject=self.subjects.get(task,'a'))
            assert status==200,(status,value)
            if value['state']==wanted:return value
            assert value['state'] not in ('SUCCEEDED','FAILED','CANCELLED','NEEDS_RECONCILIATION'),value
            return False
        return eventually(read,'task '+task+' -> '+wanted)

    def view(self, task, subject=None):
        status,value=self.http('GET','/api/tasks/'+task+'/todos',subject=subject or self.subjects.get(task,'a'))
        assert status==200,(status,value)
        return value

    def normal(self):
        task=self.task('normal',[items(('  Read source\ufeff','in_progress'),('Verify','pending')),
            items(('Read source','completed'),('Verify','in_progress')),items(('Verify','completed'))])
        initial=self.view(task)
        self.assert_that(initial['state']=='UNWRITTEN' and initial['revision']==0 and initial['todos'] is None,'unwritten differs from explicit empty')
        self.worker=self.start('worker-todo-one','worker')
        self.other=self.start('worker-todo-two','worker')
        self.state(task,'SUCCEEDED')
        view=self.view(task)
        self.assert_that(view['revision']==3 and view['todos']==items(('Verify','completed'))['todos'] and view['counts']=={'pending':0,'inProgress':0,'completed':1},'sole loop records replaces and completes ordered list',snapshot=view)
        self.assert_that(self.sql("SELECT COUNT(*) FROM artifact WHERE task_id='"+task+"';")=='0','todo summary does not publish a report')
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='3' and self.fixture.models['normal']==4,'three real tools and four model dispatches')
        status,replay=self.http('POST','/api/tasks',{'goal':'normal','capabilityIds':['todo_write']},key='normal')
        self.assert_that(status==202 and replay['taskId']==task,'idempotent acceptance retains one storage reservation')
        before=self.sql("SELECT next_event_seq,revision,models_used,tools_used FROM task WHERE id='"+task+"';")
        for _ in range(3):self.assert_that(self.view(task)==view,'repeated owner GET stable')
        self.assert_that(before==self.sql("SELECT next_event_seq,revision,models_used,tools_used FROM task WHERE id='"+task+"';"),'reads create no execution or event facts')
        for subject in ('b','c'):
            self.assert_that(self.http('GET','/api/tasks/'+task+'/todos',subject=subject)[0]==404,'foreign tenant or owner denied '+subject)
        self.stop(self.worker);self.stop(self.other);self.stop(self.control)
        self.control=self.start('control-todo-restarted','control')
        self.assert_that(self.view(task)==view,'control restart and reconnect read persisted latest')
        self.worker=self.start('worker-todo-restarted','worker')
        empty=self.task('empty',[items()]);self.state(empty,'SUCCEEDED')
        self.assert_that(self.view(empty)['todos']==[] and self.view(empty)['revision']==1,'explicit empty is PRESENT revision one')
        same=self.task('same-content',[items(('x','pending')),items(('x','pending'))]);self.state(same,'SUCCEEDED')
        self.assert_that(self.view(same)['revision']==2 and self.sql("SELECT tools_used FROM task WHERE id='"+same+"';")=='2','new invocation with identical content keeps its own fact')
        batch=self.task('batch-three',[[items(('a','pending')),items(('b','in_progress')),items(('c','completed'))]])
        self.state(batch,'SUCCEEDED')
        self.assert_that(self.view(batch)['revision']==3 and self.sql("SELECT GROUP_CONCAT(todo_expected_revision ORDER BY ordinal) FROM invocation WHERE task_id='"+batch+"';")=='0,1,2','same response slots freeze consecutive revisions')
        legacy='legacy-absent';self.fixture.plans[legacy]=[]
        status,value=self.http('POST','/api/tasks',{'goal':legacy},key=legacy)
        assert status==202,value
        self.state(value['taskId'],'SUCCEEDED')
        self.assert_that(self.view(value['taskId'])['state']=='ABSENT','ordinary task remains usable without todo row')
        self.stop(self.worker)
        self.complete=task

    def recovery(self):
        for boundary in ('M2','TODO_COMMIT_BEFORE','TODO_COMMIT_AFTER'):
            task=self.task('recover-'+boundary,[items(('original','pending')),items(('replacement','completed'))])
            worker=self.start('crash-'+boundary,'worker',boundary)
            self.barrier(worker)
            view=self.view(task)
            applied=1 if boundary=='TODO_COMMIT_AFTER' else 0
            self.assert_that(view['revision']==applied and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")==str(applied),'atomic state at '+boundary,snapshot=view)
            self.stop(worker,kill=True);self.expire(task)
            resumed=self.start('resume-'+boundary,'worker')
            self.state(task,'SUCCEEDED')
            self.assert_that(self.view(task)['revision']==2 and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='2','recovery does not duplicate revision or tool charge '+boundary)
            self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='todo.updated';")=='2','one update event per real invocation '+boundary)
            self.stop(resumed)

    def invalid(self):
        worker=self.start('worker-invalid','worker')
        cases=[items(('a','in_progress'),('b','in_progress')),items(('x','pending'),(' x ','completed')),
            items(('\ufeff\u00a0','pending')),{'todos':[],'taskId':'forged'},
            '{"todos":[],"todos":[]}','{"todos":[]} true',items(('x','unknown')),
            items(('x'*1025,'pending')),items(('😀'*257,'pending')),items(('\ud800','pending')),
            {'todos':[{'content':str(i),'status':'pending'} for i in range(33)]},
            '{"todos":[]}'+' '*16384,
            {'todos':[{'content':str(i)+'x'*300,'status':'pending'} for i in range(32)]}]
        for index,command in enumerate(cases):
            task=self.task('invalid-'+str(index),[command]);self.state(task,'FAILED')
            self.assert_that(self.sql("SELECT code,tools_used FROM task WHERE id='"+task+"';")=='TOOL_INPUT_INVALID\t0' and self.view(task)['revision']==0,'invalid input rejected without todo fact '+str(index))
        self.stop(worker)

    def cleanup_case(self):
        task=self.complete
        digest=self.view(task)['source']['outputSha256']
        held=int(self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
        self.sql("UPDATE task_todo_projection SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+task+"';")
        eventually(lambda:self.sql("SELECT content_state FROM task_todo_projection WHERE task_id='"+task+"';")=='PURGED','actual bounded retention cleanup',25)
        view=self.view(task)
        self.assert_that(view['state']=='PURGED' and view['todos'] is None and view['revision']==3 and view['source']['outputSha256']==digest,'cleanup preserves latest identity provenance and digest',snapshot=view)
        self.assert_that(int(self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))==held-2097152,'only actual cleanup releases independent storage reservation')
        self.assert_that(self.http('POST','/api/tasks',{'goal':'normal','capabilityIds':['todo_write']},key='normal')[0]==410,'purged idempotency key cannot recreate or parse old request')
        bodies=self.sql("SELECT request_snapshot,goal,summary FROM task WHERE id='"+task+"';")+self.sql("SELECT request_snapshot,response_snapshot FROM step WHERE task_id='"+task+"';")+self.sql("SELECT input_snapshot,output_snapshot FROM invocation WHERE task_id='"+task+"';")
        self.assert_that('Read source' not in bodies and 'Verify' not in bodies and 'TODO_PURGED' in bodies,'all duplicated todo bodies actually removed')

    def probe_command(self, task, worker, mode='commit', slot=0):
        if not hasattr(self,'probe_classes'):
            self.probe_classes=self.root/'probe-classes'
            shutil.copytree(Path(__file__).resolve().parents[1]/'target/test-classes',self.probe_classes)
        return [self.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TodoLedgerScenario',
            '-Dloader.path='+str(self.probe_classes),'-cp',str(self.jar),'org.springframework.boot.loader.launch.PropertiesLauncher',
            str(worker['folder']/'runtime.properties'),task,mode,str(slot)]

    def probe(self, task, worker, mode='commit', slot=0):
        result=subprocess.run(self.probe_command(task,worker,mode,slot),text=True,capture_output=True,timeout=20)
        (self.evidence/('probe-'+mode+'.jsonl')).open('a').write(json.dumps({'task':task,'slot':slot,'exit':result.returncode,'stdout':result.stdout,'stderr':result.stderr})+'\n')
        assert result.returncode==0,(result.returncode,result.stdout,result.stderr)
        return json.loads(result.stdout.strip().splitlines()[-1])

    def transaction_cases(self):
        task=self.task('transaction-boundaries',[items(('initial','pending'))])
        worker=self.start('worker-transaction-boundaries','worker','M2');self.barrier(worker)
        before=self.sql("SELECT next_event_seq,tools_used FROM task WHERE id='"+task+"';")
        for mode,code in [('old-fence','FENCE_REJECTED'),('actor','INVOCATION_MISMATCH'),('step','INVOCATION_MISMATCH'),('slot','INVOCATION_MISMATCH'),('provider','INVOCATION_MISMATCH'),('input','INVOCATION_MISMATCH'),('admit','TODO_COMMIT_REQUIRED'),('succeed','TODO_COMMIT_REQUIRED'),('uncertain','TODO_COMMIT_REQUIRED')]:
            self.assert_that(self.probe(task,worker,mode)['code']==code,'reject forged source or generic bypass '+mode)
        for table in ('invocation','step'):
            self.sql("UPDATE "+table+" SET record_version=99 WHERE task_id='"+task+"';")
            self.assert_that(self.probe(task,worker)['code']=='PAYLOAD_VERSION_UNSUPPORTED','unknown source metadata version rejected '+table)
            self.sql("UPDATE "+table+" SET record_version=1 WHERE task_id='"+task+"';")
        binding=self.sql("SELECT binding_snapshot FROM task_todo_projection WHERE task_id='"+task+"';")
        unknown=json.loads(binding);unknown['version']=99
        raw=json.dumps(unknown,separators=(',',':'))
        self.sql("UPDATE task_todo_projection SET binding_snapshot='"+raw+"',binding_digest=SHA2('"+raw+"',256) WHERE task_id='"+task+"';")
        self.assert_that(self.probe(task,worker)['code']=='TODO_BINDING_UNSUPPORTED','unknown binding version rejected before tool execution')
        self.sql("UPDATE task_todo_projection SET binding_snapshot='"+binding+"',binding_digest=SHA2('"+binding+"',256) WHERE task_id='"+task+"';")
        self.sql("UPDATE invocation SET todo_expected_revision=1 WHERE task_id='"+task+"';")
        self.assert_that(self.probe(task,worker)['code']=='TODO_REVISION_CONFLICT','stale expected revision refuses write')
        self.sql("UPDATE invocation SET todo_expected_revision=0 WHERE task_id='"+task+"';")
        self.sql("CREATE TRIGGER todo_test_rollback BEFORE UPDATE ON task_todo_projection FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rollback';")
        try:self.assert_that(self.probe(task,worker)['code']=='UNCONFIRMED','mid-transaction SQL failure exits without guessing')
        finally:self.sql('DROP TRIGGER todo_test_rollback;')
        self.assert_that(self.view(task)['revision']==0 and self.sql("SELECT next_event_seq,tools_used FROM task WHERE id='"+task+"';")==before and self.sql("SELECT COUNT(*) FROM reservation WHERE task_id='"+task+"' AND category='TOOL';")=='0','rollback leaves projection invocation event and charge all unchanged')
        locker=subprocess.Popen(self.probe_command(task,worker,'lock'),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        self.children.append(locker)
        try:
            assert locker.stdout.readline().strip()=='LOCKED'
            start=time.monotonic();result=self.probe(task,worker);elapsed=time.monotonic()-start
            self.assert_that(result['code']=='UNCONFIRMED' and elapsed<8,'contended short transaction times out without partial facts',elapsedSeconds=elapsed)
        finally:
            locker.communicate('\n',timeout=10)
        self.assert_that(self.probe(task,worker,'lost-ack')['code']=='UNCONFIRMED','real COMMIT followed by injected lost acknowledgement')
        self.assert_that(self.view(task)['revision']==1 and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='1','commit acknowledgement loss still has atomic durable fact')
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results=list(pool.map(lambda _:self.probe(task,worker),range(2)))
        self.assert_that(all(r['code']=='OK' for r in results) and self.view(task)['revision']==1 and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='1','duplicate workers resolve same stable invocation without duplicate charge')
        (worker['folder']/'release').touch();self.state(task,'SUCCEEDED');self.stop(worker)
        self.assert_that(self.probe(task,worker)['code']=='FENCE_REJECTED','terminal task cannot restore an old tool')

        task=self.task('batch-old-slot',[[items(('first','pending')),items(('second','pending')),items(('third','completed'))]])
        worker=self.start('worker-batch-old-slot','worker','M2');self.barrier(worker)
        for slot in range(3):self.assert_that(self.probe(task,worker,slot=slot)['code']=='OK','new ordered slot '+str(slot))
        original=self.probe(task,worker,slot=0)
        self.assert_that(original['result']['todos']==items(('first','pending'))['todos'] and self.view(task)['revision']==3 and self.view(task)['todos']==items(('third','completed'))['todos'],'old successful slot returns original result without overwriting later revision')
        (worker['folder']/'release').touch();self.state(task,'SUCCEEDED');self.stop(worker)

    def authorization_cases(self):
        for name,sql,restore,code in [
            ('actor',"UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';","UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice';",'ACCESS_REVOKED'),
            ('installation',"UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='todo_write';","UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='todo_write';",'CAPABILITY_REVOKED'),
            ('epoch',"UPDATE installation SET epoch=epoch+1 WHERE tenant_id='tenant-a' AND plugin_id='todo_write';","UPDATE installation SET epoch=epoch-1 WHERE tenant_id='tenant-a' AND plugin_id='todo_write';",'PLUGIN_PIN_MISMATCH'),
            ('pin',"UPDATE installation SET plugin_version='99' WHERE tenant_id='tenant-a' AND plugin_id='todo_write';","UPDATE installation SET plugin_version='0.1.0' WHERE tenant_id='tenant-a' AND plugin_id='todo_write';",'PLUGIN_PIN_MISMATCH')]:
            task=self.task('revoke-'+name,[items(('blocked','pending'))])
            worker=self.start('revoke-worker-'+name,'worker','M2');self.barrier(worker)
            self.sql(sql)
            self.assert_that(self.probe(task,worker)['code']==code and self.http('GET','/api/tasks/'+task+'/todos')[0] in (403,409),'write recovery and GET recheck current '+name)
            (worker['folder']/'release').touch()
            eventually(lambda:self.sql("SELECT state FROM task WHERE id='"+task+"';")=='FAILED','revoked task fails')
            self.stop(worker);self.sql(restore)
            self.assert_that(self.view(task)['revision']==0,'revoked task retained without todo fact '+name)
        for name in ('cancel','deadline'):
            task=self.task('blocked-'+name,[items(('blocked','pending'))])
            worker=self.start('blocked-worker-'+name,'worker','M2');self.barrier(worker)
            if name=='cancel':self.http('POST','/api/tasks/'+task+'/cancel')
            else:self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
            result=self.probe(task,worker)
            self.assert_that(result['code'] in ('CANCEL_REQUESTED','DEADLINE_EXCEEDED','FENCE_REJECTED') and self.view(task)['revision']==0,'cancel deadline retain read but reject write '+name,code=result['code'])
            (worker['folder']/'release').touch()
            eventually(lambda:self.sql("SELECT state FROM task WHERE id='"+task+"';") in ('CANCELLED','FAILED'),'blocked writer converges')
            self.stop(worker)
        task=self.task('deadline-during-commit',[items(('must-roll-back','pending'))])
        worker=self.start('worker-deadline-during-commit','worker','M2');self.barrier(worker)
        self.sql("CREATE TRIGGER todo_test_deadline BEFORE UPDATE ON task_todo_projection FOR EACH ROW UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id;")
        try:
            result=self.probe(task,worker)
            self.assert_that(result['code']=='FENCE_REJECTED' and self.view(task)['revision']==0 and self.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=='0','DB deadline expiry injected after admission rolls back projection event result and charge',code=result['code'])
        finally:self.sql('DROP TRIGGER todo_test_deadline;')
        self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
        (worker['folder']/'release').touch();self.state(task,'FAILED');self.stop(worker)

    def sse_cases(self):
        task=self.task('sse-race',[items(('updated-between-get-and-subscribe','completed'))])
        worker=self.start('worker-sse-race','worker','M2');self.barrier(worker)
        initial=self.view(task)
        before=self.sql("SELECT next_event_seq,models_used,tools_used FROM task WHERE id='"+task+"';")
        started=time.monotonic()
        status,idle=self.http('GET','/api/tasks/'+task+'/events',headers={'Last-Event-ID':task+':'+str(initial['asOfSeq'])})
        elapsed=time.monotonic()-started
        self.assert_that(status==200 and idle=='' and 24<=elapsed<35 and before==self.sql("SELECT next_event_seq,models_used,tools_used FROM task WHERE id='"+task+"';"),'actual idle SSE expires at 25 seconds without new facts',elapsedSeconds=elapsed)
        (worker['folder']/'release').touch();self.state(task,'SUCCEEDED');self.stop(worker)
        status,wire=self.http('GET','/api/tasks/'+task+'/events',headers={'Last-Event-ID':task+':'+str(initial['asOfSeq'])})
        self.assert_that(status==200 and 'todo.updated' in wire and 'updated-between-get-and-subscribe' not in wire,'SSE replays lost interval as invalidation without todo body')
        (self.evidence/'sse-replay.txt').write_text(wire)
        for cursor in (task+':999999',str(uuid.uuid4())+':1'):
            self.assert_that(self.http('GET','/api/tasks/'+task+'/events',headers={'Last-Event-ID':cursor})[0]==400,'future or foreign task SSE cursor rejected')
        self.sql("UPDATE event SET occurred_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE task_id='"+task+"';")
        status,error=self.http('GET','/api/tasks/'+task+'/events',headers={'Last-Event-ID':task+':'+str(initial['asOfSeq'])})
        self.assert_that(status==410 and self.view(task)['revision']==1,'410 cursor expires independently of latest owner snapshot',status=status,error=error)

    def limit_cases(self):
        worker=self.start('worker-local-budget','worker')
        for goal,plan,budget,code in [
            ('model-limit',[items(('kept','pending'))],{'maxModelRequests':1},'MODEL_BUDGET_EXCEEDED'),
            ('model-limit-eight',[items() for _ in range(8)],{},'MODEL_BUDGET_EXCEEDED'),
            ('tool-limit',[items(),items()],{'maxToolInvocations':1},'TOOL_BUDGET_EXCEEDED'),
            ('batch-hard-tool-limit',[[items() for _ in range(4)] for _ in range(24)],{},'TODO_EXECUTION_LIMIT'),
            ('summary-limit',[],{},'SUMMARY_TOO_LARGE'),
            ('batch-five',[[items(),items(),items(),items(),items()]],{},'MODEL_RESPONSE_TOO_LARGE')]:
            task=self.task(goal,plan,budget=budget);self.state(task,'FAILED')
            actual=self.sql("SELECT code FROM task WHERE id='"+task+"';")
            self.assert_that(actual==code,'explicit local limit '+goal,expected=code,actual=actual)
        self.stop(worker)
        for budget in ({'maxModelRequests':9},{'maxToolInvocations':21}):
            status,value=self.http('POST','/api/tasks',{'goal':'cannot-widen-fixed-caps','capabilityIds':['todo_write'],'budget':budget},key=str(uuid.uuid4()))
            self.assert_that(status==400 and value['code']=='INVALID_BUDGET','HTTP caller cannot widen existing execution ceilings',budget=budget,status=status,value=value)
        task=self.task('storage-accounting',[items(('bounded','pending'))])
        worker=self.start('worker-storage-accounting','worker','M2');self.barrier(worker)
        request=self.sql("SELECT HEX(request_snapshot) FROM step WHERE task_id='"+task+"';")
        self.sql("UPDATE step SET request_snapshot=REPEAT('x',2097152-65536) WHERE task_id='"+task+"';")
        self.assert_that(self.probe(task,worker)['code']=='TODO_STORAGE_LIMIT' and self.view(task)['revision']==0,'all accumulated body copies plus metadata reserve enforce two MiB cap')
        self.sql("UPDATE step SET request_snapshot=CONVERT(UNHEX('"+request+"') USING utf8mb4) WHERE task_id='"+task+"';")
        facts=[f"('tenant-a','{task}',{1000+i},'{task}:{1000+i}',CONCAT('fixture-meta-{i}',REPEAT('x',100)),REPEAT('x',64),REPEAT('x',64),REPEAT('x',32))" for i in range(180)]
        self.sql('INSERT INTO event(tenant_id,task_id,seq,event_id,fact_id,type,code,status) VALUES'+','.join(facts)+';')
        self.assert_that(self.probe(task,worker)['code']=='TODO_STORAGE_LIMIT' and self.view(task)['revision']==0,'fixed metadata allocation is bounded independently of body size')
        self.sql("DELETE FROM event WHERE task_id='"+task+"' AND seq>=1000;")
        (worker['folder']/'release').touch();self.state(task,'SUCCEEDED');self.stop(worker)
        held=int(self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
        self.sql('UPDATE tenant_budget SET todo_storage_limit='+str(held)+" WHERE tenant_id='tenant-a';")
        status,value=self.http('POST','/api/tasks',{'goal':'full-storage','capabilityIds':['todo_write']},key='full-storage')
        self.assert_that(status==429 and value['code']=='TODO_STORAGE_LIMIT','independent tenant todo quota rejects explicitly')
        worker=self.start('worker-limit-other','worker')
        other=self.task('other-tenant',[items()],subject='b');self.state(other,'SUCCEEDED')
        self.fixture.plans['legacy-quota-unblocked']=[]
        status,ordinary=self.http('POST','/api/tasks',{'goal':'legacy-quota-unblocked'},key='legacy-quota-unblocked')
        assert status==202,ordinary
        self.state(ordinary['taskId'],'SUCCEEDED');self.stop(worker)
        self.assert_that(self.view(other)['revision']==1 and self.view(ordinary['taskId'])['state']=='ABSENT','full todo quota leaves ordinary tasks and other tenant usable')
        self.sql("UPDATE tenant_budget SET todo_storage_limit=268435456 WHERE tenant_id='tenant-a';")
        for name in ('events','attempts'):
            task=self.task('cap-'+name,[items(('bounded','pending'))])
            worker=self.start('cap-worker-'+name,'worker','M2');self.barrier(worker)
            if name=='events':
                self.sql("UPDATE task SET next_event_seq=254 WHERE id='"+task+"';")
                self.assert_that(self.probe(task,worker)['code']=='TODO_EXECUTION_LIMIT','event cap reserves terminal and cleanup slots')
                (worker['folder']/'release').touch();self.state(task,'FAILED');self.stop(worker)
                self.assert_that(self.sql("SELECT next_event_seq FROM task WHERE id='"+task+"';")=='255','terminal fits reserved event slot')
            else:
                self.stop(worker,kill=True);self.expire(task)
                for fence in range(2,9):self.sql("INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state,quiescent) VALUES('tenant-a','"+str(uuid.uuid4())+"','"+task+"','"+str(uuid.uuid4())+"',"+str(fence)+",'EXPIRED',TRUE);")
                resumed=self.start('attempt-cap-resume','worker');self.state(task,'FAILED');self.stop(resumed)
                self.assert_that(self.sql("SELECT code FROM task WHERE id='"+task+"';")=='TODO_EXECUTION_LIMIT','eighth attempt cap prevents ninth writer')

    def retention_guards(self):
        task=self.task('retention-guard',[items(('retained-sensitive-fixture','pending'))])
        worker=self.start('worker-retention-guard','worker');self.state(task,'SUCCEEDED');self.stop(worker)
        source=self.view(task)['source']
        self.sql("UPDATE invocation SET output_digest=REPEAT('0',64) WHERE id='"+source['invocationId']+"';")
        self.assert_that(self.http('GET','/api/tasks/'+task+'/todos')[0]==409,'corrupt source digest refuses owner snapshot')
        self.sql("UPDATE invocation SET output_digest='"+source['outputSha256']+"' WHERE id='"+source['invocationId']+"';")
        original=self.sql("SELECT retain_until FROM task_todo_projection WHERE task_id='"+task+"';")
        held=self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")
        unknown=str(uuid.uuid4())
        self.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a','"+unknown+"','"+task+"','MODEL',1,'HELD_UNKNOWN','UNKNOWN');")
        self.sql("UPDATE task_todo_projection SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+task+"';")
        self.stop(self.control);self.control=self.start('control-retention-unknown','control')
        self.assert_that(self.view(task)['state']=='PRESENT' and self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")==held,'UNKNOWN prevents cleanup and quota release despite expiry')
        self.sql("UPDATE reservation SET state='SETTLED',usage_quality='KNOWN',actual=1 WHERE id='"+unknown+"';")
        attempt=str(uuid.uuid4())
        self.sql("INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state,quiescent) VALUES('tenant-a','"+attempt+"','"+task+"','"+str(uuid.uuid4())+"',99,'EXPIRED',FALSE);")
        self.stop(self.control);self.control=self.start('control-retention-writer','control')
        self.assert_that(self.view(task)['state']=='PRESENT' and self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")==held,'all historical writers must have quiescence proof before cleanup')
        self.sql("UPDATE task_attempt SET quiescent=TRUE WHERE id='"+attempt+"';")
        eventually(lambda:self.view(task)['state']=='PURGED','quiescent known terminal cleanup',25)
        released=self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")
        seq=self.view(task)['asOfSeq']
        self.stop(self.control);self.control=self.start('control-retention-repeat','control')
        self.assert_that(self.view(task)['asOfSeq']==seq and self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';")==released and self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='todo.content_deleted';")=='1','repeat maintenance neither releases twice nor emits a duplicate deletion')
        self.assert_that(self.view(self.complete)['state']=='PRESENT','not-yet-expired todo remains retained during cleanup of another task')

    def feature_and_uncertain(self):
        task=self.task('runtime-unconfirmed',[items(('once','completed'))])
        worker=self.start('worker-runtime-unconfirmed','worker','M2');self.barrier(worker)
        self.sql("CREATE TRIGGER todo_runtime_rollback BEFORE UPDATE ON task_todo_projection FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture transaction failure';")
        try:
            (worker['folder']/'release').touch()
            eventually(lambda:self.sql("SELECT quiescent FROM task_attempt WHERE task_id='"+task+"';")=='1','unconfirmed runtime drains without terminal guess')
            self.assert_that(self.sql("SELECT state,tools_used FROM task WHERE id='"+task+"';")=='RUNNING\t0' and self.view(task)['revision']==0,'todo SQL failure exits attempt without stop uncertain or ordinary tool reply')
        finally:self.sql('DROP TRIGGER todo_runtime_rollback;')
        self.stop(worker);self.expire(task)
        worker=self.start('worker-runtime-retry','worker');self.state(task,'SUCCEEDED');self.stop(worker)
        self.assert_that(self.view(task)['revision']==1 and self.fixture.models['runtime-unconfirmed']==2,'new fence resumes exact original slot after unconfirmed result')
        task=self.task('disabled-recovery',[items(('blocked','pending'))])
        worker=self.start('worker-before-disabled','worker','M2');self.barrier(worker);self.stop(worker,kill=True);self.expire(task)
        self.stop(self.control)
        self.control=self.start('control-disabled-again','control',extra={'jimu.todos.enabled':'false'})
        self.assert_that(self.view(self.complete)['state']=='PRESENT' and 'todo_write' not in self.http('GET','/api/capabilities')[1],'flag disabled retains history and hides capability')
        disabled=self.start('worker-disabled','worker',extra={'jimu.todos.enabled':'false'})
        self.state(task,'FAILED');self.stop(disabled)
        self.assert_that(self.sql("SELECT code FROM task WHERE id='"+task+"';")=='TODO_FEATURE_DISABLED' and self.view(task)['revision']==0,'disabled worker rejects recovery without clearing history')
        self.stop(self.control);self.control=self.start('control-enabled-final','control')

    def cleanup(self):
        try:
            for child in reversed(self.children):self.stop({'process':child,'name':'todo foreground collection'})
            if self.schema_ready:
                for table in ('task_todo_projection',):
                    (self.evidence/(table+'.tsv')).write_text(self.sql('SELECT * FROM '+table+';'))
                (self.evidence/'todo-invocation.tsv').write_text(self.sql('SELECT tenant_id,id,task_id,step_id,attempt_id,todo_expected_revision,todo_applied_revision,todo_committed_attempt_id,todo_committed_fence,todo_event_seq,todo_committed_at FROM invocation;'))
                (self.evidence/'model-wire.json').write_text(json.dumps(self.fixture.requests,ensure_ascii=True,indent=2))
        finally:super().cleanup()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--slice',action='store_true')
    args=parser.parse_args();experiment=TodoExperiment(args.jar,args.evidence)
    try:
        experiment.prepare();experiment.normal()
        if not args.slice:
            experiment.recovery();experiment.invalid();experiment.transaction_cases();experiment.authorization_cases();experiment.sse_cases();experiment.limit_cases();experiment.retention_guards();experiment.feature_and_uncertain();experiment.cleanup_case()
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:experiment.cleanup()
