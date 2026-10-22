# QA-01 correction handoff

This corrects the native-stop false positive reported by independent QA against `840f62c98c7a3ebbfc103b1e1d80153a8b75e442`, tree `b149cb2781dab3737e9bd4fe46219f09532818a5`. That QA failure and the old review/provenance conclusions remain valid historical records. Base master is `f6a97168281990fd036cc18bf0ddef8045d90a6d`; PR #10 remains draft. The final source SHA/tree and evidence index are in the attached delivery receipt, avoiding a self-referential commit hash here.

## Decision and scope

Proc ENOENT and `ProcessHandle.empty` share a visibility boundary. `NativeProcesses.stopped` now propagates missing observations. `NativeRunner` may independently prove absence with the existing hash-checked C launcher, using only `--probe-absent <positive-pid>`. `kill(pid, 0)` sends no signal, inherits the JVM caller's PID namespace and does not rely on proc hidepid. ESRCH alone proves absence; success means present, EPERM and other errors remain unproven. Java bounds the synchronous helper, collects it, and preserves interruption. An old launcher cannot provide this proof and is rejected by the enabled readiness execution.

Visible zombie/dead state or changed start ticks/boot still prove that the recorded identity stopped. A live matching-ticks process with a different namespace identity is rejected. Signalling still requires readable identity plus the captured JDK handle's start-time protection. A hidden live PID is not signalled merely because a probe reports presence.

Normal completion, cleanup and supervisor native recovery share the independent proof. The enabled supervisor also uses it for a missing worker/drainer process, since native quiescence depends on that receipt. Unproven work retains its native slot, native SQL record and storage reservation. Disabling search supplies no recovery proof: outstanding native runs remain unproven even if generic worker bookkeeping changes. UNKNOWN is not replayed. The existing non-native P2 bookkeeping path is unchanged when search is disabled.

The only C change is the bounded no-fork, no-task-data probe mode and its integer bound. Rebuild a fresh launcher and configure the new digest on both roles. The startup/gate protocol, rg/bwrap pins, namespaces, production limits, V11/schema and tool pins are unchanged. The vendor/npm difference and exact-source/license closure remain unresolved: no external distribution or production enablement is approved.

## Final artifact and validation

Product JVM/C/bwrap/rg run as uid65534 on real WSL Linux ext4/POSIX; the root driver owns only private mount/PID namespaces, temporary MySQL8.0.46 and controlled local providers. No host proc policy or shared service is changed.

| Artifact | SHA256 |
| --- | --- |
| Source-built JAR | `84bf6e023b710ff5bbc693e172983213bf48d377b2db26c956f663cf92798922` |
| New C launcher | `c5638ba0dba4ffb64b984baa945886275a903c6ff565a1fbfa03b96c432b0083` |
| Unchanged rg | `193906679498de4d939345b937fa24e0e69a03c244bd70c859f5e41232713f21` |
| Unchanged bwrap | `e318903862396f96de3df57264e0158682b952fd3fb53ac23d876413e7b30f71` |

The bundle receipt records the actual compiler/sysroot argv, source and every retained input hash, notices and dynamic dependencies. Native binaries are not redistributed in the evidence attachment.

The successful Maven command is `bash mvnw -o -B -ntp verify -Dtest=WorkspaceEditContractTest,NativeSearchContractTest,WorkspaceToolContractTest,ModelProtocolTest,PackageBoundariesTest,WorkerLoopTest,LocalWorkerSupervisorTest -Dit.test=WorkspaceIT`. It runs 29 unit tests and one real WorkspaceIT, with zero failures/errors/skips; formatting, Checkstyle, dependency/architecture checks and SpotBugs pass. This is a targeted verify, not the old full P2/P3 matrix.

Run `tools/p4_proc_verify.py` with `--jar`, `--test-classes`, `--bundle`, `--evidence` and `JIMU_IT_JAVA` as documented in the runtime guide. The original QA scripts are also retained unchanged in the input directory; only the copied fixture root is redirected. Old source reproduction exits 0/0/1 for hidepid=0/1/2. The new JAR's proc-only API exits 0/0/0, preserving unknown rather than falsely proving death.

The new test starts a real product readiness execution, then keeps the same non-root JVM alive across private proc remounts 0/1/2/0. A same-UID, PR_SET_DUMPABLE=0 sleeper remains State=S throughout every live observation. Runner and supervisor refuse premature worker/native STOPPED or refund; TERM and KILL followed by actual parent reap permit exactly one stop receipt. A foreign UID causes conservative EPERM. A stale-ticks receipt against a real live PID does not signal it, and a namespace mismatch is rejected. Disabled native recovery returns no proof.

The hidden-process supervisor test uses a ledger spy and one real process represented in the recorded identity slots. It tests the product write boundary, not a real hidden SQL/native tree. Actual SQL/native-tree/drainer, UNKNOWN and fence regressions use the existing selectable `p4_workspace_verify.py` entries S01/S02/S03/L01/L02 against the final JAR; command receipts and original output are attached. No hidden-proc SQL early STOPPED/refund/orphan is claimed for the old version. Real kernel PID reuse was not forced; the stale identity is constructed against a real live PID.

## Retained attempts, cleanup and next action

The first C preparation attempt used an incorrect output-argument index and failed linking; it was corrected before any product test. One WSL shell invocation lost its case argument and was rejected by argparse without starting a fixture. S03 initially exposed a test-cleanup caller still using proc-only signalling on an already reaped process; it now uses the same runner proof as production, and passes. A later verify reused WorkspaceIT's prior evidence directory and failed with FileAlreadyExistsException; that output was moved aside intact and verify reran successfully with a fresh evidence directory. Earlier build hashes and all unsuccessful logs remain separately labelled; they are not final artifact results.

Final delivery includes every selected command/exit, process and SQL receipts, probe observations, exact input/source hashes and independent cleanup audit. Temporary fixture containers, providers, child processes and roots are removed before handoff; original shared toolchains/bundles, QA input and frozen historical evidence are preserved. The original 29-unit/2-integration and other E/S/V/B results are historical evidence, not a claim that those entire suites were rerun after this correction. Remaining untested fault permutations and the source/license restriction are not waived.

Product manager next locks the attached final source SHA/tree, sends only the correction and related lifecycle scope to the original code reviewer and QA, then repeats business acceptance. This backend handoff does not merge, deploy, or dispatch them. No GitHub CI is run/enabled/retried/awaited; no real model/web/search request, production access, real Key or paid IO is used. Existing ledgers, UNKNOWN records, services and frozen snapshots are untouched.
