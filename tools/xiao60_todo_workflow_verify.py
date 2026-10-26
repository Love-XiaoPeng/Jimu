"""Todo replace/replay/owner workflow; private MySQL and outer rollback, no provider or tool."""
import argparse, hashlib, json, shutil, subprocess, traceback
from pathlib import Path
from p2_verify import Experiment, run

def compare(e, sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-todo-workflow-removed-sql.json').read_text())
    def norm(sql):
        for alias,table in [('i','invocation'),('p','task_todo_projection')]:
            sql=sql.replace(','.join(alias+'.'+c for c in inventory['columns'][table]),alias+'.*')
        return ' '.join(sql.split())
    summary={};seen=set()
    for tag,calls in sides.items():
        by={c['name']:c for c in calls};assert len(by)==len(calls)==14
        assert by['owner-initial']['result']['revision']==0 and by['owner-initial']['result']['state']=='UNWRITTEN'
        assert by['owner-content']['result']['revision']==1 and len(by['owner-content']['result']['todos'])==2
        assert by['owner-empty']['result']['revision']==2 and by['owner-empty']['result']['todos']==[]
        assert by['owner-absent']['result']['state']=='ABSENT'
        for name in ['replace-first','replace-second']:
            c=by[name];assert c['after']['task']['tools_used']==c['before']['task']['tools_used']+1
            assert c['after']['budget']['tool_used']==c['before']['budget']['tool_used']+1
            assert c['after']['task_todo_projection'][0]['revision']==c['before']['task_todo_projection'][0]['revision']+1
            inv=next(r for r in c['after']['invocation'] if r['todo_applied_revision']==c['after']['task_todo_projection'][0]['revision'])
            assert inv['state']=='SUCCEEDED' and inv['todo_committed_attempt_id']==c['after']['task']['current_attempt_id']
            reservation=next(r for r in c['after']['reservation'] if r['id']==inv['id']);assert reservation['actual']==1 and reservation['state']=='SETTLED'
            assert len([r for r in c['after']['event'] if r['fact_id']=='todo-write:'+inv['id'] and r['type']=='todo.updated'])==1
        relations=0;api={'statement':{},'execute':{},'setters':{},'getters':{},'getterTypes':{}}
        for c in calls:
            t=c['before']['task'];tenant=t['tenant_id'];tid=t['id']
            assert c['sessionPrior']==c['sessionAfter']
            assert len({q['connection'] for q in c['sql']})<=1,(tag,c['name'])
            if c['code']!='OK' or c['name'].startswith('replay'):
                for key in ['budget','invocation','task_todo_projection','event','reservation']:assert c['before'][key]==c['after'][key],(tag,c['name'],key)
            session=[q for q in c['sql'] if 'innodb_lock_wait_timeout' in q['sql']]
            if session:
                assert len(session)==3 and session[0]['sql']=='SELECT @@SESSION.innodb_lock_wait_timeout'
                assert session[0]['getters'][0]['method']=='getInt' and session[0]['getters'][0]['arguments']==['1']
                assert session[1]['sql']=='SET SESSION innodb_lock_wait_timeout=2'
                assert session[2]['sql']=='SET SESSION innodb_lock_wait_timeout='+str(c['sessionPrior'])
                assert all(q['statement']=='Statement' for q in session)
            for q in c['sql']:
                for field in ['statement','execute']:api[field][q[field]]=api[field].get(q[field],0)+1
                for v in q['setters']:api['setters'][v]=api['setters'].get(v,0)+1
                for g in q['getters']:
                    for field,value in [('getters',g['method']),('getterTypes',g['type'])]:api[field][value]=api[field].get(value,0)+1
                candidates=[op for op in inventory['operations'] if norm(op['original'])==norm(q['sql'])]
                if not candidates:continue
                op=candidates[0]['name'];seen.add(op);a=list(q['binds'].values())
                def inv(id):return next(r for r in c['after']['invocation'] if r['id']==id)
                if op=='todoCandidates':
                    r=next(r for r in c['after']['invocation'] if r['step_id']==a[2] and r['slot']==a[3]);expected=[tenant,tid,r['step_id'],r['slot']]
                elif op=='countTodoTool':expected=[tenant,tid]
                elif op=='countTodoBudget':expected=[tenant]
                elif op=='settleTodoReservation':expected=[tenant,inv(a[1])['id']]
                elif op=='completeTodoInvocation':
                    r=inv(a[7]);expected=[r['output_snapshot'],hashlib.sha256(r['output_snapshot'].encode()).hexdigest(),r['todo_expected_revision']+1,t['current_attempt_id'],t['fence'],r['todo_event_seq'],tenant,r['id']]
                elif op=='advanceTodoRevision':
                    r=inv(a[0]);output=json.loads(r['output_snapshot']);expected=[r['id'],r['step_id'],t['current_attempt_id'],t['fence'],r['todo_event_seq'],output['counts']['pending'],output['counts']['inProgress'],output['counts']['completed'],hashlib.sha256(r['output_snapshot'].encode()).hexdigest(),tenant,tid,r['todo_expected_revision']]
                elif op=='todoOwnerView':
                    assert a[1]==tid or c['name']=='owner-absent';expected=[tenant,a[1],t['owner_actor_id']]
                assert a==expected,(tag,c['name'],op,a,expected)
                relations+=1
        summary[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'parameterRelationships':relations,'api':api}
    assert seen=={op['name'] for op in inventory['operations']},seen
    for left,right in zip(sides['old'],sides['new']):
        assert left['name']==right['name'] and left['code']==right['code']
        assert [norm(q['sql']) for q in left['sql']]==[norm(q['sql']) for q in right['sql']],left['name']
        assert [list(q['binds']) for q in left['sql']]==[list(q['binds']) for q in right['sql']]
        if left['name'].startswith('replace') or left['name'].startswith('replay'):assert left['result']==right['result']
    (e.evidence/'summary.json').write_text(json.dumps({'counts':summary,'all7BusinessSitesReached':True,'sessionStatementSaveSetRestoreVerified':True,'calls':{tag:[{'name':c['name'],'code':c['code'],'sql':len(c['sql'])} for c in calls] for tag,calls in sides.items()}},ensure_ascii=False,indent=2))

def verify(e, old, classes):
    e.prepare()
    e.stop(e.control)
    for table in ['task_todo_projection']:
        e.sql("GRANT INSERT,UPDATE ON jimu."+table+" TO 'runtime'@'%';")
    digest=hashlib.sha256((Path(__file__).resolve().parents[1]/'src/main/resources/todo/todo_write-0.1.0/output.schema.json').read_bytes()).hexdigest()
    e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','todo_write','0.1.0','"+digest+"');")
    task='created-by-public-java-submit'
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'todo_workflow_' + tag
        e.sql(create.replace('`jimu`', '`'+database+'`') + ';')
        e.sql('USE '+database+';\n'+dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`'+database+'`.') + ';')
        props = e.root/(tag+'.properties')
        props.write_text((e.control['folder']/'runtime.properties').read_text().replace('/jimu?', '/'+database+'?'))
        from p3_model_verify import config
        settings=config({'a':e.fixture},paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        settings.update({'jimu.model.profiles.a.model':'local-byte-accounted-v1','jimu.model.profiles.a.revision':'local-byte-accounted-v1','jimu.model.profiles.a.response-bytes':'65536','jimu.model.profiles.a.input-limit.version':'1','jimu.model.profiles.a.input-limit.max-tokens':'8192','jimu.model.profiles.a.input-limit.proof':'test-whole-request-utf8-v1'})
        with props.open('a') as out:
            for key,value in settings.items():out.write(key+'='+str(value)+'\n')
        assert '/'+database+'?' in props.read_text()
        native = e.root/(tag+'.jar')
        shutil.copyfile(binary, native)
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TodoWorkflowScenario',
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
    for file in list((repo/'target/test-classes').rglob('TodoWorkflowScenario*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/TodoWorkflowScenario.java', archive/'TodoWorkflowScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'fbf8ff65c619a68c33851fc18ada13d157098252'], cwd=repo, text=True).splitlines()
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
