"""Bounded original image availability and new media admission; private paid=false, no execution."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback

from p2_verify import run
from p4_image_verify import ImageExperiment, png
from xiao60_todo_availability_verify import public_call

BASE_SHA = 'c33c4e5d5856885bcb0bad3a27900f4e2ff3c9576b18338736a0f766f48532f6'
SHA = lambda data: hashlib.sha256(data).hexdigest()
STORES = {'direct-absent': 0, 'direct-normal': 1, 'direct-file-failure': 1,
          'catalog-normal': 1, 'catalog-absent-model-missing': 0,
          'catalog-present-model-missing': 1, 'catalog-no-image-model-missing': 0,
          'catalog-text-model': 1, 'catalog-actor-hidden': 0, 'admission-absent': 0,
          'admission-model-rejected': 0, 'admission-accepted-replay': 1}


def tokens(proof):
    task = proof['task']
    assert task['state'] == 'QUEUED' and task['record_version'] == 3
    assert SHA(task['media_snapshot'].encode()) == task['media_digest']
    snapshot = json.loads(task['media_snapshot'])
    assert len(snapshot['entries']) == len(proof['images']) == len(proof['events']) == 1
    image, event = proof['images'][0], proof['events'][0]
    assert snapshot['entries'][0]['inputId'] == image['input_id']
    import base64
    raw = base64.b64decode(image['raw_base64'], validate=True)
    assert SHA(raw) == image['raw_sha256'] and len(raw) == image['raw_size']
    assert event['type'] == 'task.accepted' and event['seq'] == 1 and event['fact_id'] == 'accepted'
    assert len(proof['reservations']) == 2
    assert {r['category'] for r in proof['reservations']} == {'QUEUE', 'MEDIA_INPUT'}
    return {proof['taskId']: '<task>', image['input_id']: '<input>',
            event['event_id']: '<event>', task['media_digest']: '<media-digest>'}


def normalize(value, mapping):
    if isinstance(value, dict):
        return {key: normalize(item, mapping) for key, item in value.items()}
    if isinstance(value, list):
        return [normalize(item, mapping) for item in value]
    if isinstance(value, str):
        for old, new in sorted(mapping.items(), key=lambda pair: -len(pair[0])):
            value = value.replace(old, new)
        if value.startswith(('{', '[')):
            try:
                return normalize(json.loads(value), mapping)
            except json.JSONDecodeError:
                pass
    return value


def compare(folder, result, expected):
    assert all(v['exit'] == 0 and len(v['results']) == len(v['restored']) == len(v['sql']) == expected
               for v in result.values()), 'original failure retained; never reenter UNKNOWN'
    proofs, mappings = {}, {}
    for role in ['old', 'new']:
        lines = (folder / (role + '.log')).read_text().splitlines()
        rows = [json.loads(line[15:]) for line in lines if line.startswith('ACCEPTED_PROOF ')]
        assert len(rows) == (1 if any(v['name'] == 'admission-accepted-replay' for v in result[role]['results']) else 0)
        proofs[role] = rows[0] if rows else None
        mappings[role] = tokens(rows[0]) if rows else {}
        facts = [json.loads(line[6:]) for line in lines if line.startswith('FACTS ')]
        assert len(facts) == expected
        for fact in facts:
            assert fact['before'] == fact['after'] and len(json.loads(fact['before'])) == 21
    if proofs['old']:
        assert normalize(proofs['old'], mappings['old']) == normalize(proofs['new'], mappings['new'])
    values, statements = [], []
    for a, b, left, right in zip(result['old']['results'], result['new']['results'], result['old']['sql'], result['new']['sql']):
        name = a['name']
        assert b['name'] == name and a['code'] == b['code']
        assert a['storeChecks'] == b['storeChecks'] == STORES[name]
        assert a['injected'] == b['injected'] == 0
        if name == 'admission-accepted-replay':
            av, bv = json.loads(a['raw']), json.loads(b['raw'])
            for view, proof in [(av, proofs['old']), (bv, proofs['new'])]:
                assert view['taskId'] == proof['taskId'] and view['state'] == 'QUEUED'
            fields = ['state', 'revision', 'code', 'summary', 'artifacts', 'cancelRequestedAt']
            assert {k: av[k] for k in fields} == {k: bv[k] for k in fields}
        else:
            assert a['raw'] == b['raw'], name
        if name.startswith('direct-'):
            assert a['raw'] == ('true' if name == 'direct-normal' else 'false')
        if name.startswith('catalog-') and a['code'] == 'OK':
            assert ('read_image' in json.loads(a['raw'])) == (name == 'catalog-normal')
        values.append({'name': name, 'old': a, 'new': b, 'originalRawEqual': a['raw'] == b['raw'],
                       'originalStrings': {role: {'raw': row['raw'], 'bytes': len((row['raw'] or '').encode()), 'sha256': SHA((row['raw'] or '').encode())}
                                           for role, row in [('old', a), ('new', b)]},
                       'comparison': 'Admission: own task ID and listed JSON fields only; generation times retained, not compared. Other cases: exact original driver raw strings.'})
        first, left = left.split(' ', 1)
        second, right = right.split(' ', 1)
        assert first == second == name
        left, right = json.loads(left), json.loads(right)
        assert normalize(left, mappings['old']) == normalize(right, mappings['new']), name
        packets = [row for row in left if row['text'] == 'SELECT @@max_allowed_packet']
        assert len(packets) == (1 if name == 'admission-accepted-replay' else 0), name
        statements.append({'name': name, 'old': left, 'new': right, 'sameSQLOrderAndNormalizedCompleteBinds': True,
                           'normalization': 'Only independently proven generated task/input/event identifiers and hash of each original media snapshot; parsed JSON values may differ in Map.of key order. All original strings/binds remain preserved.'})
    traces = {}
    for role in ['old', 'new']:
        lines = (folder / (role + '.log')).read_text().splitlines()
        traces[role] = {}
        for line in lines:
            if not line.startswith('TIMELINE '):
                continue
            name, raw = line[9:].split(' ', 1)
            timeline = json.loads(raw)
            traces[role][name] = timeline
            if name == 'admission-accepted-replay':
                store = timeline.index('store-check')
                packet = next(i for i, v in enumerate(timeline) if isinstance(v, dict) and v['text'] == 'SELECT @@max_allowed_packet')
                installations = [i for i, v in enumerate(timeline) if isinstance(v, dict) and 'installation' in v['text'] and 'FOR SHARE' in v['text']]
                assert store < packet < min(installations)
                assert sum(v == 'store-check' for v in timeline) == 1
            if name in ['admission-absent', 'admission-model-rejected']:
                assert 'store-check' not in timeline and not any('@@max_allowed_packet' in v.get('text', '') for v in timeline if isinstance(v, dict))
        assert len(traces[role]) == expected
    assert normalize(traces['old'], mappings['old']) == normalize(traces['new'], mappings['new'])
    for name, value in [('value-comparison', values), ('sql-comparison', statements), ('accepted-proof', proofs), ('store-packet-timeline', traces)]:
        (folder / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2))


def verify(e, old, classes, phase):
    e.prepare()  # Original setup only; never the full ImageIT matrix or provider/worker loop.
    e.sql("GRANT UPDATE ON jimu.installation TO 'runtime'@'%';")
    new = e.jar
    if phase == 'all':
        e.jar = old
        try:
            old_control = e.start('control-image-old', 'control')
        finally:
            e.jar = new
        public = {}
        try:
            for label, settings in [('normal', {}), ('no-store', {'jimu.images.enabled': 'false'}),
                                    ('text-model', {'jimu.model.default-profile-ref': 'text-only'})]:
                controls = {'old': old_control, 'new': e.control}
                if settings:
                    controls = {}
                    for role, jar in [('old', old), ('new', new)]:
                        e.jar = jar
                        controls[role] = e.start('control-' + label + '-' + role, 'control', extra=settings)
                    e.jar = new
                public[label] = {}
                try:
                    for role, instance in controls.items():
                        public[label][role] = {'a': public_call(e, instance)}
                        assert public[label][role]['a']['status'] == 200
                        assert ('read_image' in public[label][role]['a']['body']) == (label == 'normal')
                        if label == 'normal':
                            public[label][role]['b'] = public_call(e, instance, 'b')
                            public[label][role]['expired'] = public_call(e, instance, expired=True)
                    for subject in (['a', 'b'] if label == 'normal' else ['a']):
                        assert public[label]['old'][subject] == public[label]['new'][subject]
                    if label == 'normal':
                        a, b = public[label]['old']['expired'], public[label]['new']['expired']
                        assert a['status'] == b['status'] == 401 and a['body']['code'] == b['body']['code']
                finally:
                    if settings:
                        for instance in controls.values():
                            e.stop(instance)
        finally:
            (e.evidence / 'public-http.json').write_text(json.dumps(public, indent=2))
            e.jar = new
            e.stop(old_control)
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for role in ['old', 'new']:
        database = 'image_availability_' + role
        e.sql(create.replace('`jimu`', '`' + database + '`') + ';')
        e.sql('USE ' + database + ';\n' + dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`' + database + '`.') + ';')
        file = e.root / (role + '.properties')
        file.write_text((e.control['folder'] / 'runtime.properties').read_text().replace('/jimu?', '/' + database + '?'))
        assert '/' + database + '?' in file.read_text()
        configs[role] = file
    (e.evidence / 'frozen-source.json').write_text(json.dumps({'dumpSHA256': SHA(dump.encode()), 'copies': list(configs),
        'unchangedServerMaxPacket': int(e.sql('SELECT @@max_allowed_packet;')),
        'boundary': 'Original private ImageExperiment setup only; paid=false, no provider/worker/tool. Outer fact restoration captures 21 tenant-a tables only. Public tenant-b is output coverage. Local POSIX store/JDK codec success is real; throwing FileFailure and mutating retained model maps are explicitly synthetic test fixtures.'}, indent=2))
    image = e.root / 'sample.png'
    image.write_bytes(png())
    result = {}
    for role, jar in [('old', old), ('new', new)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ImageAvailabilityScenario',
                   '-Dloader.path=' + str(classes), '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                   str(configs[role]), phase, str(image)]
        (e.evidence / (role + '-command.json')).write_text(json.dumps(command, indent=2))
        with (e.evidence / (role + '.log')).open('w') as log:
            child = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        lines = (e.evidence / (role + '.log')).read_text().splitlines()
        result[role] = {'exit': child.returncode, 'results': [json.loads(s[7:]) for s in lines if s.startswith('RESULT ')],
                        'restored': [s[9:] for s in lines if s.startswith('RESTORED ')], 'sql': [s[4:] for s in lines if s.startswith('SQL ')]}
        (e.evidence / 'comparison.json').write_text(json.dumps(result, indent=2))
        assert len(e.fixture.requests) == 0, 'no provider/replay'
    compare(e.evidence, result, len(STORES) if phase == 'all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert SHA(args.old_jar.read_bytes()) == BASE_SHA
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('ImageAvailabilityScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/ImageAvailabilityScenario.java', archive / 'ImageAvailabilityScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    for helper in ['xiao60_todo_availability_verify.py', 'p4_image_verify.py', 'p2_verify.py']:
        shutil.copyfile(repo / 'tools' / helper, archive / helper)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': SHA(file.read_bytes()), 'bytes': file.stat().st_size}
                                                     for role, file in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': SHA(f.read_bytes())}
                                                  for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = ImageExperiment(args.jar, args.evidence / 'image')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
