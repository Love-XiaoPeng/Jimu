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
from p2_verify import Experiment
from mcp_verify import NAMES, SCHEMA, canonical
import secrets


def compare(e, sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-mcp-lifecycle-removed-sql.json').read_text())
    summary={}; positions=0; relations=0
    for tag,calls in sides.items():
        byname={v['name']:v for v in calls}
        assert len(byname)==len(calls)==32,len(calls)
        binding=byname['binding']['result']; server=byname['server']['result']
        assert binding['servers']['controlled']==server
        assert byname['dispatch-normal']['result']==1 and byname['dispatch-cleanup']['result']==2
        assert byname['respond-normal']['after']['mcp_io'][0]['phase']=='RESPONDED'
        failed=byname['respond-error']['after']['mcp_io'][1]
        assert failed['phase']=='FAILED' and failed['error_code']=='MCP_UNAVAILABLE'
        for reserve,success in [('reserve','success'),('reserve-template','success-template')]:
            r=byname[reserve];s=byname[success]
            assert r['after']['budget']['storage_held']-r['before']['budget']['storage_held']==1048576
            result=next(x for x in s['after']['mcp_result'] if x['root_invocation_id'] not in [y['root_invocation_id'] for y in s['before']['mcp_result']])
            body=json.loads(result['body']);relations+=1
            assert len(body['pages'])==2 and len(body['cursorPositions'])==1
            assert hashlib.sha256(result['body'].encode()).hexdigest()==result['body_digest']
            assert result['byte_size']==len(result['body'].encode())+96
            assert s['after']['budget']['storage_held']-s['before']['budget']['storage_held']==result['byte_size']-1048576
            reservation=next(x for x in s['after']['reservation'] if x['id']=='mcp:'+result['root_invocation_id'])
            assert reservation['actual']==result['byte_size'] and reservation['state']=='SETTLED'
            inv=next(x for x in s['after']['invocation'] if x['id']==result['root_invocation_id'])
            assert inv['state']=='SUCCEEDED' and inv['output_snapshot']==body['pages'][0]
            assert hashlib.sha256(inv['output_snapshot'].encode()).hexdigest()==inv['output_digest']
            tool=next(x for x in s['after']['reservation'] if x['id']==inv['id'])
            assert tool['state']=='SETTLED' and tool['actual']==1
            page=byname['page' if reserve=='reserve' else 'page-template']['result']
            assert page==body['pages'][1]
        replay=byname['success-replay']
        assert replay['before']['mcp_result']==replay['after']['mcp_result']
        assert replay['before']['reservation']==replay['after']['reservation']
        assert byname['success-null-snapshot']['before']['mcp_result']==byname['success-null-snapshot']['after']['mcp_result']
        summary[tag]=[]
        for call in calls:
            assert len({v['connection'] for v in call['sql']})<=1,call['name']
            if call['code']!='OK':
                before=json.loads(json.dumps(call['before']));after=json.loads(json.dumps(call['after']))
                for x in [before,after]:
                    x['task'].pop('revision');x['task'].pop('updated_at')
                assert before==after,call['name']
            summary[tag].append({'name':call['name'],'code':call['code'],'sqlCount':len(call['sql'])})
    assert summary['old']==summary['new']
    def normalize(sql):
        for table,columns in inventory['columns'].items():
            sql=sql.replace('SELECT '+','.join(columns)+' FROM '+table,'SELECT * FROM '+table)
        sql=sql.replace('SELECT '+','.join('c.'+c for c in inventory['columns']['mcp_cursor'])+',','SELECT c.*,')
        return sql
    for old,new in zip(sides['old'],sides['new']):
        assert len(old['sql'])==len(new['sql'])
        for left,right in zip(old['sql'],new['sql']):
            assert normalize(left['sql'])==normalize(right['sql']),(old['name'],left['sql'],right['sql'])
            assert list(left['binds'])==list(right['binds'])
            positions+=len(left['binds'])
        for current in [old,new]:
            task=current['before']['task']
            for q in current['sql']:
                sql=q['sql'];a=list(q['binds'].values())
                if sql.startswith('INSERT INTO mcp_io'):
                    assert a[0:2]==[task['tenant_id'],task['id']]
                    inv=next(x for x in current['before']['invocation'] if x['id']==a[2]);assert inv['state']=='RUNNING'
                    assert a[3]==current['result'] and a[6]==('CLEANUP' if 'cleanup' in current['name'] else 'NORMAL');relations+=1
                if sql.startswith('INSERT INTO mcp_result'):
                    body=json.loads(a[7]);assert a[0:3]==[task['tenant_id'],task['id'],body['rootInvocation']]
                    assert a[3:7]==[body['server'],body['method'],body['bindingDigest'],body['projectionDigest']]
                    assert a[8]==hashlib.sha256(a[7].encode()).hexdigest() and a[9]==len(a[7].encode())+96;relations+=1
                if sql.startswith('INSERT INTO mcp_cursor'):
                    body=json.loads(next(x['body'] for x in current['after']['mcp_result'] if x['root_invocation_id']==a[5]));assert body['cursorPositions'][a[0]]==a[1]
                    assert a[2:]==[body['queryDigest'],task['tenant_id'],task['id'],body['rootInvocation']];relations+=1
    (e.evidence/'summary.json').write_text(json.dumps({'calls':summary,'pairedParameterPositions':positions,'relations':relations,'outerRollbackOnly':True,'providerRequests':0,'mcpRequests':0},indent=2))

def verify(e, old, classes):
    e.prepare()
    e.stop(e.control)
    for grant in ('INSERT,UPDATE ON mcp_io', 'INSERT,UPDATE ON mcp_result', 'INSERT,DELETE ON mcp_cursor'):
        e.sql("GRANT "+grant+" TO 'runtime'@'%';")
    contracts=Path(__file__).resolve().parents[1]/'src/main/resources/mcp'
    for name in NAMES:
        digest=hashlib.sha256((contracts/name/'output.schema.json').read_bytes()).hexdigest()
        e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+name+"','0.1.0','"+digest+"');")
    profile={'version':1,'tenant':'tenant-a','server':'controlled','epoch':1,'hostId':e.common['jimu.host-id'],'endpoint':'http://127.0.0.1:1/mcp','credentialRef':'fixture','credentialVersion':1,'allowedActors':['alice'],'allowedUris':[],'allowedUriPrefixes':['jimu-fixture://tenant-a/docs/'],'allowedTemplates':['jimu-fixture://tenant-a/docs/{name}'],'protocolVersion':'2025-06-18','serverInfo':{'name':'jimu-controlled','version':'1'},'serverCapabilities':{'resources':{}},'schemaDigest':SCHEMA}
    raw=canonical(profile)
    e.sql("INSERT INTO mcp_server(tenant_id,server_id,enabled,epoch,profile_snapshot,profile_digest) VALUES('tenant-a','controlled',TRUE,1,'"+raw+"','"+hashlib.sha256(raw.encode()).hexdigest()+"');")
    e.common['jimu.mcp.enabled']='true'
    e.common['jimu.mcp.credentials.tenant-a.controlled.fixture.1']=secrets.token_urlsafe(24)
    e.control=e.start('control-mcp-private','control')
    status,value=e.http('POST','/api/tasks',{'goal':'mcp-lifecycle-private','capabilityIds':NAMES,'budget':{'deadlineSeconds':120,'maxModelRequests':5,'maxToolInvocations':10}},key='mcp-lifecycle-private')
    assert status==202,value
    task=value['taskId']
    e.stop(e.control)
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'mcp_lifecycle_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.McpLifecycleScenario',
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
    for file in list((repo/'target/test-classes').rglob('McpLifecycleScenario*.class'))+list((repo/'target/test-classes').rglob('McpLifecycleValues*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/McpLifecycleScenario.java', archive/'McpLifecycleScenario.java')
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/McpLifecycleValues.java',archive/'McpLifecycleValues.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'de5f32d4757679c0f8c21b3cd26938b569539b6f'], cwd=repo, text=True).splitlines()
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
