#!/usr/bin/env python3
"""Foreground, isolated MySQL/ext4/three-JVM acceptance fixture. No shared services or paid model."""
import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import http.client
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.request
import uuid

MYSQL = "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b"


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, timeout=120, **kwargs).stdout.strip()


def eventually(predicate, description, timeout=50):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = predicate()
        if value:
            return value
        time.sleep(.08)
    raise AssertionError("Timed out: " + description)


class Fixture(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.origin = "http://127.0.0.1:" + str(self.server_port)
        self.models, self.tools, self.effects = {}, {}, {}
        self.credentials = {"tenant-a": secrets.token_urlsafe(24), "tenant-b": secrets.token_urlsafe(24)}
        self.identities = {}
        self.lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, status, value, mime="application/json"):
        data = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/":
            self.send(200, b"controlled provider ready", "text/plain")
            return
        tenant = next((k for k, v in self.server.credentials.items() if self.headers.get("Authorization") == "Bearer " + v), None)
        if tenant is None:
            self.send(401, {"error": "fixture credential required"})
            return
        with self.server.lock:
            self.server.tools[self.path] = self.server.tools.get(self.path, 0) + 1
        if "/effect/" in self.path:
            with self.server.lock:
                self.server.effects[self.path] = self.server.effects.get(self.path, 0) + 1
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            return
        self.send(200, ("fixture content " + self.path).encode(), "text/plain")

    def do_POST(self):
        tenant = next((k for k, v in self.server.credentials.items() if self.headers.get("Authorization") == "Bearer " + v), None)
        if tenant is None:
            self.send(401, {"error": "fixture credential required"})
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        assert body["stream"] is False
        goal = next(m["content"] for m in body["messages"] if m["role"] == "user")
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.identities.setdefault(goal, []).append(tenant)
        has_tool = any(m["role"] == "tool" for m in body["messages"])
        if has_tool:
            message = {"role": "assistant", "content": "Verified fixture result: " + goal}
            reason = "stop"
        else:
            path = ("/effect/" if goal.startswith("unknown") else "/page/") + goal
            slots = 2 if goal.startswith("multi") else 1
            message = {"role": "assistant", "content": "", "tool_calls": [
                {"id": "call_" + str(i), "type": "function", "function": {
                    "name": "web_fetch", "arguments": json.dumps({"url": self.server.origin + path + ("-"+str(i) if slots>1 else "")}, separators=(",", ":"))}}
                for i in range(slots)]}
            reason = "tool_calls"
        response = {"id": "controlled-response", "choices": [{"index": 0, "message": message, "finish_reason": reason}]}
        if not goal.startswith("missing-usage"):
            response["usage"] = {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
        self.send(200, response)


class Experiment:
    def __init__(self, jar, evidence):
        self.jar, self.evidence = jar.resolve(), evidence.resolve()
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="jimu-p2-"))
        os.chmod(self.root, 0o700)
        self.name = "jimu-p2-" + uuid.uuid4().hex[:16]
        self.cid = None
        self.children = []
        self.results = []
        self.fixture = Fixture()
        self.fixture_thread = threading.Thread(target=self.fixture.serve_forever)
        self.fixture_thread.start()
        self.key = secrets.token_bytes(32)
        self.password = secrets.token_urlsafe(32)
        self.java = None
        self.schema_ready = False

    def assert_that(self, condition, name, **facts):
        assert condition, name + ": " + str(facts)
        self.results.append({"assertion": name, **facts})
        print("PASS", name, json.dumps(facts), flush=True)

    def sql(self, sql):
        return run(["docker", "exec", "-i", self.cid, "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B --raw jimu'], input=sql)

    def prepare(self):
        java = os.environ.get("JIMU_IT_JAVA") or shutil.which("java")
        version = subprocess.run([java, "-version"], text=True, capture_output=True).stderr
        if not re.search(r'version "21\.', version):
            packages = self.root / "jdk"
            packages.mkdir()
            run(["apt-get", "-o", "Acquire::Retries=2", "download", "openjdk-21-jre-headless=21.0.12.1+1-1~24.04.4"], cwd=packages)
            package = next(packages.glob("*.deb"))
            self.results.append({"jreDebSha256": hashlib.sha256(package.read_bytes()).hexdigest()})
            run(["dpkg-deb", "-x", str(package), str(packages / "unpacked")])
            for link in (packages / "unpacked/usr/lib/jvm").rglob("*"):
                if link.is_symlink() and os.readlink(link).startswith("/etc/java-21-openjdk/"):
                    target = packages / "unpacked" / os.readlink(link).lstrip("/")
                    link.unlink()
                    link.symlink_to(target)
            java = str(next((packages / "unpacked/usr/lib/jvm").glob("*/bin/java")))
        self.java = java
        self.results.append({"java": subprocess.run([java, "-version"], text=True, capture_output=True, check=True).stderr.strip(),
                             "filesystem": run(["findmnt", "-n", "-o", "FSTYPE", "-T", str(self.root)])})
        envfile = self.root / "mysql.env"
        envfile.write_text("MYSQL_ROOT_PASSWORD=" + self.password + "\nMYSQL_DATABASE=jimu\n")
        os.chmod(envfile, 0o600)
        self.cid = run(["docker", "run", "-d", "--name", self.name, "--label", "jimu.p2=" + self.name,
                        "--env-file", str(envfile), "-p", "127.0.0.1::3306", MYSQL,
                        "--default-time-zone=+00:00", "--innodb-flush-log-at-trx-commit=1"])
        def ready():
            try:
                return self.sql("SELECT VERSION();")
            except subprocess.CalledProcessError:
                return False
        version = eventually(ready, "private MySQL", 80)
        self.assert_that(version == "8.0.46", "real pinned MySQL", version=version)
        self.dbport = int(run(["docker", "port", self.cid, "3306/tcp"]).split(":")[-1])
        self.sql("CREATE USER 'runtime'@'%' IDENTIFIED BY '" + self.password + "'; GRANT SELECT,INSERT,UPDATE,DELETE ON jimu.* TO 'runtime'@'%';")
        (self.root / "objects").mkdir()
        common = {
            "jimu.durable.enabled": "true", "jimu.db.url": "jdbc:mysql://127.0.0.1:" + str(self.dbport) + "/jimu?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
            "jimu.db.user": "runtime", "jimu.db.password": self.password,
            "jimu.db.migration-user": "root", "jimu.db.migration-password": self.password,
            "jimu.storage-root": str(self.root / "objects"), "jimu.identity.key": base64.b64encode(self.key).decode(),
            "jimu.identity.subjects": "a:tenant-a:alice;b:tenant-b:bob;c:tenant-a:carol",
            "jimu.provider-origin": self.fixture.origin, "server.port": "0", "jimu.test-mode": "true"}
        common["jimu.fixture.credentials"] = ";".join(k + ":" + v for k, v in self.fixture.credentials.items())
        self.common = common
        self.control = self.start("control", "control")
        self.schema_ready = True
        digest = "08a144489ed9ea37b5b33a5ffdc815237f31d4f229b76ba1d5e465b9e13f9ff8"
        self.sql("INSERT INTO tenant_budget(tenant_id) VALUES('tenant-a'),('tenant-b');"
                 "INSERT INTO actor_access(tenant_id,actor_id) VALUES('tenant-a','alice'),('tenant-b','bob'),('tenant-a','carol');"
                 "INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES"
                 "('tenant-a','web_fetch','0.2.0','" + digest + "'),('tenant-b','web_fetch','0.2.0','" + digest + "');")

    def start(self, name, role, boundary=""):
        folder = self.root / name
        folder.mkdir()
        config = dict(self.common, **{"jimu.role": role})
        if boundary:
            config.update({"jimu.test.boundary": boundary, "jimu.test.barrier": str(folder)})
        properties = folder / "runtime.properties"
        properties.write_text("\n".join(k + "=" + v for k, v in config.items()) + "\n")
        os.chmod(properties, 0o600)
        logfile = self.evidence / (name + ".log")
        with logfile.open("w") as log:
            child = subprocess.Popen([self.java, "-jar", str(self.jar), "--spring.config.additional-location=file:" + str(properties)], stdout=log, stderr=subprocess.STDOUT)
        self.children.append(child)
        def started():
            if child.poll() is not None:
                raise AssertionError(name + " startup failed; see " + logfile.name + "\n" + logfile.read_text()[-12000:])
            match = re.search(r"Tomcat started on port (\d+)", logfile.read_text())
            return int(match.group(1)) if match else False
        port = eventually(started, name + " startup")
        instance = {"process": child, "port": port, "folder": folder, "name": name}
        self.results.append({"process": name, "pid": child.pid, "port": port})
        self.assert_that(self.http("GET", "/readyz", instance=instance)[0] == 200, name + " readiness")
        return instance

    def stop(self, instance, kill=False):
        child = instance["process"]
        if child.poll() is None:
            child.kill() if kill else child.terminate()
            try:
                child.wait(timeout=12)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        self.results.append({"collectedPid": child.pid, "exit": child.returncode, "name": instance["name"]})

    def token(self, subject="a", expired=False):
        def enc(value):
            return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")
        data = enc({"alg": "HS256", "typ": "JWT"}) + "." + enc({"iss": "jimu-fixture", "aud": "jimu-api", "sub": subject, "exp": int(time.time()) + (-1 if expired else 3600)})
        return data + "." + base64.urlsafe_b64encode(hmac.digest(self.key, data.encode(), "sha256")).decode().rstrip("=")

    def http(self, method, path, body=None, subject="a", key=None, instance=None, headers=None):
        instance = instance or self.control
        request = urllib.request.Request("http://127.0.0.1:" + str(instance["port"]) + path, method=method,
                                         data=None if body is None else json.dumps(body).encode())
        request.add_header("Authorization", "Bearer " + self.token(subject))
        request.add_header("Content-Type", "application/json")
        if key:
            request.add_header("Idempotency-Key", key)
        for k, v in (headers or {}).items():
            request.add_header(k, v)
        try:
            response = urllib.request.urlopen(request, timeout=35)
        except urllib.error.HTTPError as e:
            response = e
        with response:
            raw = response.read().decode()
            content = response.headers.get("Content-Type", "")
            return response.status, json.loads(raw) if "application/json" in content else raw

    def submit(self, goal, budget=None, **options):
        body = {"goal": goal}
        if budget:
            body["budget"] = budget
        status, value = self.http("POST", "/api/tasks", body, key=goal, **options)
        self.assert_that(status == 202, "durable accept " + goal, status=status, response=value if status != 202 else value["taskId"])
        return value["taskId"]

    def barrier(self, worker):
        return eventually(lambda: (worker["folder"] / "reached").read_text() if (worker["folder"] / "reached").exists() else False, worker["name"] + " barrier")

    def expire(self, task):
        self.sql("UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='" + task + "';")

    def state(self, task, expected):
        def matches():
            status, view = self.http("GET", "/api/tasks/" + task)
            if status == 200 and view["state"] in ["FAILED", "CANCELLED", "NEEDS_RECONCILIATION"] and view["state"] != expected:
                raise AssertionError("Unexpected terminal " + str(view))
            return view if status == 200 and view["state"] == expected else False
        return eventually(matches, task + " -> " + expected)

    def first_slice(self):
        task = self.submit("first-slice")
        self.stop(self.control)
        self.control = self.start("control-restarted", "control")
        same = self.submit("first-slice")
        self.assert_that(task == same, "idempotency survives control restart", task=task)
        with concurrent.futures.ThreadPoolExecutor(6) as pool:
            values = list(pool.map(lambda _: self.http("POST", "/api/tasks", {"goal": "first-slice"}, key="first-slice"), range(6)))
        self.assert_that(all(s == 202 and v["taskId"] == task for s, v in values), "concurrent idempotent acceptance")
        self.assert_that(self.http("POST", "/api/tasks", {"goal": "different"}, key="first-slice")[0] == 409, "idempotency digest conflict")
        worker = self.start("worker-a", "worker", "T1")
        self.barrier(worker)
        self.assert_that(self.fixture.tools.get("/page/first-slice") == 1, "real plugin executed once before crash")
        self.stop(worker, kill=True)
        self.expire(task)
        second = self.start("worker-b", "worker")
        view = self.state(task, "SUCCEEDED")
        self.assert_that(self.fixture.tools.get("/page/first-slice") == 1, "successful tool not replayed by second worker")
        self.assert_that(self.fixture.models["first-slice"] == 2, "stored model response restored; only next round sent")
        self.assert_that(self.sql("SELECT fence FROM task WHERE id='" + task + "';") == "2", "fence strictly increases on recovery")
        self.assert_that(len(view["artifacts"]) == 1, "immutable artifact published")
        artifact = view["artifacts"][0]
        status, content = self.http("GET", "/api/artifacts/" + artifact["artifactId"] + "/download")
        self.assert_that(status == 200 and hashlib.sha256(content.encode()).hexdigest() == artifact["sha256"], "authorized artifact hash verified")
        self.stop(second)
        return task, artifact

    def crash_case(self, point, expected="SUCCEEDED", prefix="crash"):
        goal = prefix + "-" + point.lower()
        task = self.submit(goal)
        worker = self.start("before-" + goal, "worker", point)
        self.barrier(worker)
        before_models = self.fixture.models.get(goal, 0)
        before_tools = sum(v for k, v in self.fixture.tools.items() if goal in k)
        self.stop(worker, kill=True)
        self.expire(task)
        recovery = self.start("after-" + goal, "worker")
        view = self.state(task, expected)
        after_tools = sum(v for k, v in self.fixture.tools.items() if goal in k)
        if expected == "SUCCEEDED":
            expected_tools = 2 if prefix == "multi" else 1
            self.assert_that(after_tools == expected_tools, point + " tool count remains bounded", count=after_tools)
            self.assert_that(len(view["artifacts"]) == 1, point + " exactly one published artifact")
            expected_models = 3 if point == "M1_RESPONSE" else 2
            self.assert_that(self.fixture.models[goal] == expected_models, point + " deterministic model recovery", before=before_models, after=self.fixture.models[goal])
        else:
            self.assert_that(before_tools == after_tools, point + " unknown effect never retried", count=after_tools)
        self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND type='task.completed';") == ("1" if expected == "SUCCEEDED" else "0"), point + " terminal event exactly once")
        self.stop(recovery)

    def remaining(self, task, artifact):
        for subject in ["b", "c"]:
            self.assert_that(self.http("GET", "/api/tasks/" + task, subject=subject)[0] == 404, subject + " cannot query another owner")
            self.assert_that(self.http("GET", "/api/tasks/" + task + "/events", subject=subject, headers={"Last-Event-ID": "malformed"})[0] == 404, subject + " cannot read history even with bad cursor")
            self.assert_that(self.http("GET", "/api/artifacts/" + artifact["artifactId"] + "/download", subject=subject)[0] == 404, subject + " cannot download another owner artifact")
        self.assert_that(self.http("GET", "/api/capabilities", headers={"Authorization": "Bearer " + self.token(expired=True)})[0] == 401, "expired identity rejected")
        self.assert_that(self.http("GET", "/api/capabilities", headers={"Authorization": "Bearer " + self.token()[:-2] + "XX"})[0] == 401, "forged identity rejected")
        for extra in ["tenant", "actor", "tenantId"]:
            self.assert_that(self.http("POST", "/api/tasks", {"goal": "spoof", extra: "tenant-b"}, key="spoof")[0] == 400, extra + " self-reported identity rejected")
        self.assert_that(self.http("POST", "/api/tasks", {"goal": "resource", "resourceIds": ["tenant-b-secret"]}, key="resource")[0] == 403, "foreign credential handle rejected")
        self.assert_that(self.http("POST", "/api/tasks", {"goal": "money", "budget": {"maxCostMicros": 1}}, key="money")[0] == 400, "unconfigured currency rejected explicitly")
        self.assert_that(self.http("POST", "/api/tasks", {"goal": "oversize" * 1200}, key="large")[0] == 400, "UTF8 goal byte limit enforced")
        status, stream = self.http("GET", "/api/tasks/" + task + "/events")
        ids = re.findall(r"^id:(.+)$", stream, re.M)
        envelopes = [json.loads(line[5:]) for line in stream.splitlines() if line.startswith("data:")]
        self.assert_that(status == 200 and len(ids) == len(envelopes) and [e["seq"] for e in envelopes] == list(range(1, len(ids)+1)), "SSE ordered reliable replay", events=len(ids))
        self.assert_that(all(e["eventVersion"] == 1 and e["eventId"] == ids[i] and "status" in e for i, e in enumerate(envelopes)), "SSE v1 envelope and event ids")
        midpoint = len(ids)//2
        _, resumed = self.http("GET", "/api/tasks/" + task + "/events", headers={"Last-Event-ID": ids[midpoint-1]})
        self.assert_that(re.findall(r"^id:(.+)$", resumed, re.M) == ids[midpoint:], "SSE Last-Event-ID resumes without duplicate")
        self.assert_that(self.http("GET", "/api/tasks/" + task + "/events", headers={"Last-Event-ID": task + ":999999"})[0] == 400, "SSE future cursor rejected")
        self.sql("UPDATE event SET occurred_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE task_id='" + task + "';")
        self.assert_that(self.http("GET", "/api/tasks/" + task + "/events")[0] == 410, "SSE expired cursor requires snapshot")
        self.sql("UPDATE event SET occurred_at=CURRENT_TIMESTAMP(6) WHERE task_id='" + task + "';")
        self.assert_that(self.fixture.tools["/page/first-slice"] == 1, "SSE replay never executes agent")
        self.assert_that(self.http("POST", "/api/tasks/" + task + "/cancel")[0] == 200, "success before cancellation remains success")

        # Every durable boundary gets a real SIGKILL and another independent JVM.
        for point in ["M0", "M1", "M1_RESPONSE", "M2", "T0_BEFORE", "A0", "A1", "A2", "M3", "DONE"]:
            self.crash_case(point)
        self.crash_case("T1", prefix="multi")

        for point in ["T0_BEFORE", "T1"]:
            goal = "cancel-" + point.lower()
            tid = self.submit(goal)
            worker = self.start("worker-" + goal, "worker", point)
            self.barrier(worker)
            status, snapshot = self.http("POST", "/api/tasks/" + tid + "/cancel")
            self.assert_that(status == 202 and snapshot["state"] == "CANCEL_REQUESTED", point + " 202 is intent, not cancelled terminal")
            self.http("POST", "/api/tasks/" + tid + "/cancel")
            (worker["folder"] / "release").touch()
            self.state(tid, "CANCELLED")
            self.assert_that(self.fixture.tools.get("/page/" + goal, 0) == (1 if point == "T1" else 0), point + " cancellation stops new admission")
            self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='" + tid + "' AND type='task.cancel_requested';") == "1", "cancel event is idempotent " + point)
            self.stop(worker)

        self.sql("UPDATE tenant_budget SET queue_limit=2 WHERE tenant_id='tenant-a';")
        with concurrent.futures.ThreadPoolExecutor(3) as pool:
            queued = list(pool.map(lambda i: self.http("POST", "/api/tasks", {"goal": "queue-" + str(i)}, key="queue-" + str(i)), range(3)))
        self.assert_that(sorted(s for s, v in queued) == [202, 202, 429], "atomic queue reservation under concurrent acceptance")
        for status, value in queued:
            if status == 202:
                self.http("POST", "/api/tasks/" + value["taskId"] + "/cancel")
                self.http("POST", "/api/tasks/" + value["taskId"] + "/cancel")
        self.assert_that(self.sql("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a';") == "0", "queued cancellation releases reservation exactly once")
        self.sql("UPDATE tenant_budget SET queue_limit=16 WHERE tenant_id='tenant-a';")

        # Two workers contend for three tasks; two run reservations remain unique and the third waits.
        tids = [self.submit("concurrency-" + str(i)) for i in range(3)]
        a = self.start("concurrency-a", "worker", "M0")
        b = self.start("concurrency-b", "worker", "M0")
        self.barrier(a); self.barrier(b)
        self.assert_that(self.sql("SELECT running FROM tenant_budget WHERE tenant_id='tenant-a';") == "2", "two workers share bounded concurrent reservations")
        self.assert_that(self.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id IN ('" + "','".join(tids) + "');") == "2", "duplicate claim cannot create extra active attempt")
        (a["folder"] / "release").touch(); (b["folder"] / "release").touch()
        for tid in tids:
            self.state(tid, "SUCCEEDED")
        self.stop(a); self.stop(b)

        model_budget = self.submit("model-budget", budget={"maxModelRequests": 1})
        tool_budget = self.submit("multi-tool-budget", budget={"maxToolInvocations": 1})
        unknown_usage = self.submit("missing-usage")
        other = self.submit("tenant-b-task", subject="b")
        worker = self.start("budget-worker", "worker")
        self.state(model_budget, "FAILED"); self.state(tool_budget, "FAILED"); self.state(unknown_usage, "SUCCEEDED")
        eventually(lambda: self.http("GET", "/api/tasks/" + other, subject="b")[1]["state"] == "SUCCEEDED", "second tenant task")
        self.assert_that(self.fixture.models["model-budget"] == 1, "model limit persists across rounds")
        self.assert_that(sum(v for k, v in self.fixture.tools.items() if "multi-tool-budget" in k) == 1, "tool budget stops second slot")
        self.assert_that(self.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + unknown_usage + "' AND category='MODEL' AND state='HELD_UNKNOWN' AND actual IS NULL;") == "2", "missing usage retained as unknown, never zero cost")
        self.assert_that(self.fixture.identities["tenant-b-task"] == ["tenant-b", "tenant-b"], "model credentials bound to trusted tenant")
        self.assert_that(self.fixture.identities["first-slice"] == ["tenant-a", "tenant-a"], "first tenant credentials stay isolated")
        self.stop(worker)

        # Epoch revocation after callback construction must be checked again by the real gateway.
        revoked = self.submit("revoke-callback")
        worker = self.start("revoked-worker", "worker", "T0_BEFORE")
        self.barrier(worker)
        self.sql("UPDATE installation SET enabled=FALSE,epoch=epoch+1 WHERE tenant_id='tenant-a';")
        self.assert_that(self.http("GET", "/api/capabilities")[1] == [], "revoked capability disappears from discovery")
        self.assert_that(self.http("GET", "/api/artifacts/" + artifact["artifactId"] + "/download")[0] == 403, "revocation blocks old artifact download")
        (worker["folder"] / "release").touch()
        eventually(lambda: self.sql("SELECT state FROM task WHERE id='" + revoked + "';") == "FAILED", "revoked callback stopped")
        self.assert_that(self.fixture.tools.get("/page/revoke-callback", 0) == 0, "old callback cannot invoke revoked capability")
        self.stop(worker)
        self.sql("UPDATE installation SET enabled=TRUE,epoch=epoch+1 WHERE tenant_id='tenant-a';")
        self.sql("UPDATE actor_access SET enabled=FALSE,permission_version=permission_version+1 WHERE tenant_id='tenant-b';")
        self.assert_that(self.http("GET", "/api/capabilities", subject="b")[0] == 403, "actor revocation affects existing signed identity")
        self.sql("UPDATE actor_access SET enabled=TRUE,permission_version=permission_version+1 WHERE tenant_id='tenant-b';")

        self.assert_that(self.sql("SELECT SUM(queued+running) FROM tenant_budget;") == "0", "all confirmed terminal tasks release queue and run slots")
        # Unknown effects deliberately retain run occupancy pending operator reconciliation.
        self.sql("UPDATE tenant_budget SET running_limit=4 WHERE tenant_id='tenant-a';")
        self.crash_case("T0", "NEEDS_RECONCILIATION")
        self.crash_case("T1_BEFORE", "NEEDS_RECONCILIATION")
        unknown = self.submit("unknown-response-lost")
        worker = self.start("unknown-worker", "worker")
        self.state(unknown, "NEEDS_RECONCILIATION")
        self.assert_that(self.fixture.effects.get("/effect/unknown-response-lost") == 1, "fake service effect occurred once despite lost response")
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + unknown + "' AND state='OUTCOME_UNKNOWN';") == "1", "lost effect response has durable OUTCOME_UNKNOWN")
        self.stop(worker)

        # A still-live stale writer must be unable to submit its result after ownership has expired.
        stale = self.submit("stale-writer")
        old = self.start("stale-old", "worker", "T1_BEFORE")
        self.barrier(old)
        self.expire(stale)
        fresh = self.start("stale-new", "worker")
        self.state(stale, "NEEDS_RECONCILIATION")
        (old["folder"] / "release").touch()
        eventually(lambda: "FENCE_REJECTED" in (self.evidence / "stale-old.log").read_text(), "old writer rejected")
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + stale + "' AND state='SUCCEEDED';") == "0", "late old callback cannot overwrite unknown fact")
        self.assert_that(self.fixture.tools["/page/stale-writer"] == 1, "stale writer recovery does not replay effect")
        self.stop(old); self.stop(fresh)

    def cleanup(self):
        failures = []
        for child in reversed(self.children):
            try:
                self.stop({"process": child, "name": "cleanup"})
            except Exception as e:
                failures.append(str(e))
        self.fixture.shutdown()
        self.fixture.server_close()
        self.fixture_thread.join(timeout=5)
        if self.cid:
            try:
                tables = [("task", "tenant_id,id,state,fence,revision,models_used,tools_used,code"), ("task_attempt", "tenant_id,id,task_id,fence,state"), ("step", "tenant_id,id,task_id,ordinal,state,dispatches"), ("invocation", "tenant_id,id,task_id,step_id,slot,effect_intent_id,state,output_digest"), ("reservation", "tenant_id,id,task_id,category,state,amount,actual,usage_quality"), ("event", "tenant_id,task_id,seq,event_id,type,status,artifact_id"), ("artifact", "tenant_id,id,task_id,state,upload_id,sha256,size,snapshot_key"), ("tenant_budget", "*")]
                for table, fields in tables if self.schema_ready else []:
                    (self.evidence / (table + ".tsv")).write_text(self.sql("SELECT " + fields + " FROM " + table + ";"))
            except Exception as e:
                failures.append(str(e))
            try:
                label = run(["docker", "inspect", "--format", '{{index .Config.Labels "jimu.p2"}}', self.cid])
                assert label == self.name
                run(["docker", "rm", "-f", self.cid])
                assert not run(["docker", "ps", "-aq", "--filter", "label=jimu.p2=" + self.name])
            except Exception as e:
                failures.append(str(e))
        resolved = self.root.resolve()
        assert resolved.parent == Path(tempfile.gettempdir()).resolve() and resolved.name.startswith("jimu-p2-")
        shutil.rmtree(resolved)
        cleanup = {"containerRemoved": self.cid is not None and not failures, "rootRemoved": not resolved.exists(), "allChildrenExited": all(c.poll() is not None for c in self.children), "fixtureStopped": not self.fixture_thread.is_alive(), "failures": failures}
        (self.evidence / "cleanup.json").write_text(json.dumps(cleanup, indent=2))
        (self.evidence / "assertions.json").write_text(json.dumps(self.results, indent=2))
        assert not failures, failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--slice", action="store_true")
    args = parser.parse_args()
    experiment = Experiment(args.jar, args.evidence)
    try:
        experiment.prepare()
        task, artifact = experiment.first_slice()
        if not args.slice:
            experiment.remaining(task, artifact)
    finally:
        experiment.cleanup()


if __name__ == "__main__":
    main()
