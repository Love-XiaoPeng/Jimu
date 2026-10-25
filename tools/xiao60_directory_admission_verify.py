"""Bounded original directory admission chains; private paid=false fixture, no worker replay."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback

from p2_verify import run
from working_directory_verify import DirectoryExperiment


def compare(folder, result, expected):
    assert all(v['exit'] == 0 and len(v['results']) == expected
               and len(v['restored']) == expected for v in result.values()), 'original driver failure; no UNKNOWN reentry'
    values, locks, statements = [], [], []
    for left, right in zip(result['old']['results'], result['new']['results']):
        name = left['name']
        assert name == right['name'] and left['injected'] == right['injected']
        assert left['code'] == right['code'] or name == 'installation-sql'
        a, b = left['raw'], right['raw']
        if a is not None:
            a, b = json.loads(a), json.loads(b)
            for key in ['record_version', 'directory_version', 'path_policy_version', 'revision', 'cwd',
                        'content_state', 'workspace_state', 'reserved_bytes']:
                assert a['directory'].get(key) == b['directory'].get(key), (name, key)
            for key in ['state', 'capabilities', 'installation_epoch', 'next_event_seq', 'models_used', 'tools_used']:
                assert a['task'][key] == b['task'][key], (name, key)
            for body in (a, b):
                p = body['directory']
                assert p['task_id'] == body['task']['id']
                assert hashlib.sha256(p['binding_snapshot'].encode()).hexdigest() == p['binding_digest']
                assert hashlib.sha256(('tenant-a:' + p['task_id']).encode()).hexdigest() == p['namespace_digest']
            take = lambda body: [[row.get(k) for k in ['seq', 'type', 'status', 'code', 'artifact_id']] for row in body['events']]
            assert take(a) == take(b), name
        else:
            assert b is None
        values.append({'name': name, 'originalRawEqual': left['raw'] == right['raw'],
                       'oldSHA256': hashlib.sha256((left['raw'] or '').encode()).hexdigest(),
                       'newSHA256': hashlib.sha256((right['raw'] or '').encode()).hexdigest(),
                       'boundary': 'Original strings retained; proved directory/task values and reliable event fields only. Generated IDs/namespace and Map.of order may differ.'})
    assert len(result['old']['sql']) == len(result['new']['sql']) == expected
    for left, right in zip(result['old']['sql'], result['new']['sql']):
        name, a = left.split(' ', 1)
        other, b = right.split(' ', 1)
        assert name == other
        a, b = json.loads(a), json.loads(b)
        assert [r['text'] for r in a] == [r['text'] for r in b], name
        select = lambda rows: [r for r in rows if 'FOR SHARE' in r['text'] and 'installation' in r['text']]
        assert select(a) == select(b), name
        if name == 'disabled-null-task':
            assert not a, (name, a)
        projections = [r for r in a if 'FROM task_directory' in r['text']]
        if name.startswith(('projection-', 'content-', 'snapshot-', 'model-')):
            assert len(projections) == 1, (name, projections)
            assert projections[0]['binds']['1'] == 'tenant-a'
            assert len(projections[0]['binds']) == 2
        locks.append({'name': name, 'originalSQLAndFullBindingsEqual': True, 'locks': select(a)})
        statements.append({'name': name, 'originalSQLSequenceEqual': True, 'statements': len(a),
                           'allOriginalBindsEqual': [r['binds'] for r in a] == [r['binds'] for r in b],
                           'postOutcomeProofSELECTs': 4 if name == 'responded-reject' else 0})
    for tag in ('old', 'new'):
        lines = (folder / (tag + '.log')).read_text().splitlines()
        facts = [json.loads(s[6:]) for s in lines if s.startswith('FACTS ')]
        assert len(facts) == expected
        assert all(v['before'] == v['after'] and len(json.loads(v['before'])) == 20 for v in facts)
        proofs = [json.loads(s[16:]) for s in lines if s.startswith('REJECTION_PROOF ')]
        if any(v['name'] == 'responded-reject' for v in result[tag]['results']):
            assert len(proofs) == 1
            proof = proofs[0]
            assert len(proof['dispatch']) == 1 and proof['dispatch'][0]['state'] == 'RESPONDED'
            assert proof['dispatch'][0]['code'] == 'DIRECTORY_FEATURE_DISABLED'
            settled = [v for v in proof['reservations'] if v['category'] == 'MODEL']
            assert len(settled) == 1 and settled[0]['state'] == 'SETTLED' and settled[0]['actual'] == 17
            assert any(v['type'] == 'model.rejected' and v['code'] == 'DIRECTORY_FEATURE_DISABLED' for v in proof['events'])
    for name, value in [('value-comparison', values), ('lock-comparison', locks), ('sql-comparison', statements)]:
        (folder / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT UPDATE,DELETE ON jimu.installation TO 'runtime'@'%';")
    e.sql("GRANT UPDATE,DELETE ON jimu.task_directory TO 'runtime'@'%';")
    task = e.task('directory-qualification-private', [])
    dump = run(['docker', 'exec', e.cid, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for tag in ('old', 'new'):
        database = 'directory_' + tag
        e.sql(create.replace('`jimu`', '`' + database + '`') + ';')
        e.sql('USE ' + database + ';\n' + dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`' + database + '`.') + ';')
        config = e.root / (tag + '.properties')
        config.write_text((e.control['folder'] / 'runtime.properties').read_text().replace('/jimu?', '/' + database + '?'))
        assert '/' + database + '?' in config.read_text()
        configs[tag] = config
    (e.evidence / 'frozen-source.json').write_text(json.dumps({'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(),
        'task': task, 'copiesCreatedBeforeTrials': ['directory_old', 'directory_new'],
        'boundary': 'Queued actual directory task, no worker/provider. Trials and fixture UPDATE/DELETE live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.DirectoryAdmissionScenario',
                   '-Dloader.path=' + str(classes), '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                   str(configs[tag]), task, phase]
        (e.evidence / (tag + '-command.json')).write_text(json.dumps(command, indent=2))
        with (e.evidence / (tag + '.log')).open('w') as log:
            child = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=220)
        lines = (e.evidence / (tag + '.log')).read_text().splitlines()
        result[tag] = {'exit': child.returncode, 'results': [json.loads(s[7:]) for s in lines if s.startswith('RESULT ')],
                       'restored': [s[9:] for s in lines if s.startswith('RESTORED ')],
                       'sql': [s[4:] for s in lines if s.startswith('SQL ')]}
        (e.evidence / 'comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        assert len(e.fixture.requests) == provider, 'no provider replay'
    compare(e.evidence, result, 13 if phase == 'all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '73c69b664fdbdd0ccee3c59e3a94d8da3506d5218ed56c5b5dae42e6013e8a45'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('DirectoryAdmissionScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/DirectoryAdmissionScenario.java', archive / 'DirectoryAdmissionScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = DirectoryExperiment(args.jar, args.evidence / 'directory')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
