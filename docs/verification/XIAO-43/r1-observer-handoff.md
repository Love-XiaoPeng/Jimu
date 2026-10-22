# R1 observer identity correction

This corrects the high-severity review failure against `8f4e9927cbdc87c4d6958515d7f0d433f32cad42`, tree `3cc0f31cdf6ffa008e4718b80796163d96004a99`. That failure, the original QA-01 failure and earlier provenance/license conclusions remain historical records. PR #10 stays draft, base master `f6a97168281990fd036cc18bf0ddef8045d90a6d`. Final source SHA/tree, exact artifact hashes and command exit codes are in the attached delivery receipt.

## Decision

ESRCH proves absence only in the probing process's PID namespace. A receipt's target namespace cannot identify that coordinate system: the sandbox init legitimately lives in a child namespace. A proc-self/JVM PID consistency check alone also cannot identify the receipt's origin.

`ProcessIdentity` therefore adds `observerPidNamespace`, independently captured by Java and the C parent when they record each numeric PID. Existing `bootId` records that observer's boot. Native durable launch/started JSON and SQL process identity contain the new field. Before interpreting start ticks, zombie/dead state, probe absence or signalling, `NativeProcesses.requireObserver` requires current boot and observer namespace to match. `NativeRunner.absent` accepts and validates both binding values, including when called through recovery. The numeric-PID-only public proof is removed. Target own namespace checks remain separate; child init is allowed.

Workers persist boot and observer namespace at registration through the existing ledger. Forward migration V12 adds nullable columns without rewriting existing receipts; the current schema gate requires all twelve migrations and rejects old worker versions. Supervisor checks worker bindings before its generic process loop and native worker/drainer ownership observations. When search is disabled there is no independent kernel probe; missing proc/JDK handles cannot close worker receipts. Matching visible exited states can still close them. This intentionally preserves unknown over unsafe recovery.

Legacy native JSON can still deserialize, but its missing observer cannot prove exit. Legacy worker columns remain null. Different boot/namespace and inaccessible bindings likewise remain unproven; host-id is never used to backfill an assumed coordinate system. Outstanding legacy receipts require independent operator resolution. No automatic release, replay or resurrection is introduced. New startup with an old launcher cannot establish the new binding and fails readiness. Operators must build the new launcher and configure its digest on both roles; default search remains disabled.

The only C delta adds observer namespace to its own durable identity encoding. Probe argv, rg/bwrap pins, FD mounts, gates and resource limits stay fixed. No new host execution or arbitrary command is admitted. Source/license closure remains unresolved, prohibiting external distribution and production enablement.

## Reproduction and validation

Use the same WSL Ubuntu/native ext4, Java21/Maven3.9.9, actual uid65534 product JVMs and the new offline-built bundle. Root drivers own only their temporary namespaces/MySQL8.0.46/local controlled providers. No host proc policy or shared service is changed. Commands and original outputs, including retained preparation attempts, are attached.

Targeted build:

```text
bash mvnw -o -B -ntp spotless:apply
bash mvnw -o -B -ntp verify -Dtest=WorkspaceEditContractTest,NativeSearchContractTest,NativeProcessReceiptTest,WorkspaceToolContractTest,ModelProtocolTest,PackageBoundariesTest,WorkerLoopTest,LocalWorkerSupervisorTest -Dit.test=WorkspaceIT
```

This selects 35 unit tests and one WorkspaceIT, including legacy native JSON, missing/foreign worker observer, wrong boot and rejection before changed-ticks proof. It retains formatting, Checkstyle, dependency/architecture and SpotBugs gates.

With `JIMU_IT_JAVA` pointing to Java21, run these root drivers with fresh evidence directories:

```text
python3 tools/p4_namespace_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --test-classes target/test-classes --bundle /operator/new-bundle/bundle.json --evidence /operator/new-evidence/namespace
python3 tools/p4_proc_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --test-classes target/test-classes --bundle /operator/new-bundle/bundle.json --evidence /operator/new-evidence/proc
python3 tools/p4_workspace_verify.py --case L01 --jar target/jimu-0.1.0-SNAPSHOT.jar --test-classes target/test-classes --bundle /operator/new-bundle/bundle.json --evidence /operator/new-evidence/L01
python3 tools/p4_workspace_verify.py --case L02 --jar target/jimu-0.1.0-SNAPSHOT.jar --test-classes target/test-classes --bundle /operator/new-bundle/bundle.json --evidence /operator/new-evidence/L02
```

The namespace driver records a real outer same-UID process, then starts same-namespace and private-namespace product JVMs with actual native readiness. The outer process must remain State=S. A foreign observer rejects static/runner proof, forced signalling and a readable local PID with changed ticks; enabled and disabled supervisors must emit no worker/native STOPPED or storage-release calls. Same-namespace live control retains its receipt. A strict ledger spy rejects any unexpected call, including replay.

The proc driver keeps one product JVM through private hidepid=0/1/2/0 remounts while its nondumpable same-UID target stays alive, tests foreign-UID EPERM, then separately proves TERM and KILL exits after actual parent reap. Stale ticks and mismatched target namespace never signal a live process. These spy tests observe product write boundaries, not a real cross-namespace/hidden SQL refund or orphan. Full SQL/native-tree/drainer behavior, UNKNOWN retention, safe takeover and old-fence late admission/result/model rejection use the existing L01/L02 entries. S01/S02/S03 additionally cover ordinary native semantics and affected identity/escape cleanup.

Real kernel PID reuse was not forced. Not every filesystem/kernel/probe timeout/interruption permutation was injected. Earlier full E/S/V/B and P2/P3 results remain historical; this correction does not repeat those matrices or imply independent review/QA has passed. The attached final receipt is the authority for actual exits and cleanup.

## Handoff

The new source, rebuild recipe, bundle/source hashes, command receipts and indexed text evidence are delivered to the product manager for a fresh SHA lock and original reviewer/QA verification. Only after that can business acceptance be repeated. This backend turn does not merge/deploy or dispatch duplicate reviewers. No GitHub CI is run, enabled, retried or awaited. Existing production services, fixture-independent ledgers, paid=false, UNKNOWN records and frozen snapshots remain untouched. Native binaries are not redistributed in the attachment.
