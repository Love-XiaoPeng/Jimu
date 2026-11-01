# XIAO-126 deterministic repair checkpoint

This is an implementation checkpoint for the first repair batch following
`e316e658f7a6624362f753274ab76d71dead9e4e`, not a stable session_trace candidate.
The original branch and draft PR #24 remain in use. A01–A16 and historical
A03/UNKNOWN outcomes retain their existing scope and status.

## Production changes

- PURGED accounting checks the original 8,388,608-byte reservation separately
  from retained metadata, held bytes and actual settlement. Readback preserves
  that distinction and does not refund a second time.
- `settlementFence` follows dispatch → step → task using columns present in the
  real migrations. It requires the original unanswered dispatch, active current
  attempt, worker, fence and live lease. A recovered current attempt can settle
  an original step without rewriting that step's creation attempt.
- Streaming graph version validation delegates its bounded numeric token to the
  frozen Jackson reader and schema const. The differential oracle is the
  original `SessionContracts.graph(raw)`, including rounding and token-length
  boundaries. The complete graph is not parsed into a second DOM.
- The actual Spring synchronization identifies the write owner before checking
  the explicit query parameter. Missing/wrong query writes poison that owner
  before SQL, including when business code catches the error. Outside an owner,
  the legacy no-query path remains available.
- Named DML registration replaces the regex default-allow classifier. Unknown
  statements, incomplete/conflicting keys and MP wrappers invalidate staging
  conservatively and reject writes intersecting published proofs. Actor and
  installation dependencies are not interpreted as task identifiers.

## Reproducible local checks

The attached run evidence identifies the exact source, tree, JAR and scenario
class hashes, command results and first failures. The targeted Maven command is:

```text
.\mvnw.cmd -B -ntp verify -DskipITs -Dtest=LineageRawViewTest,SessionDependencyTest,SessionWriteOwnerTest,SessionMetadataAccountingServiceTest,SessionQueryOwnershipTest,SessionQueryTransactionTest,ModelDispatchServiceTest,CommitFenceServiceTest,PackageBoundariesTest
```

`SessionRepairSqlScenario` is an explicit foreground main against a fresh private
MySQL 8.0.46 database migrated by Flyway. It extends the seven dependency modes
with staging/published no-query, wrong-query, graph digest, unknown table,
actor, installation, conflicting key, MP insert, MP wrapper and cross-target
writes. Independent snapshots cover 22 tables. Successful staging DML must
actually affect one row; a published rejection must identify the owner/dependency
guard. Unrelated SQL errors cannot satisfy these assertions.

The model/purge fixture calls actual Mapper/service/transaction paths.
`SessionRepairSqlOracle` discovers the 18 accounting tables' columns from
INFORMATION_SCHEMA, excludes an independently specified BODY set and computes
32-byte row / 8-byte field / UTF-8 / NULL / UTC scalar totals without calling
production accounting. The fixture is deliberately small; empty projections
do not prove populated cases or the complete business graph.

## Remaining work

The named-write registry is conservative, not a claim that every normal
invocation/scope/terminal tail has a precise registration. Those paths and
published caller progress still require full-path evidence. Actor changes
without a production write mapper remain unknown and invalidate all staging.

Mapper-result/object handoffs, scalar temporary allocations, simultaneous
response residency, all public-exit owner/current-scope gates and maintenance
budget ownership remain open. The graph descendants undercount, indexed child
traversal and complete joint fixed-genesis/reference-graph/typed-output probe
remain open. No production codec migration or schema/checksum change was made.
The negative candidate margin remains a rejection, not acceptance evidence.

No new A03, real authorization/witness/ACK/new-fence execution acceptance,
independent review, QA, merge, deployment or GitHub CI is established here.
