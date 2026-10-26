"""Bounded original image fixture at the changed material/Service binding; no old matrix."""
import argparse, hashlib, json, shutil, subprocess, traceback
from pathlib import Path
from p2_verify import run
from p4_image_verify import ImageExperiment as Experiment, png
import base64

def compare(e, sides):
    summary = {}
    names = ['history-media-projection', 'history-media-config', 'history-media-schema', 'image-evidence', 'image-evidence-replay', 'image-internal', 'image-duplicate-references', 'image-fence', 'image-permission']
    for tag, calls in sides.items():
        assert [c['name'] for c in calls] == names
        assert [c['code'] for c in calls] == ['OK', 'SNAPSHOT_CORRUPT', 'SNAPSHOT_CORRUPT'] + ['OK'] * 4 + ['FENCE_REJECTED', 'ACCESS_REVOKED']
        assert len({q['connection'] for c in calls for q in c['sql']}) == 1
        assert any('task_image_input' in q['sql'] for c in calls for q in c['sql'])
        assert any('task_image_object' in q['sql'] for c in calls for q in c['sql'])
        relations = 0
        for c in calls:
            assert c['sessionPrior'] == c['sessionAfter']
            task = c['before']['task']
            for q in c['sql']:
                if q['sql'].startswith('SELECT ') and any(' FROM '+t+' WHERE tenant_id=? AND task_id=?' in q['sql'] for t in ['task_image_input', 'task_image_object', 'invocation', 'step', 'model_dispatch']):
                    assert list(q['binds'].values())[:2] == [task['tenant_id'], task['id']]
                    relations += 2
            if not isinstance(c['result'], dict):
                continue
            result = c['result']
            assert result['localProtocol'] and result['web']['sources'] == []
            assert len(result['images']) == (2 if c['name'] == 'image-duplicate-references' else 1)
            for item in result['images']:
                ref = item['result']
                source = next(v for v in c['before']['invocation'] if v['id'] == ref['sourceRef'][6:])
                assert ref == json.loads(source['output_snapshot'])
                assert item['outputDigest'] == source['output_digest'] == hashlib.sha256(source['output_snapshot'].encode()).hexdigest()
                step = next(v for v in c['before']['step'] if v['id'] == item['stepId'])
                checkpoint = json.loads(step['request_snapshot'])
                assert item['dispatchId'] == step['dispatch_id']
                assert item['configDigest'] == checkpoint['configDigest']
                assert item['requestBodyDigest'] == checkpoint['bodyDigest']
                assert item['responseDigest'] == step['response_digest']
                image = next(v for v in c['before']['task_image_object'] if v['image_id'] == ref['image']['imageId'])
                assert image['sha256'] == ref['image']['sha256'] and image['size'] == ref['image']['size']
                relations += 10
        assert calls[3]['result'] == calls[4]['result'] == calls[5]['result']
        summary[tag] = {'calls':len(calls), 'sql':sum(len(c['sql']) for c in calls), 'binds':sum(len(q['binds']) for c in calls for q in c['sql']), 'parameterAndResultRelations':relations, 'sameConnection':True}
    for left, right in zip(sides['old'], sides['new']):
        assert left['name'] == right['name'] and left['code'] == right['code']
        assert [q['sql'] for q in left['sql']] == [q['sql'] for q in right['sql']], left['name']
        assert [list(q['binds']) for q in left['sql']] == [list(q['binds']) for q in right['sql']]
        assert type(left['result']) == type(right['result'])
    assert summary['old'] == summary['new']
    (e.evidence/'summary.json').write_text(json.dumps(summary, indent=2))


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
        database = 'facade_' + tag
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
        command = [e.java, '-Xmx256m', '-Djimu.facade-verification=true', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.MaterialEvidenceScenario',
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
    changed = subprocess.check_output(['git', 'diff', '--name-only', '77e48a1a1d03b3849786c3d2edadb1aec9bb3ad8'], cwd=repo, text=True).splitlines()
    changed += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=repo, text=True).splitlines()
    for name in sorted(set(changed)):
        if not name.startswith('src/main/') or not (repo/name).is_file():
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
