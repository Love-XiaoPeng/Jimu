"""Web observation/cancellation/preservation; exact old/new private MySQL and outer rollback."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import traceback

import base64
from p2_verify import run
from p4_image_verify import ImageExperiment, png


def compare(e, sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-image-lifecycle-removed-sql.json').read_text())
    summary={}
    positions=0
    for tag,calls in sides.items():
        byname={v['name']:v for v in calls}
        assert len(byname)==len(calls)==34, len(calls)
        snap=byname['snapshot']['result']
        assert snap['version']==1 and len(snap['entries'])==1
        raw=byname['input']['result']
        assert raw['entry']==snap['entries'][0]
        assert base64.b64decode(raw['bytes'])==png()
        reserved=byname['reserve']
        obj=reserved['after']['task_image_object'][0]
        assert obj['phase']=='RESERVED' and obj['input_id']==snap['entries'][0]['inputId']
        assert reserved['result']==obj['image_id']
        assert reserved['after']['budget']['storage_held']-reserved['before']['budget']['storage_held']==1048576
        media=next(r for r in reserved['after']['reservation'] if r['category']=='MEDIA_STORAGE')
        assert media['id']==obj['image_id'] and media['amount']==1048576 and media['state']=='RESERVED'
        success=byname['success']
        obj=success['after']['task_image_object'][0]
        inv=success['after']['invocation'][0]
        assert obj['phase']=='COMMITTED' and inv['state']=='SUCCEEDED'
        value=json.loads(inv['output_snapshot'])
        assert value['image']['imageId']==obj['image_id'] and value['inputId']==obj['input_id']
        assert value['sourceRef']=='image:'+inv['id']
        assert hashlib.sha256(inv['output_snapshot'].encode()).hexdigest()==inv['output_digest']
        assert obj['snapshot_key']==obj['image_id']+'-'+obj['sha256']
        assert obj['sha256']==hashlib.sha256(png()).hexdigest() and obj['size']==len(png())
        assert [obj[k] for k in ['width','height','original_width','original_height']]==[3,2,3,2]
        assert success['after']['budget']['storage_held']-success['before']['budget']['storage_held']==len(png())-1048576
        assert success['after']['budget']['tool_used']==success['before']['budget']['tool_used']
        for category in ['MEDIA_STORAGE','TOOL']:
            row=next(r for r in success['after']['reservation'] if r['category']==category)
            assert row['state']=='SETTLED'
            assert row['actual']==(len(png()) if category=='MEDIA_STORAGE' else 1)
        assert byname['object']['result']['result']==value
        assert byname['step']['result']==[byname['object']['result']]
        assert byname['step-empty']['result']==[]
        assert [v['result']['image']['imageId'] for v in byname['step-two']['result']]==[byname['reserve']['result'],byname['reserve-second']['result']]
        assert byname['timeout-permission']['after']['task']['state']=='CANCEL_REQUESTED'
        assert byname['timeout-permission']['after']['task']['code']=='IMAGE_DECODE_TIMEOUT'
        assert byname['timeout-permission']['after']['task']['cancel_requested_at'] is not None
        assert byname['timeout-replay']['before']['event']==byname['timeout-replay']['after']['event']
        assert byname['register']['result']['state']=='QUEUED'
        summary[tag]=[]
        for call in calls:
            assert len({v['connection'] for v in call['sql']})<=1,call['name']
            if call['code']!='OK':
                before=json.loads(json.dumps(call['before']));after=json.loads(json.dumps(call['after']))
                for x in [before,after]:
                    x['task'].pop('revision');x['task'].pop('updated_at')
                assert before==after, call['name']
            summary[tag].append({'name':call['name'],'code':call['code'],'sqlCount':len(call['sql'])})
    assert summary['old']==summary['new']
    for old,new in zip(sides['old'],sides['new']):
        for left,right in zip(old['sql'],new['sql']):
            normalized=right['sql']
            original=left['sql']
            for table,columns in inventory['columns'].items():
                normalized=normalized.replace('SELECT '+','.join(columns)+' FROM '+table,'SELECT * FROM '+table)
                normalized=normalized.replace('SELECT '+','.join(columns)+',expires_at','SELECT *,expires_at')
                original=original.replace('SELECT '+','.join(columns)+' FROM '+table,'SELECT * FROM '+table)
                original=original.replace('SELECT '+','.join(columns)+',expires_at','SELECT *,expires_at')
            assert original==normalized,(old['name'],original,normalized)
            assert list(left['binds'])==list(right['binds'])
            positions+=len(left['binds'])
        # Verify critical parameter values against each side's independently observed rows.
        for current in [old,new]:
            before=current['before'];after=current['after'];inv=before['invocation'][0]
            for q in current['sql']:
                sql=q['sql'];args=list(q['binds'].values())
                if 'UPDATE task_image_object SET snapshot_key=' in sql:
                    image=next(v for v in before['task_image_object'] if v['producer_invocation']==args[10])
                    assert args[0].startswith(image['image_id']+'-')
                    assert args[7:]==[image['tenant_id'],image['task_id'],image['image_id'],image['producer_invocation'],before['task_image_input'][0]['input_id']]
                if sql.startswith("UPDATE reservation SET state='SETTLED',actual=?") and 'MEDIA_STORAGE' in sql:
                    assert args[0:2]==[len(png()),inv['tenant_id']]
                    assert any(v['image_id']==args[2] for v in before['task_image_object'])
                if sql.startswith('SELECT ') and 'FROM task_image_input WHERE tenant_id=? AND task_id=? AND input_id=?' in sql:
                    assert args==[inv['tenant_id'],inv['task_id'],before['task_image_input'][0]['input_id']]
    (e.evidence/'summary.json').write_text(json.dumps({'calls':summary,'pairedParameterPositions':positions,'outerRollbackOnly':True,'providerRequests':0},indent=2))


def verify(e, old, classes):
    e.prepare()
    status, value = e.http('POST', '/api/tasks', {'goal': 'image-lifecycle-private', 'capabilityIds': ['read_image'], 'budget': {'deadlineSeconds': 120, 'maxModelRequests':5, 'maxToolInvocations':10}, 'images':[{'filePath':'inputs/images/sample.png','dataBase64':base64.b64encode(png()).decode()}]}, key='image-lifecycle-private')
    assert status == 202, value
    task = value['taskId']
    e.stop(e.control)
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = len(e.fixture.requests)
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'image_lifecycle_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ImageLifecycleScenario',
                   '-Dloader.path='+str(classes), '-cp', str(native), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(props), task]
        with (e.evidence/(tag+'.log')).open('w') as log:
            child = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=150)
        lines = (e.evidence/(tag+'.log')).read_text().splitlines()
        (e.evidence/(tag+'-exit.json')).write_text(json.dumps({'exit': child.returncode, 'jarSHA256': hashlib.sha256(native.read_bytes()).hexdigest()}))
        assert child.returncode == 0, tag+' see original log'
        assert any(v.startswith('RESTORED ') for v in lines)
        sides[tag] = [json.loads(v[5:]) for v in lines if v.startswith('CALL ')]
        (e.evidence/(tag+'-calls.json')).write_text(json.dumps(sides[tag], ensure_ascii=False, indent=2))
    assert len(e.fixture.requests) == requests, 'no provider execution'
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
    for file in (repo/'target/test-classes').rglob('ImageLifecycleScenario*.class'):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/ImageLifecycleScenario.java', archive/'ImageLifecycleScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', '26522f0fa1bf6ec373a62473d9a7898f07be7777'], cwd=repo, text=True).splitlines()
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
    e = ImageExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar, classes)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
