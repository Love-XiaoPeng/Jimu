"""Bounded original/new session admission; reuse legal existing session fixture, no worker/provider."""
from types import SimpleNamespace
from p3_model_verify import config
import argparse,hashlib,json,shutil,subprocess,traceback
from pathlib import Path
from p2_verify import run
from prior_session_verify import PriorExperiment as Experiment

def compare(e,sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-execution-scope-removed-sql.json').read_text())
    norm=lambda sql:' '.join(sql.split())
    counts={};seen=set();metadata={}
    for tag,calls in sides.items():
        relations=0;api={'statement':{},'execute':{},'setters':{},'getters':{},'getterTypes':{}};scalars=[];strings=[]
        for c in calls:
            assert len({q['connection'] for q in c['sql']})==1,(tag,c['name'])
            for q in c['sql']:
                for field in ['statement','execute']:api[field][q[field]]=api[field].get(q[field],0)+1
                for v in q['setters']:api['setters'][v]=api['setters'].get(v,0)+1
                for g in q['getters']:
                    for field,value in [('getters',g['method']),('getterTypes',g['type'])]:api[field][value]=api[field].get(value,0)+1
                if q['sql'] in ['SELECT @@server_uuid','SELECT DATABASE()'] or 'information_schema.TABLES' in q['sql']:
                    scalars.append({'sql':q['sql'],'statement':q['statement'],'execute':q['execute'],'getters':q['getters']})
                matches=[o for o in inventory['operations'] if norm(o['original'])==norm(q['sql'])]
                if not matches:continue
                op=matches[0]['name'];seen.add(op);a=list(q['binds'].values())
                assert a[0]=='tenant-a'
                if len(a)==2:assert a[1]=='scope-local-run'
                if op=='stopRun':assert c['name']=='claimed-mismatch' and c['code']=='EVALUATION_SCOPE_MISMATCH'
                relations+=1
            for row in c['after']['evaluation_execution_scope']:
                raw=row['scope_snapshot'];assert hashlib.sha256(raw.encode()).hexdigest()==row['scope_digest']
                strings.append({'call':c['name'],'kind':'scope','sha256':row['scope_digest'],'utf8Bytes':len(raw.encode())})
                scope=json.loads(raw);assert scope['schema']=='execution_scope_'+tag and scope['tenantId']=='tenant-a' and scope['runId']=='scope-local-run'
                if c['name']=='scope-consume':assert c['result']==row['scope_digest']
            if c['name']=='scoped-submit':
                task=next(t for t in c['after']['task'] if t['id']==c['result']);binding=c['after']['evaluation_execution_scope'][0]
                assert task['evaluation_run_id']=='scope-local-run' and task['execution_scope_digest']==binding['scope_digest']
                for rawField,digestField in [('model_snapshot','model_digest'),('evaluation_snapshot','evaluation_digest'),('material_snapshot','material_digest')]:
                    raw=task[rawField];assert hashlib.sha256(raw.encode()).hexdigest()==task[digestField]
                    strings.append({'call':c['name'],'kind':rawField,'sha256':task[digestField],'utf8Bytes':len(raw.encode())})
            if c['name']=='claimed-mismatch':
                run=next(r for r in c['after']['evaluation_budget'] if r['id']=='scope-local-run');assert run['state']=='STOPPED'
                events=[r for r in c['after']['event'] if r['type']=='evaluation.scope_rejected'];assert len(events)==1
                event=events[0];task=next(t for t in c['after']['task'] if t['id']==event['task_id'])
                assert event['fact_id']=='scope-rejected:'+str(task['fence']) and event['code']=='EVALUATION_SCOPE_MISMATCH'
                assert c['before']['tenant_budget']==c['after']['tenant_budget']
            if c['name'] in ['fence-rejected','permission-rejected']:assert c['before']==c['after']
        assert len(set(q['sql'] for q in scalars))==3
        for sql in set(q['sql'] for q in scalars):
            getters=[g for q in scalars if q['sql']==sql for g in q['getters']]
            expected=('getInt','java.lang.Integer') if 'information_schema.TABLES' in sql else ('getString','java.lang.String')
            assert any((g['method'],g['type'])==expected for g in getters),(sql,expected)
        metadata[tag]={'scalars':scalars,'strings':strings}
        counts[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'parameterRelationships':relations,'api':api}
    assert len(seen)==4
    for left,right in zip(sides['old'],sides['new']):
        assert left['name']==right['name'] and left['code']==right['code']
        if left['name']=='scope-query-metered':assert left['result']==right['result']
        assert [norm(q['sql']) for q in left['sql']]==[norm(q['sql']) for q in right['sql']],left['name']
        assert [list(q['binds']) for q in left['sql']]==[list(q['binds']) for q in right['sql']],left['name']
    assert counts['old']['calls']==counts['new']['calls']==14
    (e.evidence/'metadata-scalars.json').write_text(json.dumps(metadata,indent=2))
    (e.evidence/'summary.json').write_text(json.dumps({'counts':counts,'all4SitesReached':True,'metadataThreeOriginalApis':True,'noWorkerToolProviderExecution':True,'outerRollbackOnly':True},indent=2))

def verify(e, old, classes):
    repo=Path(__file__).resolve().parents[1]
    e.prepare()
    e.stop(e.control)
    resources=Path(__file__).resolve().parents[1]/'src/main/resources/plugins/builtin'
    for tool in ['read','write','present']:
        digest=hashlib.sha256((resources/tool/'output.schema.json').read_bytes()).hexdigest()
        e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+tool+"','0.1.0','"+digest+"');")
    e.sql("UPDATE tenant_budget SET model_limit=10000,token_limit=1000000000,cost_limit=1000000000000;")
    e.sql("CREATE TABLE jimu_provisioning_receipt(record_version INT NOT NULL,issue_id VARCHAR(64),instance_uuid VARCHAR(64),tenant_id VARCHAR(64),run_id VARCHAR(64),approval_snapshot LONGTEXT,approval_digest CHAR(64),plan_digest CHAR(64),materials_digest CHAR(64)); GRANT SELECT,INSERT,UPDATE,DELETE ON jimu.jimu_provisioning_receipt TO 'runtime'@'%'; GRANT INSERT ON jimu.evaluation_execution_scope TO 'runtime'@'%';")
    task="public-session-admission"
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'execution_scope_' + tag
        e.sql(create.replace('`jimu`', '`'+database+'`') + ';')
        e.sql('USE '+database+';\n'+dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`'+database+'`.') + ';')
        props = e.root/(tag+'.properties')
        props.write_text((e.control['folder']/'runtime.properties').read_text().replace('/jimu?', '/'+database+'?'))
        assert '/'+database+'?' in props.read_text()
        plan=json.loads((Path(__file__).resolve().parents[1]/'src/main/resources/evaluation/qwen-main-v1.json').read_text())
        cfg=config({'a':SimpleNamespace(origin='http://127.0.0.1:1')},paid=True,run_id='scope-local-run',fixed=0)
        cfg.update({'jimu.model.evaluation-plan-ref':'qwen-main-v1','jimu.model.evaluation-phase':'A','jimu.model.evaluation-sample':'W1','jimu.test-mode':'true'})
        prefix='jimu.model.profiles.a.';plus='qwen3.7-plus-2026-05-26'
        values={'model':plus,'revision':plus,'max-output-tokens':4096,'capabilities.context-limit':1000000,'capabilities.evidence':'modelstudio-nonthinking-v1','capabilities.stream-text':'true','capabilities.stream-tools':'true','capabilities.usage-stream':'true','provider-options.enable_thinking':'false','provider-options.enable_search':'false','price.input-token-upper':1000000,'price.input-micros-per-million':plan['candidates'][plus]['inputMicrosPerMillion'],'price.output-micros-per-million':plan['candidates'][plus]['outputMicrosPerMillion']}
        cfg.update({prefix+k:str(v) for k,v in values.items()})
        for k,v in list(cfg.items()):
            if k.startswith(prefix):cfg[k.replace(prefix,'jimu.model.profiles.sse.',1)]=v
        cfg['jimu.model.profiles.sse.profile']='sse';cfg['jimu.model.profiles.sse.stream']='true'
        canonical=lambda v:json.dumps(v,ensure_ascii=False,separators=(',',':'))
        approval=canonical({'activeCandidateOnly':plus,'fixtureOnly':True})
        req={'version':1,'issueId':'XIAO-60-local-synthetic','instanceUuid':e.sql('SELECT @@server_uuid;'),'schema':database,'tenantId':'tenant-a','runId':'scope-local-run','approvalDigest':hashlib.sha256(approval.encode()).hexdigest()}
        cfg['jimu.execution-scope.requirement']=canonical(req)
        scoped=e.root/(tag+'-scoped.properties');scoped.write_text(''.join(line+'\n' for line in props.read_text().splitlines() if not line.startswith('jimu.model.'))+'\n'+'\n'.join(k+'='+str(v) for k,v in cfg.items())+'\n')
        binding=e.root/(tag+'-binding.json')
        generated=run([e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ExecutionScopeTestMain','-Dloader.path='+str(classes),'-cp',str(old),'org.springframework.boot.loader.launch.PropertiesLauncher','generate',str(scoped),str(binding)])
        (e.evidence/(tag+'-binding.json')).write_bytes(binding.read_bytes())
        native = e.root/(tag+'.jar')
        shutil.copyfile(binary, native)
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ExecutionScopeConsumptionScenario',
                   '-Dloader.path='+str(classes), '-cp', str(native), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(props), str(scoped), str(binding)]
        with (e.evidence/(tag+'.log')).open('w') as log:
            child = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=150)
        lines = (e.evidence/(tag+'.log')).read_text().splitlines()
        (e.evidence/(tag+'-exit.json')).write_text(json.dumps({'exit': child.returncode, 'jarSHA256': hashlib.sha256(native.read_bytes()).hexdigest()}))
        assert child.returncode == 0, tag+' see original log'
        assert any(v.startswith('RESTORED ') for v in lines)
        before=json.loads(next(v[11:] for v in lines if v.startswith('FULLBEFORE ')))
        after=json.loads(next(v[10:] for v in lines if v.startswith('FULLAFTER ')))
        assert before==after and 'worker_process' in before
        (e.evidence/(tag+'-restoration.json')).write_text(json.dumps({'before':before,'after':after,'equal':True,'tableCount':len(before)},ensure_ascii=False,indent=2))
        sides[tag] = [json.loads(v[5:]) for v in lines if v.startswith('CALL ')]
        (e.evidence/(tag+'-calls.json')).write_text(json.dumps(sides[tag], ensure_ascii=False, indent=2))
    assert (e.fixture.models, e.fixture.tools) == requests == ({}, {}), 'no provider or tool execution'
    compare(e, sides)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ['jar', 'old-jar', 'evidence']:
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence/'executed'
    classes = archive/'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in list((repo/'target/test-classes').rglob('ExecutionScopeConsumptionScenario*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    for file in (repo/'target/test-classes').rglob('ExecutionScopeTestMain*.class'):
        dest=classes/file.relative_to(repo/'target/test-classes');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,dest)
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/ExecutionScopeTestMain.java',archive/'ExecutionScopeTestMain.java')
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/ExecutionScopeConsumptionScenario.java', archive/'ExecutionScopeConsumptionScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'd16d4c0ec0888ee1c87c94008e7a65192ff0c623'], cwd=repo, text=True).splitlines()
    changed += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=repo, text=True).splitlines()
    for name in sorted(set(changed)):
        if not name.startswith('src/main/'):
            continue
        source = repo/name
        dest = archive/'production-source'/name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        if name.endswith('.java'):
            relative = Path(name.removeprefix('src/main/java/')).with_suffix('')
            for compiled in (repo/'target/classes'/relative.parent).glob(relative.name+'*.class'):
                dest = archive/'production-class'/compiled.relative_to(repo/'target/classes')
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(compiled, dest)
    e = Experiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar, classes)
    except BaseException as failure:
        if isinstance(failure,subprocess.CalledProcessError):
            (e.evidence/'child-stdout.txt').write_text(failure.stdout or '')
            (e.evidence/'child-stderr.txt').write_text(failure.stderr or '')
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
