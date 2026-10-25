"""Changed prepare/checkpoint paths; private DB, exact old/new JAR, outer rollback trials."""
import argparse,hashlib,json,signal,subprocess,traceback
from pathlib import Path
from session_trace_verify import TraceExperiment
from prior_session_verify import PriorExperiment
from xiao60_b2_history_storage_verify import AppendModel
from xiao60_b2_normalization_verify import observed_state

def verify(e,old,version):
    e.prepare();e.fixture.RequestHandlerClass=AppendModel
    if version=='v4':
        source=e.prior_task('source append prepare public 🧱')
        worker=e.start('prepare-source','worker');observed_state(e,source,'SUCCEEDED');e.stop(worker)
        task=e.prior_task('prepare caller public 🧱',sources=[source])
    else:task=e.task('prepare native public 🧱',[])
    config=e.control['folder']/'runtime.properties'
    for child in reversed(e.children):e.stop({'process':child,'name':'prepare foreground collection'})
    provider=len(e.fixture.requests);results={};proofs={}
    for tag,binary in [('old',old),('new',e.jar)]:
        command=[e.java,'-Xmx256m','-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionCheckpointPreparationScenario','-Dloader.path='+str(Path(__file__).resolve().parents[1]/'target/test-classes'),'-cp',str(binary),'org.springframework.boot.loader.launch.PropertiesLauncher',str(config),task]
        file=e.evidence/('driver-'+tag+'.log')
        with file.open('w') as log:run=subprocess.run(command,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=240)
        output=file.read_text()
        e.assert_that(run.returncode==0,'actual prepare/checkpoint exact JAR '+version+' '+tag,exit=run.returncode,tail=output[-6000:])
        results[tag]=[(s.split(' ',2)[1],json.loads(s.split(' ',2)[2])) for s in output.splitlines() if s.startswith('RESULT ')]
        count=12 if version=='v4' else 10
        e.assert_that(len(results[tag])==count and output.count('PASS all captured prepare facts restored after outer rollback')==count,'selected prepare paths and captured fact rollbacks '+tag,trials=count)
        proofs[tag]=[json.loads(s[6:]) for s in output.splitlines() if s.startswith('PROOF ')]
        assert len(proofs[tag])==1
        for proof in proofs[tag]:
            assert hashlib.sha256(proof['context'].encode()).hexdigest()==proof['contextDigest']
            cp=json.loads(proof['request']);assert cp['messages'][-1]['content']==proof['context']
            assert hashlib.sha256(cp['requestBody'].encode()).hexdigest()==cp['bodyDigest']
        for raw in [json.loads(s[6:]) for s in output.splitlines() if s.startswith('SCOPE ')]:
            assert hashlib.sha256(raw['raw'].encode()).hexdigest()==raw['sha256'] and len(raw['raw'].encode())==raw['bytes']
        events=[json.loads(s[6:]) for s in output.splitlines() if s.startswith('EVENT ')]
        assert len(events)==1
        for event in events:
            assert hashlib.sha256(event['raw'].encode()).hexdigest()==event['sha256'] and len(event['raw'].encode())==event['bytes']
            assert hashlib.sha256(proofs[tag][0]['request'].encode()).hexdigest()==event['sourceDigest']
        e.assert_that(provider==len(e.fixture.requests),'prepare trials never invoke or replay provider '+tag)
    e.assert_that(results['old']==results['new'],'prepare old/new ordered result JSON values agree '+version)
    # Preserve raw strings separately. Do not claim cross-JVM field order equality.
    (e.evidence/'differential.json').write_text(json.dumps({'version':version,'results':results,'originalProofs':proofs,'boundary':'Actual ledger.prepare -> ExecutionService -> reserveMedia/insertStep/session prepare/prepared event/final fence, checkpoint and same-request replay. Queued native/scope tasks claimed inside each outer rollback; source scope task alone completed by real worker. Changed SQL and conditional-body faults and six direct prepare boundary trials. No trial commits, caller provider calls, scope full successful read, persisted FAILED read, independent afterCompletion or whole-task acceptance. Original2s deadlines unchanged. All captured tenant facts restored; not whole DB rollback.'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--old-jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    assert hashlib.sha256(a.old_jar.read_bytes()).hexdigest()=='ff272c7fb1257b37cd9620d3004da232b88f3e60897590fc3ca6cb42ef78cc00'
    for name,klass in [('v3',TraceExperiment),('v4',PriorExperiment)]:
        e=klass(a.jar,a.evidence/name)
        try:verify(e,a.old_jar,name)
        except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
        finally:e.cleanup()
