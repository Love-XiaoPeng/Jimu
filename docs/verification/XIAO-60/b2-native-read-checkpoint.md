## B2 native readOrResult partial checkpoint

Continues accepted source `5715fdfab8559f6a258ac7cac272030e27c46fbc` on the original integration branch and draft PR #22. B2 remains incomplete; all 35 tables plus the readonly receipt remain OPEN.

`JdbcLedger.readOrResult` now delegates to `SessionReadServiceImpl` through typed `SessionReads`. The Service owns original-slot/input/refusal ordering, binding checks, pending versus replay decisions, task/tenant tool limits, known failure settlement and receipt publication order. `MapperSessionReads` keeps row/codec conversion and the original shared authorization, fence, history, proof and accounting operations. Ordinary step/public-event/committed-receipt reads use real MP; binding locks and conditional result writes use parameterized `SessionReadMapper`. Existing task/tenant/reservation/invocation settlement Mapper statements are reused once. No dual writer is introduced.

Nine static JDBC call sites are removed from this entry, including four reads and five writes. Inventory is 238 static JDBC, 21 dynamic sites and 833 literal binds. Shared JDBC statements still needed by search/trace/receipt proof remain in the inventory and are not counted as deleted. See `evidence/b2-native-read-paths.json` for the exact original statements and chain.

Preserved boundaries include tenant/full keys, same DS/UoW, current authority/whole-scope/fence and original locks/refusals, native v1/v2/v3 receipt encoding/proofs, original NULL versus trace source arrays, atomic tool/account/event settlement, UNKNOWN recovery without replay, and the original explicit unmetered native query with inherited lease/2-second deadline. The existing SessionQueryTransaction final and beforeCommit checks remain. Schema22, V1–V22 and frozen bytes are unchanged.

## Developer validation

Final commands and results are recorded in the attached manifest and raw Maven logs. Tests cover the Service refusal/settlement contract, standalone MP/XML registration and same DS, deadlines, package boundaries, frozen resources and source-path inventory. The focused integration uses private pinned MySQL 8.0.46 and local paid=false fixtures for native v1/v2/v3, with successful and missing reads in two original response slots per binary/version.

Final directed command (the Linux Java21 toolchain is supplied by `JIMU_IT_JAVA`):

```powershell
.\mvnw.cmd -o -B -ntp '-Dtest=SessionReadServiceTest,PersistenceAssemblyTest,SessionDeadlineTest,PackageBoundariesTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest' '-Dit.test=B2NativeReadIT' '-Djimu.native.read.evidence=b2-native-read-run4' verify
```

The final unit/contract selection contains 26 tests. `B2NativeReadIT` contains the three version groups within one JUnit integration test; the groups are not counted as three JUnit tests. Tested JAR SHA256 is `653622a907fdae1635bffbd979cdf43245fab761791cab1633033a03973e7ffc` (44,069,150 bytes; all 1,194 compiled production/resource files match and test drivers are absent). The manifest pins the delivered source/tree and the actual final exit/test/static-analysis results.

Faults cover result SQL failure, final beforeCommit timeout, commit acknowledgement loss before and after physical commit, exact original-slot replay, receipt digest/input binding tampering, and stale fence. Each task proves one TOOL consumption per original slot and no provider calls during driver faults/replay/proof checks. Failed reads persist FAILED receipts before throwing; the SessionQueryTransaction timeout rolls all staged facts back. This does not close the separate core UoW final deadline gap.

Old and new binaries operate on separate admitted tasks: outcome groups and explicit query envelopes are compared. Each task proves exact output byte replay and native receipt verification; cross-task variable timestamps/IDs are not claimed identical. Native cumulative query counters remain zero as before; actual runtime SQL logs include assertion reads and rolled-back attempts, not just committed product writes. Narrower MP/Mapper projections do not establish universal byte/query/roundtrip invariance.

Round 1 failed compilation because the inner native read method shadowed the outer MP helper; no new JAR was produced. Round 2 passed 23 unit/contract tests and SpotBugs, but the old-binary tamper fixture violated the public-event type CHECK. The fixture was corrected to a digest tamper without changing product gates. Its failed JAR/logs and successful cleanup receipt remain preserved. The first post-correction binary was superseded after moving the remaining budget decision from the adapter into the Service; its exact JAR and validation records are retained separately. Final verification does not rewrite any earlier result.

## Remaining and handoff

Next concrete B2 slice is native `searchOrResult` Service/typed port/real MP and Mapper/SQL deletion, followed by `traceOrResult`, shared runtime receipt/proof SQL, admission writes and remaining modules/maintenance. Product management queues the next owner/action; middle A remains the single integration writer on the original branch/PR. Independently bounded middle B work requires explicit interfaces, exclusive files and integration ownership first. This run ends after the current slice.

Core UoW final beforeCommit deadline gap, cold-start lock timing, full scope/maintenance/file lock arrangements, complete native v2/v3 workers, exact CLI assembly, actual v3 trace cleanup, nonempty paid-ledger anomalies and real paid vision remain OPEN. B3–B5, juniors, 61/62 and T48/new features are not activated. There is no external execution/member authorization blocker. No CI run/recovery/retry/wait, deployment, real key/external billing/shared data/service operation, daemon/proxy/FRP/runtime restart or reset occurred.
