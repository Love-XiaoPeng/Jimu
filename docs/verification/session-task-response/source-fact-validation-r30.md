# SourceFact validation continuation

Production remains exactly r29 commit `1798db17136fe0850769b93bef91236535ff0402`.
The unchanged complete JAR is 45,460,153 bytes, SHA-256
`de2082b21413ec9a65e673ea4090c5ab96f0c9b6d83292d25c1c97ebbadcfb38`.
This continuation changes only the SQL verification fixture and this record.
It closes the twenty previously unexecuted fault scenarios and the scoped graph
handoff check. The genuine second admission still rejects on operations; it does
not commit, so its later independent eventHistory proof remains unexecuted.

## Clock and diagnosed fixture problem

The two preserved r29 graph failures took about 6 ms and 87 ms inside
`lineage.graph`. Approximately 1.9–2.0 seconds had already elapsed before that
stage. Their old logs cannot attribute that prefix to individual calls.

The absolute deadline is passed to `JdbcLedger.queryView` before its constructor,
then retained by `SessionQueryContext` and only shortened by transaction entry.
It includes view construction, connection acquisition/setup, locks, production
source eligibility, Fact/Stored verification and the original commit checks.
The reflective method lookup in the fixture precedes that deadline; seeding and
codec preparation also precede it. None of those facts permits restarting the
clock after constructing a production view.

The newly authorized negative batch recorded inclusive phase times and a JFR
profile on the original `/mnt/c` classpath:

| Phase | First case, microseconds | Later median, microseconds |
| --- | ---: | ---: |
| Production queryView construction | 668268 | 637353 |
| Transaction entry through callback | 185715 | 34615 |
| Two original locks | 318713 | 11687 |
| Production sourceFacts | 564594 | 67344 |

View construction ranged from 556587 to 749353 microseconds. Within production
`JdbcLedger.queryView` stacks, native samples repeatedly stopped at filesystem
attributes (366 samples) and packaged JAR positional reads (144 samples), through
the Boot classloader and packaged contract-resource loading. Reflection before
the clock is counted separately. Sampling is evidence of the dominant location,
not an exact allocation of wall time or a profile of the two historical failures.
The first case also pays cold classloading/mapping overhead in locks and source
qualification. MySQL performance_schema records independently show that the
SQL server did not consume this prefix; the slowest SELECT in the entire batch,
including Flyway/setup queries, took 9246 microseconds. SQL server timers do not
include client mapping, classloading or network time.

The specific fixture correction copied the identical complete JAR and the two
identical fixture classes to a private WSL `/tmp` classpath, verifying all hashes
before launch. This removes Windows-mounted classpath I/O from the Linux JVM's
file-access path. It does not move production construction, SQL, checks or raw
validation outside the measured operation; no prevalidated business result,
runtime schema cache, warmed successful query or new permission proof is reused.
The absolute two seconds, fee units, owners and 65536 limit remain unchanged.

## Previously unexecuted cases

`negatives` selects exactly three metadata faults and seventeen scalar faults,
without graph or the eleven earlier successful scenarios. Each case owns a fresh
query/Work/transaction. A failed assertion is retained and does not stop the
remaining cases; a final nonzero result reports any failed case.

All twenty passed once on the exact r29 production JAR. Metadata substitutions
run as actual prepared SQL through MyBatis and Connector: changed source column,
latin1 expression and extra column. The latin1 expression rejects at original
column provenance first; it is not claimed as isolated charset-guard coverage.
The original seventeen scalar mutations are injected one field at a time into
the actual SQL result after complete Connector metering/metadata validation.
They test the adapter comparison boundary, not persisted corrupt database rows
or hand-built rows standing in for SQL. Before/after value fingerprints and the
actual result's bytes/hash are recorded separately.

Every case reaches the new SQL path, records its expected metadata or corruption
first error, poisons the Work, avoids the complete-reader fallback and releases
owners to 65536. Case receipts preserve actual SQL bytes/hash, returned-field
fingerprints, calls, first error, resources and timeline. Full stacks and server
statement durations are retained; no case is repeated to fill a log gap.

## One corrected graph check and genuine business continuation

One `graph-once` execution with the local WSL classpath passed genuine source
staging, fresh source Fact, independently read standalone Stored, complete graph
validation and source disconnection before graph publication. Its deliberately
rolled-back fixture took 701903 microseconds from its observation origin,
17 reads / 6516 bytes / 25228 operations; owners released to 65536. This remains
a synthetic one-node graph check, not acceptance of a genuine second admission.

The existing `SessionJointProjectionScenario` then ran once with
`genuine-two-admission-only-witness-second-event-history-observe`, preserving its
actual first admission, legal finish and second admission in separate original
queries. Its existing runner and operation observer were reused, with only the
evidence prefix changed; its classpath was already on private WSL storage.

| Genuine phase | Operations / reads / bytes | Result |
| --- | --- | --- |
| First admission | 54803 / 84 / 22369 | COMMITTED |
| History witness, final same-query total | 36017 / 39 / 14906 | COMMITTED with live/fence |
| Legal finish | 54339 / 76 / 23300 | COMMITTED, SUCCEEDED and quiescent |
| Second admission | 65367 returned, next 304 refused / 61 / 19245 | BUSINESS rollback |

The second query requests 65671 operations and rejects before the new 304-unit
`encode` action executes. The stack is `LineageRawView:28` →
`HistoryPolicyCodec.verify:89` → `acceptedEventHistory` → accepted event append.
It has approximately 1.39 seconds remaining after rollback, scratch peak 324915,
live 65536, and all twenty-two tables equal to their pre-request snapshot.
The wrapper's exit zero means evidence capture and cleanup succeeded; business
acceptance failed. Second commit, remaining view/account/current live/fence and
the later independent second-eventHistory query have not completed. The next
304, or the excess 135, is not the complete budget gap.

The full graph group completed in both comparable genuine second-admission
probes: r28 `4 reads / 5659 bytes / 25833 operations`; r29
`5 reads / 5792 bytes / 28694 operations`. Removing the authority raw did not
reduce this group's total cost: the extra size SELECT, metadata validation and
fresh bindings have real cost. This comparison does not price the unexecuted
tail or authorize deleting any check.

## Delivery boundary and next action

No production optimization follows the new business rejection. The executable
next step is an architecture decision in original XIAO-126 on retaining or
replacing the r29 source projection, using the two complete graph-group accounts
and the new genuine failure stack. The recommendation is to reject r29 as a
budget-saving candidate and require one complete replacement path before another
implementation run, rather than trading away the next 304-unit check. That
decision can preserve the complete original reader and all new fault evidence;
this run does not silently revert reviewed production code or request a higher
limit. No new research/codec issue or second writer is started.

Three new private MySQL containers and both private runtime roots were removed;
all foreground Java children returned. Nine prior methods, eleven prior SQL
cases and six older successes retain their own earlier-version scope. They were
not rerun. Only fixture formatting, Checkstyle and compilation were required for
this new test-only diff; no JAR rebuild, production static-analysis rerun, old
matrix, CI, deployment or merge was performed. Original A12, nine-node, M2,
independent fence and network/ACK gates remain outstanding. XIAO-125/126 remain
in progress. Raw JFR is private because it contains JVM environment metadata;
the delivered profile contains only the selected performance event types.
