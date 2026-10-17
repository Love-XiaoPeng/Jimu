"""Freeze public sources and exact repository contracts. No model/search API or credentials."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent / "Jimu"
SHA = "a20cefbf9004561bd14806c83e20c2c8940b9408"
JAVA = "src/main/java/io/github/lovexiaopeng/jimu/"
RES = "src/main/resources/plugins/builtin/"
WEB_OUTPUT = "docs/baseline/XIAO-33-web-fetch-output-v0.2.0.schema.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write(path, data):
    dest = ROOT / path
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))


def savejson(path, obj):
    write(path, json.dumps(obj, ensure_ascii=False, indent=2) + "\n")


def gitfile(path):
    return subprocess.check_output(["git", "show", f"{SHA}:{path}"], cwd=REPO)


contracts = []
paths = [JAVA + x for x in (
    "adapters/runtime/web/PublicPageProjection.java",
    "adapters/runtime/contract/ReportPolicy.java",
    "adapters/ai/springai/DurableAgent.java",
    "adapters/ai/springai/ToolDefinitionMapper.java",
    "adapters/ai/springai/OpenAiProtocol.java",
    "adapters/persistence/Payloads.java",
    "adapters/persistence/ModelCosts.java",
    "adapters/runtime/model/ModelProfiles.java",
    "model/api/ModelSnapshot.java",
    "plugins/builtin/webfetch/FetchOutput.java",
)]
paths += [RES + f"{tool}/{file}" for tool in ("webfetch", "read", "write", "present")
          for file in ("input.schema.json", "output.schema.json", "plugin.yaml") if not (tool == "webfetch" and file == "output.schema.json")]
paths += [WEB_OUTPUT]
paths += [RES + "webfetch-public/plugin.yaml", "src/main/resources/db/migration/V4__model_snapshots_and_budgets.sql"]
for path in paths:
    data = gitfile(path)
    dest = "contracts/repository/" + path
    write(dest, data)
    contracts.append({"repository_path": path, "path": dest, "bytes": len(data), "sha256": digest(data)})
savejson("contracts/repository-manifest.json", {"git_sha": SHA, "files": contracts})

descriptions = {
    "web_fetch": "Fetch from the configured origin",
    "read": "Read a UTF-8 task file with line numbers; observe missing files before write",
    "write": "Create or replace UTF-8 task file using the host's observed version",
    "present": "Publish existing observed Markdown reports as immutable authorized deliverables",
}
definitions = []
pins = []
for name, folder in (("web_fetch", "webfetch"), ("read", "read"), ("write", "write"), ("present", "present")):
    inp = gitfile(RES + folder + "/input.schema.json")
    out = gitfile(WEB_OUTPUT if name == "web_fetch" else RES + folder + "/output.schema.json")
    definitions.append({"type": "function", "function": {"name": name, "description": descriptions[name], "parameters": json.loads(inp)}})
    pins.append({"name": name, "pluginId": "jimu.web.fetch" if name == "web_fetch" else ("jimu.artifact.present" if name == "present" else "jimu.workspace." + name),
                 "version": "0.3.0" if name == "web_fetch" else "0.1.0", "inputSchemaSha256": digest(inp), "outputSchemaSha256": digest(out)})
savejson("contracts/tools.json", definitions)
savejson("contracts/tool-pins.json", {"orderedCapabilities": list(descriptions), "pins": pins, "installationEpoch": None,
                                     "epochBinding": "bind actual authorized installation epochs once at trusted task admission; never guess"})

policy = gitfile(JAVA + "adapters/runtime/contract/ReportPolicy.java").decode()
method = policy.split("public String instruction(ReportContext context) {", 1)[1].split("\n  }", 1)[0]
before, after = method.split("context.productionTime()", 1)
decode_literals = lambda text: "".join(json.loads(x) for x in re.findall(r'"(?:[^"\\]|\\.)*"', text))
system = decode_literals(before) + "{{HOST_REPORT_TIME}}" + decode_literals(after)
write("prompts/host-system.template.txt", system)
savejson("prompts/host-system-binding.json", {"template": "prompts/host-system.template.txt", "sha256": digest(system.encode()),
          "onlyVariable": "HOST_REPORT_TIME", "source": "task.report_time reserved by host at admission, formatted by Java Instant.toString()",
          "mustNotOverrideWithPlanTime": True, "recordExpandedSystemSha256AtAdmission": True})


class PublicRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if newurl != req.full_url:
            raise RuntimeError("Freeze source URL changed; do not silently follow")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_source(pair):
    name, url = pair
    opener = urllib.request.build_opener(PublicRedirects())
    req = urllib.request.Request(url, headers={"User-Agent": "Jimu-public-source-freeze/1.0", "Accept-Encoding": "identity"})
    with opener.open(req, timeout=40) as response:
        data = response.read(2_000_001)
        if len(data) > 2_000_000 or response.status != 200:
            raise RuntimeError("Invalid public source status/size")
        record = {"id": name, "url": url, "finalUrl": response.url, "httpStatus": response.status,
                  "contentType": response.headers.get("Content-Type"), "etag": response.headers.get("ETag"),
                  "lastModified": response.headers.get("Last-Modified"), "fetchedAtUtc": datetime.now(timezone.utc).isoformat(),
                  "rawPath": f"sources/{name}.raw", "rawSha256": digest(data), "rawBytes": len(data),
                  "authorizationUsed": False, "cookiesUsed": False}
    data.decode("utf-8", errors="strict")
    write(record["rawPath"], data)
    return record


source_urls = {"rfc768": "https://www.rfc-editor.org/rfc/rfc768.txt", "colorsys": "https://docs.python.org/3/library/colorsys.html"}
if "--offline-reproject" in sys.argv:
    source_records = json.loads((ROOT / "sources/raw-manifest.json").read_text(encoding="utf-8"))["sources"]
else:
    with ThreadPoolExecutor(max_workers=2) as pool:
        source_records = list(pool.map(fetch_source, source_urls.items()))
    savejson("sources/raw-manifest.json", {"purpose": "anonymous source material freeze only; not application web-tool acceptance", "sources": source_records})

# Compile only exact source projection + output record and an offline wrapper using cached deps.
# No full verify, Maven download, server, MySQL or worker is started.
deps = Path("/root/.m2/repository")
jars = [deps / p for p in (
    "org/jsoup/jsoup/1.19.1/jsoup-1.19.1.jar",
    "com/fasterxml/jackson/core/jackson-databind/2.21.7/jackson-databind-2.21.7.jar",
    "com/fasterxml/jackson/core/jackson-core/2.21.7/jackson-core-2.21.7.jar",
    "com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar",
    "org/apache/httpcomponents/core5/httpcore5/5.4.3/httpcore5-5.4.3.jar",
)]
assert all(p.is_file() for p in jars)
savejson("sources/projection-dependencies.json", [{"artifact": str(p.relative_to(deps)), "sha256": digest(p.read_bytes())} for p in jars])
wrapper = '''package io.github.lovexiaopeng.jimu.adapters.runtime.web;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
public class FreezeProjection {
  public static void main(String[] args) throws Exception {
    var output = PublicPageProjection.project(200, args[0], args[1], Files.readAllBytes(Path.of(args[2])));
    var json = io.github.lovexiaopeng.jimu.adapters.persistence.Payloads.json(output);
    Files.writeString(Path.of(args[3]), json);
    Files.writeString(Path.of(args[4]), output.content());
  }
}
'''
write("offline/FreezeProjection.java", wrapper)
classes = ROOT / "offline/classes"
classes.mkdir(parents=True, exist_ok=True)
classpath = ":".join(str(p) for p in jars)
subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes),
                str(ROOT / "contracts/repository" / (JAVA + "adapters/runtime/web/PublicPageProjection.java")),
                str(ROOT / "contracts/repository" / (JAVA + "plugins/builtin/webfetch/FetchOutput.java")),
                str(ROOT / "contracts/repository" / (JAVA + "adapters/persistence/Payloads.java")),
                str(ROOT / "contracts/repository" / (JAVA + "model/api/ModelSnapshot.java")),
                str(ROOT / "offline/FreezeProjection.java")], check=True, capture_output=True)
for s in source_records:
    stem = "sources/" + s["id"]
    subprocess.run(["java", "-cp", str(classes) + ":" + classpath,
                    "io.github.lovexiaopeng.jimu.adapters.runtime.web.FreezeProjection", s["url"], s["contentType"],
                    str(ROOT / s["rawPath"]), str(ROOT / (stem + ".fetch.json")), str(ROOT / (stem + ".visible.txt"))],
                   check=True, capture_output=True)
    fetch_data = (ROOT / (stem + ".fetch.json")).read_bytes()
    output = json.loads(fetch_data)
    assert output["httpStatus"] == 200 and output["sourceUrl"] == s["url"] and not output["truncated"]
    s.update({"canonicalOutputPath": stem + ".fetch.json", "canonicalOutputSha256": digest(fetch_data),
              "visibleContentPath": stem + ".visible.txt", "visibleContentSha256": digest(output["content"].encode()),
              "projection": "exact PublicPageProjection.java from " + SHA,
              "canonicalSerializer": "exact Payloads.json from sourceGitSha; raw UTF-8, no added newline",
              "truncated": False})
savejson("sources/source-manifest.json", {"sources": source_records,
          "liveValidation": "compare actual canonical fetch fields and visible content hashes before next model dispatch; mismatch stops sample without rebaselining or retry",
          "rawHashIsSupportingEvidence": "wire content encodings/irrelevant HTML may differ, but actual model-visible canonical output must match",
          "isLiveProviderOrApplicationAcceptance": False})
print(json.dumps({"gitSha": SHA, "repositoryFiles": len(contracts), "tools": len(definitions),
                  "sourceHashes": [{"id": s["id"], "rawSha256": s["rawSha256"], "visibleContentSha256": s["visibleContentSha256"]} for s in source_records]}, indent=2))
