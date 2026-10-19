# Normal business admission

The configured model execution path remains ModelProfiles → OpenAiProtocol → DurableAgent →
the existing plugin gateway and JdbcLedger. No evaluation batch is started by selecting a model.
The HTTP task API cannot select a provider, policy, secret, model, endpoint or execution mode.

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
