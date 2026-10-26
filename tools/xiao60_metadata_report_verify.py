"""Directory ledger lifecycle; synthetic Evidence, private MySQL, no filesystem/tool execution."""
import argparse, hashlib, json, shutil, subprocess, traceback
from pathlib import Path
from p2_verify import Experiment, run

def compare(e,sides):
    summary={}
    expected_directory=['task','task_directory','invocation_path_binding','task_attempt','step','invocation','model_dispatch','reservation','event']
    expected_todo=['task','task_todo_projection','task_attempt','step','invocation','model_dispatch','reservation','event']
    for tag,calls in sides.items():
        assert len(calls)==21,len(calls)
        by={c['name']:c for c in calls}
        relations=0
        for c in calls:
            assert len({q['connection'] for q in c['sql']})==1
            task=c['before']['task']
            scans=[q for q in c['sql'] if q['sql'].startswith('SELECT * FROM ') and ' FOR ' not in q['sql']]
            if c['name'] in ['directory-cancel','todo-cancel','directory-quiescent-close','todo-quiescent-close']:
                expected=expected_todo if c['name'].startswith('todo-') else expected_directory
                assert len(scans)==len(expected),(tag,c['name'],[q['sql'] for q in scans])
                assert [q['sql'].split()[3] for q in scans]==expected
                for q in scans:
                    assert list(q['binds'].values())==[task['tenant_id'],task['id']]
                    relations+=2
            elif c['name'].endswith('cancel-replay') or c['name']=='directory-ready':
                assert not scans
                assert c['before']['event']==c['after']['event']
            source=[q for q in c['sql'] if q['sql'].startswith('SELECT id,output_snapshot,output_digest,plugin_version,plugin_digest FROM invocation')]
            if c['name'].startswith(('ordinary-','composed-')):
                rejected=c['name'].endswith(('fence','missing-time','permission'))
                assert len(source)==(0 if rejected else 1)
                if source:
                    q=source[0]
                    assert list(q['binds'].values())==[task['tenant_id'],task['id']]
                    assert (" AND plugin_version='0.3.0'" in q['sql'])==c['name'].startswith('ordinary-')
                    assert q['sql'].endswith(' ORDER BY ordinal')
                    relations+=2
                if c['code']=='OK':
                    sources=c['result']['sources']
                    expected=[] if c['name'].endswith('empty') else sorted([r for r in c['before']['invocation'] if r['tool']=='web_fetch' and r['state']=='SUCCEEDED' and (c['name'].startswith('composed-') or r['plugin_version']=='0.3.0')],key=lambda r:r['ordinal'])
                    assert sources==[{'invocationId':r['id'],'output':r['output_snapshot'],'digest':r['output_digest']} for r in expected]
                    assert c['result']['productionTime']==task['report_time']+'Z'
                    for r in expected:assert hashlib.sha256(r['output_snapshot'].encode()).hexdigest()==r['output_digest']
        summary[tag]={'calls':len(calls),'sql':sum(len(c['sql']) for c in calls),'binds':sum(len(q['binds']) for c in calls for q in c['sql']),'relations':relations,'statements':sum(q['statement']=='Statement' for c in calls for q in c['sql'])}
    for old,new in zip(sides['old'],sides['new']):
        assert old['name']==new['name'] and old['code']==new['code']
        assert [q['sql'] for q in old['sql']]==[q['sql'] for q in new['sql']],old['name']
        assert [list(q['binds']) for q in old['sql']]==[list(q['binds']) for q in new['sql']]
    (e.evidence/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))


def verify(e, old, classes):
    e.prepare()
    e.stop(e.control)
    for table in ['task_directory','invocation_path_binding','task_todo_projection']:
        e.sql("GRANT INSERT,UPDATE ON jimu."+table+" TO 'runtime'@'%';")
    resources=Path(__file__).resolve().parents[1]/'src/main/resources/plugins/builtin'
    for name in ['working_directory','read','write','edit','todo_write','present','compose_report']:
        digest=hashlib.sha256(((resources.parent.parent/'directory/working_directory-0.1.0' if name=='working_directory' else resources.parent.parent/'todo/todo_write-0.1.0' if name=='todo_write' else resources/name)/'output.schema.json').read_bytes()).hexdigest()
        e.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+name+"','0.1.0','"+digest+"');")
    e.sql("UPDATE installation SET plugin_version='0.3.0' WHERE plugin_id='web_fetch';")
    task='created-by-public-java-submit'
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = (dict(e.fixture.models), dict(e.fixture.tools))
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'metadata_report_' + tag
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
        settings.update({'jimu.web.mode':'public','jimu.web.allowed-domains':'fixture.example','jimu.web.public-dispatch-enabled':'true','jimu.model.profiles.a.model':'local-byte-accounted-v1','jimu.model.profiles.a.revision':'local-byte-accounted-v1','jimu.model.profiles.a.response-bytes':'65536','jimu.model.profiles.a.input-limit.version':'1','jimu.model.profiles.a.input-limit.max-tokens':'8192','jimu.model.profiles.a.input-limit.proof':'test-whole-request-utf8-v1'})
        with props.open('a') as out:
            for key,value in settings.items():out.write(key+'='+str(value)+'\n')
        assert '/'+database+'?' in props.read_text()
        native = e.root/(tag+'.jar')
        shutil.copyfile(binary, native)
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.MetadataReportScenario',
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
    for file in list((repo/'target/test-classes').rglob('MetadataReportScenario*.class')):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/MetadataReportScenario.java', archive/'MetadataReportScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'f83f909cb95a07b65d56c51a0aca0d07da97645c'], cwd=repo, text=True).splitlines()
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
