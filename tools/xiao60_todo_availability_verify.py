"""Narrow todo discovery MP projection and public catalog checks; private paid=false."""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback

from p2_verify import run
from todo_verify import TodoExperiment

BASE_SHA = 'b771316eadd317749cae38319036f96904c6a67c8b12299d6eac2f3a2b4a6d27'


def public_call(e, instance, subject='a', expired=False):
    connection = http.client.HTTPConnection('127.0.0.1', instance['port'], timeout=35)
    try:
        connection.request('GET', '/api/capabilities', headers={'Authorization': 'Bearer ' + e.token(subject, expired=expired)})
        response = connection.getresponse()
        raw = response.read()
        return {'status': response.status, 'raw': raw.decode(), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'contentType': response.getheader('Content-Type'), 'body': json.loads(raw)}
    finally:
        connection.close()


def projection(row):
    text = ''.join(row['text'].split()).replace('(', '').replace(')', '')
    prefix = 'SELECTplugin_version,plugin_digestFROMinstallationWHEREtenant_id=?ANDplugin_id='
    if not text.startswith(prefix):
        return None
    binds = row['binds']
    if text == prefix + "'todo_write'":
        assert set(binds) == {'1'}
    else:
        assert text == prefix + '?' and binds['2'] == 'todo_write' and set(binds) == {'1', '2'}
    return {'tenant': binds['1'], 'plugin': 'todo_write', 'columns': ['plugin_version', 'plugin_digest'], 'enabledFilter': False, 'lock': False, 'ordering': None}


def compare(folder, result, expected):
    assert all(v['exit'] == 0 and len(v['results']) == len(v['restored']) == expected for v in result.values()), 'original driver failure retained; no UNKNOWN reentry'
    assert result['old']['results'] == result['new']['results']
    byname = {v['name']: v for v in result['new']['results']}
    for name in ['direct-valid', 'direct-other-tenant', 'direct-install-disabled']:
        if name in byname:
            assert byname[name]['raw'] == 'true', name
    for name in ['direct-disabled-null', 'direct-install-missing', 'direct-version', 'direct-digest', 'direct-model-rejected', 'direct-tail-scope']:
        if name in byname:
            assert byname[name]['raw'] == 'false', name
    for name in ['catalog-disabled-install', 'catalog-digest']:
        if name in byname:
            assert 'todo_write' not in json.loads(byname[name]['raw']), name
    if 'catalog-base' in byname:
        assert 'todo_write' in json.loads(byname['catalog-base']['raw'])
    comparisons = []
    assert len(result['old']['sql']) == len(result['new']['sql']) == expected
    for left, right in zip(result['old']['sql'], result['new']['sql']):
        name, left = left.split(' ', 1)
        other, right = right.split(' ', 1)
        assert name == other
        left, right = json.loads(left), json.loads(right)
        assert len(left) == len(right), name
        projected = []
        for a, b in zip(left, right):
            pa, pb = projection(a), projection(b)
            if pa is not None or pb is not None:
                assert pa == pb, (name, a, b)
                projected.append({'old': a, 'new': b, 'fullKeyProjection': pa})
            else:
                assert a == b, (name, a, b)
        assert len(projected) == (0 if name in ['direct-disabled-null', 'direct-model-rejected', 'catalog-disabled-install', 'catalog-actor-hidden'] else 1), name
        if projected:
            assert projected[0]['fullKeyProjection']['tenant'] == ('tenant-b' if name == 'direct-other-tenant' else 'tenant-a')
        if name in ['direct-disabled-null', 'direct-model-rejected']:
            assert not left
        comparisons.append({'name': name, 'statementCount': len(left), 'todoProjection': projected, 'allOtherSQLAndBindsEqual': True, 'normalization': 'Only the fixed todo_write literal becomes MP bind and WHERE parentheses change. Exactly two columns, full tenant/plugin, no enabled filter/lock/order/limit; all other statements and binds identical.'})
    (folder / 'sql-bind-comparison.json').write_text(json.dumps(comparisons, indent=2))
    (folder / 'raw-comparison.json').write_text(json.dumps([{'name': a['name'], 'rawEqual': True, 'sha256': hashlib.sha256((a['raw'] or '').encode()).hexdigest(), 'bytes': len((a['raw'] or '').encode())} for a in result['old']['results']], indent=2))
    for role in ['old', 'new']:
        facts = [json.loads(s[6:]) for s in (folder / (role + '.log')).read_text().splitlines() if s.startswith('FACTS ')]
        tail = [line for line in (folder / (role + '.log')).read_text().splitlines() if line.startswith('TAIL_SCOPE_READ ')]
        assert tail == (['TAIL_SCOPE_READ 1', 'TAIL_SCOPE_READ 2'] if 'direct-tail-scope' in byname else [])
        assert len(facts) == expected
        assert all(v['before'] == v['after'] and len(json.loads(v['before'])) == 19 for v in facts)


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT UPDATE,DELETE ON jimu.installation TO 'runtime'@'%';")
    original_digest = e.sql("SELECT plugin_digest FROM installation WHERE tenant_id='tenant-a' AND plugin_id='todo_write';")
    jar = e.jar
    e.jar = old
    try:
        old_control = e.start('control-todo-discovery-old', 'control')
    finally:
        e.jar = jar
    instances = {'old': old_control, 'new': e.control}
    public = {}
    try:
        for state in ['normal', 'disabled', 'digest']:
            if state == 'disabled':
                e.sql("UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='todo_write';")
            elif state == 'digest':
                e.sql("UPDATE installation SET enabled=TRUE,plugin_digest=REPEAT('b',64) WHERE tenant_id='tenant-a' AND plugin_id='todo_write';")
            public[state] = {}
            for role, instance in instances.items():
                public[state][role] = {'a': public_call(e, instance)}
                assert public[state][role]['a']['status'] == 200
                assert ('todo_write' in public[state][role]['a']['body']) == (state == 'normal')
                if state == 'normal':
                    public[state][role]['b'] = public_call(e, instance, 'b')
                    assert public[state][role]['b']['status'] == 200 and 'todo_write' in public[state][role]['b']['body']
                    public[state][role]['expired'] = public_call(e, instance, expired=True)
                    assert public[state][role]['expired']['status'] == 401
            for subject in (['a', 'b'] if state == 'normal' else ['a']):
                assert public[state]['old'][subject] == public[state]['new'][subject]
            if state == 'normal':
                assert public[state]['old']['expired']['body']['code'] == public[state]['new']['expired']['body']['code']
    finally:
        (e.evidence / 'public-http.json').write_text(json.dumps(public, indent=2))
        e.sql("UPDATE installation SET enabled=TRUE,plugin_digest='" + original_digest + "' WHERE tenant_id='tenant-a' AND plugin_id='todo_write';")
        e.stop(old_control)
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for role in ['old', 'new']:
        database = 'todo_discovery_' + role
        e.sql(create.replace('`jimu`', '`' + database + '`') + ';')
        e.sql('USE ' + database + ';\n' + dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`' + database + '`.') + ';')
        config = e.root / (role + '.properties')
        config.write_text((e.control['folder'] / 'runtime.properties').read_text().replace('/jimu?', '/' + database + '?'))
        assert '/' + database + '?' in config.read_text()
        configs[role] = config
    (e.evidence / 'frozen-source.json').write_text(json.dumps({'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(), 'copies': list(configs), 'boundary': 'No tasks/worker/provider. Catalog retains nullable query and original transaction. Outer restoration captures nineteen tenant-a tables only; tenant-b is output/binding coverage, not its fact snapshot.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for role, jar in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TodoAvailabilityScenario', '-Dloader.path=' + str(classes), '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(configs[role]), phase]
        (e.evidence / (role + '-command.json')).write_text(json.dumps(command, indent=2))
        with (e.evidence / (role + '.log')).open('w') as log:
            child = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        lines = (e.evidence / (role + '.log')).read_text().splitlines()
        result[role] = {'exit': child.returncode, 'results': [json.loads(s[7:]) for s in lines if s.startswith('RESULT ')], 'restored': [s[9:] for s in lines if s.startswith('RESTORED ')], 'sql': [s[4:] for s in lines if s.startswith('SQL ')]}
        (e.evidence / 'comparison.json').write_text(json.dumps(result, indent=2))
        assert len(e.fixture.requests) == provider == 0, 'no provider calls/replay'
    compare(e.evidence, result, 13 if phase == 'all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == BASE_SHA
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('TodoAvailabilityScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/TodoAvailabilityScenario.java', archive / 'TodoAvailabilityScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size} for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()} for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = TodoExperiment(args.jar, args.evidence / 'todo')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
