# Session lineage projection checkpoint

This is a partial implementation checkpoint after 1869fd9, not a stable session_trace candidate.
The limits remain 256 cumulative ResultSets, two seconds, 1 MiB proof/scratch and 65536 operations.
V23, stored graph codecs, output contracts and committed fences are unchanged.

## Implemented ownership and work

- Relationship lookup, expected task/parent/category, active step/response, caller progress and
  conflict comparisons now charge actual comparisons separately from lookup/key work. Invalidation
  reserves its removal plan and all Map update work before changing either relation Map. Identity
  reference counts release shared strings once; Map capacity remains at its high-water reservation.
- The test-only typed writer uses the native schema date-time validator, including leap seconds.
  An AutoCloseable output owns Result/raw/digest through receiver handoff; generator workspace is
  additional. Closing clears references and denies later reads. It is not wired into production.
- Fifteen original Mapper metadata projections stream one row through the same MyBatis/session
  transaction. Their descriptor contains the original SELECT columns, DDL types and nullable bounds.
  Two row workspaces are reserved before cursor creation, separately from ResultSet retention.
  Facades expire on advance/close. Finalization has a separate 4096-byte temporary owner. Lazy
  materialization, reservation and cursor close failures poison the transaction even when caught.

The fifteen tables are SESSION_PUBLIC_EVENT, INVOCATION_SESSION_READ, INVOCATION_SESSION_SEARCH,
INVOCATION_SESSION_TRACE, TASK_SESSION_SCOPE, TASK_SESSION_SCOPE_TARGET, STEP_SESSION_SCOPE,
INVOCATION_SESSION_SCOPE_QUERY, TASK_ATTEMPT, STEP, INVOCATION, MODEL_DISPATCH, RESERVATION, EVENT,
and INVOCATION_SESSION_LINEAGE_TRACE. Only the existing business maxima are used: 128 public
events, eight scope targets and one scope row. Other projections have no invented row cutoff.
UTF-8, NULL, decimal, UTC and Boolean scalar accounting retain the original metadata formula.

## Verification and first genuine failure

Targeted tests cover relationship alias/wrong-expectation and invalidation N-1/N/N+1 boundaries;
typed native date-time acceptance and the 5195-byte schema maximum shape; projection columns,
types, scalar edge cases, row expiry and failure release. Package architecture and SpotBugs are
included in the bounded local verify; the old full integration matrix and GitHub CI are skipped.

The private MySQL scenario migrates the actual schema, submits the root through genuine lineage
admission, and legally claims, finishes and quiesces it. All eighteen real Mapper projections
match the independent INFORMATION_SCHEMA oracle (8758 bytes for this fixture). Each of the fifteen
streamed projections exercises consumer/reservation rollback over an independent 22-table
snapshot; a caught reservation failure and the all-projection beforeCommit failure are separate
cases. The attached final execution logs are authoritative for their observed results.

The initial execution reached only one real node. The second genuine admission failed with
SESSION_HISTORY_LIMIT at 67491 operations, in accepted -> view -> historyRetained -> history
loading; it did not reach the nine-node helper. The follow-up must examine the repeated history
load after MapperCommitFence.admission using only the current transaction proof, while preserving
live/tail authorization. No limits may be raised to pass this fixture.

## Explicit remaining scope

TASK metadata still follows the legacy path: its TEXT/MEDIUMTEXT fields need a bounded character
stream TypeHandler and exact scalar accounting before it can join the new streaming path.
TASK_SESSION_LINEAGE and SESSION_HISTORY retain their earlier specialized batches.

The nine-node same-graph three-output helper is unexecuted; its baseline strings/comparisons still
need explicit ownership/metering before use as acceptance evidence. Genuine M2 slots/normalized
requests/receipts/usage, public response ownership and current-authority negative cases, ACK and
new-fence recovery are incomplete. A08/A10-A14/A15 and all other original acceptance IDs retain
their existing meaning and unfinished scope. No A03, deployment, merge or complete M2 pass is
claimed by this checkpoint.
