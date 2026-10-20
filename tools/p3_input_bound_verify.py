#!/usr/bin/env python3
"""Complete-input gate evidence using a SYNTHETIC billing unit, never Qwen token claims."""
import hashlib
import http.server
import json
from pathlib import Path
import signal
import sys
import threading

sys.dont_write_bytecode = True

from p2_verify import Experiment, Handler, eventually
from p3_model_verify import config


class GrowthWeb(Handler):
    def do_GET(self):
        if self.path.startswith("/page/input-growth-"):
            assert self.headers.get("Authorization") in ["Bearer " + v for v in self.server.credentials.values()]
            with self.server.lock:
                self.server.tools[self.path] = self.server.tools.get(self.path, 0) + 1
            self.send(200, ("actual fixture tool result " + "x" * 7600).encode(), "text/plain")
        else:
            super().do_GET()


class InputModels(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, origin):
        super().__init__(("127.0.0.1", 0), InputHandler)
        self.origin = "http://127.0.0.1:" + str(self.server_port)
        self.web = origin
        self.calls = []


class InputHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        body = json.loads(raw)
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        label = goal.split("|", 1)[0]
        has_tool = any(m["role"] == "tool" for m in body["messages"])
        assert len(raw) <= 8192 and body["max_completion_tokens"] == 502 and body["n"] == 1
        assert self.headers.get("Authorization") is None and self.headers.get("Cookie") is None
        self.server.calls.append({"label": label, "bodyBytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                                  "path": self.path, "stream": body["stream"], "hasTool": has_tool,
                                  "messageRoles": [m["role"] for m in body["messages"]],
                                  "tools": [t["function"]["name"] for t in body["tools"]],
                                  "outputLimit": body["max_completion_tokens"], "billingContract": "synthetic complete raw UTF-8 octets; zero hidden overhead"})
        if label.startswith("input-bound-"):
            message, finish = {"role": "assistant", "content": "synthetic boundary complete"}, "stop"
        elif has_tool:
            message, finish = {"role": "assistant", "content": "synthetic tool result verified"}, "stop"
        else:
            message = {"role": "assistant", "content": "", "tool_calls": [{"id": "input_call", "type": "function", "function": {
                "name": "web_fetch", "arguments": json.dumps({"url": self.server.web + "/page/" + label})}}]}
            finish = "tool_calls"
        usage = {"prompt_tokens": 8193 if "input-overrun" in label else len(raw),
                 "completion_tokens": 513 if "output-overrun" in label else 50}
        usage["total_tokens"] = sum(usage.values())
        if "unknown" in label:
            usage = None
        if body["stream"]:
            delta = dict(message)
            if "tool_calls" in delta:
                delta["tool_calls"][0]["index"] = 0
            chunks = [{"id": "synthetic-input", "model": body["model"], "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]},
                      {"choices": [], "usage": usage}]
            data = ("".join("data: " + json.dumps(c) + "\n\n" for c in chunks) + "data: [DONE]\n\n").encode()
            media = "text/event-stream"
        else:
            response = {"id": "synthetic-input", "model": body["model"], "choices": [{"index": 0, "message": message, "finish_reason": finish}]}
            if usage is not None:
                response["usage"] = usage
            data, media = json.dumps(response).encode(), "application/json"
        self.send_response(200)
        self.send_header("Content-Type", media)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def input_bounds(e):
    e.fixture.RequestHandlerClass = GrowthWeb
    server = InputModels(e.fixture.origin)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    serial = 0
    settings = config({"a": server, "b": server}, paid=True, fixed=0, run_id="input-main")
    for name in ["a", "b"]:
        p = "jimu.model.profiles." + name + "."
        settings.update({p + "model": "local-byte-accounted-v1", p + "revision": "local-byte-accounted-v1",
                         p + "capabilities.context-limit": "1000000", p + "input-limit.version": "1",
                         p + "input-limit.max-tokens": "8192", p + "input-limit.proof": "test-whole-request-utf8-v1",
                         p + "price.input-micros-per-million": "2000000", p + "price.output-micros-per-million": "8000000"})
    e.sql("UPDATE tenant_budget SET model_limit=1000,token_limit=16777216;")
    def replace(value):
        nonlocal serial
        serial += 1
        e.stop(e.control)
        e.control = e.start("input-control-" + str(serial), "control", extra=value)
    def worker(value, boundary=""):
        nonlocal serial
        serial += 1
        return e.start("input-worker-" + str(serial), "worker", boundary, extra=value)
    def accept(label, goal=None):
        status, view = e.http("POST", "/api/tasks", {"goal": goal or label, "budget": {"maxModelRequests": 3, "maxToolInvocations": 2, "deadlineSeconds": 180}}, key=label)
        e.assert_that(status == 202, "input task accepted", label=label, status=status)
        return view["taskId"]
    def count(label):
        return len([c for c in server.calls if c["label"] == label])
    def denied(task, code, label):
        view = e.state(task, "FAILED")
        e.assert_that(view["code"] == code, "input refusal code is explicit", label=label, code=view["code"])
    def run(value, label, expected="SUCCEEDED", goal=None):
        replace(value)
        task = accept(label, goal)
        process = worker(value)
        if expected == "SUCCEEDED":
            e.state(task, expected)
        else:
            denied(task, expected, label)
        e.stop(process)
        return task
    def mode(stream=False, run_id="input-main"):
        value = dict(settings, **{"jimu.model.evaluation-run-id": run_id})
        for key in ["stream", "capabilities.stream-text", "capabilities.stream-tools", "capabilities.usage-stream"]:
            value["jimu.model.profiles.a." + key] = str(stream).lower()
        value["jimu.model.profiles.a.config-version"] = "2" if stream else "1"
        e.sql("INSERT IGNORE INTO evaluation_budget(tenant_id,id) VALUES('tenant-a','" + run_id + "');")
        return value
    try:
        normal = mode()
        calibration = "input-bound-calibrate"
        run(normal, calibration)
        constant = next(c["bodyBytes"] for c in server.calls if c["label"] == calibration) - len(calibration)
        for stream in [False, True]:
            value = mode(stream)
            # SSE has an extra stream_options field; calibrate that exact serialized mode.
            label = "input-bound-calibrate-" + str(stream)
            run(value, label)
            constant = next(c["bodyBytes"] for c in server.calls if c["label"] == label) - len(label)
            for size in [8191, 8192, 8193]:
                label = "input-bound-" + str(size) + "-" + str(stream)
                goal = label + "|" + "x" * (size - constant - len(label) - 1)
                task = run(value, label, "SUCCEEDED" if size <= 8192 else "MODEL_INPUT_LIMIT_EXCEEDED", goal)
                e.assert_that(count(label) == (1 if size <= 8192 else 0), "complete request N-1 N N+1 downstream count", mode=stream, bytes=size)
                measured = e.sql("SELECT OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(request_snapshot,'$.requestBody'))) FROM step WHERE task_id='" + task + "';")
                e.assert_that(measured == str(size), "persisted complete wire includes Schema and all messages", bytes=size)
                if size > 8192:
                    e.assert_that(e.sql("SELECT COUNT(*) FROM model_dispatch WHERE task_id='" + task + "';") == "0", "rejected input has no dispatch or monetary charge")
            label = "input-normal-" + str(stream)
            task = run(value, label)
            e.assert_that(count(label) == 2 and e.fixture.tools.get("/page/" + label) == 1, "ordinary and SSE actual tool loop within input bound", stream=stream)
            label = "input-growth-" + str(stream)
            task = run(value, label, "MODEL_INPUT_LIMIT_EXCEEDED")
            measured = e.sql("SELECT OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(request_snapshot,'$.requestBody'))),dispatches FROM step WHERE task_id='" + task + "' ORDER BY ordinal DESC LIMIT 1;").split("\t")
            e.assert_that(int(measured[0]) > 8192 and measured[1] == "0" and count(label) == 1, "tool result growth is refused before second HTTP", stream=stream, bytes=measured[0])

        # Same pinned profile remains available; changing only the default cannot lower old limits.
        normal = mode()
        replace(normal)
        label = "input-bound-recover-m0"
        goal = label + "|" + "x" * 5000
        task = accept(label, goal)
        old = worker(normal, "M0"); e.barrier(old); e.stop(old, kill=True); e.expire(task)
        changed = dict(normal, **{"jimu.model.default-profile-ref": "b", "jimu.model.profiles.b.input-limit.max-tokens": "4096", "jimu.model.profiles.b.price.input-token-upper": "4096"})
        replace(changed)
        new = accept("input-bound-new-b", "input-bound-new-b|" + "x" * 5000)
        fresh = worker(changed); e.state(task, "SUCCEEDED"); denied(new, "MODEL_INPUT_LIMIT_EXCEEDED", "new-b"); e.stop(fresh)
        e.assert_that(count(label) == 1 and count("input-bound-new-b") == 0, "M0 recovery pins original 8192 while new default uses 4096")
        e.assert_that(json.loads(e.sql("SELECT model_snapshot FROM task WHERE id='" + task + "';"))["inputLimit"]["maxTokens"] == 8192, "input policy is persisted in immutable task snapshot")

        replace(normal)
        task = accept("input-recover-t1")
        old = worker(normal, "T1"); e.barrier(old); e.stop(old, kill=True); e.expire(task)
        fresh = worker(normal); e.state(task, "SUCCEEDED"); e.stop(fresh)
        e.assert_that(count("input-recover-t1") == 2 and e.fixture.tools.get("/page/input-recover-t1") == 1, "T1 recovery rechecks growing full input without repeating successful tool")

        replace(normal)
        task = accept("input-bound-policy-change")
        old = worker(normal, "M0"); e.barrier(old); e.stop(old, kill=True); e.expire(task)
        incompatible = dict(normal, **{"jimu.model.profiles.a.input-limit.max-tokens": "4096", "jimu.model.profiles.a.price.input-token-upper": "4096"})
        replace(incompatible)
        fresh = worker(incompatible); denied(task, "MODEL_CONFIG_UNAVAILABLE", "policy-change"); e.stop(fresh)
        e.assert_that(count("input-bound-policy-change") == 0, "recovery refuses changed proof/limit instead of replacing accepted snapshot")

        unproven = dict(normal, **{"jimu.model.profiles.a.input-limit.proof": "unverified-provider-tokenizer"})
        task = run(unproven, "input-unproven", "MODEL_INPUT_BOUND_UNAVAILABLE")
        e.assert_that(count("input-unproven") == 0 and e.sql("SELECT COUNT(*) FROM model_dispatch WHERE task_id='" + task + "';") == "0", "unproven upper has zero downstream dispatch")
        full = {k: v for k, v in normal.items() if not k.startswith("jimu.model.profiles.a.input-limit.")}
        full.update({"jimu.model.profiles.a.price.input-token-upper": "1000000", "jimu.model.profiles.a.price.input-micros-per-million": "6000000", "jimu.model.profiles.a.price.output-micros-per-million": "24000000"})
        task = run(full, "input-full-context", "MODEL_BUDGET_EXCEEDED")
        e.assert_that(count("input-full-context") == 0, "full-context fallback remains too expensive for one CNY and never dispatches")

        for stream in [False, True]:
            value = mode(stream, "input-unknown-" + str(stream))
            replace(value)
            label = "input-unknown-" + str(stream)
            task = accept(label)
            old = worker(value, "MODEL_RESPONSE_COMMITTED"); e.barrier(old)
            e.assert_that(e.sql("SELECT state,code FROM model_dispatch WHERE task_id='" + task + "';") == "USAGE_UNKNOWN\tMODEL_USAGE_UNKNOWN", "unknown usage decision persists before task failure", stream=stream)
            e.assert_that(e.sql("SELECT state,amount,actual FROM reservation WHERE task_id='" + task + "' AND category='MODEL_COST';") == "HELD_UNKNOWN\t20480\tNULL", "unknown usage retains entire proven input and output monetary reserve", stream=stream)
            e.stop(old, kill=True); e.expire(task); replace(value)
            fresh = worker(value); denied(task, "MODEL_USAGE_UNKNOWN", label); e.stop(fresh)
            after = run(value, label + "-after", "MODEL_BUDGET_EXCEEDED")
            e.assert_that(count(label) == 1 and count(label + "-after") == 0 and e.fixture.tools.get("/page/" + label, 0) == 0, "unknown usage stops run across process restart without tool execution or replay", stream=stream)
        for kind in ["input-overrun", "output-overrun"]:
            value = mode(False, "input-" + kind)
            label = "input-" + kind
            task = run(value, label, "MODEL_COST_BOUND_EXCEEDED")
            e.assert_that(count(label) == 1 and e.fixture.tools.get("/page/" + label, 0) == 0 and e.sql("SELECT state FROM evaluation_budget WHERE id='input-" + kind + "';") == "STOPPED", "observed input/output violations stop evaluation without executing output", kind=kind)
        (e.evidence / "input-model-facts.json").write_text(json.dumps(server.calls, indent=2))
        (e.evidence / "input-settings.json").write_text(json.dumps(normal, indent=2))
        for table in ["model_dispatch", "evaluation_budget"]:
            (e.evidence / ("input-" + table + ".tsv")).write_text(e.sql("SELECT * FROM " + table + ";"))
    finally:
        (e.evidence / "input-model-facts.json").write_text(json.dumps(server.calls, indent=2))
        server.shutdown(); server.server_close(); thread.join(5)
        e.assert_that(not thread.is_alive(), "synthetic byte-accounted service stopped")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt("Input test terminated")))
    e = Experiment(Path(sys.argv[1]), Path(sys.argv[2]))
    try:
        e.prepare()
        input_bounds(e)
    finally:
        e.cleanup()
