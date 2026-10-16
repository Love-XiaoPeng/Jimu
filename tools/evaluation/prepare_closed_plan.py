#!/usr/bin/env python3
"""Emit review-only SQL for an EXISTING isolated run; never connects or activates dispatch."""
import argparse
import hashlib
import json
from pathlib import Path
import re

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--plan", choices=["qwen-main-v1", "qwen-pro-optional-v1"], required=True)
p.add_argument("--tenant", required=True)
p.add_argument("--existing-run", required=True)
p.add_argument("--output", type=Path, required=True)
args = p.parse_args()
for value in [args.tenant, args.existing_run]:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value):
        p.error("Invalid trusted tenant/run identifier")
path = Path(__file__).resolve().parents[2]/"src/main/resources/evaluation"/(args.plan+".json")
plan = json.loads(path.read_text())
assert not plan["enabled"] and not plan["paidDispatchAuthorized"]
snapshot = json.dumps(plan, sort_keys=True, separators=(",", ":"))
digest = hashlib.sha256(snapshot.encode()).hexdigest()
sql = f"""-- CLOSED REVIEW DRAFT. No runtime configuration or permission is activated by this file.
-- Execute only after the separate allowance/account/SHA approvals, against the existing private run.
-- Keep paid-dispatch-enabled=false and production public egress/search disabled.
-- Lock order is the same as application admission: evaluation, then tenant.
START TRANSACTION;
SELECT id,state,requests_used,cost_held FROM evaluation_budget
 WHERE tenant_id='{args.tenant}' AND id='{args.existing_run}' FOR UPDATE;
SELECT tenant_id,token_held,cost_held FROM tenant_budget WHERE tenant_id='{args.tenant}' FOR UPDATE;
UPDATE evaluation_budget SET policy_version=2,max_requests={plan['maxRequests']},
 max_cost_micros={plan['maxCostMicros']},plan_snapshot='{snapshot}',plan_digest='{digest}'
 WHERE tenant_id='{args.tenant}' AND id='{args.existing_run}'
 AND (plan_snapshot IS NULL OR plan_digest='{digest}')
 AND requests_used<={plan['maxRequests']}
 AND NOT EXISTS(SELECT 1 FROM task WHERE tenant_id='{args.tenant}' AND evaluation_run_id='{args.existing_run}'
  AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED'));
-- Never INSERT a replacement run, reset requests/cost, clear UNKNOWN, or reopen STOPPED.
-- Review ROW_COUNT() = 1 and balances before preparing isolated tenant quotas separately.
SELECT ROW_COUNT() AS existing_run_updated;
COMMIT;
-- Proposed PRIVATE tenant ceilings: token_limit={plan['tenantTokenLimit']},
-- cost_limit={plan['maxCostMicros']}, model_limit={plan['maxRequests']},running_limit=1.
-- No UPDATE of existing task limits: only new accepted tasks receive plan snapshots.
"""
args.output.write_text(sql, encoding="utf-8")
print(json.dumps({"plan":args.plan,"planDigest":digest,"enabled":False,"paidDispatchAuthorized":False,"output":str(args.output)}))
