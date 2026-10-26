"""Native ledger lifecycle only; synthetic process identities, private MySQL, no tools."""
import argparse, hashlib, json, shutil, subprocess, traceback
from pathlib import Path
from p2_verify import Experiment, run

def compare(e, sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-native-lifecycle-removed-sql.json').read_text())
    def normalize(sql):
        for table,columns in inventory['columns'].items():
            sql=sql.replace('SELECT '+','.join(columns)+' FROM '+table,'SELECT * FROM '+table)
        return sql.replace('SELECT '+','.join('n.'+c for c in inventory['columns']['native_search_run']),'SELECT n.*')
    summaries={};counts={}
    for tag,calls in sides.items():
        by={c['name']:c for c in calls}; assert len(by)==len(calls)>=35
        assert by['quiescent-empty']['result'] is True
        assert by['quiescent-prepared']['result'] is False
        assert by['quiescent-stopped']['result'] is True
        for name in ['scan-empty','expired-empty','scan-foreign-host','expired-active-task','expired-active-attempt','expired-foreign-host','expired-removed']:
            assert by[name]['result']==[],name
        assert by['scan-prepared']['result'][0]['identity'] is None
        assert by['scan-prepared']['result'][0]['phase']=='PREPARED'
        identity=by['scan-started']['result'][0]['identity']
        assert identity=={'launcher':{'pid':999999,'startTicks':7,'bootId':'synthetic-boot','pidNamespace':'synthetic-pidns','observerPidNamespace':'synthetic-observer'},'bubblewrap':None,'init':None}
        assert by['scan-started']['result'][0]['stopRequested'] is False
        first=by['prepare']['after']['native_search_run'][0]['root_invocation']
        second=next(r['root_invocation'] for r in by['prepare-second']['after']['native_search_run'] if r['root_invocation']!=first)
        def row(call,root,phase='after'):
            return next(r for r in call[phase]['native_search_run'] if r['root_invocation']==root)
        assert row(by['stop-old-attempt-permission'],first)['phase']=='STOPPED'
        assert len(by['stop-repeat']['after']['native_search_stop'])==1
        assert row(by['stop-unknown'],first)['phase']=='STARTED'
        if 'scan-old-writer' in by:
            scan=by['scan-old-writer'];oldwriter=scan['result'][0]
            assert oldwriter['invocation']==second and oldwriter['attempt']!=scan['before']['task']['current_attempt_id']
            assert scan['before']['task']['fence']>next(a['fence'] for a in scan['before']['task_attempt'] if a['id']==oldwriter['attempt'])
            assert row(by['stop-before-start'],second,'before')['attempt_id']!=by['stop-before-start']['before']['task']['current_attempt_id']
            assert by['complete-stale-attempt']['code']=='FENCE_REJECTED'
        complete=by['complete']; result=row(complete,first)
        assert result['result_size']==71 and result['result_hash']==hashlib.sha256(b'synthetic-result-bytes').hexdigest()
        assert json.loads(result['cursor_index'])=={'private-cursor':1}
        r=next(r for r in complete['after']['reservation'] if r['id']=='search:'+first)
        assert r['state']=='SETTLED' and r['actual']==71
        assert complete['before']['budget']['storage_held']-complete['after']['budget']['storage_held']==4194304-71
        assert by['page']['result']=={'invocation':first,'directory':'synthetic-directory/'+first,'hash':result['result_hash'],'size':71,'page':1}
        assert by['expired']['result'][0]['invocation']==first and by['expired']['result'][0]['stopRequested'] is True
        for name,root,held in [('prepare',first,4194304),('prepare-second',second,4194304),('remove-reserved',second,-4194304),('remove-settled',first,-71)]:
            c=by[name];assert c['after']['budget']['storage_held']-c['before']['budget']['storage_held']==held,name
        for name in ['remove-reserved-repeat','remove-settled-repeat']:
            c=by[name];assert c['before']['budget']==c['after']['budget'] and c['before']['reservation']==c['after']['reservation']
        assert row(by['remove-reserved'],second)['result_size']==0
        assert by['remove-settled-repeat']['after']['budget']['storage_held']==by['prepare']['before']['budget']['storage_held']
        relations=0;api={'statements':{},'executes':{},'setters':{},'getters':{},'getterTypes':{}}
        for c in calls:
            task=c['before']['task'];tenant=task['tenant_id'];tid=task['id']
            assert len({q['connection'] for q in c['sql']})<=1,c['name']
            for q in c['sql']:
                for k,value in [('statements',q['statement']),('executes',q['execute'])]:api[k][value]=api[k].get(value,0)+1
                for value in q['setters']:api['setters'][value]=api['setters'].get(value,0)+1
                for g in q['getters']:
                    for k,v in [('getters',g['method']),('getterTypes',g['type'])]:api[k][v]=api[k].get(v,0)+1
                sql=normalize(q['sql']);a=list(q['binds'].values())
                operations=[d for d in inventory['operations'] if d['original']==sql]
                if not operations:continue
                op=operations[0]['name'];expected=None
                current=sorted(c['before']['invocation'],key=lambda x:x['ordinal'])[-1]
                if op=='selectNonStoppedSearch':expected=[tenant,tid,first]
                elif op=='selectSearchInvocation':expected=[tenant,current['id'],tid]
                elif op in ['selectSearchAggregate','selectSearchPage']:expected=[tenant,tid]
                elif op=='selectSearchWorkerHost':expected=['native-private']
                elif op=='insertSearchRun':expected=[tenant,current['id'],tid,current['effect_intent_id'],task['current_attempt_id'],'native-private','native-private','synthetic-directory/'+current['id'],hashlib.sha256(b'snapshot').hexdigest(),hashlib.sha256(b'query').hexdigest(),'glob-fixture-pin']
                elif op=='holdSearchStorage':expected=[tenant]
                elif op=='startSearchRun':
                    assert json.loads(a[0])==identity
                    expected=[a[0],tenant,tid,first,task['current_attempt_id']]
                elif op=='selectSearchStopRun':
                    root=second if c['name']=='stop-before-start' else first
                    r=row(c,root,'before');expected=[tenant,root,tid,r['effect_id'],'native-private']
                elif op=='insertSearchStop':
                    assert hashlib.sha256(a[3].encode()).hexdigest()==a[2]
                    r=row(c,a[1]); assert r['process_identity']==a[3]
                    expected=[tenant,r['root_invocation'],a[2],a[3]]
                elif op=='stopSearchRun':
                    r=row(c,a[2]);assert r['process_identity']==a[0]
                    expected=[a[0],tenant,r['root_invocation']]
                elif op=='selectSearchCompleteRun':expected=[tenant,tid,first,task['current_attempt_id']]
                elif op=='completeSearchRun':expected=[hashlib.sha256(b'synthetic-result-bytes').hexdigest(),71,row(c,first)['cursor_index'],tenant,first]
                elif op=='settleSearchStorage':expected=[71,tenant,'search:'+first]
                elif op in ['releaseCompletedSearchStorage','releaseRemovedSearchStorage']:
                    amount=4194304-71 if c['name']=='complete' else 4194304 if c['name']=='remove-reserved' else 71
                    expected=[amount,tenant]
                elif op in ['selectNativeSearches','selectExpiredSearches']:expected=['foreign-host' if 'foreign-host' in c['name'] else 'native-private']
                elif op=='selectSearchRemovalRun':
                    root=second if 'reserved' in c['name'] or c['name']=='remove-running' else first
                    r=row(c,root,'before');expected=[tenant,root,tid,r['effect_id']]
                elif op in ['selectSearchRemovalReservation','releaseSearchReservation']:
                    root=second if 'reserved' in c['name'] else first;expected=[tenant,'search:'+root]
                elif op=='removeSearchStorage':expected=[tenant,second if 'reserved' in c['name'] else first]
                assert expected is not None and a==expected,(tag,c['name'],op,a,expected)
                relations+=1
            if c['code']!='OK':
                for key in ['native_search_run','native_search_stop','reservation','budget','invocation']:
                    assert c['before'][key]==c['after'][key],(c['name'],key)
        summaries[tag]=[{'name':c['name'],'code':c['code'],'sqlCount':len(c['sql'])} for c in calls]
        counts[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'parameterRelationships':relations,'api':api}
    assert summaries['old']==summaries['new']
    for old,new in zip(sides['old'],sides['new']):
        for left,right in zip(old['sql'],new['sql']):
            assert normalize(left['sql'])==normalize(right['sql']),(old['name'],left['sql'],right['sql'])
            assert list(left['binds'])==list(right['binds'])
    (e.evidence/'summary.json').write_text(json.dumps({'calls':summaries,'counts':counts,'syntheticIdentityOnly':True,'outerRollbackOnly':True,'providerRequests':0,'nativeToolExecutions':0},indent=2))

def verify(e, old, classes):
    e.prepare()
    e.stop(e.control)
    e.sql("GRANT INSERT ON jimu.native_search_stop TO 'runtime'@'%';")
    resources=Path(__file__).resolve().parents[1]/'src/main/resources/plugins/builtin'
    for name in ['glob','grep']:
        digest=hashlib.sha256((resources/name/'output.schema.json').read_bytes()).hexdigest()
        e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+name+"','0.1.0','"+digest+"');")
    task='created-by-public-java-submit'
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'native_lifecycle_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.NativeLifecycleScenario',
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
    for file in list((repo/'target/test-classes').rglob('NativeLifecycleScenario*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/NativeLifecycleScenario.java', archive/'NativeLifecycleScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', '560c45d5a2dd7af0a8dce94bd47383dba0afb742'], cwd=repo, text=True).splitlines()
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
