"""Shared binding: exact 980bed3/current task/model decisions on one private MySQL, no model call."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import traceback

from p3_report_verify import ReportExperiment
from p3_model_verify import config


def verify(e, old_jar):
    e.prepare()
    e.assert_that(hashlib.sha256(old_jar.read_bytes()).hexdigest() ==
        '1d6d9b9cc1483d88d5916c4a88302940b5d5b12a284d27e7ff549cd2483d2b1b', 'exact 980bed3 baseline JAR')
    e.stop(e.control)
    e.sql("UPDATE tenant_budget SET cost_limit=2000000000,token_limit=1000000000,model_limit=1000,running_limit=1 WHERE tenant_id='tenant-a';")
    settings = config({'a': e.fixture}, paid=True)
    plan=json.loads((Path(__file__).resolve().parents[1]/'src/main/resources/evaluation/qwen-main-v1.json').read_text())
    model='qwen3.8-max-0902'
    rate=plan['candidates'][model]
    settings.update({'jimu.model.evaluation-plan-ref':plan['id'], 'jimu.model.evaluation-phase':'A',
        'jimu.model.evaluation-sample':'W1'})
    prefix = 'jimu.model.profiles.a.'
    for key, value in {'model':model,'revision':model,'max-output-tokens':4096,'capabilities.context-limit':1000000,
        'capabilities.evidence':'modelstudio-nonthinking-v1','capabilities.stream-text':True,
        'capabilities.stream-tools':True,'capabilities.usage-stream':True,
        'provider-options.enable_thinking':False,'provider-options.enable_search':False,
        'price.fixed-micros':0,'price.input-token-upper':1000000,
        'price.input-micros-per-million':rate['inputMicrosPerMillion'],
        'price.output-micros-per-million':rate['outputMicrosPerMillion']}.items():
        settings[prefix+key] = str(value).lower() if isinstance(value, bool) else str(value)
    e.control=e.start('binding-config-control','control',extra=settings)
    e.stop(e.control)
    (e.evidence/'database.txt').write_text(e.sql('SELECT VERSION(),@@server_uuid,DATABASE(); SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;'))
    results={}
    frozen={}
    for label, binary in [('accepted',old_jar),('current',e.jar)]:
        e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
        try:
            result=subprocess.run([e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.EvaluationBindingScenario',
                '-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),
                'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),label],
                text=True,capture_output=True,timeout=240)
            (e.evidence/(label+'.log')).write_text(result.stdout+result.stderr)
            e.assert_that(result.returncode==0 and 'PASS shared evaluation binding' in result.stdout,
                'actual submit/modelSnapshot binding driver '+label, exit=result.returncode)
            results[label]=[line for line in result.stdout.splitlines() if line.startswith('RESULT ')]
            frozen[label]=[line for line in result.stdout.splitlines() if line.startswith('FROZEN ')]
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(label+'-transactions.tsv')).write_text(e.sql(
                "SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(len(results['current'])==21 and results['accepted']==results['current'],
        '21 exact old/new binding decisions: plan/budgets/qualification/screening/UNKNOWN/corruption/drift/rollback/two-connection race')
    e.assert_that(len(frozen['current'])==2 and frozen['accepted']==frozen['current'],
        'exact old/new canonical plan and bound task snapshot/digest/limits bytes after Java value relocation')
    e.assert_that(not e.fixture.models, 'zero provider calls, no external billing')
    (e.evidence/'differential.json').write_text(json.dumps(results,indent=2))
    (e.evidence/'frozen-snapshots.json').write_text(json.dumps(frozen,indent=2))
    for table in ['evaluation_budget','task','reservation','tenant_budget']:
        fields='tenant_id,id,state,evaluation_phase,evaluation_sample,evaluation_model' if table=='task' else '*'
        (e.evidence/(table+'.tsv')).write_text(e.sql('SELECT '+fields+' FROM '+table+';'))


def main():
    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt('Binding supervisor terminated')))
    p=argparse.ArgumentParser()
    p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--old-jar',type=Path,required=True)
    p.add_argument('--evidence',type=Path,required=True)
    args=p.parse_args()
    e=ReportExperiment(args.jar,args.evidence,Path(__file__).resolve().parents[1]/'target/test-classes')
    try:
        verify(e,args.old_jar)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        e.cleanup()


if __name__=='__main__':
    main()
