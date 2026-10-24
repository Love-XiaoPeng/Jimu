"""Affected validateInput/response/M2 callers in native1/2/3 and scope4, exact old/new JARs."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Handler
from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment
from prior_session_verify import PriorExperiment

class ReadOnce(Handler):
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal=next(m['content'] for m in body['messages'] if m['role']=='user')
        context=json.loads(body['messages'][-1]['content'])
        replies=[json.loads(m['content']) for m in body['messages'] if m['role']=='tool']
        message={'role':'assistant','content':'Verified public receipt proof.'}
        if not replies:
            message['tool_calls']=[{'id':'proof_read','type':'function','function':{'name':'session_event_read','arguments':json.dumps({'seq':0})}}]
        with self.server.lock:
            self.server.models[goal]=self.server.models.get(goal,0)+1
            self.server.requests.append({'goal':goal,'body':body,'replies':replies,'context':context})
        self.send(200,{'id':'local-event-proof','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if not replies else 'stop'}],'usage':{'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}})

def verify(e,old,version):
    e.prepare();e.fixture.RequestHandlerClass=ReadOnce
    goal='caller event proof '+version+' Unicode 🧱'
    worker=None
    if version=='v4':
        source=e.prior_task('source input eligibility Unicode 🧱')
        worker=e.start('proof-worker','worker');e.state(source,'SUCCEEDED')
        task=e.prior_task(goal,sources=[source])
    else:task=e.task(goal,[])
    if worker is None:worker=e.start('proof-worker','worker')
    e.state(task,'SUCCEEDED');e.stop(worker)
    for child in reversed(e.children):e.stop({'process':child,'name':'private proof foreground collection'})
    provider=len(e.fixture.requests);results={};meters={};windows={};window_bytes={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.InputEligibilityScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('proof-'+tag+'.log')
            with file.open('w') as log:
                run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text()
            e.assert_that(run.returncode==0,'actual shared event proof driver '+tag,exit=run.returncode,tail=output[-3000:])
            results[tag]=[s for s in output.splitlines() if s.startswith('RESULT ')]
            meters[tag]=[s for s in output.splitlines() if s.startswith('METER ')]
            e.assert_that(len(results[tag])>=20,'input/refusal/response evidence '+tag)
            e.assert_that(len(e.fixture.requests)==provider,'readonly proof driver never calls provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'meters':meters,'boundary':'Only validateInput through real M2 worker and original response plus direct calls; source scope eligibility uses the original MP/parameterized Mapper chain. No stored-fact updates in the probe, no new query/transaction. Native v1/v2/v3 and scope4 are four groups inside one JUnit integration. Prior history/cold locks/core UoW gap remain OPEN.'},indent=2))
    e.assert_that(results['old']==results['new'],'exact old/new input and response outcomes agree')

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--only',choices=['all','v1','v2','v3','v4'],default='all');a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='0435cbdc7af951f845b5b1af7017dd3903b79e5da8c9e00ce40eb6da57789241'
    for name,klass in [('v1',SessionExperiment),('v2',SearchExperiment),('v3',TraceExperiment),('v4',PriorExperiment)]:
        if a.only not in ('all',name):continue
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
