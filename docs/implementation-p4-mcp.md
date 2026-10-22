# Read-only MCP resource profile (XIAO-48)

This batch implements G02/T02–T04 through the existing task API, ledger and single
ToolCallingAdvisor loop. It is the sessionless same-host Streamable HTTP profile
in [ADR-0007](adr/0007-mcp-readonly-same-host.md), not general MCP tool support.
The [architecture handoff](verification/XIAO-48/architecture/implementation-contract.md)
and upstream source/license captures retain their original bytes.

Production defaults remain disabled. No server, secret, installation or execution
authorization is created by V15. This work does not authorize production enablement,
external services, deployment, paid model calls or native search distribution.

## Task and configuration contract

`POST /api/tasks` accepts a nonempty subset of `list_mcp_resource_templates`,
`list_mcp_resources`, `read_mcp_resource`. MCP tasks cannot mix other capabilities;
`resourceIds` remains empty. Existing identity, idempotency, installation, model,
business-policy and budget gates still apply. An MCP-only task finishes with a
summary; it does not stage or publish an artifact. Existing report and image
contracts are unchanged.

This first profile also requires the existing explicit local test model assembly,
`paid=false`, and no BUSINESS policy. Enabling MCP cannot expand a production model
or an existing paid/business authorization.

The operator-owned `mcp_server` table binds tenant, actor allowlist, server alias,
host identity, literal `http://127.0.0.1:<port>/mcp` endpoint, secret reference and
version, URI/template allowlists, protocol/server/capability snapshot and packaged
schema digest. Profile JSON must be the canonical typed `McpLedger.Profile`
serialization used by `Payloads`, with its SHA256. Each configuration or permission
change must increment the epoch. The application has SELECT-only access to this
table. Each task independently pins up to four complete profiles in the same
acceptance transaction as its original tool and model pins.

The enabling property is `jimu.mcp.enabled=true`. Secrets resolve only from host
properties named
`jimu.mcp.credentials.<tenant>.<server>.<credentialRef>.<credentialVersion>`.
Keep values in a private host configuration file; do not place them in CLI arguments,
task JSON, profiles or logs. Both roles validate V15 and the byte-pinned catalog.
Host startup/readiness and idle worker polling perform no MCP network I/O and do
not require any enabled server. Upstream liveness is checked only when a task
needs a new network operation, through its audited initialize/initialized handshake.
An offline server therefore fails that call with `MCP_UNAVAILABLE` without
blocking unrelated tasks, successful cached output or local cursor recovery.
Current authorization, revocation, epoch and pins are still checked on recovery;
revoking the last server permits the worker to claim and terminate affected tasks.
The four-server bound applies to each authorized task binding, not the whole host.
No MCP I/O occurs while disabled. A task cannot supply an endpoint, credential,
command, host path, tenant or task identity as tool arguments.

## Runtime and persistence limits

MCP `2025-06-18` uses JDK21 HTTP/1.1, an independent NO_PROXY selector and no
redirects. JSON and POST SSE are accepted; sessions, GET notification streams,
reverse requests, server instructions and dynamic tools are not adopted. Resource
URIs are RPC parameters, never HTTP targets or file paths. Only allowlisted ASCII
custom-scheme hierarchical URIs and simple approved templates are accepted.

Each initial list consumes at most ten upstream pages, 100 items per page and
1,000 raw items. The complete permitted projection becomes immutable local pages
of at most 100 items and 65,536 output bytes. Local 128-bit cursors bind the tenant,
owner, task, server, method, pins, query, body hash and expiry. Cursor replay makes
no upstream request. A task may reserve four list roots at 1 MiB each, charged
against its existing tenant storage budget before network I/O.

Reads accept at most 16 contents and 262,144 decoded bytes. Text previews share a
16,384 UTF-8 byte budget, below the handoff's 32,768 byte maximum, to leave space
inside the unchanged local model checkpoint budget. Previews end on codepoint
boundaries; full decoded byte counts and SHA256 remain available. Binary content
returns identity, MIME label, size and hash only. Large cumulative conversation
history can still exhaust the existing model budget and fails explicitly.

An invocation has a 30-second monotonic network budget, capped by the task deadline,
and a 1 MiB cumulative raw-response budget including initialization and all pages.
Each send is recorded before dispatch. Tasks allow 64 ordinary sends and at most
20 cleanup notifications (one per invocation). Cancellation closes the body
subscription/future and attempts a bounded cancellation notification; it does not
claim that the independent remote service has stopped.

V15 adds nullable task MCP pins and `mcp_server`, `mcp_io`, `mcp_result`, `mcp_cursor`.
Old task/model/tool/checkpoint payload versions and bytes are unchanged. A RUNNING
call lost before commit remains UNKNOWN; successful calls restore the original
output after current authorization checks. Profile changes never rebind old tasks.
List bodies and cursors expire after seven days, without extension on access.
Cleanup only releases storage for terminal, proven-quiescent tasks with no UNKNOWN
reservations; invocation outputs, hashes and send history remain.

Use `tools/evaluation/application_grants.py` for application SQL. MCP I/O/results
receive INSERT/UPDATE, cursors INSERT/DELETE, server metadata SELECT only. Migrate
only after excluding/draining old binaries. Rollback means a V15-compatible binary
with MCP disabled; old binaries reject V15 rather than silently claiming new tasks.

## Focused local verification

Use Windows JDK21/Maven3.9.9 plus WSL Linux Python3, a reviewed local Linux Java21
runtime and the existing pinned MySQL8.0.46 image. The integration driver creates
its own MySQL container, synthetic service directory and child processes; product
and resource service processes run as uid65534. It never changes shared services.

```powershell
$env:JIMU_IT_JAVA='/operator/linux-jdk21/bin/java'
.\mvnw.cmd -B -ntp '-Dtest=McpContractTest,ModelProtocolTest,PackageBoundariesTest' '-Dit.test=McpResourceIT' verify
```

For the R1 readiness regression alone, add `-Djimu.mcp.readiness-only=true`.
It uses refused connections for the other tenant, stops the real MCP service
during read/list checkpoint recovery, restarts both control and worker while
offline, checks local pagination and revocation of all servers, and observes idle
polling without additional wire sends. The default integration includes this
regression after the existing resource, authorization and recovery cases.

On Linux set the same environment variable and use `./mvnw`. The integration entry
runs `tools/mcp_verify.py` in the foreground. Evidence includes raw redacted MCP
and model messages, DB snapshots, precise task errors, service/JAR hashes, child
identities and cleanup receipts. Failure attempts must be retained. Actual results
and source/build identity are recorded separately in this issue's verification
receipt; this document is not an acceptance claim. No GitHub CI or old complete
P2/P3/P4 matrix is run or awaited.
