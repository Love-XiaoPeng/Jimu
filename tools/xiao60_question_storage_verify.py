"""Complete question access, private paid=false, no workers/provider/tool execution."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback
from p2_verify import Experiment,run
from mcp_verify import McpExperiment
from p3_model_verify import config

class QuestionStorageExperiment(Experiment):
    start=McpExperiment.start
    def prepare(self):
        self.fixture.requests=[]
        super().prepare()
        digest=hashlib.sha256((Path(__file__).resolve().parents[1]/'src/main/resources/interaction/ask_user_question-0.2.0/output.schema.json').read_bytes()).hexdigest()
        self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','ask_user_question','0.2.0','"+digest+"'),('tenant-b','ask_user_question','0.2.0','"+digest+"');")
        self.stop(self.control)
        settings=config({'a':self.fixture},paid=False);settings.pop('jimu.model.evaluation-run-id')
        settings['jimu.questions.enabled']='true'
        self.common.update(settings)
        self.control=self.start('control-questions','control')
    def task(self,goal):
        status,value=self.http('POST','/api/tasks',{'goal':goal,'capabilityIds':['ask_user_question']},key=goal)
        self.assert_that(status==202,'accept question without worker',status=status,value=value)
        return value['taskId']

def compare(folder,result,expected,partial_failure=False):
    if partial_failure:
        assert expected==8 and all(v['exit']==1 and len(v['results'])==expected and len(v['restored'])==len(v['sql'])==expected+1 for v in result.values()),'only the eight completed original pairs preceding the known fixture failure'
        for tag in ['old','new']:
            text=(folder/(tag+'.log')).read_text()
            assert 'INSERT command denied' in text and '"state":"42000"' in text and '"vendor":1142' in text
            assert result[tag]['sql'][-1]=='question-row-bytes []'
    else:
        assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'driver error retained; no replay'
    values=[];statements=[]
    for a,b in zip(result['old']['results'],result['new']['results']):
        name=a['name'];assert name==b['name'] and a['code']==b['code'] and a['injected']==b['injected']==0
        if a['raw'] and a['raw'].startswith('{'):
            left,right=json.loads(a['raw']),json.loads(b['raw'])
            for key in ['state','capabilities','summary','models_used','tools_used','question_binding','question_binding_digest']:
                assert left['task'][key]==right['task'][key],(name,key)
            event=lambda x:[[v[k] for k in ['seq','type','status','code']] for v in x['events']]
            assert event(left)==event(right)
            assert len(left['steps'])==len(right['steps'])==1
        else:assert a['raw']==b['raw'],name
        values.append({'name':name,'code':a['code'],**{tag:{'raw':v['raw'],'sha256':hashlib.sha256((v['raw'] or '').encode()).hexdigest(),'bytes':len((v['raw'] or '').encode())} for tag,v in [('old',a),('new',b)]},'boundary':'Only stated task and event JSON fields compared; generated IDs/times/Map.of raw values retained, no wire equality claim.'})
    for a,b in zip(result['old']['sql'][:expected],result['new']['sql'][:expected]):
        name,left=a.split(' ',1);other,right=b.split(' ',1);assert name==other
        left,right=json.loads(left),json.loads(right)
        assert [v['text'] for v in left]==[v['text'] for v in right],(name,'SQL sequence')
        storage=lambda rows:[v for v in rows if v['text'].startswith('SELECT COALESCE(OCTET_LENGTH(request_snapshot),0)+') and 'FROM user_question' in v['text']]
        old,new=storage(left),storage(right)
        assert old==new and len(old)==(0 if name=='non-question' else 1),(name,'storage SQL/two binds/count')
        for row in old:assert row['binds']['1']=='tenant-a' and set(row['binds'])=={'1','2'}
        locks=lambda rows:[v for v in rows if 'FOR SHARE' in v['text'] and ('installation' in v['text'] or 'actor_access' in v['text'])]
        assert locks(left)==locks(right),(name,'complete actor/installation lock binds')
        statements.append({'name':name,'source':old,'sourceBindsEqual':True,'sqlOrderEqual':True,'statements':len(left),'locks':{'old':locks(left),'new':locks(right)},'allBindsEqual':left==right})
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines()
        facts=[json.loads(s[6:]) for s in lines if s.startswith('FACTS ')]
        assert len(facts)==expected+int(partial_failure) and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        for row in [json.loads(s[21:]) for s in lines if s.startswith('QUESTION_BYTES_PROOF ')]:
            opened,closed=row['open'],row['closed'];assert opened['answer_snapshot'] is opened['receipt_snapshot'] is None
            assert row['requestOnly']-row['before']==len(opened['request_snapshot'].encode())
            assert row['answered']-row['requestOnly']==len(closed['answer_snapshot'].encode())+len(closed['receipt_snapshot'].encode())
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    task = e.task('question-storage-private')
    dump = run(['docker', 'exec', e.cid, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for tag in ('old', 'new'):
        database = 'question_' + tag
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
        'task': task, 'copiesCreatedBeforeTrials': ['question_old', 'question_new'],
        'boundary': 'Queued actual question task, no worker/provider. Bounded task/installation UPDATE/DELETE trials live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionStorageScenario',
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
    compare(e.evidence, result, 13 if phase=='all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '9894c1afbe92a4376c5bd936fc9ae4f810e32eaf3240a397c17926f60b7d6915'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionStorageScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionStorageScenario.java', archive / 'QuestionStorageScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionStorageExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
