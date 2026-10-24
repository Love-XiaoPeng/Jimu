"""Native v3 sources/slot/pin/direct endpoint proof only; private old/new JAR differential."""
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
        if len(replies)<2:
            message['tool_calls']=[{'id':'proof_read_'+str(len(replies)),'type':'function','function':{'name':'session_event_read','arguments':json.dumps({'seq':0} if not replies else {'seq':3,'before':1})}}]
        with self.server.lock:
            self.server.models[goal]=self.server.models.get(goal,0)+1
            self.server.requests.append({'goal':goal,'body':body,'replies':replies,'context':context})
        self.send(200,{'id':'local-event-proof','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if len(replies)<2 else 'stop'}],'usage':{'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}})

def verify(e,old,version):
    e.prepare();e.fixture.RequestHandlerClass=ReadOnce
    goal='caller event proof '+version+' Unicode 🧱'
    task=e.prior_task(goal) if version=='v4' else e.task(goal,[])
    worker=e.start('proof-worker','worker');e.state(task,'SUCCEEDED');e.stop(worker)
    # Isolate readonly proof facts from this private control's scheduled scope validation.
    e.stop(e.control)
    e.assert_that(all(child.poll() is not None for child in e.children),
                  'all private application processes collected before readonly proof drivers')
    e.sql("GRANT UPDATE ON jimu.invocation_session_read TO 'runtime'@'%'; GRANT UPDATE ON jimu.invocation TO 'runtime'@'%'; GRANT UPDATE ON jimu.step TO 'runtime'@'%'; GRANT UPDATE ON jimu.session_public_event TO 'runtime'@'%';")
    provider=len(e.fixture.requests);results={};meters={};windows={};window_bytes={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ReadRelationProofScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('proof-'+tag+'.log')
            with file.open('w') as log:
                run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text()
            e.assert_that(run.returncode==0,'actual shared event proof driver '+tag,exit=run.returncode,tail=output[-3000:])
            results[tag]=[s for s in output.splitlines() if s.startswith('RESULT ')]
            meters[tag]=[s for s in output.splitlines() if s.startswith('METER ')]
            raw=next(s[len('WINDOW '):] for s in output.splitlines() if s.startswith('WINDOW '))
            windows[tag]=json.loads(raw)
            window_bytes[tag]={'sha256':hashlib.sha256(raw.encode()).hexdigest(),'bytes':len(raw.encode())}
            e.assert_that(len(results[tag])>=18,'all source and rejection evidence '+tag)
            e.assert_that(len(e.fixture.requests)==provider,'readonly proof driver never calls provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'meters':meters,'windows':windows,'windowRawBytes':window_bytes,'boundary':'Only native v3 readSources append and verifyReadRelation on one real task with two completed reads. Exact stored event bytes/digests and proof outcomes agree; read-window JSON is compared structurally because its existing Map.of window field order varies between JVM processes. Raw output/hashes remain recorded. MP projection SQL/byte meters recorded separately; no universal metric equality. Shared step/scope ref helpers and other runtime/admission SQL remain OPEN, as do core UoW deadline gap, cold locks and full scope/maintenance/file permutations.'},indent=2))
    e.assert_that(results['old']==results['new'],'exact old/new proof outcomes and frozen event digests agree')
    e.assert_that(windows['old']==windows['new'],'old/new complete read-window JSON values agree')

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--only',choices=['all','v1','v2','v3','v4'],default='all');a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='19ec4d1e69f6e96c5882598c2a9d8c8e37500a9fe88dcc9b382e8fcb22e4fc3a'
    for name,klass in [('v3',TraceExperiment)]:
        if a.only not in ('all',name):continue
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
