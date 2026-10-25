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

class QuestionAccessExperiment(Experiment):
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

def compare(folder,result,expected,full_reads_required=True):
    assert all(v['exit']==0 and len(v['results'])==len(v['restored'])==len(v['sql'])==expected for v in result.values()),'driver error retained; no replay'
    proofs={};values=[];sqlProof=[]
    for tag in ['old','new']:
        lines=(folder/(tag+'.log')).read_text().splitlines()
        proofs[tag]=[json.loads(s[12:]) for s in lines if s.startswith('CHECK_PROOF ')]
        facts=[json.loads(s[6:]) for s in lines if s.startswith('FACTS ')]
        assert len(proofs[tag])==len(facts)==expected
        assert all(v['before']==v['after'] and len(json.loads(v['before']))==20 for v in facts)
        for p in proofs[tag]:
            task=p['task'];name=p['name'];binding=task['question_binding']
            valid=hashlib.sha256(binding.encode()).hexdigest()==task['question_binding_digest']
            assert valid==(name!='owner-digest'),(tag,name,'actual binding digest')
            assert len(p['installation'])==(0 if name in ['direct-missing','public-missing'] else 1)
            if p['installation']:
                installed=p['installation'][0]
                assert set(installed)=={'tenant_id','plugin_id','enabled','epoch','plugin_version','plugin_digest'}
                assert installed['enabled']==(name not in ['direct-disabled','public-disabled'])
                assert installed['plugin_version']=='0.2.0'
            if name=='owner-purged':assert task['question_cleanup_state']=='PURGED'
            if name=='owner-raw-binding':assert binding.endswith(' ')
    original="SELECT * FROM installation WHERE tenant_id=? AND plugin_id='ask_user_question'"
    mp='SELECT * FROM installation WHERE (tenant_id = ? AND plugin_id = ?)'
    for index,(a,b) in enumerate(zip(result['old']['results'],result['new']['results'])):
        name=a['name'];assert name==b['name'] and a['code']==b['code']
        if a['raw'] is not None:
            if name.startswith('direct-'):assert a['raw']==b['raw']=='qualified'
            else:
                left,right=json.loads(a['raw']),json.loads(b['raw']);assert left==right
                if name=='owner-list':assert left=={'version':1,'items':[]}
                if name=='existing-replay':assert left=={'taskId':proofs['old'][index]['task']['id'],'state':'QUEUED','revision':2}
        values.append({'name':name,'code':a['code'],**{tag:{'raw':v['raw'],'sha256':None if v['raw'] is None else hashlib.sha256(v['raw'].encode()).hexdigest(),'bytes':None if v['raw'] is None else len(v['raw'].encode())} for tag,v in [('old',a),('new',b)]},'boundary':'Driver JSON only; original Map.of order retained, parsed JSON fields compared; no HTTP wire equivalence claim.'})
        rows={};canonical={};source={};locks={}
        for tag in ['old','new']:
            label,text=result[tag]['sql'][index].split(' ',1);assert label==name;rows[tag]=json.loads(text)
            source[tag]=[v for v in rows[tag] if v['text'] in [original,mp]]
            assert len(source[tag])==(0 if name in ['public-disabled','public-missing'] else 2 if name=='existing-replay' else 1),(tag,name,'source count')
            for s in source[tag]:
                assert s['text']==(original if tag=='old' else mp)
                assert s['binds']==({'1':'tenant-a'} if tag=='old' else {'1':'tenant-a','2':'ask_user_question'})
                assert s['bindJavaTypes']=={k:'java.lang.String' for k in s['binds']}
                assert 'FOR ' not in s['text'] and 'enabled' not in s['text']
                assert s['sourceColumns']==['tenant_id','plugin_id','enabled','epoch','plugin_version','plugin_digest']
                if full_reads_required or tag=='old':
                    assert s['sourceReadRows']==proofs[tag][index]['installation'],(tag,name,'full original six-column JDBC values')
                    if s['sourceReadTypes']:assert s['sourceReadTypes']==[{'tenant_id':'java.lang.String','plugin_id':'java.lang.String','enabled':'java.lang.Boolean','epoch':'java.lang.Long','plugin_version':'java.lang.String','plugin_digest':'java.lang.String'}]
                else:
                    assert s['sourceReadRows']==[{} for _ in proofs[tag][index]['installation']],'run1 new typed getters were not captured; no fabricated values'

            canonical[tag]=[{'text':original if v['text']==mp else v['text'],'binds':{'1':v['binds']['1']} if v['text']==mp else v['binds']} for v in rows[tag]]
            locks[tag]=[v for v in rows[tag] if 'FOR SHARE' in v['text'] or 'FOR UPDATE' in v['text']]
            if name.startswith('owner-') or name=='existing-replay':
                assert all(any('installation' in v['text'] and 'FOR SHARE' in v['text'] for v in rows[tag][:rows[tag].index(s)]) for s in source[tag])
        assert canonical['old']==canonical['new'],(name,'original other SQL/order/full binds')
        assert proofs['old'][index]==proofs['new'][index],(name,'same actual frozen task/installation rows')
        sqlProof.append({'name':name,'statements':len(rows['old']),'source':source,'locks':locks,'otherSQLOrderAndFullBindsEqual':True,'completeSourceReadsRequired':full_reads_required})
    for name,data in [('value-comparison',values),('sql-comparison',sqlProof)]:
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))

def verify(e, old, classes, phase):
    e.prepare()
    task = e.task('question-access-private')
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
        'boundary': 'Queued actual question task, no worker/provider. Bounded task/installation UPDATE/DELETE trials live inside outer rollback; task window renewal is not a query/socket/deadline change.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.QuestionAccessScenario',
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
    compare(e.evidence, result, 2 if phase=='reads' else 10)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    for flag in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--phase', default='all')
    args = parser.parse_args()
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest() == 'a0272aa514876e0ff6ff90dc5e26dd7f615deb4a3685151a22d248fc383289ad'
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence / 'executed'
    classes = archive / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo / 'target/test-classes').rglob('QuestionAccessScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/QuestionAccessScenario.java', archive / 'QuestionAccessScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([{'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', args.old_jar), ('new', args.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([{'path': f.relative_to(archive).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    experiment = QuestionAccessExperiment(args.jar, args.evidence / 'question')
    try:
        verify(experiment, args.old_jar, classes, args.phase)
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
