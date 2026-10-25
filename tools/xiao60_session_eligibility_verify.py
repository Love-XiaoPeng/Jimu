"""Narrow session eligibility and original lock chain comparison; frozen private copies, no UNKNOWN retries."""
import argparse
import hashlib
import json
import shutil
import signal
import subprocess
import traceback
from pathlib import Path

from p2_verify import run
from prior_session_verify import PriorExperiment
from session_trace_verify import TraceExperiment


def verify(e, old, scoped, classes, phase):
    e.prepare()
    # Admitted caller stays queued: account trials do not need a worker or provider.
    task = e.prior_task('facts scope 🧱') if scoped else e.task('facts native 🧱', [])
    public = {}
    for name, subject, target, expected in [('owner', 'a', task, 200),
            ('wrong-owner', 'c', task, 404), ('wrong-tenant', 'b', task, 404),
            ('absent', 'a', '00000000-0000-0000-0000-000000000000', 404)]:
        status, body = e.http('GET', '/api/tasks/' + target + '/session-history', subject=subject)
        public[name] = {'status': status, 'body': body}
        assert status == expected, (name, status, body)
    (e.evidence / 'new-public-http.json').write_text(json.dumps(public, ensure_ascii=False, indent=2))
    dump = run(['docker', 'exec', e.cid, 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for tag in ('old', 'new'):
        database = 'facts_' + tag
        e.sql(create.replace('`jimu`', '`' + database + '`') + ';')
        e.sql('USE ' + database + ';\n' + dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`' + database + '`.') + ';')
        config = e.root / (tag + '.properties')
        config.write_text((e.control['folder'] / 'runtime.properties').read_text().replace('/jimu?', '/' + database + '?'))
        assert '/' + database + '?' in config.read_text()
        configs[tag] = config
    (e.evidence / 'frozen-source.json').write_text(json.dumps({
        'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(), 'task': task,
        'copiesCreatedBeforeTrials': ['facts_old', 'facts_new'],
        'boundary': 'Admitted queued caller, empty scope sources. Queued admission is frozen. Driver claims/prepares/responds/cancels/admission only inside outer rollback. Event clock SELECT alone uses fixed test timestamp. Fixture task window renewal rolls back; original query/socket/transaction deadline remains. No provider or durable caller trial. Successful tool read, if present, is only an outer-rollback sample.'}, indent=2))
    provider = len(e.fixture.requests)
    result = {}
    for tag, binary in [('old', old), ('new', e.jar)]:
        command = [e.java, '-Xmx256m',
            '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionEligibilityScenario',
            '-Dloader.path=' + str(classes), '-cp', str(binary),
            'org.springframework.boot.loader.launch.PropertiesLauncher', str(configs[tag]), task, phase]
        (e.evidence / (tag + '-command.json')).write_text(json.dumps(command, indent=2))
        file = e.evidence / (tag + '.log')
        with file.open('w') as log:
            child = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=220)
        lines = file.read_text().splitlines()
        result[tag] = {'exit': child.returncode,
            'results': [json.loads(s[7:]) for s in lines if s.startswith('RESULT ')],
            'restored': [s[9:] for s in lines if s.startswith('RESTORED ')],
            'sql': [s[4:] for s in lines if s.startswith('SQL ')]}
        (e.evidence / 'comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        assert len(e.fixture.requests) == provider, 'must not replay provider'
    assert all(r['exit'] == 0 for r in result.values()), 'driver failures preserved; no UNKNOWN reentry'
    expected = 27
    assert all(len(r['results']) == expected and len(r['restored']) == expected for r in result.values())
    comparisons=[]
    for left,right in zip(result['old']['results'],result['new']['results']):
        assert left['name']==right['name'] and left['code']==right['code'] and left['injected']==right['injected']
        row={'name':left['name'],'originalRawEqual':left['raw']==right['raw']}
        if left['raw'] is not None and left['raw'].startswith('{'):
            a,b=json.loads(left['raw']),json.loads(right['raw'])
            assert [v['type'] for v in a['events']]==[v['type'] for v in b['events']]
            assert [v['seq'] for v in a['events']]==[v['seq'] for v in b['events']]
            for x,y in zip(a['events'],b['events']):
                # Each side already verified original digest/length and reliable seq against DB.
                # Only generated step/attempt identifiers differ, plus original request/source digests.
                xv,yv=json.loads(x['event_snapshot']),json.loads(y['event_snapshot'])
                if left['name']=='read':
                    xd,yd=dict(xv['data']),dict(yv['data'])
                    xd.pop('output_sha256',None);yd.pop('output_sha256',None)
                    assert xd==yd
                    xv=dict(xv,data=xd);yv=dict(yv,data=yd)
                for key in ['version','session_id','seq','type','time','content_policy','redacted','data','source_event_refs','source_event_seqs']:
                    if left['name']=='idempotent-retrieval' and key=='session_id':
                        assert xv[key]==a['history']['task_id'] and yv[key]==b['history']['task_id']
                    else:
                        assert xv.get(key)==yv.get(key),(left['name'],key,xv,yv)
                assert xv['source']['kind']==yv['source']['kind']
                assert x['event_bytes']==y['event_bytes'] and x['reliable_event_seq']==y['reliable_event_seq']
            for key in ['next_seq','body_bytes','metadata_bytes','held_bytes','content_state']:
                assert a['history'][key]==b['history'][key],(left['name'],key)
        else:
            assert left['raw']==right['raw']
        comparisons.append(row)
    sql_comparisons=[]
    def text(v):
        return ''.join(v['text'].replace("fact_id='terminal'",'fact_id=?').translate(str.maketrans('','', '()')).split())
    for left,right in zip(result['old']['sql'],result['new']['sql']):
        lname,lrows=left.split(' ',1);rname,rrows=right.split(' ',1)
        assert lname==rname
        lrows,rrows=json.loads(lrows),json.loads(rrows)
        assert [text(v) for v in lrows]==[text(v) for v in rrows],lname
        def count(rows,prefix):return sum(v['text'].startswith(prefix) for v in rows)
        counts={prefix:count(lrows,prefix) for prefix in ['SELECT metadata_bytes','UPDATE task_session_history SET metadata_bytes','SELECT tenant_id,id,owner_actor_id']}
        assert counts=={prefix:count(rrows,prefix) for prefix in counts},lname
        sql_comparisons.append({'name':lname,'old':len(lrows),'new':len(rrows),'originalSQLTextEqual':[v['text'] for v in lrows]==[v['text'] for v in rrows],
            'normalizedSequenceEqual':True,'accountingCounts':counts,'normalization':'whitespace/parentheses plus original terminal literal to bound fact; original SQL/binds retained'})
    assert len(result['old']['sql'])==len(result['new']['sql'])
    (e.evidence/'sql-comparison.json').write_text(json.dumps(sql_comparisons,indent=2))
    if phase=='reads':
        proofs={}
        for tag in ('old','new'):
            rows=[json.loads(line[14:]) for line in (e.evidence/(tag+'.log')).read_text().splitlines() if line.startswith('READ_ORIGINAL ')]
            assert len(rows)==1
            assert hashlib.sha256(rows[0]['raw'].encode()).hexdigest()==rows[0]['digest']
            proofs[tag]=rows[0]
        assert json.loads(proofs['old']['raw'])==json.loads(proofs['new']['raw'])
        (e.evidence/'read-byte-proof.json').write_text(json.dumps({'old':proofs['old'],'new':proofs['new'],
            'rawEqual':proofs['old']['raw']==proofs['new']['raw'],
            'boundary':'Each original raw/hash verified through invocation/binding/event, then JSON values compared. Only proved output_sha256 omitted in event value comparison.'},ensure_ascii=False,indent=2))

    (e.evidence/'value-comparison.json').write_text(json.dumps(comparisons,indent=2))
    (e.evidence / 'raw-comparison.json').write_text(json.dumps([
        {'name': left['name'], 'rawEqual': left['raw'] == right['raw'],
         'oldSHA256': hashlib.sha256((left['raw'] or '').encode()).hexdigest(),
         'newSHA256': hashlib.sha256((right['raw'] or '').encode()).hexdigest()}
        for left, right in zip(result['old']['results'], result['new']['results'])], indent=2))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--only', choices=['both', 'native', 'scope'], default='both')
    p.add_argument('--phase', choices=['all', 'main', 'reads', 'remaining'], default='all')
    a = p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest() == 'd13f81ee3078d169213c3187c7336a532cb15a790ed7d57d9f180edf9d83ab4d'
    repo = Path(__file__).resolve().parents[1]
    archive = a.evidence / 'executed'
    archive.mkdir(parents=True, exist_ok=False)
    classes = archive / 'classes'
    classes.mkdir()
    for file in (repo / 'target/test-classes').rglob('SessionEligibilityScenario*.class'):
        dest = classes / file.relative_to(repo / 'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/SessionEligibilityScenario.java', archive / 'SessionEligibilityScenario.java')
    shutil.copyfile(__file__, archive / Path(__file__).name)
    shutil.copyfile(repo / 'target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst', archive / 'compiled-inputs.txt')
    (archive / 'products.json').write_text(json.dumps([
        {'role': role, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size}
        for role, f in [('old', a.old_jar), ('new', a.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([
        {'path': str(f.relative_to(archive)), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(archive.rglob('*')) if f.is_file()], indent=2))
    failures = []
    for name, klass in [('native', TraceExperiment), ('scope', PriorExperiment)]:
        if a.only not in ('both', name):
            continue
        e = klass(a.jar, a.evidence / name)
        try:
            verify(e, a.old_jar, name == 'scope', classes, a.phase)
        except BaseException:
            (e.evidence / 'failure.txt').write_text(traceback.format_exc())
            failures.append(name)
        finally:
            try:
                e.cleanup()
            except BaseException:
                (e.evidence / 'cleanup-exception.txt').write_text(traceback.format_exc())
                failures.append(name + '-cleanup')
    assert not failures, 'preserved failures: ' + str(failures)
