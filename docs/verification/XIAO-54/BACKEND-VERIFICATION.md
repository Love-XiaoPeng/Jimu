# XIAO-54 backend verification

This is developer verification, not independent review, QA, or product acceptance. ADR-0013 is accepted only for `read-receipt-sources-v1` under product decision `01a166ed-74fb-7321-9dce-1f0e5419d460`. `replacement_policy=unsupported` remains explicit; surface, replacement chains, model causality, old-history backfill, and full G22/T46 compatibility are not delivered. The architecture package and frozen upstream differences remain unchanged.

## Source and artifact

Architecture entry: `204343e153c674e2dc188d0327edcb30a1c3814c`. First buildable slice: `e16c45ee8b7f4025edf0f10acab8bb36008a3cde`. Production source and packaged artifact: `47a4646d2b91aa069f8447e1e1405dc0a4fcbd8a`, tree `343b0e3da33ab2d161c3d85c63b05a6eec3fa5c4`. Later commits add only verification code/documentation. PR #19 retains the same draft/head branch and master base.

Tested JAR SHA256: `7a1734eba759d265f90f0ca6600635569769aa3c40f063c4d3975780c34168fa`. The separate delivery manifest records final checkout SHA/tree, each source/resource hash, JAR entries versus `target/classes`, and immutable baseline comparisons. The original architecture manifest still records its original proposed ADR bytes; it is not rewritten to hide the later scoped acceptance.

## Commands and evidence

Windows JDK21/Maven wrapper:

```powershell
.\mvnw.cmd -B -ntp spotless:apply package '-Dtest=SessionTraceContractsTest,SessionSearchContractsTest,SessionContractsTest,SessionDeadlineTest,PackageBoundariesTest,ModelProfilesTest,ModelProtocolTest,ModelInputBoundTest,WorkingDirectoryContractTest,TodoContractTest,UserQuestionContractTest,McpContractTest' spotbugs:check
```

Exit 0: 188 tests, zero failures/errors/skips; SpotBugs zero bugs/errors. The extended ledger scenario helper was subsequently test-compiled and its contract tests passed before running recovery and cleanup scenarios. No production source changed after packaging.

Runtime commands use WSL Ubuntu, private MySQL8.0.46, unprivileged real control/workers, and a loopback HTTP model with explicit `paid=false`. Each command uses `JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java` and `--jar target/jimu-0.1.0-SNAPSHOT.jar`:

```text
python3 tools/session_trace_verify.py --evidence target/xiao54/final-trace
python3 tools/session_trace_verify.py --evidence target/xiao54/final-observations --case observations
python3 tools/session_trace_verify.py --evidence target/xiao54/final-legacy-v2 --case legacy-v2
python3 tools/session_trace_migration_verify.py --old-jar ../jimu-v20.jar --evidence target/xiao54/final-migration
python3 tools/session_search_verify.py --evidence target/xiao54/final-search-v2 --case core
python3 tools/session_event_verify.py --evidence target/xiao54/final-read-v1 --case normal
python3 tools/session_event_regression_verify.py --evidence target/xiao54/final-regressions
```

Exit codes, assertions, original protocol/ledger/SQL, JVM logs, failure evidence and cleanup receipts are supplied in the delivery attachment. The old V20 JAR is the previously reviewed artifact with SHA256 `41bd465248bf3f1762e63e34998d3092bf5f9361c0eb8a8ce40adf94f385f405`.

## T01–T11 coverage

| Item | Actual developer verification | Limits |
| --- | --- | --- |
| T01 | Two real workers; search→read→trace→independent owner read/digest; task completes; owner query leaves state/counters/cursor unchanged; control restart; actual SSE and exact Last-Event-ID suffix; original-slot takeover | Local HTTP model fixture; no external model/service |
| T02 | Actual clipped seven-source and full nine-source windows; failed read `[]`; two ascending direct reverse receipts; structural empty summary; no guessed/transitive sources | Does not claim surface or causal relationships |
| T03 | Read/trace/search in one response share the M1 prefix; next step sees receipt; successful trace replay returns original bytes despite later read and a tampered current target | Not every concurrent append schedule |
| T04 | M1/M2/trace commit before/after kill/takeover; commit acknowledgement lost before/after; once-only consumption/receipts; failed replay; real ledger actor/pin/fence/revocation/flag and generic bypass checks; cancellation/deadline prevent commit; flag-off owner history and cleanup | Authorization/commit schedules not exhaustively enumerated |
| T05 | Other tenant and same-tenant different owner; body/session conflict and auth-before-body; corrupted source snapshot/digest, self/future/duplicate sources, event/source digest, sparse prefix; original-slot checks | Not an exhaustive database corruption fuzzer |
| T06 | Strict raw owner/model JSON, duplicate/unknown fields, UTF-8/surrogate, lexical -0/decimal/exponent, safe integer bound, exact1024/1025 bytes; auth lock and slow body share absolute deadline; existing physical deadline tests | DNS/TLS/network schedules not exhaustive |
| T07 | Bounded PK EXPLAIN and actual EXPLAIN ANALYZE row count; large/sparse seq; real unavailable table/provider fails safely; Unicode256-code-point summary; metadata-headroom capacity failure; source9 and byte input boundary | Not all N±1 cases at128 candidates/2MiB/180 edges/20 reverse/1024 proof reads/8MiB scratch/operations/32KiB output; these limits remain explicit rejection guards |
| T08 | Synthetic token/path redaction; hidden reasoning absent from native events, trace and actual SSE; summaries use captured public projection; flat endpoint proof | No general secret detector claim |
| T09 | Real V20→21; unchanged old snapshots and V1–20 checksums; relation0/NULL without backfill; old control/worker reject21 before readiness; pin switch rejects and restores; v1/v2 trace unavailable; old profiles retained | V21 rollback is an understanding binary with flags off, never schema downgrade |
| T10 | Private7-day timestamp fixture stops access; source/output copies accounted and purged; actual metadata=held fixed point; tenant exact difference; two controls and repeats release once; transactional purge rollback; UNKNOWN prevents cleanup | No process kill inside the purge transaction;7days is a timestamp fixture |
| T11 | Same JAR old v1 read, v2 search, directory/workspace POSIX/CAS, MCP, todo, question and old task resume | Targeted touched paths; old full matrix was not rerun |

## Preserved failures and remaining acceptance

The first compile used `ToolDescriptor.name()` instead of `capability()`; it was corrected. Initial runtime run1 reached existing metadata/cleanup headroom at the sixth M2 and safely failed `SESSION_HISTORY_LIMIT`; tool/model maxima do not guarantee every sequence fits metadata quota. Five-tool closed-loop verification passes without raising caps. Run2 incorrectly used nonexistent identity `a2` (401); its same-tenant isolation fixture was corrected to registered subject `c`. Original failed runs and successful cleanup receipts are preserved alongside final evidence.

Historical Windows full verify262 tests/1failure/15errors, frozen ADR index length3446 versus actual3819, and prior POSIX failure remain historical failures, not newly successful checks. GitHub CI was not run, enabled, restored, rerun or awaited. No deployment, production migration/grants, paid model/search, shared daemon/proxy/FRP change, or external report was performed.

Product manager next arranges the original independent reviewer and original QA against the final checkout and this exact JAR, obtains business acceptance, then decides merge/continuation. This issue remains in progress during that work.
