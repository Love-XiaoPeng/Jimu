"""Narrow metadata fixed-point migration comparison; frozen private copies, no UNKNOWN retries."""
import argparse
import hashlib
import json
import shutil
import signal
import subprocess
import traceback
from pathlib import Path

from p2_verify import run
from prior_session_verify import PriorExperiment
from session_event_verify import SessionExperiment


def verify(e, old, scoped, classes, phase):
    e.prepare()
    # Admitted caller stays queued: account trials do not need a worker or provider.
    task = e.prior_task('metadata scope 🧱') if scoped else e.task('metadata native 🧱', [])
    public = {}
    for name, subject, target, expected in [('owner', 'a', task, 200),
            ('wrong-owner', 'c', task, 404), ('wrong-tenant', 'b', task, 404),
            ('absent', 'a', '00000000-0000-0000-0000-000000000000', 404)]:
        status, body = e.http('GET', '/api/tasks/' + target + '/session-history', subject=subject)
        public[name] = {'status': status, 'body': body}
        assert status == expected, (name, status, body)
    (e.evidence / 'new-public-http.json').write_text(json.dumps(public, ensure_ascii=False, indent=2))
    dump = run(['docker', 'exec', e.cid, 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for tag in ('old', 'new'):
        database = 'account_' + tag
        e.sql(create.replace('`jimu`', '`' + database + '`') + ';')
        e.sql('USE ' + database + ';\n' + dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`' + database + '`.') + ';')
        config = e.root / (tag + '.properties')
        config.write_text((e.control['folder'] / 'runtime.properties').read_text().replace('/jimu?', '/' + database + '?'))
        assert '/' + database + '?' in config.read_text()
        configs[tag] = config
    (e.evidence / 'frozen-source.json').write_text(json.dumps({
        'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(), 'task': task,
        'copiesCreatedBeforeTrials': ['account_old', 'account_new'],
        'boundary': 'Admitted queued caller, empty scope sources. Queued admission; driver context claims only inside outer rollback; terminal and counter trials do not commit. No provider, full tool read or independent postnote acceptance.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m',
            '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionMetadataAccountingScenario',
            '-Dloader.path=' + str(classes), '-cp', str(binary),
            'org.springframework.boot.loader.launch.PropertiesLauncher', str(configs[tag]), task, phase]
        (e.evidence / (tag + '-command.json')).write_text(json.dumps(command, indent=2))
        file = e.evidence / (tag + '.log')
        with file.open('w') as log:
            child = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=160)
        lines = file.read_text().splitlines()
        result[tag] = {'exit': child.returncode,
            'results': [json.loads(s[7:]) for s in lines if s.startswith('RESULT ')],
            'restored': [s[9:] for s in lines if s.startswith('RESTORED ')],
            'sql': [s[4:] for s in lines if s.startswith('SQL ')]}
        (e.evidence / 'comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        assert len(e.fixture.requests) == provider, 'must not replay provider'
    assert all(r['exit'] == 0 for r in result.values()), 'driver failures preserved; no UNKNOWN reentry'
    expected = 1 if phase == 'terminal' else 6 if phase == 'remaining' else 14
    assert all(len(r['results']) == expected and len(r['restored']) == expected for r in result.values())
    for left, right in zip(result['old']['results'], result['new']['results']):
        assert left['name'] == right['name'] and left['code'] == right['code']
        assert left['injected'] == right['injected']
        if left['raw'] is not None:
            assert left['raw'] == right['raw'] if left['name'] != 'context' else json.loads(left['raw']) == json.loads(right['raw']), left['name']
    for left, right in zip(result['old']['sql'], result['new']['sql']):
        lname, lrows = left.split(' ', 1); rname, rrows = right.split(' ', 1)
        assert lname == rname
        lrows, rrows = json.loads(lrows), json.loads(rrows)
        assert [r['text'] for r in lrows] == [r['text'] for r in rrows], lname
        if lname not in ['context','context-account-sql','terminal']:
            assert lrows == rrows, lname
    assert len(result['old']['sql']) == len(result['new']['sql'])
    (e.evidence / 'raw-comparison.json').write_text(json.dumps([
        {'name': left['name'], 'rawEqual': left['raw'] == right['raw'],
         'oldSHA256': hashlib.sha256((left['raw'] or '').encode()).hexdigest(),
         'newSHA256': hashlib.sha256((right['raw'] or '').encode()).hexdigest()}
        for left, right in zip(result['old']['results'], result['new']['results'])], indent=2))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--only', choices=['both', 'native', 'scope'], default='both')
    p.add_argument('--phase', choices=['all', 'remaining', 'terminal'], default='all')
    a = p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest() == 'c9fa697028f54f9c9bd51c5fa68061811fa0874af3f54752f6b3d63a593335d4'
    repo = Path(__file__).resolve().parents[1]
    archive = a.evidence / 'executed'
    archive.mkdir(parents=True, exist_ok=False)
    classes = archive / 'classes'
    classes.mkdir()
    for file in (repo / 'target/test-classes').rglob('SessionMetadataAccountingScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/SessionMetadataAccountingScenario.java', archive / 'SessionMetadataAccountingScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([
        {'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', a.old_jar), ('new', a.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([
        {'path': str(f.relative_to(archive)), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    failures = []
    for name, klass in [('native', SessionExperiment), ('scope', PriorExperiment)]:
        if a.only not in ('both', name):
            continue
        e = klass(a.jar, a.evidence / name)
        try:
            verify(e, a.old_jar, name == 'scope', classes, a.phase)
        except BaseException:
            (e.evidence / 'failure.txt').write_text(traceback.format_exc())
            failures.append(name)
        finally:
            try:
                e.cleanup()
            except BaseException:
                (e.evidence / 'cleanup-exception.txt').write_text(traceback.format_exc())
                failures.append(name + '-cleanup')
    assert not failures, 'preserved failures: ' + str(failures)
