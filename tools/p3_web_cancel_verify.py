"""Cancellation metadata only; owned MySQL, local TLS and model substitutes."""
import argparse
import json
from pathlib import Path
import signal
import subprocess

from p2_verify import eventually
from p3_public_worker_verify import PublicExperiment, TlsHandler


class SlowBody(TlsHandler):
    def do_GET(self):
        if "slow" not in self.path:
            return super().do_GET()
        self.server.requests.append({"path": self.path})
        body = b"<html><main>Fictional local body</main></html>"
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body[:1])
        self.wfile.flush()
        self.server.received.set()
        self.server.release.wait(20)
        try:
            self.wfile.write(body[1:])
        except (OSError, ConnectionError):
            pass


def facts(e, task):
    return e.sql("SELECT state,code,models_used,tools_used,token_held,cost_held,model_digest,tools_digest FROM task WHERE id='" + task + "';") + "\n" + e.sql("SELECT id,state,amount,actual FROM reservation WHERE task_id='" + task + "' ORDER BY id;") + "\n" + e.sql("SELECT state,output_digest FROM invocation WHERE task_id='" + task + "';")


def observe(e, invocation, mode, expected):
    call = subprocess.run([e.java, "-Dloader.path=" + str(e.classes),
        "-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.WebCancellationProofMain",
        "-cp", str(e.jar), "org.springframework.boot.loader.launch.PropertiesLauncher",
        str(e.control["folder"] / "runtime.properties"), invocation, mode], text=True, capture_output=True, timeout=30)
    with (e.evidence / "observation-calls.jsonl").open("a") as output:
        output.write(json.dumps({"mode": mode, "exit": call.returncode,
            "stdout": call.stdout, "stderr": call.stderr}) + "\n")
    e.assert_that(call.returncode == expected and not call.stderr, mode + " actual process exit", exit=call.returncode)
    result = json.loads(call.stdout)
    e.assert_that(result == ({"recorded": True} if expected == 0 else {"code": "WEB_OBSERVATION_BINDING_REJECTED"}), mode + " fixed metadata response", result=result)


def events(e, task):
    return e.sql("SELECT fact_id,type,code FROM event WHERE task_id='" + task + "' AND type IN ('web.fetch_cancelled','web.cancel_elapsed_ms','web.checkpoint_rejected') ORDER BY seq;")


def verify(e):
    e.common["jimu.web.public-dispatch-enabled"] = "true"
    e.tls.RequestHandlerClass = SlowBody
    task = e.submit("cancel-observation-slow")
    worker = e.start("cancelled", "worker")
    e.assert_that(e.tls.received.wait(30), "actual local TLS BODY reached")
    eventually(lambda: "BODY_NONE" in e.sql("SELECT code FROM event WHERE task_id='" + task + "' AND type='web.fetch_phase';"), "client confirmed BODY")
    cancel_status, _ = e.http("POST", "/api/tasks/" + task + "/cancel")
    e.assert_that(cancel_status == 202, "running task cancellation accepted asynchronously", status=cancel_status)
    eventually(lambda: "CHECKPOINT_REJECTED" in events(e, task), "durable actual checkpoint rejection", 30)
    e.tls.release.set()
    e.state(task, "NEEDS_RECONCILIATION")
    e.stop(worker)
    actual = events(e, task)
    e.assert_that("CANCEL_REQUESTED:" in actual and "BODY_CHECKPOINT_REJECTED" in actual,
        "runtime emits fixed first cause phase and checkpoint code", events=actual)
    (e.evidence / "actual-cancellation.tsv").write_text(actual)

    # A separately captured result is not replayed. Kill at the original commit window,
    # expire its lease, then observe metadata using that original attempt binding.
    task = e.submit("cancel-observation-recovery")
    worker = e.start("original", "worker", "WEB_RESULT")
    e.barrier(worker)
    invocation = e.sql("SELECT id FROM invocation WHERE task_id='" + task + "';")
    e.stop(worker, kill=True)
    e.expire(task)
    before = facts(e, task)
    observe(e, invocation, "first", 0)
    e.assert_that(facts(e, task) == before, "expired-fence diagnostic leaves state fees holds pins and output unchanged")
    first = events(e, task)
    e.assert_that("BODY_CHECKPOINT_REJECTED" in first and "\t123" in first and "FENCE_REJECTED:7" in first,
        "all original invocation fence and monotonic metadata persisted", events=first)
    observe(e, invocation, "second", 0)
    e.assert_that(events(e, task) == first and facts(e, task) == before, "second JVM cannot replace first cause")
    observe(e, invocation, "foreign-fence", 2)
    e.assert_that(events(e, task) == first and facts(e, task) == before, "foreign fence rejected without ledger mutation")
    requests = len(e.tls.requests)
    models = dict(e.fixture.models)
    worker = e.start("recovered", "worker")
    e.state(task, "NEEDS_RECONCILIATION")
    e.stop(worker)
    e.assert_that(e.sql("SELECT state FROM reservation WHERE id='" + invocation + "';") == "HELD_UNKNOWN",
        "recovery retains original unknown reservation")
    before = facts(e, task)
    observe(e, invocation, "second", 0)
    e.assert_that(events(e, task) == first and facts(e, task) == before, "recovered fence cannot overwrite original diagnostic or settle UNKNOWN")
    e.assert_that(len(e.tls.requests) == requests and e.fixture.models == models,
        "recovery and observation do not dispatch model or tool again")
    (e.evidence / "recovery-cancellation.tsv").write_text(first)
    (e.evidence / "recovery-ledger.tsv").write_text(before)


def main():
    def terminate(signum, frame):
        raise KeyboardInterrupt("Cancellation test terminated")
    signal.signal(signal.SIGTERM, terminate)
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("testclasses", type=Path)
    args = parser.parse_args()
    e = PublicExperiment(args.jar, args.evidence, args.testclasses)
    try:
        e.prepare()
        verify(e)
    finally:
        e.cleanup()


if __name__ == "__main__":
    main()
