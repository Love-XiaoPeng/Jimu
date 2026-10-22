# P4 workspace runtime

This is the implementation companion to the accepted [contract](implementation-p4-workspace.md) and [ADR-0005](adr/0005-workspace-edit-native-search.md). The contract's historical probes remain historical; product verification is recorded separately. P3 real model/public report delivery is complete; real `web_search` remains unconfigured. This branch requires independent review/QA and product acceptance. No deployment or GitHub CI is part of this change.

## Configuration and admission

`edit@0.1.0` uses the existing private `jimu.workspace-root`. `glob@0.1.0` and `grep@0.1.0` are disabled by default. To enable them on both control and worker, provision the reviewed [offline bundle](../workers/search/README.md) and set:

```properties
jimu.search.enabled=true
jimu.search.root=/operator/private-search-root
jimu.search.launcher=/operator/bundle/bin/jimu-search-launcher
jimu.search.launcher-sha256=<exact local build SHA256>
jimu.search.rg=/operator/bundle/bin/rg
jimu.search.bubblewrap=/operator/bundle/bin/bwrap
```

The search root must exist on native POSIX storage with mode 0700. Bundle files must be ordinary executable files reached without symlinks, without setuid/setgid or group/other write permissions. Control and worker run as the same dedicated non-root OS identity on the same host, sharing existing host-id/storage ownership. Enabled startup checks fixed hashes and executes a real no-data namespace/gate self-test. Invalid/missing isolation fails startup; there is no host-rg fallback. Disabled control omits search capabilities and rejects search admission with `SEARCH_NOT_CONFIGURED`. Installation rows and epochs are operator-owned; migrations never install tools for a tenant. Normal report policy permits the original ordered web_fetch/read/compose_report/present set plus edit/glob/grep, with the original report source/time/version constraints.

V11 is forward-only and adds `native_search_run` plus insert-only physical `native_search_stop`. Generate runtime grants with `tools/evaluation/application_grants.py`; the stop table gets SELECT and INSERT, not UPDATE/DELETE. V1–V10 are untouched. New task pins containing any of edit/glob/grep initialize workspace format3 before any file operation. Old pins keep format2 and its original bytes/output shapes. There is no conversion of existing tasks. Old workers reject format3. Never roll back schema or retry UNKNOWN as part of disabling search.

## Invocation behavior

`edit` requires a read observation and performs the literal replacement, CAS and durable operation receipt under the existing native workspace transaction/lock. It keeps Unicode/BOM/newline behavior from the contract. Recovery only uses the original host operation identity, original frozen expected version and original result receipt. It cannot overwrite a later edit or replace an unproven binding. Existing write/read/present DTOs do not gain mtime fields.

Search copies one immutable task file snapshot without advancing read observations. All projected objects are ordinary private files; prefix conflicts such as `a` and `a/b` fail before launch. The C launcher restores nanosecond mtimes exactly using `futimens` because JDK timestamp setters can round them. No model argument supplies scope, native argv, host paths or operation IDs. Only fixed rg and projection FDs enter the empty namespace. `glob` includes hidden/ignored files, excludes six VCS directories, samples 100 paths then pages the remainder in native order. `grep` uses Rust regex, native ignore rules, at most 250 matching lines per page and a 2000-byte UTF-8 line preview. Every serialized page is at most 65536 bytes.

A cursor reads the persisted result object and does not start rg. Every read checks tenant/task, current authorization/epoch/lease, normalized query and plugin pin, expiry, immutable file hash/size and fixed page position. Repeated cursors return identical pages after control/worker restart. A source edit between pages does not alter the snapshot. Search results never authorize edit; use read. Result roots are charged through original SEARCH_STORAGE reservations: reserve 4MiB, settle exact result bytes; at most 20 roots/8MiB per task, seven-day retention. Quota is released only after physical cleanup on a stopped, quiescent terminal task.

Native stdout is bounded at 20,000,000 bytes, stderr retained as a 64KiB tail. Canonical result is bounded at 4MiB; malformed/truncated output is never a partial success. CPU/AS/FD/core limits are installed in C. Java's 30-second operation bound and task deadline remain authoritative. The launcher persists launch before any fork, saves actual PID/ticks/boot/PID-namespace identities, then waits for DB STARTED before opening rg's independent gate. STOPPED is recorded only after launcher/bwrap/namespace init death and drainer exit. The existing supervisor reconciles crashed workers and their native trees; unreadable proc or an incomplete launch stays unproven. Old fences may append physical stop observations, never commit successful results. Cancellation/deadline errors keep their first cause. No extra retry loop is introduced.

QA-01 correction: a missing `/proc/<pid>` and an empty JDK `ProcessHandle` are not independent exit evidence. The proc-only `NativeProcesses.stopped` API now propagates missing/unreadable observations. The runner and enabled supervisor use the hash-checked launcher in its narrow `--probe-absent <pid>` mode: `kill(pid, 0)` sends no signal and resolves the PID in the caller's inherited PID namespace, independently of proc hidepid. Only ESRCH proves absence; a live PID returns false, EPERM/other failures or a failed/timed-out/interrupted probe remain unproven. Visible zombie/dead state, changed start ticks or changed boot still prove the recorded identity exited. A live matching-ticks process with a different namespace identity is rejected. Signalling requires a positively readable identity plus the captured JDK handle's start-time guard.

This check runs at each missing observation, including recovery and the enabled supervisor's worker/drainer receipt, so changing proc policy after startup does not grant a stop receipt. Unproven runs retain their native slot/SQL state and reservation; UNKNOWN is not replayed. Disabled native recovery conservatively retains prior native runs. Rebuild the C launcher from this source into a new bundle and configure its new digest on both roles; an older launcher cannot supply the new proof and fails the enabled readiness execution. The rg/bwrap pins, isolation, schema and database migration are unchanged. The existing source/licensing restriction still prohibits external distribution or production enablement pending closure.

`present` still requires an exact successful compose_report receipt for version/hash. After editing a composed report, recompose the typed draft reflecting the edit before publication. An old published artifact stays immutable and authorization checks remain in the existing download chain.

## Local verification

Use JDK21, Maven3.9.9, Python3, dedicated local Docker MySQL8.0.46 and the actual bundle. The driver owns temporary MySQL and runs product JVMs as uid65534; it must run under a local test supervisor allowed to create that dedicated fixture. It never contacts a real model/web/search provider. On Windows, run the following inside WSL Ubuntu. Supply JIMU_IT_JAVA when Java21 is not the default; no toolchain is installed into shared system directories.

```text
export JIMU_NATIVE_BUNDLE=/operator/bundle/bundle.json
export JIMU_IT_JAVA=/operator/jdk21/bin/java
./mvnw -B -ntp -Dtest=WorkspaceEditContractTest,NativeSearchContractTest,WorkspaceToolContractTest,ModelProtocolTest,PackageBoundariesTest -Dit.test=WorkspaceIT,P4WorkspaceWorkerIT verify
python3 tools/p4_workspace_verify.py --case S05 --jar target/jimu-0.1.0-SNAPSHOT.jar --test-classes target/test-classes --bundle "$JIMU_NATIVE_BUNDLE" --evidence target/p4-evidence/S05
```

The selectable entry accepts E01–E04, S01–S06, L01/L02, V01, B01/B02 and ALL. Missing resources fail. It records native identities, raw exit/argv, immutable pages, DB receipts, exact final report hashes, assertions and exact temporary-root/container/process cleanup. Preserve failure attempts and the XIAO-42 seven historical failures. Detailed coverage and remaining limits belong to the verification receipt; a passing case name alone does not imply every proposed fault variant ran. The targeted verify includes formatting, Checkstyle, dependency/architecture checks, SpotBugs and real native/database integrations. Do not run/enable/retry/wait for GitHub CI, old full matrices or paid evaluations.

For QA-01 run `python3 tools/p4_proc_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --test-classes target/test-classes --bundle "$JIMU_NATIVE_BUNDLE" --evidence target/qa01-evidence` with the same root fixture driver and `JIMU_IT_JAVA`. It creates private mount/PID namespaces, starts a non-root product JVM with real readiness, then remounts that private proc through hidepid=0/1/2/0 while a same-UID nondumpable process stays alive. It checks both runner and supervisor ledger-write boundaries, EPERM, identity mismatch and real TERM/KILL followed by reap. The ledger spy is explicitly not a real SQL test; use the existing L01/L02 entry for database, UNKNOWN, takeover/fence and drainer regression. Host proc options and exact child/root cleanup are recorded.
