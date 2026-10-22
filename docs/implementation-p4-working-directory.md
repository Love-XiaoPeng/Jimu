# Task logical working directory

XIAO-51 implements the accepted [ADR-0010 and contract package](verification/XIAO-51/architecture/implementation-contract.md). The architecture package and its manifest remain byte preserved. This document describes the implementation; acceptance evidence is recorded separately.

Enable `jimu.working-directory.enabled=true` on control and workers and provision a private POSIX `jimu.workspace-root` (mode 0700). The default is disabled. Use an explicit local, bounded model profile with `paid=false`; no business/evaluation scope, resources, images, native search or paid dispatch is allowed. Accept exactly `working_directory`, `read`, `write`, `edit` as the task capabilities. Their existing file schemas and pins are unchanged.

`working_directory {}` returns `{"cwd":"/"}` initially. `{"cd":"a"}` enters `/a` only when a file below `a/` already exists. There are no empty-directory entities. A successful `write a/x` can establish `a`; a missing directory or a file used as a directory fails without changing cwd. Absolute paths are logical paths inside this task's namespace. Relative paths, `.` and `..` resolve at the actual invocation slot. Unicode is preserved; backslashes, drive prefixes, duplicate separators and traversal above root are rejected. No host cwd changes.

The persisted original response array fixes execution order. Each invocation freezes its original base cwd/revision and canonical file key after its predecessors succeed. Directory result, source attempt/fence, tool settlement and changed event commit together. READ and no-op SET consume one tool but do not change revision or source. Replaying a successful call returns its original result even after later directory changes. Expired leases require proof that all historical writers have stopped or are quiescent. Ordinary file writes retain their existing UNKNOWN rules.

Owner observation uses `GET /api/tasks/{taskId}/working-directory`, with bearer authorization, no body or query, and `Cache-Control: no-store`. It returns version, taskId, asOfSeq, revision, state, cwd, source and retainUntil. Existing tasks without a directory binding return ABSENT. A joined database snapshot binds the watermark to its provenance. Current actor, installation, epoch and pin authorization still apply to terminal reads; disabling the feature preserves authorized historical reads.

After GET, subscribe to the existing task SSE endpoint using `Last-Event-ID: taskId:asOfSeq`. `working_directory.changed` and `working_directory.content_deleted` are invalidations with no path bodies. On invalidation, disconnect, 25-second expiry or expired cursor, GET again. GET, idle polling and reconnects create no model/tool/revision facts.

Each directory task reserves 8 MiB separately from ordinary storage; tenant default is 64 MiB. Bounds include 2 MiB database bodies, 64 KiB fixed metadata, 2 MiB filesystem envelope, 4 MiB scoped filesystem copies, eight temporary parts, 32 KiB per file, 256 KiB current file content, eight model dispatches, twenty tools, four calls per response, eight attempts, 256 events, 8192 summary bytes and 180 seconds. New filesystem and directory transaction locks are bounded by two seconds and cancellation/deadline checks.

After seven days, only safe terminal tasks without unknown effects, unsettled usage or active historical writers can purge. PURGING first removes database body copies while preserving original digests and source metadata. A persisted secure inventory then deletes exact scoped state/temporary files. PURGED releases the reservation and emits the deletion event exactly once. Failure retains PURGING and quota for retry. Missing READY state never initializes a replacement workspace.

Only V18 is added. V1–V17, old task/tool/model/checkpoint versions and workspace formats 2/3 remain unchanged. Stop old workers before migration. Old V17 binaries reject schema 18. Roll back by deploying the V18-compatible artifact with the flag disabled; do not down-migrate or release unknown reservations.

Run targeted contracts and architecture gates:

```powershell
.\mvnw.cmd -o -B -ntp spotless:apply
.\mvnw.cmd -o -B -ntp verify '-Dtest=WorkingDirectoryContractTest,WorkspaceToolContractTest,PackageBoundariesTest,TodoContractTest,UserQuestionContractTest,McpContractTest' '-Dit.test=WorkingDirectoryIT'
```

The IT requires Docker, Python, Linux POSIX storage (Windows via WSL), and an exact old master JAR in `JIMU_V17_JAR` for D11. Each case is also runnable as `python3 -B -u tools/working_directory_verify.py --jar <current.jar> --case D01 --evidence <folder>`; D11 additionally requires `--v17-jar <old.jar>`. Missing resources and unknown cases fail. `-Djimu.directory.cases=D01,D02` selects a disclosed subset, not full acceptance.

D01 covers the real double-worker file loop; D02 interleaved call/stream order; D03 crash boundaries; D04 source/fence/transaction replay; D05 path/JSON rejection; D06 observation/CAS and POSIX boundaries; D07 authorization/cancel/deadline; D08 GET/SSE; D09 bounded execution/storage; D10 retention retry; D11 exact old upgrade; D12 targeted workspace/MCP/profile regressions. The architecture's full subcase list is the acceptance authority: a case's passing driver is not evidence for an unimplemented subcase. Record actual commands, exit codes and omissions with the final source SHA/tree/JAR digest.

Do not run or wait for GitHub CI. Preserve the existing SourceIntegrityTest ADR README length mismatch and its original failure; the targeted command does not claim the default complete matrix passed. Independent review, QA and product acceptance remain required.
