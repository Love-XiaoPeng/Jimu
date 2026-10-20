"""Owned MySQL/local TLS/model only: preserved results do not authorize replay or settlement."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess

from p3_public_worker_verify import PublicExperiment, TlsHandler


class FailedProjection(TlsHandler):
    def do_GET(self):
        if "projection-failure" not in self.path:
            return super().do_GET()
        self.server.requests.append({"path": self.path})
        body = b"FICTIONAL_ONLY_BODY_CANARY"
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def literal(value):
    return "NULL" if value is None else "CONVERT(0x" + str(value).encode().hex() + " USING utf8mb4)"


def facts(e, task):
    return e.sql("SELECT state,code,models_used,tools_used,token_held,cost_held,next_event_seq FROM task WHERE id='" + task + "';") + "\n" + e.sql("SELECT id,state,amount,actual FROM reservation WHERE task_id='" + task + "' ORDER BY id;")


def assess(e, task, invocation, label, expected=None, tenant="tenant-a"):
    before = facts(e, task)
    call = subprocess.run([e.java, "-Dloader.path=" + str(e.classes),
        "-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.WebFetchProofMain",
        "-cp", str(e.jar), "org.springframework.boot.loader.launch.PropertiesLauncher",
        str(e.control["folder"] / "runtime.properties"), tenant, task, invocation], text=True, capture_output=True)
    result = json.loads(call.stdout.strip())
    e.assert_that(call.returncode == (2 if expected else 0) and not call.stderr,
                  label + " actual exit and stderr", exit=call.returncode, stderr=call.stderr)
    e.assert_that(result.get("code") == expected if expected else result.get("readOnly") is True,
                  label + " exact proof decision", result=result)
    e.assert_that(not any(marker in call.stdout + call.stderr for marker in
        ["Actual fetched body.", "FICTIONAL_ONLY_BODY_CANARY", "https://fixture.example"]),
        label + " emits identifiers and digests without result or URL")
    e.assert_that(before == facts(e, task), label + " no state fee hold or event mutation")
    (e.evidence / (label + "-result.json")).write_text(json.dumps({"exit": call.returncode,
        "stdout": call.stdout, "stderr": call.stderr, "ledgerBefore": before,
        "ledgerAfter": facts(e, task)}, indent=2))


def mutations(e, task, invocation):
    row = json.loads(e.sql("SELECT JSON_OBJECT('input_snapshot',input_snapshot,'input_digest',input_digest,"
        "'output_snapshot',output_snapshot,'output_digest',output_digest,'plugin_version',plugin_version,"
        "'plugin_digest',plugin_digest,'installation_epoch',installation_epoch,'effect_intent_id',effect_intent_id,"
        "'slot',slot,'provider_id',provider_id,'step_id',step_id) FROM invocation WHERE id='" + invocation + "';"))
    for field, value in [("output_snapshot", None), ("output_digest", "0" * 64),
            ("plugin_version", "0.2.0"), ("plugin_digest", "0" * 64), ("installation_epoch", 999),
            ("effect_intent_id", "fictional-other-effect"), ("slot", "9:foreign"), ("provider_id", "foreign")]:
        e.sql("UPDATE invocation SET " + field + "=" + literal(value) + " WHERE id='" + invocation + "';")
        try:
            assess(e, task, invocation, "tamper-" + field,
                   "WEB_RESULT_PROOF_MISSING" if field.startswith("output_") else "WEB_RESULT_PROOF_INVALID")
        finally:
            e.sql("UPDATE invocation SET " + field + "=" + literal(row[field]) + " WHERE id='" + invocation + "';")
    changed = json.dumps({"url": "https://fixture.example/different"}, separators=(",", ":"))
    e.sql("UPDATE invocation SET input_snapshot=" + literal(changed) + ",input_digest=" + literal(hashlib.sha256(changed.encode()).hexdigest()) + " WHERE id='" + invocation + "';")
    try:
        assess(e, task, invocation, "tamper-arguments-rehashed", "WEB_RESULT_PROOF_INVALID")
    finally:
        e.sql("UPDATE invocation SET input_snapshot=" + literal(row["input_snapshot"]) + ",input_digest=" + literal(row["input_digest"]) + " WHERE id='" + invocation + "';")
    changed = json.loads(row["output_snapshot"])
    changed["content"] += " changed"
    changed = json.dumps(changed, separators=(",", ":"))
    e.sql("UPDATE invocation SET output_snapshot=" + literal(changed) + ",output_digest=" + literal(hashlib.sha256(changed.encode()).hexdigest()) + " WHERE id='" + invocation + "';")
    try:
        assess(e, task, invocation, "tamper-result-rehashed", "WEB_RESULT_PROOF_INVALID")
    finally:
        e.sql("UPDATE invocation SET output_snapshot=" + literal(row["output_snapshot"]) + ",output_digest=" + literal(row["output_digest"]) + " WHERE id='" + invocation + "';")
    step = row["step_id"]
    original = json.loads(e.sql("SELECT JSON_OBJECT('request_snapshot',request_snapshot,'request_digest',request_digest,"
        "'response_snapshot',response_snapshot,'response_digest',response_digest) FROM step WHERE id='" + step + "';"))
    for field in ["request_snapshot", "response_snapshot"]:
        content = json.loads(original[field])
        if field == "request_snapshot":
            content["requestBody"] += " "  # Outer hash recomputed, nested wire hash intentionally stale.
        else:
            content["calls"].append(dict(content["calls"][0]))
        changed = json.dumps(content, separators=(",", ":"))
        digest_field = field.replace("snapshot", "digest")
        e.sql("UPDATE step SET " + field + "=" + literal(changed) + "," + digest_field + "=" + literal(hashlib.sha256(changed.encode()).hexdigest()) + " WHERE id='" + step + "';")
        try:
            assess(e, task, invocation, "tamper-" + field, "WEB_RESULT_PROOF_INVALID")
        finally:
            e.sql("UPDATE step SET " + field + "=" + literal(original[field]) + "," + digest_field + "=" + literal(original[digest_field]) + " WHERE id='" + step + "';")
    proof = e.sql("SELECT code FROM event WHERE fact_id='web-result:" + invocation + "';")
    e.sql("DELETE FROM event WHERE fact_id='web-result:" + invocation + "';")
    try:
        assess(e, task, invocation, "missing-original-proof", "WEB_RESULT_PROOF_MISSING")
    finally:
        # Restore via the test's saved row, preserving event identity and sequence.
        e.sql("INSERT INTO event SELECT * FROM saved_web_proof;")
    assess(e, task, invocation, "wrong-tenant", "NOT_FOUND", tenant="tenant-b")
    assess(e, task, "00000000-0000-0000-0000-000000000000", "wrong-invocation", "NOT_FOUND")
    e.assert_that(e.sql("SELECT code FROM event WHERE fact_id='web-result:" + invocation + "';") == proof,
                  "isolated mutation fixture restored exact original proof")


def verify(e):
    e.common["jimu.web.public-dispatch-enabled"] = "true"
    # Endpoint configuration already captured the loopback model listener. The base SSE
    # fixture constructs tool arguments directly (without ModelHandler.send's JSON rewrite).
    # Give both response formats the same logical public target routed to owned local TLS.
    e.fixture.origin = "https://fixture.example"
    e.tls.RequestHandlerClass = FailedProjection
    for stream in [False, True]:
        label = "sse" if stream else "json"
        e.stop(e.control)
        e.control = e.start("control-" + label, "control", model_stream=stream)
        goal = "preserved-" + label
        task = e.submit(goal)
        worker = e.start("capture-" + label, "worker", "WEB_RESULT", extra={"jimu.model-stream": str(stream).lower()})
        e.barrier(worker)
        invocation = e.sql("SELECT id FROM invocation WHERE task_id='" + task + "';")
        e.assert_that(e.sql("SELECT JSON_EXTRACT(model_snapshot,'$.stream') FROM task WHERE id='" + task + "';") == str(stream).lower(),
                      label + " actual task transport snapshot")
        e.assert_that(e.sql("SELECT state FROM invocation WHERE id='" + invocation + "';") == "RUNNING"
            and e.sql("SELECT COUNT(*) FROM event WHERE fact_id='result:" + invocation + "';") == "0",
            label + " preserved result precedes success and settlement")
        assess(e, task, invocation, label + "-running-proof")
        e.stop(worker, kill=True)
        e.expire(task)
        before = len(e.tls.requests)
        worker = e.start("recover-" + label, "worker", extra={"jimu.model-stream": str(stream).lower()})
        e.state(task, "NEEDS_RECONCILIATION")
        e.stop(worker)
        e.assert_that(len(e.tls.requests) == before and e.fixture.models[goal] == 1
            and e.sql("SELECT state FROM reservation WHERE id='" + invocation + "';") == "HELD_UNKNOWN",
            label + " crash recovery retains UNKNOWN without model or tool replay")
        assess(e, task, invocation, label + "-unknown-proof")
        if not stream:
            e.sql("CREATE TABLE saved_web_proof AS SELECT * FROM event WHERE fact_id='web-result:" + invocation + "';")
            mutations(e, task, invocation)
            e.sql("DROP TABLE saved_web_proof;")
        (e.evidence / (label + "-binding.tsv")).write_text(e.sql("SELECT i.id,i.state,i.input_digest,i.output_digest,i.plugin_version,i.plugin_digest,i.installation_epoch,s.request_digest,s.response_digest,e.fact_id,e.type,e.code FROM invocation i JOIN step s ON i.step_id=s.id JOIN event e ON e.task_id=i.task_id WHERE i.id='" + invocation + "' AND (e.fact_id LIKE '%" + invocation + "%' OR e.type LIKE 'web.%') ORDER BY e.seq;"))

    task = e.submit("projection-failure")
    worker = e.start("projection-failure", "worker", extra={"jimu.model-stream": "true"})
    e.state(task, "NEEDS_RECONCILIATION")
    e.stop(worker)
    invocation = e.sql("SELECT id FROM invocation WHERE task_id='" + task + "';")
    assess(e, task, invocation, "failed-projection", "WEB_RESULT_PROOF_MISSING")
    codes = e.sql("SELECT type,code FROM event WHERE task_id='" + task + "' AND type LIKE 'web.%' ORDER BY seq;")
    e.assert_that("web.fetch_failed\tPROJECTION_IO" in codes and "CANARY" not in codes,
                  "actual local TLS projection failure records only fixed phase and type", codes=codes)
    e.assert_that(e.sql("SELECT COUNT(*) FROM invocation WHERE id='" + invocation + "' AND output_snapshot IS NULL AND output_digest IS NULL;") == "1",
                  "failed or partial result is never persisted as trusted proof")
    (e.evidence / "failure-phases.tsv").write_text(codes)


def main():
    def terminate(signum, frame):
        raise KeyboardInterrupt("Web result test terminated")
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
