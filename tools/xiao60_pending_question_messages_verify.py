"""Complete openOrResult and six SQL sites; valid active leases, private paid=false, outer rollback."""
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

class PendingQuestionMessagesExperiment(Experiment):
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
        status,value=self.http('POST','/api/tasks',{'goal':goal,'capabilityIds':['web_fetch'] if self.sample=='nonquestion' else ['ask_user_question']},key=goal)
        self.assert_that(status==202,'accept question without worker',status=status,value=value)
        return value['taskId']

def compare(folder,result,expected):
    summary=[]
    join="SELECT q.*,i.provider_id FROM user_question q JOIN invocation i ON i.tenant_id=q.tenant_id AND i.id=q.invocation_id WHERE q.tenant_id=? AND q.task_id=? AND q.delivery_state='PENDING' ORDER BY q.accepted_seq"
    for role,data in result.items():
        assert data['exit']==0 and len(data['results'])==len(data['restored'])==len(data['sql'])==1
        lines=(folder/(role+'.log')).read_text().splitlines()
        get=lambda prefix:json.loads(next(v.split(' ',2)[2] for v in lines if v.startswith(prefix)))
        pre=get('FIXTURE_PROOF ');post=get('PROOF ');rows=get('SQL ');name=data['results'][0]['name']
        active=json.loads(next(v[len('ACTIVE_LEASE_PROOF '):] for v in lines if v.startswith('ACTIVE_LEASE_PROOF ')))
        lease=active['lease'];task=active['task'];attempt=active['attempt']
        assert task['state']=='RUNNING' and attempt['state']=='ACTIVE'
        assert task['current_attempt_id']==lease['attemptId']==attempt['id'] and task['worker_id']==lease['worker'] and task['fence']==lease['fence']
        assert len({v['connection'] for v in rows})==1
        assert pre['user_question']==post['user_question'] and pre['event']==post['event']
        assert pre['invocation']==post['invocation'] and pre['budget']==post['budget']
        assert post['task']['revision']==pre['task']['revision']+1
        fence=[v for v in rows if v['text'].startswith('UPDATE task SET revision=revision+1')];assert len(fence)==1
        pending=[v for v in rows if v['text']==join]
        if name=='pending':
            assert len(pending)==1 and list(pending[0]['binds'].values())==['tenant-a',task['id']]
            questions=sorted(pre['user_question'],key=lambda q:q['accepted_seq']);assert len(post['result'])==2
            for q,raw in zip(questions,post['result']):
                message=json.loads(raw);inv=next(v for v in pre['invocation'] if v['id']==q['invocation_id'])
                assert q['phase']=='ANSWERED' and q['delivery_state']=='PENDING'
                assert hashlib.sha256(q['answer_snapshot'].encode()).hexdigest()==q['answer_digest']
                assert hashlib.sha256(q['request_snapshot'].encode()).hexdigest()==q['request_digest']
                assert message=={'version':1,'kind':'answer_to_pending_question','tool':'ask_user_question','requestId':q['id'],'callId':inv['provider_id'],'questions':json.loads(q['request_snapshot'])['questions'],'answers':json.loads(q['answer_snapshot'])}
        else:assert post['result']==[] and not pending and pre['task']['question_binding'] is None
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')];assert len(facts)==1 and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        summary.append({'role':role,'name':name,'sqlCount':len(rows),'returned':post['result'],'strings':[{'raw':v,'sha256':hashlib.sha256(v.encode()).hexdigest(),'utf8Bytes':len(v.encode())} for v in post['result']],'sql':rows})
    assert [v['text'] for v in summary[0]['sql']]==[v['text'] for v in summary[1]['sql']]
    assert summary[0]['returned']==summary[1]['returned']
    (folder/'pending-comparison.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.sample=phase
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('pending-question-messages-private'); task_id=task
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
        'boundary': 'Public pendingQuestionMessages with actual ACTIVE lease. Explicit lawful double-call responses/intents/answers/receipts are setup, not Host/tool/provider generation. Only 21 tenant-a tables restored by outer rollback; worker_process excluded. No prepare-consume measured, no durable commit.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.PendingQuestionMessagesScenario',
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
    compare(e.evidence, result, 1)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'b3495f3b602d4808effa1efce48042166b7dbb5b47c724afaa44914e5e078462'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('PendingQuestionMessagesScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/PendingQuestionMessagesScenario.java', archive / 'PendingQuestionMessagesScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    for sample in (['pending','nonquestion'] if args.phase=='all' else args.phase.split(',')):
        experiment=PendingQuestionMessagesExperiment(args.jar,args.evidence/sample)
        try:
            verify(experiment,args.old_jar,classes,sample)
        except BaseException:
            (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
            raise
        finally:
            experiment.cleanup()
