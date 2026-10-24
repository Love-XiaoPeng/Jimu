"""Four binding branches, actual worker + same-fact old/new M2 admission rollback trials."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Handler, Experiment, eventually
from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment
from prior_session_verify import PriorExperiment

def slots(context):
    version=context['version']
    calls=[]
    if version==3:calls.append(('session_event_trace',{'seq':0}))
    if version>=2:calls.append(('session_search' if version==4 else 'session_event_search',{'query':'public 🧱'}))
    calls.append(('session_event_read',{'seq':0}))
    if version==4:calls.append(('session_event_read',{'session_id':context['prior_sessions'][0]['session_id'],'seq':0}))
    return [{'id':'binding_'+str(i),'type':'function','name':name,'arguments':json.dumps(arguments,ensure_ascii=False)} for i,(name,arguments) in enumerate(calls)]

class BindingModel(Handler):
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal=next(m['content'] for m in body['messages'] if m['role']=='user')
        context=json.loads(body['messages'][-1]['content'])
        replies=[json.loads(m['content']) for m in body['messages'] if m['role']=='tool']
        calls=[] if goal.startswith('source binding') or replies else slots(context)
        message={'role':'assistant','content':'Verified public binding proof.'}
        if calls:message['tool_calls']=[{'id':c['id'],'type':c['type'],'function':{'name':c['name'],'arguments':c['arguments']}} for c in calls]
        response={'version':2,'content':message['content'],'calls':calls,'tokens':150,'usage':{'inputTokens':100,'outputTokens':50,'totalTokens':150,'cachedTokens':None,'reasoningTokens':None,'providerId':'local-binding','returnedModel':None,'finishReason':'tool_calls'}}
        with self.server.lock:
            self.server.models[goal]=self.server.models.get(goal,0)+1
            self.server.requests.append({'goal':goal,'body':body,'replies':replies,'context':context,'response':response})
        self.send(200,{'id':'local-binding','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if calls else 'stop'}],'usage':{'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}})

def observed_state(e,task,expected):
    # Observe existing worker recovery for up to120s; never retry admission/provider or change
    # product task/lease/query deadlines. Original shared fixture's50s cut could stop mid-recovery.
    def matches():
        status,view=e.http('GET','/api/tasks/'+task)
        assert status==200,(status,view)
        if view['state']==expected:return view
        if view['state'] in ('SUCCEEDED','FAILED','CANCELLED','NEEDS_RECONCILIATION'):
            raise AssertionError('Unexpected terminal '+str(view))
        return False
    return eventually(matches,task+' -> '+expected,timeout=120)

def observed_calls(e,task):
    # This slice owns the three committed tool slots, not the following model dispatch.
    # Keep terminal failures fatal until all three original scope slots/receipts are committed.
    def matches():
        status,view=e.http('GET','/api/tasks/'+task)
        assert status==200,(status,view)
        count=int(e.sql("SELECT COUNT(*) FROM invocation i JOIN invocation_session_scope_query b ON b.tenant_id=i.tenant_id AND b.invocation_id=i.id AND b.caller_task_id=i.task_id AND b.step_id=i.step_id WHERE i.tenant_id='tenant-a' AND i.task_id='"+task+"' AND i.state='SUCCEEDED' AND b.outcome='SUCCEEDED' AND b.receipt_event_seq IS NOT NULL;"))
        if count==3:
            (e.evidence/'worker-call-milestone.json').write_text(json.dumps({'task':view,'committedScopeSlots':count,'boundary':'Affected three-slot commit milestone only; does not assert whole-task success or next model dispatch.'},indent=2))
            e.assert_that(True,'three actual scope slots and receipts committed',state=view['state'],slots=count)
            return view
        if view['state'] in ('SUCCEEDED','FAILED','CANCELLED','NEEDS_RECONCILIATION'):
            raise AssertionError('Unexpected terminal before three scope commits '+str(view))
        return False
    return eventually(matches,task+' -> three scope commits',timeout=120)

def verify(e,old,version):
    e.prepare();e.fixture.RequestHandlerClass=BindingModel
    source=None
    if version=='v4':
        source=e.prior_task('source binding public 🧱')
        worker=e.start('source-worker','worker');observed_state(e,source,'SUCCEEDED');e.stop(worker)
    def task(goal):return e.prior_task(goal,sources=[source]) if source else e.task(goal,[])
    completed=task('caller binding completed '+version+' 🧱')
    worker=e.start('binding-worker','worker');observed_calls(e,completed);e.stop(worker)
    pending=task('caller binding pending '+version+' 🧱')
    paused=e.start('binding-paused','worker','M1_RESPONSE');e.barrier(paused)
    # Stop private control first, then collect this exact paused child without allowing its
    # InterruptedException handler to persist EXECUTION_FAILED or maintenance to recover it.
    for child in reversed(e.children):
        if child is not paused['process']:e.stop({'process':child,'name':'private binding foreground collection'})
    Experiment.stop(e,paused,kill=True)
    request=next(r for r in e.fixture.requests if r['goal']=='caller binding pending '+version+' 🧱')
    response=e.evidence/'pending-response.json';response.write_text(json.dumps(request['response'],ensure_ascii=False))
    provider=len(e.fixture.requests);results={};raws={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ScopeExecutionScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(paused['folder']/'runtime.properties'),completed,pending,str(response),str(e.evidence/('trace-'+tag))]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('binding-'+tag+'.log')
            with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text()
            e.assert_that(run.returncode==0,'actual ModelDispatch binding driver '+tag,exit=run.returncode,tail=output[-5000:])
            results[tag]=[(line.split(' ',2)[1],json.loads(line.split(' ',2)[2])) for line in output.splitlines() if line.startswith('RESULT ')]
            raws[tag]=[line for line in output.splitlines() if line.startswith('NORMALIZED ')]
            e.assert_that(any(key=='failure' for key,_ in results[tag]),'real SQL failure rollback evidence '+tag)
            e.assert_that(any(key=='success' for key,_ in results[tag]),'real success outer rollback evidence '+tag)
            e.assert_that(len(e.fixture.requests)==provider,'exact-JAR trials never replay provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'normalizedRaw':raws,'boundary':'One affected scope-v4 group inside one JUnit IT. Actual new worker plus old/new exact-JAR model binding, pending scope search/self/cross read and exact-output replay. Each successful admission trial executes the original three scope slots, replays each without charge and rolls the entire trial back. Each driver does two fixture renewals inside rolled-back transactions, no fixture commits/provider replay. Injected binding SQL failure rolls back prior response/slots/token settlement and any earlier binding. Success trial is outer rollback, not an extra committed response. Frozen event bytes checked. Scope original Map.of normalized byte ordering logged; JSON values compared. Core final-beforeCommit gap, cold/full locks and historical limits remain OPEN.'},ensure_ascii=False,indent=2))
    e.assert_that(results['old']==results['new'],'exact old/new binding/receipt proofs and rollback results agree')

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--only',choices=['all','v1','v2','v3','v4'],default='v4');a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='9f84771e14de2e04935a6dde96dbcd6f9546ecd640e8be1b08100c4b39ca1587'
    for name,klass in [('v1',SessionExperiment),('v2',SearchExperiment),('v3',TraceExperiment),('v4',PriorExperiment)]:
        if a.only not in ('all',name):continue
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
