"""Real worker/ledger/file/present/download assembly, local TLS and model substitutes only."""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import sys
import urllib.request

from p2_verify import Handler, eventually
from p3_public_worker_verify import PublicExperiment, TlsHandler


class ReportTls(TlsHandler):
    def do_GET(self):
        if "no-evidence" in self.path or "partial-bad" in self.path:
            self.server.requests.append({"path": self.path, "authorization": self.headers.get("Authorization"), "cookie": self.headers.get("Cookie")})
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            super().do_GET()


class ReportModel(Handler):
    def do_POST(self):
        tenant = next((k for k, v in self.server.credentials.items() if self.headers.get("Authorization") == "Bearer " + v), None)
        if tenant is None:
            self.send(401, {"error": "fixture credential required"})
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({"goal": goal, "tenant": tenant, "tools": [t["function"]["name"] for t in body["tools"]]})
        replies = [m for m in body["messages"] if m["role"] == "tool"]
        calls = [c for m in body["messages"] for c in m.get("tool_calls", [])]
        last = calls[-1]["function"]["name"] if calls else None
        system = next((m["content"] for m in body["messages"] if m["role"] == "system"), "")
        clock = re.search(r"report time (\S+)\.", system).group(1) if system else "unused"
        url = "https://fixture.example/page/" + goal
        paths = ["reports/shared.md", "reports/second.md"] if "multiple" in goal else ["reports/shared.md"]
        if "read-only" in goal:
            name, args = ("read", [{"file_path": paths[0]}]) if last is None else (None, [])
        elif last is None:
            name, args = "web_fetch", [{"url": url}]
            if "partial" in goal:
                args.append({"url": "https://fixture.example/page/partial-bad"})
        elif last == "web_fetch":
            if "unobserved" in goal:
                name, args = "write", [{"file_path": paths[0], "content": "must not be written"}]
            elif "escape" in goal:
                name, args = "read", [{"file_path": "../outside.md"}]
            elif "host-arg" in goal:
                name, args = "read", [{"file_path": paths[0], "operationId": "model-forged"}]
            else:
                name, args = "read", [{"file_path": path} for path in paths]
        elif last == "read":
            cite = "https://other.example/forged" if "forged" in goal else url
            report = "## 主题\n" + goal + "\n## 摘要\n真实本地 TLS 抓取摘要\n## 结论\n证据见[来源](" + cite + ")\n## 来源链接\n[来源](" + cite + ")\n## 生成时间\n" + clock + "\n## 局限\n本地模型与 TLS 替身，尚未核验真实业务质量。"
            if "partial-disclosed" in goal:
                report += " 部分来源失败。"
            name, args = "write", [{"file_path": path, "content": report} for path in paths]
        elif last == "write":
            name, args = "present", [{"files": [{"path": path, "description": "报告"} for path in paths]}]
        else:
            name, args = None, []
        message = {"role": "assistant", "content": "报告已交付" if name is None else ""}
        if name:
            message["tool_calls"] = [{"id": "call_" + str(len(replies)) + "_" + str(i), "type": "function", "function": {"name": name, "arguments": json.dumps(a, separators=(",", ":"))}} for i, a in enumerate(args)]
        self.send(200, {"id": "controlled-report", "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if name else "stop"}], "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}})


class ReportExperiment(PublicExperiment):
    def prepare(self):
        super().prepare()
        self.fixture.RequestHandlerClass = ReportModel
        self.fixture.requests = []
        self.tls.RequestHandlerClass = ReportTls
        workspace = self.root / "workspace"
        workspace.mkdir(mode=0o700)
        self.common["jimu.workspace-root"] = str(workspace)
        self.common["jimu.web.public-dispatch-enabled"] = "true"
        resources = Path(__file__).resolve().parents[1] / "src/main/resources/plugins/builtin"
        for name in ["read", "write", "present"]:
            digest = hashlib.sha256((resources / name / "output.schema.json").read_bytes()).hexdigest()
            for tenant in ["tenant-a", "tenant-b"]:
                self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('" + tenant + "','" + name + "','0.1.0','" + digest + "');")

    def submit(self, goal, subject="a"):
        capabilities = ["read"] if "read-only" in goal else ["web_fetch", "read", "write", "present"]
        status, view = self.http("POST", "/api/tasks", {"goal": goal, "capabilityIds": capabilities}, key=goal, subject=subject)
        self.assert_that(status == 202, "report accepted " + goal, response=view if status != 202 else view["taskId"])
        return view["taskId"]

    def receipts(self, task):
        scope = hashlib.sha256(("tenant-a:" + task).encode()).hexdigest()
        # Scope name from the host store, never exposed to the model or download response.
        path = self.root / "workspace" / (scope + ".json")
        envelope = json.loads(path.read_text())
        state = json.loads(envelope["payload"])
        self.assert_that(hashlib.sha256(envelope["payload"].encode()).hexdigest() == envelope["digest"], "workspace envelope digest validates")
        return state

    def cleanup(self):
        try:
            if hasattr(self.fixture, "requests"):
                (self.evidence / "model-requests.json").write_text(json.dumps(self.fixture.requests, indent=2))
            if self.schema_ready:
                for table in ["artifact", "invocation", "reservation", "event"]:
                    (self.evidence / ("report-" + table + ".tsv")).write_text(self.sql("SELECT * FROM " + table + ";"))
                (self.evidence / "report-context.tsv").write_text(self.sql("SELECT tenant_id,id,state,code,report_time,tools_snapshot,tools_digest FROM task;"))
            if (self.root / "workspace").exists():
                import shutil
                shutil.copytree(self.root / "workspace", self.evidence / "workspace")
        finally:
            super().cleanup()


def verify(e):
    task = e.submit("report-success-multiple")
    worker = e.start("report-writer", "worker")
    view = e.state(task, "SUCCEEDED")
    e.assert_that(len(view["artifacts"]) == 2, "actual present publishes two exact files")
    rows = e.sql("SELECT a.id,a.producer_invocation,i.tool,i.state,a.file_receipt,a.sha256,a.size,a.media_type FROM artifact a JOIN invocation i ON a.tenant_id=i.tenant_id AND a.producer_invocation=i.id WHERE a.task_id='" + task + "' ORDER BY a.id;").splitlines()
    state = e.receipts(task)
    for row in rows:
        aid, producer, tool, status, receipt, digest, size, media = row.split("\t")
        receipt = json.loads(receipt)
        file = state["files"][receipt["path"]]
        e.assert_that(tool == "present" and status == "SUCCEEDED" and file["version"] == receipt["version"] == 1 and file["sha256"] == digest == receipt["sha256"] and file["size"] == int(size) == receipt["size"], "producer is own present and exact observed written version/hash/size", artifact=aid, producer=producer)
        effect = e.sql("SELECT effect_intent_id FROM invocation WHERE id='" + producer + "';")
        e.assert_that(state["operations"][effect]["kind"] == "PRESENT" and state["bindings"][effect][receipt["path"]] == 1, "present owns durable binding and snapshot receipt")
        request = urllib.request.Request("http://127.0.0.1:" + str(e.control["port"]) + "/api/artifacts/" + aid + "/download", headers={"Authorization": "Bearer " + e.token()})
        with e.http_client.open(request, timeout=35) as response:
            body = response.read()
            e.assert_that(response.status == 200 and hashlib.sha256(body).hexdigest() == digest and body.decode() == file["content"], "authorized download contains exact CAS file")
            headers = {key: response.headers[key] for key in ["Content-Type", "Content-Disposition", "Content-Length", "X-Content-Type-Options", "ETag"]}
            with (e.evidence / "download-headers.jsonl").open("a") as log:
                log.write(json.dumps({"artifact": aid, "headers": headers}) + "\n")
            e.assert_that(response.headers.get_content_type() == "text/markdown" and response.headers.get_content_charset() == "utf-8" and response.headers["Content-Disposition"] == 'attachment; filename="' + aid + '.md"' and response.headers["X-Content-Type-Options"] == "nosniff", "safe Markdown download media filename and nosniff", headers=headers)
        for subject in ["b", "c"]:
            e.assert_that(e.http("GET", "/api/artifacts/" + aid + "/download", subject=subject)[0] == 404, "download hides another tenant or owner", subject=subject)
        e.assert_that(e.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND fact_id='published:" + aid + "';") == "1", "one published event per file")
    e.assert_that(e.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + task + "' AND category IN ('TOOL','STORAGE') AND state<>'SETTLED';") == "0", "all successful tool and storage reservations settle")
    e.assert_that(e.fixture.models["report-success-multiple"] == 5 and e.sql("SELECT tools_used FROM task WHERE id='" + task + "';") == "6", "one sole advisor loop executes fetch read2 write2 present between five model turns")
    e.stop(worker)

    subset = e.submit("report-read-only-subset")
    worker = e.start("read-only-subset", "worker")
    subset_view = e.state(subset, "FAILED")
    e.assert_that(subset_view["code"] == "REPORT_NOT_PUBLISHED" and not subset_view["artifacts"], "capability subset cannot bypass required report success condition")
    e.stop(worker)

    artifact = view["artifacts"][0]["artifactId"]
    e.sql("UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice';")
    e.assert_that(e.http("GET", "/api/artifacts/" + artifact + "/download")[0] == 403, "report download rechecks current actor revocation")
    e.sql("UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'; UPDATE artifact SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='" + artifact + "';")
    e.assert_that(e.http("GET", "/api/artifacts/" + artifact + "/download")[0] == 410, "report download rejects expired publication")

    # Independent same logical paths start missing in different task scopes.
    other = e.submit("report-second-task")
    worker = e.start("second-task", "worker")
    e.state(other, "SUCCEEDED")
    reads = json.loads(e.sql("SELECT output_snapshot FROM invocation WHERE task_id='" + other + "' AND tool='read';"))
    e.assert_that(reads["exists"] is False and reads["version"] == 0, "same tenant separate task cannot observe first task's file")
    e.stop(worker)
    foreign = e.submit("report-foreign-tenant", subject="b")
    worker = e.start("foreign-tenant", "worker")
    eventually(lambda: e.http("GET", "/api/tasks/" + foreign, subject="b")[1]["state"] == "SUCCEEDED", "foreign tenant owns report")
    foreign_read = json.loads(e.sql("SELECT output_snapshot FROM invocation WHERE task_id='" + foreign + "' AND tool='read';"))
    e.assert_that(foreign_read["exists"] is False and e.http("GET", "/api/tasks/" + foreign)[0] == 404, "tenant-scoped workspace and control authorization")
    e.stop(worker)

    partial = e.submit("report-partial-disclosed")
    worker = e.start("partial-disclosed", "worker")
    e.assert_that(len(e.state(partial, "SUCCEEDED")["artifacts"]) == 1, "partial invalid sources can publish with explicit limitations")
    e.stop(worker)

    for goal, code in [("report-no-evidence", "NO_EVIDENCE"), ("report-forged", "REPORT_CITATION_INVALID"), ("report-partial-undisclosed", "REPORT_LIMITATION_REQUIRED"), ("report-unobserved", "FS_NOT_OBSERVED"), ("report-escape", "FS_PATH_DENIED"), ("report-host-arg", "TOOL_INPUT_INVALID")]:
        failed = e.submit(goal)
        worker = e.start(goal, "worker")
        view = e.state(failed, "FAILED")
        e.assert_that(view["code"] == code and not view["artifacts"] and e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + failed + "';") == "0", "invalid reports and known file preconditions refuse without publication", goal=goal, code=view["code"])
        e.assert_that(e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + failed + "' AND state='OUTCOME_UNKNOWN';") == "0", "known precondition does not masquerade as unknown IO", goal=goal)
        e.stop(worker)

    # Successful write and present caches recover through another real worker JVM.
    for boundary in ["W1", "P2"]:
        restored = e.submit("report-recovery-" + boundary)
        worker = e.start("cache-" + boundary, "worker", boundary)
        e.barrier(worker)
        before = len(e.tls.requests)
        snapshots = e.receipts(restored)
        e.stop(worker, kill=True)
        e.expire(restored)
        worker = e.start("resume-" + boundary, "worker")
        view = e.state(restored, "SUCCEEDED")
        after = e.receipts(restored)
        e.assert_that(before == len(e.tls.requests) and len(view["artifacts"]) == 1 and after["files"] == snapshots["files"] and after["files"]["reports/shared.md"]["version"] == 1, "new JVM restores successful cache without TLS replay or repeated CAS write", boundary=boundary)
        e.assert_that(e.sql("SELECT tools_used FROM task WHERE id='" + restored + "';") == "4" and e.sql("SELECT COUNT(*) FROM event WHERE task_id='" + restored + "' AND type='artifact.created';") == "1", "recovery neither double charges tools nor duplicates publication", boundary=boundary)
        e.stop(worker)

    verify_effects(e)


def verify_effects(e):
    # Unconfirmed IO is deliberately refused, retaining reservations; no blind resume/rebind.
    for boundary in ["P0", "P1"]:
        unknown = e.submit("report-unknown-" + boundary)
        worker = e.start("unknown-" + boundary, "worker", boundary)
        e.barrier(worker)
        before = e.receipts(unknown)
        e.assert_that(e.sql("SELECT state FROM artifact WHERE task_id='" + unknown + "';") == "STAGING" and e.sql("SELECT state FROM invocation WHERE task_id='" + unknown + "' AND tool='present';") == "RUNNING", "upload intent is durable before IO and no success is visible", boundary=boundary)
        e.assert_that(e.sql("SELECT COUNT(*) FROM event ev JOIN invocation i ON i.tenant_id=ev.tenant_id AND i.task_id=ev.task_id WHERE i.task_id='" + unknown + "' AND i.tool='present' AND (ev.type='artifact.created' OR ev.fact_id=CONCAT('result:',i.id));") == "0", "precommit boundary exposes neither present success nor publication event", boundary=boundary)
        e.stop(worker, kill=True)
        e.expire(unknown)
        worker = e.start("refuse-" + boundary, "worker")
        e.state(unknown, "NEEDS_RECONCILIATION")
        eventually(lambda: e.sql("SELECT state FROM reservation WHERE task_id='" + unknown + "' AND category='RUNNING';") == "RELEASED", "old writer actually stopped")
        e.assert_that(e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + unknown + "' AND state='PUBLISHED';") == "0" and e.sql("SELECT state FROM reservation WHERE task_id='" + unknown + "' AND category='TOOL' ORDER BY id=(SELECT id FROM invocation WHERE task_id='" + unknown + "' AND tool='present') DESC LIMIT 1;") == "HELD_UNKNOWN", "unknown present never publishes or clears unknown tool reservation", boundary=boundary)
        e.assert_that(e.receipts(unknown) == before, "unconfirmed recovery retains original CAS file and binding bytes", boundary=boundary)
        e.stop(worker)

    cancelled = e.submit("report-cancel-before-publish")
    worker = e.start("cancel-present", "worker", "P1")
    e.barrier(worker)
    e.http("POST", "/api/tasks/" + cancelled + "/cancel", {})
    e.state(cancelled, "NEEDS_RECONCILIATION")
    e.stop(worker)
    e.assert_that(e.sql("SELECT cancel_requested_at IS NOT NULL FROM task WHERE id='" + cancelled + "';") == "1" and e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + cancelled + "' AND state='PUBLISHED';") == "0", "cancel before publication preserves intent and blocks report visibility")
    eventually(lambda: e.sql("SELECT state FROM reservation WHERE task_id='" + cancelled + "' AND category='RUNNING';") == "RELEASED", "cancelled writer process stop confirmed")
    e.assert_that(e.sql("SELECT state FROM reservation WHERE task_id='" + cancelled + "' AND category='STORAGE';") == "RESERVED" and e.sql("SELECT state FROM reservation WHERE tenant_id='tenant-a' AND id=(SELECT id FROM invocation WHERE task_id='" + cancelled + "' AND tool='present');") == "HELD_UNKNOWN", "actual stop releases concurrency but retains storage intent and unknown present fee")
    e.assert_that(all(r.get("authorization") is None and r.get("cookie") is None for r in e.tls.requests) and all(s == "fixture.example" for s in e.tls.names), "all actual local TLS requests anonymous and retain original SNI", count=len(e.tls.requests))


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt("Report supervisor terminated")))
    e = ReportExperiment(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
    try:
        e.prepare()
        verify_effects(e) if "--effects-only" in sys.argv[4:] else verify(e)
    finally:
        e.cleanup()
