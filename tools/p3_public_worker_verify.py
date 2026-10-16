"""Real control/worker/MySQL, local TLS + model substitutes; never public DNS/socket evidence."""
import argparse
import concurrent.futures
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import signal
import ssl
import subprocess
import threading

from p2_verify import Experiment, Handler, eventually, run


class ModelHandler(Handler):
    def send(self, status, value, mime="application/json"):
        if isinstance(value, dict) and "choices" in value:
            for choice in value["choices"]:
                for call in choice["message"].get("tool_calls", []):
                    args = json.loads(call["function"]["arguments"])
                    args["url"] = args["url"].replace(self.server.origin, "https://fixture.example")
                    if "denied" in args["url"]:
                        args["url"] = "https://127.0.0.1/forbidden"
                    call["function"]["arguments"] = json.dumps(args, separators=(",", ":"))
        super().send(status, value, mime)


class TlsHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.requests.append({"path": self.path, "authorization": self.headers.get("Authorization"),
                                     "cookie": self.headers.get("Cookie"), "peer": self.client_address[0]})
        if "slow" in self.path:
            self.server.received.set()
            self.server.release.wait(20)
        body = b"<html><main><h1>Local TLS source</h1><p>Actual fetched body.</p></main></html>"
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            pass


class PublicExperiment(Experiment):
    def __init__(self, jar, evidence, testclasses):
        super().__init__(jar, evidence)
        self.fixture.RequestHandlerClass = ModelHandler
        self.testclasses = testclasses
        self.tls = None

    def prepare(self):
        super().prepare()
        self.sql("UPDATE installation SET plugin_version='0.3.0';")
        classes = self.root / "testclasses"
        shutil.copytree(self.testclasses, classes)
        cert, key = self.root / "cert.pem", self.root / "key.pem"
        run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-keyout", str(key), "-out", str(cert), "-subj", "/CN=fixture.example",
             "-addext", "subjectAltName=" + getattr(self, "certificate_sans", "DNS:fixture.example")])
        os.chmod(key, 0o600)
        self.tls = http.server.ThreadingHTTPServer(("127.0.0.1", 0), TlsHandler)
        self.tls.requests, self.tls.names = [], []
        self.tls.received, self.tls.release = threading.Event(), threading.Event()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        context.set_servername_callback(lambda sock, name, ctx: self.tls.names.append(name))
        self.tls.socket = context.wrap_socket(self.tls.socket, server_side=True)
        self.tls_thread = threading.Thread(target=self.tls.serve_forever)
        self.tls_thread.start()
        self.common.update({"jimu.web.mode": "public", "jimu.web.allowed-domains": "fixture.example",
                            "fixture.tls.cert": str(cert), "fixture.tls.port": str(self.tls.server_port)})
        self.classes = classes

    def start(self, name, role, boundary="", model_stream=False, expected_ready=200, extra=None):
        if role == "control":
            return super().start(name, role, boundary, model_stream, expected_ready, extra)
        folder = self.root / name
        folder.mkdir()
        config = dict(self.common, **{"jimu.role": role, "fixture.tracker": str(folder / "tracker.log")})
        config.update(extra or {})
        if boundary:
            config.update({"jimu.test.boundary": boundary, "jimu.test.barrier": str(folder)})
        properties = folder / "runtime.properties"
        properties.write_text("\n".join(k + "=" + v for k, v in config.items()) + "\n")
        os.chmod(properties, 0o600)
        logfile = folder / (name + ".log")
        with logfile.open("w") as log:
            child = subprocess.Popen([self.java, "-Dloader.path=" + str(self.classes),
                "-Dloader.main=io.github.lovexiaopeng.jimu.adapters.runtime.web.LocalTlsWorkerMain",
                "-cp", str(self.jar), "org.springframework.boot.loader.launch.PropertiesLauncher",
                "--spring.profiles.active=local-tls-worker", "--spring.config.additional-location=file:" + str(properties)],
                stdout=log, stderr=subprocess.STDOUT)
        self.children.append(child)
        def started():
            if child.poll() is not None:
                raise AssertionError(name + " failed: " + logfile.read_text()[-12000:])
            match = re.search(r"Tomcat started on port (\d+)", logfile.read_text())
            return int(match.group(1)) if match else False
        port = eventually(started, name + " startup")
        instance = {"process": child, "port": port, "folder": folder, "name": name}
        self.results.append({"process": name, "pid": child.pid, "port": port, "testClasspathOnly": True})
        self.assert_that(self.http("GET", "/readyz", instance=instance)[0] == expected_ready, name + " readiness")
        return instance

    def cleanup(self):
        try:
            if self.tls:
                self.tls.release.set()
                self.tls.shutdown()
                self.tls.server_close()
                self.tls_thread.join(5)
                (self.evidence / "tls-counts.json").write_text(json.dumps({"requests": self.tls.requests,
                    "sni": self.tls.names, "listenerStopped": not self.tls_thread.is_alive()}, indent=2))
            # Stop before collecting final tracker receipts; all children belong to this run.
            for child in reversed(self.children):
                self.stop({"process": child, "name": "public-cleanup"})
            for trace in self.root.glob("*/tracker.log"):
                shutil.copyfile(trace, self.evidence / (trace.parent.name + "-tracker.log"))
        finally:
            super().cleanup()


def verify(e):
    task = e.submit("public-disabled")
    worker = e.start("disabled", "worker")
    e.state(task, "FAILED")
    e.assert_that(e.sql("SELECT code FROM task WHERE id='" + task + "';") == "PUBLIC_WEB_NOT_CONFIGURED"
                  and not e.tls.requests and not e.fixture.models,
                  "default public dispatch gate rejects before model and TLS")
    e.stop(worker)
    enabled = {"jimu.web.public-dispatch-enabled": "true"}

    task = e.submit("public-denied-observation")
    worker = e.start("barrier-observation", "worker", "M0", extra=enabled)
    eventually(lambda: (worker["folder"] / "reached").exists(), "observation worker remains paused")
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
        for seconds, persistent in [(2, False), (8, True)]:
            future = executor.submit(e.sql, "START TRANSACTION; SELECT * FROM tenant_budget WHERE tenant_id='tenant-b' FOR UPDATE; DO SLEEP(" + str(seconds) + "); COMMIT;")
            eventually(lambda: e.sql("SELECT COUNT(*) FROM information_schema.innodb_trx;") != "0", "known unrelated transaction becomes visible")
            e.assert_that(e.sql("SELECT COUNT(*) FROM information_schema.innodb_trx;") != "0",
                          "original instantaneous global assertion rejects known unrelated transaction", persistent=persistent)
            if persistent:
                try:
                    e.barrier(worker)
                except AssertionError as error:
                    e.assert_that("database quiet point" in str(error), "bounded observation still rejects persistent transaction", error=str(error))
                else:
                    raise AssertionError("Persistent transaction must fail the gate")
            else:
                e.barrier(worker)
            future.result()
    (worker["folder"] / "release").touch()
    e.state(task, "FAILED")
    e.assert_that(not e.tls.requests and e.sql("SELECT code FROM task WHERE id='" + task + "';") == "TOOL_INPUT_INVALID",
                  "observation controls leave production admission and zero TLS intact")
    e.stop(worker)

    task = e.submit("public-success")
    worker = e.start("successful", "worker", extra=enabled)
    e.state(task, "FAILED")  # No present implementation yet: never publish a legacy summary as a report.
    invocation = e.sql("SELECT id,state,output_snapshot FROM invocation WHERE task_id='" + task + "';").split("\t", 2)
    output = json.loads(invocation[2])
    e.assert_that(invocation[1] == "SUCCEEDED" and output["sourceUrl"] == "https://fixture.example/page/public-success"
                  and "Actual fetched body." in output["content"],
                  "real worker commits local TLS public reader output under own invocation", invocation=invocation[0])
    e.assert_that(e.fixture.models["public-success"] == 2 and len(e.tls.requests) == 1,
                  "sole advisor loop uses one actual fetch between two substitute model calls")
    e.assert_that(e.sql("SELECT code FROM task WHERE id='" + task + "';") == "REPORT_NOT_PUBLISHED"
                  and e.sql("SELECT COUNT(*) FROM artifact WHERE task_id='" + task + "';") == "0",
                  "unassembled present fails closed without legacy fetch-selected summary artifact")
    e.assert_that(e.http("GET", "/api/tasks/" + task, subject="b")[0] == 404 and
                  e.http("GET", "/api/tasks/" + task, subject="c")[0] == 404,
                  "other tenant and other owner cannot read public task")
    e.stop(worker)

    task = e.submit("public-recovery")
    worker = e.start("cache-writer", "worker", "T1", extra=enabled)
    e.barrier(worker)
    e.stop(worker, kill=True)
    before = len(e.tls.requests)
    e.expire(task)
    worker = e.start("cache-reader", "worker", extra=enabled)
    e.state(task, "FAILED")
    e.assert_that(len(e.tls.requests) == before and e.fixture.models["public-recovery"] == 2 and
                  e.sql("SELECT tools_used FROM task WHERE id='" + task + "';") == "1" and
                  e.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + task + "' AND state='SUCCEEDED';") == "1",
                  "new JVM consumes durable successful invocation without new TLS or tool charge")
    e.stop(worker)

    task = e.submit("public-denied")
    worker = e.start("policy-denial", "worker", extra=enabled)
    e.state(task, "FAILED")
    e.assert_that(e.sql("SELECT code FROM task WHERE id='" + task + "';") == "TOOL_INPUT_INVALID" and
                  e.sql("SELECT tools_used FROM task WHERE id='" + task + "';") == "0" and len(e.tls.requests) == before,
                  "production URL policy rejects loopback before admission or transport")
    e.stop(worker)

    task = e.submit("public-slow-cancel")
    worker = e.start("cancel-tls", "worker", extra=enabled)
    e.assert_that(e.tls.received.wait(15), "real TLS request reached slow server before cancel")
    e.assert_that(e.http("POST", "/api/tasks/" + task + "/cancel")[0] == 202, "cancel accepted during public TLS read")
    eventually(lambda: e.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='" + task + "' AND quiescent=FALSE;") == "0",
               "shared tracker drains before quiescence", 15)
    e.assert_that(e.sql("SELECT state FROM task WHERE id='" + task + "';") == "NEEDS_RECONCILIATION" and
                  e.sql("SELECT state FROM reservation WHERE task_id='" + task + "' AND category='TOOL';") == "HELD_UNKNOWN" and
                  e.sql("SELECT state FROM reservation WHERE id='" + task + ":run';") == "RELEASED",
                  "actual canceled transport retains unknown effect reservation and releases run only after drain")
    e.tls.release.set()
    e.stop(worker)
    trace = (worker["folder"] / "tracker.log").read_text()
    e.assert_that("active=1" in trace and "transport-exit" in trace and "shutdown active=0" in trace,
                  "actual public transport shares worker tracker and converges", trace=trace)
    e.assert_that(len(e.tls.requests) == before + 1 and all(r["authorization"] is None and r["cookie"] is None for r in e.tls.requests)
                  and all(name == "fixture.example" for name in e.tls.names),
                  "all local TLS sockets anonymous with original SNI and no extra dispatch", count=len(e.tls.requests))


def main():
    def terminate(signum, frame):
        raise KeyboardInterrupt("Public worker supervisor terminated")
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
