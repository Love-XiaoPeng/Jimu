# Admission SourceFact handoff to lineage Fact

This implements the 2026-10-31 architecture decision on baseline
`8a66623a68891888069dc2d4ce3dd37f85833128`. It is a production slice on draft PR 24,
not acceptance of the complete second-admission query or its later independent
eventHistory query. Their remaining costs are unknown on this revision.

## Data flow and fallback

`SessionScopeEligibilityServiceImpl.sourceFacts(..., true)` remains the only
production producer of admission staging. The lineage adapter borrows that exact
SourceFact only from the active original Work/query/physical connection, with
the original staging caller, full tenant/source key and all staged task locks.
Caller-self, absent/invalidated staging, non-admission and unsupported versions
choose the original complete Fact reader before new SQL. No new proof cache,
codec, schema resource or permission token is introduced.

The named `sourceFactSizes` query bounds the new request before materialization.
`sourceFact` joins the original task, scope and history tables and selects 24
columns, without `authority_snapshot`. Connector metadata proves each raw
column's original table/name, label, MySQL type and textual utf8mb4/UTF-8 encoding.
It also verifies the real Work and physical ResultSet connection. Ordinary
ResultSet metering still charges all returned values; these sizes do not grant
any privileged length capability or exemption from encoding/hash.

Twenty-three binding fields compare against the original SourceFact: task
tenant/id/creation/request digest/capabilities/owner/tools/epoch/state, scope
digest/bytes/content/record and authority versions/owner/tools/binding/count/
creation/retention, and history binding/version/retention. Request is a fresh
raw view even if a provider reuses String identity. It gets strict UTF-8,
independent length and SHA-256, original JSON/field parsing, history IDs,
capabilities and parent membership checks. Authority structure comes from the
still-owned original raw, never from digest-based trust of another returned raw.
All standalone genesis/graph reads and original graph validation remain intact.

Once the new size SQL starts, mismatch, budget, SQL and Error failures poison
the Work. There is no catch-and-success fallback. SQL causes remain attached;
Error is thrown unchanged and retained as the cause of the Work's preallocated
failure slot, preventing a caught Error from allowing commit.

## Added action charges

All charges precede the corresponding work. No original unit or limit changed.

| Action | Additional prepayment |
| --- | --- |
| Adapter route | 4 operations, including fallback |
| Borrow discovery | 12 + tenant/id lengths; existing metadataOwner discovery and physical binding cost; then 4 + id length for staging lookup |
| Candidate qualification | 10 + tenant/id/capabilities lengths; existing full projection-lock scan; 12 for borrow/cleanup handoff |
| Ordinary owned ResultSet routing | 1 per ResultSet; only an active borrow enters new metadata validation |
| New metadata validation | 8 + statement length, actual metadataOwner cost, 5 shape checks; size shape/type 24; raw column 18 + table/name/label lengths + 12 per column |
| Size, owner and container handoff | 18 before allocation and cleanup; original SQL/ResultSet work remains fully metered |
| Scalar equality | 6 + key length + expected string length, or one scalar unit, for each of the 23 comparisons |
| New request | Original encode/hash/parse primitives by raw length; 8 + digest length for the independent length/digest comparison |
| Source/JSON conversion | 12 + 8 per authority target; 6 + source ID length per declared ID; 4 + capability text length per capability; 10 + capabilities/id/tenant lengths for final comparisons |
| Parent and Fact return | 2 + parent length times source count when parent is present; 37 for record/list/parent/27-character timestamp handoff |
| Borrow lifetime guards | 1 before dependency mutation and 1 before graph publication; no writes or publication may execute while the synchronous alias is live |

The removed work is the actual redundant authority column read/materialization,
UTF-8 conversion, and full raw equality used to obtain its already-validated
structure. Added SQL and guards have real cost. No net saving for an entire
business query is inferred from a local fixture, and no unknown tail is priced
by subtracting a few operations from the previous rejection.

## Continuous ownership

Original staging retains `3 * (scope.bytes + sum(target.bytes)) + 2048` per source
unchanged. The Work's existing token is only a scratch key for an additional
512-byte reservation before the borrow field/stack aliases are installed.
The adapter clears its SourceFact alias before releasing this reservation.
No new wrapper, Map, token or per-source cache is allocated.

Before size SQL, the adapter reserves an additional 8192 bytes. After the bounded
size (at most 16384), it retains `8192 + 24 * requestBytes` for this source,
additive across all borrowed Facts. This conservatively holds provider/Map/raw
text, JSON tree, lists, immutable Fact and their overlap. The encoding buffer
(`128 + 3 * UTF16 length`) and each RawView's existing owner are separately
additive. The source-Fact reservation remains through the outer `withProof`
callback, including graph/Stored validation; helper return does not refund it.
Only after callback consumers have returned and rawViews have been cleared is
that reservation released. The existing scratch/graph/staging/Scope owners are
not reduced by the missing SQL authority column.

Dependency writes still use source/target/actor/installation/unknown invalidation
before SQL. Outside the borrow, the original caller progress/scalar classification
is unchanged. A write or graph publication during a live borrow poisons before
any staging removal/refund. On success the borrow ends synchronously before
scope/graph publication; graph ownership exists before staging is cleared.
Completion clears the borrow before the original owners. Runtime/Error exits
use the same finally path. Foreign query, suspension/REQUIRES_NEW, changed
connection/holder, missing locks, completion or first failure cannot borrow.

## Verification scope

`SessionLineageSourceBorrowTest` covers the new lifetime/identity/invalidation,
operation/scratch rejection and Runtime/Error boundaries using the existing
real Spring transaction-manager fixture. `SessionLineageSourceSqlScenario`
uses a new private MySQL instance and actual MyBatis/Connector metadata; its
source staging comes from the production eligibility service. It tests the new
SQL and original fallback, fresh mismatches and first SQL/Error failures.
Nine distinct borrow/owner JUnit methods passed across separate batches; the
initial operation-limit fixture failure and its single-method fix are retained.
The initial real-SQL batch passed eleven cases on JAR SHA-256
`9c47b60331504b944f87eb2b3272c5a671219aa33d299a4ef666bd65d1de2f49`,
before two final accounting charges (72 request-digest operations and 27 UTC
handoff operations). Final compilation, formatting, Checkstyle and SpotBugs
passed. These results do not establish complete graph/publication handoff.

The graph/publication scenario was attempted twice and rejected at the unchanged
absolute two-second deadline. Moving fixture codec/seed preparation outside the
measured Work did not resolve the rejection. The final production JAR SHA-256 is
`de2082b21413ec9a65e673ea4090c5ab96f0c9b6d83292d25c1c97ebbadcfb38`.
Its final attempt executed both new source queries and stopped in `lineage.graph`
at 15 reads / 3813 bytes / 16179 operations, with approximately 13 ms past the
deadline; the transaction rolled back in BUSINESS. Standalone Stored validation
and publication assertions were not reached. The three metadata fault cases
and seventeen additional scalar mutations following graph in the tail batch
were not executed. All three private containers were removed. No additional
production optimization or successful-test rerun followed this rejection.

The fixture's deliberate rollbacks and synthetic publication data are not
business acceptance. Complete second-admission commit, its later independent
eventHistory proof, and the original A12/M2/fence/network acceptance gates remain
unverified on this revision.

All original checked-history tools comparisons, source safety/retention,
currentState/live/terminalAccess/expiry/fence/seal/beforeCommit checks remain in
their original paths. No old success matrix or business probe is rerun. The
final handoff manifest records exact commands, failures, separate successful
test batches, JAR/class hashes and private-fixture cleanup. CI is not run.
