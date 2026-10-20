# Normal business admission

The configured model execution path remains ModelProfiles → OpenAiProtocol → DurableAgent →
the existing plugin gateway and JdbcLedger. No evaluation batch is started by selecting a model.
The HTTP task API cannot select a provider, policy, secret, model, endpoint or execution mode.

New BUSINESS report tasks use the source-bound `compose_report` capability instead of free
`write`; see [composition v1](source-bound-report-composition.md) for installation, schema,
receipt validation and recovery. Existing task pins and frozen evaluation tools remain valid.

The new `deepseek-nonthinking-v1` adapter is a versioned request strategy for official
`deepseek-flash` at `https://api.deepseek.com/chat/completions`. It sends
`thinking: {"type":"disabled"}` and `max_tokens`, omits `n`, `parallel_tool_calls` and Qwen
options, and uses the existing ordinary/SSE/tool parser. Usage on a final nonempty SSE choice is
accepted. The profile retains the complete capability, price, timeout, credential reference and
output envelope. The maximum total billable output remains 4096, with a margin of at least 10
(4096 gives HTTP 4086). No arbitrary provider JSON is accepted. Nonthinking is the only
supported DeepSeek strategy. The service label `DeepSeek-V4.1-Flash-1017` is descriptive:
pinning the request alias does not freeze the provider's weights.

Operator configuration adds a separate `jimu.business` section, strictly bound to
`BusinessPolicies.Settings`. `current-ref` selects a policy from the `policies` map. Each entry
has version 1, immutable `id`, mode `BUSINESS`, an `anchor` identical to the existing
`jimu.execution-scope.requirement` (issue, actual instance UUID, schema, tenant, original run and
approval digest), `instruction-digest` (SHA-256 of the authoritative business instruction's
original UTF-8 bytes), `profile-digests` (profile reference → complete canonical model SHA-256),
`request-strategy: deepseek-nonthinking-v1`, and `local-only: false` for cloud deployment.
Local loopback substitutes require both `local-only: true` and explicit test assembly.
Policy/profile canonical bytes use Payloads, including the retained version, not an HTTP
client's summary. All referenced versions must remain in restricted operator configuration.

This authority is additional to, and cannot replace, the original provisioning receipt. The
existing receipt hash, issue, approval, actual server UUID/schema, original run, plan and frozen
material digest are checked before admission. Old approval/material/scope bytes are not changed.
No new run or ledger is created. A policy authorizes ordinary business rather than a matrix,
W sample, scoring or B qualification. The original run's requests, costs, UNKNOWN occupancy,
STOPPED state and applicable cumulative rejection ceilings still apply. Task and tenant
budgets, actor/install permissions, response/tool limits, fences, deadlines and zero automatic
paid retries remain active. Paid dispatch is still separately disabled unless the trusted
`jimu.model.paid-dispatch-enabled` switch is explicitly enabled in an authorized environment.
No production config or price has been installed by this change.

V10 only adds two nullable task columns. A new business task atomically stores the policy JSON
and digest with its model/tool snapshots before queue reservation, and uses task record version
2. NULL historical tasks retain the old semantics; neither missing plan configuration nor
missing receipt turns them into business tasks. All work admission, cache recovery and report
publication use the same policy check under the existing run → tenant → task lock order.
Full request body hashes and the exact assistant/tool prefix through the preceding step are
verified before dispatch. A current step's already committed results are excluded from its own
request, so valid recovery does not replay that tool. Configuration changes affect new tasks;
recovery requires the original policy/profile version rather than the new defaults. Missing
retained authority returns 503, binding/snapshot conflicts return 409; integrity conflicts
durably stop the original run before any new model/tool/fee reservation. Known historical
usage settlement and owner/tenant-authorized committed downloads do not require current
admission. UNKNOWN amounts are never converted to zero.

Rollback: V10 remains forward-only. Old binaries reject the new schema, and their claim query
also excludes task record version 2. Stop and drain business tasks before any coordinated
binary/database rollback; never strip columns, policy bytes or task version to make an old
worker claim them. Application grants are unchanged (existing task DML suffices).

`BusinessProfilesTest` checks the closed cloud-shaped config, supported protocol and invalid
options without network. `BusinessAdmissionIT` / `tools/p3_business_verify.py` exercise real
isolated MySQL, separate worker JVMs, local TLS sources, real file/present and authorized
downloads with synthetic provider usage. Default `./mvnw -B -ntp verify` retains the historical
scope, plans, material/history, settlement and shared STOPPED regressions. These local tests
do not validate actual DeepSeek pricing, alias quality, account access, credential revocation,
private secret delivery, production TLS, or a real business task. No persistent project DB is
connected, migrated or bound; B and real model/search dispatch stay closed.

New BUSINESS task budgets are derived at acceptance from the trusted, fixed model snapshot
and the HTTP round limit (default and maximum 8; an explicit limit can only reduce it). For
each round, reserve `inputTokenUpper + maxOutputTokens` tokens and the upward-rounded
`ModelCosts.amount(price, inputTokenUpper, maxOutputTokens)` cost, including any fixed price.
The task token ceiling is that complete per-round bound times the round limit. The default
cost ceiling is the smaller of the same cost product and the original run's fixed cost limit.
An explicit HTTP cost must be positive and no larger than that derived ceiling; a cost below
one complete round is rejected before creating a task or queue reservation. Unrepresentable
arithmetic fails closed. A task ceiling is not an up-front fee reservation or actual usage:
each dispatch still locks and checks the original run, tenant and task balances, including
historical usage and UNKNOWN occupancy, before reserving that round. Concurrent tasks cannot
increase the original run's 500 CNY / 63-dispatch limits.

For the approved conservative full-context envelope, 1,000,000 input tokens at 2 CNY/million
plus 4,096 total billable output tokens at 8 CNY/million gives 1,004,096 tokens and
2,032,768 micro-CNY per round. Eight rounds give 8,032,768 tokens and 16,262,144 micro-CNY.
The HTTP output field remains 4,086; its safety margin does not reduce the billed reserve.
These are ceilings, not observed provider consumption. Accepted limits are persisted with
the task snapshots: identical submission, restart and recovery reuse them without recalculation
or increases. Existing tasks and ordinary/evaluation-mode defaults retain their prior budgets
(ordinary: 65,536 tokens / 1,000,000 micro-CNY); HTTP cannot promote a task to BUSINESS.
