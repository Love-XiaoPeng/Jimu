"""B2 original pending slots, exact old/new JARs and local synthetic provider only."""
import argparse,hashlib,io,json,signal,subprocess,traceback
from pathlib import Path
from prior_session_verify import PriorExperiment,Model

class Missing(Model):
    def do_POST(self):
        raw=self.rfile.read(int(self.headers['Content-Length']));body=json.loads(raw)
        goal=next(m['content'] for m in body['messages'] if m['role']=='user')
        if not goal.startswith('caller missing'):
            self.rfile=io.BytesIO(raw);return super().do_POST()
        context=json.loads(body['messages'][-1]['content'])
        replies=[json.loads(m['content']) for m in body['messages'] if m['role']=='tool']
        message={'role':'assistant','content':'Known missing result test.'}
        if not replies:
            message['tool_calls']=[{'id':'missing_'+str(i),'type':'function','function':{'name':name,'arguments':json.dumps(arguments)}} for i,(name,arguments) in enumerate([('session_search',{'query':'nebula'}),('session_event_read',{'seq':127})])]
        with self.server.lock:
            self.server.models[goal]=self.server.models.get(goal,0)+1
            self.server.requests.append({'goal':goal,'body':body,'context':context,'replies':replies})
        self.send(200,{'id':'scoped-query-fixture','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if not replies else 'stop'}],'usage':{'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}})

def admit(e,goal,sources=()):
    key=hashlib.sha256(goal.encode()).hexdigest()
    body={'goal':goal,'history_session_ids':list(sources)}
    for attempt in range(3):
        status,value=e.http('POST','/api/session-search/tasks',body,key=key,subject='a')
        if status!=504 or value.get('code')!='SESSION_QUERY_TIMEOUT':break
        # Same original request/key only. A timeout may have committed; never assume rollback.
        rows=e.sql("SELECT id,state,request_digest FROM task WHERE tenant_id='tenant-a' AND owner_actor_id='alice' AND goal='"+goal+"';")
        e.assert_that(len(rows.splitlines())<=1,'fixture timeout has at most one original admission',goal=goal,attempt=attempt,status=status,value=value,rows=rows)
        print('OBSERVED fixture admission timeout; retry exact request/key '+str(attempt),flush=True)
    e.assert_that(status==202,'admit '+goal,status=status,value=value)
    rows=e.sql("SELECT id FROM task WHERE tenant_id='tenant-a' AND owner_actor_id='alice' AND goal='"+goal+"';")
    e.assert_that(rows==value['taskId'],'same admission key resolves exactly one task',goal=goal)
    e.task_subjects[value['taskId']]='a'
    return value['taskId']

def verify(e,old):
    e.prepare();e.fixture.RequestHandlerClass=Missing
    e.assert_that(hashlib.sha256(old.read_bytes()).hexdigest()=='9a51a3751e6a9b0e3752d90ce9aa389f99c10ea6f42b2c881894db7f21b96b99','exact accepted33f31e8 JAR')
    source=admit(e,'nebula scoped query source')
    writer=e.start('source-worker','worker');e.state(source,'SUCCEEDED');e.quiet(source);e.stop(writer)
    results={};meters={}
    for tag,binary in [('old',old),('new',e.jar)]:
        results[tag]=[];meters[tag]={}
        for mode in ('multislot','missing'):
            caller=admit(e,'caller '+mode+' '+tag+' nebula',[source])
            worker=e.start(tag+'-'+mode,'worker',boundary='PRIOR_SESSION_COMMIT_BEFORE');e.barrier(worker)
            before=len(e.fixture.requests)
            command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ScopedQueryScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),caller,mode,tag]
            e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
            try:
                logfile=e.evidence/('driver-'+tag+'-'+mode+'.log')
                with logfile.open('w') as log:
                    run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
                output=logfile.read_text()
                e.assert_that(run.returncode==0,'actual original-slot driver '+tag+' '+mode,exit=run.returncode,tail=output[-3500:])
                e.assert_that(before==len(e.fixture.requests),'driver rollback replay and proof checks never call provider '+tag+' '+mode)
                lines=output.splitlines();outcomes=[s for s in lines if s.startswith('RESULT ')]
                e.assert_that(len(outcomes)==(5 if mode=='multislot' else 3),'actual slot outcome groups '+tag+' '+mode)
                results[tag]+=outcomes
                meters[tag][mode]={s.split(' ',2)[1]:json.loads(s.split(' ',2)[2]) for s in lines if s.startswith('QUERY ')}
            finally:
                e.sql("SET GLOBAL general_log='OFF';")
                (e.evidence/(tag+'-'+mode+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
                e.sql('TRUNCATE TABLE mysql.general_log;')
                e.stop(worker,kill=True)
            if mode=='multislot':
                e.expire(caller)
                recovered=e.start(tag+'-takeover','worker');e.state(caller,'SUCCEEDED');e.quiet(caller)
                e.assert_that(e.sql("SELECT tools_used FROM task WHERE tenant_id='tenant-a' AND id='"+caller+"';")=='4','supervised takeover preserves single consumption '+tag)
                e.scenario(recovered,caller,'account');e.stop(recovered)
                for seq in e.sql("SELECT seq FROM session_public_event WHERE tenant_id='tenant-a' AND task_id='"+caller+"' AND type='session.read' ORDER BY seq;").splitlines():
                    e.assert_that(e.event(caller,int(seq))['target']['type']=='session.read','native receipt readable after takeover '+tag)
            else:
                state=e.sql("SELECT CONCAT(i.state,':',q.outcome,':',COALESCE(q.output_bytes,'NULL'),':',COALESCE(q.read_refs_snapshot,'NULL')) FROM invocation i JOIN invocation_session_scope_query q ON q.tenant_id=i.tenant_id AND q.invocation_id=i.id WHERE i.tenant_id='tenant-a' AND i.task_id='"+caller+"' AND i.tool='session_event_read';")
                e.assert_that(state==('PENDING:PENDING:NULL:NULL' if tag=='old' else 'FAILED:FAILED:0:[]'),'actual baseline defect versus corrected known failure '+tag,state=state)
                e.http('POST','/api/tasks/'+caller+'/cancel')
    e.assert_that(len(results['old'])==len(results['new'])==8 and results['old'][:6]==results['new'][:6] and results['old'][7]==results['new'][7],'seven unchanged old/new original-slot outcome groups')
    e.assert_that(results['old'][6]=='RESULT slot-1 session_event_read BASELINE_NULL_UNBOX_ROLLBACK' and results['new'][6]=='RESULT slot-1 session_event_read FAILED rollback/final-deadline/lost-ack/replay/proof','baseline missing-read null-unboxing corrected with persisted refusal')
    (e.evidence/'differential.json').write_text(json.dumps({'outcomes':results,'meters':meters,'baselineDefect':'Old missing read null-unboxes scanRows before query CAS, rolls back and stays PENDING. New typed nullable scan fields permit original intended FAILED receipt and exactly one TOOL consumption before404; fault/commit/replay/native proof paths validated on new.','boundary':'Only affected native v4 slots, receipt proof, beforeCommit and lost-ack paths; core UoW gap/full lock/paid matrices remain OPEN'},indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();e=PriorExperiment(a.jar,a.evidence)
    try:verify(e,a.old_jar)
    except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
