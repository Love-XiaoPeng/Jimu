`sessionAccess/sessionAdmission` B2 partial checkpoint; developer self-test only. Source parent is `353595c445e7cf83280f14a39706d072466f2941`; the delivered manifest supplies the final source SHA/tree and remote receipts. XIAO-60 remains in_progress and PR22 remains draft.

## Implementation and preserved boundaries

`SessionEligibilityServiceImpl` owns both complete eligibility sequences. Typed ports obtain history, descriptors, fresh per-capability tools pins, installation values and selected model snapshots lazily. Access retains the non-session refusal, history(false), sorted capability loop, descriptor → fresh tools snapshot → installation lock → five original short-circuit comparisons. Installation getters retain original string/numeric conversion and NULL evaluation timing, including argument evaluation before a null receiver's equals fails.

Admission retains each flag/predicate call, history(true), repeated trace/search predicates and branch-local snapshot/model validation. Scope selects requireSessionRead. There is no new non-session guard, eager snapshot, merged eligibility call or rejection mapping. JdbcLedger changes only these two method bodies and its stateless Service field. baseAccess/access catch/invalidation, actual claim/fenced/existing/response callers, surrounding locks, costs, events and account sequences remain unchanged.

One old JDBC site moves to a parameterized Mapper/XML with the exact `SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE`, full tenant/plugin binds, original first-row/404 semantics, explicit query context and same DS/UoW. No new query or transaction. Inventory is 178 static / 29 dynamic / 577 binds; this is one JDBC-site removal, not removal of the lock SQL or completion of a table. Admission has no old SQL. The tracking test now permits only this exact mapper ID and exact existing SELECT-star statement; all other star/dynamic-substitution checks remain.

The source audit preserves 537 other existing production Java files and 416 baseline resource/frozen files (the original 413 plus three prior fact XML files). This slice adds one mapper XML. Schema22/V1–V22 and previously integrated 63/64/66/70 remain unchanged. No deadline/socket/network/transaction setting changes.

## Accurate product and executed rounds

Baseline JAR: `d13f81ee3078d169213c3187c7336a532cb15a790ed7d57d9f180edf9d83ab4d`, 44,463,423 bytes, 1,503 production entries.

New exact run2 JAR: `96b813d078f71cfe338436f19695cac0b196133c364016b9f4c49bd53f01adb6`, 44,474,698 bytes. Entry manifests, target/classes comparison and nested-class parser proof are attached. Of the prior entries, 1,495 retain their original bytes; JdbcLedger and seven nested classes change raw bytes, with the latter seven differing only in LineNumberTable. Eleven entries are new, for 1,514 total. Source/class/compiler receipts and original logs are sealed for each execution before subsequent changes.

| Execution | Actual outcome |
| --- | --- |
| unit1 | 22 tests: 21 passed, one fixture assertion failed. Null pin.version still evaluates the installation-version argument first; only the expectation was corrected. |
| verify run1 | 42 tests: 41 passed, one tracking gate failed on the required existing SELECT-star lock. No newly packaged product, IT or SpotBugs in this failed execution. |
| verify run2 | Two tracking tests plus one real integration test passed; verify exited 0. SpotBugs bugs/errors both 0. This was a narrowed rerun, not 42 tests passing in the final round. |

The corrected 21 Service tests, loaded Mapper SQL/bind test, architecture, scope and task-admission tests passed in run1; only the tracking gate failed there. Both private native/scope fixture groups completed cleanup with all children exited and containers removed. Original failures remain in the evidence. The first external evidence analyzer selected nonexistent rejection_code instead of the actual dispatch code field; its original source/stderr are retained. Correcting that selector does not change the executed driver or raw evidence.

## Actual runtime evidence and limits

Native-v3 and scope-v4 each execute old/new exact JARs against the same frozen private facts: 27 results and 27 outer-transaction captured-fact restorations per driver, across 19 tenant fact tables. Actual owner access, internal idempotent submit re-fetch, prepare/fenced, response success and known response rejection are covered. Additional samples exercise flag/model/snapshot/tools/pin and non-session refusal branches. Unit/source checks cover native read/search branch selection; this is not an additional native-v1/v2 runtime matrix or successful tool-read proof.

The comparison preserves original event strings/digests/lengths first, then compares only the documented JSON-value/UUID-normalized range; Map.of order and generated UUID differences are retained. Normalized sampled SQL order and original repeated account counts match. Every full-key shared-installation-lock binding is compared separately. The responded-reject sample includes four explicitly labelled post-outcome evidence SELECTs in its recorded SQL; those are outside the original production call and must not be called production SQL coverage.

Known rejection evidence observes cost/dispatch/reservation/reliable model.rejected writes after the inner UoW outcome error and before the enclosing trial rollback. This is not a durable caller commit, independently committed postnote or persisted FAILED-read. Response originates from the fixture, not a worker raw-string provenance proof.

Missing installation uses an empty ResultSet shim on the real lock statement: actual DB deletion/missing-row coverage remains absent. The one installation fault per driver is HY000/vendor1644 injected before SQL execution on a rollbackable connection, not natural 08S01, lost connection or UNKNOWN recovery. Task deadline renewal and fixed event clock are separately recorded fixture setup; original cumulative query/two-second absolute deadline settings are unchanged. No provider calls, UNKNOWN replay/recovery or old matrix reruns.

Scope403 independent afterCompletion/REQUIRES_NEW invalidation is source-preserved but not newly runtime-validated. Prior fact/read old-side timeouts and UNKNOWN failures are unchanged and OPEN. This checkpoint does not establish full successful scope tool read, persistent FAILED-read, whole-task/database rollback, independent postnotes, cold paths/lock arrangements or core final beforeCommit. Historical 08S01/709ms, failures and missing-original limits remain OPEN, along with B2/B0–B5 remainder and 35 tables plus receipt; final 61/62 remain backlog.

## Handoff

Delivered to product management as a partial checkpoint with exact source/product, sealed execution originals, SQL/binding/rejection comparisons, cleanup receipts and reproducible hash verification. No newly observed production defect required a behavior change. No new authorization blocker. Suggested next bounded slice for PM scheduling: the remaining JdbcLedger traceAvailable/priorAvailable/sessionAvailable availability orchestration; baseAccess and other modules were intentionally outside this dispatch and are unchanged. No CI, master merge, deployment or service/daemon changes.

Current run `01a17270-92e2-7f29-8351-7e4e6c6326fc` reuses unique terminal/two-hour rule `01a17271-9658-7545-860f-fa34802337b8`; no duplicate rule was created.
