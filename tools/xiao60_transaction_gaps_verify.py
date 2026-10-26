"""G01/G02/G03 only: existing private session fixture, no worker/provider execution."""
import argparse
import hashlib
import json
import shutil
import subprocess
import traceback
from pathlib import Path
from prior_session_verify import PriorExperiment


def verify(e, classes, reuse_g01):
    e.prepare()
    e.stop(e.control)
    before = (dict(e.fixture.models), dict(e.fixture.tools))
    command = [e.java, '-Xmx256m', '-XX:TieredStopAtLevel=1',
               '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TransactionGapsScenario',
               '-Dloader.path='+str(classes), '-cp', str(e.jar),
               'org.springframework.boot.loader.launch.PropertiesLauncher',
               str(e.control['folder']/'runtime.properties')]
    if reuse_g01:
        prior = json.loads((reuse_g01/'execution.json').read_text())
        assert prior['jarSHA256'] == hashlib.sha256(e.jar.read_bytes()).hexdigest()
        shutil.copytree(reuse_g01, e.evidence/'reused-g01', dirs_exist_ok=False)
        command.append('scoped-only')
    with (e.evidence/'scenario.log').open('w') as log:
        child = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    (e.evidence/'execution.json').write_text(json.dumps({'exit': child.returncode,
        'jarSHA256': hashlib.sha256(e.jar.read_bytes()).hexdigest(), 'jarBytes': e.jar.stat().st_size}))
    assert child.returncode == 0, 'original scenario.log records first error'
    lines = (e.evidence/'scenario.log').read_text().splitlines()
    proofs = {name: json.loads(next(v[len(name)+1:] for v in lines if v.startswith(name+' ')))
              for name in ['G02', 'G03FAILED', 'G03NOTE']}
    g01_lines = (reuse_g01/'scenario.log').read_text().splitlines() if reuse_g01 else lines
    proofs['G01'] = json.loads(next(v[4:] for v in g01_lines if v.startswith('G01 ')))
    assert proofs['G01']['before'] == proofs['G01']['after']
    assert 'worker_process' in proofs['G01']['before']
    note = proofs['G03NOTE']['trace']
    summary = {}
    for phase in ['G03-note', 'G03-note-repeat']:
        ops = [v for v in note if v['phase'] == phase]
        rollbacks = [v for v in ops if v.get('operation') == 'rollback']
        writes = [v for v in ops if 'invalidated_at=COALESCE' in v.get('sql', '')]
        commits = [v for v in ops if v.get('operation') == 'commit']
        assert len(rollbacks) == len(writes) == len(commits) == 1
        assert rollbacks[0]['connection'] != commits[0]['connection'] == writes[0]['connection']
        assert ops.index(rollbacks[0]) < ops.index(writes[0]) < ops.index(commits[0])
        assert any('afterCompletion' in frame for frame in writes[0]['stack'])
        summary[phase] = {'rollbackConnection': rollbacks[0]['connection'],
                          'noteConnection': writes[0]['connection'], 'oneNoteAfterRollback': True}
    assert before == (e.fixture.models, e.fixture.tools) == ({}, {})
    (e.evidence/'proofs.json').write_text(json.dumps(proofs, ensure_ascii=False, indent=2))
    (e.evidence/'summary.json').write_text(json.dumps({'G01': {'allTablesRestored': len(proofs['G01']['before']), 'origin': reuse_g01.name if reuse_g01 else 'current round'},
        'G02': {'originalSuccessfulReadAndReplay': True, 'resources': proofs['G02']['resources']},
        'G03': summary, 'noOuterFixtureTransaction': True, 'noProviderOrToolExecution': True,
        'boundary': '404 FAILED tool commit and later 403 authority-loss rollback/afterCompletion are distinct original paths. Durable private fixture rows removed with isolated container; G01 alone rolls back all mutation tables.'}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ['jar', 'evidence']:
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--reuse-g01', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    archive = args.evidence/'executed'
    classes = archive/'classes'
    classes.mkdir(parents=True, exist_ok=False)
    for file in (repo/'target/test-classes').rglob('TransactionGapsScenario*.class'):
        dest = classes/file.relative_to(repo/'target/test-classes')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    assert list(classes.rglob('*.class'))
    shutil.copyfile(repo/'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/TransactionGapsScenario.java', archive/'TransactionGapsScenario.java')
    shutil.copyfile(__file__, archive/Path(__file__).name)
    e = PriorExperiment(args.jar, args.evidence)
    try:
        verify(e, classes, args.reuse_g01)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
