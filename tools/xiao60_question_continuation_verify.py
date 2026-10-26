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

class QuestionContinuationExperiment(Experiment):
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
    assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'driver failure retained; no retry'
    read="SELECT provider_id FROM invocation WHERE tenant_id=? AND id=? AND task_id=?"
    phase="UPDATE user_question SET phase='CONTINUED',revision=revision+1 WHERE tenant_id=? AND id=? AND phase='OPEN' AND revision=?"
    norm=lambda text:''.join(c for c in text if not c.isspace() and c not in '()')
    values=[];statements=[];fixtures={};after={}
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines();fixtures[tag]={};after[tag]={}
        for line in lines:
            if line.startswith(('FIXTURE_PROOF ','REJECTION_PROOF ','PROOF ')):
                kind,name,raw=line.split(' ',2);obj=json.loads(raw);(fixtures[tag] if kind=='FIXTURE_PROOF' else after[tag])[name]=obj
        assert len(fixtures[tag])==len(after[tag])==expected
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        for name,before in fixtures[tag].items():
            t=before['task'];q=before['questions'][0];inv=before['invocations'][0]
            assert json.loads(t['tools_snapshot'])['pins']['ask_user_question']['version']=='0.2.0'
            assert json.loads(t['question_binding'])['mode']==q['mode']=='timed' and q['phase']=='OPEN'
            assert t['state']=='WAITING_USER' and t['waiting_question_id']==q['id']
            assert hashlib.sha256(t['question_binding'].encode()).hexdigest()==t['question_binding_digest']==q['binding_digest']
            assert hashlib.sha256(q['request_snapshot'].encode()).hexdigest()==q['request_digest']
            assert inv['state']=='WAITING_USER' and q['task_id']==t['id'] and q['invocation_id']==inv['id']
            assert next(v for v in before['reservations'] if v['id']==inv['id'] and v['category']=='TOOL')['state']=='RESERVED'
            obj=after[tag][name];types=[v['type'] for v in obj['events']];oldtypes=[v['type'] for v in before['events']]
            if name=='invocation-empty':assert obj==before
            elif name=='phase-zero':
                assert obj['invocations'][0]['state']=='SUCCEEDED' and obj['questions']==before['questions']
                assert types==oldtypes+['tool.completed'] and obj['task']['state']=='WAITING_USER' and obj['task']['waiting_question_id']==q['id']
                assert obj['task']['revision']==t['revision']+1
                assert next(v for v in obj['reservations'] if v['id']==inv['id'] and v['category']=='TOOL')['state']=='SETTLED'
            else:
                assert obj['task']['state']=='RESUME_READY' and obj['task']['waiting_question_id'] is None
                assert obj['questions'][0]['phase']=='ANSWERED' and obj['result']['receipt']['delivery']=='USER_MESSAGE'
                assert types.index('tool.completed')<types.index('task.resumable')<types.index('question.continued')<types.index('question.answered')
    for a,b in zip(result['old']['results'],result['new']['results']):
        name=a['name'];assert name==b['name'] and a['code']==b['code'] and a['injected']==b['injected']==0
        assert a['code']=={'invocation-empty':'NOT_FOUND','phase-zero':'QUESTION_REVISION_CONFLICT'}.get(name,'OK')
        values.append({'name':name,'code':a['code'],**{tag:{'raw':v['raw'],'sha256':hashlib.sha256((v['raw'] or '').encode()).hexdigest(),'bytes':len((v['raw'] or '').encode())} for tag,v in [('old',a),('new',b)]},'boundary':'Compare stated fields and own-side relationships only; retain generated IDs/time/Map raw order.'})
    for a,b in zip(result['old']['sql'],result['new']['sql']):
        name,left=a.split(' ',1);other,right=b.split(' ',1);assert name==other;left,right=json.loads(left),json.loads(right)
        normalize=lambda rows:[norm(v['text']) if norm(v['text'])==norm(read) else v['text'] for v in rows]
        assert normalize(left)==normalize(right),(name,'SQL sequence')
        locks=lambda rows:[v for v in rows if 'FOR SHARE' in v['text'] and ('installation' in v['text'] or 'actor_access' in v['text'])]
        assert locks(left)==locks(right)
        operations=[]
        for tag,rows in [('old',left),('new',right)]:
            before=fixtures[tag][name];t=before['task'];q=before['questions'][0];inv=before['invocations'][0];obj=after[tag][name]
            reads=[v for v in rows if norm(v['text'])==norm(read)];assert len(reads)==1;u=reads[0]
            assert u['binds']=={'1':t['tenant_id'],'2':'00000000-0000-0000-0000-000000000099' if name=='invocation-empty' else inv['id'],'3':t['id']}
            assert u['setters']=={'1':'setString','2':'setString','3':'setString'}
            assert len(u['getters'])==(0 if name=='invocation-empty' else 1)
            if u['getters']:assert u['getters'][0]['value']==inv['provider_id'] and u['getters'][0]['type']=='java.lang.String'
            updates=[v for v in rows if v['text']==phase];assert len(updates)==(0 if name=='invocation-empty' else 1)
            if name=='invocation-empty':assert len(rows)==1
            else:
                v=updates[0];assert v['binds']=={'1':t['tenant_id'],'2':q['id'],'3':q['revision']-(name=='phase-zero')}
                assert v['setters']=={'1':'setString','2':'setString','3':'setObject' if tag=='old' else 'setLong'} and v['updateCount']==(0 if name=='phase-zero' else 1)
                event=lambda type:[(i,v) for i,v in enumerate(rows) if v['text'].startswith('INSERT INTO event(') and v['binds']['6']==type]
                tool=event('tool.completed');assert len(tool)==1 and tool[0][0]<rows.index(v)
                outputs=[v for v in rows if v['text'].startswith("UPDATE invocation SET state='SUCCEEDED',output_snapshot=")];assert len(outputs)==1
                raw=outputs[0]['binds']['1'];assert hashlib.sha256(raw.encode()).hexdigest()==outputs[0]['binds']['2']
                assert obj['invocations'][0]['output_snapshot']==raw and obj['invocations'][0]['output_digest']==outputs[0]['binds']['2']
                if name=='phase-zero':assert not event('task.resumable') and not event('question.continued')
                else:
                    resumed=event('task.resumable');continued=event('question.continued');assert len(resumed)==len(continued)==1 and rows.index(v)<resumed[0][0]<continued[0][0]
            operations.append({'role':tag,'providerRead':u,'phaseUpdates':updates,'output':None if name=='invocation-empty' else {'raw':raw,'sha256':hashlib.sha256(raw.encode()).hexdigest(),'bytes':len(raw.encode())}})
        statements.append({'name':name,'sqlSequenceEqualExceptProviderParenthesesSpaces':True,'count':len(left),'locks':{'old':locks(left),'new':locks(right)},'operations':operations})
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-continuation-private'); task_id=task
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionContinuationScenario',
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
    compare(e.evidence, result, 3 if phase=='all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '88dfec6d662e9c73f92ba91e73e0c7c8caf338f5769cd230858b3703a4dc89d0'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionContinuationScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionContinuationScenario.java', archive / 'QuestionContinuationScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionContinuationExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
