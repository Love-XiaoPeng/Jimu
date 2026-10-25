"""Narrow response diagnostic. Never retries an UNKNOWN trial or changes product timeouts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import traceback

from p2_verify import Experiment
from p2_verify import run as shell_run
from p2_verify import eventually
from prior_session_verify import PriorExperiment
from session_trace_verify import TraceExperiment
from xiao60_b2_normalization_verify import observed_state
from xiao60_b2_session_response_verify import ResponseModel


def verify(e, old, name, classes, cold, agent, phase_agent, account_fault):
    e.prepare()
    e.fixture.RequestHandlerClass = ResponseModel
    # Trace keeps original trace-only input. Scope retains original search/self/cross triple.
    e.fixture.responseSlot = None if name == 'scope-three' else 0
    source = None
    if name == 'scope-three':
        source = e.prior_task('source binding response public 🧱')
        worker = e.start('response-source', 'worker')
        observed_state(e, source, 'SUCCEEDED')
        e.stop(worker)
    goal = 'caller response diagnostic ' + name + ' 🧱'
    pending = e.prior_task(goal, sources=[source]) if source else e.task(goal, [])
    if agent:
        capture = e.root / 'response-source-capture'
        capture.mkdir()
        assert 'JDK_JAVA_OPTIONS' not in os.environ, 'must preserve existing Java options'
        os.environ['JDK_JAVA_OPTIONS'] = '-javaagent:' + str(agent.resolve()) + '=' + str(capture)
        try:
            paused = e.start('response-paused', 'worker')
            eventually(lambda: (capture / 'converted-ready').exists(), 'original worker response conversion', timeout=120)
        finally:
            os.environ.pop('JDK_JAVA_OPTIONS')
        # Agent blocks after the original Payloads.json return, before modelResponded.
        # Retain both exact class byte arrays so this observation is reviewable.
        import shutil
        for file in capture.iterdir(): shutil.copyfile(file, e.evidence / file.name)
    else:
        paused = e.start('response-paused', 'worker', 'M1_RESPONSE')
        e.barrier(paused)
    for child in reversed(e.children):
        if child is not paused['process']:
            e.stop({'process': child, 'name': 'diagnostic foreground collection'})
    Experiment.stop(e, paused, kill=True)
    request = next(r for r in e.fixture.requests if r['goal'] == goal)
    response = e.evidence / 'pending-response.json'
    sample = json.dumps(request['response'], ensure_ascii=False)
    (e.evidence / 'fixture-sample-response.json').write_text(sample)
    raw = (e.evidence / 'worker-original-response.json').read_text() if agent else sample
    response.write_text(raw)
    (e.evidence / 'response-source-proof.json').write_text(json.dumps({
        'source': 'worker original Payloads.json return; test agent pauses before ledger call' if agent else 'fixture sample only',
        'workerOrSampleSHA256': hashlib.sha256(raw.encode()).hexdigest(), 'bytes': len(raw.encode()),
        'sampleSHA256': hashlib.sha256(sample.encode()).hexdigest(),
        'rawEqual': raw == sample, 'jsonValueEqual': json.loads(raw) == json.loads(sample),
        'boundary': 'Original worker bytes retained without reserialization. Driver passes pending-response original bytes unchanged to actual ledger. Sample retained separately; no raw equality assumed.'}, indent=2))
    (e.evidence / 'fixture-request.json').write_text(json.dumps(request, ensure_ascii=False, indent=2))
    # Both isolated databases are created BEFORE any diagnostic trial from this single frozen
    # snapshot. An UNKNOWN in old never causes re-entry/recovery of that database or slot.
    dump = shell_run(['docker', 'exec', e.cid, 'sh', '-c',
                      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --skip-comments -uroot jimu'])
    create = e.sql('SHOW CREATE DATABASE jimu;').split('\t', 1)[1]
    grants = e.sql("SHOW GRANTS FOR 'runtime'@'%';").splitlines()
    configs = {}
    for tag in ('old', 'new'):
        database = 'response_' + tag
        e.sql(create.replace('`jimu`', '`' + database + '`') + ';')
        e.sql('USE ' + database + ';\n' + dump)
        for grant in grants:
            if '`jimu`.' in grant:
                e.sql(grant.replace('`jimu`.', '`' + database + '`.') + ';')
        config = e.root / ('diagnostic-' + tag + '.properties')
        config.write_text((paused['folder'] / 'runtime.properties').read_text().replace('/jimu?', '/' + database + '?'))
        assert '/'+database+'?' in config.read_text(), 'driver must use its own frozen database copy'
        configs[tag] = config
    (e.evidence / 'frozen-database-source.json').write_text(json.dumps({
        'dumpSHA256': hashlib.sha256(dump.encode()).hexdigest(),
        'databasesCreatedBeforeTrials': ['response_old', 'response_new'],
        'boundary': 'Same frozen source facts, separate private copies and original table-level runtime grants. Never restore/reset/re-enter an UNKNOWN database.'}, indent=2))
    provider = len(e.fixture.requests)
    results = {}
    # Stop on UNKNOWN for this private DB; independent group uses a fresh DB.
    for tag, binary in [('old', old), ('new', e.jar)]:
        prefix = str(e.evidence / ('trace-' + tag))
        command = [e.java, '-Xmx256m', '-Ddiagnostic.prefix=' + prefix,
                   '-Ddiagnostic.cold=' + str(cold).lower(),
                   '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionResponseDiagnosticScenario',
                   '-Dloader.path=' + str(classes), '-cp', str(binary),
                   'org.springframework.boot.loader.launch.PropertiesLauncher',
                   str(configs[tag]), pending, str(response), prefix]
        if phase_agent:
            command.insert(2, '-javaagent:' + str(phase_agent.resolve()) + '=' + str(e.evidence / ('phases-' + tag)))
        command.insert(2, '-Ddiagnostic.accountFault=' + str(account_fault).lower())
        (e.evidence / ('command-' + tag + '.json')).write_text(json.dumps(command, indent=2))
        file = e.evidence / ('response-' + tag + '.log')
        with file.open('w') as log:
            run = subprocess.run(command, text=True, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        output = file.read_text()
        results[tag] = {'exit': run.returncode, 'jarSHA256': hashlib.sha256(binary.read_bytes()).hexdigest(),
                        'outcomes': [json.loads(s[11:]) for s in output.splitlines() if s.startswith('DIAGNOSTIC ')]}
        (e.evidence / 'diagnostic-summary.json').write_text(json.dumps(results, indent=2))
        assert len(e.fixture.requests) == provider, 'diagnostic must not replay provider'
        # Any uncertain transaction ends this instance. Never re-enter an original slot.
        # Continue only with the other database copy created before this trial.
    assert all(r['exit'] == 0 for r in results.values()), f'{name}: diagnostic failures preserved independently'
    return results


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--old-jar', type=Path, required=True)
    p.add_argument('--classes', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--cold', action='store_true')
    p.add_argument('--group', choices=['trace', 'scope-three', 'both'], default='both')
    p.add_argument('--source-agent', type=Path)
    p.add_argument('--phase-agent', type=Path)
    p.add_argument('--compiler-receipt', type=Path)
    p.add_argument('--account-fault', action='store_true')
    a = p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest() == 'c1bf9870dff1b7f5a30e9f7d49c2e953da6d650f92e270fd9627ccbe3176522d'
    assert hashlib.sha256(a.jar.read_bytes()).hexdigest() == 'dc1d44dbf9715b0b70fd3bf7293f08d4c83eb816ac2d299d2a60b5023f9f18f0'
    # Archive the exact execution before creating a database or entering any trial.
    import shutil
    archive = a.evidence / 'executed-driver'
    archive.mkdir(parents=True, exist_ok=False)
    shutil.copytree(a.classes, archive / 'classes')
    source_dir = Path(__file__).resolve().parents[1] / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence'
    for name in ('SessionResponseDiagnosticScenario.java', 'ResponseSourceAgent.java', 'ResponsePhaseAgent.java'):
        shutil.copyfile(source_dir / name, archive / name)
    shutil.copyfile(__file__, archive / Path(__file__).name)
    for file in (a.source_agent, a.phase_agent, a.compiler_receipt):
        if file:
            shutil.copyfile(file, archive / file.name)
    (archive / 'products.json').write_text(json.dumps([
        {'role': role, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'bytes': file.stat().st_size}
        for role, file in [('old', a.old_jar), ('new', a.jar)]], indent=2))
    (archive / 'index.json').write_text(json.dumps([
        {'path': str(file.relative_to(archive)), 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'bytes': file.stat().st_size}
        for file in sorted(archive.rglob('*')) if file.is_file()], indent=2))
    failures = []
    for name, klass in [('trace', TraceExperiment), ('scope-three', PriorExperiment)]:
        if a.group != 'both' and a.group != name:
            continue
        e = klass(a.jar, a.evidence / name)
        try:
            verify(e, a.old_jar, name, a.classes, a.cold, a.source_agent, a.phase_agent, a.account_fault)
        except BaseException:
            (e.evidence / 'failure.txt').write_text(traceback.format_exc())
            failures.append(name)
        finally:
            # Cleanup failure evidence is separate; never masks the primary experiment exception.
            try:
                e.cleanup()
            except BaseException:
                (e.evidence / 'cleanup-exception.txt').write_text(traceback.format_exc())
                failures.append(name + '-cleanup')
    assert not failures, 'Diagnostic failures retained: ' + str(failures)
