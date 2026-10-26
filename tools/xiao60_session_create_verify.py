"""Bounded original/new session admission; reuse legal existing session fixture, no worker/provider."""
import argparse,hashlib,json,shutil,subprocess,traceback
from pathlib import Path
from p2_verify import run
from prior_session_verify import PriorExperiment as Experiment

def compare(e,sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-session-create-removed-sql.json').read_text())
    def norm(sql):
        value=' '.join(sql.split())
        return 'SET SESSION max_execution_time=<remainingMillis>' if value.startswith('SET SESSION max_execution_time=') and value!='SET SESSION max_execution_time=0' else value
    counts={};seen=set()
    for tag,calls in sides.items():
        assert len(calls)==6
        relations=0;api={'statement':{},'execute':{},'setters':{},'getters':{},'getterTypes':{}}
        for c in calls:
            assert c['sessionPrior']==c['sessionAfter']
            assert len({q['connection'] for q in c['sql']})==1
            if c['name'].endswith('replay'):assert c['before']==c['after']
            if c['name']=='source-owner-refusal':
                assert c['code']=='SESSION_SCOPE_UNAVAILABLE'
                for table in ['task_session_scope','task_session_scope_target','tenant_budget','reservation']:assert c['before'][table]==c['after'][table],table
            settings=[q for q in c['sql'] if q['sql'].startswith('SET SESSION max_execution_time=')]
            assert len(settings)==2 and 0<int(settings[0]['sql'].split('=')[1])<=60000 and settings[1]['sql']=='SET SESSION max_execution_time=0'
            if c['name'].endswith('create'):
                budget=lambda proof:next(b for b in proof['tenant_budget'] if b['tenant_id']=='tenant-a')
                before,after=budget(c['before']),budget(c['after'])
                assert after['history_storage_held']-before['history_storage_held']==8388608
                assert after['queued']-before['queued']==1
                h=next(h for h in c['after']['task_session_history'] if h['task_id']==c['result'])
                r=next(r for r in c['after']['reservation'] if r['task_id']==c['result'] and r['category']=='SESSION_HISTORY_STORAGE')
                assert r['state']=='RESERVED' and r['amount']==h['reserved_bytes']==h['held_bytes']==8388608
            for q in c['sql']:
                for field in ['statement','execute']:api[field][q[field]]=api[field].get(q[field],0)+1
                for v in q['setters']:api['setters'][v]=api['setters'].get(v,0)+1
                for g in q['getters']:
                    for field,value in [('getters',g['method']),('getterTypes',g['type'])]:api[field][value]=api[field].get(value,0)+1
                matches=[o for o in inventory['operations'] if norm(o['original'])==norm(q['sql'])]
                if not matches:continue
                op=matches[0]['name'];seen.add(op);a=list(q['binds'].values());after=c['after']
                if op=='insertAuthority':
                    r=next(r for r in after['task_session_scope'] if r['caller_task_id']==c['result'])
                    expected=[r[k] for k in ['tenant_id','caller_task_id','owner_actor_id','caller_binding_digest','caller_tools_digest','authority_snapshot','authority_digest','authority_bytes','candidate_count','created_at','retain_until','next_validation_at']]
                elif op=='insertAuthorityTarget':
                    r=next(r for r in after['task_session_scope_target'] if r['caller_task_id']==c['result'] and r['ordinal']==a[2]);expected=[r[k] for k in ['tenant_id','caller_task_id','ordinal','source_task_id','target_snapshot','target_digest','target_bytes']]
                else:
                    r=next(r for r in after['task_session_history'] if r['task_id']==c['result']);expected=[r['retain_until'],r['tenant_id'],r['task_id']]
                assert a==expected,(tag,c['name'],op,a,expected);relations+=1
        counts[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'parameterRelationships':relations,'api':api}
    assert len(seen)==3
    for left,right in zip(sides['old'],sides['new']):
        assert left['name']==right['name'] and left['code']==right['code']
        assert [norm(q['sql']) for q in left['sql']]==[norm(q['sql']) for q in right['sql']],left['name']
        assert [list(q['binds']) for q in left['sql']]==[list(q['binds']) for q in right['sql']],left['name']
    (e.evidence/'summary.json').write_text(json.dumps({'counts':counts,'all3SitesReached':True,'noWorkerToolProviderExecution':True,'outerRollbackOnly':True},indent=2))

def verify(e, old, classes):
    e.prepare()
    e.stop(e.control)
    task="public-session-admission"
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'session_create_' + tag
        e.sql(create.replace('`jimu`', '`'+database+'`') + ';')
        e.sql('USE '+database+';\n'+dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`'+database+'`.') + ';')
        props = e.root/(tag+'.properties')
        props.write_text((e.control['folder']/'runtime.properties').read_text().replace('/jimu?', '/'+database+'?'))
        assert '/'+database+'?' in props.read_text()
        native = e.root/(tag+'.jar')
        shutil.copyfile(binary, native)
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionScopeCreationScenario',
                   '-Dloader.path='+str(classes), '-cp', str(native), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(props), task]
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
    for file in list((repo/'target/test-classes').rglob('SessionScopeCreationScenario*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/SessionScopeCreationScenario.java', archive/'SessionScopeCreationScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', '677cf21ab88f0786508d68bf15e3e64165cc9393'], cwd=repo, text=True).splitlines()
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
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
