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


def main():
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();e=ImageExperiment(args.jar,args.evidence)
    try: e.prepare();verify(e)
    finally: e.cleanup()


if __name__ == '__main__': main()
