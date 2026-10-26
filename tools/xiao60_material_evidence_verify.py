"""Material receipts/history/image evidence; private MySQL and outer rollback, no provider or tool."""
import argparse, hashlib, json, shutil, subprocess, traceback
from pathlib import Path
from p2_verify import run
from p4_image_verify import ImageExperiment as Experiment, png
import base64

def compare(e, sides):
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-material-evidence-removed-sql.json').read_text())
    def norm(sql):
        for table,columns in inventory['columns'].items():sql=sql.replace(','.join(columns),'*')
        return ' '.join(sql.split())
    summary={};seen=set()
    for tag,calls in sides.items():
        by={c['name']:c for c in calls};assert len(by)==len(calls)==23
        assert by['file-before']['result']==json.loads(by['file-before']['before']['task']['material_snapshot'])['target']
        assert by['file-no-material']['result'] is None and by['file-after-write']['result'] is None
        for name in ['bind-first','bind-replay']:
            r=by[name]['after']['task'];assert hashlib.sha256(r['material_file_snapshot'].encode()).hexdigest()==r['material_file_digest']
        assert by['bind-first']['after']['task']['material_file_snapshot']==by['bind-replay']['after']['task']['material_file_snapshot']
        for name in ['image-evidence','image-evidence-replay','image-internal','image-duplicate-references']:
            c=by[name];result=c['result'];assert len(result['images'])==(2 if name=='image-duplicate-references' else 1) and result['localProtocol']
            assert c['imageStoreReadDelta']==len(result['images'])
            if name=='image-duplicate-references':assert result['images'][0]==result['images'][1]
            for image in result['images']:
                src=next(r for r in c['before']['invocation'] if r['id']==image['result']['sourceRef'][6:]);assert image['outputDigest']==src['output_digest']
                assert image['outputDigest']==hashlib.sha256(src['output_snapshot'].encode()).hexdigest()
                cp=json.loads(next(r for r in c['before']['step'] if r['id']==image['stepId'])['request_snapshot'])
                assert cp['bodyDigest']==hashlib.sha256(cp['requestBody'].encode()).hexdigest()==image['requestBodyDigest']
                body=json.loads(cp['requestBody']);parts=[m['content'][1]['image_url']['url'] for m in body['messages'] if isinstance(m.get('content'),list)]
                assert parts and all(p.startswith('data:image/png;base64,') for p in parts)
                for part in parts:
                    data=base64.b64decode(part.split(',',1)[1],validate=True)
                    assert data==png() and hashlib.sha256(data).hexdigest()==image['result']['image']['sha256'] and len(data)==image['result']['image']['size']
        assert by['history-media-projection']['imageStoreReadDelta']==2
        assert by['history-media-config']['imageStoreReadDelta']==1 and by['history-media-schema']['imageStoreReadDelta']==1
        for name in ['image-fence','image-permission','image-dispatch']:assert by[name]['imageStoreReadDelta']==0
        assert by['image-missing-part']['imageStoreReadDelta']==1
        relations=0;api={'statement':{},'execute':{},'setters':{},'getters':{},'getterTypes':{}}
        for c in calls:
            t=c['before']['task'];tenant=t['tenant_id'];tid=t['id']
            assert c['sessionPrior']==c['sessionAfter']
            assert len({q['connection'] for q in c['sql']})<=1,(tag,c['name'])
            if c['name']!='bind-first':
                for key in ['budget','invocation','step','model_dispatch','task_image_object','reservation','event']:assert c['before'][key]==c['after'][key],(tag,c['name'],key)
            for q in c['sql']:
                for field in ['statement','execute']:api[field][q[field]]=api[field].get(q[field],0)+1
                for v in q['setters']:api['setters'][v]=api['setters'].get(v,0)+1
                for g in q['getters']:
                    for field,value in [('getters',g['method']),('getterTypes',g['type'])]:api[field][value]=api[field].get(value,0)+1
                candidates=[op for op in inventory['operations'] if norm(op['original'])==norm(q['sql'])]
                if not candidates:continue
                op=candidates[0]['name'];seen.add(op);a=list(q['binds'].values())
                if op=='bindMaterialReceipt':r=c['after']['task'];expected=[r['material_file_snapshot'],hashlib.sha256(r['material_file_snapshot'].encode()).hexdigest(),tenant,tid]
                elif op in ['successfulMaterialWrite','existingMaterialStep']:expected=[tenant,tid]
                elif op=='materialPrecedingSteps':expected=[tenant,tid,1 if c['name']=='history-initial' else 2]
                elif op=='materialStepInvocations':expected=[tenant,tid,c['before']['step'][0]['id']]
                else:
                    table={'imageComposition':'invocation','imageCompositionStep':'step','imageCompositionDispatch':'model_dispatch','imageSourceDigest':'invocation'}[op]
                    r=next(r for r in c['before'][table] if r['id']==a[2]);expected=[tenant,tid,r['id']]
                assert a==expected,(tag,c['name'],op,a,expected);relations+=1
        summary[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'parameterRelationships':relations,'api':api}
    assert seen=={op['name'] for op in inventory['operations']},seen
    for left,right in zip(sides['old'],sides['new']):
        assert left['name']==right['name'] and left['code']==right['code']
        assert [norm(q['sql']) for q in left['sql']]==[norm(q['sql']) for q in right['sql']],left['name']
        assert [list(q['binds']) for q in left['sql']]==[list(q['binds']) for q in right['sql']]
    (e.evidence/'summary.json').write_text(json.dumps({'counts':summary,'all9BusinessSitesReached':True,'syntheticMaterialQualificationNotPaidAdmission':True,'realPrivateImageBytes':True,'calls':{tag:[{'name':c['name'],'code':c['code'],'sql':len(c['sql'])} for c in calls] for tag,calls in sides.items()}},ensure_ascii=False,indent=2))


def verify(e, old, classes):
    e.prepare()
    e.stop(e.control)
    resources=Path(__file__).resolve().parents[1]/'src/main/resources/plugins/builtin'
    for tool in ['write']:
        digest=hashlib.sha256((resources/tool/'output.schema.json').read_bytes()).hexdigest()
        e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+tool+"','0.1.0','"+digest+"');")
    task='created-by-public-java-submit'
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'material_evidence_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.MaterialEvidenceScenario',
                   '-Dloader.path='+str(classes), '-cp', str(native), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(props), task, base64.b64encode(png()).decode()]
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
    for file in list((repo/'target/test-classes').rglob('MaterialEvidenceScenario*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/MaterialEvidenceScenario.java', archive/'MaterialEvidenceScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', '1bfd41249eb39196e1d0bfbc552d76c559102f70'], cwd=repo, text=True).splitlines()
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
