"""Bounded real HTTP/SSE comparison of the integrated task adapters; no worker/provider."""
import argparse
import hashlib
import json
import shutil
import signal
import traceback
import urllib.error
import urllib.request
from pathlib import Path

from p2_verify import Experiment


def verify(e, baseline):
    e.prepare()
    status, admitted = e.http('POST', '/api/tasks', {'goal': 'HTTP adapter integration frozen'},
                              key='http-adapter-integration')
    assert status == 202, (status, admitted)
    task = admitted['taskId']
    status, view = e.http('POST', '/api/tasks/' + task + '/cancel')
    assert status == 200 and view['state'] == 'CANCELLED'
    e.stop(e.control)
    frozen = e.sql("SELECT * FROM task ORDER BY tenant_id,id; SELECT * FROM event ORDER BY tenant_id,task_id,seq;")
    old = e.root / 'baseline.jar'
    shutil.copyfile(baseline, old)
    current = e.jar
    outputs = {}
    for side, binary in [('old', old), ('new', current)]:
        e.jar = binary
        e.control = e.start('control-' + side, 'control')
        results = []

        def request(name, method, path, data=None, subject='a', headers=None, expected=200, code=None):
            req = urllib.request.Request('http://127.0.0.1:' + str(e.control['port']) + path,
                                         data=data, method=method)
            req.add_header('Content-Type', 'application/json')
            if subject is not None:
                req.add_header('Authorization', 'Bearer ' + e.token(subject))
            for k, v in (headers or {}).items():
                req.add_header(k, v)
            try:
                response = e.http_client.open(req, timeout=12)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                raw = response.read()
                row = {'name': name, 'status': response.status,
                       'headers': dict(response.headers.items()),
                       'raw': raw.decode(), 'sha256': hashlib.sha256(raw).hexdigest()}
            results.append(row)
            (e.evidence / (side + '-http.json')).write_text(json.dumps(results, ensure_ascii=False, indent=2))
            assert row['status'] == expected, row
            if code is not None:
                assert json.loads(row['raw'])['code'] == code, row
            return row

        for name, data, code in [
            ('duplicate', b'{"goal":"a","goal":"b"}', 'INVALID_REQUEST'),
            ('number-goal', b'{"goal":1}', 'INVALID_REQUEST'),
            ('float-budget', b'{"goal":"a","budget":{"deadlineSeconds":1.5}}', 'INVALID_REQUEST'),
            ('images', b'{"goal":"a","images":{}}', 'INVALID_IMAGE_INPUT')]:
            request(name, 'POST', '/api/tasks', data, expected=400, code=code)
        request('auth-before-body', 'POST', '/api/tasks', b'{', subject=None, expected=401)
        request('body-limit', 'POST', '/api/tasks', b' ' * 6291457, expected=413, code='REQUEST_TOO_LARGE')
        replay = request('idempotent-submit', 'POST', '/api/tasks',
                         json.dumps({'goal': 'HTTP adapter integration frozen'}).encode(),
                         headers={'Idempotency-Key': 'http-adapter-integration'}, expected=202)
        assert json.loads(replay['raw'])['taskId'] == task
        assert replay['headers']['Location'] == '/api/tasks/' + task
        events = '/api/tasks/' + task + '/events'
        request('event-auth-first', 'GET', '/api/tasks/bad/events', subject=None,
                headers={'Last-Event-ID': 'bad'}, expected=401)
        request('event-id-first', 'GET', '/api/tasks/bad/events',
                headers={'Last-Event-ID': 'bad'}, expected=404)
        request('event-owner-first', 'GET', events, subject='c',
                headers={'Last-Event-ID': 'bad'}, expected=404)
        request('event-cursor', 'GET', events, headers={'Last-Event-ID': 'bad'},
                expected=400, code='INVALID_CURSOR')
        wire = request('terminal-events', 'GET', events)
        assert wire['headers']['Content-Type'].startswith('text/event-stream')
        fields = ['eventVersion', 'eventId', 'taskId', 'seq', 'type', 'occurredAt', 'status', 'code', 'artifactIds']
        data = [json.loads(line[5:]) for line in wire['raw'].splitlines() if line.startswith('data:')]
        assert len(data) >= 2 and all(list(row) == fields and row['taskId'] == task for row in data)
        assert [row['seq'] for row in data] == list(range(1, len(data) + 1))
        assert [line[3:] for line in wire['raw'].splitlines() if line.startswith('id:')] == [row['eventId'] for row in data]
        request('download-auth-first', 'GET', '/api/artifacts/bad/download', subject=None, expected=401)
        request('download-id', 'GET', '/api/artifacts/bad/download', expected=404)
        request('download-absent', 'GET', '/api/artifacts/00000000-0000-0000-0000-000000000000/download', expected=404)
        assert frozen == e.sql("SELECT * FROM task ORDER BY tenant_id,id; SELECT * FROM event ORDER BY tenant_id,task_id,seq;")
        assert not e.fixture.models and not e.fixture.tools
        outputs[side] = results
        e.stop(e.control)
    for left, right in zip(outputs['old'], outputs['new']):
        assert left['name'] == right['name'] and left['status'] == right['status']
        if left['status'] < 400:
            assert left['raw'] == right['raw'], left['name']
        else:
            assert json.loads(left['raw'])['code'] == json.loads(right['raw'])['code']
    (e.evidence / 'comparison.json').write_text(json.dumps({
        'casesPerJar': len(outputs['old']), 'sameFrozenTaskEventFacts': True,
        'sseAndIdempotentSubmitRawEqual': True, 'workersStarted': 0, 'providerCalls': 0,
        'boundary': 'Private HTTP adapter samples. No successful artifact download, active-stream disconnect/revocation race, worker/tool/session read, UNKNOWN or independent postnote acceptance.'}, indent=2))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = p.parse_args()
    e = Experiment(args.jar, args.evidence)
    try:
        (e.evidence / 'executed-source.py').write_bytes(Path(__file__).read_bytes())
        (e.evidence / 'products.json').write_text(json.dumps([
            {'role': role, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bytes': path.stat().st_size}
            for role, path in [('old', args.old_jar), ('new', args.jar)]], indent=2))
        verify(e, args.old_jar)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
