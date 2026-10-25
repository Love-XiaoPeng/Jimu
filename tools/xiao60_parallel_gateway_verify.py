"""One affected native host callback milestone; no old whole matrix or UNKNOWN retry."""
import argparse,hashlib,json,signal,traceback
from session_event_verify import SessionExperiment

def verify(e):
    e.prepare()
    task=e.task('integrated host gateway two original slots', [[{'seq':0},{'seq':0}]])
    worker=e.start('gateway-worker','worker','SESSION_COMMIT_AFTER')
    e.barrier(worker)
    rows=e.sql("SELECT slot,state,tool FROM invocation WHERE tenant_id='tenant-a' AND task_id='"+task+"' ORDER BY slot;")
    assert rows.splitlines()==['0:slot_0\tSUCCEEDED\tsession_event_read','1:slot_1\tPENDING\tsession_event_read'],rows
    e.assert_that(e.sql("SELECT COUNT(*) FROM invocation_session_read WHERE tenant_id='tenant-a' AND task_id='"+task+"' AND outcome='SUCCEEDED';")=='1','actual gateway first original slot committed once; second paused before callback')
    state=e.http('GET','/api/tasks/'+task)[1]
    assert state['state']=='RUNNING',state
    metadata=e.metadata(task)
    assert metadata['readable'] and metadata['recent'][-1]['type']=='session.read',metadata
    model_count=len(e.fixture.requests)
    assert model_count==1,'only original response is dispatched'
    e.stop(worker,kill=True)
    (e.evidence/'milestone.json').write_text(json.dumps({'task':task,'slots':rows,
        'sampledTask':state,'ownerHistory':metadata,'providerRequests':model_count,
        'jarSHA256':hashlib.sha256(e.jar.read_bytes()).hexdigest(),
        'boundary':'Actual paid=false HTTP response -> DurableAgent advisor -> HostInvocationGateway -> native session tool -> receipt/event COMMIT, paused at original SESSION_COMMIT_AFTER. Only first slot; second PENDING. Not full task, later dispatch, scope triple, pause/UNKNOWN recovery or independent postnote acceptance.'},indent=2))

if __name__=='__main__':
    from pathlib import Path
    import shutil,zipfile
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    archive=a.evidence/'executed';archive.mkdir(parents=True,exist_ok=False)
    repo=Path(__file__).resolve().parents[1]
    shutil.copyfile(__file__,archive/Path(__file__).name)
    for path in ['src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent.java',
                 'src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/HostInvocationGateway.java',
                 'src/main/java/io/github/lovexiaopeng/jimu/bootstrap/RuntimeConfiguration.java',
                 'src/test/java/io/github/lovexiaopeng/jimu/integration/ParallelGatewayIT.java']:
        shutil.copyfile(repo/path,archive/Path(path).name)
    with zipfile.ZipFile(a.jar) as product:
        for name in product.namelist():
            if name.startswith('BOOT-INF/classes/') and name.endswith('.class') and any(
                    marker in name for marker in ['DurableAgent','HostInvocationGateway','Readiness','PluginCatalogServiceImpl','RuntimeConfiguration']):
                target=archive/'classes'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(product.read(name))
    (archive/'index.json').write_text(json.dumps({'productSHA256':hashlib.sha256(a.jar.read_bytes()).hexdigest(),
        'files':[{'path':str(f.relative_to(archive)),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
                 for f in sorted(archive.rglob('*')) if f.is_file()]},indent=2))
    e=SessionExperiment(a.jar,a.evidence)
    try:verify(e)
    except BaseException:(e.evidence/'failure.txt').write_text(traceback.format_exc());raise
    finally:e.cleanup()
