# History metadata shape check

The production change applies the accepted `history-metadata-shape.patch` to
`SessionHistorySizedMetadataBatch.read` on parent
`488ff802cc6a916b909d73c0c11e024edc7363ee`. Patch SHA256:
`9ef408c6fe0c182b979b9d98bd0e77e665914ea593e0f973fcb810ae9de7c5dc`.
The source decision is XIAO-127 report `01a18b5f-28bc-71f5-a464-0dd4c92fc916`,
bound to frozen `pm-r24-input-verification.json` (144051 bytes, SHA256
`9eaaebc09f4cc521fabd2b8256f38a1c64dd87011c75ea9103d3316867a8dbdb`).

All fourteen dynamic reads and their existing type, bound and private marker
checks remain. For the private ordinary MyBatis result Map, thirteen required
non-null columns establish thirteen distinct keys. A fresh optional
`cleanup_receipt` presence read and exact Map size reject extra keys without
iterating and hashing the same fixed keys again. This reasoning does not extend
to arbitrary inconsistent custom Maps. Composite malformed rows now encounter
column validation before the extra-key check; first failure poisoning, refusal,
rollback and cleanup remain mandatory.

Each row prepays 45 operations: 38 for the optional lookup and 7 for selection,
size, primitive local and branching. The old shape walk cost 482 with all keys,
442 without cleanup. The accepted fixture load changes from 1596 to 1159; this
local difference does not establish a complete second admission budget.
Action and owner details are in `history-metadata-shape-accounting-r24.csv` and
`history-metadata-shape-ownership-r24.csv`.

No new object, field, retained alias or allocation discount is introduced.
Bootstrap retains 267596+8192+64 before construction, then hands the original
267596+8192 to the batch. Close drops rows before refund. SQL, sources, charset,
dynamic identity/authority, independent raw validation, locks, live/fence and
the statement identity/resume cleanup fix remain unchanged.

`SessionHistoryMetadataShapeTest` integrates the report's six focused methods
using existing scripted JDBC/transaction fixtures. It covers cleanup forms,
29 malformed shapes, fresh values, full-load and 45-operation boundaries, and
RuntimeException/Error first failure with cleanup. Fixture classes are reused;
their old test matrix is not part of this directed execution.

The delivery report records exact build and business results. The two existing
bounded second/response scenarios do not constitute full A12, nine-node, M2,
new fence, real network or client ACK acceptance. If second admission still
refuses, stop and return the actual executed phase/cost evidence to the original
PM for a decision on simplifying the existing metadata/accounting path.
