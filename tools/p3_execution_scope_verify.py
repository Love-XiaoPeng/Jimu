"""Immutable operator scope on owned MySQL/real worker/local TLS+model only."""
import concurrent.futures
import hashlib
import http.server
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading

sys.dont_write_bytecode = True
from p2_verify import eventually, run
from p3_model_verify import config
from p3_evaluation_plan_verify import MaterialExperiment, Model, GOALS, canonical

PLUS = 'qwen3.7-plus-2026-05-26'
RESOURCES = Path(__file__).resolve().parents[1]/'src/main/resources/evaluation'
PLAN = json.loads((RESOURCES/'qwen-main-v1.json').read_text())


class HeldModel(Model):
    def do_POST(self):
        if self.server.hold:
            self.server.received.set()
            if not self.server.release.wait(60): raise AssertionError('Model release timeout')
        super().do_POST()


def verify(e):
    server = http.server.ThreadingHTTPServer(('127.0.0.1',0),HeldModel)
    server.daemon_threads=True
    server.origin='http://127.0.0.1:'+str(server.server_port)
    server.calls,server.lock,server.evidence,server.case=[],threading.Lock(),e.evidence,'unset'
    server.hold=False;server.received=threading.Event();server.release=threading.Event()
    listener=threading.Thread(target=server.serve_forever);listener.start()
    serial=0; facts=[]
    def unique(prefix):
        nonlocal serial
        serial+=1;return prefix+'-'+str(serial)
    def sqljson(value):return 'CONVERT(0x'+canonical(value).encode().hex()+' USING utf8mb4)'
    def state(task):return e.sql("SELECT state,code FROM task WHERE id='"+task+"';")
    def totals():
        return observation('totals',e.sql("SELECT (SELECT COUNT(*) FROM task),(SELECT COUNT(*) FROM reservation),(SELECT COUNT(*) FROM model_dispatch),(SELECT COUNT(*) FROM invocation),(SELECT COUNT(*) FROM artifact),SUM(cost_held),SUM(requests_used) FROM evaluation_budget;"))
    def observation(label,raw):
        with (e.evidence/'ledger-observations.jsonl').open('a') as log:
            log.write(json.dumps({'label':label,'case':server.case,'raw':raw,'sha256':hashlib.sha256(raw.encode()).hexdigest(),'modelHttpCount':len(server.calls),'tlsCount':len(e.tls.requests)},ensure_ascii=False)+'\n')
        return raw
    def accounting(run_id):
        return observation(run_id,e.sql("SELECT requests_used,cost_held,max_requests,max_cost_micros,COALESCE(phase_b_digest,'LOCKED') FROM evaluation_budget WHERE id='"+run_id+"';"))
    def private(cfg):
        path=e.root/(unique('scope-config')+'.properties')
        path.write_text('\n'.join(k+'='+str(v) for k,v in dict(e.common,**cfg).items())+'\n');path.chmod(0o600)
        return path
    def command(action,cfg,binding,*extra):
        return [e.java,'-Dloader.path='+str(e.classes),'-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ExecutionScopeTestMain',
            '-cp',str(e.jar),'org.springframework.boot.loader.launch.PropertiesLauncher',action,str(private(cfg)),str(binding),*map(str,extra)]
    def helper(action,cfg,binding,*extra):
        try:out=run(command(action,cfg,binding,*extra))
        except subprocess.CalledProcessError as failure:
            (e.evidence/(unique(action)+'-failed.txt')).write_text(failure.stdout+'\n'+failure.stderr)
            raise
        (e.evidence/(unique(action)+'.txt')).write_text(out)
        return out.splitlines()[-1]
    def configure(run_id,sample='W1'):
        cfg=config({'a':server},paid=True,run_id=run_id)
        cfg.update({'jimu.model.evaluation-plan-ref':'qwen-main-v1','jimu.model.evaluation-phase':'A' if sample in ['W1','W2'] else 'B','jimu.model.evaluation-sample':sample})
        p='jimu.model.profiles.a.'
        values={'model':PLUS,'revision':PLUS,'max-output-tokens':4096,'capabilities.context-limit':1000000,
          'capabilities.evidence':'modelstudio-nonthinking-v1','capabilities.stream-text':True,'capabilities.stream-tools':True,'capabilities.usage-stream':True,
          'provider-options.enable_thinking':False,'provider-options.enable_search':False,'price.fixed-micros':0,'price.input-token-upper':1000000,
          'price.input-micros-per-million':PLAN['candidates'][PLUS]['inputMicrosPerMillion'],'price.output-micros-per-million':PLAN['candidates'][PLUS]['outputMicrosPerMillion']}
        cfg.update({p+k:str(v).lower() if isinstance(v,bool) else str(v) for k,v in values.items()})
        for k,v in list(cfg.items()):
            if k.startswith(p): cfg[k.replace(p,'jimu.model.profiles.sse.',1)]=v
        cfg['jimu.model.profiles.sse.profile']='sse';cfg['jimu.model.profiles.sse.stream']='true'
        cfg['jimu.model.default-profile-ref']='sse' if sample in ['W2','W4'] else 'a'
        return cfg
    def control(cfg):
        e.stop(e.control);e.control=e.start(unique('scope-control'),'control',extra=cfg)
    def worker(cfg,point=''):return e.start(unique('scope-worker'),'worker',point,extra=cfg)
    def accept(cfg,sample='W1'):
        control(cfg);server.case=unique('scope-case')
        key=server.case
        status,view=e.http('POST','/api/tasks',{'goal':GOALS[sample],'capabilityIds':['web_fetch','read','write','present']},key=key)
        e.assert_that(status==202,'frozen scoped task admitted',response=view if status!=202 else view['taskId'])
        return view['taskId'],key
    def provision(sample='W1',bind=True):
        run_id=unique('scope-run');plan=canonical(PLAN);digest=hashlib.sha256(plan.encode()).hexdigest()
        e.sql("INSERT INTO evaluation_budget(tenant_id,id,policy_version,max_requests,max_cost_micros,plan_snapshot,plan_digest) VALUES('tenant-a','"+run_id+"',2,63,500000000,"+sqljson(PLAN)+",'"+digest+"');")
        approval={'activeCandidateOnly':PLUS,'fixtureOnly':True};approval_digest=hashlib.sha256(canonical(approval).encode()).hexdigest()
        instance=e.sql('SELECT @@server_uuid;')
        req={'version':1,'issueId':'XIAO-40-local-fixture','instanceUuid':instance,'schema':'jimu','tenantId':'tenant-a','runId':run_id,'approvalDigest':approval_digest}
        e.sql("DELETE FROM jimu_provisioning_receipt; INSERT INTO jimu_provisioning_receipt(record_version,issue_id,instance_uuid,tenant_id,run_id,approval_snapshot,approval_digest,plan_digest,materials_digest) VALUES(1,'XIAO-40-local-fixture','"+instance+"','tenant-a','"+run_id+"',"+sqljson(approval)+",'"+approval_digest+"','"+digest+"','"+PLAN['materialsDigest']+"');")
        cfg=configure(run_id,'W1');cfg['jimu.execution-scope.requirement']=canonical(req)
        binding=e.root/(run_id+'.json');helper('generate',cfg,binding)
        # Exact canonical input is nonsecret and retained, unlike runtime properties.
        (e.evidence/(run_id+'-binding.json')).write_bytes(binding.read_bytes())
        if bind: e.assert_that('"result":"bound"' in helper('bind',cfg,binding),'explicit isolated operator binding')
        cfg['jimu.model.default-profile-ref']='sse' if sample=='W2' else 'a';cfg['jimu.model.evaluation-sample']=sample
        return run_id,cfg,binding
    def snapshot_scope(run_id):return observation('scope:'+run_id,e.sql("SELECT scope_digest,HEX(scope_snapshot),bound_at_utc,bound_by FROM evaluation_execution_scope WHERE run_id='"+run_id+"';"))
    def reject_submit(cfg,binding,label,sample='W1'):
        before=totals();calls=len(server.calls);goal=e.root/'goal.txt';goal.write_text(GOALS[sample])
        result=helper('submit',cfg,binding,goal,unique('refused'))
        e.assert_that(result.startswith('REFUSED:') and before==totals() and calls==len(server.calls),'scope refusal before every new reservation/HTTP: '+label,result=result)
    def damage(run_id,kind,task):
        if kind=='missing':e.sql("DELETE FROM evaluation_execution_scope WHERE run_id='"+run_id+"';")
        elif kind=='digest':e.sql("UPDATE task SET execution_scope_digest=REPEAT('f',64) WHERE id='"+task+"';")
        else:
            row=json.loads(bytes.fromhex(e.sql("SELECT HEX(scope_snapshot) FROM evaluation_execution_scope WHERE run_id='"+run_id+"';")))
            row['version']=2 if kind=='version' else row['version']
            if kind!='version':row['materialsDigest']='e'*64
            text=canonical(row)
            e.sql("UPDATE evaluation_execution_scope SET scope_snapshot="+sqljson(row)+",scope_digest='"+hashlib.sha256(text.encode()).hexdigest()+"' WHERE run_id='"+run_id+"';")
    try:
        legacy=e.submit('scope-forward-migration-legacy')
        e.stop(e.control)
        e.sql("DROP TABLE evaluation_execution_scope; ALTER TABLE task DROP COLUMN execution_scope_digest; DELETE FROM flyway_schema_history WHERE version='9';")
        migration_before=e.sql('SELECT tenant_id,id,state,model_digest,tools_digest,models_used,token_held,cost_held FROM task; SELECT * FROM evaluation_budget; SELECT * FROM reservation;')
        (e.evidence/'migration-v8-before.tsv').write_text(migration_before)
        e.control=e.start(unique('scope-v9-migration'),'control')
        migration_after=e.sql('SELECT tenant_id,id,state,model_digest,tools_digest,models_used,token_held,cost_held FROM task; SELECT * FROM evaluation_budget; SELECT * FROM reservation;')
        (e.evidence/'migration-v9-after.tsv').write_text(migration_after)
        e.assert_that(migration_before==migration_after and e.sql('SELECT COUNT(*) FROM task WHERE execution_scope_digest IS NOT NULL;')=='0','V8 to V9 retains legacy snapshots/receipts and never backfills')
        e.http('POST','/api/tasks/'+legacy+'/cancel',{})
        e.sql("CREATE TABLE jimu_provisioning_receipt(record_version INT NOT NULL,issue_id VARCHAR(64),instance_uuid VARCHAR(64),tenant_id VARCHAR(64),run_id VARCHAR(64),approval_snapshot LONGTEXT,approval_digest CHAR(64),plan_digest CHAR(64),materials_digest CHAR(64)); UPDATE tenant_budget SET model_limit=10000,token_limit=1000000000,cost_limit=10000000000;")
        e.assert_that(e.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;")=='9' and e.sql('SELECT COUNT(*) FROM evaluation_execution_scope;')=='0','V9 schema only creates no scope or run')
        if not any(flag in sys.argv for flag in ['--settlements-only','--unknown-claim-only']):
            run_id,cfg,binding=provision(bind=False)
            helper('permissions',cfg,binding)
            from evaluation.application_grants import APPLICATION_TABLES, grants
            generated=grants('jimu_xiao40','jimu_app','192.0.2.1')
            e.assert_that('evaluation_execution_scope' not in APPLICATION_TABLES and len(generated)==14 and generated[0]=="GRANT SELECT ON `jimu\\_xiao40`.* TO 'jimu_app'@'192.0.2.1';" and all('.*' not in line for line in generated[1:]),'explicit application DML list excludes authority and escapes database pattern')
            (e.evidence/'offline-application-grants.sql').write_text('\n'.join(generated))
            try:grants('jimu_xiao40','jimu_app','%')
            except ValueError:e.assert_that(True,'grant generator rejects wildcard source host')
            else:raise AssertionError('Wildcard grant source accepted')
            reject_submit(cfg,binding,'unbound deployment')
            e.assert_that(e.sql("SELECT state FROM evaluation_budget WHERE id='"+run_id+"';")=='OPEN','unbound deployment does not stop empty original run')
            original_account=accounting(run_id)
            # Kill the actual binder on both sides of its connection commit, never a simulated exception.
            for point in ['before','after']:
                marker=e.root/unique('binder-marker')
                log=e.root/(unique('binder')+'.log')
                with log.open('w') as out:p=subprocess.Popen(command('bind-crash-'+point,cfg,binding,marker),stdout=out,stderr=subprocess.STDOUT)
                e.children.append(p)
                eventually(lambda:marker.exists() or p.poll() is not None,'binder commit window')
                e.assert_that(marker.exists(),'actual binder reached '+point+' commit')
                p.kill();p.wait(10)
                count=e.sql("SELECT COUNT(*) FROM evaluation_execution_scope WHERE run_id='"+run_id+"';")
                e.assert_that(count==('0' if point=='before' else '1') and accounting(run_id)==original_account,'binder exit atomicity '+point)
            receipt=snapshot_scope(run_id)
            e.sql('RENAME TABLE evaluation_execution_scope TO fixture_hidden_scope;')
            try:reject_submit(cfg,binding,'unreadable scope table')
            finally:e.sql('RENAME TABLE fixture_hidden_scope TO evaluation_execution_scope;')
            e.assert_that(e.sql("SELECT state FROM evaluation_budget WHERE id='"+run_id+"';")=='OPEN','unreadable scope is unavailable without invented STOPPED proof')
            with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda _:helper('bind',cfg,binding),range(2)))
            e.assert_that(all('"result":"bound"' in r for r in results) and snapshot_scope(run_id)==receipt,'identical concurrent retries retain first receipt')
            original=json.loads(binding.read_text())
            variants=[('scopeId','changed'),('instanceUuid','wrong'),('schema','wrong'),('tenantId','wrong'),('runId','wrong'),('approvalDigest','a'*64),('planDigest','b'*64),('materialsDigest','c'*64),('version',2),('accountEvidenceDigest',None)]
            for field,value in variants:
                bad=json.loads(canonical(original));bad['scope'][field]=value;path=e.root/(unique('bad-binding')+'.json');path.write_text(canonical(bad))
                result=helper('bind',cfg,path)
                e.assert_that('"result":"refused"' in result and snapshot_scope(run_id)==receipt and accounting(run_id)==original_account,'immutable binder rejects '+field)
            bad=json.loads(canonical(original));bad['scope']['profilePins']['a']['modelSnapshotDigest']='0'*64
            path=e.root/'bad-pin.json';path.write_text(canonical(bad));e.assert_that('"result":"refused"' in helper('bind',cfg,path),'placeholder profile digest refused')
            for sample in ['W1','W2']:
                base=dict(cfg,**{'jimu.model.default-profile-ref':'a' if sample=='W1' else 'sse','jimu.model.evaluation-sample':sample})
                prefix='jimu.model.profiles.'+('a' if sample=='W1' else 'sse')+'.'
                bad=dict(base);bad[prefix+'base-url']='http://[::1]:'+str(server.server_port)+'/compatible-mode/v1';bad['jimu.model.allowed-origins[0]']='http://[::1]:'+str(server.server_port)
                reject_submit(bad,binding,sample+' changed API host',sample)
                for field,value in [('model','qwen3.7-flash-2026-07-15'),('model','qwen3.8-max-0902'),('revision','alias'),('base-url',server.origin+'/other'),('price.version','changed'),('credential-version','1'),('stream',str(sample=='W1').lower()),('provider-options.enable_thinking','true')]:
                    bad=dict(base);bad[prefix+field]=value
                    if field=='model':
                        bad[prefix+'revision']=value
                        bad[prefix+'price.input-micros-per-million']=str(PLAN['candidates'][value]['inputMicrosPerMillion'])
                        bad[prefix+'price.output-micros-per-million']=str(PLAN['candidates'][value]['outputMicrosPerMillion'])
                    if field=='credential-version':
                        bad[prefix+'auth-mode']='bearer';bad[prefix+'credential-ref']='env:UNAVAILABLE_SCOPE_FIXTURE_V1'
                    reject_submit(bad,binding,sample+' '+field+'='+value,sample)
                for field,value in [('jimu.model.evaluation-plan-ref',None),('jimu.model.evaluation-run-id','other'),('jimu.model.evaluation-run-id',None),('jimu.execution-scope.requirement',None),('jimu.model.default-profile-ref','absent'),(prefix+'paid','false')]:
                    bad=dict(base)
                    if value is None:bad.pop(field,None)
                    else:bad[field]=value
                    reject_submit(bad,binding,sample+' bypass '+field,sample)
            before=totals();e.sql('RENAME TABLE jimu_provisioning_receipt TO fixture_hidden_receipt;')
            try:reject_submit(cfg,binding,'missing trusted receipt')
            finally:e.sql('RENAME TABLE fixture_hidden_receipt TO jimu_provisioning_receipt;')
            e.assert_that(before==totals(),'missing receipt never synthesizes authority')
            bad=dict(cfg,**{'jimu.model.evaluation-phase':'B','jimu.model.evaluation-sample':'W3'})
            reject_submit(bad,binding,'B stays locked','W3')
            race_run,race_cfg,race_binding=provision(bind=False)
            conflict=json.loads(race_binding.read_text());conflict['scope']['scopeId']='other-valid-scope'
            conflict_file=e.root/'conflicting-binder.json';conflict_file.write_text(canonical(conflict))
            with concurrent.futures.ThreadPoolExecutor(2) as pool:
                results=list(pool.map(lambda file:helper('bind',race_cfg,file),[race_binding,conflict_file]))
            e.assert_that(sum('"result":"bound"' in r for r in results)==1 and sum('EVALUATION_SCOPE_BINDING_CONFLICT' in r for r in results)==1 and e.sql("SELECT COUNT(*) FROM evaluation_execution_scope WHERE run_id='"+race_run+"';")=='1','competing different binders serialize to one immutable winner')
            if '--admission-only' in sys.argv:return
            # All 3 recovery classes in ordinary and SSE, with actual process exit and two workers.
            published=[]
            for sample in ['W1','W2']:
                for point in ['M0','MODEL_RESPONSE_COMMITTED','T1']:
                    run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                    w=worker(cfg,point);e.barrier(w)
                    before=e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run_id+"';")
                    e.stop(w,kill=True);e.expire(task);control(cfg)
                    w=worker(cfg);w2=worker(cfg);view=e.state(task,'SUCCEEDED');e.stop(w);e.stop(w2)
                    calls=int(e.sql("SELECT models_used FROM task WHERE id='"+task+"';"))
                    e.assert_that(calls==(7 if sample=='W1' else 6) and len(view['artifacts'])==1,'valid exact-scope recovery without duplicate charge',sample=sample,point=point,prior=before)
                    e.assert_that(e.sql("SELECT COUNT(*) FROM task t JOIN evaluation_execution_scope s ON s.run_id=t.evaluation_run_id AND s.tenant_id=t.tenant_id WHERE t.id='"+task+"' AND t.execution_scope_digest=s.scope_digest;")=='1','task immutable scope pointer matches original binding')
                    before_retry=accounting(run_id);receipt=snapshot_scope(run_id);helper('bind',cfg,binding)
                    e.assert_that(before_retry==accounting(run_id) and receipt==snapshot_scope(run_id),'binding retry after legitimate consumption retains original counters and receipt')
                    published.append((task,view['artifacts'][0]['artifactId']))
                    facts.append({'run':run_id,'task':task,'sample':sample,'recovery':point,'models':calls})
            # Historical NULL never backfills; view/idempotency/cancel remain available.
            run_id,cfg,binding=provision();task,key=accept(cfg);e.sql("UPDATE task SET execution_scope_digest=NULL,model_snapshot=NULL,model_digest=NULL WHERE id='"+task+"';")
            old=totals();status,view=e.http('POST','/api/tasks',{'goal':GOALS['W1'],'capabilityIds':['web_fetch','read','write','present']},key=key)
            e.assert_that(status==202 and view['taskId']==task and old==totals(),'legacy idempotent submit only returns old view')
            w=worker(cfg);e.state(task,'FAILED');e.stop(w)
            e.assert_that(e.sql("SELECT COALESCE(execution_scope_digest,'NULL'),code FROM task WHERE id='"+task+"';")=='NULL\tEVALUATION_SCOPE_LEGACY_UNBOUND','legacy task permanently refuses without backfill')
            e.assert_that(e.sql("SELECT state FROM evaluation_budget WHERE id='"+run_id+"';")=='OPEN','legacy missing old model snapshot is not a claimed scope integrity error')
            e.assert_that(e.http('POST','/api/tasks/'+task+'/cancel')[0]==200,'legacy cancel remains callable')
            for sample in ['W1','W2']:
                for point in ['M0','T1']:
                    run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                    w=worker(cfg,point);e.barrier(w);e.stop(w,kill=True);damage(run_id,'missing',task)
                    before=totals();calls=len(server.calls);tls=len(e.tls.requests)
                    e.expire(task);control(cfg);w=worker(cfg);w2=worker(cfg);e.state(task,'FAILED');e.stop(w);e.stop(w2)
                    e.assert_that(before==totals() and calls==len(server.calls) and tls==len(e.tls.requests),'missing scope refuses PREPARED/confirmed-tool cached recovery',sample=sample,point=point)
            # Cached checkpoints, all public guard paths, reports and true post-settlement crash window.
            for sample in ['W1','W2']:
                for kind in ['missing','digest','version','material']:
                    run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                    w=worker(cfg,'MODEL_RESPONSE_COMMITTED');e.barrier(w)
                    damage(run_id,kind,task);before=totals();baseline=len(server.calls)
                    helper('guard-paths',cfg,binding,task)
                    e.assert_that(before==totals() and baseline==len(server.calls),'15 admission APIs reject cached scope corruption before new side effects',sample=sample,kind=kind)
                    e.stop(w,kill=True);e.expire(task);control(cfg);w=worker(cfg);w2=worker(cfg);e.state(task,'FAILED');e.stop(w);e.stop(w2)
                    e.assert_that(e.sql("SELECT state FROM evaluation_budget WHERE id='"+run_id+"';")=='STOPPED' and len(server.calls)==baseline,'scope integrity stop survives restart and competing workers',sample=sample,kind=kind)
                run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                server.hold=True;server.received.clear();server.release.clear()
                w=worker(cfg,'SCOPE_REJECTION_COMMITTED')
                eventually(server.received.is_set,'model admitted before scope corruption')
                damage(run_id,'digest',task);server.release.set();e.barrier(w);server.hold=False
                baseline=totals();calls=len(server.calls);balance=accounting(run_id)
                e.assert_that(e.sql("SELECT state FROM step WHERE task_id='"+task+"';")=='REJECTED' and e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"';")=='0','refused scope records actual response but creates no tool intents',sample=sample)
                e.stop(w,kill=True);e.expire(task);control(cfg);w=worker(cfg);w2=worker(cfg);e.state(task,'FAILED');e.stop(w);e.stop(w2)
                e.assert_that(len(server.calls)==calls and balance==accounting(run_id) and baseline==totals(),'exact rejection commit window keeps settlement and STOPPED after double-worker restart',sample=sample)
            for sample in ['W1','W2']:
                for point in ['P1','P2']:
                    run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                    w=worker(cfg,point);e.barrier(w)
                    published_before=e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='"+task+"' AND state='PUBLISHED';")
                    damage(run_id,'digest',task);before=totals();calls=len(server.calls)
                    helper('guard-paths',cfg,binding,task)
                    (w['folder']/'release').touch()
                    eventually(lambda:state(task).split('\t')[0] in ['FAILED','NEEDS_RECONCILIATION'],'refused present/finish collected')
                    e.stop(w)
                    e.assert_that(e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='"+task+"' AND state='PUBLISHED';")==published_before and before==totals() and len(server.calls)==calls,'real present scope guard serializes pre/post publication',sample=sample,point=point,published=published_before)
                    if published_before=='1':
                        aid=e.sql("SELECT id FROM artifact WHERE task_id='"+task+"' AND state='PUBLISHED';")
                        e.assert_that(e.http('GET','/api/artifacts/'+aid+'/download')[0]==200,'already committed present survives later scope rejection')
        if '--settlements-only' not in sys.argv:
            for sample in ['W1','W2']:
                for proof in ['corrupt','legacy']:
                    run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                    w=worker(cfg,'T0');e.barrier(w);e.stop(w,kill=True)
                    e.sql("UPDATE task SET execution_scope_digest="+("REPEAT('f',64)" if proof=='corrupt' else 'NULL')+" WHERE id='"+task+"';")
                    before=accounting(run_id);calls=len(server.calls);tls=len(e.tls.requests)
                    e.expire(task);control(cfg);w=worker(cfg);w2=worker(cfg);e.state(task,'NEEDS_RECONCILIATION');e.stop(w);e.stop(w2)
                    e.assert_that(accounting(run_id)==before and len(server.calls)==calls and len(e.tls.requests)==tls and e.sql("SELECT state FROM invocation WHERE task_id='"+task+"';")=='OUTCOME_UNKNOWN','unknown old tool retains original accounting without new effects',sample=sample,proof=proof)
                    e.assert_that(e.sql("SELECT state FROM evaluation_budget WHERE id='"+run_id+"';")==('STOPPED' if proof=='corrupt' else 'OPEN'),'claim checks claimed integrity before unknown recovery; legacy NULL remains reconciliation only',sample=sample,proof=proof)
        if '--unknown-claim-only' in sys.argv:return
        for sample in ['W1','W2']:
            for result in ['success','failure','unknown']:
                run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                w=worker(cfg,'T1_BEFORE');e.barrier(w)
                e.sql("UPDATE task SET execution_scope_digest=NULL WHERE id='"+task+"';")
                before=totals();calls=len(server.calls)
                helper('settle-tool-'+result,cfg,binding,task,'{}')
                invocation=e.sql("SELECT state FROM invocation WHERE task_id='"+task+"';")
                e.assert_that(invocation=={'success':'SUCCEEDED','failure':'FAILED','unknown':'OUTCOME_UNKNOWN'}[result] and before==totals() and len(server.calls)==calls,'previously admitted tool settles without active scope/profile',sample=sample,result=result)
                e.stop(w,kill=True)
                e.sql("UPDATE task SET state='NEEDS_RECONCILIATION' WHERE id='"+task+"'; UPDATE tenant_budget SET running=0 WHERE tenant_id='tenant-a'; UPDATE reservation SET state='RELEASED' WHERE task_id='"+task+"' AND category='RUNNING';")
        # Settlement is explicitly host accounting injection, not provider usage-parsing evidence.
        for sample in ['W1','W2']:
            for kind in ['known','unknown','expired','corrupt']:
                run_id,cfg,binding=provision(sample);task,key=accept(cfg,sample)
                w=worker(cfg,'M1');e.barrier(w);e.sql("UPDATE task SET execution_scope_digest=NULL WHERE id='"+task+"';")
                before=accounting(run_id);calls=len(server.calls)
                if kind=='expired':e.sql("UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+task+"';")
                if kind=='corrupt':e.sql("UPDATE task SET model_digest=REPEAT('e',64) WHERE id='"+task+"';")
                helper('settle-unknown' if kind=='unknown' else 'settle-known',cfg,binding,task)
                e.stop(w,kill=True)
                row=e.sql("SELECT state,cost_quality,COALESCE(actual_cost,-1) FROM model_dispatch WHERE task_id='"+task+"';")
                if kind=='known':e.assert_that(row=='RESPONDED\tKNOWN\t6098304','historical known settlement without registry/scope',sample=sample,row=row)
                elif kind=='unknown':e.assert_that(row.startswith('USAGE_UNKNOWN\tUNKNOWN\t-1') and before==accounting(run_id),'unknown historical fee retains entire original hold',sample=sample)
                elif kind=='expired':e.assert_that(row.startswith('DISPATCHED') and before==accounting(run_id),'expired settlement fence refuses without billing mutation',sample=sample)
                else:e.assert_that(row.startswith('DISPATCHED') and before==accounting(run_id) and e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='"+task+"' AND category IN ('MODEL','COST') AND state='HELD_UNKNOWN';")!='0','corrupt historical pricing refuses and retains UNKNOWN',sample=sample)
                e.assert_that(len(server.calls)==calls and e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"';")=='0','historical settlement never resends or mints tool intents')
                # Fixture teardown of admitted-but-unconfirmed tasks preserves costs while preventing later scenario claims.
                e.sql("UPDATE task SET state='NEEDS_RECONCILIATION' WHERE id='"+task+"'; UPDATE evaluation_budget SET state='STOPPED' WHERE id='"+run_id+"'; UPDATE tenant_budget SET running=0 WHERE tenant_id='tenant-a'; UPDATE reservation SET state='RELEASED' WHERE task_id='"+task+"' AND category='RUNNING';")
        if '--settlements-only' in sys.argv:return
        # Existing published artifacts do not acquire a new scope or registry dependency.
        task,artifact=published[0]
        e.sql("UPDATE task SET execution_scope_digest=NULL WHERE id='"+task+"'; UPDATE evaluation_budget SET state='STOPPED' WHERE id=(SELECT evaluation_run_id FROM task WHERE id='"+task+"');")
        control({})
        e.assert_that(e.http('GET','/api/artifacts/'+artifact+'/download')[0]==200,'old published download independent of scope and model registry')
        e.assert_that(e.http('GET','/api/artifacts/'+artifact+'/download',subject='b')[0]==404 and e.http('GET','/api/artifacts/'+artifact+'/download',subject='c')[0]==404,'original tenant/owner artifact isolation retained')
        e.sql("UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';")
        e.assert_that(e.http('GET','/api/artifacts/'+artifact+'/download')[0]==403,'download revocation retained')
        e.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'; UPDATE artifact SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='"+artifact+"';")
        e.assert_that(e.http('GET','/api/artifacts/'+artifact+'/download')[0]==410,'download expiration retained')
    finally:
        server.release.set();server.shutdown();server.server_close();listener.join(5)
        e.assert_that(not listener.is_alive(),'scope substitute model listener collected')
        (e.evidence/'scope-http.json').write_text(json.dumps(server.calls,indent=2,ensure_ascii=False))
        (e.evidence/'scope-facts.json').write_text(json.dumps(facts,indent=2))
        if e.schema_ready:
            for table in ['evaluation_budget','evaluation_execution_scope','model_dispatch']:
                # HEX for raw JSON evidence avoids terminal/TSV Unicode ambiguities.
                (e.evidence/('scope-'+table+'.tsv')).write_text(e.sql('SELECT * FROM '+table+';'))
            (e.evidence/'scope-canonical.hex.tsv').write_text(e.sql('SELECT run_id,scope_digest,HEX(scope_snapshot) FROM evaluation_execution_scope;'))


if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('Scope fixture terminated')))
    e=MaterialExperiment(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))
    try:e.prepare();verify(e)
    finally:e.cleanup()
