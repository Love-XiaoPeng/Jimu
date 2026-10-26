"""Directory ledger lifecycle; synthetic Evidence, private MySQL, no filesystem/tool execution."""
import argparse, hashlib, json, shutil, subprocess, traceback
from pathlib import Path
from p2_verify import Experiment, run

def compare(e, sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-directory-workflow-removed-sql.json').read_text())
    def norm(sql):
        for table,cols in inventory['columns'].items():
            sql=sql.replace('SELECT '+','.join(cols)+' FROM '+table,'SELECT * FROM '+table)
        for alias,table in [('i','invocation'),('p','task_directory')]:
            sql=sql.replace(','.join(alias+'.'+c for c in inventory['columns'][table]),alias+'.*')
        return ' '.join(sql.split())
    summary={};seen=set()
    for tag,calls in sides.items():
        by={c['name']:c for c in calls};assert len(by)==len(calls)==26
        assert by['snapshot-uninitialized']['result']['ready'] is False
        assert by['snapshot-ready']['result']['ready'] is True
        assert by['owner-initial']['result']['revision']==0
        assert by['owner-changed']['result']['revision']==1
        assert by['owner-changed']['result']['cwd']=='/子目录/😀'
        assert by['file-path']['result']==by['file-path-repeat']['result']=='子目录/😀/证据.txt'
        assert by['owner-absent']['result']['state']=='ABSENT' and by['owner-absent']['result']['cwd'] is None
        assert by['historical-read-replay']['result']['output'] is not None
        def binding(c,mode):return next(r for r in c['after']['invocation_path_binding'] if r['mode']==mode)
        read=binding(by['commit-read'],'READ');changed=binding(by['commit-set'],'SET')
        assert read['changed'] is False and read['base_revision']==read['applied_revision']==0 and read['changed_event_seq'] is None
        assert changed['changed'] is True and changed['base_revision']==0 and changed['applied_revision']==1
        for name in ['commit-read','commit-set']:
            c=by[name];assert c['after']['task']['tools_used']==c['before']['task']['tools_used']+1
            assert c['after']['budget']['tool_used']==c['before']['budget']['tool_used']+1
        for name in ['commit-read-replay','commit-set-replay','historical-read-replay','freeze-set-replay']:
            c=by[name]
            for key in ['budget','task_directory','invocation_path_binding','invocation','reservation','event']:assert c['before'][key]==c['after'][key],(tag,name,key)
        assert not any(r['type']=='working_directory.changed' for r in by['commit-read']['after']['event'])
        assert len([r for r in by['commit-set']['after']['event'] if r['type']=='working_directory.changed'])==1
        relations=0;api={'statement':{},'execute':{},'setters':{},'getters':{},'getterTypes':{}}
        for c in calls:
            t=c['before']['task'];tenant=t['tenant_id'];tid=t['id']
            assert len({q['connection'] for q in c['sql']})<=1,(tag,c['name'])
            if c['code']!='OK':
                for key in ['budget','invocation','invocation_path_binding','task_directory','event','reservation']:assert c['before'][key]==c['after'][key],(tag,c['name'],key)
            for q in c['sql']:
                for field in ['statement','execute']:api[field][q[field]]=api[field].get(q[field],0)+1
                for v in q['setters']:api['setters'][v]=api['setters'].get(v,0)+1
                for g in q['getters']:
                    for field,value in [('getters',g['method']),('getterTypes',g['type'])]:api[field][value]=api[field].get(value,0)+1
                candidates=[op for op in inventory['operations'] if norm(op['original'])==norm(q['sql'])]
                if not candidates:continue
                op=candidates[0]['name'];seen.update(d['name'] for d in candidates)
                a=list(q['binds'].values());r=None
                def inv(id):return next(r for r in c['after']['invocation'] if r['id']==id)
                def frozen(id):return next(r for r in c['after']['invocation_path_binding'] if r['invocation_id']==id)
                expected=None
                if op in ['readyWorkspace','originalDirectoryTask']:expected=[tenant,tid]
                elif op in ['directoryCommitInvocation','directoryFileInvocation']:expected=[tenant,tid,inv(a[2])['id']]
                elif op=='originalDirectoryInvocation':
                    r=next(r for r in c['after']['invocation'] if r['step_id']==a[2] and r['slot']==a[3]);expected=[tenant,tid,r['step_id'],r['slot']]
                elif op=='priorDirectorySlot':
                    r=next(r for r in c['after']['invocation'] if r['step_id']==a[2] and r['slot']==a[3]);assert r['slot'].startswith('0:');expected=[tenant,tid,r['step_id'],r['slot']]
                elif op=='unfinishedDirectoryStep':
                    assert any(r['ordinal']==a[2] for r in c['after']['step']);expected=[tenant,tid,a[2]]
                elif op in ['existingDirectoryBinding','frozenDirectoryBinding']:expected=[tenant,inv(a[1])['id']]
                elif op=='insertDirectoryBinding':
                    f=frozen(a[1]);p=c['after']['task_directory'][0];r=inv(a[1]);expected=[tenant,r['id'],tid,r['step_id'],p['binding_digest'],p['namespace_digest'],f['slot_index'],f['mode'],f['base_revision'],f['base_cwd'],f['target_cwd'],f['canonical_paths_snapshot'],hashlib.sha256(f['canonical_paths_snapshot'].encode()).hexdigest(),r['input_digest']]
                elif op in ['directorySourceAttempt','directoryViewAttempt']:
                    r=next(r for r in c['after']['task_attempt'] if r['id']==a[2]);expected=[tenant,tid,r['id'],r['fence']]
                elif op in ['directorySourceEvent','directoryViewEvent']:
                    r=next(r for r in c['after']['event'] if r['seq']==a[2] and r['fact_id']==a[3]);assert r['type']=='working_directory.changed';expected=[tenant,tid,r['seq'],r['fact_id']]
                elif op=='countDirectoryTool':expected=[tenant,tid]
                elif op=='countDirectoryBudget':expected=[tenant]
                elif op=='settleDirectoryReservation':
                    r=next(r for r in c['after']['reservation'] if r['id']==a[1]);assert r['state']=='SETTLED' and r['actual']==1;expected=[tenant,r['id']]
                elif op=='completeDirectoryInvocation':
                    r=inv(a[3]);expected=[r['output_snapshot'],hashlib.sha256(r['output_snapshot'].encode()).hexdigest(),tenant,r['id']]
                elif op=='completeDirectoryBinding':
                    f=frozen(a[7]);proof=json.loads(f['directory_evidence_snapshot']);assert proof['namespace']==hashlib.sha256((tenant+':'+tid).encode()).hexdigest();assert proof['target']==f['target_cwd'];assert proof['filesDigest']==hashlib.sha256(b'synthetic-files-evidence').hexdigest()
                    expected=[f['applied_revision'],f['changed'],t['current_attempt_id'],t['fence'],f['changed_event_seq'],f['directory_evidence_snapshot'],tenant,f['invocation_id']]
                elif op=='advanceDirectoryRevision':
                    f=frozen(a[2]);expected=[f['target_cwd'],f['applied_revision'],f['invocation_id'],tenant,tid,f['base_revision']]
                elif op=='directoryOwnerView':
                    assert a[0]==tenant and a[2]==t['owner_actor_id'];expected=[tenant,a[1],t['owner_actor_id']]
                    assert a[1]==tid or c['name']=='owner-absent'
                assert a==expected,(tag,c['name'],op,a,expected)
                relations+=1
        summary[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'parameterRelationships':relations,'api':api}
    assert seen=={op['name'] for op in inventory['operations']},seen
    for left,right in zip(sides['old'],sides['new']):
        assert left['name']==right['name'] and left['code']==right['code']
        assert [norm(q['sql']) for q in left['sql']]==[norm(q['sql']) for q in right['sql']],left['name']
        assert [list(q['binds']) for q in left['sql']]==[list(q['binds']) for q in right['sql']]
    (e.evidence/'summary.json').write_text(json.dumps({'counts':summary,'all21SitesReached':True,'calls':{tag:[{'name':c['name'],'code':c['code'],'sql':len(c['sql'])} for c in calls] for tag,calls in sides.items()}},ensure_ascii=False,indent=2))

def verify(e, old, classes):
    e.prepare()
    e.stop(e.control)
    for table in ['task_directory','invocation_path_binding']:
        e.sql("GRANT INSERT,UPDATE ON jimu."+table+" TO 'runtime'@'%';")
    resources=Path(__file__).resolve().parents[1]/'src/main/resources/plugins/builtin'
    for name in ['working_directory','read','write','edit']:
        digest=hashlib.sha256(((resources.parent.parent/'directory/working_directory-0.1.0' if name=='working_directory' else resources/name)/'output.schema.json').read_bytes()).hexdigest()
        e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+name+"','0.1.0','"+digest+"');")
    task='created-by-public-java-submit'
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'directory_workflow_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.DirectoryWorkflowScenario',
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
    for file in list((repo/'target/test-classes').rglob('DirectoryWorkflowScenario*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/DirectoryWorkflowScenario.java', archive/'DirectoryWorkflowScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', '8ef44048a9fe5201fd8ab6979277d0cc20a2a19a'], cwd=repo, text=True).splitlines()
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
