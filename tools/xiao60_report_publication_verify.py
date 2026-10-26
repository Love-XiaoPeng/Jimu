"""Two report publication entries; exact old/new private MySQL, legal stored setup, outer rollback."""
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


class PublicationExperiment(Experiment):
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
        resources = Path(__file__).resolve().parents[1]/'src/main/resources/plugins/builtin'
        for name in ['present', 'compose_report']:
            digest = hashlib.sha256((resources/name/'output.schema.json').read_bytes()).hexdigest()
            self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','"+name+"','0.1.0','"+digest+"');")
        self.control = self.start('report-publication-control', 'control')


def compare(e, sides):
    summary = {}
    for tag, calls in sides.items():
        byname = {v['name']: v for v in calls}
        assert len(calls) == len(byname) == 20
        stage = byname['stage-new']
        artifacts = stage['after']['artifact']
        assert len(artifacts) == len(stage['result']) == 2
        assert all(len(a) == 17 and a['state'] == 'STAGING' for a in artifacts)
        for a in artifacts:
            receipt = json.loads(a['file_receipt'])
            assert hashlib.sha256(a['file_receipt'].encode()).hexdigest() == a['receipt_digest']
            assert receipt['sha256'] == a['sha256'] and receipt['size'] == a['size']
        assert byname['stage-replay']['before']['artifact'] == byname['stage-replay']['after']['artifact']
        success = byname['present-success']
        assert all(a['state'] == 'PUBLISHED' and a['snapshot_key'] == a['upload_id']+'-'+a['sha256'] for a in success['after']['artifact'])
        settled = [r for r in success['after']['reservation'] if r['category'] in ['TOOL', 'STORAGE']]
        assert len(settled) == 3
        assert all(r['state'] == 'SETTLED' and r['amount'] == r['actual'] for r in settled)
        present = next(i for i in success['after']['invocation'] if i['tool'] == 'present')
        assert len(present) == 27 and present['state'] == 'SUCCEEDED'
        assert hashlib.sha256(present['output_snapshot'].encode()).hexdigest() == present['output_digest']
        assert [i['type'] for i in success['after']['event']].count('artifact.created') == 2
        assert [i['type'] for i in success['after']['event']].count('tool.succeeded') == 1
        late = byname['present-second-item-reject-outer-rollback']
        assert sorted(a['state'] for a in late['after']['artifact']) == ['PUBLISHED', 'STAGING']
        assert next(i for i in late['after']['invocation'] if i['tool'] == 'present')['state'] == 'RUNNING'
        rows = []
        for call in calls:
            assert len({v['connection'] for v in call['sql']}) == 1
            rows.append({'name': call['name'], 'code': call['code'], 'sqlCount': len(call['sql']),
                         'statement': sum(v['statement'] == 'Statement' for v in call['sql']),
                         'prepared': sum(v['statement'] == 'PreparedStatement' for v in call['sql'])})
        summary[tag] = rows
    assert summary['old'] == summary['new']
    artifact_columns = 'tenant_id,id,task_id,producer_invocation,upload_id,production_fence,state,snapshot_key,sha256,size,media_type,created_at,updated_at,expires_at,record_version,file_receipt,receipt_digest'
    invocation_columns = 'tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state,output_snapshot,output_digest,projection_version,updated_at,record_version,todo_expected_revision,todo_applied_revision,todo_committed_attempt_id,todo_committed_fence,todo_event_seq,todo_committed_at'
    for old, new in zip(sides['old'], sides['new']):
        for left, right in zip(old['sql'], new['sql']):
            normalized = right['sql'].replace('SELECT '+artifact_columns+' FROM artifact', 'SELECT * FROM artifact').replace('SELECT '+invocation_columns+' FROM invocation', 'SELECT * FROM invocation')
            assert left['sql'] == normalized, (old['name'], left['sql'], normalized)
            assert list(left['binds']) == list(right['binds'])
    (e.evidence/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))


def verify(e, old, classes):
    e.prepare()
    status, value = e.http('POST', '/api/tasks', {'goal': 'report-publication-private', 'capabilityIds': ['web_fetch', 'present', 'compose_report']}, key='report-publication-private')
    assert status == 202, value
    task = value['taskId']
    e.stop(e.control)
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = len(e.fixture.requests)
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'report-publication_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ReportPublicationScenario',
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
    for file in (repo/'target/test-classes').rglob('ReportPublicationScenario*.class'):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/ReportPublicationScenario.java', archive/'ReportPublicationScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    changed = subprocess.check_output(['git', 'diff', '--name-only', '699d99f85ba3084df1438861638619d339ec448a'], cwd=repo, text=True).splitlines()
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
    e = PublicationExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar, classes)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
