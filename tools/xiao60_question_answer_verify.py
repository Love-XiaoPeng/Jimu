"""Complete answer admission and five original SQL sites, private paid=false. Foreground/replay, due-stale/late and expiry in bounded outer rollback."""
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
from question_projection_sql import expected_sql,expanded_sql

class QuestionAnswerExperiment(Experiment):
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
    sha=lambda s:hashlib.sha256(s.encode()).hexdigest()
    seq='SELECT COALESCE(MAX(accepted_seq),0)+1 FROM user_question WHERE tenant_id=? AND task_id=?'
    update="UPDATE user_question SET phase='ANSWERED',revision=revision+1,answer_id=?,answered_actor_id=?,answered_at=?,accepted_seq=?,answer_snapshot=?,answer_digest=?,answer_key=?,answer_request_digest=?,answer_source_revision=?,receipt_snapshot=?,delivery_state=? WHERE tenant_id=? AND id=? AND revision=? AND answer_id IS NULL"
    allcalls={};summary=[]
    for role,data in result.items():
        lock=expected_sql(role,'SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE')
        previous=expected_sql(role,'SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND owner_actor_id=? AND answer_key=?')
        reload=expected_sql(role,'SELECT * FROM user_question WHERE tenant_id=? AND id=?')
        assert data['exit']==0 and len(data['results'])==len(data['restored'])==len(data['sql'])==expected
        lines=(folder/(role+'.log')).read_text().splitlines();calls=[json.loads(v[12:]) for v in lines if v.startswith('ANSWER_CALL ')]
        assert len(calls)==5
        allcalls[role]=calls
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        for v in calls:
            raw=v['proofRaw'];assert sha(raw)==v['proofSHA256'] and len(raw.encode())==v['proofBytes'];proof=json.loads(raw)
            task=proof['task'];rows=v['sql'];q=next(q for q in proof['questions'] if q['id']=='00000000-0000-0000-0000-000000000001');name=v['name'];texts=[r['text'] for r in rows]
            assert len({r['connection'] for r in rows})==1
            assert texts[0].startswith('SELECT') and 'tenant_budget' in texts[0] and rows[0]['binds']=={'1':task['tenant_id']}
            assert len([r for r in rows if r['text']==lock])==len([r for r in rows if r['text']==previous])==1
            for r in rows:
                if r['text']==lock:assert r['binds']=={'1':task['tenant_id'],'2':task['id'],'3':q['id']}
                if r['text']==previous:assert r['binds']=={'1':task['tenant_id'],'2':task['id'],'3':'alice','4':v['key']}
                if 'FOR SHARE' in r['text'] and ('actor_access' in r['text'] or 'installation' in r['text']):assert r['binds']=={'1':task['tenant_id'],'2':'alice' if 'actor_access' in r['text'] else 'ask_user_question'}
            clocks=[r for r in rows if r['text']=='SELECT CURRENT_TIMESTAMP(6)']
            assert len(clocks)==(0 if name=='replay' else 1)
            writes=[r for r in rows if r['text']==update]
            assert len(writes)==(1 if name in ['foreground','late'] else 0)
            assert len([r for r in rows if r['text']==seq])==len(writes)
            if name in ['foreground','late','replay']:
                assert v['code']=='OK' and v['replay']==(name=='replay') and v['receipt']==json.loads(q['receipt_snapshot'])
                assert q['phase']=='ANSWERED' and q['answer_id']==v['receipt']['answerId'] and q['accepted_seq']==2
                assert sha(q['answer_snapshot'])==q['answer_digest'] and json.loads(q['answer_snapshot'])==[{'id':'q1','selected':[],'custom':'选择🙂'}]
                assert q['delivery_state']==('PENDING' if name=='late' else 'CHECKPOINTED')
            if writes:
                w=writes[0];b=w['binds'];assert w['updateCount']==1 and len(b)==14
                assert [b[str(i)] for i in [1,2,4,5,6,7,8,9,10,11,12,13,14]]==[q['answer_id'],'alice',2,q['answer_snapshot'],q['answer_digest'],v['key'],q['answer_request_digest'],v['revision'],q['receipt_snapshot'],q['delivery_state'],task['tenant_id'],q['id'],v['revision']]
                assert b['3']['isoValue']==v['receipt']['answeredAt']
                assert w['api']==('executeUpdate' if role=='old' else 'execute')
            events=[e['type'] for e in proof['events']]
            if name=='replay':assert not any(t.startswith(('UPDATE ','INSERT ')) for t in texts) and all(v['receipt']==u['receipt'] for u in calls if u['name']=='foreground')
            if name=='foreground':assert events[-3:]==['tool.completed','task.resumable','question.answered'] and not any(r['text']==reload for r in rows)
            if name=='due-stale':
                assert v['code']=='QUESTION_REVISION_CONFLICT' and v['status']==409 and q['phase']=='CONTINUED' and q['revision']==3 and q['answer_id'] is None
                assert events[-3:]==['tool.completed','task.resumable','question.continued'] and len([r for r in rows if r['text']==reload])==1
            if name=='late':assert events[-4:]==['tool.completed','task.resumable','question.continued','question.answered'] and not any("UPDATE invocation SET state='SUCCEEDED'" in t for t in texts)
            if name=='expired':assert v['code']=='QUESTION_EXPIRED' and v['status']==410 and q['phase']=='CLOSED' and task['state']=='FAILED' and task['code']=='DEADLINE_EXCEEDED' and events[-1]=='task.failed'
            if name!='replay':
                clock=next(g['value']['isoValue'] for g in clocks[0]['getters'] if g.get('requestedType')=='java.time.LocalDateTime');now=datetime.datetime.fromisoformat(clock);end=datetime.datetime.fromisoformat(q['answer_expires_at']['isoValue'])
                assert (now>=end)==(name=='expired')
            # Every captured full question ResultSet retains column names, order and effective values; raw getters differ.
            summary.append({'role':role,'name':name,'code':v['code'],'sqlCount':len(rows),'sql':rows,'proofSHA256':v['proofSHA256'],'proofBytes':v['proofBytes']})
    assert [v['name'] for v in allcalls['old']]==[v['name'] for v in allcalls['new']]
    for left,right in zip(allcalls['old'],allcalls['new']):
        assert left['code']==right['code'] and [expanded_sql(r['text']) for r in left['sql']]==[r['text'] for r in right['sql']]
    (folder/'answer-comparison.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-answer-private'); task_id=task
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionAnswerScenario',
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
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'b29314a5390bc3bad2ed0bc11a2873df95674dd5bf94207b392252aca1eb510a'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionAnswerScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionAnswerScenario.java', archive / 'QuestionAnswerScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionAnswerExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
