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
from p2_verify import eventually, run
from p3_model_verify import config
from p3_report_verify import ReportExperiment
from p3_public_worker_verify import TlsHandler


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


FROZEN = Path(__file__).resolve().parent / "evaluation/frozen"
SAMPLES = {x["id"]: x for x in json.loads((FROZEN/"samples.json").read_text())["samples"]}
SOURCES = {x["id"]: x for x in json.loads((FROZEN/"sources/source-manifest.json").read_text())["sources"]}
GOALS = {sample: (FROZEN/value["goal"]["path"]).read_text() for sample,value in SAMPLES.items()}


class FrozenTls(TlsHandler):
    def do_GET(self):
        self.server.requests.append({"host":self.headers.get("Host"),"path":self.path,
            "authorization":self.headers.get("Authorization"),"cookie":self.headers.get("Cookie")})
        source = next((s for s in SOURCES.values() if s["url"].split("/",3)[-1] == self.path.lstrip("/")), None)
        data=(FROZEN/source["rawPath"]).read_bytes() if source else b"non-frozen content"
        if getattr(self.server,"tamper",False): data += b" altered source body"
        self.send_response(200)
        self.send_header("Content-Type",source["contentType"] if source else "text/plain;charset=utf-8")
        self.send_header("Content-Length",str(len(data)))
        self.end_headers();self.wfile.write(data)


class MaterialExperiment(ReportExperiment):
    def prepare(self):
        self.certificate_sans="DNS:fixture.example,DNS:www.rfc-editor.org,DNS:docs.python.org"
        super().prepare()
        self.tls.RequestHandlerClass=FrozenTls
        self.common["jimu.web.allowed-domains"]="fixture.example,www.rfc-editor.org,docs.python.org"

    def seed(self, task, altered=False):
        draft=self.root/("draft-"+task+".md")
        draft.write_bytes((FROZEN/"drafts/W4.initial.md").read_bytes()+(b" altered draft" if altered else b""))
        receipt=run([self.java,"-Dloader.path="+str(self.classes),
            "-Dloader.main=io.github.lovexiaopeng.jimu.adapters.runtime.web.EvaluationWorkspaceMain",
            "-cp",str(self.jar),"org.springframework.boot.loader.launch.PropertiesLauncher",
            str(self.root/"workspace"),"tenant-a",task,str(draft)])
        (self.evidence/("seed-"+task+".json")).write_text(receipt)


class Model(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        body = json.loads(raw)
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        replies = [m for m in body["messages"] if m["role"] == "tool"]
        case=self.server.case
        sample=next(k for k,v in GOALS.items() if v==goal)
        single = case.startswith(("boundary-","token-boundary-","concurrent-last-slot"))
        names = [t["function"]["name"] for t in body.get("tools", [])]
        assert names == ["web_fetch", "read", "write", "present"], names
        assert body["max_completion_tokens"] == 4086 and "max_tokens" not in body
        assert body["enable_thinking"] is False and body["enable_search"] is False and body["n"] == 1
        assert not self.headers.get("Authorization") and not self.headers.get("Cookie")
        if body["stream"]:
            assert body["stream_options"] == {"include_usage": True}
        url = SOURCES[SAMPLES[sample]["sourceIds"][0]]["url"]
        if "nonfrozen-url" in case: url="https://fixture.example/unfrozen"
        system = next((m["content"] for m in body["messages"] if m["role"] == "system"), "")
        clock = re.search(r"report time (\S+)\.", system).group(1) if system else "unused"
        report = "## 主题\n关闭计划替身\n## 摘要\n本地 TLS 资料\n## 结论\n[资料](" + url + ")\n## 来源链接\n[资料](" + url + ")\n## 生成时间\n" + clock + "\n## 局限\n仅本地替身验证，不代表模型质量。"
        stages = [("web_fetch", {"url": url}), ("read", {"file_path": SAMPLES[sample]["target"]}),
                  ("write", {"file_path": SAMPLES[sample]["target"], "content": report}),
                  ("read", {"file_path": SAMPLES[sample]["target"]}),
                  ("present", {"files": [{"path": SAMPLES[sample]["target"]}]})]
        w1 = sample=="W1"
        if sample=="W3":
            stages.insert(1,("web_fetch",{"url":SOURCES["colorsys"]["url"]}))
            del stages[4] # a successful write already supplies a real observation for present
        if w1:
            stages.insert(4,("read", {"file_path":SAMPLES[sample]["target"]}))
        name, args = (None, {}) if single or len(replies) >= len(stages) else stages[len(replies)]
        message = {"content": "本地报告已交付"}
        if name:
            message["tool_calls"] = [{"id": "eval_" + str(len(replies)), "type": "function",
                "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}]
            if "current-step-partial" in case and not replies:
                message["tool_calls"].append({"id":"eval_1","type":"function",
                    "function":{"name":"read","arguments":json.dumps({"file_path":SAMPLES[sample]["target"]})}})
        usage = {"prompt_tokens": 1000000, "completion_tokens": 4096, "total_tokens": 1004096}
        if "output-below" in case:
            usage={"prompt_tokens":1000000,"completion_tokens":4095,"total_tokens":1004095}
        if "output-above" in case:
            usage={"prompt_tokens":1000000,"completion_tokens":4097,"total_tokens":1004097}
        if "unknown" in case:
            usage = {"prompt_tokens": 1000000}
        with self.server.lock:
            index=len(self.server.calls)
            (self.server.evidence/("http-request-%04d.bin"%index)).write_bytes(raw)
            self.server.calls.append({"case":case,"sample":sample,"goal": goal, "model": body["model"], "stream": body["stream"],
                "turn": len(replies)+1, "wireLimit": 4086, "bodySha256": hashlib.sha256(raw).hexdigest(),
                "tools": names, "syntheticUsage": usage})
        reason = "tool_calls" if name else "stop"
        if body["stream"]:
            delta = dict(message)
            if name:
                for index,call in enumerate(delta["tool_calls"]): call["index"] = index
            chunks = [{"id": "closed-local", "choices": [{"index": 0,"delta": delta,"finish_reason": reason}]}, {"choices": [],"usage": usage}]
            data = ("".join("data: " + json.dumps(c,ensure_ascii=False) + "\n\n" for c in chunks) + "data: [DONE]\n\n").encode()
        else:
            data = json.dumps({"id": "closed-local", "choices": [{"index": 0,"message": message,"finish_reason": reason}],"usage": usage},ensure_ascii=False).encode()
        self.send_response(200)
        (self.server.evidence/("http-response-%04d.bin"%index)).write_bytes(data)
        self.send_header("Content-Type", "text/event-stream" if body["stream"] else "application/json")
        self.send_header("Content-Length",str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def verify(e, materials_only=False):
    server = http.server.ThreadingHTTPServer(("127.0.0.1",0),Model)
    server.daemon_threads = True
    server.origin = "http://127.0.0.1:"+str(server.server_port)
    server.calls, server.lock = [], threading.Lock()
    server.evidence=e.evidence; server.case="unset"
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
        server.case=goal
        status, view = e.http("POST","/api/tasks", {"goal":GOALS[sample],"capabilityIds":["web_fetch","read","write","present"]},key=goal)
        e.assert_that(status==expect,"plan acceptance "+goal,status=status,response=view)
        task=view.get("taskId")
        if status==202 and sample=="W4": e.seed(task, "bad-draft" in goal)
        return task

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

    def change_json(table, ident, field, digest_field, mutate):
        # HEX is independent of client display encodings, tabs and newlines.
        raw=bytes.fromhex(e.sql("SELECT HEX("+field+") FROM "+table+" WHERE id='"+ident+"';").strip())
        value=json.loads(raw)
        mutate(value)
        text=canonical(value)
        e.sql("UPDATE "+table+" SET "+field+"=CONVERT(0x"+text.encode().hex()+" USING utf8mb4),"+digest_field+"='"+hashlib.sha256(text.encode()).hexdigest()+"' WHERE id='"+ident+"';")

    def materials():
        p=json.loads((resources/"qwen-main-v1.json").read_text())
        candidate="qwen3.8-max-0902"
        for sample in ["W1","W2"]:
            kinds=["goal-admission","nonfrozen-url","source-body","frozen-cache","goal-cache","system-cache","schema-cache","description-cache","source-cache","snapshot-cache","missing-snapshot-cache","missing-file-cache"]
            if "--material-smoke" in sys.argv: kinds=["frozen-cache","schema-cache"]
            for kind in kinds:
                run_id="material-"+sample+"-"+kind
                provision(p,run_id)
                cfg=settings(p,candidate,sample,run_id)
                replace(cfg)
                server.case=run_id
                baseline=len(server.calls)
                tls_before=len(e.tls.requests)
                if kind=="goal-admission":
                    prior=e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run_id+"';")
                    status,view=e.http("POST","/api/tasks",{"goal":"unfrozen-plain-goal-no-C1","capabilityIds":["web_fetch","read","write","present"]},key=run_id)
                    e.assert_that(status==409 and view["code"]=="EVALUATION_MATERIAL_MISMATCH"
                        and e.sql("SELECT COUNT(*) FROM task WHERE evaluation_run_id='"+run_id+"';")=="0"
                        and prior==e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run_id+"';"),
                        "arbitrary goal rejected before binding sample or spending quota",sample=sample)
                    # A refusal has not poisoned the sample's genuine admission slot.
                    task=accept(run_id+"-frozen-after-refusal",sample)
                    e.assert_that(task is not None,"frozen goal remains admissible after wrong goal",sample=sample)
                    # Complete this positive instead of leaving a queued task for another case's worker.
                    w=worker(cfg);e.state(task,"SUCCEEDED");e.stop(w)
                    continue
                task=accept(run_id,sample)
                recovering=kind.endswith("cache")
                e.tls.tamper=kind=="source-body"
                w=worker(cfg,"T1" if recovering else "")
                expected="SUCCEEDED" if kind=="frozen-cache" else "FAILED"
                original=None
                if recovering:
                    e.barrier(w)
                    original=e.sql("SELECT models_used,tools_used,cost_held FROM task WHERE id='"+task+"';")
                    e.stop(w,kill=True);e.expire(task)
                    step=e.sql("SELECT id FROM step WHERE task_id='"+task+"' ORDER BY ordinal DESC LIMIT 1;").strip()
                    if kind=="missing-snapshot-cache":
                        e.sql("UPDATE task SET material_snapshot=NULL,material_digest=NULL WHERE id='"+task+"';")
                    elif kind=="missing-file-cache":
                        e.sql("UPDATE task SET material_file_snapshot=NULL,material_file_digest=NULL WHERE id='"+task+"';")
                    elif kind=="snapshot-cache":
                        change_json("task",task,"material_snapshot","material_digest",lambda m:m.update(goal="changed snapshot"))
                    elif kind=="source-cache":
                        invocation=e.sql("SELECT id FROM invocation WHERE task_id='"+task+"' AND tool='web_fetch';").strip()
                        change_json("invocation",invocation,"output_snapshot","output_digest",lambda m:m.update(content=m["content"]+" tampered cached source"))
                    elif kind!="frozen-cache":
                        def mutation(checkpoint):
                            body=json.loads(checkpoint["requestBody"])
                            if kind in ["goal-cache","system-cache"]:
                                index=1 if kind=="goal-cache" else 0
                                body["messages"][index]["content"]="unfrozen recovered text"
                                checkpoint["messages"][index]["content"]="unfrozen recovered text"
                            elif kind=="schema-cache": body["tools"][0]["function"]["parameters"]["additionalProperties"]=True
                            else: body["tools"][0]["function"]["description"]="unfrozen recovered schema description"
                            checkpoint["requestBody"]=json.dumps(body,ensure_ascii=False,separators=(",",":"))
                            checkpoint["bodyDigest"]=hashlib.sha256(checkpoint["requestBody"].encode()).hexdigest()
                        change_json("step",step,"request_snapshot","request_digest",mutation)
                    w=worker(cfg)
                view=e.state(task,expected);e.stop(w);e.tls.tamper=False
                added=server.calls[baseline:]
                if kind=="frozen-cache":
                    rounds=7 if sample=="W1" else 6
                    e.assert_that(len(added)==rounds and len(e.tls.requests)==tls_before+1
                        and len(view["artifacts"])==1,"frozen material survives actual JVM restart and cached fetch",sample=sample)
                else:
                    e.assert_that(len(added)==1 and not view["artifacts"]
                        and view["code"]=="EVALUATION_MATERIAL_MISMATCH"
                        and e.sql("SELECT state,requests_used FROM evaluation_budget WHERE id='"+run_id+"';")=="STOPPED\t1",
                        "unfrozen materials never consume a further model allowance or publish",sample=sample,case=kind,code=view["code"])
                    if kind=="nonfrozen-url":
                        e.assert_that(e.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=="0" and len(e.tls.requests)==tls_before,
                            "nonfrozen tool input refused before tool quota and transport",sample=sample)
                    else:
                        e.assert_that(e.sql("SELECT tools_used FROM task WHERE id='"+task+"';")=="1"
                            and e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='"+task+"' AND category='TOOL' AND state='SETTLED' AND actual=1;")=="1",
                            "known fetch settlement retained on material refusal",sample=sample,case=kind)
                    if original:
                        e.assert_that(original==e.sql("SELECT models_used,tools_used,cost_held FROM task WHERE id='"+task+"';"),
                            "recovery material refusal leaves all original model/tool costs intact",sample=sample,case=kind)
                facts.append({"materialCase":kind,"sample":sample,"task":task,"view":view,"originalBalances":original})

        # B qualification is a clearly labelled trusted SQL fixture, not measured quality.
        draft_kinds=["bad-draft","missing-draft","frozen-draft-cache","draft-rebind-cache","draft-history-cache"]
        if "--material-smoke" in sys.argv: draft_kinds=["draft-history-cache"]
        for kind in draft_kinds:
            run_id="material-W4-"+kind
            digest=provision(p,run_id)
            for model in p["candidates"]:
                for sample in ["W1","W2"]:
                    replace(settings(p,model,sample,run_id))
                    task=accept(run_id+"-synthetic-predecessor-"+model+sample,sample)
                    e.sql("UPDATE task SET state='SUCCEEDED' WHERE id='"+task+"';"
                        "UPDATE reservation SET state='RELEASED' WHERE id='"+task+":queue';"
                        "UPDATE tenant_budget SET queued=queued-1 WHERE tenant_id='tenant-a';")
            qualification(p,digest,run_id,[candidate])
            cfg=settings(p,candidate,"W4",run_id)
            replace(cfg)
            server.case=run_id
            if kind=="missing-draft":
                status,view=e.http("POST","/api/tasks",{"goal":GOALS["W4"],"capabilityIds":["web_fetch","read","write","present"]},key=run_id)
                e.assert_that(status==202,"missing draft fixture accepted before host preflight")
                task=view["taskId"]
            else: task=accept(run_id,"W4")
            baseline=len(server.calls)
            recovering=kind in ["frozen-draft-cache","draft-rebind-cache","draft-history-cache"]
            w=worker(cfg,"READ_MODEL_PREPARED" if kind=="draft-history-cache" else "T1" if recovering else "")
            if recovering:
                e.barrier(w);e.stop(w,kill=True);e.expire(task)
                receipt=e.sql("SELECT material_file_snapshot,material_file_digest FROM task WHERE id='"+task+"';")
                if kind=="draft-rebind-cache": e.seed(task,altered=True)
                if kind=="draft-history-cache":
                    step=e.sql("SELECT id FROM step WHERE task_id='"+task+"' ORDER BY ordinal DESC LIMIT 1;").strip()
                    def mutate_draft(checkpoint):
                        body=json.loads(checkpoint["requestBody"])
                        body["messages"][-1]["content"]+=" altered visible draft"
                        checkpoint["messages"][-1]["replies"][-1]["data"]+=" altered visible draft"
                        checkpoint["requestBody"]=json.dumps(body,ensure_ascii=False,separators=(",",":"))
                        checkpoint["bodyDigest"]=hashlib.sha256(checkpoint["requestBody"].encode()).hexdigest()
                    change_json("step",step,"request_snapshot","request_digest",mutate_draft)
                w=worker(cfg)
                view=e.state(task,"SUCCEEDED" if kind=="frozen-draft-cache" else "FAILED");e.stop(w)
                e.assert_that(receipt==e.sql("SELECT material_file_snapshot,material_file_digest FROM task WHERE id='"+task+"';"),"W4 retains original durable version and hash over process restart",case=kind)
                if kind=="frozen-draft-cache":
                    e.assert_that(len(server.calls)-baseline==6 and len(view["artifacts"])==1,"W4 exact real draft survives cached recovery")
                else:
                    e.assert_that(len(server.calls)-baseline==(2 if kind=="draft-history-cache" else 1) and not view["artifacts"] and view["code"]=="EVALUATION_MATERIAL_MISMATCH",
                        "recovery cannot adopt a newly seeded draft or changed real version")
            else:
                view=e.state(task,"FAILED");e.stop(w)
                e.assert_that(len(server.calls)==baseline and view["code"]=="EVALUATION_MATERIAL_MISMATCH"
                    and e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run_id+"';")=="0\t0",
                    "W4 missing or altered draft refused before any model quota",case=kind)
            facts.append({"materialCase":kind,"sample":"W4","task":task,"view":view,"predecessor":"synthetic trusted SQL quality fixture, not grading acceptance"})

    def history():
        p=json.loads((resources/"qwen-main-v1.json").read_text())
        candidate="qwen3.8-max-0902"
        reproduce="--history-reproduce" in sys.argv
        samples=["W2" if "--history-sse" in sys.argv else "W1"] if reproduce else ["W1","W2"]
        kinds=["rollback","missing-round","missing-assistant","missing-tool","duplicate-round",
            "reordered","arguments","assistant-content","call-id","output",
            "current-step-complete","current-step-partial","prepared-positive"]
        if "--history-smoke" in sys.argv: kinds=["rollback","current-step-partial","prepared-positive"]
        if reproduce: kinds=["rollback"]
        for sample in samples:
            for kind in kinds:
                run_id="history-"+sample+"-"+kind
                provision(p,run_id)
                cfg=settings(p,candidate,sample,run_id)
                replace(cfg);task=accept(run_id,sample)
                calls_before=len(server.calls);tls_before=len(e.tls.requests)
                current=kind.startswith("current-step-")
                positive=current or kind=="prepared-positive"
                w=worker(cfg,"T1" if current else "READ_MODEL_PREPARED")
                e.barrier(w)
                expected_calls=1 if current else 2
                e.assert_that(len(server.calls)-calls_before==expected_calls,"history fixture reached precise target step",sample=sample,case=kind)
                step=e.sql("SELECT id FROM step WHERE task_id='"+task+"' ORDER BY ordinal DESC LIMIT 1;").strip()
                e.stop(w,kill=True);e.expire(task)
                # Actual process exit precedes all private-DB fault injection.
                replace(cfg)
                if current:
                    states=e.sql("SELECT state FROM invocation WHERE step_id='"+step+"' ORDER BY slot;")
                    e.assert_that(states==("SUCCEEDED\nPENDING" if kind.endswith("partial") else "SUCCEEDED"),
                        "own step has committed tool output absent from its legal old request",sample=sample,case=kind,states=states)
                raw=bytes.fromhex(e.sql("SELECT HEX(request_snapshot) FROM step WHERE id='"+step+"';"))
                (e.evidence/(run_id+"-original-checkpoint.bin")).write_bytes(raw)
                if not positive:
                    if kind=="rollback":
                        old=bytes.fromhex(e.sql("SELECT HEX(request_snapshot) FROM step WHERE task_id='"+task+"' AND ordinal=1;"))
                        e.sql("UPDATE step SET request_snapshot=CONVERT(0x"+old.hex()+" USING utf8mb4),request_digest='"+hashlib.sha256(old).hexdigest()+"' WHERE id='"+step+"';")
                    else:
                        def mutation(checkpoint):
                            messages=checkpoint["messages"]
                            if kind=="missing-round": del messages[2:4]
                            elif kind=="missing-assistant": del messages[2]
                            elif kind=="missing-tool": del messages[3]
                            elif kind=="duplicate-round": messages[4:4]=json.loads(json.dumps(messages[2:4]))
                            elif kind=="reordered": messages[2:6]=messages[4:6]+messages[2:4]
                            elif kind=="arguments":
                                args=json.loads(messages[2]["calls"][0]["arguments"])
                                args["unexpected_history_parameter"]=True
                                messages[2]["calls"][0]["arguments"]=json.dumps(args,ensure_ascii=False)
                            elif kind=="assistant-content": messages[2]["content"]+=" changed assistant"
                            elif kind=="call-id":
                                messages[2]["calls"][0]["id"]="changed_call"
                                messages[3]["replies"][0]["id"]="changed_call"
                            elif kind=="output": messages[5]["replies"][0]["data"]+=" changed output"
                            body=json.loads(checkpoint["requestBody"]);visible=[]
                            for m in messages:
                                if m["role"]=="tool":
                                    visible.extend({"role":"tool","tool_call_id":r["id"],"content":r["data"]} for r in m["replies"])
                                else:
                                    item={"role":m["role"],"content":m["content"]}
                                    if m["calls"]: item["tool_calls"]=[{"id":c["id"],"type":"function","function":{"name":c["name"],"arguments":c["arguments"]}} for c in m["calls"]]
                                    visible.append(item)
                            body["messages"]=visible
                            checkpoint["requestBody"]=json.dumps(body,ensure_ascii=False,separators=(",",":"))
                            checkpoint["bodyDigest"]=hashlib.sha256(checkpoint["requestBody"].encode()).hexdigest()
                        change_json("step",step,"request_snapshot","request_digest",mutation)
                if kind=="arguments":
                    # Explicit pre-existing liability fixture; not a provider usage claim.
                    e.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,usage_quality,cost_quality,state) VALUES('tenant-a','"+task+":prior-unknown','"+task+"','MODEL',17,'UNKNOWN','UNKNOWN','HELD_UNKNOWN');"
                        "UPDATE task SET token_held=token_held+17,cost_held=cost_held+23 WHERE id='"+task+"';"
                        "UPDATE tenant_budget SET token_held=token_held+17,cost_held=cost_held+23 WHERE tenant_id='tenant-a';"
                        "UPDATE evaluation_budget SET cost_held=cost_held+23 WHERE id='"+run_id+"';")
                reservations=e.sql("SELECT id,state,amount,actual,usage_quality,cost_quality FROM reservation WHERE task_id='"+task+"' AND category NOT IN ('RUNNING','QUEUE') ORDER BY id;")
                balances=e.sql("SELECT models_used,tools_used,cost_held,token_held FROM task WHERE id='"+task+"';")
                run_before=e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run_id+"';")
                invocations=e.sql("SELECT id,state,input_digest,output_digest FROM invocation WHERE task_id='"+task+"' ORDER BY id;")
                (e.evidence/(run_id+"-before.json")).write_text(json.dumps({"balances":balances,"run":run_before,
                    "reservations":reservations,"invocations":invocations},indent=2),encoding="utf-8")
                if reproduce:
                    w=worker(cfg,"MODEL_RESPONSE_COMMITTED");e.barrier(w)
                    e.assert_that(len(server.calls)-calls_before==3
                        and server.calls[-1]["bodySha256"]==server.calls[calls_before]["bodySha256"]
                        and e.sql("SELECT state,requests_used FROM evaluation_budget WHERE id='"+run_id+"';")=="OPEN\t3",
                        "old product admits self-consistent step1 rollback at step3",sample=sample)
                    e.stop(w,kill=True)
                    view=e.http("GET","/api/tasks/"+task)[1]
                else:
                    w=worker(cfg);view=e.state(task,"SUCCEEDED" if positive else "FAILED");e.stop(w)
                    if positive:
                        rounds=(7 if sample=="W1" else 6)-(1 if kind.endswith("partial") else 0)
                        e.assert_that(len(server.calls)-calls_before==rounds and len(e.tls.requests)==tls_before+1
                            and len(view["artifacts"])==1,"legal step boundary survives actual worker and control restart",sample=sample,case=kind)
                        artifact=view["artifacts"][0]
                        status,body=e.http("GET","/api/artifacts/"+artifact["artifactId"]+"/download")
                        e.assert_that(status==200 and hashlib.sha256(body.encode()).hexdigest()==artifact["sha256"],
                            "positive recovered report remains exact and authorized for owner",sample=sample,case=kind)
                        (e.evidence/(run_id+"-download.md")).write_bytes(body.encode("utf-8"))
                        (e.evidence/(run_id+"-download.json")).write_text(json.dumps({"task":task,"artifact":artifact,
                            "httpStatus":status,"downloadSha256":hashlib.sha256(body.encode()).hexdigest()},indent=2),encoding="utf-8")
                    else:
                        e.assert_that(view["code"]=="EVALUATION_MATERIAL_MISMATCH" and not view["artifacts"]
                            and len(server.calls)-calls_before==expected_calls
                            and e.sql("SELECT state FROM evaluation_budget WHERE id='"+run_id+"';")=="STOPPED",
                            "incomplete or changed history durably stops before any further HTTP",sample=sample,case=kind)
                        e.assert_that(balances==e.sql("SELECT models_used,tools_used,cost_held,token_held FROM task WHERE id='"+task+"';")
                            and run_before==e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run_id+"';")
                            and invocations==e.sql("SELECT id,state,input_digest,output_digest FROM invocation WHERE task_id='"+task+"' ORDER BY id;")
                            and reservations==e.sql("SELECT id,state,amount,actual,usage_quality,cost_quality FROM reservation WHERE task_id='"+task+"' AND category NOT IN ('RUNNING','QUEUE') ORDER BY id;"),
                            "history refusal preserves fees reservations and invocation facts",sample=sample,case=kind)
                        e.assert_that(e.sql("SELECT state FROM reservation WHERE id='"+task+":run';")=="RELEASED",
                            "confirmed stopped worker releases concurrency separately from fees",sample=sample,case=kind)
                        if kind=="rollback":
                            # The persisted stop also applies after another control/worker restart.
                            other_sample="W2" if sample=="W1" else "W1"
                            other_cfg=settings(p,candidate,other_sample,run_id)
                            replace(other_cfg);follow=accept(run_id+"-same-run-follow",other_sample)
                            w=worker(other_cfg);follow_view=e.state(follow,"FAILED");e.stop(w)
                            e.assert_that(not follow_view["artifacts"] and len(server.calls)-calls_before==expected_calls
                                and e.sql("SELECT models_used,tools_used FROM task WHERE id='"+follow+"';")=="0\t0"
                                and run_before==e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run_id+"';"),
                                "persisted history stop prevents same-run follow-up effects after restart",sample=sample)
                facts.append({"historyCase":kind,"sample":sample,"task":task,"step":step,"view":view,
                    "before":{"balances":balances,"run":run_before,"reservations":reservations,"invocations":invocations},
                    "scope":"private DB checkpoint fault injection after actual worker exit" if not positive else "actual process recovery without snapshot mutation"})
                (e.evidence/"history-facts.json").write_text(json.dumps(facts,ensure_ascii=False,indent=2),encoding="utf-8")
        (e.evidence/"history-facts.json").write_text(json.dumps(facts,ensure_ascii=False,indent=2),encoding="utf-8")

    try:
        e.sql("UPDATE tenant_budget SET token_limit=1000000000,cost_limit=2000000000,model_limit=1000,running_limit=1 WHERE tenant_id='tenant-a';")
        other = e.sql("SELECT token_limit,cost_limit,model_limit,running_limit FROM tenant_budget WHERE tenant_id='tenant-b';")
        if "--history-only" in sys.argv:
            history()
            return
        if "--plans-only" not in sys.argv:
            materials()
        if materials_only:
            return
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
            calls=sum(c["case"]==run+"-W1" for c in server.calls)
            e.assert_that(calls==(1 if label in ["exact","plus-one"] else 0),"prior balance/count/stopped boundary",case=label,calls=calls,predecessor="SQL fixture, not historical account evidence")
            facts.append({"case":label,"run":e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")})

        run="concurrent-last-slot"
        provision(p,run)
        cfg=settings(p,model,"W1",run)
        replace(cfg)
        server.case=run
        def race(index):
            return e.http("POST","/api/tasks",{"goal":GOALS["W1"],"capabilityIds":["web_fetch","read","write","present"]},key=run+"-"+str(index))
        with concurrent.futures.ThreadPoolExecutor(6) as pool:
            results=list(pool.map(race,range(6)))
        accepted=[v["taskId"] for status,v in results if status==202]
        e.assert_that(len(accepted)==1 and sum(status==409 for status,v in results)==5,"concurrent keys share one immutable model/sample binding")
        e.sql("UPDATE evaluation_budget SET requests_used=62 WHERE id='"+run+"';")
        w=worker(cfg)
        other_worker=worker(cfg)
        e.state(accepted[0],"FAILED")
        e.stop(w); e.stop(other_worker)
        e.assert_that(e.sql("SELECT requests_used FROM evaluation_budget WHERE id='"+run+"';")=="63" and sum(c["case"].startswith(run) for c in server.calls)==1,"competing workers consume the final shared request once")

        for delta in [-1,0,1]:
            e.sql("UPDATE tenant_budget SET token_limit=1000000000 WHERE tenant_id='tenant-a';")
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
            e.assert_that(sum(c["case"]==run+"-W1" for c in server.calls)==(0 if delta<0 else 1),"complete-context token N-1/N/N+1 boundary",delta=delta)
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
            e.assert_that(not follow_view["artifacts"] and not any(c["case"]==run+"-after-raise" for c in server.calls)
                and e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")==before
                and e.sql("SELECT state FROM evaluation_budget WHERE id='"+run+"';")=="STOPPED",
                "raising closed allowance never reopens UNKNOWN or releases held cost",stream=stream)
            reservations=e.sql("SELECT id,state,amount,actual,usage_quality,cost_quality FROM reservation WHERE task_id='"+task+"' ORDER BY id;")
            status,rejection=e.http("POST","/api/tasks",{"goal":"unfrozen-plain-goal-no-C1","capabilityIds":["web_fetch","read","write","present"]},key=run+"-bad-material-after-unknown")
            e.assert_that(status==409 and rejection["code"]=="EVALUATION_MATERIAL_MISMATCH"
                and before==e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='"+run+"';")
                and reservations==e.sql("SELECT id,state,amount,actual,usage_quality,cost_quality FROM reservation WHERE task_id='"+task+"' ORDER BY id;"),
                "material refusal preserves prior UNKNOWN liabilities and all reservation states",stream=stream)

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
        e.assert_that(view["code"] in ["EVALUATION_CONFIG_UNAVAILABLE","EVALUATION_PHASE_LOCKED"] and not any(c["case"]=="drift-recovery-W1" for c in server.calls),"recovery rejects changed sample before HTTP")
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
        for table in ["evaluation_budget","model_dispatch","reservation","tenant_budget"]:
            (e.evidence/("material-"+table+".tsv")).write_text(e.sql("SELECT * FROM "+table+";"),encoding="utf-8")
        (e.evidence/"material-tasks.tsv").write_text(e.sql("SELECT id,state,code,HEX(goal),material_digest,material_system_digest,HEX(material_snapshot),material_file_digest,HEX(material_file_snapshot),models_used,tools_used,cost_held,token_held FROM task ORDER BY id;"),encoding="utf-8")
        (e.evidence/"material-facts.json").write_text(json.dumps(facts,ensure_ascii=False,indent=2))
        # Byte-reproducible request snapshots and complete serialized HTTP bodies.
        receipts=[]
        for row in e.sql("SELECT id,request_digest,HEX(request_snapshot) FROM step ORDER BY id;").splitlines():
            ident,digest,encoded=row.split("\t")
            snapshot=bytes.fromhex(encoded)
            assert hashlib.sha256(snapshot).hexdigest()==digest
            checkpoint=json.loads(snapshot)
            raw=checkpoint["requestBody"].encode("utf-8")
            assert hashlib.sha256(raw).hexdigest()==checkpoint["bodyDigest"]
            (e.evidence/("checkpoint-"+ident+".bin")).write_bytes(snapshot)
            (e.evidence/("body-"+ident+".bin")).write_bytes(raw)
            receipts.append({"step":ident,"snapshotSha256":digest,"bodySha256":checkpoint["bodyDigest"],"bodyBytes":len(raw)})
        (e.evidence/"body-receipts.json").write_text(json.dumps(receipts,indent=2))
        (e.evidence/"material-tls-requests.json").write_text(json.dumps(e.tls.requests,indent=2))
        if "--history-only" in sys.argv:
            (e.evidence/"history-step.tsv").write_text(e.sql("SELECT id,task_id,ordinal,state,request_digest,HEX(request_snapshot),response_digest,HEX(response_snapshot) FROM step ORDER BY task_id,ordinal;"),encoding="utf-8")
            (e.evidence/"history-invocation.tsv").write_text(e.sql("SELECT id,task_id,step_id,slot,provider_id,tool,state,input_digest,HEX(input_snapshot),output_digest,HEX(output_snapshot) FROM invocation ORDER BY task_id,step_id,slot;"),encoding="utf-8")


if __name__=="__main__":
    signal.signal(signal.SIGTERM,lambda signum,frame: (_ for _ in ()).throw(KeyboardInterrupt("Plan test terminated")))
    e=MaterialExperiment(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))
    try:
        e.prepare()
        verify(e, "--materials-only" in sys.argv)
    finally:
        e.cleanup()
