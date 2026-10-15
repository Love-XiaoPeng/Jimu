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
import signal
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
        self.effect_ids = {}
        self.credentials = {"tenant-a": secrets.token_urlsafe(24), "tenant-b": secrets.token_urlsafe(24)}
        self.identities = {}
        self.partial = threading.Event()
        self.release_stream = threading.Event()
        self.available = True
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
            self.send(200 if self.server.available else 503, b"controlled provider ready", "text/plain")
            return
        tenant = next((k for k, v in self.server.credentials.items() if self.headers.get("Authorization") == "Bearer " + v), None)
        if tenant is None:
            self.send(401, {"error": "fixture credential required"})
            return
        if self.path.startswith("/effects/"):
            with self.server.lock:
                effect = self.server.effect_ids.get(self.path.removeprefix("/effects/"))
            self.send(200, effect) if effect and effect["tenant"] == tenant else self.send(404, {})
            return
        if self.path.startswith("/private/"):
            owner = self.path.split("/")[2]
            self.send(200 if owner == tenant else 403, (owner + "-fixture-private-content").encode() if owner == tenant else b"forbidden", "text/plain")
            return
        with self.server.lock:
            self.server.tools[self.path] = self.server.tools.get(self.path, 0) + 1
        if "/effect/" in self.path:
            with self.server.lock:
                self.server.effects[self.path] = self.server.effects.get(self.path, 0) + 1
                effect_id = self.headers["X-Jimu-Effect-Intent-Id"]
                effect = self.server.effect_ids.setdefault(effect_id, {"tenant": tenant, "path": self.path, "count": 0})
                effect["count"] += 1
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
        assert isinstance(body["stream"], bool)
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
            if goal == "foreign-origin":
                message["tool_calls"][0]["function"]["arguments"] = json.dumps({"url": "http://127.0.0.1:1/foreign"})
            if goal == "foreign-tenant-resource":
                message["tool_calls"][0]["function"]["arguments"] = json.dumps({"url": self.server.origin + "/private/tenant-b/document"})
            if goal == "unauthorized-tool":
                message["tool_calls"][0]["function"]["name"] = "shell"
            reason = "tool_calls"
        response = {"id": "controlled-response", "choices": [{"index": 0, "message": message, "finish_reason": reason}]}
        if not goal.startswith("missing-usage"):
            response["usage"] = {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
        if not body["stream"]:
            self.send(200, response)
            return
        chunks = [{"choices": [{"index": 0, "delta": {"role": "assistant"}}]}]
        if has_tool:
            text = message["content"]
            chunks.extend({"choices": [{"index": 0, "delta": {"content": piece}}]} for piece in [text[:5], text[5:]])
        else:
            for index, call in enumerate(message["tool_calls"]):
                args = call["function"]["arguments"]
                chunks.append({"choices": [{"index": 0, "delta": {"tool_calls": [{"index": index, "id": call["id"], "type": "function", "function": {"name": "web_fetch", "arguments": args[:10]}}]}}]})
                chunks.append({"choices": [{"index": 0, "delta": {"tool_calls": [{"index": index, "function": {"arguments": args[10:]}}]}}]})
        chunks.append({"choices": [{"index": 0, "delta": {}, "finish_reason": reason}], "usage": response.get("usage")})
        data = ("".join("data: " + json.dumps(c) + "\n\n" for c in chunks) + ("" if goal.startswith("stream-incomplete") else "data: [DONE]\n\n")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        split = data.index(b"\n\n") + 2
        try:
            self.wfile.write(data[:split]); self.wfile.flush()
            if goal.startswith("stream-partial") and self.server.models[goal] == 1:
                self.server.partial.set()
                self.server.release_stream.wait(20)
            self.wfile.write(data[split:])
        except (BrokenPipeError, ConnectionResetError):
            pass


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
        # All test HTTP targets are loopback; inherited desktop proxies must not route them.
        self.http_client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def assert_that(self, condition, name, **facts):
        assert condition, name + ": " + str(facts)
        self.results.append({"assertion": name, **facts})
        print("PASS", name, json.dumps(facts), flush=True)

    def sql(self, sql):
        return run(["docker", "exec", "-i", self.cid, "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B --raw jimu'], input=sql)

    def prepare(self):
        # Native files avoid WSL cross-filesystem JAR lookup costs; object semantics use ext4 too.
        packaged = self.root / "jimu.jar"
        shutil.copyfile(self.jar, packaged)
        self.results.append({"jarSha256": hashlib.sha256(packaged.read_bytes()).hexdigest()})
        self.jar = packaged
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
        common["jimu.host-id"] = str(uuid.uuid4())
        common["jimu.fixture.credentials"] = ";".join(k + ":" + v for k, v in self.fixture.credentials.items())
        self.common = common
        self.control = self.start("control", "control")
        self.schema_ready = True
        digest = "08a144489ed9ea37b5b33a5ffdc815237f31d4f229b76ba1d5e465b9e13f9ff8"
        self.sql("INSERT INTO tenant_budget(tenant_id) VALUES('tenant-a'),('tenant-b');"
                 "INSERT INTO actor_access(tenant_id,actor_id) VALUES('tenant-a','alice'),('tenant-b','bob'),('tenant-a','carol');"
                 "INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES"
                 "('tenant-a','web_fetch','0.2.0','" + digest + "'),('tenant-b','web_fetch','0.2.0','" + digest + "');")

    def start(self, name, role, boundary="", model_stream=False):
        folder = self.root / name
        folder.mkdir()
        config = dict(self.common, **{"jimu.role": role})
        config["jimu.model-stream"] = str(model_stream).lower()
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
            response = self.http_client.open(request, timeout=35)
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
        marker = eventually(lambda: (worker["folder"] / "reached").read_text() if (worker["folder"] / "reached").exists() else False, worker["name"] + " barrier")
        self.assert_that(self.sql("SELECT COUNT(*) FROM information_schema.innodb_trx;") == "0", worker["name"] + " external boundary holds no DB transaction")
        return marker

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

    def crash_case(self, point, expected="SUCCEEDED", prefix="crash", model_stream=False):
        goal = prefix + "-" + point.lower()
        task = self.submit(goal)
        worker = self.start("before-" + goal, "worker", point, model_stream=model_stream)
        self.barrier(worker)
        before_models = self.fixture.models.get(goal, 0)
        before_tools = self.tool_count(goal)
        self.stop(worker, kill=True)
        self.expire(task)
        recovery = self.start("after-" + goal, "worker", model_stream=model_stream)
        view = self.state(task, expected)
        after_tools = self.tool_count(goal)
        if expected == "SUCCEEDED":
            expected_tools = 2 if prefix == "multi" else 1
            self.assert_that(after_tools == expected_tools, point + " tool count remains bounded", count=after_tools)
            self.assert_that(len(view["artifacts"]) == 1, point + " exactly one published artifact")
            self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND type='artifact.created';") == "1", point + " artifact publication event idempotent")
            expected_models = 3 if point == "M1_RESPONSE" else 2
            self.assert_that(self.fixture.models[goal] == expected_models, point + " deterministic model recovery", before=before_models, after=self.fixture.models[goal])
        else:
            self.assert_that(before_tools == after_tools, point + " unknown effect never retried", count=after_tools)
        self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND type='task.completed';") == ("1" if expected == "SUCCEEDED" else "0"), point + " terminal event exactly once")
        self.stop(recovery)

    def tool_count(self, goal):
        return sum(v for k, v in self.fixture.tools.items() if k in ["/page/" + goal, "/effect/" + goal] or k.startswith("/page/" + goal + "-"))

    def reject_start(self, name, overrides, expected):
        folder = self.root / name
        folder.mkdir()
        config = dict(self.common, **{"jimu.role": "worker"})
        config.update(overrides)
        properties = folder / "runtime.properties"
        properties.write_text("\n".join(k + "=" + v for k, v in config.items()) + "\n")
        os.chmod(properties, 0o600)
        with (self.evidence / (name + ".log")).open("w") as log:
            child = subprocess.Popen([self.java, "-jar", str(self.jar), "--spring.config.additional-location=file:" + str(properties)], stdout=log, stderr=subprocess.STDOUT)
        self.children.append(child)
        self.assert_that(child.wait(timeout=40) != 0, name + " is required, startup rejects missing/incompatible resource")
        self.assert_that(expected in (self.evidence / (name + ".log")).read_text(), name + " fails for intended reason")

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
        for budget in [{"maxModelRequests": 1.5}, {"deadlineSeconds": "10"}, {"maxToolInvocations": -1}]:
            self.assert_that(self.http("POST", "/api/tasks", {"goal": "invalid-budget", "budget": budget}, key="invalid-budget")[0] == 400, "budget type and positive integer boundary " + str(budget))
        self.assert_that(self.http("POST", "/api/tasks", {"goal": "null-capability", "capabilityIds": [None]}, key="null-cap")[0] == 400, "null capability rejected at boundary")
        # Replay more than one 64-row batch, including terminal tasks.
        for index in range(100):
            self.sql("INSERT INTO event(tenant_id,task_id,seq,event_id,fact_id,type,status) SELECT tenant_id,id,next_event_seq+1,CONCAT(id,':',next_event_seq+1),'replay-" + str(index) + "','test.replay',state FROM task WHERE id='" + task + "'; UPDATE task SET next_event_seq=next_event_seq+1 WHERE id='" + task + "';")
        status, stream = self.http("GET", "/api/tasks/" + task + "/events")
        ids = re.findall(r"^id:(.+)$", stream, re.M)
        envelopes = [json.loads(line[5:]) for line in stream.splitlines() if line.startswith("data:")]
        self.assert_that(status == 200 and len(ids) > 100 and len(ids) == len(envelopes) and [e["seq"] for e in envelopes] == list(range(1, len(ids)+1)), "SSE ordered reliable replay", events=len(ids))
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
        snapshot_key = self.sql("SELECT snapshot_key FROM artifact WHERE task_id='" + task + "';")
        snapshot = self.root / "objects" / hashlib.sha256(b"tenant-a").hexdigest() / snapshot_key
        original = snapshot.read_bytes()
        os.chmod(snapshot, 0o600); snapshot.write_bytes(b"x" * len(original)); os.chmod(snapshot, 0o400)
        self.assert_that(self.http("GET", "/api/artifacts/" + artifact["artifactId"] + "/download")[0] == 503, "artifact corruption cannot bypass download integrity check")
        os.chmod(snapshot, 0o600); snapshot.write_bytes(original); os.chmod(snapshot, 0o400)
        self.sql("UPDATE artifact SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id='" + task + "';")
        self.assert_that(self.http("GET", "/api/artifacts/" + artifact["artifactId"] + "/download")[0] == 410, "owner receives explicit artifact expiry")
        self.stop(self.control)
        run(["docker", "restart", self.cid])
        eventually(lambda: self.database_ready(), "database restart durability")
        newport = int(run(["docker", "port", self.cid, "3306/tcp"]).split(":")[-1])
        self.common["jimu.db.url"] = self.common["jimu.db.url"].replace(":" + str(self.dbport) + "/", ":" + str(newport) + "/")
        self.dbport = newport
        self.control = self.start("control-after-db-restart", "control")
        self.assert_that(self.http("GET", "/api/tasks/" + task)[1]["state"] == "SUCCEEDED", "task ledger survives real MySQL container restart")
        self.assert_that(self.http("GET", "/api/tasks/" + task + "/events", headers={"Last-Event-ID": ids[midpoint-1]})[1].count("\nid:") + 1 == len(ids) - midpoint, "SSE survives control and database restart")
        run(["docker", "stop", self.cid])
        self.assert_that(self.http("GET", "/readyz")[0] == 503, "database loss makes control unready")
        self.assert_that(self.http("GET", "/api/tasks/" + task)[0] == 503, "database loss returns safe 503 rather than false success")
        run(["docker", "start", self.cid])
        eventually(lambda: self.database_ready(), "private database restored")
        newport = int(run(["docker", "port", self.cid, "3306/tcp"]).split(":")[-1])
        self.common["jimu.db.url"] = self.common["jimu.db.url"].replace(":" + str(self.dbport) + "/", ":" + str(newport) + "/")
        self.dbport = newport
        self.stop(self.control)
        self.control = self.start("control-after-outage", "control")
        self.reject_start("missing-store", {"jimu.storage-root": str(self.root / "missing-objects")}, "missing-objects")
        self.reject_start("missing-database", {"jimu.db.url": "jdbc:mysql://127.0.0.1:1/missing?connectTimeout=1000"}, "Communications link failure")
        self.sql("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) SELECT MAX(installed_rank)+1,'999','future schema','SQL','future.sql','fixture',0,TRUE FROM flyway_schema_history;")
        self.reject_start("wrong-schema", {}, "Unsupported schema version")
        self.sql("DELETE FROM flyway_schema_history WHERE version='999';")

        # Every durable boundary gets a real SIGKILL and another independent JVM.
        for point in ["M0", "M1", "M1_RESPONSE", "M2", "T0_BEFORE", "A0", "A1", "A2", "M3", "DONE"]:
            self.crash_case(point)
        self.crash_case("T1", prefix="multi")
        for point in ["M1_RESPONSE", "T1"]:
            self.crash_case(point, prefix="stream", model_stream=True)
        partial = self.submit("stream-partial")
        old_stream = self.start("stream-partial-old", "worker", model_stream=True)
        eventually(self.fixture.partial.is_set, "provider sent partial SSE")
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + partial + "';") == "0", "partial model delta cannot create invocation")
        self.stop(old_stream, kill=True)
        self.fixture.release_stream.set()
        self.expire(partial)
        new_stream = self.start("stream-partial-new", "worker", model_stream=True)
        self.state(partial, "SUCCEEDED")
        self.assert_that(self.fixture.models["stream-partial"] == 3, "interrupted stream dispatch retained and reissued within budget")
        self.assert_that(self.fixture.tools["/page/stream-partial"] == 1, "stream recovery executes tool only after complete response")
        incomplete = self.submit("stream-incomplete")
        self.state(incomplete, "FAILED")
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + incomplete + "';") == "0", "SSE without DONE is not a response")
        self.stop(new_stream)

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

        # An expired lease is not proof that the old model dispatcher has stopped.
        cancelled = self.submit("cancel-live-m1")
        old = self.start("cancel-live-old", "worker", "M1")
        self.barrier(old)
        self.http("POST", "/api/tasks/" + cancelled + "/cancel")
        os.kill(self.control["process"].pid, signal.SIGSTOP)
        try:
            self.expire(cancelled)
            fresh = self.start("cancel-live-contender", "worker")
            self.assert_that(old["process"].poll() is None and self.sql("SELECT state FROM task WHERE id='" + cancelled + "';") == "CANCEL_REQUESTED", "expired lease cannot finalize cancellation while old worker is live")
            self.assert_that(self.sql("SELECT state FROM reservation WHERE id='" + cancelled + ":run';") == "RESERVED", "live cancelled worker retains concurrency reservation")
            (old["folder"] / "release").touch()
            eventually(lambda: "Execution stopped:" in (self.evidence / "cancel-live-old.log").read_text(), "old cancelled dispatcher rechecks admission after barrier")
            self.assert_that(self.fixture.models.get("cancel-live-m1", 0) == 0, "cancelled stale model dispatcher causes zero HTTP requests")
            self.stop(fresh)
        finally:
            os.kill(self.control["process"].pid, signal.SIGCONT)
        eventually(lambda: old["process"].poll() is not None, "cancelled stale worker stop confirmed", 12)
        self.state(cancelled, "CANCELLED")
        self.assert_that(self.fixture.models.get("cancel-live-m1", 0) == 0, "no model dispatch after cancelled terminal")

        # Current implementation and persisted pins must agree before any effect.
        original_pin = self.sql("SELECT plugin_digest FROM installation WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
        for field, value in [("plugin_version", "99.0.0"), ("plugin_digest", "0" * 64)]:
            pinned = self.submit("pin-" + field)
            writer = self.start("pin-writer-" + field, "worker", "T0_BEFORE")
            self.barrier(writer)
            self.sql("UPDATE installation SET " + field + "='" + value + "' WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
            (writer["folder"] / "release").touch()
            self.state(pinned, "FAILED")
            self.assert_that(self.sql("SELECT code FROM task WHERE id='" + pinned + "';") == "PLUGIN_PIN_MISMATCH" and self.fixture.tools.get("/page/pin-" + field, 0) == 0, field + " drift rejects before effect")
            self.stop(writer)
            self.sql("UPDATE installation SET plugin_version='0.2.0',plugin_digest='" + original_pin + "' WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
        self.sql("UPDATE installation SET plugin_version='99.0.0' WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
        pinned = self.submit("pin-already-wrong")
        writer = self.start("pin-already-wrong-worker", "worker")
        self.state(pinned, "FAILED")
        self.assert_that(self.sql("SELECT code FROM task WHERE id='" + pinned + "';") == "PLUGIN_PIN_MISMATCH" and self.fixture.tools.get("/page/pin-already-wrong", 0) == 0, "persisted unsupported implementation pin cannot run builtin")
        self.stop(writer)
        self.sql("UPDATE installation SET plugin_version='0.2.0' WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")

        pinned = self.submit("pin-recovery")
        writer = self.start("pin-recovery-old", "worker", "T0_BEFORE")
        self.barrier(writer); self.stop(writer, kill=True)
        self.sql("UPDATE installation SET plugin_digest='" + "1" * 64 + "' WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
        self.expire(pinned)
        reader = self.start("pin-recovery-new", "worker")
        self.state(pinned, "FAILED")
        self.assert_that(self.sql("SELECT code FROM task WHERE id='" + pinned + "';") == "PLUGIN_PIN_MISMATCH" and self.fixture.tools.get("/page/pin-recovery", 0) == 0, "recovery checks persisted pin against implementation and current installation")
        self.stop(reader)
        self.sql("UPDATE installation SET plugin_digest='" + original_pin + "' WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
        pinned = self.submit("pin-installation-missing")
        writer = self.start("pin-installation-missing-worker", "worker", "T0_BEFORE")
        self.barrier(writer)
        self.sql("DELETE FROM installation WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
        (writer["folder"] / "release").touch()
        eventually(lambda: self.sql("SELECT state FROM task WHERE id='" + pinned + "';") == "FAILED", "missing installation stops task")
        self.assert_that(self.http("GET", "/api/tasks/" + pinned)[0] == 403, "missing installation revokes task visibility")
        self.assert_that(self.sql("SELECT code FROM task WHERE id='" + pinned + "';") == "CAPABILITY_REVOKED" and self.fixture.tools.get("/page/pin-installation-missing", 0) == 0, "missing installation rejects bound callback before effect")
        self.stop(writer)
        self.sql("INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','web_fetch','0.2.0','" + original_pin + "');")

        abandoned = self.submit("cancel-artifact-upload")
        writer = self.start("artifact-upload-writer", "worker", "A1")
        self.barrier(writer)
        self.http("POST", "/api/tasks/" + abandoned + "/cancel")
        eventually(lambda: writer["process"].poll() is not None, "supervisor confirms non-cooperating worker stopped", 12)
        self.state(abandoned, "CANCELLED")
        upload_id = self.sql("SELECT upload_id FROM artifact WHERE task_id='" + abandoned + "';")
        objects = list((self.root / "objects").rglob(upload_id + "*"))
        self.assert_that(len(objects) == 1, "unpublished immutable object remains associated with upload intent")
        self.sql("UPDATE artifact SET updated_at=CURRENT_TIMESTAMP(6)-INTERVAL 2 HOUR WHERE task_id='" + abandoned + "';")
        eventually(lambda: self.sql("SELECT r.state FROM reservation r JOIN artifact a ON r.tenant_id=a.tenant_id AND r.id=a.id WHERE a.task_id='" + abandoned + "';") == "RELEASED", "stopped upload reconciled and storage budget released")
        self.assert_that(not any(p.exists() for p in objects), "unpublished objects cleaned only after confirmed writer stop")
        self.assert_that(self.sql("SELECT COUNT(*) FROM event WHERE task_id='" + abandoned + "' AND type='artifact.created';") == "0", "cancelled upload never emits artifact.created")

        invalid_payload = self.submit("wrong-payload-version")
        producer = self.start("wrong-payload-producer", "worker", "M2")
        self.barrier(producer); self.stop(producer, kill=True)
        self.sql("UPDATE step SET record_version=99 WHERE task_id='" + invalid_payload + "';")
        self.expire(invalid_payload)
        reader = self.start("wrong-payload-reader", "worker")
        self.state(invalid_payload, "FAILED")
        self.assert_that(self.fixture.tools.get("/page/wrong-payload-version", 0) == 0, "unsupported persisted version cannot dispatch a tool")
        self.stop(reader)

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

        tenant_a = self.submit("parallel-tenant-a")
        a = self.start("parallel-tenant-a-worker", "worker", "M0")
        self.barrier(a)
        tenant_b = self.submit("parallel-tenant-b", subject="b")
        b = self.start("parallel-tenant-b-worker", "worker", "M0")
        self.barrier(b)
        self.assert_that(self.sql("SELECT COUNT(DISTINCT tenant_id) FROM task WHERE id IN ('" + tenant_a + "','" + tenant_b + "') AND state='RUNNING';") == "2", "independent workers execute two trusted tenants concurrently")
        self.assert_that(self.http("GET", "/api/tasks/" + tenant_a, subject="b")[0] == 404 and self.http("GET", "/api/tasks/" + tenant_b)[0] == 404, "parallel task snapshots cannot cross tenant boundary")
        (a["folder"] / "release").touch(); (b["folder"] / "release").touch()
        self.state(tenant_a, "SUCCEEDED")
        eventually(lambda: self.http("GET", "/api/tasks/" + tenant_b, subject="b")[1]["state"] == "SUCCEEDED", "parallel second tenant completes")
        self.assert_that(self.fixture.identities["parallel-tenant-a"] == ["tenant-a", "tenant-a"] and self.fixture.identities["parallel-tenant-b"] == ["tenant-b", "tenant-b"], "concurrent model credentials and checkpoint history stay tenant bound")
        self.stop(a); self.stop(b)

        health_worker = self.start("provider-health-worker", "worker")
        self.fixture.available = False
        queued_provider = self.submit("wait-for-provider")
        time.sleep(.8)
        self.assert_that(self.http("GET", "/readyz", instance=health_worker)[0] == 503, "missing provider makes worker unready")
        self.assert_that(self.http("GET", "/api/tasks/" + queued_provider)[1]["state"] == "QUEUED" and self.fixture.models.get("wait-for-provider", 0) == 0, "unready worker refuses claim and provider dispatch")
        self.fixture.available = True
        self.state(queued_provider, "SUCCEEDED")
        self.stop(health_worker)

        model_budget = self.submit("model-budget", budget={"maxModelRequests": 1})
        tool_budget = self.submit("multi-tool-budget", budget={"maxToolInvocations": 1})
        unknown_usage = self.submit("missing-usage")
        other = self.submit("tenant-b-task", subject="b")
        foreign_origin = self.submit("foreign-origin")
        foreign_tenant = self.submit("foreign-tenant-resource")
        unauthorized_tool = self.submit("unauthorized-tool")
        worker = self.start("budget-worker", "worker")
        self.state(model_budget, "FAILED"); self.state(tool_budget, "FAILED"); self.state(unknown_usage, "SUCCEEDED")
        eventually(lambda: self.http("GET", "/api/tasks/" + other, subject="b")[1]["state"] == "SUCCEEDED", "second tenant task")
        self.assert_that(self.fixture.models["model-budget"] == 1, "model limit persists across rounds")
        self.assert_that(self.tool_count("multi-tool-budget") == 1, "tool budget stops second slot")
        self.assert_that(self.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + unknown_usage + "' AND category='MODEL' AND state='HELD_UNKNOWN' AND actual IS NULL;") == "2", "missing usage retained as unknown, never zero cost")
        self.assert_that(self.fixture.identities["tenant-b-task"] == ["tenant-b", "tenant-b"], "model credentials bound to trusted tenant")
        self.assert_that(self.fixture.identities["first-slice"] == ["tenant-a", "tenant-a"], "first tenant credentials stay isolated")
        self.state(foreign_origin, "FAILED")
        self.state(foreign_tenant, "SUCCEEDED")
        self.state(unauthorized_tool, "FAILED")
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='" + foreign_origin + "';") == "0", "foreign origin rejected before tool dispatch")
        self.assert_that(self.sql("SELECT output_snapshot FROM invocation WHERE task_id='" + foreign_tenant + "';").find("tenant-b-fixture-private-content") == -1, "tenant bound connection credential cannot read another tenant resource")
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='" + unauthorized_tool + "';") == "0", "model cannot select unauthorized tool")
        self.stop(worker)

        # Epoch revocation after callback construction must be checked again by the real gateway.
        revoked = self.submit("revoke-callback")
        worker = self.start("revoked-worker", "worker", "T0_BEFORE")
        self.barrier(worker)
        live = []
        for _ in range(2):
            connection = http.client.HTTPConnection("127.0.0.1", self.control["port"], timeout=8)
            connection.request("GET", "/api/tasks/" + revoked + "/events", headers={"Authorization": "Bearer " + self.token()})
            response = connection.getresponse()
            self.assert_that(response.status == 200, "live SSE subscription authorized")
            live.append((connection, response))
        self.assert_that(self.http("GET", "/api/tasks/" + revoked + "/events")[0] == 429, "per actor/task SSE subscription cap enforced")
        self.sql("UPDATE installation SET enabled=FALSE,epoch=epoch+1 WHERE tenant_id='tenant-a';")
        for connection, response in live:
            started = time.monotonic()
            response.read(); connection.close()
            self.assert_that(time.monotonic() - started < 5, "existing SSE closes after capability revocation")
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
        self.assert_that(self.fixture.effects.get("/effect/unknown-response-lost") == 1, "fake service effect occurred once despite lost response", count=self.fixture.effects.get("/effect/unknown-response-lost"))
        self.assert_that(self.sql("SELECT COUNT(*) FROM invocation WHERE task_id='" + unknown + "' AND state='OUTCOME_UNKNOWN';") == "1", "lost effect response has durable OUTCOME_UNKNOWN")
        self.stop(worker)
        effect_id = self.sql("SELECT effect_intent_id FROM invocation WHERE task_id='" + unknown + "';")
        request = urllib.request.Request(self.fixture.origin + "/effects/" + effect_id, headers={"Authorization": "Bearer " + self.fixture.credentials["tenant-a"]})
        with self.http_client.open(request, timeout=5) as response:
            effect = json.load(response)
        self.assert_that(effect["count"] == 1 and effect["path"] == "/effect/unknown-response-lost", "queryable service reconciles stable effect intent", effectId=effect_id, count=effect["count"])
        self.assert_that(self.http("POST", "/api/tasks/" + unknown + "/cancel")[1]["state"] == "NEEDS_RECONCILIATION", "unknown effect cancellation never reports safe cancellation")
        recovery = self.start("unknown-no-retry", "worker")
        time.sleep(.5)
        self.stop(recovery)
        self.assert_that(self.fixture.effects.get("/effect/unknown-response-lost") == 1, "unknown outcome remains single effect after worker restart", count=self.fixture.effects.get("/effect/unknown-response-lost"))

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
        eventually(lambda: self.sql("SELECT SUM(queued+running) FROM tenant_budget;") == "0", "confirmed process stops release run occupancy while unknown effect cost stays held")

    def database_ready(self):
        try:
            return self.sql("SELECT 1;") == "1"
        except subprocess.CalledProcessError:
            return False

    def cleanup(self):
        failures = []
        self.fixture.release_stream.set()
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
                tables = [("task", "tenant_id,id,state,current_attempt_id,worker_id,fence,lease_until,revision,models_used,tools_used,token_held,code"), ("task_attempt", "tenant_id,id,task_id,worker_id,fence,state"), ("step", "tenant_id,id,task_id,attempt_id,ordinal,state,dispatch_id,dispatches,request_digest,response_digest,request_snapshot,response_snapshot"), ("invocation", "tenant_id,id,task_id,step_id,attempt_id,slot,provider_id,effect_intent_id,state,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,output_snapshot,output_digest"), ("reservation", "tenant_id,id,task_id,dispatch_id,category,state,amount,actual,usage_quality,cost_quality"), ("event", "tenant_id,task_id,seq,event_id,type,status,artifact_id"), ("artifact", "tenant_id,id,task_id,state,upload_id,sha256,size,snapshot_key"), ("tenant_budget", "*"), ("worker_process", "*"), ("flyway_schema_history", "version,script,checksum,success")]
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
        (self.evidence / "fixture-counts.json").write_text(json.dumps({"models": self.fixture.models, "tools": self.fixture.tools, "effects": self.fixture.effects, "effectIds": self.fixture.effect_ids, "tenantBindings": self.fixture.identities}, indent=2))
        assert not failures, failures


def main():
    def terminate(signum, frame):
        raise KeyboardInterrupt("Test supervisor terminated")
    signal.signal(signal.SIGTERM, terminate)
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
