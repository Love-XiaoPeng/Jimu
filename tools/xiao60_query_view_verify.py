"""Bounded query-view assembly comparison using the existing owner/session fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import traceback
from p2_verify import run
from xiao60_pending_question_messages_verify import PendingQuestionMessagesExperiment


def compare(e, sides):
    counts = {}
    for tag, calls in sides.items():
        assert [c['name'] for c in calls] == ['owner', 'owner-metered', 'owner-metered-again', 'owner-unmetered', 'cross-owner', 'cross-tenant']
        connections = set()
        relations = 0
        for c in calls:
            assert c['before'] == c['after'], c['name']
            assert c['sessionPrior'] == c['sessionAfter']
            assert c['maxExecutionPrior'] == c['maxExecutionAfter']
            ids = {q['connection'] for q in c['sql']}
            assert len(ids) == 1, c['name']
            connections.update(ids)
            rows = [q for q in c['sql'] if q['text'] == 'SELECT * FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=? FOR UPDATE']
            assert len(rows) == 1
            assert list(rows[0]['binds'].values()) == ['tenant-b' if c['name'] == 'cross-tenant' else 'tenant-a', e.owner_task, 'carol' if c['name'] == 'cross-owner' else 'alice']
            relations += 3
            if c['name'].startswith('cross-'):
                assert c['code'] == 'SESSION_EVENT_NOT_FOUND'
            else:
                assert c['code'] == 'OK' and json.loads(c['result'])['readable']
                assert c['result'] == calls[0]['result']
            assert any(q['text'].startswith('SET SESSION max_execution_time=') for q in c['sql'])
        assert len(connections) == 1, 'all affected calls join the same bound connection'
        lines = (e.evidence / (tag + '.log')).read_text().splitlines()
        get = lambda prefix: json.loads(next(v[len(prefix):] for v in lines if v.startswith(prefix)))
        a, b, c = [get(prefix) for prefix in ['METER_BEFORE ', 'METER_MIDDLE ', 'METER_AFTER ']]
        assert a == {'reads': 2, 'bytes': 12, 'operations': 4}
        assert all(c[k] - b[k] == b[k] - a[k] > 0 for k in a), 'real cumulative reads/UTF8/operations, no reset'
        assert get('UNMETERED ') == {'reads': 0, 'bytes': 0, 'operations': 0}
        restored = get('RESTORATION ')
        assert restored['equal'] and restored['before'] == restored['after']
        assert 'worker_process' in json.loads(restored['before'])
        counts[tag] = {'calls': len(calls), 'sql': sum(len(c['sql']) for c in calls),
                       'binds': sum(len(q['binds']) for c in calls for q in c['sql']),
                       'relations': relations, 'queryMeter': [a, b, c],
                       'restoredTables': len(json.loads(restored['before'])), 'sameConnection': True}
    def normalize(sql):
        return 'SET SESSION max_execution_time=<remaining>' if sql.startswith('SET SESSION max_execution_time=') and not sql.endswith('=0') else sql
    for old, new in zip(sides['old'], sides['new']):
        assert [normalize(q['text']) for q in old['sql']] == [normalize(q['text']) for q in new['sql']], old['name']
        assert [q['binds'] for q in old['sql']] == [q['binds'] for q in new['sql']], old['name']
        assert old['result'] == new['result'] and old['code'] == new['code']
    assert counts['old'] == counts['new']
    (e.evidence / 'offline-comparison.json').write_text(json.dumps(counts, ensure_ascii=False, indent=2))


def verify(e, old, classes):
    e.prepare()
    repo = Path(__file__).resolve().parents[1]
    digest = hashlib.sha256((repo / 'src/main/resources/session/session_event_read-0.1.0/output.schema.json').read_bytes()).hexdigest()
    e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','session_event_read','0.1.0','" + digest + "');")
    # Same accepted session fixture grants, restricted to this private database.
    for table in ['task_session_history', 'session_public_event']:
        e.sql("GRANT INSERT,UPDATE ON jimu." + table + " TO 'runtime'@'%';")
    e.stop(e.control)
    e.common.update({'jimu.session-event-read.enabled': 'true',
                     'jimu.model.profiles.a.model': 'local-byte-accounted-v1',
                     'jimu.model.profiles.a.revision': 'local-byte-accounted-v1',
                     'jimu.model.profiles.a.response-bytes': '16384',
                     'jimu.model.profiles.a.input-limit.version': '1',
                     'jimu.model.profiles.a.input-limit.max-tokens': '8192',
                     'jimu.model.profiles.a.input-limit.proof': 'test-whole-request-utf8-v1'})
    e.control = e.start('control-query-view', 'control')
    status, owner = e.http('POST', '/api/tasks', {'goal': 'query-view-owner-history', 'capabilityIds': ['session_event_read']}, key='query-view-owner-history')
    assert status == 202, (status, owner)
    e.owner_task = owner['taskId']
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'query_view_' + tag
        e.sql(create.replace('`jimu`', '`' + database + '`') + ';')
        e.sql('USE ' + database + ';\n' + dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`' + database + '`.') + ';')
        props = e.root / (tag + '.properties')
        props.write_text((e.control['folder'] / 'runtime.properties').read_text().replace('/jimu?', '/' + database + '?'))
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.OwnerDeliveryScenario', '-Dloader.path=' + str(classes), '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(props), e.owner_task, e.owner_task, 'query-view']
        (e.evidence / (tag + '-command.json')).write_text(json.dumps(command, indent=2))
        with (e.evidence / (tag + '.log')).open('w') as log:
            child = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        (e.evidence / (tag + '-exit.json')).write_text(json.dumps({'exit': child.returncode, 'jarSHA256': hashlib.sha256(binary.read_bytes()).hexdigest()}))
        lines = (e.evidence / (tag + '.log')).read_text().splitlines()
        sides[tag] = [json.loads(v[5:]) for v in lines if v.startswith('CALL ')]
        (e.evidence / (tag + '-calls.json')).write_text(json.dumps(sides[tag], ensure_ascii=False, indent=2))
        assert child.returncode == 0, tag + ' original fixture failed'
    assert (e.fixture.models, e.fixture.tools) == requests == ({}, {}), 'no provider or tool execution'
    compare(e, sides)
    (e.evidence / 'provenance.json').write_text(json.dumps({'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(), 'ownerTask': e.owner_task,
         'boundary': 'Actual public owner queries and natural permission refusals with same-DS product constructor, no worker/provider/tool. Full private table outer rollback proves restoration, not per-call durable commit.'}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '60ae50e17ab9603c0383a6c21bfbf0482605e78ba85511a8e4f51f5f0cbafc24'
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('OwnerDeliveryScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/OwnerDeliveryScenario.java', archive / 'OwnerDeliveryScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'd23a89efacb0bbc262205ab941be3c5ba9e13823'], cwd=repo, text=True).splitlines() + subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=repo, text=True).splitlines()
    for name in sorted(set(changed)):
        if not name.startswith('src/main/') or not (repo / name).is_file():
            continue
        dest = archive / 'production-source' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repo / name, dest)
        if name.endswith('.java'):
            relative = Path(name.removeprefix('src/main/java/')).with_suffix('')
            for compiled in (repo / 'target/classes' / relative.parent).glob(relative.name + '*.class'):
                dest = archive / 'production-class' / compiled.relative_to(repo / 'target/classes')
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(compiled, dest)
    e = PendingQuestionMessagesExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar, classes)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
