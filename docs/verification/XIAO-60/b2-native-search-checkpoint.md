## B2 native searchOrResult partial checkpoint

Continues accepted source `b257556a9821552f62662d92507123692afd1b07` on the original integration branch and draft PR #22. B2 remains incomplete; all 35 tables plus readonly receipt remain OPEN.

`JdbcLedger.searchOrResult` delegates to `SessionSearchServiceImpl` through typed `SessionSearches`. The Service owns tool-before-input refusal order, normalized binding proofs, pending/replay decisions, reliable event/reservation/attempt/output proofs, task/tenant budgets and known failure settlement. `MapperSessionSearches` translates typed facts and the original versioned codecs. Ordinary step, reliable event, reservation and attempt reads use existing real MP projections; binding locks and pending CAS use parameterized `SessionSearchMapper`. Existing task/tenant/reservation/invocation settlement Mapper statements are reused once. Shared history, scan and proof SQL still in use is not counted as deleted.

Ten static JDBC calls are removed: five reads and five writes. Static inventory is 228 with 796 literal binds. Raw dynamic expression/forwarder inventory is 23, formerly 21: the original `found.rows()` value accessor moves into the new adapter, and two `Input.query()` accessors also match the scanner's SQL-name heuristic. These two added scan records are value accessors, not new dynamic SQL. The original statements and actual chain are indexed in `evidence/b2-native-search-paths.json`.

Preserved behavior includes tenant/full keys, current authority/whole-scope/fence, same DS/UoW, original locks and refusal/proof order, event receipt bytes, atomic settlement and UNKNOWN recovery without replay. The native entry retains its explicit unmetered query, inherited lease and absolute 2-second deadline. The pending path retains its final explicit check and SessionQueryTransaction retains last/beforeCommit checks. Known corrupt/version/limit failures commit one settled TOOL and a FAILED binding with NULL scan metadata before throwing; unexpected failures roll back. Schema22, V1–V22 and frozen bytes are unchanged.

## Developer validation

The second complete directed verify passed 37 unit/contract checks and one JUnit integration, exit 0, SpotBugs 0. The native v2/v3 cases are two groups within that single integration. Three cleanup receipts pass (first-round v2 plus final v2/v3). Final JAR SHA256 is `ff9e1936ab17f8522332c3c4bafbb671bb5eb41207acc10b2fc7c15b63d5cfac`, 44,099,825 bytes; all 1,212 compiled production/resource files match and test drivers are absent. The first-round JAR has identical production content but distinct ZIP bytes; both exact artifacts are preserved/reconstructible.

```powershell
$env:JIMU_IT_JAVA='/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java'
.\mvnw.cmd -o -B -ntp '-Dtest=SessionSearchServiceTest,PersistenceAssemblyTest,SessionDeadlineTest,PackageBoundariesTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest' '-Dit.test=B2NativeSearchIT' '-Djimu.native.search.evidence=b2-native-search-run2' verify
```

The focused integration uses exact baseline/new JARs with private MySQL 8.0.46 and local paid=false fixtures. Native v2/v3 groups each exercise two actual immutable search slots: a successful user-event match and a corrupt-event failure. Each original slot checks result SQL rollback, final beforeCommit timeout rollback, commit acknowledgement loss before/after physical commit, exact byte replay, reliable event fact/input binding tampering, stale fence and exactly one TOOL consumption. Provider request counts remain fixed during fault/replay/proof driver calls. Version groups are within one JUnit test, not separate JUnit counts.

Native cumulative query counters remain zero by the existing unmetered contract. Raw SQL logs include assertion reads and rolled-back attempts. Output bytes are replayed exactly within each original task; cross-task variable IDs/timestamps are not claimed identical. Narrower projections do not establish universal SQL/byte/roundtrip invariance.

The first complete directed verify failed in the baseline binary's cold search path with `SESSION_QUERY_TIMEOUT`; its raw log, JAR and successful cleanup receipt are preserved. The fixture was corrected to allow at most two retries of that exact original slot only after proving all task/history/reservation/event/accounting facts unchanged, including when deadline mapping happens after the SQL fault hook. The product deadline was not extended. This does not close cold-start timing or complete lock permutations. Earlier batch failures remain under their original records.

## Remaining and handoff

Product management queues native `traceOrResult` Service/typed port/real MP and Mapper/SQL deletion next, followed by shared runtime receipt/proof SQL, admission writes and remaining module/maintenance paths. Middle A remains the sole integration writer on the same branch/draft PR; independently bounded middle B work needs explicit interfaces, exclusive files and integration ownership. This run ends after the current slice; maintenance switching remains with the controller.

Core UoW final beforeCommit deadline gap, cold-start lock timing, full scope/maintenance/file lock arrangements, complete native v2/v3 workers, exact CLI assembly, actual v3 trace cleanup, nonempty paid-ledger anomalies and real paid vision remain OPEN. B3–B5, juniors, 61/62 and T48/new features are not activated. There is no external execution/member authorization blocker. No CI run/recovery/retry/wait, deployment, real keys/external billing/shared data/services, daemon/proxy/FRP/runtime restart or reset occurred.
