"""Operator-only binding and preserved-result assessment on exact old/new private artifacts."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import signal
import shutil
import subprocess
import traceback
from p2_verify import eventually
from p3_public_worker_verify import PublicExperiment
from p3_model_verify import config
from p3_web_result_verify import mutations

ROOT=Path(__file__).resolve().parents[1]
def canonical(v): return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def literal(v): return 'CONVERT(0x'+str(v).encode().hex()+' USING utf8mb4)'

def verify(e,old):
    e.prepare()
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest()=='0563df5941485e11cb28cdd7ef850887252b511cf51b1772074001a932ef59a4','exact ea57cd8 baseline JAR')
    prior=e.root/'prior.jar';shutil.copyfile(old,prior);old=prior
    current=e.jar; results={}; serial=0
    def call(binary,main,args,label,expected=0):
        result=subprocess.run([e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.'+main,'-Dloader.path='+str(e.classes),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,args)],text=True,capture_output=True,timeout=55)
        (e.evidence/(label+'.log')).write_text(result.stdout+result.stderr)
        e.assert_that(result.returncode==expected,label+' real exit',exit=result.returncode)
        if main=='WebFetchProofMain':
            json.loads(result.stdout.strip())  # Preserve the single JSON stdout contract.
        return result.stdout.splitlines()[-1]
    # Produce a real preserved public result, then exercise the unchanged UNKNOWN recovery.
    e.common['jimu.web.public-dispatch-enabled']='true';e.fixture.origin='https://fixture.example'
    e.stop(e.control);e.control=e.start('operator-public','control')
    task=e.submit('operator-preserved-proof');worker=e.start('operator-capture','worker','WEB_RESULT');e.barrier(worker)
    invocation=e.sql("SELECT id FROM invocation WHERE tenant_id='tenant-a' AND task_id='"+task+"';")
    e.stop(worker,kill=True);e.expire(task)
    a=e.start('operator-recover-a','worker');b=e.start('operator-recover-b','worker');e.state(task,'NEEDS_RECONCILIATION');e.stop(a);e.stop(b)
    e.assert_that(e.sql("SELECT state FROM invocation WHERE id='"+invocation+"';")=='OUTCOME_UNKNOWN','two actual workers retain UNKNOWN without replay')
    e.stop(e.control)
    properties=e.control['folder']/'runtime.properties'
    frozen=e.sql("SELECT state,models_used,tools_used,token_held,cost_held,next_event_seq FROM task WHERE id='"+task+"'; SELECT id,state,amount,actual FROM reservation ORDER BY id; SELECT COUNT(*) FROM event;")
    proof_calls=len(e.tls.requests); model_calls=dict(e.fixture.models)
    # Reuse mutation rules, but retain raw stdout and enforce the original single JSON response.
    import p3_web_result_verify
    def assess(obj,tid,iid,label,expected=None,tenant='tenant-a'):
        nonlocal serial
        serial+=1
        raw=call(e.jar,'WebFetchProofMain',[properties,tenant,tid,iid],f'assess-{tag}-{serial}-{label}',2 if expected else 0)
        value=json.loads(raw)
        e.assert_that(value.get('code')==expected if expected else value.get('readOnly') is True,label+' decision '+tag,result=value)
        values.append({'case':label,'result':value})
    p3_web_result_verify.assess=assess
    for tag,binary in [('old',old),('new',current)]:
        e.jar=binary;values=[]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            assess(e,task,invocation,'unknown-proof')
            e.sql("CREATE TABLE saved_web_proof AS SELECT * FROM event WHERE fact_id='web-result:"+invocation+"';")
            try:mutations(e,task,invocation)
            finally:e.sql('DROP TABLE saved_web_proof;')
            results[tag]=values
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-assessment-transactions.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
        e.assert_that(frozen==e.sql("SELECT state,models_used,tools_used,token_held,cost_held,next_event_seq FROM task WHERE id='"+task+"'; SELECT id,state,amount,actual FROM reservation ORDER BY id; SELECT COUNT(*) FROM event;"),'no assessment writes '+tag)
    e.jar=current
    e.assert_that(results['old']==results['new'],'exact old/new read-only proof decisions')
    (e.evidence/'assessment-differential.json').write_text(json.dumps(results,indent=2))
    # Synthetic paid run and receipt do not authorize provider access or actual paid execution.
    plan=json.loads((ROOT/'src/main/resources/evaluation/qwen-main-v1.json').read_text());model='qwen3.7-plus-2026-05-26'
    run='operator-fixture';digest=hashlib.sha256(canonical(plan).encode()).hexdigest()
    approval={'activeCandidateOnly':model,'fixtureOnly':True};approval_digest=hashlib.sha256(canonical(approval).encode()).hexdigest()
    instance=e.sql('SELECT @@server_uuid;')
    req={'version':1,'issueId':'XIAO-60-synthetic-operator','instanceUuid':instance,'schema':'jimu','tenantId':'tenant-a','runId':run,'approvalDigest':approval_digest}
    e.sql("INSERT INTO evaluation_budget(tenant_id,id,policy_version,max_requests,max_cost_micros,plan_snapshot,plan_digest) VALUES('tenant-a','"+run+"',2,63,500000000,"+literal(canonical(plan))+",'"+digest+"');CREATE TABLE jimu_provisioning_receipt(record_version INT,issue_id VARCHAR(64),instance_uuid VARCHAR(64),tenant_id VARCHAR(64),run_id VARCHAR(64),approval_snapshot LONGTEXT,approval_digest CHAR(64),plan_digest CHAR(64),materials_digest CHAR(64));")
    receipt="INSERT INTO jimu_provisioning_receipt VALUES(1,'XIAO-60-synthetic-operator','"+instance+"','tenant-a','"+run+"',"+literal(canonical(approval))+",'"+approval_digest+"','"+digest+"','"+plan['materialsDigest']+"');"
    e.sql(receipt)
    cfg=config({'a':e.fixture},paid=True,run_id=run)
    cfg.update({'jimu.execution-scope.requirement':canonical(req),'jimu.model.evaluation-plan-ref':plan['id'],'jimu.model.evaluation-phase':'A','jimu.model.evaluation-sample':'W1'})
    prefix='jimu.model.profiles.a.'
    # The public web fixture's logical URL must not become a model endpoint.
    cfg[prefix+'base-url']='http://127.0.0.1:'+str(e.fixture.server_port)+'/compatible-mode/v1'
    cfg['jimu.model.allowed-origins']='http://127.0.0.1:'+str(e.fixture.server_port)
    for k,v in {'model':model,'revision':model,'max-output-tokens':4096,'capabilities.context-limit':1000000,'capabilities.evidence':'modelstudio-nonthinking-v1','capabilities.stream-text':True,'capabilities.stream-tools':True,'capabilities.usage-stream':True,'provider-options.enable_thinking':False,'provider-options.enable_search':False,'price.fixed-micros':0,'price.input-token-upper':1000000,'price.input-micros-per-million':plan['candidates'][model]['inputMicrosPerMillion'],'price.output-micros-per-million':plan['candidates'][model]['outputMicrosPerMillion']}.items():
        cfg[prefix+k]=str(v).lower() if isinstance(v,bool) else str(v)
    for k,v in list(cfg.items()):
        if k.startswith(prefix):cfg[k.replace(prefix,'jimu.model.profiles.sse.',1)]=v
    cfg['jimu.model.profiles.sse.profile']='sse';cfg['jimu.model.profiles.sse.stream']='true'
    private=e.root/'operator.properties';private.write_text('\n'.join(k+'='+str(v) for k,v in dict(e.common,**cfg).items())+'\n');private.chmod(0o600)
    binding=e.root/'operator-binding.json'
    call(old,'ExecutionScopeTestMain',['generate',private,binding],'generate')
    original=json.loads(binding.read_text());(e.evidence/'synthetic-binding.json').write_text(canonical(original))
    # This operator run has no tasks. Existing unscoped task totals must still match tenant accounting.
    def account():return e.sql("SELECT * FROM evaluation_budget;SELECT * FROM tenant_budget;SELECT * FROM reservation;SELECT * FROM jimu_provisioning_receipt;")
    base_account=account();binding_results={}
    for tag,binary in [('old',old),('new',current)]:
        values=[]; e.sql("DELETE FROM evaluation_execution_scope WHERE tenant_id='tenant-a' AND run_id='"+run+"';")
        def bind(label,path=binding,mode='bind'):
            nonlocal serial
            serial+=1
            raw=call(binary,'ExecutionScopeTestMain',[mode,private,path],f'bind-{tag}-{serial}-{label}')
            value=json.loads(raw);values.append({'case':label,'result':value});return value
        def refused(label,code):
            v=bind(label);e.assert_that(v=={'result':'refused','code':code},tag+' '+label,result=v)
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            for kind in ['missing','ambiguous','wrong-instance','wrong-approval']:
                if kind=='missing':e.sql("UPDATE jimu_provisioning_receipt SET tenant_id='hidden';")
                elif kind=='ambiguous':e.sql(receipt)
                elif kind=='wrong-instance':e.sql("UPDATE jimu_provisioning_receipt SET instance_uuid='wrong';")
                else:e.sql("UPDATE jimu_provisioning_receipt SET approval_digest=REPEAT('f',64);")
                refused(kind,'EVALUATION_SCOPE_UNAVAILABLE' if kind in ['missing','ambiguous'] else 'EVALUATION_SCOPE_NOT_ALLOWED')
                e.sql('DELETE FROM jimu_provisioning_receipt;'+receipt)
            e.sql('RENAME TABLE jimu_provisioning_receipt TO hidden_receipt;')
            try:refused('missing-table','EVALUATION_SCOPE_UNAVAILABLE')
            finally:e.sql('RENAME TABLE hidden_receipt TO jimu_provisioning_receipt;')
            e.sql("UPDATE evaluation_budget SET requests_used=1 WHERE id='"+run+"';")
            refused('counter-mismatch','EVALUATION_SCOPE_NOT_ALLOWED')
            e.sql("UPDATE evaluation_budget SET requests_used=0,cost_held=1 WHERE id='"+run+"';")
            refused('cost-mismatch','EVALUATION_SCOPE_LEDGER_UNPROVEN')
            e.sql("UPDATE evaluation_budget SET cost_held=0 WHERE id='"+run+"';")
            # Actual process exit on both sides of physical commit, with bounded markers.
            for point in ['before','after']:
                marker=e.root/(tag+'-'+point);log=e.evidence/(tag+'-'+point+'-crash.log')
                command=[e.java,'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ExecutionScopeTestMain','-Dloader.path='+str(e.classes),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher','bind-crash-'+point,str(private),str(binding),str(marker)]
                with log.open('w') as output:
                    child=subprocess.Popen(command,stdout=output,stderr=subprocess.STDOUT);e.children.append(child)
                    try:
                        eventually(lambda:marker.exists() or child.poll() is not None,'physical commit marker '+tag+' '+point,35)
                        e.assert_that(marker.exists(),'actual commit barrier '+tag+' '+point)
                    finally:
                        if child.poll() is None:child.kill()
                        child.wait(timeout=10)
                count=e.sql("SELECT COUNT(*) FROM evaluation_execution_scope WHERE run_id='"+run+"';")
                e.assert_that(count==('0' if point=='before' else '1'),'physical commit atomicity '+tag+' '+point,count=count)
            e.sql("UPDATE evaluation_execution_scope SET bound_at_utc='2026-10-01 12:34:56.123456' WHERE tenant_id='tenant-a' AND run_id='"+run+"';")
            first=bind('retry-after-commit')
            changed=dict(original,boundBy='second-actor');path=e.root/'changed-actor.json';path.write_text(canonical(changed))
            retry=bind('retry-different-actor',path);e.assert_that(retry==first,'retains original binder/time '+tag)
            v=bind('local-evidence-not-cloud-authority',mode='bind-production')
            e.assert_that(v=={'result':'refused','code':'EVALUATION_SCOPE_NOT_ALLOWED'},'requirement-only operator cannot promote local evidence '+tag)
            changed=json.loads(canonical(original));changed['scope']['scopeId']='different';path=e.root/'conflict.json';path.write_text(canonical(changed))
            v=bind('conflict',path);e.assert_that(v=={'result':'refused','code':'EVALUATION_SCOPE_BINDING_CONFLICT'},'exact binding conflict '+tag)
            e.sql("UPDATE evaluation_budget SET state='STOPPED' WHERE id='"+run+"';");refused('stopped-retry','MODEL_BUDGET_EXCEEDED');e.sql("UPDATE evaluation_budget SET state='OPEN' WHERE id='"+run+"';")
            with concurrent.futures.ThreadPoolExecutor(2) as pool:
                race=list(pool.map(lambda i:json.loads(call(binary,'ExecutionScopeTestMain',['bind',private,binding],tag+'-race-'+str(i))),range(2)))
            e.assert_that(all(v==first for v in race),'two concurrent binders retain first row '+tag)
            call(binary,'OperatorBindingLockScenario',[private,binding],tag+'-actual-lock-wait')
            e.sql('ALTER TABLE jimu_provisioning_receipt DROP COLUMN record_version;')
            try:v=bind('optional-record-version');e.assert_that(v==first,'optional external record_version '+tag)
            finally:e.sql('ALTER TABLE jimu_provisioning_receipt ADD COLUMN record_version INT FIRST;UPDATE jimu_provisioning_receipt SET record_version=1;')
            e.assert_that(account()==base_account,'operator does not change receipt/budget/task ledger '+tag)
            binding_results[tag]=values
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-binding-transactions.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'root%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(binding_results['old']==binding_results['new'],'exact old/new binding refusals/readback/idempotency')
    (e.evidence/'binding-differential.json').write_text(json.dumps(binding_results,indent=2))
    call(current,'OperatorQueryScenario',[private,run,task,invocation],'query-readonly-transaction')
    e.assert_that(dict(e.fixture.models)==model_calls and len(e.tls.requests)==proof_calls,'all operator calls have zero provider/tool effects; synthetic paid-run only')
    (e.evidence/'database.txt').write_text(e.sql('SELECT VERSION(),@@server_uuid,DATABASE();SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;'))

def main():
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('Operator fixture terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();e=PublicExperiment(a.jar,a.evidence,ROOT/'target/test-classes')
    try:verify(e,a.old_jar)
    except BaseException:
        (e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()

if __name__=='__main__':main()
