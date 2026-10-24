## B2 native traceOrResult partial checkpoint

Continues accepted source `80a054fa404bd311e08c6c993dcad67158af06af` on the original integration branch and draft PR #22. B2 remains incomplete; all 35 tables plus readonly receipt remain OPEN.

`JdbcLedger.traceOrResult` delegates to `SessionTraceServiceImpl` through typed `SessionTraces`. The Service owns tool-before-input refusal order, target/policy/content binding proofs, pending/replay decisions, reliable event/reservation/attempt/output proofs, task/tenant budgets and known failure settlement. `MapperSessionTraces` translates typed facts and the original versioned codecs. Ordinary step, reliable event, reservation and attempt reads use existing real MP projections; trace binding locks and pending CAS use parameterized `SessionTraceMapper`. Existing task/tenant/reservation/invocation settlement Mapper statements are reused once. Shared history, trace scan and read-receipt proof SQL still in use is not counted as deleted.

Ten static JDBC calls are removed: five reads and five writes. Static inventory is 218 with 756 literal binds. Raw dynamic expression/forwarder inventory remains 23; the original `found.rows()` accessor moves into the new adapter. The original statements and actual chain are indexed in `evidence/b2-native-trace-paths.json`.

Preserved behavior includes tenant/full keys, current authority/whole-scope/fence, same DS/UoW, original locks and refusal/proof order, reliable event receipt bytes, atomic settlement and UNKNOWN recovery without replay. The native entry retains its explicit unmetered query, inherited lease and absolute 2-second deadline. The pending path retains its final explicit check and SessionQueryTransaction retains last/beforeCommit checks. Known missing/corrupt/version/limit failures commit one settled TOOL and a FAILED binding with NULL scan/relations/target/edge metadata before throwing; unexpected failures roll back. Schema22, V1–V22 and frozen bytes are unchanged.

## Developer validation

The first complete directed verify passed 40 unit/contract checks and one JUnit integration, exit0, SpotBugs0/errors0. Its single private-instance cleanup receipt confirms children exited, fixture stopped, container removed and private root removed. This is developer self-test; no independent review or QA acceptance is claimed.

The selected unit/contract suite contains 40 checks, including 30 trace Service contracts. The exact baseline JAR SHA256 is `ff9e1936ab17f8522332c3c4bafbb671bb5eb41207acc10b2fc7c15b63d5cfac`. The changed JAR SHA256 is `e9fded1056eec86d73d6100054ea7255a3622fe3499255428d2ed40a54c42c39`, 44,129,112 bytes; all 1,230 compiled production/resource files match and test drivers are absent.

```powershell
$env:JIMU_IT_JAVA='/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java'
.\mvnw.cmd -o -B -ntp '-Dtest=SessionTraceServiceTest,PersistenceAssemblyTest,SessionDeadlineTest,PackageBoundariesTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest' '-Dit.test=B2NativeTraceIT' '-Djimu.native.trace.evidence=b2-native-trace-run1' verify
```

The focused integration uses exact baseline/new JARs, private MySQL 8.0.46 and local paid=false fixtures. Its one native v3 group follows an actual prerequisite read with three immutable trace slots: target0 with reverse derived receipt3, receipt3 with direct source0, and missing target127. Each trace slot checks SQL rollback, final beforeCommit deadline rollback, commit acknowledgement loss before/after physical commit, exact byte replay, reliable event fact/input binding tampering, stale fence and exactly one TOOL consumption. Each binary consumes four TOOL units including the prerequisite read. Provider request counts remain fixed during the fault/replay/proof driver. Three slots are within one JUnit integration, not three integrations or a complete native worker matrix.

Native cumulative query counters remain zero by the original unmetered contract. Raw SQL logs include assertion reads and rolled-back attempts. Output bytes replay exactly within each task; cross-task variable IDs/timestamps are not claimed identical. Narrower projections do not establish universal SQL/byte/roundtrip invariance.

Both baseline and changed cold paths hit the original absolute deadline inside the first SQL-fault exercise. Each retried that exact slot once only after proving task/history/read-binding/trace-binding/reservation/event/accounting facts unchanged (the fixture permits at most two such retries). Both raw failures and retries remain in the driver logs; no product deadline was extended. This does not close cold-start timing or complete lock permutations. Earlier batch failures remain under their original records.

## Remaining and handoff

Product management queues the shared runtime `JdbcSessionRecords.verify/verifyEvent` event/receipt proof Service, typed port, real MP/Mapper and corresponding old SQL deletion next, followed by other runtime receipt/proof, admission writes and module/maintenance paths. Middle A remains the sole integration writer on the same branch/draft PR. Completion requires the actual call chain and necessary affected-path differential evidence; shared SQL remains OPEN until its callers move. Independently bounded middle B work needs explicit interfaces, exclusive files and integration ownership. This run ends after the current slice; maintenance switching remains with the controller.

Core UoW final beforeCommit deadline gap, cold-start lock timing, full scope/maintenance/file lock arrangements, complete native workers, exact CLI assembly, actual v3 trace cleanup, nonempty paid-ledger anomalies and real paid vision remain OPEN. B3–B5, juniors, 61/62 and T48/new features are not activated. There is no external execution/member authorization blocker. No CI run/recovery/retry/wait, deployment, real keys/external billing/shared data/services, daemon/proxy/FRP/runtime restart or reset occurred.
