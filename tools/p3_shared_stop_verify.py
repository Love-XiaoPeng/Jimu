"""Run-wide STOPPED across cached tools/publication, real restart and commit races; local only."""
import concurrent.futures
import http.server
import json
from pathlib import Path
import re
import signal
import sys
import threading

sys.dont_write_bytecode=True
from p2_verify import eventually
from p3_model_verify import config
from p3_report_verify import ReportExperiment


class Model(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass

    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        goal=next(m["content"] for m in body["messages"] if m["role"]=="user")
        public=goal.startswith("present-")
        replies=[m for m in body["messages"] if m["role"]=="tool"]
        unknown="-unknown" in goal
        malformed="-malformed-" in goal
        url=("https://fixture.example" if public else self.server.web)+"/page/"+goal
        system=next((m["content"] for m in body["messages"] if m["role"]=="system"),"")
        clock=re.search(r"report time (\S+)\.",system).group(1) if system else "unused"
        report="## 主题\n共享停发\n## 摘要\n实际本地TLS\n## 结论\n[来源]("+url+")\n## 来源链接\n[来源]("+url+")\n## 生成时间\n"+clock+"\n## 局限\n仅替身故障验证。"
        stages=[("web_fetch",{"url":url}),("read",{"file_path":"reports/stop.md"}),
                ("write",{"file_path":"reports/stop.md","content":report}),
                ("present",{"files":[{"path":"reports/stop.md"}]})] if public else [("web_fetch",{"url":url})]
        name,args=stages[len(replies)] if len(replies)<len(stages) else (None,{})
        message={"content":"报告已生成"}
        if name:
            message["tool_calls"]=[{"id":"stop_"+str(len(replies)),"type":"function","function":{"name":name,"arguments":json.dumps(args)}}]
        usage={"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}
        if unknown: usage={"prompt_tokens":100}
        reason="tool_calls" if name else "stop"
        with self.server.lock:
            self.server.calls.append({"goal":goal,"stream":body["stream"],"turn":len(replies)+1,"usage":None if malformed else usage,"malformedResponse":malformed})
        if body["stream"]:
            delta=dict(message)
            if name: delta["tool_calls"][0]["index"]=0
            chunks=[{"id":"stop-local","choices":[{"index":0,"delta":delta,"finish_reason":reason}]},{"choices":[],"usage":usage}]
            data=("".join("data: "+json.dumps(c)+"\n\n" for c in chunks)+"data: [DONE]\n\n").encode()
        else:
            data=json.dumps({"id":"stop-local","choices":[{"index":0,"message":message,"finish_reason":reason}],"usage":usage}).encode()
        if malformed: data=b'{unparseable'
        self.send_response(200);self.send_header("Content-Type","text/event-stream" if body["stream"] else "application/json")
        self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data)


def verify(e):
    server=http.server.ThreadingHTTPServer(("127.0.0.1",0),Model)
    server.daemon_threads=True;server.origin="http://127.0.0.1:"+str(server.server_port)
    server.web=e.fixture.origin;server.calls=[];server.lock=threading.Lock()
    thread=threading.Thread(target=server.serve_forever);thread.start()
    serial=0;facts=[]

    def cfg(run,public,stream):
        out=config({"a":server},paid=True,fixed=0,run_id=run)
        out.update({"jimu.web.mode":"public" if public else "fixture","jimu.model.profiles.a.stream":str(stream).lower(),
            "jimu.model.profiles.a.capabilities.stream-text":"true","jimu.model.profiles.a.capabilities.stream-tools":"true",
            "jimu.model.profiles.a.capabilities.usage-stream":"true","jimu.model.profiles.a.price.input-micros-per-million":"1000000",
            "jimu.model.profiles.a.price.output-micros-per-million":"2000000"})
        return out

    def control(settings):
        nonlocal serial
        serial+=1;e.stop(e.control);e.control=e.start("stop-control-"+str(serial),"control",extra=settings)

    def worker(settings,point=""):
        nonlocal serial
        serial+=1;return e.start("stop-worker-"+str(serial),"worker",point,extra=settings)

    def accept(goal,public):
        status,view=e.http("POST","/api/tasks",{"goal":goal,"capabilityIds":["web_fetch","read","write","present"] if public else ["web_fetch"]},key=goal)
        e.assert_that(status==202,"shared-stop task accepted",goal=goal,status=status)
        return view["taskId"]

    def pause(task,settings,point):
        w=worker(settings,point)
        marker=e.barrier(w)
        e.assert_that(marker.split()[1]==task,"shared-stop boundary belongs to intended task",expectedTask=task,marker=marker)
        return w

    def target_counts(task):
        return e.sql("SELECT models_used,tools_used,(SELECT COUNT(*) FROM artifact WHERE task_id=t.id),(SELECT COUNT(*) FROM artifact WHERE task_id=t.id AND state='PUBLISHED') FROM task t WHERE id='"+task+"';")

    def stop_run(settings,public,label,point="MODEL_RESPONSE_COMMITTED"):
        # The review's exact fixture/JSON counterexample commits modelFailed(..., null).
        malformed=label=="fixture-cache-false-true" and point=="MODEL_RESPONSE_COMMITTED"
        if malformed: point="MODEL_FAILURE_COMMITTED"
        bad=accept(label+("-malformed-unknown" if malformed else "-unknown"),public)
        w=pause(bad,settings,point)
        if point in ["MODEL_RESPONSE_COMMITTED","MODEL_FAILURE_COMMITTED"]:
            state=e.sql("SELECT state FROM evaluation_budget WHERE id='"+settings["jimu.model.evaluation-run-id"]+"';")
            e.assert_that(state=="STOPPED","unknown response committed run-wide STOPPED",task=bad,runState=state)
        return bad,w

    try:
        e.sql("UPDATE tenant_budget SET model_limit=1000,tool_limit=1000,token_limit=16777216,cost_limit=100000000,running_limit=2 WHERE tenant_id='tenant-a';")
        for public in [False,True]:
            mode="present" if public else "fixture"
            e.sql("UPDATE installation SET plugin_version='"+("0.3.0" if public else "0.2.0")+"' WHERE plugin_id='web_fetch';")
            for stream in [False,True]:
                # Same cached tool/response is safe under OPEN, refused after another task stops run.
                for stopped in [False,True]:
                    run=mode+"-cache-"+str(stream).lower()+"-"+str(stopped).lower()
                    settings=cfg(run,public,stream)
                    e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','"+run+"');")
                    control(settings)
                    task=accept(run,public)
                    w=pause(task,settings,"W1" if public else "T1")
                    e.stop(w,kill=True);e.expire(task)
                    w=pause(task,settings,"MODEL_RESPONSE_COMMITTED")
                    # Keep the cached task's real owner alive until the other task stops the run.
                    # A future lease cannot park a dead writer: supervisor correctly expires it.
                    before=target_counts(task);before_calls=len(server.calls)
                    if stopped:
                        bad,bw=stop_run(settings,public,run)
                        e.stop(bw,kill=True)
                        held=e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")
                        before_calls=len(server.calls)
                    e.stop(w,kill=True)
                    # An actual control restart and two competing worker JVMs use persisted state.
                    control(settings);e.expire(task)
                    if stopped: e.expire(bad)
                    w=worker(settings);w2=worker(settings)
                    view=e.state(task,"FAILED" if stopped else "SUCCEEDED")
                    if stopped: e.state(bad,"FAILED")
                    e.stop(w);e.stop(w2)
                    after=target_counts(task)
                    if stopped:
                        e.assert_that(before==after and len(server.calls)==before_calls,"shared STOPPED blocks cached tool/publication after actual restart and competing workers",mode=mode,stream=stream,before=before,after=after)
                        e.assert_that(held==e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';"),"UNKNOWN and already known settlements remain exact",run=run,balance=held)
                    else:
                        e.assert_that(len(view["artifacts"])==1,"OPEN run cached recovery still publishes normally",mode=mode,stream=stream)
                    facts.append({"case":run,"task":task,"state":view["state"],"before":before,"after":after,"stopped":stopped,"modelCallsAfter":len(server.calls)})

            # Stop before commit, publish before stop, and truly competing commits, both producers.
            for order in ["stop-first","publish-first","race"]:
                run=mode+"-commit-"+order
                settings=cfg(run,public,False)
                e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','"+run+"');")
                control(settings);task=accept(run,public)
                point=("P2" if public else "A2") if order=="publish-first" else ("P1" if public else "A1")
                w=pause(task,settings,point)
                before=target_counts(task)
                bad,bw=stop_run(settings,public,run,"M1_RESPONSE" if order=="race" else "MODEL_RESPONSE_COMMITTED")
                if order=="race":
                    with concurrent.futures.ThreadPoolExecutor(2) as pool:
                        list(pool.map(lambda process:(process["folder"]/"release").touch(),[w,bw]))
                else:
                    (w["folder"]/"release").touch();(bw["folder"]/"release").touch()
                e.state(bad,"FAILED")
                eventually(lambda:e.http("GET","/api/tasks/"+task)[1]["state"] in ["FAILED","NEEDS_RECONCILIATION","SUCCEEDED"],"publication race collected")
                e.stop(w);e.stop(bw)
                published=int(e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='"+task+"' AND state='PUBLISHED';"))
                if order=="stop-first":e.assert_that(published==0,"stop commits before publication denies actual producer",mode=mode)
                if order=="publish-first":e.assert_that(published==1,"already committed publication remains",mode=mode)
                stopped_at=e.sql("SELECT ended_at FROM model_dispatch WHERE task_id='"+bad+"';")
                late=e.sql("SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type IN ('model.dispatched','tool.dispatched','artifact.created','task.completed') AND occurred_at>'"+stopped_at+"';")
                e.assert_that(late=="0","same run lock serializes admission/publication/success against STOPPED commit",mode=mode,order=order,stopTime=stopped_at,published=published)
                if published:
                    aid=e.sql("SELECT id FROM artifact WHERE task_id='"+task+"' AND state='PUBLISHED';")
                    e.assert_that(e.http("GET","/api/artifacts/"+aid+"/download")[0]==200,"pre-stop published bytes keep authorized download",mode=mode)
                # Restart must not turn a refused upload or failed run into publication/success.
                control(settings)
                w=worker(settings);w2=worker(settings);e.stop(w);e.stop(w2)
                e.assert_that(e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='"+task+"' AND state='PUBLISHED';")==str(published),"post-race restart preserves publication decision")
                facts.append({"case":run,"task":task,"unknownTask":bad,"before":before,"after":target_counts(task),"published":published,"stopTime":stopped_at})

            # Another OPEN run is unaffected even within the same tenant.
            run=mode+"-other-open";settings=cfg(run,public,False)
            e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','"+run+"');")
            control(settings);task=accept(run,public);w=worker(settings);view=e.state(task,"SUCCEEDED");e.stop(w)
            e.assert_that(len(view["artifacts"])==1,"other OPEN run unaffected",mode=mode)
        for table in ["evaluation_budget","model_dispatch","reservation","event","artifact"]:
            (e.evidence/("shared-stop-"+table+".tsv")).write_text(e.sql("SELECT * FROM "+table+";"))
        (e.evidence/"shared-stop-facts.json").write_text(json.dumps(facts,indent=2))
    finally:
        (e.evidence/"shared-stop-http.json").write_text(json.dumps(server.calls,indent=2))
        server.shutdown();server.server_close();thread.join(5)
        e.assert_that(not thread.is_alive(),"shared-stop model listener collected")


if __name__=="__main__":
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt("Shared-stop test terminated")))
    e=ReportExperiment(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))
    try:e.prepare();verify(e)
    finally:e.cleanup()
