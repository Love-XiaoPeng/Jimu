"""Closed plan admission on real worker/MySQL; synthetic grades, usage and local TLS only."""
import concurrent.futures
import hashlib
import http.server
import json
from pathlib import Path
import re
import signal
import sys
import threading

sys.dont_write_bytecode = True
from p2_verify import eventually
from p3_model_verify import config
from p3_report_verify import ReportExperiment


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class Model(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        body = json.loads(raw)
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        replies = [m for m in body["messages"] if m["role"] == "tool"]
        single = goal.startswith(("boundary-","token-boundary-","concurrent-last-slot"))
        names = [t["function"]["name"] for t in body.get("tools", [])]
        assert names == ["web_fetch", "read", "write", "present"], names
        assert body["max_completion_tokens"] == 4086 and "max_tokens" not in body
        assert body["enable_thinking"] is False and body["enable_search"] is False and body["n"] == 1
        assert not self.headers.get("Authorization") and not self.headers.get("Cookie")
        if body["stream"]:
            assert body["stream_options"] == {"include_usage": True}
        url = "https://fixture.example/page/" + goal
        system = next((m["content"] for m in body["messages"] if m["role"] == "system"), "")
        clock = re.search(r"report time (\S+)\.", system).group(1) if system else "unused"
        report = "## 主题\n关闭计划替身\n## 摘要\n本地 TLS 资料\n## 结论\n[资料](" + url + ")\n## 来源链接\n[资料](" + url + ")\n## 生成时间\n" + clock + "\n## 局限\n仅本地替身验证，不代表模型质量。"
        stages = [("web_fetch", {"url": url}), ("read", {"file_path": "reports/closed.md"}),
                  ("write", {"file_path": "reports/closed.md", "content": report}),
                  ("read", {"file_path": "reports/closed.md"}),
                  ("present", {"files": [{"path": "reports/closed.md"}]})]
        w1 = goal.endswith("-W1")
        if w1:
            stages.insert(4,("read", {"file_path":"reports/closed.md"}))
        name, args = (None, {}) if single or len(replies) >= len(stages) else stages[len(replies)]
        message = {"content": "本地报告已交付"}
        if name:
            message["tool_calls"] = [{"id": "eval_" + str(len(replies)), "type": "function",
                "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}]
        usage = {"prompt_tokens": 1000000, "completion_tokens": 4096, "total_tokens": 1004096}
        if "output-below" in goal:
            usage={"prompt_tokens":1000000,"completion_tokens":4095,"total_tokens":1004095}
        if "output-above" in goal:
            usage={"prompt_tokens":1000000,"completion_tokens":4097,"total_tokens":1004097}
        if "unknown" in goal:
            usage = {"prompt_tokens": 1000000}
        with self.server.lock:
            self.server.calls.append({"goal": goal, "model": body["model"], "stream": body["stream"],
                "turn": len(replies)+1, "wireLimit": 4086, "bodySha256": hashlib.sha256(raw).hexdigest(),
                "tools": names, "syntheticUsage": usage})
        reason = "tool_calls" if name else "stop"
        if body["stream"]:
            delta = dict(message)
            if name:
                delta["tool_calls"][0]["index"] = 0
            chunks = [{"id": "closed-local", "choices": [{"index": 0,"delta": delta,"finish_reason": reason}]}, {"choices": [],"usage": usage}]
            data = ("".join("data: " + json.dumps(c,ensure_ascii=False) + "\n\n" for c in chunks) + "data: [DONE]\n\n").encode()
        else:
            data = json.dumps({"id": "closed-local", "choices": [{"index": 0,"message": message,"finish_reason": reason}],"usage": usage},ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream" if body["stream"] else "application/json")
        self.send_header("Content-Length",str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def verify(e):
    server = http.server.ThreadingHTTPServer(("127.0.0.1",0),Model)
    server.daemon_threads = True
    server.origin = "http://127.0.0.1:"+str(server.server_port)
    server.calls, server.lock = [], threading.Lock()
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    serial = 0
    facts = []
    resources = Path(__file__).resolve().parents[1]/"src/main/resources/evaluation"

    def settings(plan, model, sample, run):
        out = config({"a":server}, paid=True, run_id=run)
        out.update({"jimu.model.evaluation-plan-ref": plan["id"], "jimu.model.evaluation-phase": "A" if sample in ["W1","W2"] else "B", "jimu.model.evaluation-sample": sample})
        r = plan["candidates"][model]
        prefix = "jimu.model.profiles.a."
        for key,value in {"model":model,"revision":model,"stream":sample in ["W2","W4"],
            "max-output-tokens":4096,"capabilities.context-limit":1000000,
            "capabilities.evidence":"modelstudio-nonthinking-v1","capabilities.stream-text":True,
            "capabilities.stream-tools":True,"capabilities.usage-stream":True,
            "provider-options.enable_thinking":False,"provider-options.enable_search":False,
            "price.fixed-micros":0,"price.input-token-upper":1000000,
            "price.input-micros-per-million":r["inputMicrosPerMillion"],
            "price.output-micros-per-million":r["outputMicrosPerMillion"]}.items():
            out[prefix+key] = str(value).lower() if isinstance(value,bool) else str(value)
        return out

    def replace(cfg):
        nonlocal serial
        serial += 1
        e.stop(e.control)
        e.control = e.start("plan-control-"+str(serial),"control",extra=cfg)

    def worker(cfg, boundary=""):
        nonlocal serial
        serial += 1
        return e.start("plan-worker-"+str(serial),"worker",boundary,extra=cfg)

    def provision(plan, run, prior=0, stopped=False, requests=0):
        text = canonical(plan)
        digest = hashlib.sha256(text.encode()).hexdigest()
        # Existing run balances are changed only in this private DB fixture, never discarded.
        e.sql("INSERT INTO evaluation_budget(tenant_id,id,policy_version,max_requests,max_cost_micros,cost_held,requests_used,state,plan_snapshot,plan_digest) VALUES('tenant-a','"+run+"',2,"+str(plan["maxRequests"])+","+str(plan["maxCostMicros"])+","+str(prior)+","+str(requests)+",'"+("STOPPED" if stopped else "OPEN")+"','"+text+"','"+digest+"');")
        return digest

    def accept(goal, sample, expect=202):
        status, view = e.http("POST","/api/tasks", {"goal":goal,"capabilityIds":["web_fetch","read","write","present"]},key=goal)
        e.assert_that(status==expect,"plan acceptance "+goal,status=status,response=view)
        return view.get("taskId")

    def complete(cfg,goal,sample,expected="SUCCEEDED"):
        replace(cfg)
        task = accept(goal,sample)
        recovering = goal in ["qwen-main-v1-qwen3.7-plus-2026-05-26-W1", "qwen-main-v1-qwen3.7-plus-2026-05-26-W2"]
        w = worker(cfg,"T1" if recovering else "")
        if recovering:
            e.barrier(w)
            e.stop(w,kill=True)
            e.expire(task)
            w=worker(cfg)
        view = e.state(task,expected)
        e.stop(w)
        return task,view

    def qualification(plan,digest,run,finalists,invalid=False):
        score = {"chinese":18,"tools":26 if invalid else 28,"sources":28,"report":18,"hardFailure":False}
        q = {"planDigest":digest,"materialsDigest":plan["materialsDigest"],"assessments":{m:{"completed":True,"c1Passed":True,"c1Chinese":18,"w1":score,"w2":score,"evidenceSha256":"b"*64} for m in plan["candidates"]},"finalists":finalists,"adjudicated":True,"acceptedFinalCodeSha":"c"*40,"evidenceManifestDigest":"d"*64,"actor":"local-synthetic-reviewer","timestamp":"2026-10-16T00:00:00Z","originalRunId":run}
        text=canonical(q)
        e.sql("UPDATE evaluation_budget SET phase_b_snapshot='"+text+"',phase_b_digest='"+hashlib.sha256(text.encode()).hexdigest()+"' WHERE id='"+run+"';")
        return q

    try:
        e.sql("UPDATE tenant_budget SET token_limit=1000000000,cost_limit=2000000000,model_limit=1000,running_limit=1 WHERE tenant_id='tenant-a';")
        other = e.sql("SELECT token_limit,cost_limit,model_limit,running_limit FROM tenant_budget WHERE tenant_id='tenant-b';")
        for ident in ["qwen-main-v1","qwen-pro-optional-v1"]:
            p=json.loads((resources/(ident+".json")).read_text())
            e.assert_that(not p["enabled"] and not p["paidDispatchAuthorized"],"plan is closed",plan=ident)
            run=ident
            digest=provision(p,run)
            before_tokens=int(e.sql("SELECT token_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
            e.sql("UPDATE tenant_budget SET token_limit="+str(before_tokens+p["tenantTokenLimit"])+" WHERE tenant_id='tenant-a';")
            models=list(p["candidates"])
            finalists=sorted(models,key=lambda m:p["candidates"][m]["inputMicrosPerMillion"],reverse=True)[:2]
            cfg=settings(p,finalists[0],"W3",run)
            replace(cfg)
            accept(ident+"-locked-W3","W3",409)
            qualification(p,digest,run,finalists,invalid=True)
            accept(ident+"-bad-grade-W3","W3",409)
            for model in models:
                for sample in ["W1","W2"]:
                    cfg=settings(p,model,sample,run)
                    task,view=complete(cfg,ident+"-"+model+"-"+sample,sample)
                    rounds=7 if sample=="W1" else 6
                    e.assert_that(e.sql("SELECT models_used,token_held FROM task WHERE id='"+task+"';")==str(rounds)+"\t"+str(rounds*1004096),"full context and billed output retained at exact task boundary",task=task)
                    e.assert_that(len(view["artifacts"])==1,"report comes from actual present",task=task)
                    if view["artifacts"]:
                        aid=view["artifacts"][0]["artifactId"]
                        e.assert_that(e.http("GET","/api/artifacts/"+aid+"/download")[0]==200,"bounded report authorized download")
                    accept(ident+"-duplicate-"+model+"-"+sample,sample,409)
            q=qualification(p,digest,run,finalists)
            held=int(e.sql("SELECT token_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
            ceiling=int(e.sql("SELECT token_limit FROM tenant_budget WHERE tenant_id='tenant-a';"))
            e.sql("UPDATE tenant_budget SET token_limit=token_limit-1 WHERE tenant_id='tenant-a';")
            replace(settings(p,finalists[0],"W3",run))
            accept(ident+"-B-envelope-short-W3","W3",429)
            e.sql("UPDATE tenant_budget SET token_limit="+str(ceiling)+" WHERE tenant_id='tenant-a';")
            used=int(e.sql("SELECT model_used FROM tenant_budget WHERE tenant_id='tenant-a';"))
            limit=int(e.sql("SELECT model_limit FROM tenant_budget WHERE tenant_id='tenant-a';"))
            e.sql("UPDATE tenant_budget SET model_limit="+str(used+12*len(finalists)-1)+" WHERE tenant_id='tenant-a';")
            accept(ident+"-B-request-envelope-short-W3","W3",429)
            e.sql("UPDATE tenant_budget SET model_limit="+str(limit)+" WHERE tenant_id='tenant-a';")
            nonfinal=next(m for m in models if m not in finalists)
            replace(settings(p,nonfinal,"W3",run))
            accept(ident+"-nonfinal-W3","W3",409)
            for model in finalists:
                for sample in ["W3","W4"]:
                    cfg=settings(p,model,sample,run)
                    task,view=complete(cfg,ident+"-"+model+"-"+sample,sample)
                    e.assert_that(len(view["artifacts"])==1,"qualified stage B produces real local report",task=task)
            totals=e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")
            expected=471999593 if ident=="qwen-main-v1" else 626584745
            e.assert_that(totals==str(p["maxRequests"])+"\t"+str(expected),"entire worst case matrix fits global and staged caps",totals=totals)
            e.assert_that(e.sql("SELECT token_held FROM tenant_budget WHERE tenant_id='tenant-a';")==str(before_tokens+p["tenantTokenLimit"]),"private tenant token limit exactly consumed without clearing earlier usage")
            facts.append({"plan":p,"qualificationSyntheticOnly":q,"runTotals":totals})

        # First admission must fit the entire remaining fair matrix, including old liabilities.
        p=json.loads((resources/"qwen-main-v1.json").read_text())
        e.sql("UPDATE tenant_budget SET token_limit=1000000000 WHERE tenant_id='tenant-a';")
        model="qwen3.8-max-0902"
        cost=12147456
        provision(p,"initial-insufficient",p["maxCostMicros"]-p["matrixWorstCostMicros"]+1)
        replace(settings(p,model,"W1","initial-insufficient"))
        accept("initial-insufficient-W1","W1",429)
        provision(p,"initial-old-requests",requests=1)
        replace(settings(p,model,"W1","initial-old-requests"))
        accept("initial-old-requests-W1","W1",429)

        # Isolated tenant ceilings must fit the fair matrix before the first new binding.
        run="initial-private-envelope"
        provision(p,run)
        replace(settings(p,model,"W1",run))
        for column,used,required in [("token_limit","token_held",p["tenantTokenLimit"]),
                ("cost_limit","cost_held",p["matrixWorstCostMicros"]),("model_limit","model_used",p["maxRequests"])]:
            original=int(e.sql("SELECT "+column+" FROM tenant_budget WHERE tenant_id='tenant-a';"))
            held=int(e.sql("SELECT "+used+" FROM tenant_budget WHERE tenant_id='tenant-a';"))
            e.sql("UPDATE tenant_budget SET "+column+"="+str(held+required-1)+" WHERE tenant_id='tenant-a';")
            accept("initial-"+column+"-short-W1","W1",429)
            e.sql("UPDATE tenant_budget SET "+column+"="+str(original)+" WHERE tenant_id='tenant-a';")

        # After plan binding, exercise N-1/N/N+1 remaining cost and the final request slot.
        for label,prior,requests,stopped in [("minus-one",p["maxCostMicros"]-cost+1,0,False),("exact",p["maxCostMicros"]-cost,62,False),("plus-one",p["maxCostMicros"]-cost-1,0,False),("request-full",0,63,False),("stopped",12345,0,True)]:
            run="boundary-"+label
            provision(p,run)
            cfg=settings(p,model,"W1",run)
            replace(cfg)
            task=accept(run+"-W1","W1")
            # Explicit SQL fixture predecessor, kept separate from real HTTP evidence.
            e.sql("UPDATE evaluation_budget SET cost_held="+str(prior)+",requests_used="+str(requests)+",state='"+("STOPPED" if stopped else "OPEN")+"' WHERE id='"+run+"';")
            e.sql("UPDATE tenant_budget SET token_limit=1000000000 WHERE tenant_id='tenant-a';")
            w=worker(cfg)
            view=e.state(task,"FAILED")
            e.stop(w)
            calls=sum(c["goal"]==run+"-W1" for c in server.calls)
            e.assert_that(calls==(1 if label in ["exact","plus-one"] else 0),"prior balance/count/stopped boundary",case=label,calls=calls,predecessor="SQL fixture, not historical account evidence")
            facts.append({"case":label,"run":e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")})

        run="concurrent-last-slot"
        provision(p,run)
        cfg=settings(p,model,"W1",run)
        replace(cfg)
        def race(index):
            return e.http("POST","/api/tasks",{"goal":run+"-"+str(index)+"-W1","capabilityIds":["web_fetch","read","write","present"]},key=run+"-"+str(index))
        with concurrent.futures.ThreadPoolExecutor(6) as pool:
            results=list(pool.map(race,range(6)))
        accepted=[v["taskId"] for status,v in results if status==202]
        e.assert_that(len(accepted)==1 and sum(status==409 for status,v in results)==5,"concurrent keys share one immutable model/sample binding")
        e.sql("UPDATE evaluation_budget SET requests_used=62 WHERE id='"+run+"';")
        w=worker(cfg)
        other_worker=worker(cfg)
        e.state(accepted[0],"FAILED")
        e.stop(w); e.stop(other_worker)
        e.assert_that(e.sql("SELECT requests_used FROM evaluation_budget WHERE id='"+run+"';")=="63" and sum(c["goal"].startswith(run) for c in server.calls)==1,"competing workers consume the final shared request once")

        for delta in [-1,0,1]:
            run="token-boundary-"+str(delta).replace("-","minus")
            provision(p,run)
            cfg=settings(p,model,"W1",run)
            replace(cfg)
            task=accept(run+"-W1","W1")
            held=int(e.sql("SELECT token_held FROM tenant_budget WHERE tenant_id='tenant-a';"))
            e.sql("UPDATE tenant_budget SET token_limit="+str(held+1004096+delta)+" WHERE tenant_id='tenant-a';")
            w=worker(cfg)
            e.state(task,"FAILED")
            e.stop(w)
            e.assert_that(sum(c["goal"]==run+"-W1" for c in server.calls)==(0 if delta<0 else 1),"complete-context token N-1/N/N+1 boundary",delta=delta)
        e.sql("UPDATE tenant_budget SET token_limit=1000000000 WHERE tenant_id='tenant-a';")

        for stream in [False,True]:
            sample="W2" if stream else "W1"
            run="new-unknown-"+str(stream).lower()
            provision(p,run)
            e.sql("UPDATE evaluation_budget SET max_cost_micros=490000000 WHERE id='"+run+"';")
            cfg=settings(p,model,sample,run)
            task,view=complete(cfg,run,sample,"FAILED")
            e.assert_that(view["code"]=="MODEL_USAGE_UNKNOWN" and e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")=="STOPPED\t1\t"+str(cost),"4096 plan unknown stays stopped and retains full cost",stream=stream)
            e.assert_that(e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"';")=="0" and not view["artifacts"],"unknown dispatch never reaches tool or publication")
            before=e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")
            e.sql("UPDATE evaluation_budget SET max_cost_micros=500000000 WHERE id='"+run+"';")
            other_sample="W1" if stream else "W2"
            raised=settings(p,model,other_sample,run)
            follow,follow_view=complete(raised,run+"-after-raise",other_sample,"FAILED")
            e.assert_that(not follow_view["artifacts"] and not any(c["goal"]==run+"-after-raise" for c in server.calls)
                and e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")==before
                and e.sql("SELECT state FROM evaluation_budget WHERE id='"+run+"';")=="STOPPED",
                "raising closed allowance never reopens UNKNOWN or releases held cost",stream=stream)

        for stream in [False,True]:
            for above in [False,True]:
                run=("output-above-" if above else "output-below-")+str(stream).lower()
                provision(p,run)
                sample="W2" if stream else "W1"
                cfg=settings(p,model,sample,run)
                task,view=complete(cfg,run,sample,"FAILED" if above else "SUCCEEDED")
                if above:
                    e.assert_that(view["code"]=="MODEL_COST_BOUND_EXCEEDED" and not view["artifacts"]
                        and e.sql("SELECT state,cost_held FROM evaluation_budget WHERE id='"+run+"';")=="STOPPED\t"+str(cost+36),
                        "4097 total billed output rejects and retains actual overrun cost",stream=stream)
                else:
                    e.assert_that(len(view["artifacts"])==1,"4095 total billed output remains allowed",stream=stream)

        # Snapshot recovery: changed phase/sample and proposed optional plan are not upgrades.
        run="drift-recovery"
        provision(p,run)
        cfg=settings(p,model,"W1",run)
        replace(cfg)
        task=accept("drift-recovery-W1","W1")
        w=worker(cfg,"M0")
        e.barrier(w)
        before=e.sql("SELECT evaluation_snapshot,evaluation_digest,model_digest,max_tokens,max_cost_micros FROM task WHERE id='"+task+"';")
        e.stop(w,kill=True)
        e.expire(task)
        changed=settings(p,model,"W3",run)
        w=worker(changed)
        view=e.state(task,"FAILED")
        e.stop(w)
        e.assert_that(view["code"] in ["EVALUATION_CONFIG_UNAVAILABLE","EVALUATION_PHASE_LOCKED"] and not any(c["goal"]=="drift-recovery-W1" for c in server.calls),"recovery rejects changed sample before HTTP")
        e.assert_that(e.sql("SELECT evaluation_snapshot,evaluation_digest,model_digest,max_tokens,max_cost_micros FROM task WHERE id='"+task+"';")==before,"recovery retains exact original allowance and input/output pins")
        e.assert_that(e.sql("SELECT token_limit,cost_limit,model_limit,running_limit FROM tenant_budget WHERE tenant_id='tenant-b';")==other,"isolated quotas never alter other tenant")
        (e.evidence/"plan-facts.json").write_text(json.dumps(facts,ensure_ascii=False,indent=2))
        for table in ["evaluation_budget","model_dispatch","reservation"]:
            (e.evidence/("plan-"+table+".tsv")).write_text(e.sql("SELECT * FROM "+table+";"))
        (e.evidence/"plan-tasks.tsv").write_text(e.sql("SELECT id,state,code,model_digest,evaluation_snapshot,evaluation_digest,evaluation_phase,evaluation_sample,evaluation_model,max_models,max_tokens,models_used,token_held,max_cost_micros,cost_held FROM task;"))
    finally:
        (e.evidence/"plan-model-calls.json").write_text(json.dumps(server.calls,ensure_ascii=False,indent=2))
        server.shutdown(); server.server_close(); thread.join(5)
        e.assert_that(not thread.is_alive(),"closed plan model thread collected")


if __name__=="__main__":
    signal.signal(signal.SIGTERM,lambda signum,frame: (_ for _ in ()).throw(KeyboardInterrupt("Plan test terminated")))
    e=ReportExperiment(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))
    try:
        e.prepare()
        verify(e)
    finally:
        e.cleanup()
