# XIAO-52 architecture handoff

This package fixes the implementation contract for the first bounded `session_event_read` slice. Product source baseline: `270a5d15611f5d7e59bbbd1d96c94d07567fc858`, tree `a64d3ac4593de748c50df15ebb53f71d4bdcc0c2`. No product runtime change or database migration is performed by this package.

Read `implementation-contract.md` and `../../../adr/0011-authorized-session-event-read.md` first. `contracts/` contains the authoritative new manifest, input/output/event/context/owner/binding JSON Schemas, byte-pinned catalog and examples. `migration-contract.sql` is the V19 implementation design. It must be promoted and tested by the backend, not executed as an architecture probe.

The current task is the only session the model may read. Native history seq starts at zero and is distinct from the existing SSE cursor. The native log stores complete public event bodies at their fact commits; current-step visibility is frozen before M1. The capture redaction, restricted event vocabulary, structured output, truncated neighbor summaries, result receipts and absence of cross-task access are explicit upstream compatibility differences. Existing SSE status envelopes do not supply the requested full event body.

`examples/trace.json` is an illustrative two-step trace, not an observed product execution. JSON example files are readable object representations. `first-read-output.wire.json` contains the exact compact UTF-8 bytes whose SHA256 and size appear in `read-receipt.json`; the receipt never embeds that output.

Reproduction from repository root requires Node 22+, Java 21, PowerShell 7 and the existing Maven runtime dependency cache:

```powershell
node docs/verification/XIAO-52/architecture/fetch-upstream.cjs
node docs/verification/XIAO-52/architecture/make-contracts.cjs
& ./docs/verification/XIAO-52/architecture/verify.ps1
node docs/verification/XIAO-52/architecture/capture-evidence.cjs
node docs/verification/XIAO-52/architecture/package.cjs --verify
```

Run the last command without `--verify` only when intentionally refreshing the package manifest after changes. Node/PowerShell are evidence tooling, not new Jimu runtime dependencies. Fixed upstream sources, Git blobs, original MIT license and source line map are under `evidence/`; see `THIRD_PARTY_NOTICES.md`. `VERIFICATION.md` states exactly what ran and what remains unexecuted.

The product manager checks current occupancy and assigns the original backend once. Backend continues the linked draft PR/head, implements this contract without altering frozen baselines, and supplies a buildable first slice within the two-hour checkpoint. H01–H12 require the same source/JAR and independent review, QA and product acceptance. This architecture handoff does not close XIAO-52.
