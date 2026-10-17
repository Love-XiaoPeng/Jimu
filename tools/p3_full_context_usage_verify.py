#!/usr/bin/env python3
"""Real worker/MySQL recovery with local billing fixtures; no real paid dispatch."""
import concurrent.futures
import http.server
import json
from pathlib import Path
import signal
import sys
import threading

sys.dont_write_bytecode = True

from p2_verify import Experiment
from p3_model_verify import config


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        round_number = 1 + sum(m["role"] == "tool" for m in body["messages"])
        quota = goal.startswith("quota-")
        known = "known-control" in goal or quota
        context = 131072 if goal == "quota-plus" else 32768 if quota else 8192
        usage = {"prompt_tokens": context if quota else 100,
                 "completion_tokens": 512 if quota else 50}
        usage["total_tokens"] = sum(usage.values())
        if "missing-input" in goal:
            usage = {"completion_tokens": 50, "total_tokens": 150}
        elif "missing-output" in goal:
            usage = {"prompt_tokens": 100, "total_tokens": 150}
        elif not known:
            usage = None
        finish = "stop" if known and round_number == (4 if quota else 2) else "tool_calls"
        message = {"content": "local full-context fixture"} if finish == "stop" else {
            "tool_calls": [{"id": "full_call_" + str(round_number), "type": "function", "function": {
                "name": "web_fetch", "arguments": json.dumps({"url": self.server.web + "/page/" + goal + "-" + str(round_number)})}}]}
        if "invalid" in goal:
            finish = "length"
        assert self.headers.get("Authorization") is None and self.headers.get("Cookie") is None
        assert body["max_completion_tokens"] == 502 and body["n"] == 1
        with self.server.lock:
            self.server.calls.append({"goal": goal, "stream": body["stream"], "round": round_number,
                                      "usage": usage, "plannedFinishReason": finish, "outputLimit": 502,
                                      "wireBillingReadable": "invalid" not in goal})
        if body["stream"]:
            delta = dict(message)
            if "tool_calls" in delta:
                delta["tool_calls"][0]["index"] = 0
            chunks = [{"id": "full-context-local", "model": body["model"], "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]},
                      {"choices": [], "usage": usage}]
            data = ("".join("data: " + json.dumps(c) + "\n\n" for c in chunks) + "data: [DONE]\n\n").encode()
        else:
            response = {"id": "full-context-local", "model": body["model"], "choices": [{"message": message, "finish_reason": finish}]}
            if usage is not None:
                response["usage"] = usage
            data = json.dumps(response).encode()
        if "invalid" in goal:
            # Unreadable billing reaches modelFailed(..., null), not just a ModelUsage of null components.
            data = b"data: {unreadable\n\n" if body["stream"] else b"{unreadable"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream" if body["stream"] else "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def full_context_usage(e, cases=("missing-input", "missing-output", "absent", "invalid", "known-control")):
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    server.origin = "http://127.0.0.1:" + str(server.server_port)
    server.web, server.calls, server.lock = e.fixture.origin, [], threading.Lock()
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    serial, facts = 0, []

    def replace(settings):
        nonlocal serial
        serial += 1
        e.stop(e.control)
        e.control = e.start("full-control-" + str(serial), "control", extra=settings)

    def worker(settings, point=""):
        nonlocal serial
        serial += 1
        return e.start("full-worker-" + str(serial), "worker", point, extra=settings)

    def billing(task, run):
        return {"evaluation": e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='" + run + "';"),
                "taskCost": e.sql("SELECT models_used,cost_held FROM task WHERE id='" + task + "';"),
                "dispatch": e.sql("SELECT state,code,input_tokens,output_tokens,total_tokens,reserved_cost,actual_cost,cost_quality FROM model_dispatch WHERE task_id='" + task + "';"),
                "reservation": e.sql("SELECT state,amount,actual FROM reservation WHERE task_id='" + task + "' AND category='MODEL_COST';")}

    try:
        # This trusted setup only changes private fixture DB rows, never application defaults.
        plan = json.loads((Path(__file__).parent / "evaluation/full-context-CLOSED-quota-plan.json").read_text())
        e.assert_that(plan["enabled"] is False and plan["paidDispatchEnabled"] is False and plan["searchDispatchEnabled"] is False,
                      "isolated quota plan remains closed")
        e.sql("UPDATE tenant_budget SET token_limit=659456 WHERE tenant_id='tenant-a'; INSERT INTO evaluation_budget(tenant_id,id,max_requests) VALUES('tenant-a','quota-local',8);")
        quota_tasks = []
        for candidate in plan["tasks"]:
            settings = config({"a": server}, paid=True, run_id="quota-local", fixed=0)
            p = "jimu.model.profiles.a."
            settings.update({p + "capabilities.context-limit": str(candidate["contextTokens"]),
                             p + "price.input-token-upper": str(candidate["contextTokens"]),
                             p + "price.input-micros-per-million": str(candidate["conditionalInputRateMicros"]),
                             p + "price.output-micros-per-million": str(candidate["conditionalOutputRateMicros"])})
            replace(settings)
            task = e.submit("quota-" + candidate["label"], budget={"maxModelRequests": 4, "maxToolInvocations": 6})
            # Trusted private setup while no worker exists; no model-controlled quota field.
            e.sql("UPDATE task SET max_tokens=" + str(candidate["taskTokenLimit"]) + " WHERE tenant_id='tenant-a' AND id='" + task + "' AND state='QUEUED' AND models_used=0;")
            other = e.sql("SELECT token_limit,cost_limit FROM tenant_budget WHERE tenant_id='tenant-b';")
            new = worker(settings)
            e.state(task, "SUCCEEDED"); e.stop(new)
            expected = str(candidate["taskTokenLimit"])
            e.assert_that(e.sql("SELECT max_tokens,token_held,models_used,max_cost_micros FROM task WHERE id='" + task + "';") == expected + "\t" + expected + "\t4\t1000000",
                          "four full-context reservations fit exact private task quota without widening money", candidate=candidate["label"])
            e.assert_that(e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + task + "' AND category='MODEL' AND state='SETTLED' AND amount=actual;") == "4",
                          "all four rounds retain worst-case token reservation", candidate=candidate["label"])
            e.assert_that(other == "524288\t1000000" and e.sql("SELECT token_limit,cost_limit FROM tenant_budget WHERE tenant_id='tenant-b';") == other,
                          "private quota setup leaves other tenant defaults intact", candidate=candidate["label"])
            quota_tasks.append(task)
        e.assert_that(e.sql("SELECT token_limit,token_held,cost_limit,cost_held FROM tenant_budget WHERE tenant_id='tenant-a';") == "659456\t659456\t1000000\t757764" and
                      e.sql("SELECT state,requests_used,max_requests,max_cost_micros,cost_held FROM evaluation_budget WHERE id='quota-local';") == "OPEN\t8\t8\t1000000\t757764",
                      "two local quota tasks exhaust eight slots and 659456 tokens at conditional 757764 micro-CNY")
        (e.evidence / "quota-local-facts.json").write_text(json.dumps({"plan": plan, "tasks": quota_tasks,
            "taskRows": e.sql("SELECT id,max_tokens,token_held,models_used,max_cost_micros,cost_held,model_snapshot FROM task WHERE evaluation_run_id='quota-local';"),
            "tenantRows": e.sql("SELECT tenant_id,token_limit,token_held,cost_limit,cost_held FROM tenant_budget;"),
            "run": billing(quota_tasks[-1], "quota-local")["evaluation"]}, indent=2))
        # Additional isolated runs are test cases, never new real evaluation authorization.
        e.sql("UPDATE tenant_budget SET token_limit=16777216,cost_limit=10000000 WHERE tenant_id='tenant-a';")
        for stream in [False, True]:
            for kind in cases:
                known = kind == "known-control"
                goal = "full-" + ("sse-" if stream else "json-") + kind
                run = goal
                e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','" + run + "');")
                settings = config({"a": server, "b": server}, paid=True, run_id=run, fixed=0)
                p = "jimu.model.profiles.a."
                settings.update({p + "price.input-micros-per-million": "2000000", p + "price.output-micros-per-million": "8000000"})
                for key in ["stream", "capabilities.stream-text", "capabilities.stream-tools", "capabilities.usage-stream"]:
                    settings[p + key] = str(stream).lower()
                replace(settings)
                task = e.submit(goal)
                pin = e.sql("SELECT model_digest,model_snapshot FROM task WHERE id='" + task + "';")
                snapshot = json.loads(pin.split("\t", 1)[1])
                e.assert_that("inputLimit" not in snapshot and snapshot["price"]["inputTokenUpper"] == snapshot["capabilities"]["contextLimit"] == 8192,
                              "accepted paid task pins full-context pricing without optional input gate", case=goal)
                point = "MODEL_FAILURE_COMMITTED" if kind == "invalid" else "MODEL_RESPONSE_COMMITTED"
                old = worker(settings, point)
                marker = e.barrier(old)
                before = billing(task, run)
                e.assert_that(marker.split()[:2] == [point, task] and e.sql("SELECT state,code FROM task WHERE id='" + task + "';") == "RUNNING\tNULL",
                              "actual worker paused after ledger commit before task failure", case=goal, marker=marker)
                e.assert_that(before["evaluation"] == ("OPEN\t1\t600" if known else "STOPPED\t1\t20480") and
                              before["reservation"] == ("SETTLED\t20480\t600" if known else "HELD_UNKNOWN\t20480\tNULL"),
                              "known price settles or unknown full-context upper stays held and stops run", case=goal)
                e.assert_that(before["dispatch"].startswith("RESPONDED\tNULL" if known else "USAGE_UNKNOWN\tMODEL_USAGE_UNKNOWN"),
                              "durable dispatch decision survives response and invalid-output paths", case=goal)
                e.assert_that(e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "';") == ("1" if known else "0"),
                              "unknown model output admits no tool intents", case=goal)
                e.stop(old, kill=True)
                e.sql("UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE id='" + task + "';")
                # Only default changes; the original profile and run remain available on recovery.
                recovered = dict(settings, **{"jimu.model.default-profile-ref": "b"})
                replace(recovered)
                first, second = worker(recovered), worker(recovered)
                e.expire(task)
                view = e.state(task, "SUCCEEDED" if known else "FAILED")
                e.assert_that(known or view["code"] == "MODEL_USAGE_UNKNOWN", "recovery honors persistent usage stop", case=goal, code=view["code"])
                e.assert_that(e.sql("SELECT model_digest,model_snapshot FROM task WHERE id='" + task + "';") == pin,
                              "recovery never silently changes full-context task snapshot", case=goal)
                after = billing(task, run)
                e.assert_that(known or after == before, "unknown reserves and original usage facts survive actual JVM kill/restart", case=goal)
                if known:
                    e.assert_that(after["evaluation"] == "OPEN\t2\t1200" and
                                  after["reservation"].splitlines() == ["SETTLED\t20480\t600"] * 2,
                                  "known full-context recovery remains open and settles exactly two successful turns", case=goal)
                if not known:
                    # Restore paid selection for new tasks, keeping replacement workers running.
                    replace(settings)
                    with concurrent.futures.ThreadPoolExecutor(2) as pool:
                        follows = list(pool.map(lambda i: e.submit(goal + "-after-" + str(i)), range(2)))
                    for follow in follows:
                        follow_view = e.state(follow, "FAILED")
                        e.assert_that(follow_view["code"] == "MODEL_BUDGET_EXCEEDED", "stopped run denies followup before HTTP", case=goal)
                    e.assert_that(billing(task, run) == before, "concurrent followup refusals retain original monetary hold", case=goal)
                e.stop(first); e.stop(second)
                calls = [c for c in server.calls if c["goal"].startswith(goal)]
                tools = sum(v for k, v in e.fixture.tools.items() if k.startswith("/page/" + goal))
                artifacts = e.sql("SELECT COUNT(*) FROM artifact a JOIN task t ON a.tenant_id=t.tenant_id AND a.task_id=t.id WHERE t.evaluation_run_id='" + run + "';")
                e.assert_that(len(calls) == (2 if known else 1) and tools == (1 if known else 0) and artifacts == ("1" if known else "0"),
                              "known cached tool loop completes; stopped run has no new model tool or artifact", case=goal,
                              modelRequests=len(calls), toolRequests=tools, artifacts=int(artifacts))
                facts.append({"case": goal, "task": task, "boundary": point, "marker": marker, "before": before, "after": after,
                              "modelRequests": len(calls), "toolRequests": tools, "artifacts": int(artifacts), "terminalCode": view["code"]})
                (e.evidence / "full-context-facts.json").write_text(json.dumps(facts, indent=2))
        for table in ["model_dispatch", "evaluation_budget", "reservation"]:
            (e.evidence / ("full-context-" + table + ".tsv")).write_text(e.sql("SELECT * FROM " + table + ";"))
    finally:
        (e.evidence / "full-context-model-requests.json").write_text(json.dumps(server.calls, indent=2))
        server.shutdown(); server.server_close(); thread.join(5)
        e.assert_that(not thread.is_alive(), "full-context local model thread collected")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt("Full-context test terminated")))
    e = Experiment(Path(sys.argv[1]), Path(sys.argv[2]))
    try:
        e.prepare()
        if "--failures-only" in sys.argv[3:]:
            full_context_usage(e, ("invalid",))
        else:
            full_context_usage(e)
    finally:
        e.cleanup()
