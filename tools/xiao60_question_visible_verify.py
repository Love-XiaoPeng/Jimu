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

class QuestionVisibilityExperiment(Experiment):
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
    assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'original driver failure must not be replayed'
    values=[];statements=[]
    owner=lambda text:text.replace(' ','').replace('(','').replace(')','')=='SELECTidFROMtaskWHEREtenant_id=?ANDid=?ANDowner_actor_id=?'
    for a,b in zip(result['old']['results'],result['new']['results']):
        name=a['name'];assert name==b['name'] and a['code']==b['code'] and a['injected']==b['injected']==0
        if a['raw']:
            l,r=json.loads(a['raw']),json.loads(b['raw'])
            assert l['task']==r['task'],(name,'admitted task original bytes/types represented as JSON')
            assert l==r,(name,'full locked row keys/nulls/types or empty public view')
            if name=='direct-row':
                row=l['result'];assert l['lockedKeys']==list(row) and len(row)==len(l['task'])+2
                assert row['lease_until'] is row['live'] is None and row['within_deadline']==1
                assert row['id']==task_id and row['tenant_id']=='tenant-a' and row['owner_actor_id']=='alice'
                assert l['lockedTypes']['live']=='NULL' and 'within_deadline' in l['lockedTypes']
            if name=='owner-list': assert l['result']=={'version':1,'items':[]}
        else:assert a['raw']==b['raw']
        values.append({'name':name,'code':a['code'],**{tag:{'raw':v['raw'],'sha256':hashlib.sha256((v['raw'] or '').encode()).hexdigest(),'bytes':len((v['raw'] or '').encode())} for tag,v in [('old',a),('new',b)]},'boundary':'Same frozen admitted task on each side; direct complete row key order/types/nulls compared separately. Only stated JSON/raw bytes equal.'})
    for a,b in zip(result['old']['sql'],result['new']['sql']):
        name,left=a.split(' ',1);other,right=b.split(' ',1);assert name==other
        left,right=json.loads(left),json.loads(right)
        normalize=lambda text:'OWNER_ID_THREE_KEYS' if owner(text) else text
        assert [normalize(v['text']) for v in left]==[normalize(v['text']) for v in right],(name,'SQL order')
        old,new=[v for v in left if owner(v['text'])],[v for v in right if owner(v['text'])]
        count=0 if name=='disabled-identity' else 1
        assert len(old)==len(new)==count
        if count:
            actor='mallory' if name=='wrong-owner' else 'alice'
            for side in [old[0],new[0]]:
                assert side['binds']=={'1':'tenant-a','2':task_id,'3':actor}
                assert side['setters']=={'1':'setString','2':'setString','3':'setString'}
                assert side['getters']==([] if name=='wrong-owner' else [{'method':'getObject' if side is old[0] else 'getString','column':1 if side is old[0] else 'id','value':task_id,'type':'java.lang.String'}])
        locks=lambda rows:[v for v in rows if 'FOR SHARE' in v['text'] and ('actor_access' in v['text'] or 'installation' in v['text'])]
        assert locks(left)==locks(right),(name,'full actor/installation binds')
        for rows in [left,right]:
            owner_indices=[i for i,v in enumerate(rows) if owner(v['text'])]
            actor_indices=[i for i,v in enumerate(rows) if 'FROM actor_access' in v['text']]
            task_locks=[i for i,v in enumerate(rows) if 'AS live' in v['text'] and 'FOR UPDATE' in v['text']]
            scope_reads=[i for i,v in enumerate(rows) if v['text'].startswith('SELECT capabilities FROM task WHERE')]
            if name=='disabled-identity': assert len(actor_indices)==1 and not owner_indices and not task_locks and not scope_reads
            elif name=='wrong-owner': assert actor_indices[0]<owner_indices[0] and not task_locks and not scope_reads
            else:
                assert actor_indices[0]<owner_indices[0]<scope_reads[0]<task_locks[0]<actor_indices[1]
                assert len(task_locks)==1
                if name=='authorize-missing-question':
                    assert rows[-1]['text']=='SELECT id FROM user_question WHERE tenant_id=? AND task_id=? AND id=?'
                    assert rows[-1]['binds']=={'1':'tenant-a','2':task_id,'3':'00000000-0000-0000-0000-000000000001'}
        statements.append({'name':name,'sqlOrderEqualModuloOwnerMpFormatting':True,'owner':{'old':old,'new':new},'locks':locks(left),'statements':len(left),'scopeAndTaskOrderChecked':True})
    for role in ['old','new']:
        lines=(folder/(role+'.log')).read_text().splitlines();facts=[json.loads(s[6:]) for s in lines if s.startswith('FACTS ')]
        assert len(facts)==expected and all(v['before']==v['after'] and len(json.loads(v['before']))==21 for v in facts)
    for name,data in [('value-comparison',values),('sql-comparison',statements)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))


def verify(e, old, classes, phase):
    e.prepare()
    e.sql("INSERT INTO actor_access(tenant_id,actor_id,enabled) VALUES('tenant-a','mallory',TRUE);")
    global task_id
    task = e.task('question-visible-private'); task_id=task
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
        'boundary': 'Queued actual question task, no problem row/model response/worker/provider/tool execution. Read/identity-disable trials live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionVisibilityScenario',
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
    compare(e.evidence, result, 5 if phase=='all' else len(phase.split(',')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'a53263c652a8e843398aed8c48428a94ca4d977c14443bbf3638386d51334af4'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionVisibilityScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionVisibilityScenario.java', archive / 'QuestionVisibilityScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionVisibilityExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
