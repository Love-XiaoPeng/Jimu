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

class QuestionResumeExperiment(Experiment):
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
    assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'driver failure kept; no retry'
    values=[];statements=[]
    resume_sql="UPDATE task SET state='RESUME_READY',waiting_question_id=NULL WHERE tenant_id=? AND id=? AND state='WAITING_USER' AND waiting_question_id=?"
    for a,b in zip(result['old']['results'],result['new']['results']):
        name=a['name'];assert name==b['name'] and a['code']==b['code'] and a['injected']==b['injected']==0
        if a['raw']:
            left,right=json.loads(a['raw']),json.loads(b['raw'])
            for obj in [left,right]:
                t=obj['task'];q=obj['questions'][0]
                assert t['state']=='RESUME_READY' and t['waiting_question_id'] is None
                resumable=[v for v in obj['events'] if v['type']=='task.resumable']
                assert len(resumable)==1 and resumable[0]['fact_id']=='question-resume:'+q['id'] and resumable[0]['status']=='RESUME_READY'
                if name in ['foreground-answer','due-continue']:
                    assert q['phase']=='ANSWERED' and obj['result']['receipt']['delivery']==('TOOL_RESULT' if name=='foreground-answer' else 'USER_MESSAGE')
                    assert obj['invocations'][0]['state']=='SUCCEEDED'
                    assert any(v['category']=='TOOL' and v['state']=='SETTLED' and v['actual']==1 for v in obj['reservations'])
                    types=[v['type'] for v in obj['events']]
                    assert types.index('tool.completed')<types.index('task.resumable')<types.index('question.answered')
                    if name=='due-continue':assert types.index('task.resumable')<types.index('question.continued')<types.index('question.answered')
            assert [v['type'] for v in left['events']]==[v['type'] for v in right['events']]
        values.append({'name':name,'code':a['code'],**{tag:{'raw':v['raw'],'sha256':hashlib.sha256((v['raw'] or '').encode()).hexdigest(),'bytes':len((v['raw'] or '').encode())} for tag,v in [('old',a),('new',b)]},'boundary':'Generated IDs/times and Map.of raw order retained; compare only stated fields and own-side relationships.'})
    for a,b in zip(result['old']['sql'],result['new']['sql']):
        name,left=a.split(' ',1);other,right=b.split(' ',1);assert name==other;left,right=json.loads(left),json.loads(right)
        assert [v['text'] for v in left]==[v['text'] for v in right],(name,'SQL order')
        locks=lambda rows:[v for v in rows if 'FOR SHARE' in v['text'] and ('installation' in v['text'] or 'actor_access' in v['text'])]
        assert locks(left)==locks(right),(name,'full actor/installation locks')
        updates=[]
        for tag,rows in [('old',left),('new',right)]:
            update=[v for v in rows if v['text']==resume_sql];assert len(update)==1
            u=update[0];assert u['binds']=={'1':'tenant-a','2':task_id,'3':'00000000-0000-0000-0000-0000000000'+('99' if name=='direct-wrong' else '01')}
            assert u['updateCount']==(0 if name=='direct-wrong' else 1)
            assert u['setters']=={'1':'setString','2':'setString','3':'setString'}
            if name=='direct-wrong':assert len(rows)==1,'no event SQL after count0'
            if name=='foreground-answer':
                i=rows.index(u)
                assert any(v['text'].startswith("UPDATE user_question SET phase='ANSWERED'") for v in rows[:i])
                assert any(v['text'].startswith("UPDATE invocation SET state='SUCCEEDED'") for v in rows[:i])
                assert any(v['text'].startswith("UPDATE reservation SET state='SETTLED'") for v in rows[:i])
            if name=='due-continue':assert any(v['text'].startswith("UPDATE user_question SET phase='CONTINUED'") for v in rows[:rows.index(u)])
            updates.append({'role':tag,**u})
        statements.append({'name':name,'sqlOrderEqual':True,'count':len(left),'locks':{'old':locks(left),'new':locks(right)},'resumeUpdates':updates,'allBindsEqual':left==right})
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines();facts=[json.loads(s[6:]) for s in lines if s.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        rejects=[s.split(' ',2) for s in lines if s.startswith('REJECTION_PROOF ')]
        for _,name,raw in rejects:
            obj=json.loads(raw);assert name=='direct-wrong' and obj['task']['state']=='WAITING_USER' and obj['task']['waiting_question_id']=='00000000-0000-0000-0000-000000000001'
            assert not any(v['type']=='task.resumable' for v in obj['events'])
        fixtures=[s[len('FIXTURE_PROOF '):].split(' ',1) for s in lines if s.startswith('FIXTURE_PROOF ')];assert len(fixtures)==expected
        for name,raw in fixtures:
            obj=json.loads(raw);t=obj['task'];q=obj['questions'][0]
            assert json.loads(t['tools_snapshot'])['pins']['ask_user_question']['version']=='0.2.0'
            assert json.loads(t['question_binding'])['mode']==q['mode']=='timed' and q['phase']=='OPEN'
            assert t['state']=='WAITING_USER' and t['waiting_question_id']==q['id']
            assert hashlib.sha256(t['question_binding'].encode()).hexdigest()==t['question_binding_digest']==q['binding_digest']
            assert hashlib.sha256(q['request_snapshot'].encode()).hexdigest()==q['request_digest']
            assert obj['invocations'][0]['state']=='WAITING_USER' and q['task_id']==t['id'] and q['invocation_id']==obj['invocations'][0]['id']
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-resume-private'); task_id=task
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionResumeScenario',
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
    compare(e.evidence, result, 4 if phase=='all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '5663d8f3fb5b58d318c9ef7e6817bce01581fbc99c8a9c9f572361b9119b166e'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionResumeScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionResumeScenario.java', archive / 'QuestionResumeScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionResumeExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
