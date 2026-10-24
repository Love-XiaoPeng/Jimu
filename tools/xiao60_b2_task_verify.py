"""Task intake differential on the exact prior/current JAR, private MySQL and actual transaction log."""
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
    # Match the existing application_grants.py contract for this private question-task fixture.
    # Experiment's general core bootstrap grants only APPLICATION_TABLES, which omits this table.
    e.sql("GRANT INSERT,UPDATE ON jimu.user_question TO 'runtime'@'%';")
    (e.evidence/'fixture-grants.txt').write_text(e.sql("SHOW GRANTS FOR 'runtime'@'%';"))
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest() ==
        '377083663030f452d849e0d8c18f8b9a68dcf796ab4afa1a48743c5acf1b5bc1', 'exact dbcf58d old JAR')
    e.stop(e.control)
    (e.evidence/'database.txt').write_text(e.sql('SELECT VERSION(),@@server_uuid,DATABASE(); SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;'))
    results={}
    frozen={}
    for tag,binary in [('old',old),('new',e.jar)]:
        e.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';")
        try:
            result=subprocess.run([e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.TaskSubmitScenario',
                '-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),
                'org.springframework.boot.loader.launch.PropertiesLauncher',str(e.control['folder']/'runtime.properties'),tag],
                text=True,capture_output=True,timeout=180)
            (e.evidence/(tag+'.log')).write_text(result.stdout+result.stderr)
            e.assert_that(result.returncode==0 and 'PASS task submit' in result.stdout, 'actual task intake '+tag, exit=result.returncode)
            results[tag]=[line for line in result.stdout.splitlines() if line.startswith('RESULT ')]
            frozen[tag]=[line for line in result.stdout.splitlines() if line.startswith('FROZEN ')]
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-transactions.tsv')).write_text(e.sql(
                "SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(len(results['new'])==16 and results['old']==results['new'], '16 exact prior/current admission/retry/refusal/rollback/deadline/race outcomes')
    e.assert_that(frozen['old']==frozen['new'], 'exact frozen request/tools/model bytes, limits and DB deadline interval')
    e.assert_that(not e.fixture.models,'zero provider calls in direct intake differential')
    (e.evidence/'differential.json').write_text(json.dumps(results,indent=2))
    (e.evidence/'frozen.json').write_text(json.dumps(frozen,indent=2))


def main():
    signal.signal(signal.SIGTERM,lambda s,f: (_ for _ in ()).throw(KeyboardInterrupt('Task supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();e=Experiment(a.jar,a.evidence)
    try: verify(e,a.old_jar)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally: e.cleanup()


if __name__=='__main__': main()
