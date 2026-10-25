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

class QuestionPauseExperiment(Experiment):
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
    first_sql="UPDATE task_attempt SET state='PAUSED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND fence=? AND state='ACTIVE'"
    second_sql="UPDATE task SET state=?,question_continuation_step_id=?,fence=fence+1,current_attempt_id=NULL,worker_id=NULL,lease_until=NULL WHERE tenant_id=? AND id=? AND fence=?"
    values=[];statements=[];proofs={};leases={}
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines()
        facts=[json.loads(s[6:]) for s in lines if s.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
        proofs[tag]={};leases[tag]=[json.loads(s[12:]) for s in lines if s.startswith('LEASE_PROOF ')]
        for line in lines:
            if line.startswith(('FIXTURE_PROOF ','PROOF ','REJECTION_PROOF ')):
                kind,name,raw=line.split(' ',2);proofs[tag][(kind,name)]=json.loads(raw)
        assert len(leases[tag])==expected
    for index,(a,b) in enumerate(zip(result['old']['results'],result['new']['results'])):
        name=a['name'];assert name==b['name'] and a['code']==b['code'] and a['injected']==b['injected']==0
        pairs=[]
        for tag,item in [('old',a),('new',b)]:
            before=proofs[tag][('FIXTURE_PROOF',name)];after=proofs[tag][('PROOF' if item['code']=='OK' else 'REJECTION_PROOF',name)]
            t=before['task'];u=after['task'];lease=leases[tag][index]
            attempt=next(v for v in before['attempts'] if v['id']==lease['attempt']);end=next(v for v in after['attempts'] if v['id']==lease['attempt'])
            assert [lease[k] for k in ['tenant','task','attempt','worker']]==[t[k] for k in ['tenant_id','id','current_attempt_id','worker_id']]
            assert attempt['state']=='ACTIVE' and attempt['worker_id']==lease['worker'] and attempt['tenant_id']==lease['tenant'] and attempt['task_id']==lease['task']
            assert attempt['fence']==lease['fence']+(name=='attempt-fence') and t['fence']==lease['fence']+(name=='task-fence')
            if name=='attempt-fence':assert end==attempt and u==t and after['events']==before['events']
            elif name=='task-fence':
                assert end['state']=='PAUSED' and end['ended_at'] is not None and u==t and after['events']==before['events']
            else:
                assert end['state']=='PAUSED' and end['ended_at'] is not None
                assert u['fence']==t['fence']+1 and u['current_attempt_id'] is None and u['worker_id'] is None and u['lease_until'] is None
                assert u['state']==('RESUME_READY' if name=='public-finish' else 'WAITING_USER')
                assert u['waiting_question_id']==t['waiting_question_id'] and u['ready_at']==t['ready_at'] and u['deadline']==t['deadline']
                assert u['revision']==t['revision']+2*(name=='public-finish')  # Original fenced increment plus caller-owned event.
                assert u['updated_at']==t['updated_at'] if name!='public-finish' else True
                if name=='public-finish':
                    q=before['questions'][0];assert q['delivery_state']=='PENDING' and q['phase']=='ANSWERED'
                    assert json.loads(t['tools_snapshot'])['pins']['ask_user_question']['version']=='0.2.0' and json.loads(t['question_binding'])['mode']==q['mode']=='timed'
                    assert hashlib.sha256(t['question_binding'].encode()).hexdigest()==t['question_binding_digest']==q['binding_digest']
                    for field in ['request','answer']:assert hashlib.sha256(q[field+'_snapshot'].encode()).hexdigest()==q[field+'_digest']
                    assert u['question_continuation_step_id']==before['steps'][-1]['id']
                    assert after['questions']==before['questions'] and after['result']=={'publicQuestionSuspended':True,'internalTrueBranchByUnchangedSource':True}
                    events=after['events'][len(before['events']):];assert len(events)==1
                    assert events[0]['fact_id']=='question-final-resume:'+u['question_continuation_step_id'] and events[0]['type']=='task.resumable' and events[0]['status']=='RESUME_READY'
                else:assert u['question_continuation_step_id'] is None and after['events']==before['events']
            pairs.append({'role':tag,'before':before,'after':after})
        values.append({'name':name,'code':a['code'],**{tag:{'raw':v['raw'],'sha256':hashlib.sha256((v['raw'] or '').encode()).hexdigest(),'bytes':len((v['raw'] or '').encode())} for tag,v in [('old',a),('new',b)]},'ownSideProofs':pairs,'boundary':'Generated IDs/times/Map.of order retained; only proven fields and own-side relations compared.'})
    for index,(a,b) in enumerate(zip(result['old']['sql'],result['new']['sql'])):
        name,left=a.split(' ',1);other,right=b.split(' ',1);assert name==other;left,right=json.loads(left),json.loads(right)
        assert [v['text'] for v in left]==[v['text'] for v in right],(name,'prepared SQL order')
        locks=lambda rows:[v for v in rows if 'FOR SHARE' in v['text'] and ('installation' in v['text'] or 'actor_access' in v['text'])]
        assert locks(left)==locks(right)
        updates=[]
        for tag,rows in [('old',left),('new',right)]:
            lease=leases[tag][index];before=proofs[tag][('FIXTURE_PROOF',name)]
            first=[v for v in rows if v['text']==first_sql];second=[v for v in rows if v['text']==second_sql]
            assert len(first)==1 and len(second)==(0 if name=='attempt-fence' else 1)
            assert first[0]['binds']=={'1':lease['tenant'],'2':lease['attempt'],'3':lease['fence']}
            assert first[0]['updateCount']==(0 if name=='attempt-fence' else 1)
            if second:
                assert second[0]['binds']=={'1':'RESUME_READY' if name=='public-finish' else 'WAITING_USER','2':before['steps'][-1]['id'] if name=='public-finish' else None,'3':lease['tenant'],'4':lease['task'],'5':lease['fence']}
                assert second[0]['updateCount']==(0 if name=='task-fence' else 1) and rows.index(first[0])<rows.index(second[0])
            if name!='public-finish':assert len(rows)==len(first)+len(second),'no extra SELECT or event in direct pause'
            else:
                assert any('FROM task ' in v['text'] and 'FOR UPDATE' in v['text'] for v in rows[:rows.index(first[0])])
                assert any(v['text'].startswith('SELECT q.*,i.provider_id FROM user_question') for v in rows[:rows.index(first[0])])
                assert any(v['text'].startswith('INSERT INTO event(') for v in rows[rows.index(second[0])+1:])
            updates.append({'role':tag,'attempt':first[0],'task':second[0] if second else None})
        statements.append({'name':name,'sqlOrderEqual':True,'count':len(left),'locks':{'old':locks(left),'new':locks(right)},'pauseUpdates':updates,'allBindsEqual':left==right})
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-pause-private'); task_id=task
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
        'boundary': 'Queued actual question task; public finish has explicit timed CONTINUED question/invocation/reservation plus original late answer setup; no openQuestion/provider/tool/worker loop. Trials live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionPauseScenario',
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
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'df245e310ffb24c34efc894e81fde5a00fd8703f06fd96bc60e2fb9a78bb5df1'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionPauseScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionPauseScenario.java', archive / 'QuestionPauseScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionPauseExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
