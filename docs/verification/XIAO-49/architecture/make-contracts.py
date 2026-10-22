"""Generate architecture handoff contracts, not product resources or executable handlers."""
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "contracts"
ROOT.mkdir(exist_ok=True)
DIALECT = "https://json-schema.org/draft/2020-12/schema"
NOTICE = (
    "No answer batch arrived before the timeout. This is pending, not a skipped answer. "
    "Continue useful independent work. The user can still answer; their reply will be a user message "
    "identified as answer_to_pending_question with this callId and the original questions. "
    "Do not treat this as permission."
)


def write(path, value):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def obj(properties, required=None):
    return {"type": "object", "additionalProperties": False,
            "properties": properties, "required": list(properties) if required is None else required}


def string(limit, minimum=1):
    return {"type": "string", "minLength": minimum, "maxLength": limit}


def array(items, minimum, maximum, unique=False):
    value = {"type": "array", "items": items, "minItems": minimum, "maxItems": maximum}
    if unique:
        value["uniqueItems"] = True
    return value


def schema(name, body):
    return {"$schema": DIALECT, "$id": "urn:jimu:interaction:" + name, **body}


uuid = {"type": "string", "format": "uuid"}
instant = {"type": "string", "format": "date-time"}
integer = {"type": "integer", "minimum": 1, "maximum": 2147483647}
identifier = {"type": "string", "pattern": "^[A-Za-z0-9_.-]{1,64}$"}
digest = {"type": "string", "pattern": "^[a-f0-9]{64}$"}
option = obj({"label": string(256), "description": string(1024)}, ["label"])
question = obj({
    "id": identifier, "question": string(4096), "header": string(128),
    "options": array(option, 1, 8), "multi_select": {"type": "boolean"}
}, ["id", "question"])
questions = array(question, 1, 4)
answer = obj({"id": identifier, "selected": array(string(256), 0, 8, True),
              "custom": string(2048)}, ["id", "selected"])
answers = array(answer, 1, 4)
answer_result = obj({"answers": answers})
pending_result = obj({"pending": {"const": True}, "callId": string(128), "message": {"const": NOTICE}})
timeout = {"oneOf": [{"const": -1}, {"type": "integer", "minimum": 1, "maximum": 180}]}
variants = []
for version, mode in (("0.1.0", "blocking"), ("0.2.0", "timed")):
    path = "ask_user_question-" + version
    properties = {"questions": questions}
    if mode == "timed":
        properties["timeout"] = dict(timeout, description="Batch foreground wait in seconds; default 120. -1 blocks until answer or task deadline.")
    write(path + "/input.schema.json", schema("ask-user:" + version + ":input", obj(properties, ["questions"])))
    result = answer_result if mode == "blocking" else {"oneOf": [answer_result, pending_result]}
    write(path + "/output.schema.json", schema("ask-user:" + version + ":output", result))
    manifest = {
        "manifestVersion": 1, "id": "jimu.interaction.ask-user", "version": version,
        "kind": "tool", "hostApi": ">=0.1.0 <0.2.0",
        "entry": {"type": "trusted-jvm", "class": "io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin"},
        "capabilities": [{"name": "ask_user_question", "inputSchema": "input.schema.json",
                          "outputSchema": "output.schema.json", "sideEffect": "write"}],
        "permissions": {}, "secretRefs": [], "dependencies": [],
        "limits": {"timeoutMs": 30000, "outputBytes": 16384},
        "healthCheck": {"operation": "question-ledger-readiness", "timeoutMs": 2000},
        "errorContract": "jimu-invocation-result",
        "eventTypes": ["invocation.started", "invocation.completed", "invocation.failed",
                       "question.created", "question.answered", "question.continued", "question.closed"]
    }
    write(path + "/plugin.yaml", manifest)  # JSON is a strict YAML subset, matching existing MCP resources.
    description = (
        "Ask the user a concise question when you need confirmation, a choice, or missing information before proceeding."
        if mode == "blocking" else
        "Ask brief, direct, self-contained questions about missing information, preferences, or decisions. "
        "Use distinct stable question IDs. An explicit skipped answer has empty selected and no custom. "
        "Pending means no answer batch arrived before the foreground timeout; it grants no permission. "
        "The task deadline still applies."
    )
    (ROOT / path / "description.txt").write_text(description + "\n", encoding="utf-8")
    variants.append({"id": manifest["id"], "version": version, "mode": mode,
                     "tool": "ask_user_question", "root": path,
                     "inputSha256": hashlib.sha256((ROOT / path / "input.schema.json").read_bytes()).hexdigest(),
                     "outputSha256": hashlib.sha256((ROOT / path / "output.schema.json").read_bytes()).hexdigest(),
                     "manifestSha256": hashlib.sha256((ROOT / path / "plugin.yaml").read_bytes()).hexdigest()})

write("answer-request-v1.schema.json", schema("answer-request:v1", obj({
    "questionVersion": integer, "expectedRevision": integer, "answers": answers
})))
receipt = obj({
    "version": {"const": 1}, "requestId": uuid, "answerId": uuid,
    "questionVersion": {"const": 1}, "acceptedRevision": integer,
    "answeredBy": string(64), "answeredAt": instant,
    "delivery": {"enum": ["TOOL_RESULT", "USER_MESSAGE"]}
})
write("answer-receipt-v1.schema.json", schema("answer-receipt:v1", receipt))
view = obj({
    "requestId": uuid, "questionVersion": {"const": 1}, "revision": integer,
    "invocationId": uuid, "callId": string(128), "mode": {"enum": ["blocking", "timed"]},
    "phase": {"enum": ["OPEN", "CONTINUED", "ANSWERED", "CLOSED"]},
    "questions": questions, "foregroundDueAt": {"oneOf": [instant, {"type": "null"}]},
    "answerExpiresAt": instant, "answerable": {"type": "boolean"},
    "answerId": uuid, "answeredBy": string(64), "answeredAt": instant, "answers": answers,
    "deliveryState": {"enum": ["NONE", "PENDING", "CHECKPOINTED", "DISCARDED"]},
    "closeCode": string(64)
}, ["requestId", "questionVersion", "revision", "invocationId", "callId", "mode", "phase",
    "questions", "foregroundDueAt", "answerExpiresAt", "answerable"])
view["allOf"] = [
    {"if": {"properties": {"phase": {"const": "ANSWERED"}}},
     "then": {"required": ["answerId", "answeredBy", "answeredAt", "answers", "deliveryState"],
              "properties": {"answerable": {"const": False}}}},
    {"if": {"properties": {"phase": {"const": "CLOSED"}}},
     "then": {"required": ["closeCode"], "properties": {"answerable": {"const": False}}}}
]
write("question-list-v1.schema.json", schema("question-list:v1", obj({
    "version": {"const": 1}, "items": array(view, 0, 8)
})))
write("late-reply-v1.schema.json", schema("late-reply:v1", obj({
    "kind": {"const": "answer_to_pending_question"}, "tool": {"const": "ask_user_question"},
    "callId": string(128), "requestId": uuid, "questions": questions, "answers": answers
})))
write("task-binding-v1.schema.json", schema("task-binding:v1", obj({
    "version": {"const": 1}, "profile": {"const": "local-durable-questions-v1"},
    "mode": {"enum": ["blocking", "timed"]}, "defaultTimeoutSeconds": {"const": 120},
    "catalogDigest": digest, "maxBatches": {"const": 8}, "maxQuestions": {"const": 4},
    "maxWaitSeconds": {"const": 180}, "maxTaskBytes": {"const": 2097152}
})))
examples = {
    "blocking-input.json": {"questions": [{"id": "mode", "question": "Choose a mode", "header": "Mode",
                                          "options": [{"label": "Fast", "description": "Shorter wait"}, {"label": "Careful"}]}]},
    "timed-input.json": {"questions": [{"id": "features", "question": "Choose features", "multi_select": True,
                                       "options": [{"label": "A"}, {"label": "B"}]}], "timeout": 2},
    "indefinite-input.json": {"questions": [{"id": "detail", "question": "What detail should I use?"}], "timeout": -1},
    "answer-result.json": {"answers": [{"id": "features", "selected": ["A", "B"], "custom": "C too"}]},
    "skip-result.json": {"answers": [{"id": "mode", "selected": []}]},
    "pending-result.json": {"pending": True, "callId": "provider-call-1", "message": NOTICE},
    "answer-request.json": {"questionVersion": 1, "expectedRevision": 1, "answers": [{"id": "mode", "selected": ["Fast"]}]},
    "answer-receipt.json": {"version": 1, "requestId": "11111111-1111-4111-8111-111111111111",
                            "answerId": "22222222-2222-4222-8222-222222222222", "questionVersion": 1,
                            "acceptedRevision": 2, "answeredBy": "owner-a", "answeredAt": "2026-10-22T14:00:00Z", "delivery": "TOOL_RESULT"},
    "empty-list.json": {"version": 1, "items": []},
    "binding.json": {"version": 1, "profile": "local-durable-questions-v1", "mode": "blocking", "defaultTimeoutSeconds": 120,
                     "catalogDigest": "a" * 64, "maxBatches": 8, "maxQuestions": 4, "maxWaitSeconds": 180, "maxTaskBytes": 2097152},
}
examples["late-reply.json"] = {"kind": "answer_to_pending_question", "tool": "ask_user_question",
                               "callId": "provider-call-1", "requestId": examples["answer-receipt.json"]["requestId"],
                               "questions": examples["blocking-input.json"]["questions"],
                               "answers": examples["answer-request.json"]["answers"]}
examples["open-list.json"] = {"version": 1, "items": [{
    "requestId": examples["answer-receipt.json"]["requestId"], "questionVersion": 1, "revision": 1,
    "invocationId": "33333333-3333-4333-8333-333333333333", "callId": "provider-call-1", "mode": "blocking",
    "phase": "OPEN", "questions": examples["blocking-input.json"]["questions"], "foregroundDueAt": None,
    "answerExpiresAt": "2026-10-22T14:02:00Z", "answerable": True
}]}
for name, example in examples.items():
    write("examples/" + name, example)
index_files = []
for file in sorted(ROOT.rglob("*")):
    if file.is_file() and "examples" not in file.parts and file.name not in ("schema-index.json", "catalog.json"):
        index_files.append({"path": file.relative_to(ROOT).as_posix(),
                            "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
write("schema-index.json", {"version": 1, "files": index_files})
write("catalog.json", {
    "version": 1, "sourceGroup": "G04", "sourceTool": "T11",
    "upstreamCommit": "571902028a75c64c1d767f4ae5fd099485f0ddf0", "defaultVersion": "0.1.0",
    "profile": "local-durable-questions-v1", "variants": variants,
    "schemaDigest": hashlib.sha256((ROOT / "schema-index.json").read_bytes()).hexdigest(),
    "pendingNotice": NOTICE,
    "notes": ["Select one installed version per task, never two duplicate function entries.",
              "Manifest timeoutMs covers one bounded host/DB operation, never the duration of human wait.",
              "UTF-8 byte limits, unique question IDs/labels and original-question answer matching require runtime validation."]
})
print(f"Generated {len(index_files)} indexed contract files; catalog digest "
      + hashlib.sha256((ROOT / "schema-index.json").read_bytes()).hexdigest())
