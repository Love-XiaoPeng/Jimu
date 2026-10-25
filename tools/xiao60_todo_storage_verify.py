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


def compare(folder, result, expected):
    assert all(v['exit']==0 and len(v['results'])==expected and len(v['restored'])==expected for v in result.values()), 'driver failure retained; no UNKNOWN replay'
    values, statements=[],[]
    for left,right in zip(result['old']['results'],result['new']['results']):
        name=left['name']
        assert name==right['name'] and left['code']==right['code'] and left['injected']==right['injected']==0
        a,b=left['raw'],right['raw']
        if a and a.startswith('{'):
            a,b=json.loads(a),json.loads(b)
            for key in ['record_version','todo_version','revision','content_state','binding_snapshot','binding_digest','reserved_bytes']:
                assert a['todo'][key]==b['todo'][key],(name,key)
            for key in ['tenant_id','id','state','capabilities','installation_epoch','next_event_seq','models_used','tools_used']:
                assert a['task'][key]==b['task'][key],(name,key)
            for body in [a,b]:
                p=body['todo']
                assert p['task_id']==body['task']['id']
                assert hashlib.sha256(p['binding_snapshot'].encode()).hexdigest()==p['binding_digest']
            event=lambda x:[[v.get(k) for k in ['seq','type','status','code','artifact_id']] for v in x['events']]
            assert event(a)==event(b),name
        else: assert a==b,(name,a,b)
        values.append({'name':name,'code':left['code'],'originalRawEqual':left['raw']==right['raw'],
          'oldSHA256':hashlib.sha256((left['raw'] or '').encode()).hexdigest(),'newSHA256':hashlib.sha256((right['raw'] or '').encode()).hexdigest(),
          'oldBytes':len((left['raw'] or '').encode()),'newBytes':len((right['raw'] or '').encode()),
          'boundary':'Original strings retained; only stated task/projection/reliable event fields compared; generated IDs/times/Map.of strings not normalized into wire equality.'})
    for left,right in zip(result['old']['sql'],result['new']['sql']):
        name,a=left.split(' ',1);other,b=right.split(' ',1)
        assert name==other
        a,b=json.loads(a),json.loads(b)
        assert [v['text'] for v in a]==[v['text'] for v in b],name
        storage=lambda rows:[v for v in rows if v['text'].startswith('SELECT COALESCE(OCTET_LENGTH(t.request_snapshot),0)+')]
        sa,sb=storage(a),storage(b)
        assert sa==sb,(name,'storage SQL/full binds')
        assert all(set(v['binds'])=={'1','2'} and v['binds']['1']=='tenant-a' for v in sa)
        locks=lambda rows:[v for v in rows if 'FOR SHARE' in v['text'] and ('installation' in v['text'] or 'actor_access' in v['text'])]
        assert locks(a)==locks(b),(name,'actor/installation full locks')
        if name in ['non-todo','public-projection-missing']:assert not sa,name
        else:assert len(sa)==(2 if name=='prepare-dispatch' else 1),(name,len(sa))
        statements.append({'name':name,'originalSQLSequenceEqual':True,'statements':len(a),'allOriginalBindsEqual':a==b,
         'storageCalls':len(sa),'storageSQLAndFullBinds':sa,'actorAndInstallationLocks':locks(a),
         'postOutcomeProofSELECTs':4 if name=='responded-reject' else 0})
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines()
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==20 for v in facts)
        proofs=[json.loads(v[16:]) for v in lines if v.startswith('REJECTION_PROOF ')]
        if any(v['name']=='responded-reject' for v in result[tag]['results']):
            assert len(proofs)==1
            p=proofs[0]
            assert len(p['dispatch'])==1 and p['dispatch'][0]['state']=='RESPONDED' and p['dispatch'][0]['code']=='TODO_STORAGE_LIMIT'
            settled=[v for v in p['reservations'] if v['category']=='MODEL']
            assert len(settled)==1 and settled[0]['state']=='SETTLED' and settled[0]['actual']==17
            assert any(v['type']=='model.rejected' and v['code']=='TODO_STORAGE_LIMIT' for v in p['events'])
        utf=[json.loads(v[11:]) for v in lines if v.startswith('BYTE_PROOF ')]
        if any(v['name']=='direct-utf8' for v in result[tag]['results']):
            assert len(utf)==1 and utf[0]['after']-utf[0]['before']==utf[0]['expectedUTF8Delta']==20
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT UPDATE,DELETE ON jimu.installation TO 'runtime'@'%';")
    e.sql("GRANT UPDATE,DELETE ON jimu.task_todo_projection TO 'runtime'@'%';")
    task = e.task('todo-storage-private', [])
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TodoStorageScenario',
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
    compare(e.evidence, result, 14 if phase == 'all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '70a1518edcde457853c78acabe631e621625c0bf8aadf880e9403f49add5de9c'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('TodoStorageScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/TodoStorageScenario.java', archive / 'TodoStorageScenario.java')
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
