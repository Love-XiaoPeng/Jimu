"""Database clock and UTC conversion, private paid=false; Statement and prepared observations."""
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

class QuestionClockExperiment(Experiment):
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
    import datetime,calendar,re
    clock='SELECT CURRENT_TIMESTAMP(6)'
    sha=lambda s:hashlib.sha256(s.encode()).hexdigest()
    def stamp(v):
        t=datetime.datetime.fromisoformat(v['isoValue']);return calendar.timegm(t.timetuple()),t.microsecond*1000
    values=[];statements=[];fixtures={};after={};settings={}
    assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'driver failure retained; no retry'
    for role in ['old','new']:
        lines=(folder/(role+'.log')).read_text().splitlines();fixtures[role]={};after[role]={};settings[role]={}
        for line in lines:
            if line.startswith(('FIXTURE_PROOF ','PROOF ')):
                kind,name,raw=line.split(' ',2);(fixtures[role] if kind=='FIXTURE_PROOF' else after[role])[name]=json.loads(raw)
            if line.startswith('SETTINGS '):
                _,name,raw=line.split(' ',2);settings[role][name]=json.loads(raw)
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')];assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        assert len(fixtures[role])==len(after[role])==expected
    for left,right in zip(result['old']['results'],result['new']['results']):
        name=left['name'];assert name==right['name'] and left['code']==right['code']=='OK' and left['injected']==right['injected']==0
        values.append({'name':name,**{role:{'raw':v['raw'],'sha256':sha(v['raw']),'bytes':len(v['raw'].encode())} for role,v in [('old',left),('new',right)]},'boundary':'Compare own observed LocalDateTime/UTC Instant and stated fields only. Independent wall-clock values and generated IDs are not cross-side equal.'})
    for left,right in zip(result['old']['sql'],result['new']['sql']):
        name,l=left.split(' ',1);other,r=right.split(' ',1);assert name==other;l,r=json.loads(l),json.loads(r)
        norm=lambda rows:[re.sub(r'=\d+$','=remaining',v['text']) if v['text'].startswith('SET SESSION max_execution_time=') else v['text'] for v in rows]
        assert norm(l)==norm(r),(name,'SQL sequence')
        own=[]
        for role,rows in [('old',l),('new',r)]:
            before=fixtures[role][name];obj=after[role][name];task=before['task'];q=before['questions'][0]
            assert q['phase']=='OPEN' and q['mode']=='timed' and task['state']=='WAITING_USER'
            assert sha(q['request_snapshot'])==q['request_digest'] and sha(task['question_binding'])==q['binding_digest']==task['question_binding_digest']
            clocks=[v for v in rows if v['text']==clock and v['questionNow']];assert len(clocks)==(4 if name=='direct-clock' else 1)
            typed=[]
            for v in clocks:
                assert v['binds']==v['setters']=={} and v['statement']==('Statement' if role=='old' else 'PreparedStatement')
                assert v['api']==('executeQuery' if role=='old' else 'execute')
                getters=[g for g in v['getters'] if g['requestedType']=='java.time.LocalDateTime'];assert len(getters)==1
                g=getters[0];assert g['type']=='java.time.LocalDateTime' and g['method']=='getObject'
                assert g['column']==(1 if role=='old' else 'CURRENT_TIMESTAMP(6)')
                sec,nano=stamp(g['value']);assert nano%1000==0;typed.append({'local':g['value'],'epochSecond':sec,'nano':nano})
            if name=='direct-clock':
                value=obj['result'];returned=value['plain']+value['scoped'];assert len(returned)==4
                for actual,original in zip(returned,typed):assert (actual['epochSecond'],actual['nano'])==(original['epochSecond'],original['nano']) and stamp(actual)==(original['epochSecond'],original['nano'])
                assert value['before']=={'reads':7,'bytes':100,'operations':13} and value['after']=={'reads':9,'bytes':210,'operations':17}
                assert value['deadline']==value['deadlineAfter'] and len(value['columns'])==2
                for column,observed in zip(value['columns'],typed[2:]):assert column['label']=='CURRENT_TIMESTAMP(6)' and column['value']==observed['local']
                assert len({v['connection'] for v in rows})==1
                network=[v for v in settings[role][name] if v['method']=='setNetworkTimeout'];assert len(network)==2 and all(0<v['millis']<=2000 for v in network)
                limits=[v for v in rows if v['text'].startswith('SET SESSION max_execution_time=')];assert len(limits)==2 and all(0<int(v['text'].split('=')[1])<=2000 for v in limits)
                assert obj['questions']==before['questions'] and obj['invocations']==before['invocations'] and obj['events']==before['events']
            else:
                locks=[v for v in rows if 'FOR SHARE' in v['text'] and ('actor_access' in v['text'] or 'installation' in v['text'])];assert locks
                for v in locks:assert v['binds']=={'1':task['tenant_id'],'2':task['owner_actor_id'] if 'actor_access' in v['text'] else 'ask_user_question'}
                index=rows.index(clocks[0]);assert index>max(rows.index(v) for v in locks)
                assert typed[0]['epochSecond']<stamp(q['answer_expires_at'])[0] and typed[0]['epochSecond']<stamp(q['foreground_due_at'])[0]
                if name=='owner-list':
                    joined=next(i for i,v in enumerate(rows) if v['text'].startswith('SELECT q.*,i.provider_id FROM user_question q JOIN invocation i') and 'ORDER BY q.ordinal' in v['text']);assert index<joined
                    items=obj['result']['items'];assert len(items)==1 and items[0]['requestId']==q['id'] and items[0]['revision']==q['revision'] and items[0]['phase']=='OPEN' and items[0]['answerable'] is True
                    assert obj['questions']==before['questions'] and obj['events']==before['events']
                else:
                    previous=next(i for i,v in enumerate(rows) if v['text']=='SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND owner_actor_id=? AND answer_key=?');assert previous<index
                    qlock=next(i for i,v in enumerate(rows) if v['text']=='SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE');assert qlock<previous
                    answered=next(i for i,v in enumerate(rows) if v['text'].startswith("UPDATE user_question SET phase='ANSWERED'"));assert index<answered
                    assert obj['result']['receipt']['delivery']=='TOOL_RESULT' and obj['questions'][0]['phase']=='ANSWERED'
                    types=[v['type'] for v in obj['events']];assert types.index('tool.completed')<types.index('task.resumable')<types.index('question.answered') and 'question.continued' not in types
            own.append({'role':role,'clockOperations':clocks,'observedLocalAndUtc':typed,'settings':settings[role][name],'meter':obj['result'] if name=='direct-clock' else None})
        statements.append({'name':name,'sqlSequenceEqualExceptRemainingTimeoutValues':True,'count':len(l),'operations':own})
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-clock-private'); task_id=task
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionClockScenario',
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
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == '29d39520cd4f411c2746d9c191599657f33e348ec5cd08dfaf3cb131a505df5e'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionClockScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionClockScenario.java', archive / 'QuestionClockScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionClockExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
