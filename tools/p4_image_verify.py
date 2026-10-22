"""Task image admission -> real media part -> report v2 -> owner download. Local protocol only."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import struct
import subprocess
import time
import uuid
import zlib

from p2_verify import Experiment, Handler, eventually


def png(width=3, height=2):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    raw = b''.join(b'\0' + bytes([240, 32, 16]) * width for _ in range(height))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')


class ImageProvider(Handler):
    def do_POST(self):
        assert self.path == '/v1/chat/completions'
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert 'Authorization' not in self.headers  # explicit no-secret local profile
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        media = []
        for message in body['messages']:
            if isinstance(message.get('content'), list):
                assert message['role'] == 'user'
                text, part = message['content']
                assert text['type'] == 'text' and text['text'].startswith('[UNTRUSTED_TASK_IMAGE image:')
                assert part['type'] == 'image_url' and part['image_url']['detail'] == 'auto'
                data = base64.b64decode(part['image_url']['url'].removeprefix('data:image/png;base64,'), validate=True)
                assert data.startswith(b'\x89PNG\r\n\x1a\n')
                width, height = struct.unpack('>II', data[16:24])
                # Full PNG container and deflate decoding, not a header or text surrogate.
                at, pixels = 8, bytearray()
                while at < len(data):
                    size = struct.unpack('>I', data[at:at+4])[0]
                    kind, content = data[at+4:at+8], data[at+8:at+8+size]
                    assert zlib.crc32(kind+content) == struct.unpack('>I', data[at+8+size:at+12+size])[0]
                    if kind == b'IDAT': pixels.extend(content)
                    at += size+12
                decoded = zlib.decompress(pixels)
                assert len(decoded) >= width*height*3
                digest = hashlib.sha256(data).hexdigest()
                assert digest in text['text'] and f'{width}x{height}' in text['text']
                media.append({'sha256': digest, 'size': len(data), 'width': width, 'height': height})
        calls = [c for m in body['messages'] for c in m.get('tool_calls', [])]
        replies = [m for m in body['messages'] if m['role'] == 'tool']
        last = calls[-1]['function']['name'] if calls else None
        path = 'reports/image.md'
        if last is None:
            name, args = 'read_image', {'file_path': 'inputs/images/sample.jpg' if goal == 'image-stream' else 'inputs/images/sample.png'}
        elif last == 'read_image':
            assert len(media) == 1
            name, args = 'read', {'file_path': path}
        elif last == 'read':
            source = json.loads(next(r['content'] for r in replies if 'sourceRef' in r['content']))['sourceRef']
            if goal == 'image-foreign':
                source = self.server.first_source
            segment = {'sourceType': 'image', 'kind': 'text', 'text': '受控样本包含红色像素，协议替身未作视觉推理。', 'sourceRef': source}
            name, args = 'compose_report', {'file_path': path, 'draft': {'version': 2, 'subject': '图片协议验证', 'summary': [segment], 'conclusions': [segment], 'limitations': []}}
        elif last == 'compose_report':
            name, args = 'present', {'files': [{'path': path}]}
        else:
            name, args = None, None
        message = {'role': 'assistant', 'content': '本地协议结果已发布' if not name else ''}
        if name:
            message['tool_calls'] = [{'id': 'image_' + str(len(replies)), 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args, ensure_ascii=False, separators=(',', ':'))}}]
        receipt = {'goal': goal, 'bodySha256': hashlib.sha256(json.dumps(body, separators=(',', ':')).encode()).hexdigest(), 'stream': body['stream'], 'media': media, 'body': body}
        with self.server.lock:
            if goal == 'image-call' and last == 'read': self.server.first_source = source
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append(receipt)
        usage = {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}
        if not body['stream']:
            self.send(200, {'id': 'controlled-image', 'choices': [{'index': 0, 'message': message, 'finish_reason': 'tool_calls' if name else 'stop'}], 'usage': usage})
        else:
            delta = dict(message)
            if name: delta['tool_calls'][0]['index'] = 0
            values = [{'id': 'controlled-image', 'choices': [{'index': 0, 'delta': delta, 'finish_reason': 'tool_calls' if name else 'stop'}]}, {'id': 'controlled-image', 'choices': [], 'usage': usage}]
            text = ''.join('data: ' + json.dumps(v, ensure_ascii=False) + '\n\n' for v in values) + 'data: [DONE]\n\n'
            self.send(200, text.encode(), 'text/event-stream')


class ImageExperiment(Experiment):
    def start(self, name, role, boundary='', model_stream=False, expected_ready=200, extra=None):
        if role == 'control' and not hasattr(self, 'image_setup'):
            self.image_setup = True
            self.fixture.RequestHandlerClass = ImageProvider
            self.fixture.requests = []
            for folder in ('images', 'workspace'):
                (self.root / folder).mkdir(mode=0o700)
            self.common.update({'jimu.images.enabled': 'true', 'jimu.images.root': str(self.root / 'images'),
                                'jimu.workspace-root': str(self.root / 'workspace'), 'jimu.web.mode': 'public',
                                'jimu.web.allowed-domains': 'fixture.example', 'jimu.web.public-dispatch-enabled': 'true'})
            p = 'jimu.model.profiles.image-local.'
            profile = {'version': 3, 'profile': 'image-local', 'config-version': 1, 'adapter': 'openai-chat-images-v1',
                       'base-url': self.fixture.origin + '/v1', 'model': 'local-image-protocol-v1', 'revision': 'local-image-protocol-v1',
                       'auth-mode': 'none', 'credential-version': 0, 'transport-policy': 'trusted-local-v1', 'connect-timeout-ms': 2000,
                       'request-timeout-ms': 20000, 'response-bytes': 1048576, 'stream': False, 'max-output-tokens': 2048,
                       'output-limit-field': 'max_tokens', 'output-margin-tokens': 0, 'paid': False,
                       'capabilities.call': True, 'capabilities.tools': True, 'capabilities.stream-text': True, 'capabilities.stream-tools': True,
                       'capabilities.usage-call': True, 'capabilities.usage-stream': True, 'capabilities.parallel-tool-field': False,
                       'capabilities.context-limit': 65536, 'capabilities.evidence': 'local-image-protocol-v1',
                       'image-input.version': 1, 'image-input.enabled': True, 'image-input.media-types[0]': 'image/png',
                       'image-input.max-images': 4, 'image-input.max-image-bytes': 1048576, 'image-input.max-total-image-bytes': 4194304,
                       'image-input.max-request-bytes': 8388608, 'image-input.evidence': 'local-image-protocol-v1'}
            self.common.update({p+k: str(v).lower() if isinstance(v, bool) else str(v) for k,v in profile.items()})
            streaming = dict(profile, profile='image-stream', stream=True)
            self.common.update({'jimu.model.profiles.image-stream.'+k: str(v).lower() if isinstance(v, bool) else str(v) for k,v in streaming.items()})
            self.common['jimu.model.tenant-profiles.tenant-b'] = 'image-stream'
            legacy = {k:v for k,v in profile.items() if not k.startswith('image-input.')}
            legacy.update(version=2, profile='text-only', adapter='openai-chat-v1')
            self.common.update({'jimu.model.profiles.text-only.'+k: str(v).lower() if isinstance(v, bool) else str(v) for k,v in legacy.items()})
            self.common.update({'jimu.model.default-profile-ref': 'image-local', 'jimu.model.allowed-origins[0]': self.fixture.origin})
        folder = self.root / name
        folder.mkdir()
        config = dict(self.common, **{'jimu.role': role})
        config.update(extra or {})
        if boundary: config.update({'jimu.test.boundary': boundary, 'jimu.test.barrier': str(folder)})
        props = folder / 'runtime.properties'
        props.write_text('\n'.join(k+'='+v for k,v in config.items())+'\n')
        os.chmod(props, 0o600)
        # All fixtures are task-private. Product JVMs really run with bounded heap as nobody.
        for path in [self.root, *self.root.rglob('*')]:
            if path.name != 'mysql.env': os.chown(path, 65534, 65534)
        logpath = folder / (name+'.log')
        with logpath.open('w') as log:
            child = subprocess.Popen(['setpriv', '--reuid=65534', '--regid=65534', '--clear-groups', self.java, '-Xmx256m', '-jar', str(self.jar), '--spring.config.additional-location=file:'+str(props)], stdout=log, stderr=subprocess.STDOUT)
        self.children.append(child)
        def started():
            if child.poll() is not None: raise AssertionError(name+' startup failed\n'+logpath.read_text()[-16000:])
            match = re.search(r'Tomcat started on port (\d+)', logpath.read_text())
            return int(match.group(1)) if match else False
        port = eventually(started, name+' startup')
        instance = {'process': child, 'port': port, 'folder': folder, 'name': name}
        self.assert_that(self.http('GET', '/readyz', instance=instance)[0] == expected_ready, name+' readiness')
        self.results.append({'process': name, 'pid': child.pid, 'uid': 65534, 'xmx': '256m'})
        return instance

    def prepare(self):
        super().prepare()
        resources = Path(__file__).resolve().parents[1] / 'src/main/resources/plugins/builtin'
        for tenant in ('tenant-a', 'tenant-b'):
            for tool in ('read', 'read_image', 'compose_report', 'present'):
                version = '0.2.0' if tool == 'compose_report' else '0.1.0'
                directory = tool+'-0.2.0' if tool == 'compose_report' else tool
                digest = hashlib.sha256((resources / directory / 'output.schema.json').read_bytes()).hexdigest()
                self.sql(f"INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('{tenant}','{tool}','{version}','{digest}');")

    def cleanup(self):
        try:
            if hasattr(self.fixture, 'requests'):
                (self.evidence / 'provider-requests.json').write_text(json.dumps(self.fixture.requests, ensure_ascii=False, indent=2))
            if self.schema_ready:
                (self.evidence / 'media-metadata.tsv').write_text(self.sql('SELECT tenant_id,task_id,image_id,producer_invocation,input_id,snapshot_key,sha256,size,width,height,phase FROM task_image_object;'))
        finally: super().cleanup()


def verify(e):
    capabilities = ['read', 'read_image', 'compose_report', 'present']
    body = {'goal': 'image-call', 'capabilityIds': capabilities, 'images': [{'filePath': 'inputs/images/sample.png', 'dataBase64': base64.b64encode(png()).decode()}]}
    before = e.sql('SELECT COUNT(*) FROM task;')
    for invalid in [dict(body, unknown=True), dict(body, budget={'deadlineSeconds': '10'}), dict(body, budget={'deadlineSeconds': 1.5})]:
        status, failure = e.http('POST', '/api/tasks', invalid, key='invalid-request')
        e.assert_that(status == 400 and failure['code'] == 'INVALID_REQUEST', 'legacy malformed envelope retains INVALID_REQUEST', response=failure)
    for label, media in [('bad-base64', 'YWJj='), ('whitespace', 'Y Q=='), ('empty', ''), ('too-large', base64.b64encode(bytes(1048577)).decode())]:
        invalid = dict(body, images=[dict(body['images'][0], dataBase64=media)])
        e.assert_that(e.http('POST', '/api/tasks', invalid, key=label)[0] == 400, 'strict image admission rejects '+label)
    for path in ['../secret.png', '/etc/passwd', 'inputs/images/../secret.png', 'https://fixture.example/a.png', 'inputs/images/a\\b.png']:
        invalid = dict(body, images=[dict(body['images'][0], filePath=path)])
        e.assert_that(e.http('POST', '/api/tasks', invalid, key='invalid-path')[0] == 400, 'logical path rejected', path=path)
    e.assert_that(e.sql('SELECT COUNT(*) FROM task;') == before and not e.fixture.requests, 'invalid inputs create zero tasks and dispatch zero requests')
    for name, config, expected in [('no-store', {'jimu.images.enabled': 'false'}, 'IMAGE_STORE_UNAVAILABLE'),
                                   ('no-capability', {'jimu.model.default-profile-ref': 'text-only'}, 'MODEL_CAPABILITY_UNSUPPORTED')]:
        control = e.start(name, 'control', extra=config)
        status, rejection = e.http('POST', '/api/tasks', body, key=name, instance=control)
        e.assert_that(status == 503 and rejection['code'] == expected, name+' rejected before admission', response=rejection)
        e.assert_that('read_image' not in e.http('GET', '/api/capabilities', instance=control)[1], name+' omits image advertisement')
        e.stop(control)
    e.assert_that(e.sql('SELECT COUNT(*) FROM task;') == before and not e.fixture.requests, 'unsupported capability/store create zero tasks and dispatch zero requests')
    status, view = e.http('POST', '/api/tasks', body, key='image-call')
    e.assert_that(status == 202, 'image submission accepted', response=view)
    task = view['taskId']
    e.assert_that(e.http('POST', '/api/tasks', body, key='image-call')[1]['taskId'] == task, 'identical media submission is idempotent')
    changed = json.loads(json.dumps(body)); changed['images'][0]['dataBase64'] = base64.b64encode(png(4,2)).decode()
    e.assert_that(e.http('POST', '/api/tasks', changed, key='image-call')[0] == 409, 'changed media conflicts with idempotency key')
    worker = e.start('image-worker', 'worker')
    view = e.state(task, 'SUCCEEDED')
    artifact = view['artifacts'][0]
    status, report = e.http('GET', '/api/artifacts/'+artifact['artifactId']+'/download')
    e.assert_that(status == 200 and hashlib.sha256(report.encode()).hexdigest() == artifact['sha256'], 'exact composed report downloads')
    e.assert_that('图片解读（模型推断）' in report and '本地受控provider协议验证，未验证真实模型视觉能力' in report, 'host distinguishes model inference and protocol substitute')
    for subject in ('b', 'c'):
        e.assert_that(e.http('GET', '/api/artifacts/'+artifact['artifactId']+'/download', subject=subject)[0] == 404, 'download owner/tenant authorization', subject=subject)
    (e.evidence / 'download.md').write_text(report)
    e.assert_that(any(r['media'] for r in e.fixture.requests), 'actual HTTP image parts fully decoded and verified')
    e.stop(worker)
    foreign = dict(body, goal='image-foreign')
    _, view = e.http('POST', '/api/tasks', foreign, key='image-foreign')
    worker = e.start('foreign-source-worker', 'worker')
    rejected = e.state(view['taskId'], 'FAILED')
    e.assert_that(rejected['code'].startswith('REPORT_') and not rejected['artifacts'], 'another task image source cannot compose or publish', code=rejected['code'])
    e.stop(worker)
    # Same product stack and task contract, with real SSE model transport.
    jpeg = (Path(__file__).resolve().parents[1] / 'src/test/resources/images/red-3x2.jpg').read_bytes()
    streaming = dict(body, goal='image-stream', images=[{'filePath': 'inputs/images/sample.jpg', 'dataBase64': base64.b64encode(jpeg).decode()}])
    status, view = e.http('POST', '/api/tasks', streaming, key='image-stream', subject='b')
    e.assert_that(status == 202, 'stream image admission')
    worker = e.start('stream-worker', 'worker')
    eventually(lambda: e.http('GET', '/api/tasks/'+view['taskId'], subject='b')[1]['state'] == 'SUCCEEDED', 'SSE image task success')
    e.assert_that(any(r['goal'] == 'image-stream' and r['stream'] and r['media'] for r in e.fixture.requests), 'SSE request carries actual image media')
    e.stop(worker)
    # Crash after read_image's atomic commit, before another model request.
    restored = dict(body, goal='image-restore')
    status, view = e.http('POST', '/api/tasks', restored, key='image-restore')
    task = view['taskId']
    worker = e.start('before-image-restore', 'worker', 'P2')
    e.barrier(worker)
    e.assert_that(e.sql("SELECT COUNT(*) FROM task_image_object WHERE task_id='"+task+"' AND phase='COMMITTED';") == '1', 'image bytes and invocation committed before crash')
    e.stop(worker, kill=True); e.expire(task)
    worker = e.start('after-image-restore', 'worker')
    e.state(task, 'SUCCEEDED')
    e.assert_that(e.sql("SELECT COUNT(*) FROM task_image_object WHERE task_id='"+task+"';") == '1', 'restore reuses immutable image, never decodes twice')
    e.assert_that(all(len(r['media']) == 1 for r in e.fixture.requests if r['goal'] == 'image-restore' and r['media']), 'restored media remains deduplicated')
    e.stop(worker)
    cancelled = dict(body, goal='image-cancel')
    _, view = e.http('POST', '/api/tasks', cancelled, key='image-cancel')
    task = view['taskId']; worker = e.start('cancel-image-worker', 'worker', 'P2')
    e.barrier(worker)
    e.assert_that(e.http('POST', '/api/tasks/'+task+'/cancel')[0] == 202, 'cancel image task records intent')
    (worker['folder'] / 'release').touch()
    e.state(task, 'CANCELLED')
    e.assert_that(e.fixture.models.get('image-cancel', 0) == 1, 'cancel prevents image model dispatch after committed read')
    e.assert_that(e.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='"+task+"' AND quiescent=FALSE;") == '0', 'cancelled image worker has actual quiescence')
    e.stop(worker)


def verify_retention(e):
    def snapshots(task=None):
        condition = " WHERE task_id='"+task+"'" if task else ''
        return [json.loads(line) for line in e.sql(
            "SELECT JSON_OBJECT('id',id,'task',task_id,'snapshot',request_snapshot,'digest',request_digest) FROM step"+condition+" ORDER BY task_id,ordinal;").splitlines()]

    def metered():
        return e.sql("SELECT COUNT(*) FROM step s JOIN task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id LEFT JOIN reservation r ON r.tenant_id=s.tenant_id AND r.id=CONCAT(s.id,':media-checkpoint') WHERE t.record_version=3 AND (r.id IS NULL OR r.amount<>OCTET_LENGTH(s.request_snapshot) OR r.actual<>r.amount OR r.state<>'SETTLED');") == '0'

    # Recreate the V13 representation in this private fixture, then let real Flyway upgrade it.
    # No product snapshot bytes are fabricated or rewritten by this setup.
    original = snapshots()
    e.assert_that(metered(), 'every retained image checkpoint is metered by exact UTF-8 bytes')
    e.stop(e.control)
    missed = e.sql("SELECT id FROM task WHERE goal='image-call';")
    e.sql("DELETE FROM reservation WHERE category='MEDIA_CHECKPOINT'; ALTER TABLE tenant_budget DROP CHECK tenant_media_checkpoint_nonnegative,DROP COLUMN media_checkpoint_held; ALTER TABLE step DROP COLUMN request_cleanup_state,DROP COLUMN retained_request_digest; DELETE FROM flyway_schema_history WHERE version='14'; UPDATE task SET media_cleanup_state='DELETED' WHERE id='"+missed+"'; UPDATE tenant_budget SET storage_limit=storage_held WHERE tenant_id='tenant-a';")
    e.control = e.start('checkpoint-v14-upgrade', 'control')
    e.assert_that(snapshots() == original and metered(), 'V13 upgrade meters old checkpoint bytes without changing original history')
    e.assert_that(e.sql("SELECT media_cleanup_state FROM task WHERE id='"+missed+"';") == 'RETAINED', 'V14 re-enqueues previously missed checkpoint copies')
    held = e.sql("SELECT storage_held,media_checkpoint_held,storage_limit FROM tenant_budget WHERE tenant_id='tenant-a';")
    e.assert_that(sum(map(int, held.split()[:2])) > int(held.split()[2]), 'pre-existing excess occupancy is visible without raising quota', counters=held)
    body = {'goal': 'image-cleanup-live', 'capabilityIds': ['read', 'read_image', 'compose_report', 'present'],
            'images': [{'filePath': 'inputs/images/sample.png', 'dataBase64': base64.b64encode(png()).decode()}]}
    before = e.sql('SELECT COUNT(*) FROM task;')
    status, rejection = e.http('POST', '/api/tasks', body, key='over-checkpoint-budget')
    e.assert_that(status == 429 and rejection['code'] == 'STORAGE_BUDGET_EXCEEDED' and e.sql('SELECT COUNT(*) FROM task;') == before,
                  'checkpoint debt blocks new media admission atomically', response=rejection)
    e.sql("UPDATE tenant_budget SET storage_limit=16777216 WHERE tenant_id='tenant-a';")

    _, view = e.http('POST', '/api/tasks', body, key='image-cleanup-live')
    live = view['taskId']
    worker = e.start('cleanup-live-worker', 'worker', 'READ_MODEL_PREPARED')
    e.barrier(worker)
    active = snapshots(live)
    e.assert_that(any('data:image/png;base64,' in row['snapshot'] for row in active), 'live checkpoint contains actual recoverable PNG bytes')
    # Stop maintenance only while installing deterministic negative fixtures; the live worker
    # remains a real process at an external boundary with no held DB transaction.
    e.stop(e.control)
    unknown = e.sql("SELECT id FROM task WHERE goal='image-foreign';")
    reconciliation = e.sql("SELECT id FROM task WHERE goal='image-stream';")
    unproven = e.sql("SELECT id FROM task WHERE goal='image-restore';")
    attempt = e.sql("SELECT id,worker_id,quiescent FROM task_attempt WHERE task_id='"+unproven+"' ORDER BY fence LIMIT 1;").split()
    e.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality,cost_quality) VALUES('tenant-a','"+unknown+":retention-proof','"+unknown+"','MODEL_COST',0,'HELD_UNKNOWN','UNKNOWN','UNKNOWN'); UPDATE task SET state='NEEDS_RECONCILIATION' WHERE id='"+reconciliation+"'; UPDATE task_attempt SET worker_id='"+str(uuid.uuid4())+"',quiescent=FALSE WHERE id='"+attempt[0]+"'; CREATE TRIGGER checkpoint_cleanup_failure BEFORE UPDATE ON step FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled checkpoint purge rollback'; UPDATE task SET updated_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE id IN ('"+"','".join((missed,live,unknown,reconciliation,unproven))+"');")
    protected = {task: snapshots(task) for task in (live, unknown, reconciliation, unproven)}
    protected_facts = e.sql("SELECT id,state,amount,actual,usage_quality,cost_quality FROM reservation WHERE task_id IN ('"+"','".join(protected)+"') ORDER BY tenant_id,id;")
    before_held = e.sql("SELECT tenant_id,storage_held,media_checkpoint_held FROM tenant_budget ORDER BY tenant_id;")
    files = list((e.root / 'images').iterdir())
    e.control = e.start('checkpoint-cleanup-control', 'control')
    eventually(lambda: e.sql("SELECT COUNT(*) FROM task_image_object WHERE task_id='"+missed+"' AND cleanup_state='DELETING';") == '1', 'real object deletion begins before injected final transaction failure')
    eventually(lambda: 'controlled checkpoint purge rollback' in (e.control['folder'] / 'checkpoint-cleanup-control.log').read_text()
               or 'controlled checkpoint purge rollback' in (worker['folder'] / 'cleanup-live-worker.log').read_text(), 'real maintenance observes injected SQL failure')
    e.assert_that(snapshots(missed) == [row for row in original if row['task'] == missed]
                  and e.sql("SELECT tenant_id,storage_held,media_checkpoint_held FROM tenant_budget ORDER BY tenant_id;") == before_held
                  and e.sql("SELECT media_cleanup_state FROM task WHERE id='"+missed+"';") == 'RETAINED',
                  'failed purge rolls back checkpoint mutations and all quota releases after filesystem deletion')
    e.sql('DROP TRIGGER checkpoint_cleanup_failure;')
    eventually(lambda: e.sql("SELECT media_cleanup_state FROM task WHERE id='"+missed+"';") == 'DELETED', 'real maintenance purges terminal checkpoint media')
    # Require an additional full maintenance period, not a one-time observation.
    time.sleep(11)
    for task, expected in protected.items():
        e.assert_that(snapshots(task) == expected and e.sql("SELECT media_cleanup_state FROM task WHERE id='"+task+"';") == 'RETAINED',
                      'cleanup preserves exact bytes with active/recoverable/UNKNOWN/missing-stop proof', task=task)
    e.assert_that(e.sql("SELECT id,state,amount,actual,usage_quality,cost_quality FROM reservation WHERE task_id IN ('"+"','".join(protected)+"') ORDER BY tenant_id,id;") == protected_facts,
                  'protected media reservations and UNKNOWN cost facts remain unchanged')
    cleaned = e.sql("SELECT JSON_OBJECT('snapshot',request_snapshot,'originalDigest',request_digest,'retainedDigest',retained_request_digest,'cleanup',request_cleanup_state) FROM step WHERE task_id='"+missed+"' ORDER BY ordinal;")
    originals = [row for row in original if row['task'] == missed]
    for row, old in zip(map(json.loads, cleaned.splitlines()), originals, strict=True):
        retained, prior = json.loads(row['snapshot']), json.loads(old['snapshot'])
        e.assert_that(row['cleanup'] == 'MEDIA_PURGED' and 'requestBody' not in retained and 'data:image/' not in row['snapshot']
                      and row['originalDigest'] == old['digest'] and row['retainedDigest'] == hashlib.sha256(row['snapshot'].encode()).hexdigest()
                      and retained == {k:v for k,v in prior.items() if k != 'requestBody'},
                      'purged checkpoint removes image bytes and preserves original body/config/history digests')
    e.assert_that(e.sql("SELECT COUNT(*) FROM task_image_input WHERE task_id='"+missed+"' AND raw_base64 IS NOT NULL;") == '0'
                  and e.sql("SELECT COUNT(*) FROM task_image_object WHERE task_id='"+missed+"' AND cleanup_state<>'DELETED';") == '0'
                  and e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='"+missed+"' AND category LIKE 'MEDIA_%' AND state<>'RELEASED';") == '0',
                  'real cleanup removes all raw/object/checkpoint copies before media reservations release')
    e.assert_that(len(list((e.root / 'images').iterdir())) < len(files), 'recorded normalized PNG removed from native store')
    after_held = e.sql("SELECT tenant_id,storage_held,media_checkpoint_held FROM tenant_budget ORDER BY tenant_id;")
    time.sleep(11)
    e.assert_that(e.sql("SELECT tenant_id,storage_held,media_checkpoint_held FROM tenant_budget ORDER BY tenant_id;") == after_held, 'repeated maintenance does not double-refund any media category')
    (e.evidence / 'checkpoint-cleanup.json').write_text(json.dumps({'original': original, 'purged': list(map(json.loads, cleaned.splitlines())), 'protected': protected,
        'heldBefore': before_held, 'heldAfter': after_held, 'protectedReservations': protected_facts}, ensure_ascii=False, indent=2))
    # The preserved live checkpoint is then recovered by the real worker, not a JSON surrogate.
    e.stop(worker, kill=True); e.expire(live)
    e.sql("UPDATE task_attempt SET worker_id='"+attempt[1]+"',quiescent="+attempt[2]+" WHERE id='"+attempt[0]+"';")
    worker = e.start('cleanup-recovery-worker', 'worker')
    e.state(live, 'SUCCEEDED')
    e.stop(worker)
    e.assert_that(all(row in snapshots(live) for row in active), 'real crash recovery reuses retained checkpoint bytes without rewriting or duplicate charging')
    e.assert_that(e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='"+live+"' AND category='MEDIA_CHECKPOINT';") == e.sql("SELECT COUNT(*) FROM step WHERE task_id='"+live+"';"), 'recovery charges each checkpoint exactly once')
    # Prior killed workers may still lack an independent process-stop receipt. Keep their
    # conservative run reservations intact; give this separate storage-boundary case a slot.
    e.sql("UPDATE tenant_budget SET running_limit=4 WHERE tenant_id='tenant-a';")
    _, view = e.http('POST', '/api/tasks', dict(body, goal='image-checkpoint-budget'), key='image-checkpoint-budget')
    limited = view['taskId']
    worker = e.start('checkpoint-budget-worker', 'worker', 'READ_MODEL_PREPARED')
    e.barrier(worker)
    count = e.sql("SELECT COUNT(*) FROM step WHERE task_id='"+limited+"';")
    e.sql("UPDATE tenant_budget SET storage_limit=storage_held+media_checkpoint_held WHERE tenant_id='tenant-a';")
    (worker['folder'] / 'release').touch()
    failed = e.state(limited, 'FAILED')
    e.stop(worker)
    e.assert_that(failed['code'] == 'STORAGE_BUDGET_EXCEEDED'
                  and e.sql("SELECT COUNT(*) FROM step WHERE task_id='"+limited+"';") == count
                  and e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='"+limited+"' AND category='MEDIA_CHECKPOINT';") == count
                  and e.fixture.models.get('image-checkpoint-budget') == int(count),
                  'checkpoint storage exhaustion rolls back new checkpoint and reservation before HTTP dispatch', code=failed['code'])
    e.sql("UPDATE tenant_budget SET storage_limit=16777216 WHERE tenant_id='tenant-a';")


def main():
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();e=ImageExperiment(args.jar,args.evidence)
    try: e.prepare();verify(e);verify_retention(e)
    finally: e.cleanup()


if __name__ == '__main__': main()
