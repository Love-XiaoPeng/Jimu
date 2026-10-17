"""Build two CLOSED trusted plan configurations. Offline only; no product changes."""
from datetime import datetime, timezone
from decimal import Decimal, ROUND_CEILING
from itertools import combinations
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
OLD = ROOT.parent / "bailian-quality-plan"
SHA = "a20cefbf9004561bd14806c83e20c2c8940b9408"
P = 1004096


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write(path, text):
    dest = ROOT / path
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8", newline="\n")


def save(path, obj):
    write(path, json.dumps(obj, ensure_ascii=False, indent=2) + "\n")


def ref(path):
    data = (ROOT / path).read_bytes()
    return {"path": path, "sha256": sha(data), "bytes": len(data)}


def amount(input_rate, output_rate, inputs=1000000, outputs=4096):
    # integer micro-CNY / million tokens; exact same ceil as ModelCosts.amount.
    return (inputs * input_rate + outputs * output_rate + 999999) // 1000000


sources = json.loads((ROOT / "sources/source-manifest.json").read_text(encoding="utf-8"))
rubric = json.loads((ROOT / "scoring/rubric.json").read_text(encoding="utf-8"))
assert {k: sum(i["max"] for i in rubric["items"] if i["dimension"] == k) for k in rubric["dimensions"]} == rubric["dimensions"]
assert sum(rubric["dimensions"].values()) == 100
for item in rubric["items"]:
    assert item["anchors"][0]["score"] == item["max"]
    assert item["anchors"][-1]["score"] == 0

c1_schema = {"$schema":"https://json-schema.org/draft/2020-12/schema", "type":"object", "additionalProperties":False,
             "required":["工位达成率","总体达成率","结论","局限"], "properties": {
                 "工位达成率":{"type":"object","additionalProperties":False,"required":["A","B"],"properties":{"A":{"const":"90.0%"},"B":{"const":"110.0%"}}},
                 "总体达成率":{"const":"98.0%"},"结论":{"type":"string","minLength":1,"maxLength":80},"局限":{"type":"string","minLength":1}}}
save("scoring/C1-answer.schema.json", c1_schema)
save("scoring/oracles.json", {
    "C1":{"parentSample":"W1","extraction":"exactly one json fenced block in 摘要; strict duplicate-key rejection; validate C1-answer.schema.json",
          "manualChecks":["结论为中文且无虚构原因", "局限明确缺停机、质量、人员数据且不能推断原因"],"allChecksRequired":True},
    "W1":{"facts":["Source Port、Destination Port、Length、Checksum四字段", "Length单位为octets且minimum为8", "UDP不保证delivery或duplicate protection"],
          "boundaries":["不从RFC768推断现代性能排行", "不将checksum等同于可靠交付", "不据此断言IPv6行为或当前价格"]},
    "W2":{"facts":["RGB与YIQ/HLS/HSV的双向API名称及转换方向", "一般坐标为0到1，YIQ的I/Q可正可负", "红色RGB(1,0,0)手工HSV为(0,1,1)"],
          "boundaries":["无Python运行证据", "无速度或精度排名证据", "手工例子与实际执行结果明确区分"]},
    "W3":{"facts":["至少一个RFC768明示事实且引用匹配", "至少一个colorsys明示事实且引用匹配", "两条性能断言都不能由当前来源证明"],
          "boundaries":["UDP始终比TCP快无本业务实测依据", "colorsys最快实现无比较证据", "建议不能作为已验证结论"]},
    "W4":{"facts":["纠正UDP保证可靠送达的说法", "纠正已经生产压测/总比其他传输快的说法", "修订理由可追溯到本次实际抓取的RFC768"],
          "boundaries":["没有生产压测证据", "旧草稿不是证据来源", "真实read的旧版本与最终CAS/present收据一致"]}
})
save("scoring/scorecard.template.json", {"planId":None,"policyDigest":None,"sampleId":None,"blindCandidateId":None,"reviewer":None,
       "items":[{"id":i["id"],"score":None,"evidenceReferences":[],"anchorMatched":None} for i in rubric["items"]],
       "hardFails":[],"C1AllChecksPassed":None,"hostStructurePassed":None,"publishedDownloadHashPassed":None,
       "usageAndAllEffectsKnown":None,"adjudicated":False,"totals":None,"passing":False})

samples = []
for name, phase, stream, cap, count, low, high in (
    ("W1","A",False,7,1,600,900), ("W2","A",True,6,1,800,1200),
    ("W3","B",False,6,2,1000,1500), ("W4","B",True,6,1,1000,1500)):
    goal = (ROOT / f"prompts/{name}.goal.txt").read_text(encoding="utf-8")
    assert "${" not in goal and "{{" not in goal and len(goal.encode()) <= 8192
    request = {"goal":goal, "capabilityIds":["web_fetch","read","write","present"], "resourceIds":[],
               "budget":{"deadlineSeconds":180,"maxModelRequests":cap,"maxToolInvocations":count+6}}
    # Existing public maxCostMicros API stays <=1 CNY; trusted plan admission supplies its bounded task cap.
    save(f"requests/{name}.submit.json", request)
    samples.append({"id":name,"phase":phase,"stream":stream,"maxModelDispatches":cap,"maxBillableOutputTokens":cap*4096,
                    "maxFullContextTokenReservation":cap*P,"request":ref(f"requests/{name}.submit.json"),
                    "goal":ref(f"prompts/{name}.goal.txt"),"target":"reports/"+name.lower()+".md",
                    "sourceIds":["rfc768","colorsys"] if name=="W3" else (["colorsys"] if name=="W2" else ["rfc768"]),
                    "maxToolInvocations":count+6,"perToolMaxInvocations":{"web_fetch":count,"read":2,"write":2,"present":2},
                    "initialTarget":"seeded-draft" if name=="W4" else "must-be-absent",
                    "hanCharacterRange":[low,high],"requiredSections":["主题","摘要","结论","来源链接","生成时间","局限"]})
save("samples.json", {"samples":samples,"C1":{"embeddedIn":"W1","separateTask":False,"separateDispatch":False},
       "workspaceIsolation":"one dedicated evaluation tenant and original durable run; separate fresh task workspaces per candidate/sample, same relative target; no cross-task observations"})
save("drafts/seed-manifest.json", {"sample":"W4","file":ref("drafts/W4.initial.md"),"logicalPath":"reports/w4.md",
       "version":None,"versionBinding":"create through existing trusted FilePort/store under exact task scope; record real version/size/hash before worker starts; do not guess version or write OS files behind store",
       "initialBytesIdenticalAcrossCandidates":True,"legacyArtifactPolicy":"retain any actual old artifact receipts; do not manufacture a prior published artifact"})

# Preserve the original public pricing/capability evidence without another network read.
official = json.loads((OLD / "sources.json").read_text(encoding="utf-8"))
for source in official:
    raw = (OLD / (source["name"] + ".html")).read_bytes()
    assert sha(raw) == source["sha256"]
    dest = ROOT / "official" / (source["name"] + ".md")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(raw)
save("official/sources.json", official)

old_models = json.loads((OLD / "plan.json").read_text(encoding="utf-8"))["models"]
models = []
for m in old_models:
    tier = m["tier"]
    ir = m["price_input_micros_per_million_max"]
    outr = m["price_output_micros_per_million_max"]
    reserve = amount(ir, outr)
    # low bracket for known short actual input, list price and peak Pro; this is not the ledger flat-rate debit.
    expected = amount(int(Decimal(m["price_cny_per_million"]["input_low"])*1000000),
                      int(Decimal(m["price_cny_per_million"]["output_low"])*1000000),12000,2048)
    ledger_scenario = amount(ir, outr, 12000, 2048)
    profiles = []
    for stream in (False, True):
        model_id = m["model"]
        profiles.append({"version":2,"profile":"eval-"+tier.lower()+ ("-sse-v1" if stream else "-call-v1"),"configVersion":1,
                         "adapter":"openai-chat-v1","baseUrl":None,"model":model_id,"revision":model_id,"authMode":"bearer",
                         "credentialRef":None,"credentialVersion":0,"transportPolicy":"cloud-https-v1",
                         "connectTimeoutMs":10000,"requestTimeoutMs":120000,"responseBytes":1048576,"stream":stream,
                         "maxOutputTokens":4096,"outputLimitField":"max_completion_tokens","outputMarginTokens":10,
                         "capabilities":{"call":True,"tools":True,"streamText":True,"streamTools":True,"usageCall":True,"usageStream":True,
                                         "parallelToolField":False,"contextLimit":1000000,"evidence":"modelstudio-nonthinking-v1"},
                         "providerOptions":{"enable_search":False,"enable_thinking":False},"paid":True,
                         "price":{"version":"bailian-beijing-payg-list-20261016-"+tier.lower(),"currency":"CNY",
                                  "inputMicrosPerMillion":ir,"outputMicrosPerMillion":outr,"fixedMicros":0,"inputTokenUpper":1000000,
                                  "boundEvidence":"official-sources-sha256; full-context + total-output 4086+10; no paid extras"}})
    task_caps = {s["id"]:{"maxDispatches":s["maxModelDispatches"],"maxCostMicros":reserve*s["maxModelDispatches"],
                           "tokenLimit":P*s["maxModelDispatches"]} for s in samples}
    item = {"tier":tier,"model":m["model"],"profileTemplates":profiles,"tariffEvidence":m["source"],
            "perDispatchReserveMicros":reserve,"phaseLimits":{
                "A":{"maxDispatches":13,"maxCostMicros":reserve*13,"maxBillableOutputTokens":13*4096,"tokenLimit":13*P},
                "B":{"maxDispatches":12,"maxCostMicros":reserve*12,"maxBillableOutputTokens":12*4096,"tokenLimit":12*P}},
            "maxAcrossPhases":{"dispatches":25,"costMicros":reserve*25,"billableOutputTokens":25*4096,"tokenLimit":25*P},
            "taskLimits":task_caps,"forecastPerDispatch":{"knownUsageListPriceEstimateMicros":expected,"configuredFlatRateLedgerDebitMicros":ledger_scenario}}
    models.append(item)
save("profiles/candidates.json", {"sourceGitSha":SHA,"templatesAreClosedUntilAccountBinding":True,
       "accountFieldsNeverGuessed":["baseUrl","credentialRef","credentialVersion"],
       "inputLimit":"omitted; no real small-input proof claimed", "models":models,
       "runtimeDigest":"compute with existing ModelProfiles.digest / Payloads after trusted account binding; artifact hashes are not substituted for runtime digests",
       "pricing":"highest applicable fixed flat rate for existing reserve/settle engine; evaluator separately reports known-usage tier list-price estimate, not an invoice"})

gate = {"initialState":"LOCKED", "automaticOnBudgetIncrease":False,
        "eligibility":{"requiredASamples":["W1","W2"],"minimumReportScore":85,"minimumToolsScore":27,"minimumSourcesScore":27,
                       "embeddedC1AllChecksRequired":True,"everyRequiredReportMustPass":True,"hardFailCountMustEqual":0,
                       "adjudicationRequired":True,"originalRunStateMustEqual":"OPEN","globalUnknownCountMustEqual":0,
                       "allPhaseATasksTerminalWithKnownOutcome":True,"maxFinalists":2},
        "conditions":["selected plan/candidate set frozen before first dispatch and unchanged", "all assigned A tasks terminal with known outcomes",
                       "no UNKNOWN usage/cost/tool outcome anywhere on original run; run remains OPEN",
                       "for every finalist: W1 and W2 both pass adjudicated rubric, embedded C1 all checks pass",
                       "exact prompt/tool/source/draft hashes match and final code independently accepted",
                       "original cumulative money, phase, model, sample, token and dispatch remaining allowance all suffice"],
        "transition":"trusted transaction locks original run/plan phase/model counters; verify score/evidence digests; select zero, one or two eligible finalists once; persist immutable unlock receipt; no reselect or add Pro",
        "receiptFields":["planId","planSha256","originalRunId","AResultDigest","adjudicatedScorecardDigests","evidenceManifestDigest","finalistModelIds","actor","timestamp","fenceOrRowVersion"],
        "zeroFinalists":"no B dispatch; report no passing model", "oneFinalist":"only that finalist receives its 12-dispatch B envelope; no backfill", "twoFinalists":"24 global B dispatches maximum"}
save("gates/B-unlock-policy.json", gate)

common_paths = ["contracts/repository-manifest.json","contracts/tools.json","contracts/tool-pins.json","prompts/host-system.template.txt",
                "prompts/host-system-binding.json","sources/source-manifest.json","drafts/seed-manifest.json","samples.json",
                "scoring/rubric.json","scoring/C1-answer.schema.json","scoring/oracles.json","scoring/scorecard.template.json",
                "gates/B-unlock-policy.json","official/sources.json"]
save("common-materials.json", {"version":1,"references":[ref(p) for p in common_paths],
       "dynamicBindings":["task UUID/owner/private workspace scope", "host-reserved report_time only in system template", "actual authorized installation epochs", "actual W4 initial store version", "confirmed account binding and final independent code SHA"],
       "bindingReceiptsRequiredBeforeFirstDispatch":True,
       "finalHttpBody":"serialize through existing OpenAiProtocol, hash exact complete UTF-8 bytes including all history/tools/results; bind to durable dispatch before send; no whitespace or field mutation after proof/reservation",
       "materialChange":"digest mismatch rejects and persistently stops further paid plan dispatches; no silent rebaseline; new reviewed plan revision required before fresh admission; existing tasks keep originals"})

audits = []
for plan_id, tiers, total, calls, a_cap, b_cap in (
    ("qwen-main-v1",["Plus","Flash","Max"],500000000,63,260000000,240000000),
    ("qwen-pro-optional-v1",["Plus","Flash","Max","Pro"],650000000,76,380000000,270000000)):
    candidates = [m for m in models if m["tier"] in tiers]
    a_count = len(candidates)*13
    a_reserve = sum(m["phaseLimits"]["A"]["maxCostMicros"] for m in candidates)
    a_forecast = sum(m["forecastPerDispatch"]["knownUsageListPriceEstimateMicros"]*13 for m in candidates)
    a_ledger = sum(m["forecastPerDispatch"]["configuredFlatRateLedgerDebitMicros"]*13 for m in candidates)
    branches = []
    for n in (0,1,2):
        for pair in combinations(candidates, n):
            b_reserve = sum(m["phaseLimits"]["B"]["maxCostMicros"] for m in pair)
            b_forecast = sum(m["forecastPerDispatch"]["knownUsageListPriceEstimateMicros"]*12 for m in pair)
            b_ledger = sum(m["forecastPerDispatch"]["configuredFlatRateLedgerDebitMicros"]*12 for m in pair)
            branches.append({"finalists":[m["model"] for m in pair],"BMaxDispatches":12*n,"BReserveMicros":b_reserve,
                             "totalReserveMicros":a_reserve+b_reserve,"remainingForLegacyAndUnallocatedMicros":total-a_reserve-b_reserve,
                             "knownUsageListPriceForecastMicros":a_forecast+b_forecast,"configuredFlatRateLedgerForecastMicros":a_ledger+b_ledger})
    worst = max(branches,key=lambda b:b["totalReserveMicros"])
    assert a_count+24 == calls and a_reserve <= a_cap and worst["BReserveMicros"] <= b_cap and worst["totalReserveMicros"]<=total
    save(f"profiles/{plan_id}.json", {"sourceGitSha":SHA,"templatesAreClosedUntilAccountBinding":True,
         "models":candidates,"inputLimit":"omitted; full context only", "binding":"confirmed account fields only; compute actual ModelProfiles.digest after binding; do not use artifact hash as runtime model digest"})
    plan = {"kind":"jimu-trusted-evaluation-plan","version":1,"planId":plan_id,"issueId":"01a14029-5248-7d16-917c-f9ae27b477b2",
            "enabled":False,"paidDispatchEnabled":False,"searchDispatchEnabled":False,"productionEgressEnabled":False,
            "authorizationEffective":False,"accountAdmitted":False,"selected":False,
            "candidateModelIds":[m["model"] for m in candidates],"candidateScopeFreeze":"before first A dispatch; no plan switch or adding candidate after any dispatch",
            "selectionMode":"exactly one plan bound to original durable run; not two independent allowances",
            "accountBinding":{"region":None,"plan":None,"deploymentScope":None,"apiHost":None,"credentialRef":None,"credentialVersion":None,"exactModelPermissionsConfirmed":False,"egressRestrictionsConfirmed":False},
            "requiredPricingScope":"cn-beijing / Alibaba Model Studio deployed / PAYG / CNY / list prices; peak Pro",
            "originalRunId":None,"isolatedTenantId":None,"approvedExecutionReceipt":None,"acceptedFinalCodeSha":None,
            "limits":{"currency":"CNY","cumulativeMaxCostMicros":total,"cumulativeMaxDispatches":calls,
                      "maxBillableOutputPerDispatch":4096,"httpMaxCompletionTokens":4086,"outputMarginTokens":10,
                      "matrixMaxBillableOutputTokens":calls*4096,"isolatedTenantTokenLimit":calls*P,"perDispatchTokenReservation":P,
                      "maxInflight":1,"httpRetries":0,"paidSearchCostMicros":0,"paidSearchDispatches":0},
            "phases":{"A":{"state":"CLOSED_NOT_AUTHORIZED","maxCostMicros":a_cap,"maxDispatches":a_count,"maxBillableOutputTokens":a_count*4096,"tokenLimit":a_count*P,"samples":["W1","W2"]},
                      "B":{"state":"LOCKED","maxCostMicros":b_cap,"maxDispatches":24,"maxBillableOutputTokens":24*4096,"tokenLimit":24*P,"samples":["W3","W4"],"maxFinalists":2,"unlockPolicy":ref("gates/B-unlock-policy.json")}},
            "modelLimits":[{"model":m["model"],"phaseLimits":m["phaseLimits"],"maxAcrossPhases":m["maxAcrossPhases"],"taskLimits":m["taskLimits"]} for m in candidates],
            "commonMaterials":ref("common-materials.json"),"sourceOfExactProfiles":ref(f"profiles/{plan_id}.json"),
            "ledgerRules":{"originalRunRequired":True,"preservePriorRequestsAndCost":True,"legacyAndUnknownIncludedInGlobalCap":True,
                           "unusedMoneyCreatesCallsOrSearchAuthorization":False,"quotaIncreaseClearsUnknownOrReopensStopped":False,
                           "existingTaskSnapshotLimitsChanged":False,"requireFullRemainingMatrixAtInitialAdmission":True,
                           "phaseAndModelCapsCannotBorrow":True,"spentPlusHeldPlusNextReserveCheckedAtomically":True},
            "runtimePreparation":{"entry":"existing trusted operator setup only; no public plan/profile/endpoint/credential controls",
                                  "requestBudgetMaxCost":"leave public maxCostMicros cap unchanged; trusted plan-specific task admission supplies bounded max_cost_micros before first dispatch, persists plan binding; never mass-update legacy tasks",
                                  "schemaMigration":"existing V4 CHECK limits are 20 requests/1 CNY; use a new reviewed forward migration for bounded explicit policy, retain old defaults and task snapshots; do not rewrite V4 or delete counters",
                                  "profileBinding":"only fill confirmed account fields and compute runtime digests; otherwise leave closed; output4096 and Max/Pro admission need bounded validation support"}}
    save(f"plans/{plan_id}.json", plan)
    audits.append({"planId":plan_id,"phaseAReserveMicros":a_reserve,"phaseAExpectedMicros":a_forecast,"phaseALedgerScenarioMicros":a_ledger,
                   "branches":branches,"worst":worst,"candidateRows":[{"model":m["model"],"perDispatchReserveMicros":m["perDispatchReserveMicros"],"phaseLimits":m["phaseLimits"]} for m in candidates]})
save("budget-audit.json", {"unit":"integer micro-CNY; per dispatch ceil; expected scenario 12K full input and 2048 total output, no discounts; forecast not admission", "plans":audits})
save("plan-catalogue.json", {"version":1,"selectedPlan":None,"enabled":False,"exclusive":True,
       "plans":[ref("plans/qwen-main-v1.json"),ref("plans/qwen-pro-optional-v1.json")],
       "selectionFreeze":"persist selected plan id/hash/candidates at trusted original-run admission before first paid dispatch; cannot activate both or switch for more allowance"})
write("runtime-closed.properties", "# Overlay only, using existing recognized keys. It does not load the new plan contract by itself.\njimu.model.paid-dispatch-enabled=false\njimu.web.public-dispatch-enabled=false\n")

closed_plans = [json.loads((ROOT / f"plans/{plan_id}.json").read_text(encoding="utf-8")) for plan_id in ("qwen-main-v1","qwen-pro-optional-v1")]
save("closed-plan.schema.json", {"$schema":"https://json-schema.org/draft/2020-12/schema","title":"Exact closed plan approval envelope v1",
     "description":"Exact-value constraint for the two frozen CLOSED plans, not an enabled runtime authorization receipt schema. Any semantic edit requires a reviewed plan revision.",
     "oneOf":[{"const":p} for p in closed_plans]})

print(json.dumps([{"planId":a["planId"],"AReserveMicros":a["phaseAReserveMicros"],"worst":a["worst"]} for a in audits],ensure_ascii=False,indent=2))
