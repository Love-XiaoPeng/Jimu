#!/usr/bin/env python3
"""Local protocol/snapshot/money fixtures only. Never contacts a real paid provider."""
import http.server
import json
from pathlib import Path
import threading
import concurrent.futures


class Models(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, web_origin):
        super().__init__(("127.0.0.1", 0), ModelHandler)
        self.origin = "http://127.0.0.1:" + str(self.server_port)
        self.web_origin = web_origin
        self.calls = []
        self.lock = threading.Lock()


class ModelHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        with self.server.lock:
            self.server.calls.append({"goal": goal, "path": self.path, "model": body["model"],
                                      "authorizationAbsent": self.headers.get("Authorization") is None,
                                      "outputLimit": body.get("max_completion_tokens", body.get("max_tokens"))})
        status = 429 if goal == "core-rate-limit" else 200
        if goal.startswith("cost-") or any(m["role"] == "tool" for m in body["messages"]):
            message, reason = {"content": "local protocol fixture"}, "stop"
        else:
            message, reason = {"content": "", "tool_calls": [{"id": "call_local", "type": "function", "function": {
                "name": "web_fetch", "arguments": json.dumps({"url": self.server.web_origin + "/page/" + goal})}}]}, "tool_calls"
        response = {"id": "local-test-id", "model": body["model"], "choices": [{"message": message, "finish_reason": reason}]}
        if "unknown" not in goal:
            input_tokens = 9000 if goal == "cost-overrun" else 100
            response["usage"] = {"prompt_tokens": input_tokens, "completion_tokens": 50, "total_tokens": input_tokens + 50}
        if goal.startswith("cost-bound-"):
            if "partial-output" in goal:
                response["usage"] = {"completion_tokens": 1000}
            elif "partial-input" in goal:
                response["usage"] = {"prompt_tokens": 9000}
            elif "total-only" in goal:
                response["usage"] = {"total_tokens": 9000}
            else:
                response["usage"] = {"prompt_tokens": 100, "completion_tokens": 1000, "total_tokens": 1100}
            if "length" in goal or "truncated" in goal:
                response["choices"][0] = {"message": {"tool_calls": [{"id": "blocked_tool", "type": "function", "function": {
                    "name": "web_fetch", "arguments": json.dumps({"url": self.server.web_origin + "/page/" + goal})}}]}, "finish_reason": "length"}
        if body["stream"]:
            choice = response["choices"][0]
            delta = dict(choice["message"])
            if "tool_calls" in delta:
                delta["tool_calls"][0]["index"] = 0
            chunks = [{"id": response["id"], "model": response["model"], "choices": [{"index": 0, "delta": delta, "finish_reason": choice["finish_reason"]}]},
                      {"choices": [], "usage": response.get("usage")}]
            data = ("".join("data: " + json.dumps(c) + "\n\n" for c in chunks) +
                    ("" if "truncated" in goal else "data: [DONE]\n\n")).encode()
        else:
            data = json.dumps(response).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def config(servers, selected="a", paid=False, run_id="core-evaluation", enabled=True, fixed=50000):
    out = {"jimu.model.default-profile-ref": selected, "jimu.model.paid-dispatch-enabled": str(enabled).lower(),
           "jimu.model.evaluation-run-id": run_id}
    def flatten(prefix, value):
        for key, item in value.items():
            if isinstance(item, dict):
                flatten(prefix + key + ".", item)
            elif item is not None:
                out[prefix + key] = str(item).lower() if isinstance(item, bool) else str(item)
    for index, (name, server) in enumerate(servers.items()):
        out["jimu.model.allowed-origins[" + str(index) + "]"] = server.origin
        p = {"version": 2, "profile": name, "config-version": 1, "adapter": "openai-chat-v1",
             "base-url": server.origin + ("/compatible-mode/v1" if name == "a" else "/v1"),
             "model": "independent-local-model-snapshot-2026-10-16-" + name, "revision": "local-v1",
             "auth-mode": "none", "credential-version": 0, "transport-policy": "trusted-local-v1",
             "connect-timeout-ms": 2000, "request-timeout-ms": 20000, "response-bytes": 1048576,
             "stream": False, "max-output-tokens": 512, "output-limit-field": "max_completion_tokens", "output-margin-tokens": 10,
             "capabilities": {"call": True, "tools": True, "usage-call": True, "context-limit": 8192, "evidence": "local-test-v1"}, "paid": paid}
        if paid:
            p["price"] = {"version": "synthetic-v1", "currency": "CNY", "input-micros-per-million": 0,
                          "output-micros-per-million": 0, "fixed-micros": fixed, "input-token-upper": 8192,
                          "bound-evidence": "synthetic-local-context-and-total-output-v1"}
        flatten("jimu.model.profiles." + name + ".", p)
    return out


def model_core(e, bounds_only=False):
    servers = {name: Models(e.fixture.origin) for name in ("a", "b")}
    threads = [threading.Thread(target=s.serve_forever) for s in servers.values()]
    for thread in threads:
        thread.start()
    e.stop(e.control)
    e.sql("UPDATE tenant_budget SET model_limit=1000,token_limit=16777216;")
    serial = 0
    def control(settings):
        nonlocal serial
        serial += 1
        e.control = e.start("core-control-" + str(serial), "control", extra=settings)
    def worker(settings, point=""):
        nonlocal serial
        serial += 1
        return e.start("core-worker-" + str(serial), "worker", point, extra=settings)
    def count(goal):
        return sum(c["goal"] == goal for s in servers.values() for c in s.calls)
    def replace(settings):
        e.stop(e.control)
        control(settings)
    def bound_regressions():
        # R1/R2: each known component stops dispatch independently of executable output.
        cases = ("partial-output", "partial-input", "length-overrun", "stream-partial-output",
                 "stream-partial-input", "stream-truncated-overrun", "total-only")
        e.sql("UPDATE tenant_budget SET cost_limit=10000000 WHERE tenant_id='tenant-a';")
        for case in cases:
            run_id = "bound-" + case
            e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','" + run_id + "');")
            settings = config(servers, paid=True, run_id=run_id)
            if case.startswith("stream-"):
                for name in servers:
                    prefix = "jimu.model.profiles." + name + "."
                    for key in ("stream", "capabilities.stream-text", "capabilities.stream-tools", "capabilities.usage-stream"):
                        settings[prefix + key] = "true"
            replace(settings)
            goal = "cost-bound-" + case
            task = e.submit(goal)
            new = worker(settings)
            e.state(task, "FAILED")
            e.stop(new)
            e.assert_that(e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';") == "STOPPED\t1\t50000",
                          "known usage violation durably stops run and retains charge", case=case)
            expected = ("NULL\t1000\tNULL" if "partial-output" in case else "9000\tNULL\tNULL" if "partial-input" in case else
                        "NULL\tNULL\t9000" if case == "total-only" else "100\t1000\t1100")
            e.assert_that(e.sql("SELECT input_tokens,output_tokens,total_tokens FROM model_dispatch WHERE task_id='" + task + "';") == expected,
                          "billing components survive independent of response executability", case=case)
            e.assert_that(e.sql("SELECT state,code FROM model_dispatch WHERE task_id='" + task + "';") == "BOUND_EXCEEDED\tMODEL_COST_BOUND_EXCEEDED",
                          "dispatch persists explicit bound violation", case=case)
            unknown = "partial" in case or case == "total-only"
            e.assert_that(e.sql("SELECT actual_cost,cost_quality FROM model_dispatch WHERE task_id='" + task + "';") == ("NULL\tUNKNOWN" if unknown else "50000\tKNOWN"),
                          "missing component retains UNKNOWN monetary reservation", case=case)
            e.assert_that(e.sql("SELECT state,actual FROM reservation WHERE task_id='" + task + "' AND category='MODEL_COST';") == ("HELD_UNKNOWN\tNULL" if unknown else "SETTLED\t50000"),
                          "billing reservation remains reconcilable", case=case)
            if "length" in case or "truncated" in case:
                e.assert_that(json.loads(e.sql("SELECT usage_snapshot FROM model_dispatch WHERE task_id='" + task + "';"))["finishReason"] == "length",
                              "rejected response retains finish reason", case=case)
            e.assert_that(e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "';") == "0" and
                          e.fixture.tools.get("/page/" + goal, 0) == 0, "overrun output never executes a tool", case=case)
            # Kill all application processes, then restart control and two competing workers.
            replace(settings)
            new, other = worker(settings), worker(settings)
            with concurrent.futures.ThreadPoolExecutor(4) as pool:
                after = list(pool.map(lambda i: e.submit(goal + "-after-" + str(i)), range(4)))
            for following in after:
                e.state(following, "FAILED")
            e.stop(new); e.stop(other)
            e.assert_that(all(count(goal + "-after-" + str(i)) == 0 for i in range(4)) and count(goal) == 1,
                          "persisted stop blocks concurrent requests after process restart", case=case)
            e.assert_that(e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='" + run_id + "';") == "STOPPED\t1\t50000",
                          "concurrent denied dispatches never alter stopped run budget", case=case)
            e.assert_that(e.sql("SELECT COUNT(*) FROM task WHERE evaluation_run_id='" + run_id + "' AND code='MODEL_BUDGET_EXCEEDED';") == "4",
                          "all concurrent attempts fail at durable budget boundary", case=case)
        (e.evidence / "bound-model-facts.json").write_text(json.dumps([c for s in servers.values() for c in s.calls if c["goal"].startswith("cost-bound-")], indent=2))
        for table in ("model_dispatch", "evaluation_budget"):
            (e.evidence / ("bound-" + table + ".tsv")).write_text(e.sql("SELECT * FROM " + table + ";"))
    try:
        if bounds_only:
            control(config(servers))
            bound_regressions()
            return
        a, b = config(servers, "a"), config(servers, "b")
        # Queue/M0/M1/M2/T1 recover across default changes; all later steps retain A.
        for point in ("QUEUED", "M0", "M1", "M2", "T1"):
            control(a)
            goal = "core-pin-" + point.lower()
            task = e.submit(goal)
            old = None
            if point != "QUEUED":
                old = worker(a, point)
                e.barrier(old)
                e.stop(old, kill=True)
                e.expire(task)
            replace(b)
            # Same idempotency key after switching default must return the original task.
            status, retry = e.http("POST", "/api/tasks", {"goal": goal}, key=goal)
            e.assert_that(status == 202 and retry["taskId"] == task, "model default change preserves idempotent task", boundary=point)
            new = worker(b)
            e.state(task, "SUCCEEDED")
            e.stop(new)
            facts = [c for s in servers.values() for c in s.calls if c["goal"] == goal]
            e.assert_that(len(facts) == 2 and all(c["model"].endswith("-a") and c["path"] == "/compatible-mode/v1/chat/completions" for c in facts),
                          "all recovered steps retain original model and path", boundary=point)
            e.assert_that(e.fixture.tools.get("/page/" + goal) == 1, "model config recovery never replays successful tool", boundary=point)
            e.stop(e.control)
        control(b)
        task = e.submit("core-new-default")
        new = worker(b)
        e.state(task, "SUCCEEDED")
        e.stop(new)
        e.assert_that(all(c["model"].endswith("-b") for c in servers["b"].calls if c["goal"] == "core-new-default"), "new tasks use new default endpoint")
        e.sql("UPDATE task SET model_snapshot=NULL,model_digest=NULL,request_snapshot=JSON_REMOVE(CAST(request_snapshot AS JSON),'$.budget.maxCostMicros') WHERE id='" + task + "'; UPDATE task SET request_digest=SHA2(request_snapshot,256) WHERE id='" + task + "';")
        status, historical = e.http("POST", "/api/tasks", {"goal": "core-new-default"}, key="core-new-default")
        e.assert_that(status == 202 and historical["taskId"] == task and historical["state"] == "SUCCEEDED", "completed legacy request without monetary field remains readable and idempotent")

        # Historical completed P2 rows remain readable; old unfinished rows cannot invent a profile.
        missing = e.submit("core-snapshot-missing")
        e.sql("UPDATE task SET model_snapshot=NULL,model_digest=NULL WHERE id='" + missing + "';")
        new = worker(b)
        e.state(missing, "FAILED")
        e.stop(new)
        e.assert_that(count("core-snapshot-missing") == 0 and e.sql("SELECT code FROM task WHERE id='" + missing + "';") == "MODEL_SNAPSHOT_MISSING", "missing legacy snapshot blocks HTTP")

        missing_profile = e.submit("core-profile-removed")
        only_a = {k: v for k, v in a.items() if not k.startswith("jimu.model.profiles.b.")}
        replace(only_a)
        new = worker(only_a)
        e.state(missing_profile, "FAILED")
        e.stop(new)
        e.assert_that(count("core-profile-removed") == 0 and e.sql("SELECT code FROM task WHERE id='" + missing_profile + "';") == "MODEL_CONFIG_UNAVAILABLE", "removed old profile cannot fall back")

        replace(b)
        failed = e.submit("core-rate-limit")
        new = worker(b)
        e.state(failed, "FAILED")
        e.stop(new)
        e.assert_that(count("core-rate-limit") == 1 and e.sql("SELECT code FROM task WHERE id='" + failed + "';") == "MODEL_RATE_LIMITED", "429 has safe failure code and zero retries")

        # The paid policy is exercised only against these independent loopback fixtures.
        e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','core-evaluation');")
        blocked = config(servers, paid=True, enabled=False)
        replace(blocked)
        status, rejection = e.http("POST", "/api/tasks", {"goal": "cost-disabled"}, key="cost-disabled")
        e.assert_that(status == 503 and rejection["code"] == "MODEL_COST_BOUND_UNAVAILABLE" and count("cost-disabled") == 0, "disabled paid policy rejects before admission and HTTP")
        paid_a, paid_b = config(servers, "a", True), config(servers, "b", True)
        replace(paid_a)
        tasks = []
        for i in range(10):
            tasks.append(e.submit("cost-first-" + str(i)))
        replace(paid_b)
        # Two workers compete for a shared durable cap across two candidate snapshots.
        new, other = worker(paid_b), worker(paid_b)
        for task in tasks:
            e.state(task, "SUCCEEDED")
        with concurrent.futures.ThreadPoolExecutor(4) as pool:
            submitted = list(pool.map(lambda i: e.submit("cost-second-" + ("unknown" if i == 0 else str(i))), range(10)))
        for task in submitted:
            e.state(task, "SUCCEEDED")
        final = e.submit("cost-21st")
        e.state(final, "FAILED")
        e.stop(new); e.stop(other)
        e.assert_that(count("cost-21st") == 0 and e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='core-evaluation';") == "20\t1000000", "20 requests and one CNY shared across candidates workers and restart")
        e.assert_that(e.sql("SELECT COUNT(*) FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.goal='cost-second-unknown' AND d.actual_cost IS NULL AND d.cost_quality='UNKNOWN' AND d.reserved_cost=50000;") == "1", "unknown cost retains monetary upper bound")
        e.assert_that(e.sql("SELECT COUNT(*) FROM reservation r JOIN task t ON t.id=r.task_id AND t.tenant_id=r.tenant_id WHERE t.goal='cost-second-unknown' AND r.category='MODEL_COST' AND r.state='HELD_UNKNOWN' AND r.actual IS NULL;") == "1", "unknown reservation is not released or settled to zero")

        # Isolate the currency cap from the request cap, then force an actual bound violation.
        e.sql("UPDATE tenant_budget SET cost_limit=10000000 WHERE tenant_id='tenant-a'; INSERT INTO evaluation_budget(tenant_id,id,max_cost_micros) VALUES('tenant-a','core-small',100000);")
        small = config(servers, paid=True, run_id="core-small")
        replace(small)
        for goal in ("cost-small-1", "cost-small-2", "cost-small-rejected"):
            task = e.submit(goal)
            new = worker(small)
            e.state(task, "FAILED" if goal.endswith("rejected") else "SUCCEEDED")
            e.stop(new)
        e.assert_that(count("cost-small-rejected") == 0 and e.sql("SELECT requests_used,cost_held FROM evaluation_budget WHERE id='core-small';") == "2\t100000", "currency cap stops dispatch with request slots remaining")
        e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','core-overrun');")
        overrun = config(servers, paid=True, run_id="core-overrun")
        replace(overrun)
        tightened = e.submit("cost-task-tightened", budget={"maxCostMicros": 1})
        new = worker(overrun)
        e.state(tightened, "FAILED")
        e.stop(new)
        e.assert_that(count("cost-task-tightened") == 0, "caller monetary budget can only tighten the dispatch cap")
        task = e.submit("cost-overrun")
        new = worker(overrun)
        e.state(task, "FAILED")
        e.stop(new)
        after = e.submit("cost-after-overrun")
        new = worker(overrun)
        e.state(after, "FAILED")
        e.stop(new)
        e.assert_that(e.sql("SELECT state FROM evaluation_budget WHERE id='core-overrun';") == "STOPPED" and count("cost-after-overrun") == 0,
                      "observed usage bound violation persists and stops subsequent requests")
        bound_regressions()
        facts = [c for s in servers.values() for c in s.calls]
        e.assert_that(all(c["authorizationAbsent"] and c["outputLimit"] == 502 for c in facts), "all local model requests omit auth and honor 512 token bound with 10 token margin")
        (e.evidence / "local-model-facts.json").write_text(json.dumps(facts, indent=2))
        for table in ("model_dispatch", "evaluation_budget"):
            (e.evidence / (table + ".tsv")).write_text(e.sql("SELECT * FROM " + table + ";"))
    finally:
        for server in servers.values():
            server.shutdown(); server.server_close()
        for thread in threads:
            thread.join(timeout=5)
        assert not any(t.is_alive() for t in threads)


if __name__ == "__main__":
    import sys
    from p2_verify import Experiment
    experiment = Experiment(Path(sys.argv[1]), Path(sys.argv[2]))
    try:
        experiment.prepare()
        model_core(experiment, "--bounds-only" in sys.argv[3:])
    finally:
        experiment.cleanup()
