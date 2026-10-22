# P4 implementation and verification handoff

PR: https://github.com/Love-XiaoPeng/Jimu/pull/10, base master. The final issue attachment carries the exact delivery SHA/tree, JAR/native hashes, commands, exits, assertions, database receipts and cleanup. `checkpoint-90m.md` is explicitly historical progress, not the final outcome.

The implementation adds atomic literal edit in the existing POSIX workspace transaction, immutable native glob/grep results and cursor pages, and V11 search lifecycle/storage receipts in the existing ledger and worker supervisor. The source contract, packaged schemas and runtime configuration are documented in `docs/implementation-p4-workspace.md`, ADR-0005 and `docs/implementation-p4-runtime.md`. Large literal expansions are byte-counted before allocation; a rejected edit preserves file version/content.

## Reproduce the necessary local checks

Use JDK21/Maven3.9.9, Python3, dedicated local Docker MySQL8.0.46, native POSIX storage, the reviewed offline bundle and a test supervisor permitted to set uid65534. No resource is silently skipped. Product JVMs and C/bwrap/rg run as uid65534. The controlled model and TLS provider are local fixtures; this is no claim of a new real-model evaluation.

```text
export JIMU_IT_JAVA=/operator/jdk21/bin/java
export JIMU_NATIVE_BUNDLE=/operator/bundle/bundle.json
./mvnw -B -ntp verify -Dtest=WorkspaceEditContractTest,NativeSearchContractTest,WorkspaceToolContractTest,ModelProtocolTest,PackageBoundariesTest,WorkerLoopTest,LocalWorkerSupervisorTest -Dit.test=WorkspaceIT,P4WorkspaceWorkerIT
```

`tools/p4_workspace_verify.py` accepts ALL or individual E01–E04/S01–S06/L01–L02/V01/B01/B02. S06 includes actual stdout flood plus database quota/TTL cases. Fixture tenants have explicit token/storage ceilings sufficient to retain crash-case reservations; production defaults are unchanged. The 601-row cumulative model-history fixture raises only its private task token ceiling. Twenty actual result roots are bounded by the existing 20-tool ceiling; this case does not separately bypass that ceiling to invoke the SQL root guard a 21st time. The cumulative-byte case exercises the actual 8MiB reserve/settle admission guard. TTL evidence verifies physical removal before refunds.

V01 compiles the exact master `f6a97168281990fd036cc18bf0ddef8045d90a6d` store from Git, runs its format2/3 checks as nobody and compares format3 bytes before/after refusal. Git history and the JDK compiler are required for this case. Its per-child x86_64 seccomp fixture rejects namespace clone/unshare while leaving the host policy unchanged. It also covers missing/wrong bundle and disabled catalog/admission. Packaged manifest/schema errors and duplicate definitions are covered by the existing strict loaders and contract/architecture checks.

L01 checks cancellation at snapshot, export, gate, result-save and committed-result boundaries, and actual deadline expiry. L02 kills worker, launcher and bwrap, including the durable-started/pre-SQL-identity crash window; it checks UNKNOWN retention on an expired saved result, and a separate safe recovery that really advances the fence before rejecting old admission. PID-reuse identity and symlink/FD negative cases run against actual proc/native processes. Existing supervisor unit tests cover unreadable/missing proc conservatism. Not every possible kernel/proc failure permutation or timing interleaving is claimed to have been injected.

## Preserved failures and practical limits

The evidence attachment retains attempts that found migration-gate omission, nanosecond timestamp rounding, stdout flood, cleanup stream closure, fixture token/storage exhaustion, fixture legacy IDs/read oracle, an incomplete namespace-denial fixture, and the correct refusal to replay UNKNOWN on lease expiry. A premature JAR copy during packaging produced a corrupt-test-JAR failure and is retained. Subsequent attempts use completed JARs. Earlier source/toolchain download failures and the vendor/npm binary mismatch are recorded, not relabelled as success. XIAO-42's seven historical failures and the eight frozen baselines remain unchanged.

The bundle recipe, conservative notices/SBOM and hashed source-input inventory are in `workers/search`. The vendor release digest differs from the pinned npm binary. The inventory conservatively covers the known source graph; exact npm linked-feature/vendor rebuild attestation is unavailable. Do not claim bit-identical provenance or an all-MIT closure. Keep corresponding bubblewrap source/Ubuntu patches and notices with redistribution. The optional native assembly is disabled by default, requires non-root Linux x86_64 namespaces and has no host-execution fallback.

Independent code review and QA on the delivered SHA, product acceptance and any later merge remain the product manager's next actions. This backend task does not merge or deploy. No GitHub CI was run, enabled, retried or awaited. No production data, real keys, paid evaluation or old P2/P3 full matrix was accessed or replayed.
