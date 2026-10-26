"""Three public publication entries; exact old/new private MySQL, legal stored setup, outer rollback."""
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
        self.control = self.start('publication-control', 'control')


def compare(e, sides):
    summary = {}
    for tag, calls in sides.items():
        byname = {v['name']: v for v in calls}
        assert len(calls) == len(byname) == 18
        first = byname['stage-new']
        a = first['after']['artifact'][0]
        aid = a['id']
        assert first['result']['id'] == aid and a['state'] == 'STAGING'
        assert a['sha256'] == hashlib.sha256('产物 publication fixture'.encode()).hexdigest()
        assert a['size'] == 3 and a['snapshot_key'] is None
        assert len(a) == 17 and a['file_receipt'] is None and a['receipt_digest'] is None
        assert a['producer_invocation'] == first['after']['invocation'][0]['id']
        assert first['after']['budget']['storage_held'] == first['before']['budget']['storage_held'] + 3
        reservation = next(v for v in first['after']['reservation'] if v['id'] == aid)
        assert reservation['state'] == 'RESERVED' and reservation['amount'] == 3 and reservation['actual'] is None
        assert byname['stage-staging-replay']['after']['artifact'] == first['after']['artifact']
        verified = byname['verify']['after']['artifact'][0]
        assert verified['state'] == 'VERIFIED' and verified['snapshot_key'] == 'immutable-fixture-key'
        assert byname['verify-replay-zero-updates']['after']['artifact'][0] == verified
        published = byname['publish']['after']['artifact'][0]
        assert published['state'] == 'PUBLISHED' and published['sha256'] == a['sha256']
        assert byname['publish-replay']['before']['artifact'] == byname['publish-replay']['after']['artifact']
        assert byname['publish-replay']['before']['event'] == byname['publish-replay']['after']['event']
        assert byname['stage-published-replay']['result']['state'] == 'PUBLISHED'
        settled = next(v for v in byname['publish']['after']['reservation'] if v['id'] == aid)
        assert settled['state'] == 'SETTLED' and settled['actual'] == settled['amount'] == 3
        types = [v['type'] for v in byname['publish']['after']['event']]
        for event in ['artifact.staging', 'artifact.verified', 'artifact.created']:
            assert types.count(event) == 1
        assert len(byname['verified-missing-count-ignored']['after']['event']) == len(byname['verified-missing-count-ignored']['before']['event']) + 1
        rows = []
        for call in calls:
            assert len({v['connection'] for v in call['sql']}) == 1
            if call['name'].startswith(('fence-', 'permission-')):
                assert call['before']['artifact'] == call['after']['artifact']
                assert not any('FROM artifact WHERE' in v['sql'] or v['sql'].startswith(('UPDATE artifact', 'INSERT INTO artifact')) for v in call['sql'])
            rows.append({'name': call['name'], 'code': call['code'], 'sqlCount': len(call['sql']),
                         'statement': sum(v['statement'] == 'Statement' for v in call['sql']),
                         'prepared': sum(v['statement'] == 'PreparedStatement' for v in call['sql'])})
        summary[tag] = rows
    assert summary['old'] == summary['new']
    # Projection expansion is intentional. Preserve statement/bind order and SQL otherwise;
    # generated IDs and independently observed database times are related only within each side.
    projection = 'tenant_id,id,task_id,producer_invocation,upload_id,production_fence,state,snapshot_key,sha256,size,media_type,created_at,updated_at,expires_at,record_version,file_receipt,receipt_digest'
    for old, new in zip(sides['old'], sides['new']):
        for left, right in zip(old['sql'], new['sql']):
            assert left['sql'] == right['sql'].replace('SELECT '+projection+' FROM artifact', 'SELECT * FROM artifact')
            assert list(left['binds']) == list(right['binds'])
    (e.evidence/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))


def verify(e, old, classes):
    e.prepare()
    status, value = e.http('POST', '/api/tasks', {'goal': 'publication-private', 'capabilityIds': ['web_fetch']}, key='publication-private')
    assert status == 202, value
    task = value['taskId']
    e.stop(e.control)
    dump = run(['docker', 'exec', e.cid, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    sides = {}
    requests = len(e.fixture.requests)
    for tag, binary in [('old', old), ('new', e.jar)]:
        database = 'publication_' + tag
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
        command = [e.java, '-Xmx256m', '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ArtifactPublicationScenario',
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
    for file in (repo/'target/test-classes').rglob('ArtifactPublicationScenario*.class'):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/ArtifactPublicationScenario.java', archive/'ArtifactPublicationScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    e = PublicationExperiment(args.jar, args.evidence)
    try:
        verify(e, args.old_jar, classes)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
