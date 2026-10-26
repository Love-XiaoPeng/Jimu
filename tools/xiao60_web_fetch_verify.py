"""Web observation/cancellation/preservation; exact old/new private MySQL and outer rollback."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import traceback

from p2_verify import Experiment, run
from mcp_verify import McpExperiment
from p3_model_verify import config


class WebExperiment(Experiment):
    start = McpExperiment.start

    def prepare(self):
        self.fixture.requests = []
        super().prepare()
        self.stop(self.control)
        settings = config({'a': self.fixture}, paid=False)
        settings.pop('jimu.model.evaluation-run-id')
        self.common.update(settings)
        self.common.update({'jimu.web.mode': 'public', 'jimu.web.allowed-domains': 'fixture.example', 'jimu.web.public-dispatch-enabled': 'true'})
        self.sql("UPDATE installation SET plugin_version='0.3.0' WHERE plugin_id='web_fetch';")
        self.control = self.start('web-fetch-control', 'control')


def compare(e, sides):
    def effect_view(proof):
        value=json.loads(json.dumps(proof))
        # fenced(false) increments revision even before a later rejection. These
        # joined outer-transaction writes remain visible until its final rollback.
        value['task'].pop('revision')
        value['task'].pop('updated_at')
        return value
    summary = {}
    for tag, calls in sides.items():
        byname = {v['name']: v for v in calls}
        assert len(calls) == len(byname) == 29
        phase = byname['observe-phase']
        assert [(x['type'],x['code']) for x in phase['after']['event'] if x['type'].startswith('web.')] == [('web.fetch_phase','BODY_NONE')]
        assert effect_view(byname['observe-phase-replay']['before']) == effect_view(byname['observe-phase-replay']['after'])
        preserve = byname['preserve-new']
        invocation = preserve['after']['invocation'][0]
        assert len(invocation) == 27 and invocation['state'] == 'RUNNING'
        assert hashlib.sha256(invocation['output_snapshot'].encode()).hexdigest() == invocation['output_digest']
        proof = next(x for x in preserve['after']['event'] if x['type'] == 'web.result_preserved')
        assert len(proof['code']) == 64
        assert preserve['before']['reservation'] == preserve['after']['reservation']
        assert preserve['before']['budget'] == preserve['after']['budget']
        assert effect_view(byname['preserve-replay']['before']) == effect_view(byname['preserve-replay']['after'])
        cancel = byname['cancel-expired-original']
        assert cancel['before']['task']['state'] == 'NEEDS_RECONCILIATION'
        assert cancel['before']['task_attempt'][0]['state'] == 'STOPPED'
        assert cancel['before']['invocation'] == cancel['after']['invocation']
        assert cancel['before']['reservation'] == cancel['after']['reservation']
        assert cancel['before']['budget'] == cancel['after']['budget']
        assert cancel['before']['task']['fence'] > cancel['before']['task_attempt'][0]['fence']
        added = cancel['after']['event'][len(cancel['before']['event']):]
        assert [(x['type'],x['code']) for x in added] == [('web.fetch_cancelled','BODY_CHECKPOINT_REJECTED'),('web.cancel_elapsed_ms','123'),('web.checkpoint_rejected','FENCE_REJECTED:7'),('web.fetch_failed','BODY_CANCELLED')]
        assert byname['cancel-unknown-replay']['before'] == byname['cancel-unknown-replay']['after']
        rows=[]
        for call in calls:
            if call['code'] != 'OK':
                assert effect_view(call['before']) == effect_view(call['after']), call['name']
            revision_delta=call['after']['task']['revision']-call['before']['task']['revision']
            fence_delta=0 if call['name'].startswith('cancel-') or call['code'] in ['FENCE_REJECTED','java.lang.NullPointerException'] else 1
            expected_delta=fence_delta+len(call['after']['event'])-len(call['before']['event'])
            assert revision_delta==expected_delta,(call['name'],revision_delta)
            assert len({v['connection'] for v in call['sql']}) <= 1
            rows.append({'name':call['name'],'code':call['code'],'sqlCount':len(call['sql']), 'statement':sum(v['statement']=='Statement' for v in call['sql']), 'prepared':sum(v['statement']=='PreparedStatement' for v in call['sql'])})
        summary[tag]=rows
    assert summary['old'] == summary['new']
    inventory=json.loads((Path(__file__).resolve().parents[1]/'docs/verification/XIAO-60/evidence/b2-web-fetch-removed-sql.json').read_text())
    bindings=0
    for old,new in zip(sides['old'],sides['new']):
        for index,(left,right) in enumerate(zip(old['sql'],new['sql'])):
            normalized=right['sql']
            for table,columns in inventory['columns'].items():
                normalized=normalized.replace('SELECT '+','.join(columns)+' FROM '+table,'SELECT * FROM '+table)
            assert left['sql'] == normalized, (old['name'],left['sql'],normalized)
            assert list(left['binds']) == list(right['binds'])
            # Parameters that identify this invocation/step/task must reach the same
            # original positions. Generated missing/foreign UUIDs are separately rejected.
            for current in [old,new]:
                row=current['before']['invocation'][0]
                if normalized.startswith('SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?'):
                    values=list(current['sql'][index]['binds'].values())
                    assert values[:2] == [row['tenant_id'],row['task_id']]
                    if current['name']=='preserve-missing-invocation':
                        assert len(values[2])==36 and values[2]!=row['id']
                    else:
                        assert values[2]==row['id']
            bindings += len(left['binds'])
    (e.evidence/'summary.json').write_text(json.dumps({'calls':summary,'pairedParameterPositions':bindings},ensure_ascii=False,indent=2))


def verify(e, old, classes):
    e.prepare()
    status, value = e.http('POST', '/api/tasks', {'goal': 'web-fetch-private', 'capabilityIds': ['web_fetch']}, key='web-fetch-private')
    assert status == 202, value
    task = value['taskId']
    e.stop(e.control)
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = len(e.fixture.requests)
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'web-fetch_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.WebFetchScenario',
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
    for file in (repo/'target/test-classes').rglob('WebFetchScenario*.class'):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/WebFetchScenario.java', archive/'WebFetchScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', '4929a7f7bb380d70d90682e550db2eabfeee2d64'], cwd=repo, text=True).splitlines()
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
    e = WebExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar, classes)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
