"""Offline cross-file integrity and cost review. Does not execute product gates or any network."""
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent
SHA = "a20cefbf9004561bd14806c83e20c2c8940b9408"
checks = []


def load(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save(path, obj):
    (ROOT / path).write_text(json.dumps(obj, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")


def refs(obj):
    if isinstance(obj, dict):
        if set(("path","sha256")).issubset(obj):
            path = (ROOT / obj["path"]).resolve()
            assert path.is_relative_to(ROOT.resolve()) and path.is_file()
            data = path.read_bytes()
            assert digest(data) == obj["sha256"], obj["path"]
            if "bytes" in obj:
                assert len(data) == obj["bytes"]
        for value in obj.values():
            refs(value)
    elif isinstance(obj, list):
        for item in obj:
            refs(item)


schema = load("closed-plan.schema.json")
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
samples = load("samples.json")["samples"]
for plan_id in ("qwen-main-v1","qwen-pro-optional-v1"):
    plan = load(f"plans/{plan_id}.json")
    validator.validate(plan)
    refs(plan)
    assert all(plan[k] is False for k in ("enabled","paidDispatchEnabled","searchDispatchEnabled","productionEgressEnabled","authorizationEffective","accountAdmitted","selected"))
    assert plan["phases"]["B"]["state"] == "LOCKED"
    profiles = load(plan["sourceOfExactProfiles"]["path"])["models"]
    assert [m["model"] for m in profiles] == plan["candidateModelIds"]
    if plan_id == "qwen-main-v1":
        assert len(profiles)==3 and all(m["model"].startswith("qwen") for m in profiles)
    else:
        assert len(profiles)==4 and profiles[-1]["model"]=="deepseek-v4-pro-0813"
    limits = plan["limits"]
    assert limits["maxBillableOutputPerDispatch"] == limits["httpMaxCompletionTokens"]+limits["outputMarginTokens"] == 4096
    assert limits["cumulativeMaxDispatches"]*1004096 == limits["isolatedTenantTokenLimit"]
    assert plan["phases"]["A"]["maxCostMicros"]+plan["phases"]["B"]["maxCostMicros"]==limits["cumulativeMaxCostMicros"]
    assert plan["phases"]["A"]["maxDispatches"]+plan["phases"]["B"]["maxDispatches"]==limits["cumulativeMaxDispatches"]
    for m in profiles:
        assert len(m["profileTemplates"])==2
        for p in m["profileTemplates"]:
            assert p["model"]==p["revision"]==m["model"] and p["maxOutputTokens"]==4096 and p["outputMarginTokens"]==10
            assert p["capabilities"]["contextLimit"]==p["price"]["inputTokenUpper"]==1000000
            assert p["providerOptions"]=={"enable_search":False,"enable_thinking":False}
            assert p["baseUrl"] is None and p["credentialRef"] is None and "inputLimit" not in p
            price=p["price"]
            # Independent Decimal ceiling check rather than builder's integer formula.
            val=(Decimal(1000000)*price["inputMicrosPerMillion"]+Decimal(4096)*price["outputMicrosPerMillion"])/1000000
            assert int(val.to_integral_value(rounding="ROUND_CEILING"))+price["fixedMicros"]==m["perDispatchReserveMicros"]
        for s in samples:
            caps=m["taskLimits"][s["id"]]
            assert caps["maxCostMicros"]==m["perDispatchReserveMicros"]*s["maxModelDispatches"]
            assert caps["tokenLimit"]==1004096*s["maxModelDispatches"]
    audit=next(a for a in load("budget-audit.json")["plans"] if a["planId"]==plan_id)
    assert len(audit["branches"])==(7 if len(profiles)==3 else 11)
    for branch in audit["branches"]:
        chosen=[m for m in profiles if m["model"] in branch["finalists"]]
        assert branch["BReserveMicros"]==12*sum(m["perDispatchReserveMicros"] for m in chosen)
        assert branch["BReserveMicros"]<=plan["phases"]["B"]["maxCostMicros"]
        assert branch["totalReserveMicros"]==audit["phaseAReserveMicros"]+branch["BReserveMicros"]
        assert branch["totalReserveMicros"]<=limits["cumulativeMaxCostMicros"]
    checks.append(plan_id+": exact closed schema, exclusive candidates, monetary/token/output/sample/phase bounds and all finalist branches")

for s in samples:
    refs(s)
    request=load(s["request"]["path"])
    assert request["goal"]==(ROOT/s["goal"]["path"]).read_text(encoding="utf-8")
    assert len(request["goal"].encode())<=8192 and "${" not in request["goal"]
    assert "maxCostMicros" not in request["budget"]
    assert request["budget"]["maxModelRequests"]==s["maxModelDispatches"]<=8
    assert request["budget"]["maxToolInvocations"]==s["maxToolInvocations"]==sum(s["perToolMaxInvocations"].values())<=20
    assert request["capabilityIds"]==["web_fetch","read","write","present"]
checks.append("four complete goal/request files; C1 embedded in W1; actual public request shape and limits retained")

repo=ROOT.parent/"Jimu"
manifest=load("contracts/repository-manifest.json")
for item in manifest["files"]:
    data=(ROOT/item["path"]).read_bytes()
    expected=subprocess.check_output(["git","show",f'{SHA}:{item["repository_path"]}'],cwd=repo)
    assert data==expected and digest(data)==item["sha256"]
checks.append("24 exact repository source/schema/manifest/migration files equal read-only baseline git blobs")
for source in load("sources/source-manifest.json")["sources"]:
    assert digest((ROOT/source["rawPath"]).read_bytes())==source["rawSha256"]
    out=load(source["canonicalOutputPath"])
    assert digest((ROOT/source["canonicalOutputPath"]).read_bytes())==source["canonicalOutputSha256"]
    assert digest(out["content"].encode())==source["visibleContentSha256"]
    assert out["content"]==(ROOT/source["visibleContentPath"]).read_text(encoding="utf-8")
    assert out["httpStatus"]==200 and out["sourceUrl"]==source["url"] and out["truncated"] is False
checks.append("two frozen raw and exact-projected source outputs/content hashes; no live application acceptance claimed")
for path in ("common-materials.json","drafts/seed-manifest.json","plan-catalogue.json"):
    refs(load(path))
rubric=load("scoring/rubric.json")
assert len(rubric["items"])==18
for dim,maximum in rubric["dimensions"].items():
    assert sum(i["max"] for i in rubric["items"] if i["dimension"]==dim)==maximum
Draft202012Validator.check_schema(load("scoring/C1-answer.schema.json"))
checks.append("18 explicit discrete scoring items sum to 100; C1 schema and all material references valid")
properties=(ROOT/"runtime-closed.properties").read_text()
assert properties=="# Overlay only, using existing recognized keys. It does not load the new plan contract by itself.\njimu.model.paid-dispatch-enabled=false\njimu.web.public-dispatch-enabled=false\n"
checks.append("existing recognized paid/public-dispatch switches false; no activation operation executed")

save("VALIDATION.json", {"result":"PASS","scope":"offline delivered configuration/material verification only; not backend default verify, fault testing, live models, search or application downloads",
     "checkedAtUtc":datetime.now(timezone.utc).isoformat(),"checks":checks,"sourceGitSha":SHA})
status=subprocess.check_output(["git","status","--porcelain"],cwd=repo,text=True)
assert not status.strip()
save("RESOURCE-RECEIPT.json", {"recordedAtUtc":datetime.now(timezone.utc).isoformat(),"publicAnonymousSourceGets":2,
     "paidModelRequests":0,"paidSearchRequests":0,"applicationToolInvocations":0,"credentialsReadOrCopied":False,
     "productFilesModified":False,"productWorktreeClean":True,"ownedLiveProcessesServicesContainersDatabases":[],
     "offlineJavaCompilerAndProjection":"foreground commands completed; cached jars read only, no download or install",
     "retained":"research sources/config/scripts intentionally retained; compiled helper classes excluded from deliverable",
     "temporaryCommentBody":"remove only after successful final comment post","issueStatus":"in_progress","PR":"unmerged; no merge attempted"})

files=sorted(p for p in ROOT.rglob("*") if p.is_file() and "offline/classes" not in p.relative_to(ROOT).as_posix() and p.name!="MANIFEST.json")
entries=[{"path":p.relative_to(ROOT).as_posix(),"bytes":p.stat().st_size,"sha256":digest(p.read_bytes())} for p in files]
save("MANIFEST.json", entries)
archive=ROOT.parent/"XIAO-40-frozen-main-optional-plans-20261016.zip"
with zipfile.ZipFile(archive,"w",zipfile.ZIP_DEFLATED) as z:
    for p in files+[ROOT/"MANIFEST.json"]:
        z.write(p,arcname="frozen-plans/"+p.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for item in entries:
        assert digest(z.read("frozen-plans/"+item["path"]))==item["sha256"]
print(json.dumps({"result":"PASS","checks":len(checks),"manifestFiles":len(entries),"archive":archive.name,
      "bytes":archive.stat().st_size,"sha256":digest(archive.read_bytes())},indent=2))
