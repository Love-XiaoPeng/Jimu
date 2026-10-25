`JdbcLedger.capabilities` B2 partial checkpoint. Developer self-test only; issue60 stays in_progress and PR22 draft. Base `f57991c13f2aa73522d2db410b5515e790778ab9`; exact final SHA/tree and remote readback are supplied in the manifest.

## Complete discovery use case

TaskController → TaskService → Tasks/JdbcLedger now reaches TaskCapabilitiesServiceImpl. The Service owns the original transaction invocation, identity authorization, enabled installation list and all ten stream filters through to the immutable toList result. MapperTaskCapabilities only supplies the original typed transaction/gates and real MP list; no filtering remains there. The original tx special TaskFailure commit/outside rejection implementation and identity full tenant/actor FOR SHARE/first-row enabled check are unchanged. No second transaction, TaskService recursion, broader catch, nullable-query change, session queryView or deadline/socket/network change.

MCP enabled and search supplier retain evaluation before the name condition for every candidate reaching their filter. Earlier rejection avoids all later gates. Questions and other flags are read live; directory/todo/session qualification is conditional on the original name. Session methods reuse the prior availability Service without caching booleans, selected model, descriptors or queries. Image keeps name → imagesAvailable → selected → existing ModelImages.enabled; its FileFailure/TaskFailure rules remain in their original methods. NULL and non-TaskFailure propagation are not normalized. Unit evidence covers all ten rejection stages, per-candidate and duplicate calls, supplier side effects/failures on unrelated names, identity/list ordering, empty/NULL lists, NULL candidates, image model/selection behavior, preserved candidate order and immutable result. This is not a new broad model matrix.

## Real installation query and preservation

The original single SELECT plugin_id FROM installation WHERE tenant_id=? AND enabled=TRUE ORDER BY plugin_id becomes real InstallationMapper.selectObjs with LambdaQueryWrapper selecting only plugin_id, tenant and enabled predicates and database orderByAsc(plugin_id). No entity over-read, Java sorting, distinct, limit, new lock/query/cache or missing-row refusal. The String cast preserves SQL NULL rather than converting it to the string null; the schema column remains VARCHAR/NOT NULL primary-key data. Actual MySQL samples compare original raw candidate lists, tenant bindings and enabled filtering. MP binds TRUE and adds WHERE parentheses/explicit ASC; that list statement's complete semantic projection/bind/order is compared separately, while every gate SQL and binding is compared exactly. Identity denial reaches no list; each other trial reaches exactly one list query.

One old business JDBC list site was removed; the SQL still executes, so SQL-elimination claimed0. Inventory173 static/29 dynamic/570 binds, all36 table/receipt states OPEN. The other546 existing production Java and all417 baseline resource/frozen files remain byte-identical, schema22/V1–V22 unchanged; no new XML/resource/Bean/POM. All other Ledger methods and original tx/identity/access/baseAccess remain source-identical, including previously integrated63/64/66/70 and session eligibility/availability/response/fact/append/account/owner.

## Accurate product and executions

Baseline JAR SHA256 `f8c0a39c93813f8a54694cab7f0bd5e40740550daea455b504f076b07f8dde09`,44,484,285 bytes/1,521 production entries.

Final tested JAR SHA256 `ebc498a4d173c85ede24be42787eacaae8881c1ff909132b85391f0f6c60a391`,44,491,280 bytes/1,525 production entries. Of the old entries,1,518 retain original bytes; three changes are Ledger and its two nested classes ($3 and ScopeInvalidation). The latter are independently parsed to show only Code.LineNumberTable changes; four entries are new. All other persistence/availability/account and resource bytes match, and target/classes matches the exact final JAR.

| Execution | Actual result |
| --- | --- |
| unit1 | 27 discovery unit cases +2 tracking contracts passed. Test/production source, executed class and compiler inputs sealed before the later verify. No packaged product attributed to this command. |
| verify run1 | 27 discovery +2 tracking +1 architecture =30 unit/contract/architecture checks and1 actual integration test passed, verify0. SpotBugs bugs/errors0. |

Both rounds' source/class/compiler receipts and raw logs are sealed independently. The preparation script's first invocation used the wrong cwd and did not run; first git commit exited128 because no Git identity was configured. Both have terminal-only original receipts, with summaries and that limitation retained separately. The final commit preserves the baseline user author/committer identity via command-scoped git configuration, without writing shared/global/local configuration. Subsequent correct generation/format and both validation commands passed. Existing historical product/test failures remain unchanged and are not rewritten by this success.

## Actual bounded chain and limits

Native and scope private frozen copies each execute old/new9 catalog trials: base tenant-a, tenant-b, identity denial, truly empty installation list by private DELETE, disabled read flag, enabled search supplier, v3/v4 gate combinations and one original list query synthetic fault per driver. This covers only discovery affected by this slice; the previous availability25-case matrix, response six groups and cold initialization experiments were not rerun. Public TaskController/TaskService endpoints add old/new tenant-a/b200 and expired identity401 per mode,12 HTTP samples total. Normal original raw bytes/digests/length/content-type match.401 compares only status/code and retains separate bodies/traceId; no general wire/Map.of guarantee.

Old/new9 RESULT comparisons per mode and9 restorations per driver are complete. Each proof captures all columns of19 tenant-a tables, including private installation mutations; task tables are empty in this catalog fixture. Tenant-b output/binds are checked separately, without claiming a tenant-b snapshot. These36 outer captured-fact restorations are not whole DB/task rollback, persistent commit, FAILED-read or independent invalidation postnotes. Two full private container cleanup receipts confirm children collected and containers removed. No provider calls, tools executed or UNKNOWN recovery/replay/original-slot retry. HY000/vendor1644 is a once-per-driver pre-execute synthetic list failure with rollbackable connection, not natural08S01/failed connection. Cause/suppressed/next SQLException/SQLState/vendor, rollback, cleanup and evidence errors are retained separately.

## Handoff

Delivered to product management with source/bundle, exact JAR parts, per-round originals, SQL/bind/HTTP/raw/fact proofs, source/product preservation and cleanup receipts; archive indexes and concatenation are independently checked. Proposed next concrete B2 slice is complete JdbcLedger.baseAccess authorization orchestration with typed lazy ports, preserving epoch and each existing module's order and locks; its actual scope remains for PM dispatch. No part of baseAccess was implemented here.

B2/B0–B5 incomplete.35 tables+receipt, core final beforeCommit, scope full successful tool read, persistent FAILED-read, independent postnotes, other true missing DB rows, natural08S01/historical709ms, cold paths/lock arrangements and all historical failures/missing originals remain OPEN; final61/62 backlog. No new authorization blocker. No CI/master merge/deployment/real key or billing/shared service/daemon/proxy/FRP/reset operation.

Current run `01a172c1-9aa7-7e3b-b4d8-fabac8df8560` reuses unique completed/failed/cancelled/two-hour PM rule `01a172c2-23a7-7508-9eca-7725ebeb7a8e`, expiry2026-10-25T13:11:28Z. No duplicate rule was registered.
