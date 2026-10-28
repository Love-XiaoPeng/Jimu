"""One A03, then small-body nine-node chain/branch and fresh-JVM original-slot replay."""
import argparse
import json
from pathlib import Path
import signal
import shutil
import subprocess
import traceback

from session_lineage_verify import LineageExperiment


def verify(e):
    e.prepare()
    e.core(a03_only=True)
    chain = []
    for ordinal in range(8):
        chain.append(e.task('budget-chain-' + str(ordinal), chain, chain[-1] if chain else None, plan=[]))
    plan = [[('session_trace', {'session_id': chain[0]}),
             ('session_trace', {'session_id': chain[3]}), ('session_trace', {})]]
    end = e.task('budget-depth-eight', chain, chain[-1], plan=plan)
    outputs = e.outputs(end)
    e.assert_that(len(outputs) == 3 and len(outputs[2]['ancestors']) == 8
        and len(outputs[0]['descendants']) == 8
        and [node['depth'] for node in outputs[0]['descendants']] == list(range(1, 9)),
        'nine nodes depth eight three traces in one response', task=end, sources=chain)
    branch = e.task('budget-branch-nine', chain, chain[0], plan=plan)
    branch_outputs = e.outputs(branch)
    e.assert_that([node['node']['session_id'] for node in branch_outputs[0]['descendants']] == chain[1:] + [branch]
        and len(branch_outputs[2]['ancestors']) == 1, 'nine-node stable branch DFS', task=branch)
    for task in (end, branch):
        e.assert_that(e.sql("SELECT COUNT(DISTINCT step_id),COUNT(DISTINCT context_digest),COUNT(*) FROM invocation_session_lineage_trace WHERE caller_task_id='" + task + "';") == '1\t1\t3',
            'same response and frozen cut for nine-node three trace slots', task=task)
    # Default remains64MiB; only the private fixture quota is raised for the72MiB scope.
    e.sql("UPDATE tenant_budget SET history_storage_limit=67108864 WHERE tenant_id='tenant-a';")
    e.task('budget-default-quota-refusal', chain, chain[-1], plan=plan, expected=429)
    e.sql("UPDATE tenant_budget SET history_storage_limit=536870912 WHERE tenant_id='tenant-a';")
    e.stop(e.worker)
    config = e.control['folder'] / 'runtime.properties'
    e.stop(e.control)
    classes = e.root / 'test-classes'
    shutil.copytree(Path(__file__).resolve().parents[1] / 'target/test-classes', classes)
    command = [e.java, '-Xmx384m',
        '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionLineageRecoveryScenario',
        '-Dloader.path=' + str(classes), '-cp', str(e.jar),
        'org.springframework.boot.loader.launch.PropertiesLauncher', str(config), end]
    (e.evidence / 'recovery-command.json').write_text(json.dumps(command, indent=2))
    with (e.evidence / 'recovery.log').open('w') as log:
        run = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=40)
    output = (e.evidence / 'recovery.log').read_text()
    e.assert_that(run.returncode == 0 and 'RECOVERY ' in output, 'fresh JVM new real fence returns original bytes once',
        exit=run.returncode, tail=output[-6000:])
    (e.evidence / 'budget-tasks.json').write_text(json.dumps({'chain': chain, 'end': end, 'branch': branch}, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = p.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    e = LineageExperiment(args.jar, args.evidence)
    try:
        verify(e)
    except BaseException:
        (e.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()
