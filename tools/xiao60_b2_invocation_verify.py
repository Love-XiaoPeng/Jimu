"""Only changed Invocation paths: exact prior/current binary, private MySQL and transaction logs."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import traceback
from p2_verify import Experiment


def verify(e, old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() ==
        '1b1d551cd83e572728c76b6a392efc89357d95bfddb9420d7da840493176f817', 'exact 7d72157 old JAR')
    e.stop(e.control)
    (e.evidence/'database.txt').write_text(e.sql('SELECT VERSION(),@@server_uuid,DATABASE(); SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;'))
    scenarios = {
        'workflow': ('CoreWorkflowScenario','admit-binding,admit-pin,admit-pending,admit-replay,success-duplicate,success-race'),
        'settlement': ('CoreSettlementScenario','tool-failed,tool-unknown,claim-recovery')}
    for name,(driver,modes) in scenarios.items():
        results={}
        for tag,binary in [('old',old),('new',e.jar)]:
            e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
            try:
                result=subprocess.run([e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.'+driver,
                    '-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),
                    'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),tag,modes],
                    text=True,capture_output=True,timeout=180)
                (e.evidence/(name+'-'+tag+'.log')).write_text(result.stdout+result.stderr)
                e.assert_that(result.returncode==0, 'actual Invocation '+name+' '+tag, exit=result.returncode)
                results[tag]=[line for line in result.stdout.splitlines() if line.startswith('RESULT ')]
            finally:
                e.sql("SET GLOBAL general_log='OFF';")
                (e.evidence/(name+'-'+tag+'-transactions.tsv')).write_text(e.sql(
                    "SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
                e.sql('TRUNCATE TABLE mysql.general_log;')
        e.assert_that(len(results['new'])==len(modes.split(',')) and results['old']==results['new'],
            'exact prior/current '+name+' state/accounting/event/rollback/fence/replay/concurrency results')
        (e.evidence/(name+'-differential.json')).write_text(json.dumps(results,indent=2))
    e.assert_that(not e.fixture.models,'zero provider calls in direct differential')
    e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
    try:
        result=subprocess.run([e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.InvocationQueryScenario',
            '-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(e.jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties')],
            text=True,capture_output=True,timeout=90)
        (e.evidence/'query-context.log').write_text(result.stdout+result.stderr)
        e.assert_that(result.returncode==0 and 'PASS Invocation query context MySQL' in result.stdout,
            'new MP/XML explicit context, same DS/UoW, actual metering and absolute deadline',exit=result.returncode)
    finally:
        e.sql("SET GLOBAL general_log='OFF';")
        (e.evidence/'query-transactions.tsv').write_text(e.sql(
            "SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
        e.sql('TRUNCATE TABLE mysql.general_log;')


def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('Invocation supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();e=Experiment(a.jar,a.evidence)
    try: verify(e,a.old_jar)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally: e.cleanup()


if __name__=='__main__': main()
