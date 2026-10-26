"""One bounded private comparison; reuse accepted question fixture, no worker/tool/provider."""
import argparse,hashlib,json,shutil,subprocess,traceback
from pathlib import Path
from p2_verify import run
from xiao60_pending_question_messages_verify import PendingQuestionMessagesExperiment

def compare(e,sides):
    owner_sql='SELECT * FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=? FOR UPDATE'
    delivery_sql="UPDATE user_question SET delivery_state='CHECKPOINTED',delivery_step_id=? WHERE tenant_id=? AND id=? AND delivery_state='PENDING'"
    counts={}
    for tag,calls in sides.items():
        by={c['name']:c for c in calls}
        assert len(calls)==8 and len(by)==8
        relation=0
        for c in calls:
            assert c['sessionPrior']==c['sessionAfter']
            assert len({q['connection'] for q in c['sql']})==1
            if c['name'].startswith('owner') or c['name']=='cross-owner':
                rows=[q for q in c['sql'] if q['text']==owner_sql]
                assert len(rows)==1
                assert list(rows[0]['binds'].values())==['tenant-a',e.owner_task,'carol' if c['name']=='cross-owner' else 'alice']
                relation+=3
                assert c['before']==c['after']
                if c['name']=='cross-owner':assert c['code']=='SESSION_EVENT_NOT_FOUND'
                else:
                    assert c['code']=='OK' and json.loads(c['result'])['readable']
                    # Original full task projection and actual result metadata/getters preserved.
                    assert len({g['column'] for g in rows[0]['getters']})>=60
            updates=[q for q in c['sql'] if q['text']==delivery_sql]
            if c['name'] in ('prepare-new','prepare-conflict'):
                assert len(updates)==2
                questions=sorted(c['before']['user_question'],key=lambda q:q['accepted_seq'])
                assert [list(q['binds'].values())[2] for q in updates]==[q['id'] for q in questions]
                for q in updates:
                    args=list(q['binds'].values());assert args[1]=='tenant-a';relation+=3
                clear=[q for q in c['sql'] if q['text'].startswith('UPDATE task SET question_continuation_step_id=NULL')]
                if c['name']=='prepare-new':
                    assert c['code']=='OK' and len(clear)==1
                    assert [q['updateCount'] for q in updates]==[1,1]
                    step=c['result']['id']
                    assert all(q['delivery_state']=='CHECKPOINTED' and q['delivery_step_id']==step for q in c['after']['user_question'])
                    assert all(list(q['binds'].values())[0]==step for q in updates)
                    assert c['after']['task']['question_continuation_step_id'] is None
                    assert len(c['after']['step'])==len(c['before']['step'])+1
                else:
                    assert c['code']=='QUESTION_DELIVERY_CONFLICT' and not clear
                    assert [q['updateCount'] for q in updates]==[1,0]
                    assert c['after']['task']['question_continuation_step_id']==c['before']['task']['question_continuation_step_id']
            elif c['name'] in ('prepare-exact-replay','checkpoint-read'):
                assert c['code']=='OK' and not updates
                assert not any(q['text'].startswith('INSERT INTO step') for q in c['sql'])
                assert c['before']['user_question']==c['after']['user_question']
                assert c['before']['step']==c['after']['step']
                assert c['result']['id']==by['prepare-new']['result']['id']
            elif c['name']=='wrong-fence':assert c['code']=='FENCE_REJECTED' and not updates
        counts[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'parameterRelations':relation,'statements':sum(q['statement']=='Statement' for c in calls for q in c['sql'])}
        lines=(e.evidence/(tag+'.log')).read_text().splitlines()
        restored=[json.loads(v[12:]) for v in lines if v.startswith('RESTORATION ')]
        assert len(restored)==2 and all(v['equal'] and v['before']==v['after'] and 'worker_process' in json.loads(v['before']) for v in restored)
        before=json.loads(next(v[13:] for v in lines if v.startswith('METER_BEFORE ')))
        after=json.loads(next(v[12:] for v in lines if v.startswith('METER_AFTER ')))
        assert all(after[k]>before[k] for k in before)
        counts[tag]['queryMeter']={'before':before,'after':after}
        counts[tag]['restoredTables']=len(json.loads(restored[0]['before']))
        api={key:{} for key in ['statement','api','setters','getters']}
        for c in calls:
            for q in c['sql']:
                for key in ['statement','api']:api[key][q[key]]=api[key].get(q[key],0)+1
                for method in q['setters'].values():api['setters'][method]=api['setters'].get(method,0)+1
                for g in q['getters']:api['getters'][g['method']]=api['getters'].get(g['method'],0)+1
        counts[tag]['jdbcApis']=api
    assert counts['old']['calls']==counts['new']['calls']
    def normalize(s):return 'SET SESSION max_execution_time=<remaining>' if s.startswith('SET SESSION max_execution_time=') and not s.endswith('=0') else s
    for old,new in zip(sides['old'],sides['new']):
        assert old['name']==new['name']
        assert [normalize(q['text']) for q in old['sql']]==[normalize(q['text']) for q in new['sql']],old['name']
    (e.evidence/'offline-comparison.json').write_text(json.dumps(counts,ensure_ascii=False,indent=2))

def verify(e,old,classes):
    e.prepare()
    root=Path(__file__).resolve().parents[1]
    digest=hashlib.sha256((root/'src/main/resources/session/session_event_read-0.1.0/output.schema.json').read_bytes()).hexdigest()
    e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','session_event_read','0.1.0','"+digest+"');")
    for table in ['task_session_history','session_public_event','invocation_session_read','user_question']:
        e.sql("GRANT INSERT,UPDATE ON jimu."+table+" TO 'runtime'@'%';")
    e.stop(e.control)
    # Reuse SessionExperiment's accepted local model qualifications, not a wider flag bypass.
    e.common.update({'jimu.session-event-read.enabled':'true',
        'jimu.model.profiles.a.model':'local-byte-accounted-v1',
        'jimu.model.profiles.a.revision':'local-byte-accounted-v1',
        'jimu.model.profiles.a.response-bytes':'16384',
        'jimu.model.profiles.a.input-limit.version':'1',
        'jimu.model.profiles.a.input-limit.max-tokens':'8192',
        'jimu.model.profiles.a.input-limit.proof':'test-whole-request-utf8-v1'})
    e.control=e.start('control-owner-delivery','control')
    status,question=e.http('POST','/api/tasks',{'goal':'owner-delivery-question','capabilityIds':['ask_user_question']},key='owner-delivery-question')
    assert status==202,(status,question)
    status,owner=e.http('POST','/api/tasks',{'goal':'owner-delivery-history','capabilityIds':['session_event_read']},key='owner-delivery-history')
    assert status==202,(status,owner)
    e.owner_task=owner['taskId']
    dump=run(['docker','exec',e.cid,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create=e.sql('SHOW CREATE DATABASE jimu;').split('\t',1)[1]
    grants=e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides={};requests=(dict(e.fixture.models),dict(e.fixture.tools))
    for tag,binary in [('old',old),('new',e.jar)]:
        database='owner_delivery_'+tag
        e.sql(create.replace('`jimu`','`'+database+'`')+';');e.sql('USE '+database+';\n'+dump)
        for grant in grants:
            if '`jimu`.' in grant:e.sql(grant.replace('`jimu`.','`'+database+'`.')+';')
        props=e.root/(tag+'.properties');props.write_text((e.control['folder']/'runtime.properties').read_text().replace('/jimu?','/'+database+'?'))
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.OwnerDeliveryScenario','-Dloader.path='+str(classes),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(props),question['taskId'],owner['taskId']]
        (e.evidence/(tag+'-command.json')).write_text(json.dumps(command,indent=2))
        with (e.evidence/(tag+'.log')).open('w') as log:child=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=180)
        lines=(e.evidence/(tag+'.log')).read_text().splitlines()
        (e.evidence/(tag+'-exit.json')).write_text(json.dumps({'exit':child.returncode,'jarSHA256':hashlib.sha256(binary.read_bytes()).hexdigest()}))
        sides[tag]=[json.loads(v[5:]) for v in lines if v.startswith('CALL ')]
        (e.evidence/(tag+'-calls.json')).write_text(json.dumps(sides[tag],ensure_ascii=False,indent=2))
        assert child.returncode==0,tag+' original driver failed'
    assert (e.fixture.models,e.fixture.tools)==requests==({},{}),'no provider or tool execution'
    compare(e,sides)
    (e.evidence/'provenance.json').write_text(json.dumps({'dumpSHA256':hashlib.sha256(dump.encode()).hexdigest(),'ownerTask':owner['taskId'],'questionTask':question['taskId'],'boundary':'Synthetic responses/answered questions and same-connection second-row interleaving, not Host/provider/external concurrency. Public new prepare and replay; outer rollback proves all private tables restored, not durable commits.'},indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ['jar','old-jar','evidence']:parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();repo=Path(__file__).resolve().parents[1]
    assert hashlib.sha256(args.old_jar.read_bytes()).hexdigest()=='e7710148f462d0728476fce2dc8dfb2e83cad4cb1b519f89827b2002aacc3846'
    archive=args.evidence/'executed';classes=archive/'classes';classes.mkdir(parents=True,exist_ok=False)
    for file in (repo/'target/test-classes').rglob('OwnerDeliveryScenario*.class'):
        dest=classes/file.relative_to(repo/'target/test-classes');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,dest)
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/OwnerDeliveryScenario.java',archive/'OwnerDeliveryScenario.java')
    shutil.copyfile(__file__,archive/Path(__file__).name)
    changed=subprocess.check_output(['git','diff','--name-only','d76507c39cd806a734dcbfadb53a6fe6d17bbadc'],cwd=repo,text=True).splitlines()+subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=repo,text=True).splitlines()
    for name in sorted(set(changed)):
        if not name.startswith('src/main/'):continue
        dest=archive/'production-source'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(repo/name,dest)
        if name.endswith('.java'):
            relative=Path(name.removeprefix('src/main/java/')).with_suffix('')
            for compiled in (repo/'target/classes'/relative.parent).glob(relative.name+'*.class'):
                dest=archive/'production-class'/compiled.relative_to(repo/'target/classes');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(compiled,dest)
    e=PendingQuestionMessagesExperiment(args.jar,args.evidence)
    try:verify(e,args.old_jar,classes)
    except BaseException as failure:
        if isinstance(failure,subprocess.CalledProcessError):
            (e.evidence/'child-stdout.txt').write_text(failure.stdout or '');(e.evidence/'child-stderr.txt').write_text(failure.stderr or '')
        (e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
