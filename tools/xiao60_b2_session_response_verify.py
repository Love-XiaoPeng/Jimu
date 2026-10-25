"""Actual response preflight/settlement: native and scope, exact JAR outer rollback trials."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Experiment,Handler
from session_trace_verify import TraceExperiment
from prior_session_verify import PriorExperiment
from xiao60_b2_normalization_verify import slots,observed_state

class ResponseModel(Handler):
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal=next(m['content'] for m in body['messages'] if m['role']=='user')
        context=json.loads(body['messages'][-1]['content'])
        replies=[json.loads(m['content']) for m in body['messages'] if m['role']=='tool']
        calls=[] if goal.startswith('source binding') or replies else slots(context)
        if calls:calls=[calls[self.server.responseSlot]]
        message={'role':'assistant','content':'Verified public response proof.'}
        if calls:message['tool_calls']=[{'id':c['id'],'type':c['type'],'function':{'name':c['name'],'arguments':c['arguments']}} for c in calls]
        response={'version':2,'content':message['content'],'calls':calls,'tokens':150,'usage':{'inputTokens':100,'outputTokens':50,'totalTokens':150,'cachedTokens':None,'reasoningTokens':None,'providerId':'local-response','returnedModel':None,'finishReason':'tool_calls'}}
        with self.server.lock:
            self.server.models[goal]=self.server.models.get(goal,0)+1
            self.server.requests.append({'goal':goal,'body':body,'replies':replies,'context':context,'response':response})
        self.send(200,{'id':'local-response','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if calls else 'stop'}],'usage':{'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}})

def verify(e,old,version,response_slot):
    e.prepare();e.fixture.RequestHandlerClass=ResponseModel;e.fixture.responseSlot=response_slot
    source=None
    if version.startswith('v4'):
        source=e.prior_task('source binding response public 🧱')
        worker=e.start('response-source','worker');observed_state(e,source,'SUCCEEDED');e.stop(worker)
    goal='caller response pending '+version+' 🧱'
    pending=e.prior_task(goal,sources=[source]) if source else e.task(goal,[])
    paused=e.start('response-paused','worker','M1_RESPONSE');e.barrier(paused)
    for child in reversed(e.children):
        if child is not paused['process']:e.stop({'process':child,'name':'response foreground collection'})
    Experiment.stop(e,paused,kill=True)
    request=next(r for r in e.fixture.requests if r['goal']==goal)
    response=e.evidence/'pending-response.json';response.write_text(json.dumps(request['response'],ensure_ascii=False))
    provider=len(e.fixture.requests);results={};proofs={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionResponseScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(paused['folder']/'runtime.properties'),pending,str(response),str(e.evidence/('trace-'+tag))]
        file=e.evidence/('response-'+tag+'.log')
        with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
        output=file.read_text()
        e.assert_that(run.returncode==0,'actual response exact-JAR driver '+version+' '+tag,exit=run.returncode,tail=output[-6000:])
        results[tag]=[(s.split(' ',2)[1],json.loads(s.split(' ',2)[2])) for s in output.splitlines() if s.startswith('RESULT ')]
        e.assert_that(output.count('PASS outer rollback restores exact response/dispatch/slots/bindings/events/reservations/budgets/history/scope facts')==5,'five captured-tenant fact rollbacks '+tag)
        proofs[tag]=[json.loads(s[6:]) for s in output.splitlines() if s.startswith('PROOF ')]
        assert len(proofs[tag])==1
        for proof in proofs[tag]:
            assert hashlib.sha256(proof['raw'].encode()).hexdigest()==proof['sha256']
            assert len(proof['raw'].encode())==proof['bytes']
        assert len(e.fixture.requests)==provider,'trials cannot replay provider'
    e.assert_that(results['old']==results['new'],'ordered response values and original boundaries agree '+version)
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'eventProofs':proofs,'boundary':'Actual modelResponded -> ModelDispatchService -> MapperModelWorkflows -> session response preflight. Success, two known payload refusals with token/rejection-event writes and outside-inner-transaction error, store/binding SQL faults. Five trials per exact JAR, every trial outer rollback with all captured tenant16 facts and budgets compared; one fixture lease/deadline UPDATE per trial also rolled back. Six separate private DB groups each actual provider1call native trace/search/read or scope search/self/cross read response; not modified captured success responses. Separate preflight/account/normalized warmups use original2s queries and outer rollbacks. No trial commit/provider replay/UNKNOWN recovery/full-task or scope full successful read/independent afterCompletion acceptance. Original2s query deadline unchanged. Rounds2/3 old scope and round4 old native combined3call response deadline/rollback connection failures remain OPEN; isolated1call paths do not prove combined3call success or diagnose cause/close cold locks. Event raw hash/length verified independently; JSON value comparison preserves original Map.of cross-JVM order differences.'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='c1bf9870dff1b7f5a30e9f7d49c2e953da6d650f92e270fd9627ccbe3176522d'
    failures=[]
    for name,klass,response_slot in [('v3-trace',TraceExperiment,0),('v3-search',TraceExperiment,1),('v3-read',TraceExperiment,2),('v4-search',PriorExperiment,0),('v4-self',PriorExperiment,1),('v4-cross',PriorExperiment,2)]:
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name,response_slot)
        except Exception:
            (e.evidence/'failure.txt').write_text(traceback.format_exc());failures.append(name)
        finally:e.cleanup()
    assert not failures,'Independent failed response groups: '+str(failures)
