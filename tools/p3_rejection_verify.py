#!/usr/bin/env python3
"""Exit only at a verified committed billing fact, before WorkerLoop.stop; local fixtures only."""
import concurrent.futures
import http.server
import json
from pathlib import Path
import threading


CASES = ("json-stop-unknown", "json-tool-known", "sse-stop-known", "sse-tool-unknown",
         "json-length-within", "sse-no-done-within", "sse-no-done-overrun")


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        with self.server.lock:
            self.server.calls.append(goal)
        original = goal.removeprefix("r3-") in CASES
        usage = {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
        reason, message = "stop", {"content": "local billing recovery fixture"}
        if original:
            if goal.endswith("stop-unknown"):
                usage = {"completion_tokens": 1000}
            elif goal.endswith("tool-unknown"):
                usage = {"prompt_tokens": 9000}
            elif not goal.endswith("within"):
                usage = {"prompt_tokens": 100, "completion_tokens": 1000, "total_tokens": 1100}
            if "-tool-" in goal or "-length-" in goal or "-no-done-" in goal:
                message = {"tool_calls": [{"id": "r3_tool", "type": "function", "function": {
                    "name": "web_fetch", "arguments": json.dumps({"url": self.server.web_origin + "/page/" + goal})}}]}
                reason = "length" if "-length-" in goal else "tool_calls"
        if body["stream"]:
            delta = dict(message)
            if "tool_calls" in delta:
                delta["tool_calls"][0]["index"] = 0
            chunks = [{"id": "r3-local", "model": body["model"], "choices": [{"index": 0, "delta": delta, "finish_reason": reason}]},
                      {"choices": [], "usage": usage}]
            data = ("".join("data: " + json.dumps(c) + "\n\n" for c in chunks) +
                    ("" if original and "-no-done-" in goal else "data: [DONE]\n\n")).encode()
        else:
            data = json.dumps({"id": "r3-local", "model": body["model"], "choices": [{"message": message, "finish_reason": reason}], "usage": usage}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def rejection_recovery(e, old_expected=False):
    from p3_model_verify import config
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    server.origin = "http://127.0.0.1:" + str(server.server_port)
    server.web_origin, server.calls, server.lock = e.fixture.origin, [], threading.Lock()
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    facts = []
    e.stop(e.control)

    def billing(task, run):
        return {"evaluation": e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='" + run + "';"),
                "taskCost": e.sql("SELECT models_used,cost_held FROM task WHERE id='" + task + "';"),
                "dispatch": e.sql("SELECT state,code,input_tokens,output_tokens,total_tokens,usage_snapshot,reserved_cost,actual_cost,cost_quality FROM model_dispatch WHERE task_id='" + task + "';"),
                "reservation": e.sql("SELECT state,amount,actual,usage_quality,cost_quality FROM reservation WHERE task_id='" + task + "' AND category='MODEL_COST';")}

    try:
        e.sql("UPDATE tenant_budget SET model_limit=1000,token_limit=16777216,cost_limit=10000000 WHERE tenant_id='tenant-a';")
        for i, case in enumerate(CASES):
            goal, run = "r3-" + case, "rejection-" + case
            invalid = "length" in case or "no-done" in case
            within, unknown = case.endswith("within"), case.endswith("unknown")
            e.sql("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','" + run + "');")
            settings = config({"a": server}, paid=True, run_id=run)
            settings["jimu.model.profiles.a.price.input-micros-per-million"] = "2000000"
            settings["jimu.model.profiles.a.price.output-micros-per-million"] = "8000000"
            if case.startswith("sse-"):
                for field in ("stream", "capabilities.stream-text", "capabilities.stream-tools", "capabilities.usage-stream"):
                    settings["jimu.model.profiles.a." + field] = "true"
            e.control = e.start("rejection-control-" + str(i), "control", extra=settings)
            task = e.submit(goal)
            point = "MODEL_FAILURE_COMMITTED" if invalid else "MODEL_RESPONSE_COMMITTED"
            old = e.start("rejection-exit-" + str(i), "worker", point, extra=settings)
            marker = e.barrier(old)
            e.assert_that(marker.split()[:2] == [point, task], "exact commit boundary reached for original task", case=case)
            e.assert_that(e.sql("SELECT state,code FROM task WHERE id='" + task + "';") == "RUNNING\tNULL",
                          "billing committed before task failure", case=case)
            before = billing(task, run)
            held = 70480 if unknown else 50600 if within else 58200
            e.assert_that(before["evaluation"] == ("OPEN" if within else "STOPPED") + "\t1\t" + str(held),
                          "committed nonzero price or UNKNOWN upper bound observed", case=case)
            cached = e.sql("SELECT state,response_snapshot IS NOT NULL FROM step WHERE task_id='" + task + "';")
            pending = e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "';")
            e.assert_that(cached.endswith("\t1") if old_expected and not invalid else cached.endswith("\t0"),
                          "executable cache disposition observed at commit boundary", case=case)
            e.assert_that(pending == ("1" if old_expected and "-tool-" in goal else "0"),
                          "tool intent disposition observed at commit boundary", case=case, count=int(pending))
            e.stop(old, kill=True)
            # Pin the dead lease until both replacement workers are ready, then race recovery.
            e.sql("UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE id='" + task + "';")
            e.stop(e.control)
            e.control = e.start("rejection-restarted-control-" + str(i), "control", extra=settings)
            first = e.start("rejection-recover-a-" + str(i), "worker", extra=settings)
            second = e.start("rejection-recover-b-" + str(i), "worker", extra=settings)
            e.expire(task)
            expected_state = "SUCCEEDED" if old_expected and not invalid and "-stop-" in goal else "FAILED"
            view = e.state(task, expected_state)
            expected_code = (None if expected_state == "SUCCEEDED" else "MODEL_BUDGET_EXCEEDED") if old_expected else (
                "MODEL_STREAM_INCOMPLETE" if within and case.startswith("sse-") else "MODEL_RESPONSE_INVALID" if within else "MODEL_COST_BOUND_EXCEEDED")
            e.assert_that(view["code"] == expected_code, "recovery terminal reason matches durable decision", case=case, state=view["state"], code=view["code"])
            tool_count = e.fixture.tools.get("/page/" + goal, 0)
            e.assert_that(tool_count == (1 if old_expected and "-tool-" in goal else 0),
                          "recovery tool effects observed", case=case, count=tool_count)
            e.assert_that(not view["artifacts"] and e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + task + "';") == "0",
                          "recovery produces no artifact from rejected output", case=case)
            after = billing(task, run)
            e.assert_that(after == before and server.calls.count(goal) == 1,
                          "original billing unchanged and original model request never repeated", case=case)
            with concurrent.futures.ThreadPoolExecutor(4) as pool:
                tasks = list(pool.map(lambda n: e.submit(goal + "-after-" + str(n)), range(4)))
            for follow in tasks:
                e.state(follow, "SUCCEEDED" if within else "FAILED")
            e.stop(first); e.stop(second)
            follow_count = sum(c.startswith(goal + "-after-") for c in server.calls)
            expected_evaluation = "OPEN\t5\t253000" if within else before["evaluation"]
            e.assert_that(follow_count == (4 if within else 0) and e.sql("SELECT state,requests_used,cost_held FROM evaluation_budget WHERE id='" + run + "';") == expected_evaluation,
                          "concurrent followups honor stopped run or continue unaffected open run", case=case)
            e.assert_that(billing(task, run)["dispatch"] == before["dispatch"] and billing(task, run)["reservation"] == before["reservation"],
                          "subsequent work never re-settles original dispatch", case=case)
            facts.append({"case": case, "boundary": point, "marker": marker, "task": task,
                          "atCommit": before, "stepAtCommit": cached, "invocationsAtCommit": pending,
                          "afterRecovery": after, "taskStateAfterRecovery": view["state"], "taskCodeAfterRecovery": view["code"],
                          "toolRequests": tool_count, "followupModelRequests": follow_count, "evaluationAfterFollowups": expected_evaluation})
            (e.evidence / "rejection-recovery-facts.json").write_text(json.dumps(facts, indent=2))
            e.stop(e.control)
        (e.evidence / "rejection-model-requests.json").write_text(json.dumps(server.calls, indent=2))
    finally:
        server.shutdown(); server.server_close(); thread.join(5)
        (e.evidence / "rejection-http-cleanup.json").write_text(json.dumps({"modelFixtureThreadExited": not thread.is_alive()}))
        assert not thread.is_alive()


if __name__ == "__main__":
    import sys
    from p2_verify import Experiment
    e = Experiment(Path(sys.argv[1]), Path(sys.argv[2]))
    try:
        e.prepare()
        rejection_recovery(e, "--expect-old" in sys.argv[3:])
    finally:
        e.cleanup()
