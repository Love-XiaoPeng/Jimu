"""Complete list qualification, fields, repeated reads and bounded response, private paid=false."""
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

class QuestionListingExperiment(Experiment):
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
    clock='SELECT CURRENT_TIMESTAMP(6)'
    joined='SELECT q.*,i.provider_id FROM user_question q JOIN invocation i ON i.tenant_id=q.tenant_id AND i.id=q.invocation_id WHERE q.tenant_id=? AND q.task_id=? ORDER BY q.ordinal'
    values=[];statements=[];shapes={};fixtures={};after={}
    assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'driver failure retained; no retry'
    for role in ['old','new']:
        fixtures[role]={};after[role]={};shapes[role]={}
        lines=(folder/(role+'.log')).read_text().splitlines()
        for line in lines:
            if line.startswith(('FIXTURE_PROOF ','PROOF ','LIST_SHAPE ')):
                kind,name,raw=line.split(' ',2)
                (fixtures[role] if kind=='FIXTURE_PROOF' else after[role] if kind=='PROOF' else shapes[role])[name]=json.loads(raw)
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        assert len(fixtures[role])==len(after[role])==len(shapes[role])==expected
    for left,right in zip(result['old']['results'],result['new']['results']):
        name=left['name'];assert name==right['name'] and left['code']==right['code']=='OK' and left['injected']==right['injected']==0
        values.append({'name':name,**{role:{'raw':v['raw'],'sha256':sha(v['raw']),'bytes':len(v['raw'].encode())} for role,v in [('old',left),('new',right)]},'boundary':'Validate complete own-side result against stored legal rows. Current database time, generated invocation/attempt IDs and top-level Map.of ordering are not byte equality contracts.'})
    for left,right in zip(result['old']['sql'],result['new']['sql']):
        name,l=left.split(' ',1);other,r=right.split(' ',1);assert name==other;l,r=json.loads(l),json.loads(r)
        assert [v['text'] for v in l]==[v['text'] for v in r],(name,'SQL sequence')
        assert [v['binds'] for v in l]==[v['binds'] for v in r],(name,'full binds')
        assert len({v['connection'] for v in l})==len({v['connection'] for v in r})==1
        own=[]
        for role,rows in [('old',l),('new',r)]:
            before=fixtures[role][name];obj=after[role][name];task=before['task'];items=obj['result']['items'];shape=shapes[role][name]
            assert obj['result']['version']==1 and before['questions']==obj['questions'] and before['invocations']==obj['invocations'] and before['events']==obj['events']
            clocks=[v for v in rows if v['text']==clock and v['questionNow']];joins=[v for v in rows if v['text']==joined]
            # Full tenant binding and original actor/installation lock chain, not only plugin-name substrings.
            locks=[v for v in rows if 'FOR SHARE' in v['text'] and ('actor_access' in v['text'] or 'installation' in v['text'])]
            for v in locks:assert v['binds']=={'1':task['tenant_id'],'2':task['owner_actor_id'] if 'actor_access' in v['text'] else ('web_fetch' if name=='nonquestion-list' else 'ask_user_question')}
            budget=next(i for i,v in enumerate(rows) if 'tenant_budget' in v['text'] and 'FOR UPDATE' in v['text']);assert rows[budget]['binds']=={'1':task['tenant_id']}
            if name=='nonquestion-list':
                assert items==shape==[] and not clocks and not joins and task['capabilities']=='web_fetch',task['capabilities']
            else:
                assert len(items)==len(shape)==len(before['questions'])==2 and len(clocks)==len(joins)==1
                index=rows.index(clocks[0]);joinIndex=rows.index(joins[0]);assert budget<index<joinIndex and locks and index>min(rows.index(v) for v in locks)
                assert joins[0]['binds']=={'1':task['tenant_id'],'2':task['id']} and joins[0]['setters']=={'1':'setString','2':'setString'}
                assert joins[0]['api']==('executeQuery' if role=='old' else 'execute')
                assert clocks[0]['statement']=='PreparedStatement' and clocks[0]['api']=='execute'
                clockRead=next(g for g in clocks[0]['getters'] if g['requestedType']=='java.time.LocalDateTime')['value']['isoValue'];now=datetime.datetime.fromisoformat(clockRead)
                assert [q['ordinal'] for q in before['questions']]==[1,2]
                expectedKeys=['requestId','questionVersion','revision','invocationId','callId','mode','phase','questions','foregroundDueAt','answerExpiresAt','answerable']
                projected=[]
                for q,item,form in zip(before['questions'],items,shape):
                    invocation=next(v for v in before['invocations'] if v['id']==q['invocation_id'])
                    assert sha(q['request_snapshot'])==q['request_digest'] and sha(task['question_binding'])==q['binding_digest']==task['question_binding_digest']
                    for field,column in [('requestId','id'),('questionVersion','question_version'),('revision','revision'),('invocationId','invocation_id'),('mode','mode'),('phase','phase')]:assert item[field]==q[column]
                    assert item['callId']==invocation['provider_id'] and item['questions']==json.loads(q['request_snapshot'])['questions']
                    def local(v):return None if v is None else datetime.datetime.fromisoformat(v['isoValue'])
                    def instant(v):return None if v is None else datetime.datetime.fromisoformat(v.removesuffix('Z'))
                    assert instant(item['foregroundDueAt'])==local(q['foreground_due_at']) and instant(item['answerExpiresAt'])==local(q['answer_expires_at'])
                    assert now<local(q['answer_expires_at']) and (q['foreground_due_at'] is None or now<local(q['foreground_due_at']))
                    assert item['answerable']==(q['phase'] in ['OPEN','CONTINUED'])
                    keys=expectedKeys.copy()
                    if q['answer_id'] is not None:
                        keys+=['answerId','answeredBy','answeredAt','answers','deliveryState']
                        assert item['answerId']==q['answer_id'] and item['answeredBy']==q['answered_actor_id'] and instant(item['answeredAt'])==local(q['answered_at'])
                        assert item['answers']==json.loads(q['answer_snapshot']) and sha(q['answer_snapshot'])==q['answer_digest'] and item['deliveryState']==q['delivery_state']
                    if q['close_code'] is not None:keys+=['closeCode'];assert item['closeCode']==q['close_code']
                    assert form['keys']==keys and form['mapClass']=='java.util.LinkedHashMap' and form['types']['questionVersion']==form['types']['revision']=='java.lang.Long' and form['types']['answerable']=='java.lang.Boolean'
                    projected.append({'id':q['id'],'keys':keys,'types':form['types']})
                # Existing full view runs once per row; retain its real artifact queries after the join.
                artifacts=[v for v in rows[joinIndex+1:] if 'FROM artifact ' in v['text']];assert len(artifacts)==2
                own.append({'role':role,'join':joins[0],'clock':clocks[0],'projected':projected,'fullViewArtifactReads':artifacts})
        assert shapes['old'][name]==shapes['new'][name]
        statements.append({'name':name,'fullSqlAndBindsEqual':True,'sqlCount':len(l),'operations':own})
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-listing-private'); task_id=task
    status,plain=e.http('POST','/api/tasks',{'goal':'question-listing-nonquestion','capabilityIds':['web_fetch']},key='question-listing-nonquestion')
    e.assert_that(status==202,'accept ordinary nonquestion task',status=status,value=plain)
    nonquestion=plain['taskId']
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
        config.write_text(config.read_text()+'\nlist.nonquestion='+nonquestion+'\n')
        configs[tag] = config
    (e.evidence / 'frozen-source.json').write_text(json.dumps({'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(),
        'task': task, 'copiesCreatedBeforeTrials': ['question_old', 'question_new'],
        'boundary': 'Queued actual question task; model response and stored matching timed question are explicit fixtures, no worker/provider/tool execution. Trials live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionListingScenario',
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
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '5490d94f373d47d4d5a4a61ba29403460bf20a3c9d9b7370245d6570391897f8'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionListingScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionListingScenario.java', archive / 'QuestionListingScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionListingExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
