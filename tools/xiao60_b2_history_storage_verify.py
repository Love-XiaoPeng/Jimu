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
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionHistoryStorageScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(worker['folder']/'runtime.properties'),task]
        # JDBC proxy traces retain every statement. Avoid adding server TABLE log writes
        # to the unchanged 2s tool path; earlier instrumented failures stay in evidence.
        e.sql("SET GLOBAL log_output='TABLE';SET GLOBAL general_log='OFF';")
        try:
            file=e.evidence/('driver-'+tag+'.log')
            with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            output=file.read_text()
            outputs[tag]=next((s.split(' ',2)[2] for s in output.splitlines() if s.startswith('RESULT tool ')),None) if run.returncode==0 else None
            e.assert_that(run.returncode==0,'append exact-JAR '+version+' '+tag,exit=run.returncode,tail=output[-6000:])
            results[tag]={'outcomes':[(s.split(' ',2)[1],json.loads(s.split(' ',2)[2])) for s in output.splitlines() if s.startswith('RESULT ')], 'events':{s.split(' ',2)[1]:json.loads(s.split(' ',2)[2]) for s in output.splitlines() if s.startswith('EVENT ')}}
            count=16 if version=='v4' else 17
            e.assert_that(len(results[tag]['outcomes'])==count and output.count('PASS all captured history storage facts restored after outer rollback')==count,'selected history storage trials and full captured-fact rollbacks '+tag,selectedTrials=count)
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
    if version=='v3':
        e.assert_that(outside_window(outputs['old'])==outside_window(outputs['new']),'original read output differs only in window object field order '+version)
    else:
        e.assert_that(outputs=={'old':None,'new':None},'scope full read success is explicitly outside this narrowed selection; previous failed attempts remain OPEN')
    e.assert_that(compare(results['old']['events'],outputs['old'])==compare(results['new']['events'],outputs['new']),'original output/receipt/event hash chain proven, remaining event values and stored columns agree '+version)
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'originalToolOutputs':outputs,'boundary':'Actual admission and original frozen PENDING read. Native17 trials per driver include actual full read success; scope16 explicitly exclude full read success after repeated old-JAR timeout, retaining actual first-body SQL fault plus admission/body/terminal trials. Each full captured-tenant-fact outer rollback checked. Conditional body zero/negative/limits/long/missing/PURGED/non-session and INSERT/body faults. Original2s owner/account/readonly scope warmups separately rolled back; server general_log disabled, JDBC TRACE retained. No trial commits/provider or UNKNOWN replay. Scope full successful read, independent afterCompletion, persisted FAILED read, whole-task, cold/full locks and prior failures remain OPEN.'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='f406e14358b871fc9cf07c412a9c8bb217bd57f9e6b4edcec8ceb947d8cb5ce2'
    for name,klass in [('v3',TraceExperiment),('v4',PriorExperiment)]:
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
