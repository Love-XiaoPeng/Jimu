"""Changed append paths only: actual runtime facts, exact old/new append and rollback trials."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from p2_verify import Handler,Experiment
from session_trace_verify import TraceExperiment
from prior_session_verify import PriorExperiment
from xiao60_b2_normalization_verify import observed_state

class AppendModel(Handler):
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal=next(m['content'] for m in body['messages'] if m['role']=='user')
        context=json.loads(body['messages'][-1]['content'])
        replies=[m for m in body['messages'] if m['role']=='tool']
        message={'role':'assistant','content':'Append public evidence.'}
        if not goal.startswith('source append') and not replies:
            args={'seq':0}
            if context['version']==4:args['session_id']=context['prior_sessions'][0]['session_id']
            message['tool_calls']=[{'id':'append_read','type':'function','function':{'name':'session_event_read','arguments':json.dumps(args)}}]
        with self.server.lock:
            self.server.models[goal]=self.server.models.get(goal,0)+1
            self.server.requests.append({'goal':goal,'body':body,'context':context})
        self.send(200,{'id':'local-append','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if 'tool_calls' in message else 'stop'}],'usage':{'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}})

def compare(events, output):
    result={}
    for trial,row in events.items():
        row=dict(row)
        raw=row.pop('event_snapshot');digest=row.pop('event_digest')
        assert hashlib.sha256(raw.encode()).hexdigest()==digest
        assert len(raw.encode())==row['event_bytes']
        row['eventValue']=json.loads(raw)
        if trial=='tool':
            receipt=row['eventValue']['data']
            assert receipt['output_sha256']==hashlib.sha256(output.encode()).hexdigest()
            assert receipt['output_bytes']==len(output.encode())
            # The original Map.of window order has a JVM salt. Only its independently
            # proven output hash is excluded from cross-JVM value comparison.
            del receipt['output_sha256']
        result[trial]=row
    return result

def verify(e,old,version):
    e.prepare();e.fixture.RequestHandlerClass=AppendModel
    if version=='v4':
        source=e.prior_task('source append public 🧱')
        worker=e.start('append-source','worker');observed_state(e,source,'SUCCEEDED');e.stop(worker)
        task=e.prior_task('caller append pending 🧱',sources=[source])
        boundary='SESSION_COMMIT_BEFORE'
    else:
        task=e.task('native append pending 🧱',[]);boundary='SESSION_COMMIT_BEFORE'
    worker=e.start('append-pending','worker',boundary=boundary);e.barrier(worker)
    for child in reversed(e.children):
        if child is not worker['process']:e.stop({'process':child,'name':'append private foreground collection'})
    Experiment.stop(e,worker,kill=True)
    provider=len(e.fixture.requests);results={};outputs={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionEventAppendScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='ON';")
        try:
            file=e.evidence/('driver-'+tag+'.log')
            with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text()
            outputs[tag]=next(s.split(' ',2)[2] for s in output.splitlines() if s.startswith('RESULT tool ')) if run.returncode==0 else None
            e.assert_that(run.returncode==0,'append exact-JAR '+version+' '+tag,exit=run.returncode,tail=output[-6000:])
            results[tag]={'outcomes':[(s.split(' ',2)[1],json.loads(s.split(' ',2)[2])) for s in output.splitlines() if s.startswith('RESULT ')], 'events':{s.split(' ',2)[1]:json.loads(s.split(' ',2)[2]) for s in output.splitlines() if s.startswith('EVENT ')}}
            e.assert_that(len(results[tag]['outcomes'])==17 and output.count('PASS all captured append facts restored after outer rollback')==17,'seventeen append trials and full captured-fact rollbacks '+tag)
            e.assert_that(provider==len(e.fixture.requests),'append comparison never replays provider '+tag)
        finally:
            e.sql("SET GLOBAL general_log='OFF';")
            (e.evidence/(tag+'-runtime-sql.tsv')).write_text(e.sql("SELECT event_time,thread_id,command_type,argument FROM mysql.general_log WHERE user_host LIKE 'runtime%' ORDER BY event_time;"))
            e.sql('TRUNCATE TABLE mysql.general_log;')
    e.assert_that(results['old']['outcomes']==results['new']['outcomes'],'append old/new outcomes match '+version)
    def outside_window(raw):
        at=raw.index('"window":')+len('"window":')
        value,end=json.JSONDecoder().raw_decode(raw[at:])
        return raw[:at],value,raw[at+end:]
    e.assert_that(outside_window(outputs['old'])==outside_window(outputs['new']),'original read output differs only in window object field order '+version)
    e.assert_that(compare(results['old']['events'],outputs['old'])==compare(results['new']['events'],outputs['new']),'original output/receipt/event hash chain proven, remaining event values and stored columns agree '+version)
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'originalToolOutputs':outputs,'boundary':'Actual new worker accepted/step/model committed facts and original PENDING native or cross-scope read. Seventeen old/new trials each outer rollback: original sessionFact duplicates, fresh/idempotent append and caller account, ordinary/terminal caps, body cap, direct and actual read tool INSERT/cursor faults, real terminal sessionFact. One separate original2s query/account warmup per driver also outer-rolled-back, not a tool retry/cold acceptance. Three fixture lease/deadline updates and boundary/body/state fixtures per driver also rolled back. DB clock alone frozen via same SQL proxy for comparison; no production clock/deadline changes. Original raw output SHA256/length -> receipt -> raw event SHA256/length checked separately. Cross-JVM output bytes outside original Map.of window strictly equal; only that proven output_sha256 is excluded from event JSON value comparison, all original bytes/digests retained. Read/terminal staged trials are not durable commits/whole-task acceptance or independent afterCompletion coverage. No UNKNOWN/provider replay. Historical original-slot read,503/dispatch timeouts,cold/full locks/core final-beforeCommit remain OPEN.'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='70e63fd8612663dd48d58fcaf3a489223469dbe4e78f5e3723c87edf14c3440a'
    for name,klass in [('v3',TraceExperiment),('v4',PriorExperiment)]:
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
