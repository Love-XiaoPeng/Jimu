"""Complete pre-answer authorization, actual MP existence read and original transaction, private paid=false."""
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

class QuestionAuthorizationExperiment(Experiment):
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

def compare(folder,result,expected):
    import re
    oldsql='SELECT id FROM user_question WHERE tenant_id=? AND task_id=? AND id=?'
    newsql='SELECT id FROM user_question WHERE (tenant_id = ? AND task_id = ? AND id = ?)'
    clean=lambda x:re.sub(r'\s+',' ',x).strip()
    proofs={};summary=[]
    for role,data in result.items():
        assert data['exit']==0 and len(data['results'])==len(data['restored'])==len(data['sql'])==expected
        proofs[role]={}
        lines=(folder/(role+'.log')).read_text().splitlines()
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        for line in lines:
            if line.startswith(('FIXTURE_PROOF ','PROOF ','REJECTION_PROOF ')):
                kind,name,raw=line.split(' ',2);proofs[role][(kind,name)]=json.loads(raw)
    for left,right in zip(result['old']['results'],result['new']['results']):
        name=left['name'];assert name==right['name']
        expectedCode='OK' if name=='authorize-present' else 'QUESTION_NOT_FOUND'
        assert left['code']==right['code']==expectedCode and left['injected']==right['injected']==0
    for l,r in zip(result['old']['sql'],result['new']['sql']):
        name,l=l.split(' ',1);other,r=r.split(' ',1);assert name==other;l,r=json.loads(l),json.loads(r)
        lt=[v for v in l if clean(v['text'])==oldsql];rt=[v for v in r if clean(v['text'])==newsql]
        assert len(lt)==len(rt)==1
        assert [newsql if clean(v['text'])==oldsql else v['text'] for v in l]==[newsql if clean(v['text'])==newsql else v['text'] for v in r]
        assert [v['binds'] for v in l]==[v['binds'] for v in r]
        for role,rows,target in [('old',l,lt[0]),('new',r,rt[0])]:
            before=proofs[role][('FIXTURE_PROOF',name)];after=proofs[role][('PROOF' if name=='authorize-present' else 'REJECTION_PROOF',name)]
            task=before['task'];request=next(q['id'] for q in before['questions'] if q['phase']=='OPEN') if name=='authorize-present' else 'ffffffff-ffff-ffff-ffff-ffffffffffff'
            assert target['binds']=={'1':task['tenant_id'],'2':task['id'],'3':request}
            assert target['setters']=={'1':'setString','2':'setString','3':'setString'}
            assert target['api']==('executeQuery' if role=='old' else 'execute')
            assert len({v['connection'] for v in rows})==1
            assert not any('CURRENT_TIMESTAMP(6)'==v['text'].strip().removeprefix('SELECT ') for v in rows)
            assert rows[-1] is target
            assert rows[0]['binds']=={'1':task['tenant_id']} and 'tenant_budget' in rows[0]['text'] and 'FOR UPDATE' in rows[0]['text']
            locks=[v for v in rows if 'FOR SHARE' in v['text'] and ('actor_access' in v['text'] or 'installation' in v['text'])]
            assert len(locks)==3
            for v in locks:assert v['binds']=={'1':task['tenant_id'],'2':task['owner_actor_id'] if 'actor_access' in v['text'] else 'ask_user_question'}
            assert all(before[k]==after[k] for k in before if k!='result')
            if name=='authorize-present':
                assert after['result']=='void'
                getters=[v for v in target['getters'] if v['method'].startswith('get')]
                assert len(getters)==1 and getters[0]['value']==request
                assert getters[0]['method']==('getObject' if role=='old' else 'getString')
            else:assert not target.get('getters')
        summary.append({'name':name,'code':'OK' if name=='authorize-present' else 'QUESTION_NOT_FOUND','sqlCount':len(l),'old':lt[0],'new':rt[0],'fullLockAndBindSequenceSameExceptMpFormatting':True,'oneConnectionEach':True,'noWritesNoClock':True})
    (folder/'authorization-comparison.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-authorization-private'); task_id=task
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
        'boundary': 'Queued actual question task; model response and stored matching timed question are explicit fixtures, no worker/provider/tool execution. Trials live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionAuthorizationScenario',
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
    compare(e.evidence, result, 2 if phase=='all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'e06fece541a6675d2438066e5cf8c4bbcd3affcdbfde743820e88cf144f30555'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionAuthorizationScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionAuthorizationScenario.java', archive / 'QuestionAuthorizationScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionAuthorizationExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
