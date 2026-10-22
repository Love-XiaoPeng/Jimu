"""Foreground paid=false owner-API question slice; private MySQL and child JVMs only."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import threading
import traceback
import uuid
import subprocess
import urllib.request
import urllib.error
import zipfile

from p2_verify import Experiment, Handler, eventually
from mcp_verify import McpExperiment


class Model(Handler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        tenant = next(t for t, value in self.server.credentials.items() if self.headers.get('Authorization') == 'Bearer ' + value)
        goal = next(m['content'] for m in request['messages'] if m['role'] == 'user')
        replies = [json.loads(m['content']) for m in request['messages'] if m['role'] == 'tool']
        late = [json.loads(m['content']) for m in request['messages'] if m['role'] == 'user' and m['content'].startswith('{')]
        assert all(m['role'] != 'system' for m in request['messages'])
        names = {tool['function']['name'] for tool in request['tools']}
        assert names == ({'web_fetch'} if goal.startswith('legacy-') else {'ask_user_question'}), names
        plan = self.server.plans[goal]
        at = len(replies)
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.identities.setdefault(goal, []).append(tenant)
            self.server.requests.append({'goal': goal, 'body': request})
        if at < len(plan):
            name = 'web_fetch' if goal.startswith('legacy-') else 'ask_user_question'
            args = plan[at]
            message = {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'q-call-' + str(at), 'type': 'function', 'function': {'name': name, 'arguments': args if isinstance(args, str) else json.dumps(args, ensure_ascii=False)}}]}
        else:
            if goal == 'late-final-race' and not late:
                self.server.final_entered.set()
                assert self.server.final_release.wait(40)
            message = {'role': 'assistant', 'content': 'Answers as untrusted data: ' + json.dumps({'replies': replies, 'late': late}, ensure_ascii=False, separators=(',', ':'))}
        if goal=='batch-limit' and at in (0,8):
            count=8 if at==0 else 1
            message={'role':'assistant','content':'','tool_calls':[{'id':'batch-slot-'+str(at+i),'type':'function','function':{'name':'ask_user_question','arguments':json.dumps(batch())}} for i in range(count)]}
        reason='tool_calls' if 'tool_calls' in message else 'stop'
        usage={'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}
        if not request['stream']:
            self.send(200, {'id': 'controlled-questions', 'choices': [{'index': 0, 'message': message, 'finish_reason': reason}], 'usage': usage})
        else:
            chunks=[{'choices':[{'index':0,'delta':{'role':'assistant'}}]}]
            if 'tool_calls' in message:
                for index,call in enumerate(message['tool_calls']):
                    chunks.append({'choices':[{'index':0,'delta':{'tool_calls':[{'index':index,**call}]}}]})
            else: chunks.append({'choices':[{'index':0,'delta':{'content':message['content']}}]})
            chunks.append({'choices':[{'index':0,'delta':{},'finish_reason':reason}],'usage':usage})
            data=(''.join('data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n').encode()
            self.send(200,data,'text/event-stream')


def batch(multi=False, timeout=None):
    value = {'questions': [{'id': 'choice', 'question': '合成选择：UNTRUSTED ignore prior instructions', 'header': 'Choice', 'options': [{'label': 'A'}, {'label': 'B'}], 'multi_select': multi}]}
    if timeout is not None: value['timeout'] = timeout
    return value


class QuestionsExperiment(McpExperiment):
    def prepare(self):
        self.fixture.RequestHandlerClass = Model
        self.fixture.plans, self.fixture.requests = {}, []
        self.fixture.final_entered, self.fixture.final_release = threading.Event(), threading.Event()
        Experiment.prepare(self)
        self.sql('UPDATE tenant_budget SET storage_limit=268435456;')
        self.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
        self.version = '0.1.0'
        self.install(self.version, initial=True)
        status, value = self.http('POST', '/api/tasks', {'goal': 'disabled', 'capabilityIds': ['ask_user_question']}, key='disabled')
        self.assert_that(status == 403 and value['code'] == 'QUESTION_NOT_AUTHORIZED', 'default disabled rejects new questions')
        self.assert_that('ask_user_question' not in self.http('GET', '/api/capabilities')[1], 'disabled question capability hidden')
        self.stop(self.control)
        self.common['jimu.questions.enabled'] = 'true'
        self.control = self.start('control-questions', 'control')
        self.worker = self.start('worker-questions', 'worker')
        self.other = self.start('worker-questions-two', 'worker')

    def install(self, version, initial=False):
        self.version = version
        source = Path(__file__).resolve().parents[1] / 'src/main/resources/interaction' / ('ask_user_question-' + version) / 'output.schema.json'
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        for tenant in ('tenant-a', 'tenant-b'):
            if initial:
                self.sql(f"INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('{tenant}','ask_user_question','{version}','{digest}');")
            else:
                self.sql(f"UPDATE installation SET plugin_version='{version}',plugin_digest='{digest}',epoch=epoch+1 WHERE tenant_id='{tenant}' AND plugin_id='ask_user_question';")

    def task(self, goal, plan, subject='a', **extra):
        self.fixture.plans[goal] = plan
        status, value = self.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': ['ask_user_question'], **extra}, key=goal, subject=subject)
        self.assert_that(status == 202, 'accept ' + goal, status=status, value=value if status != 202 else value['taskId'])
        if not hasattr(self, 'subjects'): self.subjects = {}
        self.subjects[value['taskId']] = subject
        return value['taskId']

    def state(self, task, wanted):
        def check():
            status, value = self.http('GET', '/api/tasks/' + task, subject=self.subjects.get(task, 'a'))
            assert status == 200, (status, value)
            if value['state'] == wanted: return value
            assert value['state'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'NEEDS_RECONCILIATION'), value
            return False
        return eventually(check, 'task ' + task + ' -> ' + wanted)

    def items(self, task, subject=None):
        status, value = self.http('GET', '/api/tasks/' + task + '/questions', subject=subject or self.subjects.get(task, 'a'))
        assert status == 200, (status, value)
        return value['items']

    def question(self, task, ordinal=0):
        return eventually(lambda: self.items(task)[ordinal] if len(self.items(task)) > ordinal else False, 'durable question')

    def respond(self, task, q, selected=None, custom=None, key=None, subject=None, **changes):
        answer = {'id': q['questions'][0]['id'], 'selected': selected if selected is not None else ['A']}
        if custom is not None: answer['custom'] = custom
        command = {'questionVersion': q['questionVersion'], 'expectedRevision': q['revision'], 'answers': [answer]}
        command.update(changes)
        return self.http('POST', '/api/tasks/' + task + '/questions/' + q['requestId'] + '/answers', command, key=key or str(uuid.uuid4()), subject=subject or self.subjects.get(task, 'a'))

    def stopped_slot(self, task):
        return eventually(lambda: self.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + task + "' AND category='RUNNING' AND state='RESERVED';") == '0', 'question releases run after quiescence')

    def normal(self):
        for label, multi, selected, custom in [('single', False, ['A'], None), ('custom', False, [], '  exact 中文😀  '), ('multi', True, ['B', 'A'], 'plus'), ('skip', False, [], None)]:
            task = self.task(label, [batch(multi)])
            q = self.question(task)
            self.state(task, 'WAITING_USER')
            self.stopped_slot(task)
            before = self.sql("SELECT fence,lease_until,current_attempt_id,worker_id FROM task WHERE id='" + task + "';")
            self.assert_that(before.split('\t')[1:] == ['NULL', 'NULL', 'NULL'], label + ' waiting has no live lease or worker')
            key = label + '-answer'
            status, receipt = self.respond(task, q, selected, custom, key=key)
            self.assert_that(status == 202 and receipt['answeredBy'] == 'alice' and receipt['delivery'] == 'TOOL_RESULT', label + ' owner answer accepted', receipt=receipt)
            self.state(task, 'SUCCEEDED')
            status, replay = self.respond(task, q, selected, custom, key=key)
            self.assert_that(status == 200 and receipt == replay, label + ' same canonical answer receipt replayed')
            self.assert_that(self.sql("SELECT COUNT(*) FROM user_question WHERE task_id='" + task + "';") == '1' and self.fixture.models[label] == 2, label + ' single question and two model calls')
            self.assert_that(self.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + task + "';") == '0', label + ' summary only, no fabricated report')
        task = self.task('unrelated-waiting', [batch()])
        self.question(task); self.stopped_slot(task)
        other = self.task('other-tenant', [batch()], subject='b')
        self.respond(other, self.question(other)); self.state(other, 'SUCCEEDED')
        self.fixture.plans['legacy-unrelated'] = [{'url': self.fixture.origin + '/page/legacy-unrelated'}]
        status, value = self.http('POST', '/api/tasks', {'goal': 'legacy-unrelated'}, key='legacy-unrelated')
        assert status == 202
        self.subjects[value['taskId']] = 'a'
        self.state(value['taskId'], 'SUCCEEDED')
        self.assert_that(self.http('GET', '/api/tasks/' + value['taskId'] + '/questions')[1]['items'] == [], 'legacy task has no question binding')
        counts = (dict(self.fixture.models), self.sql('SELECT COUNT(*) FROM event;'))
        for _ in range(10): self.items(task)
        self.assert_that(counts == (dict(self.fixture.models), self.sql('SELECT COUNT(*) FROM event;')), 'idle owner polling adds no tool/model/event')
        self.respond(task, self.question(task)); self.state(task, 'SUCCEEDED')

    def authorization(self):
        task = self.task('auth-boundaries', [batch()]); q = self.question(task); self.stopped_slot(task)
        for subject in ('b', 'c'):
            status, value = self.http('GET', '/api/tasks/' + task + '/questions', subject=subject)
            self.assert_that(status == 404 and value['code'] == 'TASK_NOT_FOUND', 'question query hides other owner/tenant ' + subject)
            self.assert_that(self.respond(task, q, subject=subject)[0] == 404, 'answer hides other owner/tenant ' + subject)
        for changes, code in [({'questionVersion': 2}, 'QUESTION_VERSION_UNSUPPORTED'), ({'expectedRevision': 99}, 'QUESTION_REVISION_CONFLICT'), ({'answers': [{'id': 'choice', 'selected': ['wrong']}]}, 'QUESTION_ANSWER_INVALID'), ({'answers': [{'id': 'choice', 'selected': ['A'], 'custom': 'also'}]}, 'QUESTION_ANSWER_INVALID')]:
            status, value = self.respond(task, q, **changes)
            self.assert_that(status in (400, 409) and value['code'] == code, code, response=value)
        self.sql("UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';")
        self.assert_that(self.http('GET', '/api/tasks/' + task + '/questions')[0] == 403 and self.respond(task, q)[0] == 403, 'current actor revocation applies immediately')
        self.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice';")
        # Maintenance may have legitimately closed the revoked task; never restore it.
        if self.http('GET', '/api/tasks/' + task)[1]['state'] == 'WAITING_USER':
            self.respond(task, q); self.state(task, 'SUCCEEDED')
        race = self.task('answer-race', [batch(True)]); q = self.question(race); self.stopped_slot(race)
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda i: self.respond(race, q, selected=['A'] if i % 2 else ['B'], key='race-' + str(i)), range(6)))
        self.assert_that(sum(s == 202 for s, _ in results) == 1 and all(s in (202, 409) for s, _ in results), 'two workers and six answers accept one continuation', statuses=[s for s, _ in results])
        self.state(race, 'SUCCEEDED')
        self.assert_that(self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='" + race + "';") == '2', 'one resume attempt despite races')
        cancel = self.task('cancel-question', [batch()]); q = self.question(cancel); self.stopped_slot(cancel)
        self.http('POST', '/api/tasks/' + cancel + '/cancel'); self.state(cancel, 'CANCELLED')
        self.assert_that(self.respond(cancel, q)[1]['code'] == 'QUESTION_CLOSED', 'cancelled question refuses new answer')

    def timed(self):
        self.install('0.2.0')
        task = self.task('timed-late', [batch(timeout=1), batch(), batch()])
        first = self.question(task)
        second = self.question(task, 1)
        self.stopped_slot(task)
        first = self.items(task)[0]
        self.assert_that(first['phase'] == 'CONTINUED' and first['answerable'], 'pending tool still has answerable question')
        original = self.sql("SELECT output_snapshot,output_digest FROM invocation WHERE id='" + first['invocationId'] + "';")
        status, receipt = self.respond(task, first, selected=[], custom='late untrusted')
        self.assert_that(status == 202 and receipt['delivery'] == 'USER_MESSAGE', 'late owner answer accepted as user message')
        self.assert_that(self.items(task)[1]['phase'] == 'OPEN', 'late first answer does not clear second wait')
        self.respond(task, second)
        third = self.question(task, 2); self.respond(task, third); self.state(task, 'SUCCEEDED')
        self.assert_that(original == self.sql("SELECT output_snapshot,output_digest FROM invocation WHERE id='" + first['invocationId'] + "';"), 'original pending tool result immutable')
        requests = [x['body'] for x in self.fixture.requests if x['goal'] == 'timed-late']
        late_counts = [sum(m['role'] == 'user' and 'answer_to_pending_question' in m['content'] for m in r['messages']) for r in requests]
        self.assert_that(late_counts[-2:] == [1, 1], 'late user checkpoint persists across subsequent advisor steps exactly once', counts=late_counts)
        self.assert_that(self.sql("SELECT delivery_state FROM user_question WHERE id='" + first['requestId'] + "';") == 'CHECKPOINTED', 'late answer consumed by persisted prepare')
        final = self.task('late-final-race', [batch(timeout=1)])
        q = self.question(final)
        assert self.fixture.final_entered.wait(40)
        q = self.items(final)[0]
        self.assert_that(self.respond(final, q)[0] == 202, 'answer commits before final response')
        self.fixture.final_release.set()
        self.state(final, 'SUCCEEDED')
        self.assert_that(self.fixture.models['late-final-race'] == 3, 'final/late race resumes same advisor through one durable continuation')
        # -1 remains bounded by absolute task deadline.
        deadline = self.task('blocking-deadline', [batch(timeout=-1)], budget={'deadlineSeconds': 3})
        q = self.question(deadline); self.stopped_slot(deadline)
        self.assert_that(q['foregroundDueAt'] is None, '-1 has no foreground timer')
        self.state(deadline, 'FAILED')
        status, value = self.respond(deadline, q)
        self.assert_that(status == 410 and value['code'] == 'QUESTION_EXPIRED', 'absolute deadline rejects answer and never revives task')

    def recovery(self):
        self.install('0.1.0')
        task = self.task('restart-wait', [batch()]); q = self.question(task); self.stopped_slot(task)
        self.stop(self.control); self.stop(self.worker); self.stop(self.other)
        self.control = self.start('control-restarted', 'control')
        self.assert_that(self.items(task)[0]['requestId'] == q['requestId'], 'control restart preserves durable question identity')
        status,receipt=self.respond(task,q,key='restart-answer')
        assert status==202
        self.stop(self.control)
        self.control=self.start('control-after-answer-commit','control')
        self.assert_that(self.respond(task,q,key='restart-answer')==(200,receipt),'answer receipt canonical digest survives control JVM restart')
        self.worker = self.start('worker-restarted', 'worker')
        self.other = self.start('worker-restarted-two', 'worker')
        self.state(task, 'SUCCEEDED')
        self.assert_that(self.fixture.models['restart-wait'] == 2, 'answer committed without running worker resumes after restart')

    def fence_probe(self, task, original):
        if not hasattr(self,'probe_classpath'):
            classes=self.root/'probe-classes'; classes.mkdir()
            libraries=self.root/'probe-libraries'; libraries.mkdir()
            with zipfile.ZipFile(self.jar) as archive:
                for name in archive.namelist():
                    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                        (libraries/Path(name).name).write_bytes(archive.read(name))
                    elif name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                        dest=classes/name.removeprefix('BOOT-INF/classes/'); dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(archive.read(name))
            source=Path(__file__).resolve().parents[1]/'target/test-classes/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionFenceProbe.class'
            dest=classes/'io/github/lovexiaopeng/jimu/adapters/persistence/QuestionFenceProbe.class';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest)
            for path in [classes,libraries,*classes.rglob('*'),*libraries.iterdir()]: os.chown(path,65534,65534)
            self.probe_classpath=str(classes)+':'+str(libraries/'*')
        tenant,actor,attempt,fence,worker=original.split('\t')
        before=self.sql("SELECT state,fence,next_event_seq,models_used,tools_used FROM task WHERE id='"+task+"';")
        result=subprocess.run(['setpriv','--reuid=65534','--regid=65534','--clear-groups',self.java,'-cp',self.probe_classpath,'io.github.lovexiaopeng.jimu.adapters.persistence.QuestionFenceProbe',str(self.control['folder']/'runtime.properties'),tenant,actor,task,attempt,fence,worker],text=True,capture_output=True,timeout=30)
        (self.evidence/('fence-'+task+'.json')).write_text(json.dumps({'exit':result.returncode,'stdout':result.stdout,'stderr':result.stderr},indent=2))
        self.assert_that(result.returncode==0 and 'PASS old fence' in result.stdout, 'old fence rejected at actual ledger methods', exit=result.returncode, output=result.stdout, error=result.stderr[-1000:])
        self.assert_that(before==self.sql("SELECT state,fence,next_event_seq,models_used,tools_used FROM task WHERE id='"+task+"';"),'stale lease changed no execution fact')

    def faults(self):
        self.stop(self.worker); self.stop(self.other)
        for point in ('M0','M2','QUESTION_OPEN_BEFORE','QUESTION_OPEN_COMMITTED'):
            worker=self.start('fault-'+point,'worker',boundary=point)
            task=self.task('fault-'+point,[batch()])
            self.barrier(worker)
            original=self.sql("SELECT t.tenant_id,t.owner_actor_id,a.id,a.fence,a.worker_id FROM task t JOIN task_attempt a ON a.tenant_id=t.tenant_id AND a.task_id=t.id WHERE t.id='"+task+"' ORDER BY a.fence LIMIT 1;")
            if point=='QUESTION_OPEN_COMMITTED':
                q=self.question(task)
                self.assert_that(self.sql("SELECT quiescent FROM task_attempt WHERE task_id='"+task+"';")=='0','pause commit does not forge quiescence')
                self.assert_that(self.respond(task,q)[0]==202,'answer commits while old writer is still unquiescent')
                replacement=self.start('replacement-'+point,'worker')
                self.assert_that(self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"';")=='1','no new attempt while paused old writer remains active')
                self.fixture.plans['legacy-during-unquiescent-resume']=[]
                status,legacy=self.http('POST','/api/tasks',{'goal':'legacy-during-unquiescent-resume'},key='legacy-during-unquiescent-resume');assert status==202
                self.subjects[legacy['taskId']]='a';self.state(legacy['taskId'],'SUCCEEDED')
                self.assert_that(self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"';")=='1','unquiescent resume does not block unrelated same-tenant legacy task')
                self.stop(worker,kill=True)
            else:
                self.stop(worker,kill=True)
                replacement=self.start('replacement-'+point,'worker')
                q=self.question(task);self.respond(task,q)
            self.state(task,'SUCCEEDED')
            self.assert_that(self.sql("SELECT COUNT(*) FROM user_question WHERE task_id='"+task+"';")=='1' and self.fixture.models['fault-'+point]==2,'crash '+point+' creates one question and no duplicate model response')
            self.fence_probe(task,original)
            self.stop(replacement)
        self.install('0.2.0')
        worker=self.start('fault-late-prepare','worker',boundary='QUESTION_LATE_PREPARED')
        task=self.task('fault-late-prepare',[batch(timeout=1),batch()])
        self.question(task);second=self.question(task,1)
        self.respond(task,self.items(task)[0]);self.respond(task,second)
        self.barrier(worker)
        self.assert_that(self.sql("SELECT delivery_state FROM user_question WHERE task_id='"+task+"' AND ordinal=1;")=='CHECKPOINTED','late answer and checkpoint commit before dispatch')
        self.stop(worker,kill=True)
        self.worker=self.start('recover-late-prepare','worker')
        self.state(task,'SUCCEEDED')
        bodies=[x['body'] for x in self.fixture.requests if x['goal']=='fault-late-prepare']
        self.assert_that(self.fixture.models['fault-late-prepare']==3 and sum(m['role']=='user' and 'answer_to_pending_question' in m['content'] for m in bodies[-1]['messages'])==1,'crash after late prepare recovers exact request once')
        self.other=self.start('recover-late-prepare-two','worker')

    def raw_answer(self,task,q,raw):
        request=urllib.request.Request('http://127.0.0.1:'+str(self.control['port'])+'/api/tasks/'+task+'/questions/'+q['requestId']+'/answers',data=raw,method='POST',headers={'Authorization':'Bearer '+self.token(),'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
        try: response=self.http_client.open(request,timeout=20)
        except urllib.error.HTTPError as error: response=error
        with response: return response.status,json.loads(response.read())

    def revocation_and_due(self):
        task=self.task('install-revocation',[batch()]);q=self.question(task);self.stopped_slot(task)
        self.sql("UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question';")
        self.assert_that(self.http('GET','/api/tasks/'+task+'/questions')[0]==403 and self.respond(task,q)[0]==403,'disabled installation blocks read and answer immediately')
        eventually(lambda:self.sql("SELECT state FROM task WHERE id='"+task+"';")=='FAILED','maintenance closes revoked wait')
        self.sql("UPDATE installation SET enabled=TRUE,epoch=epoch+1 WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question';")
        self.assert_that(self.respond(task,q)[1]['code']=='PLUGIN_PIN_MISMATCH','reinstalled capability does not rebind old task')
        task=self.task('pin-revocation',[batch()]);q=self.question(task);self.stopped_slot(task)
        self.install('0.2.0')
        self.assert_that(self.http('GET','/api/tasks/'+task+'/questions')[1]['code']=='PLUGIN_PIN_MISMATCH' and self.respond(task,q)[1]['code']=='PLUGIN_PIN_MISMATCH','changed pin returns 409 before question content')
        eventually(lambda:self.sql("SELECT state FROM task WHERE id='"+task+"';")=='FAILED','maintenance closes changed pin')
        # Keep actual model loop absent while testing due-before-maintenance POST settlement.
        self.stop(self.worker);self.stop(self.other)
        worker=self.start('due-worker','worker')
        task=self.task('due-before-maintenance',[batch()]);q=self.question(task);self.stopped_slot(task)
        self.stop(worker)
        delta=self.sql("SELECT TIMESTAMPDIFF(SECOND,created_at,foreground_due_at) FROM user_question WHERE task_id='"+task+"';")
        self.assert_that(delta=='120','timed omitted timeout defaults to absolute 120 seconds')
        due=self.sql("SELECT foreground_due_at FROM user_question WHERE task_id='"+task+"';")
        for _ in range(3):self.items(task)
        self.assert_that(due==self.sql("SELECT foreground_due_at FROM user_question WHERE task_id='"+task+"';"),'connections and polling do not extend foreground due')
        self.sql("UPDATE user_question SET foreground_due_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+task+"';")
        status,value=self.respond(task,q)
        self.assert_that(status==409 and value['code']=='QUESTION_REVISION_CONFLICT','POST settles already-due foreground before rejecting old revision')
        refreshed=self.items(task)[0]
        self.assert_that(refreshed['phase']=='CONTINUED' and refreshed['revision']==2,'due settlement commits despite stale-answer rejection')
        status,receipt=self.respond(task,refreshed,key='due-answer')
        self.assert_that(status==202 and receipt['delivery']=='USER_MESSAGE','refreshed late answer is ordinary user delivery')
        self.worker=self.start('due-resumer','worker');self.other=self.start('due-resumer-two','worker')
        self.state(task,'SUCCEEDED')
        self.assert_that(self.fixture.models['due-before-maintenance']==2,'pending replay and late checkpoint need one next model call')
        # Same-key first submissions race under the same task lock.
        task=self.task('same-key-race',[batch(True)]);q=self.question(task);self.stopped_slot(task)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(lambda i:self.respond(task,q,selected=['B','A'] if i%2 else ['A','B'],key='same-key-concurrent'),range(4)))
        self.assert_that(sum(s==202 for s,_ in results)==1 and sum(s==200 for s,_ in results)==3 and all(r==results[0][1] for _,r in results),'concurrent canonical same-key submissions share one receipt',statuses=[s for s,_ in results])
        self.state(task,'SUCCEEDED')
        # This profile stays disabled globally by default; no readiness dependency on a human.
        waiting=self.task('feature-disabled-wait',[batch(timeout=-1)]);waiting_q=self.question(waiting);self.stopped_slot(waiting)
        self.stop(self.worker);self.stop(self.other);self.stop(self.control)
        self.control=self.start('control-questions-disabled','control',extra={'jimu.questions.enabled':'false'})
        eventually(lambda:self.sql("SELECT state FROM task WHERE id='"+waiting+"';")=='FAILED','disabled host closes accepted questions')
        self.assert_that(self.items(waiting)[0]['closeCode']=='QUESTION_FEATURE_DISABLED','feature disable preserves authorized history')
        self.assert_that(self.respond(task,q,selected=['A','B'],key='same-key-concurrent')[0]==200,'feature disabled preserves authorized historical receipt')
        self.assert_that(self.http('GET','/readyz')[0]==200,'feature disabled does not block global readiness')
        self.stop(self.control);self.control=self.start('control-questions-reenabled','control')
        self.worker=self.start('worker-questions-reenabled','worker');self.other=self.start('worker-questions-reenabled-two','worker')
        self.assert_that(self.sql("SELECT state FROM task WHERE id='"+waiting+"';")=='FAILED','reenabling feature never revives closed task')

    def streaming_and_limits(self):
        self.stop(self.worker);self.stop(self.other);self.stop(self.control)
        self.common['jimu.model-stream']='true'
        self.control=self.start('control-stream-questions','control')
        self.worker=self.start('worker-stream-questions','worker');self.other=self.start('worker-stream-questions-two','worker')
        task=self.task('stream-question',[batch()]);q=self.question(task);self.stopped_slot(task)
        self.respond(task,q);self.state(task,'SUCCEEDED')
        self.assert_that(self.fixture.models['stream-question']==2,'real streaming provider pauses and resumes without uncertain tool result')
        for i,value in enumerate([
            {'questions':[{'id':'same','question':'x'},{'id':'same','question':'y'}]},
            {'questions':[{'id':'q','question':'x','options':[{'label':'A'},{'label':'A'}]}]},
            {'questions':[{'id':'q','question':'😀'*1025}]},
            {'questions':[{'id':'q','question':'x'}],'timeout':1.0},
            '{"questions":[],"questions":[{"id":"q","question":"x"}]}',
        ]):
            task=self.task('invalid-model-question-'+str(i),[value])
            self.state(task,'FAILED')
            self.assert_that(self.sql("SELECT code FROM task WHERE id='"+task+"';")=='TOOL_INPUT_INVALID' and self.sql("SELECT COUNT(*) FROM user_question WHERE task_id='"+task+"';")=='0','invalid model question rejected before tool budget and persistence '+str(i))
        task=self.task('tight-tool-budget',[batch(),batch()],budget={'maxToolInvocations':1})
        q=self.question(task);self.respond(task,q);self.state(task,'FAILED')
        self.assert_that(self.sql("SELECT code,tools_used FROM task WHERE id='"+task+"';")=='TOOL_BUDGET_EXCEEDED\t1','resume reads successful slot with exhausted tool count then rejects new dispatch')
        task=self.task('tight-model-budget',[batch()],budget={'maxModelRequests':1})
        q=self.question(task);self.respond(task,q);self.state(task,'FAILED')
        self.assert_that(self.sql("SELECT code,models_used FROM task WHERE id='"+task+"';")=='MODEL_BUDGET_EXCEEDED\t1','answer cannot bypass model request budget on resume')
        task=self.task('batch-limit',[batch()])
        for ordinal in range(8):self.respond(task,self.question(task,ordinal),selected=[])
        self.state(task,'FAILED')
        self.assert_that(self.sql("SELECT code,tools_used FROM task WHERE id='"+task+"';")=='QUESTION_BATCH_LIMIT\t8' and self.sql("SELECT COUNT(*) FROM user_question WHERE task_id='"+task+"';")=='8','eight batches permit repeated question ids by invocation then reject ninth before persistence')
        self.fixture.plans['legacy-unknown-fact']=[{'url':self.fixture.origin+'/effect/legacy-unknown-fact'}]
        status,value=self.http('POST','/api/tasks',{'goal':'legacy-unknown-fact'},key='legacy-unknown-fact');assert status==202
        unknown=value['taskId'];self.subjects[unknown]='a';self.state(unknown,'NEEDS_RECONCILIATION')
        facts=self.sql("SELECT state,input_digest,output_digest,effect_intent_id FROM invocation WHERE task_id='"+unknown+"';")
        reservations=self.sql("SELECT category,state,amount,usage_quality FROM reservation WHERE task_id='"+unknown+"' ORDER BY id;")
        task=self.task('unknown-retention',[batch()]);q=self.question(task);self.stopped_slot(task)
        self.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a','synthetic-unknown-question-cost','"+task+"','MODEL_COST',17,'HELD_UNKNOWN','UNKNOWN');")
        self.respond(task,q);self.state(task,'SUCCEEDED')
        self.sql("UPDATE task SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE id='"+task+"';")
        # A separate eligible task forces this maintenance pass to be observed, without a sleep.
        sentinel=self.task('retention-pass-sentinel',[batch()]);self.respond(sentinel,self.question(sentinel));self.state(sentinel,'SUCCEEDED')
        self.sql("UPDATE task SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE id='"+sentinel+"';")
        eventually(lambda:self.sql("SELECT question_cleanup_state FROM task WHERE id='"+sentinel+"';")=='PURGED','observed maintenance pass with unknown retained',timeout=25)
        self.assert_that(self.sql("SELECT question_cleanup_state FROM task WHERE id='"+task+"';")=='RETAINED' and self.sql("SELECT state FROM reservation WHERE id='"+task+":question-storage';")=='RESERVED','unknown reservation prevents question purge and storage refund')
        self.assert_that(facts==self.sql("SELECT state,input_digest,output_digest,effect_intent_id FROM invocation WHERE task_id='"+unknown+"';") and reservations==self.sql("SELECT category,state,amount,usage_quality FROM reservation WHERE task_id='"+unknown+"' ORDER BY id;"),'question operations preserve unrelated UNKNOWN effect and reservation facts')

    def limits_and_retention(self):
        self.install('0.1.0')
        task=self.task('strict-http',[batch()]);q=self.question(task);self.stopped_slot(task)
        valid={'questionVersion':1,'expectedRevision':1,'answers':[{'id':'choice','selected':[]}]}
        raw=json.dumps(valid).encode()
        bad=[raw+b' {}',raw.replace(b'"questionVersion": 1',b'"questionVersion": 1,"questionVersion":1'),raw.replace(b'"expectedRevision": 1',b'"expectedRevision":1.0'),raw.replace(b'"selected": []',b'"selected":[],"custom":null'),raw.replace(b'"selected": []',b'"selected":[],"answeredBy":"alice"')]
        for payload in bad:
            status,value=self.raw_answer(task,q,payload)
            self.assert_that(status==400 and value['code']=='QUESTION_ANSWER_INVALID','strict HTTP rejects ambiguous/coerced/forged answer',status=status)
        status,value=self.raw_answer(task,q,b' '*32769)
        self.assert_that(status==413 and value['code']=='REQUEST_TOO_LARGE','answer body read is bounded before parsing')
        other=self.task('cross-task-question',[batch()]);otherq=self.question(other)
        self.assert_that(self.respond(other,q)[1]['code']=='QUESTION_NOT_FOUND','question requestId cannot move to another visible task')
        status,receipt=self.respond(task,q,key='same-key');assert status==202
        self.assert_that(self.respond(task,q,key='same-key',selected=['B'])[1]['code']=='IDEMPOTENCY_CONFLICT','same answer key refuses changed content')
        self.assert_that(self.respond(other,otherq,key='same-key')[0]==202,'idempotency keys are task scoped')
        self.state(task,'SUCCEEDED');self.state(other,'SUCCEEDED')
        # Retention reserves full bounded payload space until actual purge, including completed tasks.
        self.sql("UPDATE tenant_budget SET question_tasks_limit=question_tasks_held WHERE tenant_id='tenant-a';")
        status,value=self.http('POST','/api/tasks',{'goal':'capacity-denied','capabilityIds':['ask_user_question']},key='capacity-denied')
        self.assert_that(status==429 and value['code']=='QUESTION_TASK_LIMIT','question task capacity rejects without creating task')
        self.sql("UPDATE tenant_budget SET question_tasks_limit=16,storage_limit=storage_held WHERE tenant_id='tenant-a';")
        status,value=self.http('POST','/api/tasks',{'goal':'storage-denied','capabilityIds':['ask_user_question']},key='storage-denied')
        self.assert_that(status==429 and value['code']=='STORAGE_BUDGET_EXCEEDED','storage capacity rejects without creating task')
        self.sql("UPDATE tenant_budget SET storage_limit=268435456 WHERE tenant_id='tenant-a';")
        # Synthetic clock ageing is confined to the private fixture; every body copy must be removed.
        digests=self.sql("SELECT request_digest,answer_digest FROM user_question WHERE task_id='"+task+"';")
        self.sql("UPDATE task SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE id='"+task+"'; UPDATE user_question SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY WHERE task_id='"+task+"';")
        eventually(lambda:self.sql("SELECT question_cleanup_state FROM task WHERE id='"+task+"';")=='PURGED','retained question payload purge',timeout=25)
        self.assert_that(self.http('GET','/api/tasks/'+task+'/questions')[0]==410 and self.respond(task,q,key='same-key')[0]==410,'purged questions and receipts return 410')
        self.assert_that(digests==self.sql("SELECT request_digest,answer_digest FROM user_question WHERE task_id='"+task+"';"),'purge retains original question/answer digests')
        self.assert_that(self.sql("SELECT state FROM reservation WHERE id='"+task+":question-storage';")=='RELEASED','question storage released only after actual payload purge')

    def cleanup(self):
        self.fixture.final_release.set()
        try:
            for child in reversed(self.children): self.stop({'process': child, 'name': 'question collection'})
            if self.schema_ready:
                (self.evidence / 'user_question.tsv').write_text(self.sql('SELECT * FROM user_question;'), encoding='utf-8')
                (self.evidence / 'question-task.tsv').write_text(self.sql('SELECT tenant_id,id,question_binding,question_binding_digest,waiting_question_id,question_continuation_step_id,question_cleanup_state FROM task;'), encoding='utf-8')
            (self.evidence / 'model-wire.json').write_text(json.dumps(self.fixture.requests, ensure_ascii=False, indent=2), encoding='utf-8')
        finally: Experiment.cleanup(self)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--v15-jar', type=Path, required=True)
    args = parser.parse_args()
    experiment = QuestionsExperiment(args.jar, args.evidence)
    try:
        experiment.prepare()
        experiment.normal()
        experiment.authorization()
        experiment.timed()
        experiment.recovery()
        experiment.faults()
        experiment.limits_and_retention()
        experiment.revocation_and_due()
        experiment.streaming_and_limits()
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally: experiment.cleanup()
    from user_question_migration_verify import verify_migration
    verify_migration(args.jar,args.v15_jar,args.evidence / 'migration',Model)
