"""Bounded original todo qualification chains; private paid=false fixture, no worker replay."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback

from p2_verify import run
from todo_verify import TodoExperiment


def canonical(rows,role):
    result=[]
    for row in rows:
        row={'text':row['text'],'binds':dict(row['binds'])}
        if row['text']=='SELECT * FROM task_todo_projection WHERE (tenant_id = ? AND task_id = ?)':
            assert role=='new' and set(row['binds'])=={'1','2'}
            row['text']='SELECT * FROM task_todo_projection WHERE tenant_id=? AND task_id=?'
        result.append(row)
    return result


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
            for key in ['record_version','todo_version','revision','content_state','reserved_bytes','binding_snapshot','binding_digest']:
                assert a['todo'].get(key) == b['todo'].get(key), (name, key)
            for key in ['state', 'capabilities', 'installation_epoch', 'next_event_seq', 'models_used', 'tools_used']:
                assert a['task'][key] == b['task'][key], (name, key)
            for body in (a, b):
                p = body['todo']
                assert p['task_id'] == body['task']['id']
                assert hashlib.sha256(p['binding_snapshot'].encode()).hexdigest() == p['binding_digest']
            take = lambda body: [[row.get(k) for k in ['seq', 'type', 'status', 'code', 'artifact_id']] for row in body['events']]
            assert take(a) == take(b), name
        else:
            assert b is None
        values.append({'name': name, 'originalRawEqual': left['raw'] == right['raw'],
                       'oldSHA256': hashlib.sha256((left['raw'] or '').encode()).hexdigest(),
                       'newSHA256': hashlib.sha256((right['raw'] or '').encode()).hexdigest(),
                       'boundary': 'Original strings retained; proved todo/task values and reliable event fields only. Generated IDs/namespace and Map.of order may differ.'})
    assert len(result['old']['sql']) == len(result['new']['sql']) == expected
    for left, right in zip(result['old']['sql'], result['new']['sql']):
        name, a = left.split(' ', 1)
        other, b = right.split(' ', 1)
        assert name == other
        a, b = json.loads(a), json.loads(b)
        a=canonical(a,'old')
        b=canonical(b,'new')
        assert [r['text'] for r in a] == [r['text'] for r in b], name
        select = lambda rows: [r for r in rows if 'FOR SHARE' in r['text'] and 'installation' in r['text']]
        assert select(a) == select(b), name
        projection=lambda rows:[r for r in rows if r['text']=='SELECT * FROM task_todo_projection WHERE tenant_id=? AND task_id=?']
        pa,pb=projection(a),projection(b)
        if name=='idempotent-retrieval':
            for role,rows in [('old',pa),('new',pb)]:
                trial=next(v for v in result[role]['results'] if v['name']==name)
                own=json.loads(trial['raw'])['task']['id']
                assert rows and all(r['binds']=={'1':'tenant-a','2':own} for r in rows),(name,role,rows,own)
            assert len(pa)==len(pb)
        else:assert pa==pb,(name,pa,pb)
        assert all(r['binds']['1']=='tenant-a' and r['binds']['2'] for r in pa)
        if name in ['direct-valid','direct-missing','public-missing','digest-rejected','codec-version','codec-todo-version','codec-invalid','original-string','purged-row']:assert len(pa)==1,(name,pa)
        locks.append({'name': name, 'originalSQLAndFullBindingsEqual': True, 'locks': select(a)})
        statements.append({'name': name, 'originalSQLSequenceEqual': True, 'statements': len(a),
                           'allOriginalBindsEqual': [r['binds'] for r in a] == [r['binds'] for r in b],
                           'projectionCalls': len(pa), 'projectionFullBindings':{'old':pa,'new':pb},'projectionComparison':'Exact bindings except generated idempotent task IDs checked independently against each own task proof', 'postOutcomeProofSELECTs':0})
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
            assert proof['dispatch'][0]['code'] == 'TODO_FEATURE_DISABLED'
            settled = [v for v in proof['reservations'] if v['category'] == 'MODEL']
            assert len(settled) == 1 and settled[0]['state'] == 'SETTLED' and settled[0]['actual'] == 17
            assert any(v['type'] == 'model.rejected' and v['code'] == 'TODO_FEATURE_DISABLED' for v in proof['events'])
    for name, value in [('value-comparison', values), ('lock-comparison', locks), ('sql-comparison', statements)]:
        (folder / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT UPDATE,DELETE ON jimu.installation TO 'runtime'@'%';")
    e.sql("GRANT UPDATE,DELETE ON jimu.task_todo_projection TO 'runtime'@'%';")
    task = e.task('todo-projection-private', [])
    if phase=='all':
        public = {}
        for name, subject, target, expected in [('owner', 'a', task, 200), ('wrong-owner', 'c', task, 404),
                ('wrong-tenant', 'b', task, 404), ('absent', 'a', '00000000-0000-0000-0000-000000000000', 404)]:
            status, body = e.http('GET', '/api/tasks/' + target + '/todos', subject=subject)
            assert status == expected, (name, status, body)
            public[name] = {'status': status, 'body': body}
        (e.evidence / 'new-public-http.json').write_text(json.dumps(public, ensure_ascii=False, indent=2))
    dump = run(['docker', 'exec', e.cid, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for tag in ('old', 'new'):
        database = 'todo_' + tag
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
        'task': task, 'copiesCreatedBeforeTrials': ['todo_old', 'todo_new'],
        'boundary': 'Queued actual todo task, no worker/provider. Trials and fixture UPDATE/DELETE live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TodoProjectionScenario',
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
    compare(e.evidence, result, 12 if phase == 'all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'b2c9944b3710c5a06f57f0d70c983d0da56a5a569041a8792d6a02b7e87cdcf6'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('TodoProjectionScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/TodoProjectionScenario.java', archive / 'TodoProjectionScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = TodoExperiment(args.jar, args.evidence / 'todo')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
