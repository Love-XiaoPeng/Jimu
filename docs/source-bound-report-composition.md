# Source-bound report composition v1

New BUSINESS reports use `web_fetch/read/compose_report/present`. An operator must explicitly
install `compose_report@0.1.0` with the packaged output schema SHA256, using the existing
installation/epoch authority. This change installs nothing in a persistent environment.
Combining `compose_report` with free `write`, or admitting a new BUSINESS report with the old
free-write capability set, is refused. Existing idempotent submissions and old task snapshots
retain their definitions, instruction, HTTP body and accounting. Frozen evaluations retain
their original tools. No migration, run replacement or fee/budget change is required.
The renderer v1 constant is pinned by the new output schema digest. Before rolling back to a
binary without this tool, stop and drain new composition tasks; retain their pins, receipts
and UNKNOWN occupancy rather than converting them to free-write tasks.

The input is `{file_path,draft}`. The packaged schema is authoritative. Version 1 has a plain
subject, 1–8 summary segments, 1–8 conclusion segments, and up to four limitations. Each
segment binds an exact saved `sourceRef` and an exact body quote. Text segments reject HTTP(S)
URLs. Endpoint segments preserve an explicit `http`/`https` protocol and host/path identifier;
their full endpoint must be a complete token in the quoted saved source, including the
surrounding source characters. Credentials, query/fragment, whitespace and active markup
are unsupported. All invalid fields reject the complete draft; no sentence is deleted or
repaired. Raw calls exceeding 65,535 UTF-8 bytes are refused at the provider boundary before
planning a MySQL TEXT snapshot, preserving independently verified usage. Duplicate keys,
trailing JSON values and schema violations are refused before executable tool admission;
the response plan remains PENDING without output or TOOL quota. Diagnostics contain stable
codes, not source text.

`ReportComposer` shares the original `ReportPolicy` usable-source predicate: confirmed,
same-task/tenant, pinned public `web_fetch` output with a verified digest, successful status,
untruncated, nonempty useful body, excluding wrapper headers and login/error/snippet pages.
Ambiguous versions of the same source URL are refused. Source instructions are untrusted.
The host escapes free text and generates all six sections, citations and the reserved task
report time. Its candidate must pass the existing publication gate, and its complete HTTP(S)
URL set must equal the used fetched-source set, before any file write. Invalid sources and
valid unused sources are disclosed separately. Exact quotations establish traceability;
they do not establish semantic entailment of a model's summary.

Read the target first, even when missing. Writing uses the existing bound FilePort operation,
read observation, CAS, cancellation and fence. The receipt records version 1, renderer
`source-bound-report-v1`, `draftSha256`, host `reportTime`, source invocation/output digest,
quote UTF-8 body offset/length/hash and the actual written path/version/SHA256/size.
`draftSha256` is SHA256 of UTF-8 `Payloads.json(typed Draft)`: alphabetically sorted record
properties, explicit null fields, array order preserved; it is not the incoming JSON byte hash.
The existing invocation separately retains the original complete call bytes and their digest.

Only tasks pinned to the new tool require composition receipts. Before staging an upload,
`present` searches same-task/tenant confirmed successful invocations, verifies their input,
output and tool pin digests, reconstructs the deterministic receipt and compares the exact
file version/hash/size. Missing, forged, foreign or stale receipts refuse publication. The
original gate still validates the actual file. No model-supplied receipt can authorize it.

Crash probes C0/C1/C2 bracket validation-before-write, write-before-success, and committed
success. C0/C1 are unconfirmed and retain UNKNOWN rather than inferring success from a file;
C2 restores the confirmed output without writing again. Existing P0/P1/P2 upload/publication
semantics remain. STOPPED, HELD_UNKNOWN, old costs, scope and B admission are unaffected.

`ReportComposerTest` covers pure validation and receipts; `ComposeReportIT` runs ordinary/SSE
composition, real isolated MySQL/worker/FilePort/publication/download, invalid drafts,
receipt bypasses and crash recovery with two workers. `BusinessAdmissionIT` retains cumulative
budget, authorization, known historical settlement, UNKNOWN/STOPPED and exact snapshot
regressions. `ReportWorkerIT` continues exercising old read/write/present and citation refusal.
All providers and TLS pages in these tests are local substitutes. Run the required full
`./mvnw -B -ntp verify`; no real model compliance, pricing, deployment or factual correctness
is claimed by isolated results. Any later installation/deployment/business task needs its
existing operational authorization and separate acceptance.
