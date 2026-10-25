"""Bounded stored todo source qualification; no provider/worker/tool execution."""
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
    assert all(v['exit']==0 and len(v['results'])==expected and len(v['restored'])==expected for v in result.values()), 'driver failure retained; no replay'
    proofs={};facts=[]
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines()
        proofs[tag]=[json.loads(v[14:]) for v in lines if v.startswith('FIXTURE_PROOF ')]
        # len('FIXTURE_PROOF ')==14
        assert len(proofs[tag])==expected
        restored=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(restored)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==20 for v in restored)
        for p in proofs[tag]:
            i,pj,a=p['invocation'],p['projection'],p['attempt']
            assert i['todo_committed_attempt_id']==pj['committed_attempt_id']==a['id']
            assert i['todo_committed_fence']==pj['committed_fence']==a['fence']
            assert i['todo_applied_revision']==pj['revision']==1 and i['state']=='SUCCEEDED'
            assert hashlib.sha256(i['output_snapshot'].encode()).hexdigest()==i['output_digest']==pj['output_digest']
            assert i['todo_event_seq']==pj['updated_seq'] and any(e['seq']==i['todo_event_seq'] and e['type']=='tool.completed' for e in p['events'])
            assert hashlib.sha256(p['step']['response_snapshot'].encode()).hexdigest()==p['step']['response_digest']
    def normalize(value,attempt):
        if isinstance(value,str):return value.replace(attempt,'<own-attempt>')
        if isinstance(value,dict):return {k:normalize(v,attempt) for k,v in value.items()}
        if isinstance(value,list):return [normalize(v,attempt) for v in value]
        return value
    values=[];statements=[]
    original='SELECT id FROM task_attempt WHERE tenant_id=? AND task_id=? AND id=? AND fence=?'
    mp='SELECT id FROM task_attempt WHERE (tenant_id = ? AND task_id = ? AND id = ? AND fence = ?)'
    for index,(left,right) in enumerate(zip(result['old']['results'],result['new']['results'])):
        name=left['name'];assert name==right['name'] and left['code']==right['code']
        attempts={tag:proofs[tag][index]['attempt']['id'] for tag in proofs}
        if left['raw'] is not None:
            if name=='direct-valid':assert left['raw']==right['raw']=='qualified'
            else:
                a,b=json.loads(left['raw']),json.loads(right['raw'])
                assert normalize(a,attempts['old'])==normalize(b,attempts['new']),(name,'stated JSON fields')
                if name.startswith('owner-'):
                    for tag,body in [('old',a),('new',b)]:
                        assert body['source']['createdAttemptId']==body['source']['committedAttemptId']==attempts[tag]
                        assert body['revision']==1
                        if name=='owner-purged':assert body['state']=='PURGED' and body['todos'] is None
        values.append({'name':name,'code':left['code'],**{tag:{'raw':v['raw'],'sha256':None if v['raw'] is None else hashlib.sha256(v['raw'].encode()).hexdigest(),'bytes':None if v['raw'] is None else len(v['raw'].encode())} for tag,v in [('old',left),('new',right)]},'boundary':'Original strings retained; parsed JSON compared only after each own generated attempt ID is independently verified and normalized.'})
        la,sa=result['old']['sql'][index].split(' ',1);lb,sb=result['new']['sql'][index].split(' ',1);assert la==lb==name
        rows={'old':json.loads(sa),'new':json.loads(sb)}
        canonical={};queries={}
        for tag,data in rows.items():
            query=[v for v in data if v['text'] in [original,mp]];assert len(query)==(0 if name=='wrong-owner' else 1),(tag,name,query)
            p=proofs[tag][index];i=p['invocation'];attempt=p['attempt']
            if query:
                assert query[0]['text']==(original if tag=='old' else mp)
                assert query[0]['binds']=={'1':'tenant-a','2':i['task_id'],'3':attempt['id'],'4':attempt['fence']+(1 if name=='direct-size-zero' else 0)},(tag,name,'complete four keys')
                assert query[0]['bindJavaTypes']=={'1':'java.lang.String','2':'java.lang.String','3':'java.lang.String','4':'java.lang.Long'}
            texts=[v['text'] for v in data]
            fences=[j for j,t in enumerate(texts) if t.startswith('UPDATE task SET updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=')]
            assert len(fences)==(1 if name=='stored-replay' else 0),(tag,name,'shared fence calls')
            if fences:assert texts.index(original if tag=='old' else mp)<fences[0]
            canonical[tag]=normalize([{'text':original if v['text']==mp else v['text'],'binds':v['binds']} for v in data],attempts[tag])
            queries[tag]=query
        assert canonical['old']==canonical['new'],(name,'SQL sequence/full binds within own-attempt normalization')
        statements.append({'name':name,'statements':len(rows['old']),'oldSourceQuery':queries['old'],'newSourceQuery':queries['new'],'sameOtherSQLAndBindsAfterOwnAttemptNormalization':True,'locks':{tag:[v for v in data if 'FOR SHARE' in v['text'] or 'FOR UPDATE' in v['text']] for tag,data in rows.items()}})
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))

def verify(e, old, classes, phase):
    e.prepare()
    task = e.task('result-source-private', [])
    dump = run(['docker', 'exec', e.cid, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for tag in ('old', 'new'):
        database = 'source_' + tag
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
        'task': task, 'copiesCreatedBeforeTrials': ['source_old', 'source_new'],
        'boundary': 'Queued actual todo task, no worker/provider. Trials and legal stored-result INSERT/UPDATE live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TodoResultSourceScenario',
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
    compare(e.evidence, result, 7)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '7df174b0cf718a89f0c2c8ec7a76477966f5e75e818943dbde41f61123e7a971'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('TodoResultSourceScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/TodoResultSourceScenario.java', archive / 'TodoResultSourceScenario.java')
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
