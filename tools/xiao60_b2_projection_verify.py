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
        if goal.startswith('source projection'):
            pass
        elif not replies:
            name='session_search' if context['version']==4 else 'session_event_search'
            message['tool_calls']=[{'id':'proof_search','type':'function','function':{'name':name,'arguments':json.dumps({'query':'/private/synthetic next public 🧱'},ensure_ascii=False)}}]
        elif len(replies)==1:
            message['tool_calls']=[{'id':'proof_read','type':'function','function':{'name':'session_event_read','arguments':json.dumps({'seq':0})}}]
        with self.server.lock:
            self.server.models[goal]=self.server.models.get(goal,0)+1
            self.server.requests.append({'goal':goal,'body':body,'replies':replies,'context':context})
        self.send(200,{'id':'local-event-proof','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if 'tool_calls' in message else 'stop'}],'usage':{'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}})

def verify(e,old,version):
    e.prepare();e.fixture.RequestHandlerClass=ReadOnce
    goal='caller event proof '+version+' Unicode 🧱'
    worker=None
    if version=='v4':
        source=e.prior_task('source projection Unicode 🧱')
        worker=e.start('proof-worker','worker');e.state(source,'SUCCEEDED')
        task=e.prior_task(goal,sources=[source])
    else:task=e.task(goal,[])
    if worker is None:worker=e.start('proof-worker','worker')
    e.state(task,'SUCCEEDED');e.stop(worker)
    for child in reversed(e.children):e.stop({'process':child,'name':'private proof foreground collection'})
    provider=len(e.fixture.requests);results={};meters={};windows={};window_bytes={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.CallProjectionScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('proof-'+tag+'.log')
            with file.open('w') as log:
                run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text()
            e.assert_that(run.returncode==0,'actual shared event proof driver '+tag,exit=run.returncode,tail=output[-3000:])
            results[tag]=[s for s in output.splitlines() if s.startswith('RESULT ')]
            meters[tag]=[s for s in output.splitlines() if s.startswith('METER ')]
            e.assert_that(len(results[tag])>=20,'projection and frozen event proof evidence '+tag)
            e.assert_that(len(e.fixture.requests)==provider,'readonly proof driver never calls provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'meters':meters,'boundary':'Only shared projection through actual M2 response preflight and JdbcLedger append, direct exact-JAR projections and event/receipt proofs. Native2 and scope4 are two groups inside one JUnit integration. No fixture UPDATE. JSON window values compared; existing cross-JVM field order bytes remain recorded. Historical cold locks/core UoW gap remain OPEN.'},indent=2))
    e.assert_that(results['old']==results['new'],'exact old/new projection and frozen event outcomes agree')
    e.assert_that(any('projection:1 ' in row and '[redacted]' in row for row in results['new']),'query-only path redaction recorded')
    e.assert_that(any('appended:' in row and '[redacted]' in row for row in results['new']),'actual response preflight and append redact calls')
    wires={}
    for tag in ['old','new']:
        output=(e.evidence/('proof-'+tag+'.log')).read_text()
        raw=next(line[7:] for line in output.splitlines() if line.startswith('WINDOW '));wires[tag]=raw
    (e.evidence/'window-byte-digests.json').write_text(json.dumps({tag:hashlib.sha256(raw.encode()).hexdigest() for tag,raw in wires.items()},indent=2))
    e.assert_that(json.loads(wires['old'])==json.loads(wires['new']),'window JSON values agree')

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--only',choices=['all','v1','v2','v3','v4'],default='all');a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='f9662ef0c3b06c8e83998af473a641126d2e949d230ad2ae0a84046a0b702790'
    for name,klass in [('v2',SearchExperiment),('v4',PriorExperiment)]:
        if a.only not in ('all',name):continue
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
