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

class QuestionOpeningExperiment(Experiment):
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
    import datetime
    sha=lambda raw:hashlib.sha256(raw.encode()).hexdigest()
    summary=[]
    for role,data in result.items():
        assert data['exit']==0 and len(data['results'])==len(data['restored'])==len(data['sql'])==expected
        lines=(folder/(role+'.log')).read_text().splitlines()
        active=[json.loads(v[len('ACTIVE_LEASE_PROOF '):]) for v in lines if v.startswith('ACTIVE_LEASE_PROOF ')]
        before={v.split(' ',2)[1]:json.loads(v.split(' ',2)[2]) for v in lines if v.startswith('FIXTURE_PROOF ')}
        after={v.split(' ',2)[1]:json.loads(v.split(' ',2)[2]) for v in lines if v.startswith('PROOF ')}
        sql={v.split(' ',2)[1]:json.loads(v.split(' ',2)[2]) for v in lines if v.startswith('SQL ')}
        assert len(active)==expected
        for fact in active:
            lease=fact['lease'];task=fact['task'];attempt=fact['attempt']
            assert task['state']=='RUNNING' and attempt['state']=='ACTIVE' and task['current_attempt_id']==lease['attemptId']==attempt['id']
            assert task['worker_id']==lease['worker'] and task['fence']==lease['fence']
        for name,proof in after.items():
            pre=before[name];rows=sql[name];texts=[v['text'] for v in rows];task=proof['task'];original=pre['task'];r=proof['result'];history=name=='history'
            assert len({v['connection'] for v in rows})==1
            inv=[v for v in rows if v['text']=='SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? AND slot=? FOR UPDATE']
            assert len(inv)==1 and list(inv[0]['binds'].values())==[task['tenant_id'],task['id'],proof['step'][0]['id'],'0:read_1']
            assert r['suspended']==(not history)
            assert sum(t.startswith('SELECT COUNT(*)+1 FROM user_question') for t in texts)==(0 if history else 1)
            qinsert=[v for v in rows if v['text'].startswith('INSERT INTO user_question(')]
            if history:
                assert not qinsert and not proof['user_question'] and task['state']=='RUNNING'
                invocation=next(v for v in proof['invocation'] if v['provider_id']=='read_1')
                assert r['result']==json.loads(invocation['output_snapshot']) and sha(invocation['output_snapshot'])==invocation['output_digest']
                assert not any('question.created'==e['type'] for e in proof['event'])
            else:
                assert len(qinsert)==1 and len(proof['user_question'])==1
                q=proof['user_question'][0];v=qinsert[0];bind=v['binds'];assert len(bind)==16 and v['updateCount']==1
                assert list(bind.values())[:8]==[task['tenant_id'],q['id'],task['id'],'alice',q['invocation_id'],1,task['question_binding_digest'],q['mode']]
                assert sha(q['request_snapshot'])==q['request_digest']==bind['11'] and q['request_snapshot']==bind['10']
                assert q['phase']=='OPEN' and task['state']=='WAITING_USER' and task['current_attempt_id'] is None and task['worker_id'] is None and task['lease_until'] is None
                assert proof['task_attempt'][0]['state']=='PAUSED'
                assert task['tools_used']==original['tools_used']+1 and proof['budget']['tool_used']==pre['budget']['tool_used']+1
                reservation=next(v for v in proof['reservation'] if v['id']==q['invocation_id'])
                assert reservation['category']=='TOOL' and reservation['amount']==1 and reservation['state']=='RESERVED'
                invocation=next(v for v in proof['invocation'] if v['id']==q['invocation_id']);assert invocation['state']=='WAITING_USER'
                assert [e['type'] for e in proof['event']][-2:]==['question.created','task.waiting_user']
                if name=='blocking-new':
                    assert q['mode']=='blocking' and q['foreground_due_at'] is None and q['effective_timeout'] is None
                    assert all(bind[str(i)] is None for i in [9,12,13,14])
                else:
                    assert q['mode']=='timed' and q['effective_timeout']==120 and all(bind[str(i)]==120 for i in [9,12,13,14])
                    due=datetime.datetime.fromisoformat(q['foreground_due_at']['isoValue']);created=datetime.datetime.fromisoformat(q['created_at']['isoValue'])
                    assert 119.9<=(due-created).total_seconds()<=120.1
                assert q['answer_expires_at']==original['deadline']
                retain=datetime.datetime.fromisoformat(q['retain_until']['isoValue']);created=datetime.datetime.fromisoformat(original['created_at']['isoValue']);assert (retain-created).total_seconds()==604800
            # Per-side strings and lengths are evidence; independent generated values are not equal-byte contracts.
            raw=next(v.split(' ',2)[2] for v in lines if v.startswith('PROOF '+name+' '))
            summary.append({'role':role,'name':name,'sqlCount':len(rows),'proofSHA256':sha(raw),'proofBytes':len(raw.encode()),'sql':rows})
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
    for name in [v['name'] for v in summary if v['role']=='old']:
        pair=[v for v in summary if v['name']==name];assert len(pair)==2 and [r['text'] for r in pair[0]['sql']]==[r['text'] for r in pair[1]['sql']]
    (folder/'opening-comparison.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-opening-private'); task_id=task
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
        'boundary': 'Queued actual question task; model response and matching invocation intents are setup facts; only measured openOrResult creates questions, no worker/provider/tool execution. Trials live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionOpeningScenario',
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
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '61bdd9b446b2d67313a69da989db38d735847701e802679435967d6f25b62e22'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionOpeningScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionOpeningScenario.java', archive / 'QuestionOpeningScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionOpeningExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
