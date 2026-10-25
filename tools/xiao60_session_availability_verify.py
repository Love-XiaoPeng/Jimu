"""Bounded private catalog samples; exact JAR pair, original SQL/binds and no provider work."""
import argparse
import hashlib
import http.client
import json
import shutil
import signal
import subprocess
import traceback
from pathlib import Path
from p2_verify import run
from session_trace_verify import TraceExperiment
from prior_session_verify import PriorExperiment

BASE_SHA = '96b813d078f71cfe338436f19695cac0b196133c364016b9f4c49bd53f01adb6'


def public_call(e, instance, subject='a', expired=False):
    connection = http.client.HTTPConnection('127.0.0.1', instance['port'], timeout=35)
    try:
        connection.request('GET', '/api/capabilities', headers={'Authorization': 'Bearer '+e.token(subject, expired=expired)})
        response = connection.getresponse(); raw = response.read()
        return {'status': response.status, 'raw': raw.decode('utf-8'), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
                'contentType': response.getheader('Content-Type'), 'body': json.loads(raw)}
    finally:
        connection.close()


def projection(row):
    text = ''.join(row['text'].split()).replace('(', '').replace(')', '')
    if not text.startswith('SELECTplugin_version,plugin_digestFROMinstallationWHERE'):
        return None
    assert 'FOR' not in text and 'tenant_id=?ANDplugin_id=' in text
    binds = row['binds']
    tenant = binds['1']
    if "plugin_id='session_event_read'" in text:
        plugin = 'session_event_read'
    elif "plugin_id='session_event_search'" in text:
        plugin = 'session_event_search'
    else:
        plugin = binds['2']
    if 'enabled=?' in text:
        assert binds['3'] is True
    else:
        assert 'enabled=TRUE' in text
    return {'tenant': tenant, 'plugin': plugin, 'enabled': True, 'projection': ['plugin_version', 'plugin_digest']}


def verify(e, old, classes):
    e.prepare()
    # Only this private fixture receives installation mutation rights for rollback trials.
    e.sql("GRANT INSERT,UPDATE,DELETE ON jimu.installation TO 'runtime'@'%';")
    # Seed every relevant plugin once, preserving existing native/scope pins. All missing pins
    # start mismatched; each test changes exact private rows only within its outer rollback.
    for name in ['session_event_read', 'session_event_search', 'session_event_trace', 'session_search']:
        e.sql("INSERT IGNORE INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','" + name + "','unavailable','unavailable');")
    public = {}
    for role in ['new', 'old']:
        jar = e.jar
        if role == 'old':
            e.jar = old
            instance = e.start('control-availability-old', 'control')
            e.jar = jar
        else:
            instance = e.control
        public[role] = {}
        for subject in ['a', 'b']:
            public[role][subject] = public_call(e, instance, subject)
            assert public[role][subject]['status'] == 200
        public[role]['expired'] = public_call(e, instance, expired=True)
        assert public[role]['expired']['status'] == 401
        if role == 'old':
            e.stop(instance)
    for subject in ['a', 'b']:
        assert public['old'][subject] == public['new'][subject]
    assert public['old']['expired']['body']['code'] == public['new']['expired']['body']['code']
    (e.evidence/'public-http.json').write_text(json.dumps(public, indent=2))
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for role in ['old', 'new']:
        database = 'availability_' + role
        e.sql(create.replace('`jimu`', '`'+database+'`')+';')
        e.sql('USE '+database+';\n'+dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`'+database+'`.')+';')
        config = e.root/(role+'.properties')
        config.write_text((e.control['folder']/'runtime.properties').read_text().replace('/jimu?', '/'+database+'?'))
        assert '/'+database+'?' in config.read_text()
        configs[role] = config
    (e.evidence/'frozen-source.json').write_text(json.dumps({'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(), 'copies': list(configs), 'boundary': 'Catalog has original nullable query/no new session queryView. Installation fixture updates/deletes and all actual catalog calls participate in outer private transaction; facts captured after rollback are 19 tenant tables, not whole database or durable caller commits.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for role, jar in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionAvailabilityScenario', '-Dloader.path='+str(classes), '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(configs[role])]
        (e.evidence/(role+'-command.json')).write_text(json.dumps(command, indent=2))
        with (e.evidence/(role+'.log')).open('w') as log:
            child = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        lines = (e.evidence/(role+'.log')).read_text().splitlines()
        result[role] = {'exit': child.returncode, 'results': [json.loads(s[7:]) for s in lines if s.startswith('RESULT ')], 'restored': [s[9:] for s in lines if s.startswith('RESTORED ')], 'sql': [s[4:] for s in lines if s.startswith('SQL ')]}
        (e.evidence/'comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        assert len(e.fixture.requests) == provider, 'no provider calls/replay'
    assert all(r['exit'] == 0 for r in result.values()), 'driver failure retained; no UNKNOWN retries'
    assert all(len(r['results']) == len(r['restored']) == 25 for r in result.values())
    assert result['old']['results'] == result['new']['results']
    positive = ['trace', 'prior', 'read', 'search', 'read-recursive-search', 'search-fallback-trace', 'read-prior-shortcut']
    negative = ['disabled-read', 'missing-read', 'missing-search', 'trace-version', 'prior-digest']
    byname = {r['name']: r for r in result['new']['results']}
    assert all(byname[n]['raw'] == 'true' for n in positive)
    assert all(byname[n]['raw'] == 'false' for n in negative)
    for v, expected in [('v1', ['session_event_read', 'web_fetch']), ('v2', ['session_event_read', 'session_event_search', 'web_fetch']), ('v3', ['session_event_read', 'session_event_search', 'session_event_trace', 'web_fetch']), ('v4', ['session_event_read', 'session_search', 'web_fetch'])]:
        assert json.loads(byname['catalog-'+v]['raw']) == expected
    assert not any(n.startswith('session_') for n in json.loads(byname['catalog-model-rejected']['raw']))
    comparisons = []
    assert len(result['old']['sql']) == len(result['new']['sql'])
    for left, right in zip(result['old']['sql'], result['new']['sql']):
        name, left = left.split(' ', 1)
        other, right = right.split(' ', 1)
        assert name == other
        left, right = json.loads(left), json.loads(right)
        assert len(left) == len(right), name
        projections = []
        for a, b in zip(left, right):
            pa, pb = projection(a), projection(b)
            if pa is not None or pb is not None:
                assert pa == pb, (name, a, b)
                projections.append({'old': a, 'new': b, 'semanticFullKey': pa})
            else:
                assert a == b, (name, a, b)
        comparisons.append({'name': name, 'statementCount': len(left), 'projectionBinds': projections, 'otherSQLAndBindsEqual': True, 'normalization': 'Only installation projection WHERE parentheses and literal plugin/enabled become bound MP parameters. Full tenant/plugin/enabled and two columns compared separately.'})
    (e.evidence/'sql-bind-comparison.json').write_text(json.dumps(comparisons, ensure_ascii=False, indent=2))
    (e.evidence/'raw-comparison.json').write_text(json.dumps([{'name': a['name'], 'rawEqual': True, 'sha256': hashlib.sha256((a['raw'] or '').encode()).hexdigest()} for a in result['old']['results']], indent=2))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--only', choices=['both', 'native', 'scope'], default='both')
    a = p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest() == BASE_SHA
    repo = Path(__file__).resolve().parents[1]
    archive = a.evidence/'executed'
    archive.mkdir(parents=True, exist_ok=False)
    classes = archive/'classes'; classes.mkdir()
    for file in (repo/'target/test-classes').rglob('SessionAvailabilityScenario*.class'):
        dest = classes/file.relative_to(repo/'target/test-classes'); dest.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/SessionAvailabilityScenario.java', archive/'SessionAvailabilityScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    shutil.copyfile(repo/'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive/'compiled-inputs.txt')
    (archive/'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size} for role, f in [('old', a.old_jar), ('new', a.jar)]], indent=2))
    (archive/'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()} for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    failures = []
    for name, klass in [('native', TraceExperiment), ('scope', PriorExperiment)]:
        if a.only not in ('both', name): continue
        e = klass(a.jar, a.evidence/name)
        try:
            verify(e, a.old_jar, classes)
        except BaseException:
            (e.evidence/'failure.txt').write_text(traceback.format_exc()); failures.append(name)
        finally:
            try: e.cleanup()
            except BaseException:
                (e.evidence/'cleanup-exception.txt').write_text(traceback.format_exc()); failures.append(name+'-cleanup')
    assert not failures, 'preserved failures: '+str(failures)
