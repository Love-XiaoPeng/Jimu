"""B1 query difference only: actual v4 proof/async/public/model paths and bounded locks."""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import shutil
import subprocess
import time
import traceback
from prior_session_verify import PriorExperiment


def verify(experiment, old_jar):
    experiment.prepare()
    evidence = experiment.evidence
    (evidence / 'database.txt').write_text(experiment.sql('SELECT VERSION(),@@server_uuid,DATABASE(); SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;'))
    source = experiment.prior_task('source explicit query nebula')
    worker = experiment.start('query-source-worker', 'worker')
    experiment.state(source, 'SUCCEEDED'); experiment.quiet(source); experiment.stop(worker)
    caller = experiment.prior_task('caller explicit query nebula', [source])
    before = experiment.search(caller, {'query': 'nebula'})
    experiment.assert_that(before['returned_count'] == 1, 'explicit async query retains whole source proof')
    worker = experiment.start('query-caller-worker', 'worker')
    experiment.state(caller, 'SUCCEEDED'); experiment.quiet(caller); experiment.stop(worker)
    experiment.assert_that(experiment.sql("SELECT tools_used FROM task WHERE id='"+caller+"';") == '2', 'real scoped search and read settle once')
    receipts = experiment.sql("SELECT seq FROM session_public_event WHERE task_id='"+caller+"' AND type='session.read' ORDER BY seq;").splitlines()
    experiment.assert_that(bool(receipts), 'native public read receipt exists')
    for seq in receipts:
        experiment.assert_that(experiment.event(caller, int(seq))['target']['type'] == 'session.read', 'complete READ proof publicly revalidated')
    snapshots = [experiment.metadata(caller), experiment.search(caller, {'query': 'nebula'}), experiment.event(caller, int(receipts[0]))]
    current = experiment.jar
    experiment.stop(experiment.control)
    experiment.jar = old_jar
    experiment.control = experiment.start('query-accepted-control', 'control')
    accepted = [experiment.metadata(caller), experiment.search(caller, {'query': 'nebula'}), experiment.event(caller, int(receipts[0]))]
    experiment.assert_that(accepted == snapshots, 'accepted/new binary exact history/search/READ snapshots on same schema22')
    (evidence / 'binary-differential.json').write_text(json.dumps({'accepted': accepted, 'current': snapshots}, ensure_ascii=False, indent=2))
    experiment.stop(experiment.control); experiment.jar = current
    experiment.control = experiment.start('query-current-control', 'control')
    for subject in ('b', 'c'):
        experiment.search(caller, {'query': 'nebula'}, 404, subject=subject)
    # One partial-body request exercises the same absolute deadline across both async phases.
    connection = http.client.HTTPConnection('127.0.0.1', experiment.control['port'], timeout=5)
    started = time.monotonic()
    try:
        connection.putrequest('POST', '/api/tasks/'+caller+'/session-history/session-search')
        for key, value in {'Authorization': 'Bearer '+experiment.token('a'), 'Content-Type':'application/json', 'Content-Length':'100'}.items(): connection.putheader(key,value)
        connection.endheaders(); connection.send(b'{')
        response = connection.getresponse(); result = json.loads(response.read())
        elapsed = time.monotonic() - started
        experiment.assert_that(response.status == 504 and result['code'] == 'SESSION_QUERY_TIMEOUT' and elapsed < 2.5, 'authorization and async body retain original absolute two seconds', elapsed=elapsed, status=response.status, value=result)
    finally: connection.close()
    task = experiment.prior_task('source controlled query deadline')
    worker = experiment.start('query-lock-worker', 'worker', boundary='M0'); experiment.barrier(worker)
    classes = experiment.root / 'query-test-classes'
    shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
    consumption = "SELECT tools_used,models_used,next_event_seq FROM task WHERE id='"+task+"'; SELECT COUNT(*) FROM session_public_event WHERE task_id='"+task+"';"
    before = experiment.sql(consumption)
    for name, binary in [('accepted', old_jar), ('current', current)]:
        result = subprocess.run([experiment.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionContextLockScenario', '-Dloader.path='+str(classes), '-cp', str(binary), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(worker['folder'] / 'runtime.properties'), task, 'fixed'], text=True, capture_output=True, timeout=25)
        (evidence / ('context-lock-'+name+'.log')).write_text(result.stdout+result.stderr)
        experiment.assert_that(result.returncode == 0, 'real bounded budget lock '+name, exit=result.returncode)
    experiment.assert_that(before == experiment.sql(consumption), 'timeout and retry consume no model/tool/event')
    (worker['folder'] / 'release').touch(); experiment.state(task, 'SUCCEEDED'); experiment.quiet(task); experiment.stop(worker)
    experiment.assert_that(experiment.http('DELETE','/api/tasks/'+caller+'/session-search-scope')[0] == 204, 'explicit scope revocation commits')
    experiment.search(caller, {'query':'nebula'}, 404)
    # Change the actual installation pin so access records authority loss after rollback.
    other = experiment.prior_task('rollback scope query', [source])
    experiment.sql("UPDATE installation SET epoch=epoch+1 WHERE tenant_id='tenant-a' AND plugin_id='session_event_read';")
    experiment.search(other, {'query':'nebula'}, 404)
    status, safe = experiment.http('GET','/api/tasks/'+other)
    experiment.assert_that(status == 200 and safe['code'] == 'SESSION_SCOPE_UNAVAILABLE' and safe['summary'] is None and safe['artifacts'] == [], 'source authority loss keeps metadata and blocks task body')
    experiment.assert_that(experiment.sql("SELECT invalidated_at IS NOT NULL FROM task_session_scope WHERE caller_task_id='"+other+"';") == '1', 'authority loss recorded after rollback with explicit new transaction')
    (evidence / 'query-result.json').write_text(json.dumps({'source':source,'caller':caller,'bounded':task,'scopeInvalidation':other,'jarSha256':hashlib.sha256(current.read_bytes()).hexdigest()},indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar',type=Path,required=True); parser.add_argument('--old-jar',type=Path,required=True); parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args(); experiment=PriorExperiment(args.jar,args.evidence)
    try: verify(experiment,args.old_jar.resolve())
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc()); raise
    finally: experiment.cleanup()
