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

class QuestionInputExperiment(Experiment):
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
    assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'driver error retained; no retry'
    values=[];statements=[]
    for a,b in zip(result['old']['results'],result['new']['results']):
        name=a['name'];assert name==b['name'] and a['code']==b['code'] and a['injected']==b['injected']==0
        if a['raw']:
            l,r=json.loads(a['raw']),json.loads(b['raw'])
            for key in ['state','capabilities','question_binding','question_binding_digest','tools_used']:
                assert l['task'][key]==r['task'][key],(name,key)
            ql,qr=l['questions'][0],r['questions'][0]
            for key in ['id','mode','phase','revision','binding_digest','request_snapshot','request_digest','answer_snapshot','answer_digest','delivery_state']:
                assert ql[key]==qr[key],(name,key)
            if name=='owner-list':
                li,ri=l['result']['items'][0],r['result']['items'][0]
                for key in ['requestId','questionVersion','revision','callId','mode','phase','questions','answerable']:
                    assert li[key]==ri[key],(name,key)
                assert li['answerable'] is True and li['mode']=='timed' and len(li['questions'])==1
            if name.startswith('answer-'):
                assert l['result']['replay']==r['result']['replay']==(name=='answer-replay')
                for obj in [l,r]:
                    receipt=obj['result']['receipt'];q=obj['questions'][0]
                    assert receipt['answerId']==q['answer_id'] and receipt['requestId']==q['id']
                    assert receipt['delivery']=='USER_MESSAGE' and receipt['acceptedRevision']==3
            if name=='late-messages':
                for obj in [l,r]:
                    reply=json.loads(obj['result'][obj['questions'][0]['id']])
                    assert reply['questions']==[{'id':'q1','question':'请选择🙂'}]
                    assert reply['answers']==json.loads(obj['questions'][0]['answer_snapshot'])
        else: assert a['raw']==b['raw']
        values.append({'name':name,'code':a['code'],**{tag:{'raw':v['raw'],'sha256':hashlib.sha256((v['raw'] or '').encode()).hexdigest(),'bytes':len((v['raw'] or '').encode())} for tag,v in [('old',a),('new',b)]},'boundary':'Only stated fields compared; generated IDs/times and Map.of originals separately retained.'})
    for a,b in zip(result['old']['sql'],result['new']['sql']):
        name,left=a.split(' ',1);other,right=b.split(' ',1);assert name==other
        left,right=json.loads(left),json.loads(right)
        assert [v['text'] for v in left]==[v['text'] for v in right],(name,'SQL sequence')
        locks=lambda rows:[v for v in rows if 'FOR SHARE' in v['text'] and ('installation' in v['text'] or 'actor_access' in v['text'])]
        assert locks(left)==locks(right),(name,'full actor/installation lock binds')
        for rows in [left,right]:
            for row in rows:
                if row['text'].startswith('SELECT q.*,i.provider_id FROM user_question'):
                    assert row['binds']=={'1':'tenant-a','2':task_id}
                if row['text'].startswith('SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE'):
                    assert row['binds']=={'1':'tenant-a','2':task_id,'3':'00000000-0000-0000-0000-000000000001'}
        statements.append({'name':name,'sqlOrderEqual':True,'count':len(left),'locks':{'old':locks(left),'new':locks(right)},'allBindsEqual':left==right})
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines()
        facts=[json.loads(s[6:]) for s in lines if s.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        fixtures=[s[len('FIXTURE_PROOF '):].split(' ',1) for s in lines if s.startswith('FIXTURE_PROOF ')]
        assert len(fixtures)==expected
        for name,raw in fixtures:
            obj=json.loads(raw);t=obj['task'];q=obj['questions'][0];pins=json.loads(t['tools_snapshot'])['pins']
            assert pins['ask_user_question']['version']=='0.2.0' and json.loads(t['question_binding'])['mode']==q['mode']=='timed'
            assert hashlib.sha256(t['question_binding'].encode()).hexdigest()==t['question_binding_digest']==q['binding_digest']
            assert (hashlib.sha256(q['request_snapshot'].encode()).hexdigest()==q['request_digest'])==(name!='list-digest')
            assert q['task_id']==t['id'] and q['invocation_id']==obj['invocations'][0]['id']
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-input-private'); task_id=task
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionInputScenario',
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
    compare(e.evidence, result, 6 if phase=='all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '37eedf2f468b22793fc819cc0142bb820bd4c443c0f978e98bad757d6f073376'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionInputScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionInputScenario.java', archive / 'QuestionInputScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionInputExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
