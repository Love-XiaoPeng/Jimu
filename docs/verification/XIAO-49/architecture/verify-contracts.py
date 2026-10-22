"""Validate handoff schemas/bytes; does not implement or validate product authorization or DB state."""
import copy
import hashlib
import importlib.metadata
import json
from pathlib import Path
import jsonschema

ROOT = Path(__file__).resolve().parent
C = ROOT / "contracts"
read = lambda p: json.loads(p.read_text(encoding="utf-8-sig"))
validators = {}
for path in sorted(C.rglob("*.schema.json")):
    doc = read(path)
    jsonschema.Draft202012Validator.check_schema(doc)
    validators[path.relative_to(C).as_posix()] = jsonschema.Draft202012Validator(doc, format_checker=jsonschema.FormatChecker())
index = read(C / "schema-index.json")
for entry in index["files"]:
    assert hashlib.sha256((C / entry["path"]).read_bytes()).hexdigest() == entry["sha256"]
assert read(C / "catalog.json")["schemaDigest"] == hashlib.sha256((C / "schema-index.json").read_bytes()).hexdigest()

cases = []
def case(name, schema, value, valid):
    errors = list(validators[schema].iter_errors(value))
    assert bool(errors) != valid, (name, [e.message for e in errors])
    cases.append({"name": name, "schema": schema, "value": value, "valid": valid})

b = "ask_user_question-0.1.0/"
t = "ask_user_question-0.2.0/"
examples = {p.name: read(p) for p in (C / "examples").glob("*.json")}
for path, example in [
    (b + "input.schema.json", "blocking-input.json"),
    (t + "input.schema.json", "blocking-input.json"),
    (t + "input.schema.json", "timed-input.json"),
    (t + "input.schema.json", "indefinite-input.json"),
    (b + "output.schema.json", "answer-result.json"),
    (b + "output.schema.json", "skip-result.json"),
    (t + "output.schema.json", "answer-result.json"),
    (t + "output.schema.json", "pending-result.json"),
    ("answer-request-v1.schema.json", "answer-request.json"),
    ("answer-receipt-v1.schema.json", "answer-receipt.json"),
    ("question-list-v1.schema.json", "empty-list.json"),
    ("question-list-v1.schema.json", "open-list.json"),
    ("task-binding-v1.schema.json", "binding.json"),
    ("late-reply-v1.schema.json", "late-reply.json"),
]:
    case(example + " in " + path, path, examples[example], True)

base = examples["blocking-input.json"]
case("empty questions", b + "input.schema.json", {"questions": []}, False)
case("too many questions", b + "input.schema.json", {"questions": base["questions"] * 5}, False)
case("missing required question", b + "input.schema.json", {"questions": [{"id": "q"}]}, False)
case("unknown receiver", b + "input.schema.json", dict(base, receiver="other-user"), False)
case("model selects mode", b + "input.schema.json", dict(base, mode="timed"), False)
case("blocking timeout injection", b + "input.schema.json", dict(base, timeout=1), False)
case("service intent injection", b + "input.schema.json", {"questions": [dict(base["questions"][0], intent={"kind": "plan-review"})]}, False)
case("question identity injection", b + "input.schema.json", {"questions": [dict(base["questions"][0], owner="other")]}, False)
case("option unknown field", b + "input.schema.json", {"questions": [{"id": "q", "question": "Q", "options": [{"label": "A", "credential": "bad"}]}]}, False)
case("non-ASCII identifier", b + "input.schema.json", {"questions": [{"id": "问", "question": "Q"}]}, False)
case("empty option array", b + "input.schema.json", {"questions": [{"id": "q", "question": "Q", "options": []}]}, False)
case("too many options", b + "input.schema.json", {"questions": [{"id": "q", "question": "Q", "options": [{"label": str(i)} for i in range(9)]}]}, False)
case("overlong question", b + "input.schema.json", {"questions": [{"id": "q", "question": "a" * 4097}]}, False)
for value in (0, -2, 181, 1.5, "2", None):
    case("invalid timed duration " + repr(value), t + "input.schema.json", dict(base, timeout=value), False)
case("pending is not blocking answer", b + "output.schema.json", examples["pending-result.json"], False)
case("pending missing callId", t + "output.schema.json", {"pending": True, "message": read(C / "catalog.json")["pendingNotice"]}, False)
case("pending false", t + "output.schema.json", dict(examples["pending-result.json"], pending=False), False)
case("pending with invented notice", t + "output.schema.json", dict(examples["pending-result.json"], message="approved"), False)
case("pending and answers together", t + "output.schema.json", dict(examples["pending-result.json"], answers=[]), False)
case("forged responder", "answer-request-v1.schema.json", dict(examples["answer-request.json"], answeredBy="other"), False)
case("zero revision", "answer-request-v1.schema.json", dict(examples["answer-request.json"], expectedRevision=0), False)
case("answer missing id", b + "output.schema.json", {"answers": [{"selected": []}]}, False)
case("selected indexes", b + "output.schema.json", {"answers": [{"id": "q", "selected": [1]}]}, False)
case("duplicate selected label", b + "output.schema.json", {"answers": [{"id": "q", "selected": ["A", "A"]}]}, False)
case("null custom", b + "output.schema.json", {"answers": [{"id": "q", "selected": [], "custom": None}]}, False)
case("empty answer batch", "answer-request-v1.schema.json", dict(examples["answer-request.json"], answers=[]), False)
case("invented reply tool", "late-reply-v1.schema.json", dict(examples["late-reply.json"], tool="deploy"), False)
case("unbounded binding", "task-binding-v1.schema.json", dict(examples["binding.json"], maxTaskBytes=999999999), False)
bad_view = copy.deepcopy(examples["open-list.json"])
bad_view["items"][0]["phase"] = "CLOSED"
case("closed but answerable", "question-list-v1.schema.json", bad_view, False)

# This is intentionally shape-valid: byte and source-dependent checks are a runtime requirement.
unicode_value = {"questions": [{"id": "q", "question": "😀" * 4096}]}
assert not list(validators[b + "input.schema.json"].iter_errors(unicode_value))
assert len(unicode_value["questions"][0]["question"].encode("utf-8")) > 4096
receipt = {
    "scope": "Architecture schema/source checks only; no product HTTP, authorization, SQL, crash recovery or business acceptance",
    "jsonschemaVersion": importlib.metadata.version("jsonschema"),
    "schemasChecked": len(validators), "indexedFilesChecked": len(index["files"]),
    "positive": sum(c["valid"] for c in cases), "negative": sum(not c["valid"] for c in cases),
    "runtimeChecksStillRequired": ["UTF-8 byte limits", "strict JSON duplicate keys and number tokens", "original question/option matching", "identity/pins/epochs", "SQL atomicity/fence/quiescence", "timed/late answer delivery", "legacy migration/retention"],
    "unicodeBoundary": "4096 emoji pass the character schema and exceed the 4096 UTF-8 byte field limit: product must reject in runtime validation"
}
(ROOT / "evidence" / "contract-check.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(ROOT / "evidence" / "schema-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"PASS {len(validators)} schemas, {len(index['files'])} indexed files, {receipt['positive']} positive and {receipt['negative']} negative cases")
