"""Normal business admission on owned MySQL, local model + TLS only; no real configuration."""
import copy
import concurrent.futures
import hashlib
import http.server
import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading
import urllib.request

sys.dont_write_bytecode = True

from p3_model_verify import config
from p3_report_verify import ReportExperiment
from p3_evaluation_plan_verify import canonical

EVIDENCE = '中文本地证据：文档说明接口 https://api.example.test/anthropic，支持普通及流式调用；本页仅为隔离替身。'
SOURCE = 'https://fixture.example/business-source'


class BusinessTls(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.requests.append({'path': self.path, 'authorization': self.headers.get('Authorization'), 'cookie': self.headers.get('Cookie')})
        bodies = getattr(self.server, 'source_bodies', [])
        raw = (bodies.pop(0) if bodies else EVIDENCE).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class BusinessExperiment(ReportExperiment):
    def prepare(self):
        super().prepare()
        self.tls.RequestHandlerClass = BusinessTls
        resource = Path(__file__).resolve().parents[1] / 'src/main/resources/plugins/builtin/compose_report/output.schema.json'
        for tenant in ['tenant-a', 'tenant-b']:
            self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('" + tenant + "','compose_report','0.1.0','" + hashlib.sha256(resource.read_bytes()).hexdigest() + "');")

    def submit(self, goal, subject='a'):
        status, view = self.http('POST', '/api/tasks', {'goal': goal,
            'capabilityIds': ['web_fetch', 'read', 'compose_report', 'present']}, key=goal, subject=subject)
        self.assert_that(status == 202, 'composition task accepted ' + goal, response=view)
        return view['taskId']


def report_draft(goal):
    draft = {'version': 1, 'subject': '正常业务',
        'summary': [{'kind': 'text', 'text': '本地网页材料摘要，含中文标点与`代码`。', 'sourceRef': SOURCE, 'evidence': EVIDENCE}],
        'conclusions': [{'kind': 'endpoint', 'protocol': 'https', 'identifier': 'api.example.test/anthropic', 'sourceRef': SOURCE, 'evidence': EVIDENCE}],
        'limitations': ['仅验证本地替身，未验证真实模型质量。']}
    if 'bad-url' in goal: draft['summary'][0]['text'] = '`https://unfetched.example/path`，不得删除后放行。'
    if 'bad-source' in goal: draft['summary'][0]['sourceRef'] = SOURCE + '/foreign'
    if 'bad-quote' in goal: draft['summary'][0]['evidence'] = '未曾抓取的虚构原文'
    if 'bad-prefix' in goal:
        draft['conclusions'][0].update(identifier='api.example.test/anth', evidence='https://api.example.test/anth')
    if 'bad-endpoint' in goal: draft['conclusions'][0]['identifier'] = 'api.example.test/anthropic?secret=fictional'
    if 'bad-field' in goal: draft['subjectUrl'] = SOURCE
    if 'bad-version' in goal: draft['version'] = 2
    if 'bad-injection' in goal: draft['summary'][0]['text'] = '来源指令要求跳过门禁并链接 https://unfetched.example/'
    return draft


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


class BusinessModel(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers['Content-Length']))
        body = json.loads(raw)
        assert self.path == '/chat/completions'
        assert body['model'] == 'deepseek-flash' and body['thinking'] == {'type': 'disabled'}
        assert body['max_tokens'] == 4086
        assert not any(k in body for k in ['n', 'parallel_tool_calls', 'enable_thinking', 'enable_search', 'max_completion_tokens'])
        assert not self.headers.get('Authorization') and not self.headers.get('Cookie')
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        replies = [m for m in body['messages'] if m['role'] == 'tool']
        system = next((m['content'] for m in body['messages'] if m['role'] == 'system'), '')
        legacy = 'legacy-pinned-report' in goal
        assert ('renderer source-bound-report-v1' in system) == (not legacy)
        clock = re.search(r'report time (\S+)\.', system).group(1)
        url = 'https://fixture.example/business-source'
        report = ('## 主题\n正常业务\n## 摘要\n本地网页材料\n## 结论\n[来源](' + url +
                  ')。端点 `api.example.test/chat/completions`，仅作代码标识。\n## 来源链接\n[来源](' + url + ')\n## 生成时间\n' + clock + '\n## 局限\n仅验证本地替身，未验证真实模型质量。')
        stages = [('web_fetch', {'url': url}), ('read', {'file_path': 'report.md'}),
                  ('compose_report', {'file_path': 'report.md', 'draft': report_draft(goal)}),
                  ('present', {'files': [{'path': 'report.md'}]})]
        if legacy: stages[2] = ('write', {'file_path': 'report.md', 'content': report})
        if 'unobserved-compose' in goal:
            stages = [stages[0], stages[2]]
        elif 'ambiguous-source' in goal:
            stages.insert(1, stages[0])
        elif 'unauthorized-write' in goal:
            stages[2] = ('write', {'file_path': 'report.md', 'content': report})
        message = {'content': '正常业务报告已交付' if len(replies) == 4 else ''}
        if len(replies) < len(stages):
            name, arguments = stages[len(replies)]
            message['tool_calls'] = [{'id': 'business_' + str(len(replies)), 'type': 'function',
                'function': {'name': name, 'arguments': canonical(arguments)}}]
            if 'bad-duplicate' in goal and name == 'compose_report':
                message['tool_calls'][0]['function']['arguments'] = canonical(arguments).replace('"version":1', '"version":1,"version":1')
            elif 'bad-empty' in goal and name == 'compose_report':
                message['tool_calls'][0]['function']['arguments'] = ' '
            elif 'bad-oversize' in goal and name == 'compose_report':
                message['tool_calls'][0]['function']['arguments'] = ' ' * 65537
        usage = {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}
        if 'unknown' in goal:
            usage = {'prompt_tokens': 100}
        elif 'over-bound' in goal:
            usage = {'prompt_tokens': 100, 'completion_tokens': 4097, 'total_tokens': 4197}
        reason = 'tool_calls' if 'tool_calls' in message else 'stop'
        if body['stream']:
            assert body['stream_options'] == {'include_usage': True}
            delta = copy.deepcopy(message)
            for index, call in enumerate(delta.get('tool_calls', [])):
                call['index'] = index
            # DeepSeek may place usage on its final NONEMPTY choice, not a separate empty chunk.
            chunk = {'id': 'local-business', 'model': body['model'], 'usage': usage,
                     'choices': [{'index': 0, 'delta': delta, 'finish_reason': reason}]}
            response = ('data: ' + canonical(chunk) + '\n\ndata: [DONE]\n\n').encode()
        else:
            response = canonical({'id': 'local-business', 'model': body['model'], 'usage': usage,
                'choices': [{'index': 0, 'message': message, 'finish_reason': reason}]}).encode()
        with self.server.lock:
            index = len(self.server.calls)
            self.server.calls.append({'goal': goal, 'stream': body['stream'], 'turn': len(replies) + 1,
                'requestSha256': hashlib.sha256(raw).hexdigest(), 'responseSha256': hashlib.sha256(response).hexdigest(),
                'syntheticUsage': usage})
            (self.server.evidence / ('request-%04d.bin' % index)).write_bytes(raw)
            (self.server.evidence / ('response-%04d.bin' % index)).write_bytes(response)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream' if body['stream'] else 'application/json')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


def verify(e):
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), BusinessModel)
    server.daemon_threads = True
    server.origin = 'http://127.0.0.1:' + str(server.server_port)
    server.calls, server.lock, server.evidence = [], threading.Lock(), e.evidence
    listener = threading.Thread(target=server.serve_forever)
    listener.start()
    serial = 0
    plan = json.loads((Path(__file__).resolve().parents[1] / 'src/main/resources/evaluation/qwen-main-v1.json').read_text())

    def unique(prefix):
        nonlocal serial
        serial += 1
        return prefix + '-' + str(serial)

    def sqljson(obj):
        return 'CONVERT(0x' + canonical(obj).encode().hex() + ' USING utf8mb4)'

    def private(cfg):
        path = e.root / (unique('business') + '.properties')
        path.write_text('\n'.join(k + '=' + str(v) for k, v in dict(e.common, **cfg).items()) + '\n')
        path.chmod(0o600)
        return path

    def helper(action, cfg, *args):
        command = [e.java, '-Dloader.path=' + str(e.classes),
            '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ExecutionScopeTestMain',
            '-cp', str(e.jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
            action, str(private(cfg)), str(e.root / 'unused'), *map(str, args)]
        result = subprocess.run(command, text=True, capture_output=True, timeout=120)
        (e.evidence / (unique(action) + '-result.json')).write_text(json.dumps({
            'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}, ensure_ascii=False))
        if result.returncode != 0: raise AssertionError('Local synthetic helper failed: ' + action)
        output = result.stdout.strip()
        (e.evidence / (unique(action) + '.txt')).write_text(output)
        return output.splitlines()[-1]

    def flatten(prefix, obj):
        result = {}
        for key, value in obj.items():
            if isinstance(value, dict):
                result.update(flatten(prefix + key + '.', value))
            elif value is not None:
                result[prefix + key] = str(value).lower() if isinstance(value, bool) else str(value)
        return result

    def provision(full_context=False, input_rate=2000000):
        run_id = unique('original-run')
        e.sql("INSERT INTO evaluation_budget(tenant_id,id,policy_version,max_requests,max_cost_micros,cost_held,requests_used,plan_snapshot,plan_digest) VALUES('tenant-a','" + run_id + "',2,63,500000000,7000,2," + sqljson(plan) + ",'" + digest(plan) + "');")
        approval = {'activeCandidateOnly': 'qwen3.7-plus-2026-05-26', 'fixtureOnly': True}
        anchor = {'version': 1, 'issueId': 'XIAO-40-private-business', 'instanceUuid': e.sql('SELECT @@server_uuid;'),
                  'schema': 'jimu', 'tenantId': 'tenant-a', 'runId': run_id, 'approvalDigest': digest(approval)}
        e.sql("DELETE FROM jimu_provisioning_receipt; INSERT INTO jimu_provisioning_receipt VALUES(1,'" + anchor['issueId'] + "','" + anchor['instanceUuid'] + "','tenant-a','" + run_id + "'," + sqljson(approval) + ",'" + digest(approval) + "','" + digest(plan) + "','" + plan['materialsDigest'] + "');")
        cfg = config({'a': server, 'b': server}, paid=True, run_id=run_id)
        # Production retains frozen evaluation metadata beside BUSINESS authority.
        # Its presence must not select the frozen report instruction for a new business task.
        cfg.update({'jimu.model.evaluation-plan-ref': 'qwen-main-v1',
                    'jimu.model.evaluation-phase': 'A', 'jimu.model.evaluation-sample': 'W1'})
        cfg['jimu.execution-scope.requirement'] = canonical(anchor)
        for name in ['a', 'b']:
            prefix = 'jimu.model.profiles.' + name + '.'
            cfg.update({prefix + k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in {
                'adapter': 'deepseek-nonthinking-v1', 'base-url': server.origin, 'model': 'deepseek-flash',
                'revision': 'DeepSeek-V4.1-Flash-1017', 'config-version': 1 if name == 'a' else 2,
                'max-output-tokens': 4096, 'output-limit-field': 'max_tokens', 'stream': name == 'b',
                'capabilities.stream-text': True, 'capabilities.stream-tools': True, 'capabilities.usage-stream': True,
                'capabilities.evidence': 'deepseek-nonthinking-v1'}.items()})
            if full_context:
                # Documented production-sized envelope with synthetic local billing only.
                cfg.update({prefix + key: str(value) for key, value in {
                    'capabilities.context-limit': 1000000, 'price.input-token-upper': 1000000,
                    'price.input-micros-per-million': input_rate,
                    'price.output-micros-per-million': 8000000, 'price.fixed-micros': 0,
                    'price.bound-evidence': 'local-full-context-budget-regression'}.items()})
        pins = json.loads(helper('business-pins', cfg))
        policies = {name: {'version': 1, 'id': name, 'mode': 'BUSINESS', 'anchor': anchor,
                    'instruction-digest': hashlib.sha256(('member business instruction ' + name).encode()).hexdigest(),
                    'profile-digests': pins, 'request-strategy': 'deepseek-nonthinking-v1', 'local-only': True} for name in ['v1', 'v2']}
        cfg.update(flatten('jimu.business.', {'current-ref': 'v1', 'policies': policies}))
        return run_id, cfg

    def control(cfg):
        e.stop(e.control)
        e.control = e.start(unique('business-control'), 'control', extra=cfg)

    def worker(cfg, point=''):
        return e.start(unique('business-worker'), 'worker', point, extra=cfg)

    def totals():
        return e.sql("SELECT (SELECT COUNT(*) FROM task),(SELECT COUNT(*) FROM reservation),(SELECT COUNT(*) FROM model_dispatch),(SELECT COUNT(*) FROM invocation),(SELECT COUNT(*) FROM artifact),SUM(requests_used),SUM(cost_held) FROM evaluation_budget;")

    def denied(cfg, label, goal=None, extra=None, subject='a'):
        control(cfg)
        before, count = totals(), len(server.calls)
        status, view = e.http('POST', '/api/tasks', dict({'goal': goal or label, 'capabilityIds': ['web_fetch', 'read', 'compose_report', 'present']}, **(extra or {})), key=unique('refused'), subject=subject)
        e.assert_that(status in [400, 409, 503] and before == totals() and count == len(server.calls),
                      'rejected before reservation/HTTP: ' + label, status=status, code=view.get('code'))

    try:
        legacy_task = e.submit('business-v9-legacy-migration-control')
        legacy_before = totals()
        status, view = e.http('POST', '/api/tasks', {'goal': 'legacy-over-budget',
            'budget': {'maxCostMicros': 1000001}}, key='legacy-over-budget')
        e.assert_that(status == 400 and view['code'] == 'INVALID_BUDGET' and legacy_before == totals(),
                      'ordinary mode retains HTTP one-yuan ceiling before task reservation')
        e.assert_that(e.sql("SELECT record_version,max_tokens,max_cost_micros FROM task WHERE id='" + legacy_task + "';") == '1\t65536\t1000000',
                      'ordinary task retains original token/cost defaults')
        e.http('POST', '/api/tasks/' + legacy_task + '/cancel', {})
        legacy_unfinished = e.submit('business-v9-unfinished-migration-control')
        e.stop(e.control)
        e.sql("CREATE TABLE jimu_provisioning_receipt(record_version INT,issue_id VARCHAR(64),instance_uuid VARCHAR(64),tenant_id VARCHAR(64),run_id VARCHAR(64),approval_snapshot LONGTEXT,approval_digest CHAR(64),plan_digest CHAR(64),materials_digest CHAR(64)); UPDATE tenant_budget SET model_limit=1000,token_limit=100000000,cost_limit=1000000000;")
        # Forward migration: preserve every existing task/ledger byte; nullable columns never backfill.
        before = e.sql('SELECT tenant_id,id,request_digest,model_digest,tools_digest FROM task; SELECT * FROM evaluation_budget; SELECT * FROM reservation;')
        (e.evidence / 'migration-v9-before.utf8').write_bytes(before.encode())
        e.sql("ALTER TABLE task DROP COLUMN business_policy_snapshot,DROP COLUMN business_policy_digest; DELETE FROM flyway_schema_history WHERE version='10';")
        e.control = e.start(unique('v10-upgrade'), 'control')
        after = e.sql('SELECT tenant_id,id,request_digest,model_digest,tools_digest FROM task; SELECT * FROM evaluation_budget; SELECT * FROM reservation;')
        (e.evidence / 'migration-v10-after.utf8').write_bytes(after.encode())
        e.assert_that(before == after and e.sql('SELECT COUNT(*) FROM task WHERE business_policy_snapshot IS NOT NULL;') == '0', 'V10 forward migration changes structure only')
        # Private fixture permits the full-context bound; production tenant budgets stay intact.
        e.sql("UPDATE tenant_budget SET token_limit=16777216,cost_limit=500000000 WHERE tenant_id='tenant-a';")
        for stream in [False, True]:
            run_id, cfg = provision(full_context=True)
            cfg['jimu.model.default-profile-ref'] = 'b' if stream else 'a'
            control(cfg)
            goal = unique('normal-report')
            task = e.submit(goal)
            limits = 'max_models,max_tokens,max_cost_micros'
            pinned = e.sql("SELECT HEX(business_policy_snapshot),business_policy_digest,model_digest,tools_digest," + limits + " FROM task WHERE id='" + task + "';")
            actual_limits = e.sql('SELECT ' + limits + " FROM task WHERE id='" + task + "';")
            e.assert_that(actual_limits == '8\t8032768\t16262144',
                          'full-context default = eight conservative rounds; output margin never lowers reservation',
                          stream=stream, actualLimits=actual_limits)
            body = {'goal': goal, 'capabilityIds': ['web_fetch', 'read', 'compose_report', 'present']}
            before = totals()
            with concurrent.futures.ThreadPoolExecutor(6) as pool:
                retries = list(pool.map(lambda _: e.http('POST', '/api/tasks', body, key=goal), range(6)))
            e.assert_that(all(s == 202 and v['taskId'] == task for s, v in retries) and before == totals(),
                          'concurrent idempotent BUSINESS submission creates no extra quota or task', stream=stream)
            # Current-step results are committed but next request not prepared: recovery must allow it.
            old = worker(cfg, 'T1')
            e.barrier(old); e.stop(old, kill=True); e.expire(task)
            switched = dict(cfg, **{'jimu.model.default-profile-ref': 'a' if stream else 'b', 'jimu.business.current-ref': 'v2'})
            control(switched)
            new = worker(switched)
            view = e.state(task, 'SUCCEEDED'); e.stop(new)
            e.assert_that(e.sql("SELECT record_version,business_policy_snapshot,business_policy_digest,evaluation_run_id,code FROM task WHERE id='" + legacy_unfinished + "';") == '1\tNULL\tNULL\tNULL\tEVALUATION_SCOPE_LEGACY_UNBOUND', 'old unfinished task never acquires BUSINESS policy or original run')
            e.assert_that(pinned == e.sql("SELECT HEX(business_policy_snapshot),business_policy_digest,model_digest,tools_digest," + limits + " FROM task WHERE id='" + task + "';"), 'restart/default/policy change retains original task and budget bytes', stream=stream)
            before = totals()
            e.assert_that(e.http('POST', '/api/tasks', body, key=goal)[1]['taskId'] == task and before == totals(),
                          'finished-task HTTP retry after configuration change does not derive a new budget', stream=stream)
            calls = [c for c in server.calls if c['goal'] == goal]
            e.assert_that(len(calls) == 5 and all(c['stream'] == stream for c in calls), 'normal/SSE final usage + tool history replay without extra HTTP', stream=stream)
            aid = view['artifacts'][0]['artifactId']
            downloaded = e.http('GET', '/api/artifacts/' + aid + '/download')
            e.assert_that(downloaded[0] == 200 and '正常业务' in downloaded[1], 'real present authorized download', stream=stream)
            request = urllib.request.Request('http://127.0.0.1:' + str(e.control['port']) + '/api/artifacts/' + aid + '/download', headers={'Authorization': 'Bearer ' + e.token()})
            with e.http_client.open(request, timeout=35) as response:
                raw = response.read()
            (e.evidence / ('download-' + task + '.bin')).write_bytes(raw)
            e.assert_that(hashlib.sha256(raw).hexdigest() == view['artifacts'][0]['sha256'], 'download raw bytes match committed artifact digest', stream=stream)
            receipt = json.loads(e.sql("SELECT output_snapshot FROM invocation WHERE task_id='" + task + "' AND tool='compose_report' AND state='SUCCEEDED';"))
            (e.evidence / ('composition-' + task + '.json')).write_text(canonical(receipt), encoding='utf-8')
            source_id, source_hash = e.sql("SELECT id,output_digest FROM invocation WHERE task_id='" + task + "' AND tool='web_fetch' AND state='SUCCEEDED';").split('\t')
            e.assert_that(receipt['version'] == 1 and receipt['rendererVersion'] == 'source-bound-report-v1'
                and receipt['file']['sha256'] == hashlib.sha256(raw).hexdigest() and receipt['file']['size'] == len(raw)
                and all(b['invocationId'] == source_id and b['outputDigest'] == source_hash and b['sourceUrl'] == SOURCE for b in receipt['bindings'])
                and set(re.findall(r'https?://[^\s<>\[\]()]+', raw.decode())) == {SOURCE}
                and raw.decode().count('## ') == 6 and '协议 `https`' in raw.decode(),
                'ordinary/SSE authorized bytes match confirmed source bindings and deterministic six-section output', stream=stream)
            e.assert_that(e.http('GET', '/api/artifacts/' + aid + '/download', subject='b')[0] == 404 and e.http('GET', '/api/artifacts/' + aid + '/download', subject='c')[0] == 404, 'tenant and owner download isolation', stream=stream)
            e.assert_that(e.sql("SELECT record_version,evaluation_snapshot,execution_scope_digest FROM task WHERE id='" + task + "';") == '2\tNULL\tNULL' and e.sql("SELECT requests_used,phase_b_digest FROM evaluation_budget WHERE id='" + run_id + "';") == '7\tNULL', 'original occupancy retained; no evaluation matrix, B or old scope rebound')
            e.assert_that(e.sql("SELECT COUNT(*) FROM model_dispatch WHERE task_id='" + task + "' AND cost_quality='KNOWN' AND input_tokens=100 AND output_tokens=50;") == '5', 'provider fixture usage separately settled for all turns')
            e.assert_that(e.sql("SELECT COUNT(*),SUM(reserved_cost),SUM(actual_cost) FROM model_dispatch WHERE task_id='" + task + "';") == '5\t10163840\t3000',
                          'per-round full reserve settles only synthetic provider usage, not task ceiling', stream=stream)
            fresh = e.submit(unique('new-default'))
            e.assert_that(e.sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(model_snapshot,'$.profile')),JSON_UNQUOTE(JSON_EXTRACT(business_policy_snapshot,'$.id')) FROM task WHERE id='" + fresh + "';") == ('a' if stream else 'b') + '\tv2', 'only new task follows new profile and policy')
            e.http('POST', '/api/tasks/' + fresh + '/cancel', {})
            # Historical downloads survive removal of current business authority and profile registry.
            e.stop(e.control); e.control = e.start(unique('historical-download'), 'control')
            e.assert_that(e.http('GET', '/api/artifacts/' + aid + '/download')[0] == 200, 'committed download independent of current policy/configuration')
        if '--compose-only' in sys.argv[4:]:
            verify_composition(e, provision, control, worker, helper, unique, server)
            return
        if '--report-config-only' in sys.argv[4:]:
            (e.evidence / 'http-facts.json').write_text(json.dumps(server.calls, ensure_ascii=False, indent=2))
            e.assert_that(len(server.calls) == 10,
                          'retained evaluation selection: normal/SSE reports, recovery and downloads use current business instruction')
            return
        run_id, cfg = provision(full_context=True)
        control(cfg)
        for submitted, expected in [
                ({'maxCostMicros': 0}, (400, 'INVALID_BUDGET')),
                ({'maxCostMicros': -1}, (400, 'INVALID_BUDGET')),
                ({'maxCostMicros': 2032767}, (429, 'MODEL_BUDGET_EXCEEDED')),
                ({'maxCostMicros': 16262145}, (400, 'INVALID_BUDGET')),
                ({'maxCostMicros': 9223372036854775807}, (400, 'INVALID_BUDGET')),
                ({'maxModelRequests': 9}, (400, 'INVALID_BUDGET'))]:
            before, count = totals(), len(server.calls)
            status, view = e.http('POST', '/api/tasks', {'goal': 'budget-refusal',
                'capabilityIds': ['web_fetch', 'read', 'compose_report', 'present'], 'budget': submitted}, key=unique('budget-refusal'))
            e.assert_that((status, view.get('code')) == expected and before == totals() and count == len(server.calls),
                          'invalid or insufficient BUSINESS budget rejected before reservation/HTTP', budget=submitted, status=status, code=view.get('code'))
        for submitted, expected in [
                ({'maxModelRequests': 1, 'maxCostMicros': 2032768}, '1\t1004096\t2032768'),
                ({'maxModelRequests': 2, 'maxCostMicros': 2032768}, '2\t2008192\t2032768'),
                ({'maxModelRequests': 3}, '3\t3012288\t6098304')]:
            body = {'goal': unique('bounded-task'), 'capabilityIds': ['web_fetch', 'read', 'compose_report', 'present'], 'budget': submitted}
            status, view = e.http('POST', '/api/tasks', body, key=body['goal'])
            e.assert_that(status == 202, 'explicit BUSINESS budget above old HTTP limit accepted only within derived ceiling', budget=submitted, status=status)
            task = view['taskId']
            e.assert_that(e.sql("SELECT max_models,max_tokens,max_cost_micros FROM task WHERE id='" + task + "';") == expected,
                          'round limit derives complete token ceiling; explicit cost only tightens', budget=submitted)
            before = totals()
            changed = copy.deepcopy(body); changed['budget']['maxCostMicros'] = 16262144
            e.assert_that(e.http('POST', '/api/tasks', changed, key=body['goal'])[0] == 409 and before == totals(),
                          'HTTP retry cannot increase persisted task budget')
            e.http('POST', '/api/tasks/' + task + '/cancel', {})
        (e.evidence / 'budget-http-cases.json').write_text(json.dumps(e.results[-15:], ensure_ascii=False, indent=2))
        run_id, cfg = provision(full_context=True, input_rate=100000000)
        control(cfg)
        before = totals()
        status, view = e.http('POST', '/api/tasks', {'goal': 'over-original-cap',
            'budget': {'maxCostMicros': 500000001}}, key=unique('over-original-cap'))
        e.assert_that(status == 400 and view['code'] == 'INVALID_BUDGET' and before == totals(),
                      'derived task ceiling cannot expand original run cost limit')
        task = e.submit(unique('clamped-to-original-cap'))
        e.assert_that(e.sql("SELECT max_models,max_tokens,max_cost_micros FROM task WHERE id='" + task + "';") == '8\t8032768\t500000000',
                      'expensive synthetic profile clamps task cost at original run ceiling, without changing run')
        e.http('POST', '/api/tasks/' + task + '/cancel', {})
        run_id, cfg = provision()
        legacy = {k: v for k, v in cfg.items() if not k.startswith('jimu.business.') and not k.startswith('jimu.model.profiles.')}
        legacy.update({k: v for k, v in config({'a': server}, paid=True, run_id=run_id).items() if k.startswith('jimu.model.profiles.')})
        denied(legacy, 'deleting business policy and plan-ref does not authorize old managed tenant')
        denied(cfg, 'HTTP forged mode', extra={'mode': 'BUSINESS'})
        denied(cfg, 'HTTP model override', extra={'model': 'forged'})
        denied(cfg, 'HTTP credential override', extra={'credentialRef': 'env:FICTIONAL_V1'})
        wrong = dict(cfg, **{'jimu.model.evaluation-run-id': 'different-run'})
        denied(wrong, 'run substitution')
        e.sql('RENAME TABLE jimu_provisioning_receipt TO hidden_receipt;')
        denied(cfg, 'missing original provisioning receipt')
        e.sql('RENAME TABLE hidden_receipt TO jimu_provisioning_receipt;')
        denied(cfg, 'tenant substitution', subject='b')
        for cap in ['requests', 'currency']:
            run_id, cfg = provision()
            e.sql("UPDATE evaluation_budget SET " + ('requests_used=63' if cap == 'requests' else 'cost_held=500000000') + " WHERE id='" + run_id + "';")
            control(cfg); task = e.submit(unique('cumulative-boundary'))
            prior, count = e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';"), len(server.calls)
            w = worker(cfg); e.state(task, 'FAILED'); e.stop(w)
            e.assert_that(prior == e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';") and count == len(server.calls) and e.sql("SELECT COUNT(*) FROM model_dispatch WHERE task_id='" + task + "';") == '0', 'original cumulative cap includes historical occupancy before business dispatch', cap=cap)
        for kind in ['unknown', 'over-bound']:
            for stream in [False, True]:
                run_id, cfg = provision()
                cfg['jimu.model.default-profile-ref'] = 'b' if stream else 'a'
                control(cfg)
                goal = unique(kind)
                task = e.submit(goal)
                queued = e.submit(unique('stopped-sibling'))
                # This probe requires the violating task first. Admission timestamps can order
                # the sibling earlier, in which case its prior five valid rounds are billable.
                e.sql("UPDATE task SET ready_at=CASE WHEN id='" + task + "' THEN '2000-01-01 00:00:00' ELSE '2000-01-01 00:00:01' END WHERE id IN ('" + task + "','" + queued + "');")
                e.assert_that(e.sql("SELECT id FROM task WHERE id IN ('" + task + "','" + queued + "') ORDER BY ready_at,id;") == task + '\n' + queued,
                              'usage-bound probe fixes violating task before queued sibling', kind=kind, stream=stream)
                w = worker(cfg); e.state(task, 'FAILED'); e.stop(w)
                boundary = {
                    'kind': kind, 'stream': stream,
                    'run': e.sql("SELECT id,state,requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';"),
                    'tasks': e.sql("SELECT id,state,code,models_used,ready_at FROM task WHERE evaluation_run_id='" + run_id + "' ORDER BY ready_at,id;"),
                    'dispatch': e.sql("SELECT task_id,state,code,input_tokens,output_tokens,total_tokens,cost_quality FROM model_dispatch WHERE task_id IN ('" + task + "','" + queued + "') ORDER BY started_at,id;"),
                    'fixtureCalls': len(server.calls), 'alternateFixtureModels': dict(e.fixture.models)}
                (e.evidence / ('usage-bound-' + kind + '-' + str(stream).lower() + '.json')).write_text(json.dumps(boundary, indent=2))
                e.assert_that(e.sql("SELECT state,requests_used FROM evaluation_budget WHERE id='" + run_id + "';") == 'STOPPED\t3', 'UNKNOWN/over-bound durably stops original run', **boundary)
                held = e.sql("SELECT cost_held FROM evaluation_budget WHERE id='" + run_id + "';")
                if kind == 'unknown':
                    e.assert_that(e.sql("SELECT state,actual FROM reservation WHERE task_id='" + task + "' AND category='MODEL_COST';") == 'HELD_UNKNOWN\tNULL', 'unknown fee never zeroed or released')
                before, count, tls = totals(), len(server.calls), len(e.tls.requests)
                control(cfg); one, two = worker(cfg), worker(cfg); e.state(queued, 'FAILED'); e.stop(one); e.stop(two)
                e.assert_that(before == totals() and count == len(server.calls) and tls == len(e.tls.requests) and e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + task + "';") == '0', 'restart/two workers add no model/tool/artifact or fee release', kind=kind, stream=stream, held=held)
        for field in ['policy', 'profile', 'history', 'history-sse', 'missing-version']:
            run_id, cfg = provision()
            if field == 'history-sse': cfg['jimu.model.default-profile-ref'] = 'b'
            control(cfg)
            task = e.submit(unique('tamper-' + field))
            w = worker(cfg, 'T1'); e.barrier(w); e.stop(w, kill=True); e.expire(task)
            if field == 'policy':
                e.sql("UPDATE task SET business_policy_digest=REPEAT('f',64) WHERE id='" + task + "';")
            elif field == 'profile':
                e.sql("UPDATE task SET model_snapshot=JSON_SET(model_snapshot,'$.model','forged'),model_digest=SHA2(model_snapshot,256) WHERE id='" + task + "';")
            elif field == 'missing-version':
                cfg = {k: v for k, v in cfg.items() if not k.startswith('jimu.business.policies.v1.')}
                cfg['jimu.business.current-ref'] = 'v2'
            else:
                # Rehash a self-consistent but false prefix; the committed assistant/tool history wins.
                checkpoint = json.loads(bytes.fromhex(e.sql("SELECT HEX(request_snapshot) FROM step WHERE task_id='" + task + "';")))
                body = json.loads(checkpoint['requestBody']); body['messages'][1]['content'] = 'false goal'
                checkpoint['messages'][1]['content'] = 'false goal'
                checkpoint['requestBody'] = canonical(body); checkpoint['bodyDigest'] = hashlib.sha256(checkpoint['requestBody'].encode()).hexdigest()
                e.sql("UPDATE step SET request_snapshot=" + sqljson(checkpoint) + ",request_digest='" + digest(checkpoint) + "' WHERE task_id='" + task + "';")
            before, count, tls = totals(), len(server.calls), len(e.tls.requests)
            control(cfg); w = worker(cfg)
            # Integrity rejection can precede a claim, leaving task unclaimed but run durably stopped.
            if field in ['policy', 'profile']:
                from p2_verify import eventually
                eventually(lambda: e.sql("SELECT state FROM evaluation_budget WHERE id='" + run_id + "';") == 'STOPPED', 'business integrity rejection')
            else:
                e.state(task, 'FAILED')
            e.stop(w)
            e.assert_that(before == totals() and count == len(server.calls) and tls == len(e.tls.requests), 'tampering/missing retained version adds no reservation or IO: ' + field)
        for action in ['checkpoint', 'modelSnapshot', 'toolSnapshot', 'dispatch', 'toolAdmission', 'presentSucceeded', 'finish']:
            run_id, cfg = provision(); control(cfg)
            task = e.submit(unique('guard-' + action))
            w = worker(cfg, 'M0'); e.barrier(w)
            # Each entrypoint receives its own OPEN authority and valid active lease.
            e.assert_that(e.sql("SELECT state FROM evaluation_budget WHERE id='" + run_id + "';") == 'OPEN', 'guard begins with independent OPEN run', entry=action)
            e.sql("UPDATE task SET business_policy_digest=REPEAT('f',64) WHERE id='" + task + "';")
            before, count = totals(), len(server.calls)
            result = helper('guard-path', cfg, task, action, 'BUSINESS_POLICY_CONFLICT')
            e.stop(w)
            e.state(task, 'FAILED')
            e.assert_that('BUSINESS_POLICY_CONFLICT' in result and before == totals() and count == len(server.calls), 'entrypoint policy denial precedes new quota/IO', entry=action)
        for stream in [False, True]:
            run_id, cfg = provision()
            cfg['jimu.model.default-profile-ref'] = 'b' if stream else 'a'
            control(cfg); task = e.submit(unique('historical-settlement'))
            w = worker(cfg, 'M1'); e.barrier(w)
            before = e.sql("SELECT requests_used FROM evaluation_budget WHERE id='" + run_id + "';")
            result = helper('settle-known', cfg, task)
            e.stop(w)
            e.state(task, 'FAILED')
            e.assert_that('historical settlement fixture completed' in result and e.sql("SELECT cost_quality,input_tokens,output_tokens FROM model_dispatch WHERE task_id='" + task + "';") == 'KNOWN\t100\t50' and e.sql("SELECT requests_used FROM evaluation_budget WHERE id='" + run_id + "';") == before, 'known admitted usage settles after profile removal with no second request', stream=stream)
        for boundary in ['requests', 'cost']:
            run_id, cfg = provision(full_context=True); control(cfg)
            e.sql("UPDATE tenant_budget SET running_limit=2 WHERE tenant_id='tenant-a';")
            if boundary == 'requests':
                e.sql("UPDATE evaluation_budget SET requests_used=62 WHERE id='" + run_id + "';")
            else:
                e.sql("UPDATE evaluation_budget SET cost_held=497967232 WHERE id='" + run_id + "';")
            tasks = []
            for i in range(2):
                goal = unique('concurrent-' + boundary)
                status, view = e.http('POST', '/api/tasks', {'goal': goal,
                    'capabilityIds': ['web_fetch', 'read', 'compose_report', 'present'], 'budget': {'maxModelRequests': 1}}, key=goal)
                e.assert_that(status == 202, 'bounded task accepted for concurrent run-cap race', boundary=boundary)
                tasks.append(view['taskId'])
            a, b = worker(cfg, 'M0'), worker(cfg, 'M0'); e.barrier(a); e.barrier(b)
            count = len(server.calls)
            result = helper('budget-race', cfg, *tasks)
            accounting = e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';")
            expected = '63\t2039768' if boundary == 'requests' else '3\t500000000'
            e.assert_that(accounting == expected and 'ADMITTED' in result and 'MODEL_BUDGET_EXCEEDED' in result and len(server.calls) == count,
                          'concurrent dispatch admits exactly one at original 63-request/500-yuan boundary', boundary=boundary)
            limits = e.sql("SELECT id,max_models,max_tokens,max_cost_micros FROM task WHERE evaluation_run_id='" + run_id + "' ORDER BY id;")
            e.stop(a, kill=True); e.stop(b, kill=True)
            for task in tasks: e.expire(task)
            # A committed dispatch lost before HTTP retains UNKNOWN. Budget denial prevents
            # replay; unlike a committed unknown provider result, it need not stop the run.
            w = worker(cfg)
            from p2_verify import eventually
            eventually(lambda: e.sql("SELECT COUNT(*) FROM task WHERE evaluation_run_id='" + run_id + "' AND state NOT IN ('FAILED','CANCELLED','NEEDS_RECONCILIATION','SUCCEEDED');") == '0', 'both race tasks reach a terminal state')
            e.stop(w)
            recovery = {
                'boundary': boundary,
                'run': e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';"),
                'tasks': e.sql("SELECT id,state,code,max_models,max_tokens,max_cost_micros,models_used,cost_held FROM task WHERE evaluation_run_id='" + run_id + "' ORDER BY id;"),
                'dispatch': e.sql("SELECT state,cost_quality,reserved_cost,actual_cost FROM model_dispatch WHERE task_id IN ('" + "','".join(tasks) + "');"),
                'costReservation': e.sql("SELECT state,amount,actual,usage_quality FROM reservation WHERE category='MODEL_COST' AND task_id IN ('" + "','".join(tasks) + "');")}
            (e.evidence / ('budget-race-' + boundary + '-recovery.json')).write_text(json.dumps(recovery, indent=2))
            e.assert_that(e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';") == accounting
                and e.sql("SELECT id,max_models,max_tokens,max_cost_micros FROM task WHERE evaluation_run_id='" + run_id + "' ORDER BY id;") == limits
                and e.sql("SELECT COUNT(*) FROM model_dispatch WHERE task_id IN ('" + "','".join(tasks) + "');") == '1'
                and recovery['dispatch'] == 'DISPATCHED\tUNKNOWN\t2032768\tNULL'
                and recovery['costReservation'] == 'HELD_UNKNOWN\t2032768\tNULL\tUNKNOWN'
                and len(server.calls) == count,
                'restart retains UNKNOWN occupancy and fixed task ceilings without extra dispatch/HTTP', boundary=boundary)
            e.sql("UPDATE tenant_budget SET running_limit=1 WHERE tenant_id='tenant-a';")
        (e.evidence / 'http-facts.json').write_text(json.dumps(server.calls, ensure_ascii=False, indent=2))
        for table, fields in [('task', 'tenant_id,id,record_version,HEX(business_policy_snapshot),business_policy_digest,model_digest,evaluation_run_id,execution_scope_digest,max_models,max_tokens,max_cost_micros,models_used,cost_held'), ('evaluation_budget', '*'), ('model_dispatch', '*')]:
            (e.evidence / ('business-' + table + '.tsv')).write_text(e.sql('SELECT ' + fields + ' FROM ' + table + ';'))
    finally:
        server.shutdown(); server.server_close(); listener.join()
        e.assert_that(not listener.is_alive(), 'business local model listener stopped', calls=len(server.calls))


def verify_composition(e, provision, control, worker, helper, unique, server):
    """Actual tool/ledger boundaries; every task uses its own private BUSINESS run."""
    from p2_verify import eventually
    # Seed an isolated historical v2 BUSINESS row with the original request/tool contract.
    # This is fixture setup, never a migration/backfill of an existing environment.
    _, cfg = provision(full_context=True); control(cfg)
    goal = unique('legacy-pinned-report'); task = e.submit(goal)
    request = json.loads(e.sql("SELECT request_snapshot FROM task WHERE id='" + task + "';"))
    tools = json.loads(e.sql("SELECT tools_snapshot FROM task WHERE id='" + task + "';"))
    request['capabilityIds'] = ['web_fetch', 'read', 'write', 'present']
    tools['pins']['write'] = tools['pins'].pop('compose_report')
    tools['pins']['write']['digest'] = e.sql("SELECT plugin_digest FROM installation WHERE tenant_id='tenant-a' AND plugin_id='write';")
    columns = []
    for column, value in [('request_snapshot', request), ('tools_snapshot', tools)]:
        raw = canonical(value)
        columns.extend([column + '=CONVERT(0x' + raw.encode().hex() + ' USING utf8mb4)',
                        column.replace('_snapshot', '_digest') + "='" + hashlib.sha256(raw.encode()).hexdigest() + "'"])
    e.sql("UPDATE task SET capabilities='web_fetch,read,write,present'," + ','.join(columns) + " WHERE id='" + task + "';")
    before = e.sql("SELECT HEX(request_snapshot),request_digest,HEX(tools_snapshot),tools_digest,HEX(model_snapshot),model_digest,HEX(business_policy_snapshot),business_policy_digest FROM task WHERE id='" + task + "';")
    w = worker(cfg, 'W1'); e.barrier(w)
    history = e.sql("SELECT HEX(request_snapshot),request_digest FROM step WHERE task_id='" + task + "' ORDER BY ordinal;")
    files = e.receipts(task)['files']
    e.stop(w, kill=True); e.expire(task)
    w = worker(dict(cfg, **{'jimu.business.current-ref': 'v2', 'jimu.model.default-profile-ref': 'b'}))
    view = e.state(task, 'SUCCEEDED'); e.stop(w)
    e.assert_that(before == e.sql("SELECT HEX(request_snapshot),request_digest,HEX(tools_snapshot),tools_digest,HEX(model_snapshot),model_digest,HEX(business_policy_snapshot),business_policy_digest FROM task WHERE id='" + task + "';")
        and e.sql("SELECT HEX(request_snapshot),request_digest FROM step WHERE task_id='" + task + "' ORDER BY ordinal;").startswith(history)
        and e.receipts(task)['files'] == files,
        'old BUSINESS request/tool/model/policy and HTTP checkpoint bytes recover unchanged')
    e.assert_that(e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "' AND tool='compose_report';") == '0'
        and len([c for c in server.calls if c['goal'] == goal]) == 5
        and e.http('POST', '/api/tasks', {'goal': goal, 'capabilityIds': request['capabilityIds']}, key=goal)[1]['taskId'] == task
        and e.http('GET', '/api/artifacts/' + view['artifacts'][0]['artifactId'] + '/download')[0] == 200,
        'old free-write pins/cache/idempotency/download remain valid without extra model charge')
    for case in ['uninstalled', 'wrong-pin', 'free-write']:
        _, cfg = provision(); control(cfg)
        before = len(server.calls)
        original = e.sql("SELECT plugin_digest FROM installation WHERE tenant_id='tenant-a' AND plugin_id='compose_report';")
        if case == 'uninstalled': e.sql("UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='compose_report';")
        elif case == 'wrong-pin': e.sql("UPDATE installation SET plugin_digest=REPEAT('f',64) WHERE tenant_id='tenant-a' AND plugin_id='compose_report';")
        caps = ['web_fetch', 'read', 'write', 'present'] if case == 'free-write' else ['web_fetch', 'read', 'compose_report', 'present']
        status, view = e.http('POST', '/api/tasks', {'goal': unique(case), 'capabilityIds': caps}, key=unique('admission'))
        if case == 'wrong-pin':
            e.assert_that(status == 202, 'wrong installed pin is snapshotted for worker verification')
            task = view['taskId']; w = worker(cfg)
            view = e.state(task, 'FAILED'); e.stop(w)
            e.assert_that(view['code'] == 'PLUGIN_PIN_MISMATCH' and not view['artifacts'], 'wrong packaged pin rejected before model HTTP')
        else:
            e.assert_that(status == (403 if case == 'uninstalled' else 400), 'missing installation/free-write admission refused', case=case, response=view)
        e.assert_that(len(server.calls) == before, 'admission or pin failure adds no model call', case=case)
        e.sql("UPDATE installation SET enabled=TRUE,plugin_digest='" + original + "' WHERE tenant_id='tenant-a' AND plugin_id='compose_report';")
    for case, code in [
            ('bad-url', 'REPORT_DRAFT_RAW_URL_FORBIDDEN'), ('bad-source', 'REPORT_DRAFT_SOURCE_UNKNOWN'),
            ('bad-quote', 'REPORT_DRAFT_EVIDENCE_MISMATCH'), ('bad-prefix', 'REPORT_DRAFT_ENDPOINT_UNSUPPORTED'),
            ('bad-endpoint', 'REPORT_DRAFT_ENDPOINT_INVALID'), ('bad-field', 'TOOL_INPUT_INVALID'),
            ('bad-version', 'TOOL_INPUT_INVALID'), ('bad-duplicate', 'TOOL_INPUT_INVALID'),
            ('bad-duplicate-sse', 'TOOL_INPUT_INVALID'), ('bad-empty', 'TOOL_INPUT_INVALID'),
            ('bad-oversize', 'MODEL_RESPONSE_INVALID'), ('bad-oversize-sse', 'MODEL_STREAM_INCOMPLETE'),
            ('bad-injection', 'REPORT_DRAFT_RAW_URL_FORBIDDEN'), ('unobserved-compose', 'FS_NOT_OBSERVED'),
            ('ambiguous-source', 'REPORT_DRAFT_SOURCE_AMBIGUOUS'), ('login-source', 'REPORT_DRAFT_SOURCE_UNKNOWN'),
            ('empty-source', 'REPORT_DRAFT_SOURCE_UNKNOWN'),
            ('unauthorized-write', 'MODEL_RESPONSE_INVALID')]:
        _, cfg = provision(); control(cfg)
        if case in ['bad-duplicate-sse', 'bad-oversize-sse']:
            cfg['jimu.model.default-profile-ref'] = 'b'; control(cfg)
        if case == 'ambiguous-source': e.tls.source_bodies = [EVIDENCE, EVIDENCE + ' 本地第二个版本。']
        elif case == 'login-source': e.tls.source_bodies = ['Login required before accessing this page and all its contents.']
        elif case == 'empty-source': e.tls.source_bodies = ['']
        objects = sorted(str(p.relative_to(e.root / 'objects')) for p in (e.root / 'objects').rglob('*') if p.is_file())
        task = e.submit(unique(case)); w = worker(cfg)
        view = e.state(task, 'FAILED'); e.stop(w)
        scope = hashlib.sha256(('tenant-a:' + task).encode()).hexdigest()
        workspace = e.root / 'workspace' / (scope + '.json')
        files = e.receipts(task)['files'] if workspace.exists() else {}
        e.assert_that(view['code'] == code and not files and not view['artifacts'] and
            e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + task + "';") == '0',
            'whole draft rejected before file/upload/publication', case=case, actual=view['code'])
        e.assert_that(objects == sorted(str(p.relative_to(e.root / 'objects')) for p in (e.root / 'objects').rglob('*') if p.is_file()),
                      'negative adds no object store file', case=case)
        e.assert_that(e.sql("SELECT COUNT(*) FROM model_dispatch WHERE task_id='" + task + "' AND cost_quality='KNOWN';") != '0',
                      'rejection retains actual synthetic model accounting', case=case)
        if code == 'TOOL_INPUT_INVALID':
            e.assert_that(e.sql("SELECT COUNT(*),SUM(state='PENDING'),SUM(output_snapshot IS NOT NULL) FROM invocation WHERE task_id='" + task + "' AND tool='compose_report';") == '1\t1\t0'
                and e.sql("SELECT COUNT(*) FROM reservation r JOIN invocation i ON r.dispatch_id=i.id WHERE i.task_id='" + task + "' AND i.tool='compose_report' AND r.category='TOOL';") == '0',
                'raw/schema rejection preserves planned input without composer admission/output/quota', case=case)
        if case.startswith('bad-oversize'):
            e.assert_that(e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "' AND tool='compose_report';") == '0',
                'oversize rejection precedes raw TEXT snapshot planning and retains known model accounting', case=case)
    for boundary in ['C0', 'C1', 'C2', 'P0', 'P1', 'P2']:
        run_id, cfg = provision(); control(cfg)
        task = e.submit(unique('compose-recover-' + boundary)); w = worker(cfg, boundary)
        e.barrier(w)
        files = e.receipts(task)['files']
        old = e.sql("SELECT request_digest,response_digest FROM step WHERE task_id='" + task + "' ORDER BY ordinal;")
        known = e.sql("SELECT COUNT(*),SUM(actual_cost) FROM model_dispatch WHERE task_id='" + task + "';")
        calls, fetches = len(server.calls), len(e.tls.requests)
        if boundary == 'C0': e.assert_that(not files, 'C0 validated draft has no file write')
        else: e.assert_that(files['report.md']['version'] == 1, 'single CAS write at boundary', boundary=boundary)
        e.stop(w, kill=True); e.expire(task)
        one, two = worker(cfg), worker(cfg)
        success = boundary in ['C2', 'P2']
        view = e.state(task, 'SUCCEEDED' if success else 'NEEDS_RECONCILIATION')
        e.stop(one); e.stop(two)
        e.assert_that(e.receipts(task)['files'] == files and len(e.tls.requests) == fetches,
                      'two workers never repeat compose/CAS or old fetch', boundary=boundary)
        if success:
            e.assert_that(len(view['artifacts']) == 1 and e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "' AND tool='compose_report';") == '1'
                and e.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND type='artifact.created';") == '1',
                'confirmed result recovers exactly one publication', boundary=boundary)
            e.assert_that(e.sql("SELECT COUNT(*) FROM model_dispatch WHERE task_id='" + task + "';") == '5',
                          'confirmed recovery retains five total model rounds', boundary=boundary)
            e.assert_that(e.sql("SELECT request_digest,response_digest FROM step WHERE task_id='" + task + "' ORDER BY ordinal;").startswith(old),
                          'confirmed checkpoint prefix bytes unchanged', boundary=boundary)
        else:
            e.assert_that(not view['artifacts'] and len(server.calls) == calls and
                e.sql("SELECT COUNT(*),SUM(actual_cost) FROM model_dispatch WHERE task_id='" + task + "';") == known and
                e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + task + "' AND state='HELD_UNKNOWN';") != '0',
                'unconfirmed IO retains UNKNOWN and cost without blind replay', boundary=boundary)
            e.assert_that(e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + task + "' AND state='PUBLISHED';") == '0',
                          'unknown never publishes', boundary=boundary)
    # Each bypass starts with a real, confirmed compose and a live, fenced present lease.
    for case in ['missing', 'forged-hash', 'forged-binding', 'bad-input-digest', 'bad-output-digest', 'wrong-version', 'wrong-file-hash', 'cross-task', 'wrong-pin', 'wrong-source-pin', 'bad-source-digest']:
        _, cfg = provision(); control(cfg)
        task = e.submit(unique('receipt-' + case)); w = worker(cfg, 'P_BEFORE_STAGE'); e.barrier(w)
        file = e.receipts(task)['files']['report.md']
        report = {'path': 'report.md', 'version': file['version'], 'sha256': file['sha256'], 'size': file['size'], 'mediaType': 'text/markdown; charset=utf-8'}
        row = json.loads(e.sql("SELECT output_snapshot FROM invocation WHERE task_id='" + task + "' AND tool='compose_report';"))
        code = 'REPORT_COMPOSITION_REQUIRED'
        if case == 'missing':
            e.sql("UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE task_id='" + task + "' AND tool='compose_report';")
        elif case == 'cross-task':
            foreign = e.submit(unique('foreign-receipt-holder'))
            e.http('POST', '/api/tasks/' + foreign + '/cancel', {})
            e.sql("UPDATE invocation SET task_id='" + foreign + "' WHERE task_id='" + task + "' AND tool='compose_report';")
        elif case == 'wrong-version': report['version'] += 1
        elif case == 'wrong-file-hash': report['sha256'] = 'f' * 64
        elif case == 'wrong-pin':
            code = 'REPORT_COMPOSITION_INVALID'
            e.sql("UPDATE invocation SET plugin_digest=REPEAT('f',64) WHERE task_id='" + task + "' AND tool='compose_report';")
        elif case == 'wrong-source-pin':
            code = 'REPORT_COMPOSITION_INVALID'
            e.sql("UPDATE invocation SET plugin_digest=REPEAT('f',64) WHERE task_id='" + task + "' AND tool='web_fetch';")
        elif case == 'bad-output-digest':
            code = 'REPORT_COMPOSITION_INVALID'
            e.sql("UPDATE invocation SET output_digest=REPEAT('f',64) WHERE task_id='" + task + "' AND tool='compose_report';")
        elif case == 'bad-input-digest':
            code = 'REPORT_COMPOSITION_INVALID'
            e.sql("UPDATE invocation SET input_digest=REPEAT('f',64) WHERE task_id='" + task + "' AND tool='compose_report';")
        elif case == 'bad-source-digest':
            code = 'SNAPSHOT_CORRUPT'
            e.sql("UPDATE invocation SET output_digest=REPEAT('f',64) WHERE task_id='" + task + "' AND tool='web_fetch';")
        else:
            if case == 'forged-hash': row['draftSha256'] = 'f' * 64
            else: row['bindings'][0]['invocationId'] = 'fictional-other-fetch'
            raw = canonical(row)
            e.sql("UPDATE invocation SET output_snapshot=CONVERT(0x" + raw.encode().hex() + " USING utf8mb4),output_digest='" + hashlib.sha256(raw.encode()).hexdigest() + "' WHERE task_id='" + task + "' AND tool='compose_report';")
        calls = len(server.calls)
        result = helper('compose-stage', cfg, task, canonical(report), code)
        e.assert_that(code in result and e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + task + "';") == '0'
            and len(server.calls) == calls and e.receipts(task)['files']['report.md'] == file,
            'staging rejects bypass with zero new file/upload/publication', case=case)
        e.http('POST', '/api/tasks/' + task + '/cancel', {}); e.stop(w)
        eventually(lambda: e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + task + "' AND category='RUNNING' AND state='RELEASED';") == '1', 'cancelled private worker stopped')


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *args: sys.exit(143))
    experiment = BusinessExperiment(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
    try:
        experiment.prepare()
        verify(experiment)
    finally:
        experiment.cleanup()
