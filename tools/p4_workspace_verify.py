"""Selectable P4 verification, owned MySQL + non-root JVMs + actual native search. No paid IO."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tempfile

sys.dont_write_bytecode = True
from p2_verify import Handler, eventually
from p3_business_verify import BusinessExperiment, BusinessTls, EVIDENCE, SOURCE, report_draft


class P4Model(Handler):
    def do_POST(self):
        tenant = next((k for k, v in self.server.credentials.items()
                       if self.headers.get('Authorization') == 'Bearer ' + v), None)
        if tenant is None:
            self.send(401, {'error': 'fixture credential required'})
            return
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        replies = [m for m in body['messages'] if m['role'] == 'tool']
        names = {call['id']: call['function']['name'] for message in body['messages']
                 for call in message.get('tool_calls', [])}
        for reply in replies: reply['name'] = names[reply['tool_call_id']]
        rounds = sum(1 for m in body['messages'] if m['role'] == 'assistant')
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({'goal': goal, 'tenant': tenant, 'round': rounds,
                'tools': [t['function']['name'] for t in body['tools']]})
        draft = report_draft(goal)
        draft['summary'][0]['text'] = '原始结论：本地网页材料摘要。'
        revised = report_draft(goal)
        revised['summary'][0]['text'] = '修订结论：本地网页材料摘要。'
        stages = [
            [('web_fetch', {'url': SOURCE}), ('read', {'file_path': 'report.md'})],
            [('compose_report', {'file_path': 'report.md', 'draft': draft})],
            [('glob', {'pattern': '*.md'}), ('grep', {'pattern': '原始结论'}), ('read', {'file_path': 'report.md'})],
            [('edit', {'file_path': 'report.md', 'old_string': '原始结论', 'new_string': '修订结论'})],
            [('grep', {'pattern': '修订结论'})],
            [('compose_report', {'file_path': 'report.md', 'draft': revised})],
            [('present', {'files': [{'path': 'report.md'}]})],
        ]
        if goal.startswith('B02'):
            stages = stages[:4] + [[('present', {'files': [{'path': 'report.md'}]})]]
        if goal.startswith('pages-'):
            page_replies = [json.loads(m['content']) for m in replies if m['name'] == 'grep']
            self.server.p4_pages[goal] = page_replies
            if rounds == 0:
                stages = [[('grep', {'pattern': 'ROW', 'path': 'data.txt'})]]
            elif rounds == 1:
                cursor = page_replies[0]['nextCursor']
                stages = [[], [('read', {'file_path': 'data.txt'}),
                    ('edit', {'file_path': 'data.txt', 'old_string': 'ROW000', 'new_string': 'CHANGED'}),
                    ('grep', {'pattern': 'ROW', 'path': 'data.txt', 'cursor': cursor})]]
            elif rounds == 2:
                cursor = page_replies[0]['nextCursor']
                stages = [[], [], [('grep', {'pattern': 'ROW', 'path': 'data.txt', 'cursor': cursor}),
                    ('grep', {'pattern': 'ROW', 'path': 'data.txt', 'cursor': page_replies[1]['nextCursor']})]]
            else: stages = []
        if goal.startswith('foreign-'):
            stages = [[('grep', {'pattern': 'ROW', 'path': 'data.txt', 'cursor': self.server.foreign_cursor})]]
        if goal.startswith('reject-query-') or goal.startswith('expired-page-'):
            page_replies = [json.loads(m['content']) for m in replies if m['name'] == 'grep']
            stages = [[('grep', {'pattern': 'ROW', 'path': 'data.txt'})]]
            if rounds == 1:
                stages += [[('grep', {'pattern': 'ROW' if goal.startswith('expired') else 'DIFFERENT',
                    'path': 'data.txt', 'cursor': page_replies[0]['nextCursor']})]]
        if goal.startswith('lifecycle-'):
            stages = [[('grep', {'pattern': 'ROW', 'path': 'data.txt'})]]
        if rounds == 5 and not goal.startswith('B02'):
            actual = json.loads(replies[-1]['content'])
            assert actual['seen'] > 0 and any('修订结论' in m['line'] for m in actual['matches'])
        message = {'role': 'assistant', 'content': 'P4 local deliverable complete'}
        if rounds < len(stages):
            message['content'] = ''
            message['tool_calls'] = [{'id': 'p4_' + str(rounds) + '_' + str(i), 'type': 'function',
                'function': {'name': name, 'arguments': json.dumps(arguments, ensure_ascii=False)}}
                for i, (name, arguments) in enumerate(stages[rounds])]
        self.send(200, {'id': 'controlled-p4', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if 'tool_calls' in message else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


class P4Experiment(BusinessExperiment):
    def prepare_p4(self, bundle):
        self.prepare()
        self.stop(self.control)
        self.fixture.RequestHandlerClass = P4Model
        self.fixture.p4_pages = {}
        self.tls.RequestHandlerClass = BusinessTls
        self.sql("GRANT INSERT ON jimu.native_search_stop TO 'runtime'@'%';")
        # This fixture deliberately returns hundreds of rows through cumulative model history.
        # Give only the private synthetic tenants room for that history; product defaults stay fixed.
        self.sql("UPDATE tenant_budget SET token_limit=8388608;")
        resources = Path(__file__).resolve().parents[1] / 'src/main/resources/plugins/builtin'
        for name in ['edit', 'glob', 'grep']:
            digest = hashlib.sha256((resources / name / 'output.schema.json').read_bytes()).hexdigest()
            for tenant in ['tenant-a', 'tenant-b']:
                self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('" + tenant + "','" + name + "','0.1.0','" + digest + "');")
        search = self.root / 'native'; search.mkdir(mode=0o700)
        self.common.update({'jimu.search.enabled': 'true', 'jimu.search.root': str(search),
            'jimu.search.launcher': bundle['launcher'], 'jimu.search.launcher-sha256': bundle['launcherSha256'],
            'jimu.search.rg': bundle['rg'], 'jimu.search.bubblewrap': bundle['bubblewrap']})
        for path in [self.root, *self.root.rglob('*')]:
            if not path.is_symlink(): os.chown(path, 65534, 65534)
        self.nonroot = True
        self.control = self.start('p4-control', 'control')

    def start(self, name, role, boundary='', model_stream=False, expected_ready=200, extra=None):
        if not getattr(self, 'nonroot', False):
            return super().start(name, role, boundary, model_stream, expected_ready, extra)
        folder = self.root / name
        folder.mkdir(mode=0o700); os.chown(folder, 65534, 65534)
        config = dict(self.common, **{'jimu.role': role})
        config.update(extra or {})
        if role == 'worker': config['fixture.tracker'] = str(folder / 'tracker.log')
        if boundary: config.update({'jimu.test.boundary': boundary, 'jimu.test.barrier': str(folder)})
        properties = folder / 'runtime.properties'
        properties.write_text('\n'.join(k + '=' + v for k, v in config.items()) + '\n')
        os.chmod(properties, 0o600); os.chown(properties, 65534, 65534)
        command = ['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', self.java]
        if role == 'control': command += ['-jar', str(self.jar)]
        else:
            command += ['-Dloader.path=' + str(self.classes),
                '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.runtime.web.LocalTlsWorkerMain',
                '-cp', str(self.jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                '--spring.profiles.active=local-tls-worker']
        command += ['--spring.config.additional-location=file:' + str(properties)]
        logfile = folder / (name + '.log')
        with logfile.open('w') as log: child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        self.children.append(child)
        def started():
            if child.poll() is not None: raise AssertionError(name + ' startup failed\n' + logfile.read_text()[-16000:])
            match = re.search(r'Tomcat started on port (\d+)', logfile.read_text())
            return int(match.group(1)) if match else False
        port = eventually(started, name + ' startup')
        instance = {'process': child, 'port': port, 'folder': folder, 'name': name}
        self.assert_that(self.http('GET', '/readyz', instance=instance)[0] == expected_ready, name + ' readiness')
        status = Path('/proc/' + str(child.pid) + '/status').read_text()
        self.assert_that(re.search(r'^Uid:\s+65534\s+65534\s+65534\s+65534$', status, re.M) is not None,
                         'actual JVM is non-root', role=role, pid=child.pid)
        return instance

    def submit_p4(self, goal):
        caps = ['web_fetch', 'read', 'compose_report', 'present', 'edit', 'glob', 'grep']
        status, view = self.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': caps}, key=goal)
        self.assert_that(status == 202, 'P4 task admitted', response=view)
        return view['taskId']

    def submit_search(self, goal, subject='a'):
        status, view = self.http('POST', '/api/tasks', {'goal': goal,
            'capabilityIds': ['read', 'edit', 'glob', 'grep'],
            'budget': {'maxModelRequests':8,'maxToolInvocations':20}}, key=goal, subject=subject)
        self.assert_that(status == 202, 'search task admitted', response=view)
        return view['taskId']

    def seed(self, task, tenant='tenant-a', files=None):
        fixture = self.root / ('seed-' + task + '.json')
        fixture.write_text(json.dumps(files or {'data.txt': '\n'.join('ROW%03d' % i for i in range(601)) + '\n'}))
        os.chown(fixture,65534,65534)
        command = ['setpriv','--reuid=65534','--regid=65534','--clear-groups',self.java,
            '-Dloader.path=' + str(self.classes),
            '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.storage.WorkspaceSeedScenario',
            '-cp',str(self.jar),'org.springframework.boot.loader.launch.PropertiesLauncher',
            str(self.root / 'workspace'),tenant,task,str(fixture)]
        subprocess.run(command,check=True,timeout=30)

    def verify_pages(self, case):
        goal = 'pages-' + case
        task = self.submit_search(goal); self.seed(task)
        self.sql("UPDATE task SET max_tokens=1048576 WHERE id='" + task + "';")
        worker = self.start(case+'-pages-initial','worker', 'S_COMMITTED')
        self.barrier(worker)
        before = self.sql("SELECT snapshot_digest,result_hash,result_size,cursor_index,process_identity FROM native_search_run WHERE task_id='" + task + "';")
        self.stop(worker,kill=True); self.stop(self.control)
        self.control = self.start(case+'-pages-control-restarted','control')
        self.expire(task)
        worker = self.start(case+'-pages-recovered','worker')
        self.state(task,'FAILED')
        self.assert_that(self.sql("SELECT code FROM task WHERE id='" + task + "';") == 'REPORT_NOT_PUBLISHED', 'tool-only fixture reaches final report guard')
        pages = self.fixture.p4_pages[goal]
        self.assert_that(len(pages) == 4 and pages[1] == pages[2], 'repeated cursor returns identical immutable page after restart')
        lines = [m['line'] for p in [pages[0],pages[1],pages[3]] for m in p['matches']]
        self.assert_that(lines == ['ROW%03d' % i for i in range(601)], 'all 601 matches exactly once in original snapshot order')
        self.assert_that(self.receipts(task)['files']['data.txt']['content'].startswith('CHANGED'), 'source changed between pages')
        self.assert_that(self.sql("SELECT COUNT(*) FROM native_search_run WHERE task_id='" + task + "';") == '1'
            and self.sql("SELECT snapshot_digest,result_hash,result_size,cursor_index,process_identity FROM native_search_run WHERE task_id='" + task + "';") == before,
            'recovery and page reads never start another rg or mutate original result')
        self.assert_that(self.http('GET','/api/tasks/' + task,subject='b')[0] == 404
            and self.http('GET','/api/tasks/' + task,subject='c')[0] == 404,'foreign tenant/actor cannot view root task')
        self.fixture.foreign_cursor = pages[0]['nextCursor']
        self.stop(worker)
        if case == 'S04':
            for subject in ['a','b']:
                foreign = self.submit_search('foreign-' + subject,subject)
                other = self.start('foreign-worker-' + subject,'worker')
                # state() uses actor a, so query tenant b explicitly.
                eventually(lambda: self.http('GET','/api/tasks/' + foreign,subject=subject)[1]['state'] == 'FAILED','foreign cursor rejected')
                self.assert_that(self.sql("SELECT code FROM task WHERE id='" + foreign + "';") == 'SEARCH_CURSOR_INVALID'
                    and self.sql("SELECT COUNT(*) FROM native_search_run WHERE task_id='" + foreign + "';") == '0','foreign cursor never starts rg',subject=subject)
                self.stop(other)
            mismatch=self.submit_search('reject-query-mismatch');self.seed(mismatch)
            other=self.start('query-mismatch','worker');self.state(mismatch,'FAILED');self.stop(other)
            self.assert_that(self.sql("SELECT code FROM task WHERE id='"+mismatch+"';")=='SEARCH_CURSOR_INVALID','cursor binds normalized query and tool pin')
        else:
            expired=self.submit_search('expired-page-now');self.seed(expired)
            other=self.start('expired-page-worker','worker','S_COMMITTED');self.barrier(other)
            self.sql("UPDATE native_search_run SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='"+expired+"';")
            (other['folder']/'release').touch();self.state(expired,'FAILED');self.stop(other)
            self.assert_that(self.sql("SELECT code FROM task WHERE id='"+expired+"';")=='SEARCH_CURSOR_INVALID','expired cursor rejects without re-search')
        (self.evidence / (case+'-pages.json')).write_text(json.dumps(pages,ensure_ascii=False))

    def verify_lifecycle(self, case):
        points = ['S_SNAPSHOT','S_BEFORE_LAUNCH','S_GATE','S_BEFORE_RESULT','S_COMMITTED'] if case == 'L01' else ['worker','worker-before-identity','launcher','bubblewrap']
        for index, point in enumerate(points):
            goal='lifecycle-'+case+'-'+point
            task=self.submit_search(goal);self.seed(task)
            boundary=point if case=='L01' else ('S_IDENTITY' if point=='worker-before-identity' else 'S_GATE')
            worker=self.start(case+'-life-'+str(index),'worker',boundary)
            self.barrier(worker)
            before=self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"' AND state='SUCCEEDED';")
            runs=self.sql("SELECT process_identity FROM native_search_run WHERE task_id='"+task+"';")
            identity=json.loads(runs) if runs and runs!='NULL' else None
            if case=='L01':
                self.http('POST','/api/tasks/'+task+'/cancel')
            elif point.startswith('worker'):
                if point=='worker-before-identity':
                    self.assert_that(identity is None,'crash precedes SQL process identity')
                    directory=self.sql("SELECT runtime_directory FROM native_search_run WHERE task_id='"+task+"';")
                    identity=json.loads((Path(directory)/'started.json').read_text())['identity']
                self.stop(worker,kill=True);self.expire(task)
                # Claim the expired uncertain effect through the existing worker loop.
                replacement=self.start(case+'-life-recovery-'+str(index),'worker')
            else:
                os.kill(identity[point]['pid'],9)
                self.http('POST','/api/tasks/'+task+'/cancel')
            if worker['process'].poll() is None: (worker['folder']/'release').touch()
            if case=='L01' and point=='S_COMMITTED':
                self.state(task,'CANCELLED')
            else:
                eventually(lambda: self.http('GET','/api/tasks/'+task)[1]['state'] in ['CANCELLED','NEEDS_RECONCILIATION'],'lifecycle terminal')
            eventually(lambda: self.sql("SELECT COUNT(*) FROM native_search_run WHERE task_id='"+task+"' AND phase<>'STOPPED';")=='0','native identities proven stopped')
            self.stop(worker)
            if case=='L02' and point.startswith('worker'): self.stop(replacement)
            eventually(lambda: self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"' AND quiescent=FALSE;")=='0','native attempt physically quiescent')
            self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"' AND state='SUCCEEDED';")==before,'cancel/kill never commits late native result',point=point)
            if identity:
                for name, process in identity.items():
                    path=Path('/proc/'+str(process['pid'])+'/stat')
                    if path.exists():
                        fields=path.read_text().rsplit(') ',1)[1].split()
                        self.assert_that(int(fields[19])!=process['startTicks'] or fields[0] in ['Z','X'],'native identity actually exited',kind=name,pid=process['pid'])
            self.assert_that(self.fixture.models.get(goal)==1,'no subsequent model request after cancel/kill',point=point)
            (self.evidence/(case+'-'+point+'.json')).write_text(json.dumps({'identity':identity,'phase':point,'taskId':task}))
        if case == 'L01':
            task=self.submit_search('lifecycle-deadline');self.seed(task)
            worker=self.start('deadline-gate','worker','S_GATE');self.barrier(worker)
            self.sql("UPDATE task SET deadline=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
            (worker['folder']/'release').touch()
            eventually(lambda:self.http('GET','/api/tasks/'+task)[1]['state'] in ['FAILED','NEEDS_RECONCILIATION'],'deadline terminates search')
            eventually(lambda:self.sql("SELECT COUNT(*) FROM native_search_run WHERE task_id='"+task+"' AND phase<>'STOPPED';")=='0','deadline proves native tree stopped')
            self.stop(worker)
            self.assert_that(self.fixture.models.get('lifecycle-deadline')==1 and self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"' AND state='SUCCEEDED';")=='0','deadline prevents late native success and next model')
        else:
            task=self.submit_search('lifecycle-stale-fence');self.seed(task)
            old=self.start('stale-result','worker','S_BEFORE_RESULT');self.barrier(old)
            fence=self.sql("SELECT fence FROM task WHERE id='"+task+"';")
            self.expire(task)
            fresh=self.start('stale-recovery','worker')
            eventually(lambda:self.sql("SELECT fence FROM task WHERE id='"+task+"';")!=fence,'new attempt advances fence')
            (old['folder']/'release').touch()
            eventually(lambda:self.http('GET','/api/tasks/'+task)[1]['state']=='NEEDS_RECONCILIATION','uncertain old search stays reconciliable')
            self.stop(old);self.stop(fresh)
            self.assert_that(self.fixture.models.get('lifecycle-stale-fence')==1 and self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"' AND state='SUCCEEDED';")=='0','old fence cannot publish saved result or dispatch next model')

    def verify_versioning(self):
        from p4_legacy_verify import verify_legacy
        verify_legacy(self.jar,self.classes,self.java,self.evidence/'legacy-worker')
        enabled=self.http('GET','/api/capabilities')[1]
        disabled=self.start('native-disabled','control',extra={'jimu.search.enabled':'false'})
        without=self.http('GET','/api/capabilities',instance=disabled)[1]
        self.assert_that(all(name in json.dumps(enabled) for name in ['edit','glob','grep'])
            and all(name not in json.dumps(without) for name in ['"glob"','"grep"']),
            'disabled search is not advertised; enabled catalog contains all three tools')
        status,body=self.http('POST','/api/tasks',{'goal':'disabled-search','capabilityIds':['grep']},key='disabled-search',instance=disabled)
        self.assert_that(status==503 and body['code']=='SEARCH_NOT_CONFIGURED','disabled search rejects admission without native launch')
        self.stop(disabled)
        for name,extra in [('wrong-bundle',{'jimu.search.launcher-sha256':'0'*64}),
                           ('missing-bundle',{'jimu.search.rg':str(self.root/'missing-rg')})]:
            try:
                self.start(name,'worker',extra=extra)
                raise AssertionError('invalid native assembly became ready')
            except AssertionError as error:
                self.assert_that('startup failed' in str(error),'invalid native assembly fails readiness without host fallback',variant=name)
        (self.evidence/'V01-catalog.json').write_text(json.dumps({'enabled':enabled,'disabled':without}))

    def cleanup(self):
        if self.schema_ready:
            for table in ['native_search_run','native_search_stop']:
                (self.evidence/(table+'.tsv')).write_text(self.sql('SELECT * FROM '+table+';'))
        super().cleanup()

    def verify_business(self, case):
        worker = self.start('p4-worker-' + case, 'worker')
        task = self.submit_p4(case + '-nonroot-native-report')
        view = self.state(task, 'SUCCEEDED' if case == 'B01' else 'FAILED')
        state = self.receipts(task)
        self.assert_that(state['format'] == 3 and state['files']['report.md']['modifiedAt'] > 0,
                         'new pins initialize format3 before first write')
        edits = self.sql("SELECT output_snapshot FROM invocation WHERE task_id='" + task + "' AND tool='edit' AND state='SUCCEEDED';")
        self.assert_that(json.loads(edits)['version'] == 2, 'literal edit actually committed v2')
        self.assert_that('修订结论' in state['files']['report.md']['content'], 'edited Unicode text is durable')
        self.assert_that(self.sql("SELECT COUNT(*) FROM native_search_run WHERE task_id='" + task + "' AND phase='STOPPED' AND process_identity IS NOT NULL;") == ('3' if case == 'B01' else '2'),
                         'all actual native trees have durable stopped identities')
        if case == 'B02':
            self.assert_that(view['code'] == 'REPORT_COMPOSITION_REQUIRED' and not view['artifacts'],
                             'edit cannot publish a stale composition receipt', view=view)
        else:
            self.assert_that(len(view['artifacts']) == 1, 'only final recomposed report is published')
            artifact = view['artifacts'][0]
            status, body = self.http('GET', '/api/artifacts/' + artifact['artifactId'] + '/download')
            self.assert_that(status == 200 and '修订结论' in body and '原始结论' not in body
                and hashlib.sha256(body.encode()).hexdigest() == artifact['sha256']
                and len(body.encode()) == artifact['size'], 'edited text reaches exact immutable authorized download')
            for subject in ['b', 'c']:
                self.assert_that(self.http('GET', '/api/artifacts/' + artifact['artifactId'] + '/download', subject=subject)[0] == 404,
                                 'cross-tenant/actor download denied', subject=subject)
            (self.evidence / 'B01-final.md').write_text(body, encoding='utf-8')
            self.assert_that(state['files']['report.md']['version'] == 3, 'recompose uses edited observed version')
        self.stop(worker)
        eventually(lambda: self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='" + task + "' AND quiescent=FALSE;") == '0', 'attempt physically quiescent')
        for table in ['native_search_run', 'native_search_stop']:
            (self.evidence / (case + '-' + table + '.tsv')).write_text(self.sql('SELECT * FROM ' + table + ';'))


def local_case(args, bundle):
    args.evidence.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='jimu-p4-'))
    receipt = {'case': args.case, 'jarSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
               'bundle': bundle, 'uid': 65534, 'root': str(root)}
    try:
        jar = root / 'jimu.jar'; shutil.copyfile(args.jar, jar)
        classes = root / 'classes'; shutil.copytree(args.test_classes, classes)
        workspace = root / 'workspace'; workspace.mkdir(mode=0o700)
        evidence = root / 'evidence'; evidence.mkdir(mode=0o700)
        java = os.environ.get('JIMU_IT_JAVA') or shutil.which('java')
        receipt['java'] = subprocess.run([java, '-version'], text=True, capture_output=True, check=True).stderr
        receipt['filesystem'] = subprocess.run(['findmnt', '-n', '-o', 'FSTYPE', '-T', str(root)], text=True, capture_output=True, check=True).stdout.strip()
        command = ['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', java,
                   '-Dloader.path=' + str(classes)]
        if args.case.startswith('E'):
            command += ['-Dloader.main=io.github.lovexiaopeng.jimu.adapters.storage.WorkspaceEditScenario',
                '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                str(workspace), str(evidence), args.case, str(jar), str(classes)]
        else:
            command += ['-Dloader.main=io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchScenario',
                '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                str(workspace), bundle['launcher'], bundle['launcherSha256'], bundle['rg'], bundle['bubblewrap'],
                args.case, str(evidence)]
        for path in [root, *root.rglob('*')]: os.chown(path, 65534, 65534)
        receipt['command'] = command
        with (args.evidence / 'scenario.log').open('w') as log:
            child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            try: receipt['exit'] = child.wait(timeout=150)
            finally:
                if child.poll() is None: child.kill(); child.wait()
        shutil.copytree(evidence, args.evidence, dirs_exist_ok=True)
        print((args.evidence / 'scenario.log').read_text(), flush=True)
        assert receipt['exit'] == 0, args.case + ' failed'
    finally:
        shutil.rmtree(root)
        receipt['nativeRootRemoved'] = not root.exists()
        (args.evidence / 'cleanup.json').write_text(json.dumps(receipt, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', required=True, choices=['ALL','E01', 'E02', 'E03', 'E04', 'S01', 'S02', 'S03', 'S04', 'S05', 'S06', 'L01', 'L02', 'V01', 'B01', 'B02'])
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--test-classes', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    assert sys.platform == 'linux' and os.geteuid() == 0, 'driver owns private MySQL; product JVMs run as nobody'
    bundle = json.loads(args.bundle.read_text())
    if args.case == 'ALL':
        original=args.evidence
        for case in ['E01','E02','E03','E04','S01','S02','S03','S06']:
            args.case=case;args.evidence=original/case;local_case(args,bundle)
        e=P4Experiment(args.jar,original/'persistent',args.test_classes)
        try:
            e.prepare_p4(bundle)
            e.verify_business('B01');e.verify_business('B02')
            e.verify_pages('S05');e.verify_pages('S04')
            e.verify_lifecycle('L01');e.verify_lifecycle('L02');e.verify_versioning()
        finally:e.cleanup()
        return
    if args.case.startswith('E') or args.case in ['S01', 'S02', 'S03', 'S06']:
        local_case(args, bundle)
        return
    e = P4Experiment(args.jar, args.evidence, args.test_classes)
    try:
        e.prepare_p4(bundle)
        if args.case in ['S04','S05']: e.verify_pages(args.case)
        elif args.case in ['L01','L02']: e.verify_lifecycle(args.case)
        elif args.case == 'V01': e.verify_versioning()
        else: e.verify_business(args.case)
    finally:
        e.cleanup()


if __name__ == '__main__': main()
