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
    assert all(v['exit']==0 and len(v['results'])==expected and len(v['restored'])==expected for v in result.values()), 'driver failure retained; no replay'
    values=[];statements=[]
    for left,right in zip(result['old']['results'],result['new']['results']):
        name=left['name'];assert name==right['name'] and left['code']==right['code']
        a,b=json.loads(left['raw']),json.loads(right['raw'])
        for key in ['workspaceState','taskState','capabilities']:
            assert a[key]==b[key],(name,key)
        for tag,body in [('old',a),('new',b)]:
            assert body['lease']['tenant']=='tenant-a' and body['workspaceState']==('READY' if name=='directory-ready' else 'UNINITIALIZED')
            if name=='direct-old-fence':assert body['beforeUpdatedAt']==body['afterUpdatedAt']
            else:assert body['databaseClockBefore']['isoValue']<=body['afterUpdatedAt']['isoValue']<=body['databaseClockAfter']['isoValue'] and body['beforeUpdatedAt']!=body['afterUpdatedAt']
        values.append({'name':name,'code':left['code'],'oldSHA256':hashlib.sha256(left['raw'].encode()).hexdigest(),'newSHA256':hashlib.sha256(right['raw'].encode()).hexdigest(),'oldBytes':len(left['raw'].encode()),'newBytes':len(right['raw'].encode()),'boundary':'Each side original database timestamps and generated attempt ID retained and independently verified. Only workspace/task state and capabilities compared across products.'})
    assert len(result['old']['sql'])==len(result['new']['sql'])==expected
    for left,right in zip(result['old']['sql'],result['new']['sql']):
        name,a=left.split(' ',1);other,b=right.split(' ',1);assert name==other
        a,b=json.loads(a),json.loads(b)
        assert [v['text'] for v in a]==[v['text'] for v in b],name
        fences=lambda rows:[v for v in rows if v['text'].startswith('UPDATE task SET updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=')]
        fa,fb=fences(a),fences(b);assert len(fa)==len(fb)==1
        counts=[]
        for tag,f in [('old',fa[0]),('new',fb[0])]:
            resultRow=next(v for v in result[tag]['results'] if v['name']==name)
            lease=json.loads(resultRow['raw'])['lease']
            assert f['binds']=={str(i+1):lease[k] for i,k in enumerate(['tenant','task','attempt','worker','fence'])},(name,tag,'complete binds')
            assert f['updateCount']==(0 if name=='direct-old-fence' else 1),(name,tag,f)
            counts.append(f['updateCount'])
        if name=='directory-ready':
            for rows in [a,b]:
                texts=[v['text'] for v in rows]
                projection=next(i for i,t in enumerate(texts) if t.startswith("UPDATE task_directory SET workspace_state='READY'"))
                fence=next(i for i,t in enumerate(texts) if t.startswith('UPDATE task SET updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='))
                assert projection<fence
                assert any('FOR UPDATE' in t and 'task' in t for t in texts[:projection])
        statements.append({'name':name,'originalSQLSequenceEqual':True,'oldFence':fa[0],'newFence':fb[0],'actualCounts':counts,'statements':len(a),'boundary':'Five full bindings independently match each actual Lease; generated attempt IDs not claimed identical.'})
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines()
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==20 for v in facts)
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT UPDATE ON jimu.task_directory TO 'runtime'@'%';")
    task = e.task('commit-fence-private', [])
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.CommitFenceScenario',
                   '-Dloader.path=' + str(classes), '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                   str(configs[tag]), task]
        (e.evidence / (tag + '-command.json')).write_text(json.dumps(command, indent=2))
        with (e.evidence / (tag + '.log')).open('w') as log:
            child = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=220)
        lines = (e.evidence / (tag + '.log')).read_text().splitlines()
        result[tag] = {'exit': child.returncode, 'results': [json.loads(s[7:]) for s in lines if s.startswith('RESULT ')],
                       'restored': [s[9:] for s in lines if s.startswith('RESTORED ')],
                       'sql': [s[4:] for s in lines if s.startswith('SQL ')]}
        (e.evidence / 'comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        assert len(e.fixture.requests) == provider, 'no provider replay'
    compare(e.evidence, result, 3)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '402d431da703ad2b11bfbdd4deb022af7eb61584844ae24afdc7bfe5b57cb03d'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('CommitFenceScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/CommitFenceScenario.java', archive / 'CommitFenceScenario.java')
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
