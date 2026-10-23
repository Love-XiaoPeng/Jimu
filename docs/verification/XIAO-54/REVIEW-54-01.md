# REVIEW-54-01: unexecuted read retention cleanup

The independent reviewer found a P1 in source `585a6e347ef7f894eea891a1060bf7786a5084c9` / JAR `7a1734eba759d265f90f0ca6600635569769aa3c40f063c4d3975780c34168fa`. A normal owner cancellation after M2 leaves a v3 read binding PENDING. The V21 `read_relation_shape` constraint incorrectly required PRESENT for that shape, while safe history cleanup sets every read binding to PURGED. MySQL3819 rolled back cleanup and prevented later cleanup work in the same maintenance pass. Independent fixture teardown success did not mean product cleanup succeeded. Original reviewer evidence remains attached to XIAO-54 comment `01a16722-f613-71a2-b9d1-fa32f44183b1`.

The correction permits both PRESENT and PURGED for a relation_version1/PENDING read with NULL source snapshot/digest/bytes. Its existing V19 terminal-shape constraint still requires NULL execution result, receipt, committed attempt/fence/time and failure code. No read is changed to SUCCEEDED/FAILED and no source/receipt is fabricated. Java cleanup, safety checks, metadata fixed point and release logic are unchanged.

V21 is still an unpublished migration in draft PR #19; only private test databases have run it. This patch corrects V21 in place, without touching V1–V20, frozen resources, original architecture evidence, or production databases. It performs no Flyway checksum repair or schema downgrade. The original architecture migration proposal remains historical evidence of the incorrect restriction; the implementation correction is explicit here.

## Differential verification

Build on Windows JDK21, Maven wrapper:

```powershell
.\mvnw.cmd -B -ntp spotless:check package '-Dtest=SessionTraceContractsTest,SessionSearchContractsTest,SessionContractsTest,SessionDeadlineTest,PackageBoundariesTest' spotbugs:check
```

Runtime commands use WSL Ubuntu with `JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java`, private MySQL8.0.46 and `--jar target/jimu-0.1.0-SNAPSHOT.jar`:

```text
python3 tools/session_trace_verify.py --evidence target/xiao54/review54-pending-cleanup-final --case pending-cleanup
python3 tools/session_trace_verify.py --evidence target/xiao54/review54-observations --case observations
python3 tools/session_trace_migration_verify.py --old-jar ../jimu-v20.jar --evidence target/xiao54/review54-migration
```

The pending-cleanup case creates actual M2-bound reads and ends them through owner cancellation, deadline expiry, and a preceding trace slot's deterministic failure. It also holds a live writer and injects a private UNKNOWN reservation to verify both cleanup exclusions. A later successful read task is eligible behind the formerly failing candidates. Two actual control processes run scheduled cleanup with the trace flag off. Checks cover:

- Real pending bindings before/after termination; unexecuted tombstones preserve NULL sources/digests/bytes/receipts and PENDING outcome, with no native read event.
- All body copies sum to zero after cleanup; independently recomputed metadata equals stored metadata, held bytes and settled storage reservation actual.
- All four eligible histories purge, including the later successful read; tenant release equals four reservations minus exact retained metadata, one deletion event each.
- Repeated dual cleanup leaves receipts and held quota unchanged; UNKNOWN history retains its full reservation; an active M2 writer's content remains present.
- Source-capture/trace, actual SSE reconnect and bounded PK observations still work. V20→21 preserves old bytes/checksums and old binaries reject21 before readiness.

The correction delivery manifest supplies the new source/tree/JAR, actual exit codes, packaged-entry checks, protected-baseline comparison, raw protocol/ledger/database evidence and private resource cleanup receipts. Earlier 188-test/full T01–T11 evidence remains attached to the earlier JAR and is not relabeled as a rerun on this patch. This pass uses 73 targeted tests and differential runtime cases. Seven-day expiry is a private timestamp fixture. No exhaustive capacity/authorization/DNS/TLS matrix or purge-transaction process kill was added; independent reviewer/QA acceptance is still required. No GitHub CI was run or awaited.

The first new live-writer probe compared body byte counts after issuing cancellation. Terminalization legitimately changes those bodies, so that assertion failed while history still remained PRESENT with the full reservation and NULL cleanup receipt. The corrected probe observes an actual nonquiescent M2 writer before cancellation and checks content/held quota/receipt directly. This fixture failure and its successful private-resource teardown are retained separately; neither is recorded as a successful product cleanup run.

Scope remains only `read-receipt-sources-v1`, with `replacement_policy=unsupported`; surface/replacement chains, inferred model causality and old-history backfill remain undelivered.
