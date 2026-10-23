# Authorized session event search

ADR-0012 and `verification/XIAO-53/architecture/implementation-contract.md` define the versioned contract. This implementation adds migration V20, `session_event_search@0.1.0` and the explicit `local-session-event-search-v2` binding. The existing read-only v1 resources and V1–V19 remain unchanged.

Enable both `jimu.session-event-read.enabled=true` and `jimu.session-event-search.enabled=true` explicitly. The latter defaults to false. Admit exactly `["session_event_read","session_event_search"]`, in that order, with no resources or images. Install read `0.2.0` and search `0.1.0` with their packaged output digests. Current installation pins and epochs are rechecked; changing read's installed version revokes access to tasks pinned to its other version. No grants or installations are executed by the product on an operator's behalf; the grant generator emits reviewable SQL for the new table.

The model must pass the existing trusted local, explicit paid=false whole-request proof gate. The combination retains the 180 second, 8 model request, 20 tool and 8 attempt limits. It uses the existing single ToolCallingAdvisor loop. The immutable M1 context freezes all slots' upper sequence. M2 captures original arguments and a separate public projection of search's query, then binds each normalized query to its original invocation. Successful replay checks current authority and returns original bytes without rescanning newer history. Search uses reliable tool receipts and does not append a native read receipt.

Only stored public user/model content participates in case-sensitive literal code point matching. ECMAScript whitespace is collapsed; regular expressions, wildcard/FTS syntax, case/Unicode folding and recursive tool outputs are not evaluated. Sequence/time/type filters use the frozen prefix; provenance is validated even for filtered-out events. Valid `surfaces` filters return `SESSION_SEARCH_FILTER_UNSUPPORTED`. Results sort by occurrences descending, normalized length ascending, event microseconds descending and actual sequence descending, with at most16 hits and explicit snippet clipping/capping. Read the returned seq through the independent authorized read tool to inspect the full event.

Owner API: `POST /api/tasks/{taskId}/session-history/search`, authenticated as the task's current owner, `Content-Type: application/json`, body using the packaged search input Schema. Path authorization precedes body errors. It rejects querystrings and bodies over2048 bytes, uses nonblocking body reads, one shared cancellable deadline timer (not one thread per request), and one absolute2 second deadline across initial authorization, body, parsing and the final reauthorized database snapshot. It returns `Cache-Control: no-store`. Owner queries do not create invocations, charge tool/model budget or mutate task state; retained terminal v2 tasks remain queryable when the feature flag is off. V1 search returns409 `SESSION_SEARCH_UNAVAILABLE`.

Candidate reads use the task-scoped primary key prefix, at most128 events/2 MiB, with bounded source reads, read/scratch accounting, code point comparisons and32 KiB output. SQL, physical connection/handshake and computation share the remaining deadline. Provider faults never fall back to another source. Query/parameters/output/context copies are body bytes within the original storage quota. The normalized query participates in the same7 day expiry and safe exact cleanup, with digest/byte tombstones retained and charged. The post-settlement fixed-point calculation includes reservation metadata widths.

Targeted local verification entry points:

```text
.\mvnw.cmd -B -ntp spotless:apply package -Dtest=SessionSearchContractsTest,SessionContractsTest,SessionDeadlineTest,PackageBoundariesTest,ModelProfilesTest,ModelProtocolTest,ModelInputBoundTest,WorkingDirectoryContractTest,TodoContractTest,UserQuestionContractTest,McpContractTest spotbugs:check
python3 tools/session_search_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --evidence target/xiao53/runtime --case all
python3 tools/session_search_migration_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --old-jar <exact-V19.jar> --evidence target/xiao53/migration
python3 tools/session_event_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --evidence target/xiao53/read-v1 --case core
python3 tools/session_event_regression_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --evidence target/xiao53/regressions
```

Run Python integration on the existing private WSL/Docker fixture as documented by the previous session batch. Missing resources fail. These are development checks; exact SHA/tree/JAR evidence and the actual S01–S12 coverage/limitations belong in the delivery report. Do not run GitHub CI, rewrite historical verify failures or interpret the targeted command as a default full verify pass.
