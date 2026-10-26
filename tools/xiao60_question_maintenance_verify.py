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

class QuestionMaintenanceExperiment(Experiment):
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
    summary=[]
    for role,data in result.items():
        assert data['exit']==0 and len(data['results'])==len(data['restored'])==len(data['sql'])==expected
        lines=(folder/(role+'.log')).read_text().splitlines()
        before={v.split(' ',2)[1]:json.loads(v.split(' ',2)[2]) for v in lines if v.startswith('FIXTURE_PROOF ')}
        after={v.split(' ',2)[1]:json.loads(v.split(' ',2)[2]) for v in lines if v.startswith('PROOF ')}
        sql={v.split(' ',2)[1]:json.loads(v.split(' ',2)[2]) for v in lines if v.startswith('SQL ')}
        for name,proof in after.items():
            pre=before[name];rows=sql[name];texts=[v['text'] for v in rows];task=proof['task'];q=proof['user_question'][0]
            assert len({v['connection'] for v in rows})==1
            scan=rows[0];assert scan['text'].startswith('SELECT t.tenant_id,t.id FROM task t LEFT JOIN actor_access') and scan['text'].endswith('ORDER BY t.deadline,t.id LIMIT 64')
            assert list(scan['binds'].values())==[True]
            due=[v for v in rows if v['text']=="SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND phase='OPEN' AND foreground_due_at<=CURRENT_TIMESTAMP(6) FOR UPDATE"]
            oldq=pre['user_question'][0];assert oldq['phase']=='OPEN' and pre['task']['state']=='WAITING_USER'
            assert pre['task_attempt'][0]['state']=='PAUSED'
            if name=='due':
                assert len(due)==1 and list(due[0]['binds'].values())==['tenant-a',task['id']]
                assert q['phase']=='CONTINUED' and q['revision']==oldq['revision']+1 and task['state']=='RESUME_READY'
                assert [e['type'] for e in proof['event']][-3:]==['tool.completed','task.resumable','question.continued']
                assert next(v for v in proof['invocation'] if v['id']==q['invocation_id'])['state']=='SUCCEEDED'
            else:
                assert not due and task['state']=='CANCELLED' and task['code']=='CANCELLED'
                assert q['phase']=='CLOSED' and q['close_code']=='CANCELLED'
                assert not any(e['type']=='question.continued' for e in proof['event'])
            raw=next(v.split(' ',2)[2] for v in lines if v.startswith('PROOF '+name+' '))
            summary.append({'role':role,'name':name,'sqlCount':len(rows),'proofSHA256':hashlib.sha256(raw.encode()).hexdigest(),'proofBytes':len(raw.encode()),'sql':rows})
        facts=[json.loads(v[6:]) for v in lines if v.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
    for name in [v['name'] for v in summary if v['role']=='old']:
        pair=[v for v in summary if v['name']==name];assert len(pair)==2 and [r['text'] for r in pair[0]['sql']]==[r['text'] for r in pair[1]['sql']]
    (folder/'maintenance-comparison.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    global task_id
    task = e.task('question-maintenance-private'); task_id=task
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
        'boundary': 'Direct package maintainQuestions only, scan logically outside per-candidate tx but both join the explicit outer fixture rollback. Explicit lawful timed question/response/intents/TOOL reservation and PAUSED attempt are setup facts, not Host/provider generation. No worker loop, no lease required. Only captured tenant-a 21 tables restored; worker_process excluded.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionMaintenanceScenario',
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
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'fe9125c3577e6476f3c6ff2fa4c81fd318f1ea08a987b3fccb506155b8150457'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionMaintenanceScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionMaintenanceScenario.java', archive / 'QuestionMaintenanceScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionMaintenanceExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
