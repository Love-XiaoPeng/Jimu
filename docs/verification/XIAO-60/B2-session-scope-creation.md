## Session scope creation increment

Baseline: `677cf21ab88f0786508d68bf15e3e64165cc9393`, tree
`abb61c034a0434438b404f824aedf6dae0d6d6f1`. The original packaged JAR is
44,928,926 bytes, SHA256
`5a96e6b9edd56503737b0e82c8d5296f4b7ba9e4e2c0cf7bc425274d293cca1f`.

`JdbcSessionScopes.create` delegates to `SessionScopeCreationServiceImpl` through
`SessionScopeCreationRecords`. The Service owns the ordered source loop, local
403/404/410 mapping, authority and target field order, earliest retention, two
binding/hash evaluations, contract validation order, target ordinals and UTF8
accounting. Row views retain the original map; getters cast on each original
arrival. The port only invokes existing source/history eligibility, codecs and
individual IO. `Object` for the target SQL source preserves the original
`target.get("session_id")` value without an additional cast.

The existing `SessionScopeMapper` owns the authority and target INSERTs;
`SessionHistoryRetentionMapper` owns the history retention UPDATE. The three
original statements and 22 binding positions are recorded in
`evidence/b2-session-create-removed-sql.json`. Update counts remain ignored.
Admission still calls `sessions.create`, scope creation, then `holdHistory` on
the original DS/UoW/query context. No transaction, retry, commit, compensation,
deadline or connection settings are introduced.

The necessary new tests cover empty/ordered multiple targets, earliest retain,
ordered fields, UTF8 copies, repeated binding and caller accesses, local catch
boundaries, later failure stopping, map identity and NULL/cast first errors.
Actual loaded SQL and binding positions and the current Spring Mapper scan are
checked without connecting to a database. Existing PriorSession contracts/IT,
scope eligibility/normalization and admission/history-storage coverage were
read and applicable unchanged evidence reused; the old complete matrix is not
rerun.

`B2SessionScopeCreationIT` reuses the existing private session fixture setup.
One shared recorder executes both original and new packaged JARs. Public
paid=false admission creates empty scopes and two independent v4 source rows;
explicit SQL makes those sources terminal, quiescent and legally retained.
No worker, tool or provider generates the terminal evidence. The complete
two-source admission and reordered-source idempotency, authority/target hashes,
caller/history retention, actual stored UTF8 body and permission refusal are
recorded. Source locks and the original repeated history reads are retained.
Raw SQL, each binding, setters, result access types and connection IDs remain
in the evidence; driver API differences are reported rather than normalized
away. Generated UUIDs and DB timestamps are checked against this side's actual
rows, not equated across independent executions.

Calls join an outer test transaction. A rejected admission can leave its
partial task/history rows visible inside that transaction until rollback; this
does not prove a separately committed rejection or per-call rollback. The
original query session settings are restored after calls. Whole private-table
before/after captures, including `worker_process`, prove outer rollback only.
Private container/process/files/fixture cleanup is captured separately.

Actual remaining inventory is **15 static JDBC sites / 29 dynamic sites / 12
static binds**, SQL eliminated **0**. Remaining static sites: Ledger 6/5 binds,
ExecutionScopes 6/4, SessionQueryTransaction 2/0, ExecutionRecords 1/3. Dynamic
sites are not decremented by literal counting. All 36 table states remain OPEN.
The original session timeout Statement/getInt sites are infrastructure,
not migrated business IO. A suitable next complete group is execution-scope
qualification/provisioning evidence plus its `requireScope` consumption, when
PM activates that frozen boundary; owner/checkpoint and infrastructure remain
separate remaining work.

`JdbcLedger` is unchanged. Scope task/lock/scope/snapshot/require/source bodies
are unchanged, with only `source` visibility widened for its port adapter.
Runtime, controllers, Host, Bean/bootstrap, POM, schema22/migrations and all
already migrated business internals remain frozen. No CI, deployment, paid or
shared service action, master merge, or T48 activation occurs. XIAO-60 remains
in_progress and PR22 draft; this is a B2 development increment, not independent
QA or whole-refactor acceptance.

The attached manifest binds final commit/tree, production source blobs/classes,
frozen blobs, original JAR bytes and remaining inventory. The delta patch
reconstructs the exact tested JAR from the baseline above. The offline verifier
also accepts only manifest, patch and evidence parts, without a full ZIP
fallback. Original failed checks and corrected results are retained separately.

The production JAR is 44,936,946 bytes, SHA256
`70c452c335d8d0c992a901796d51dcb2fbf468574ef4046c6cfd552875975c18`.
The final production checks passed: 27 targeted tests, SpotBugs zero findings,
and one bounded MySQL IT. A subsequent fixture-only correction fixes permission
refusal to the second canonical source so independent random UUID order cannot
make the comparison flaky; the final one-IT execution uses the same unchanged
production JAR. Each side records 6 calls / 736 SQL / 1,686 binds / 8 direct
parameter relationships, reaches all three migrated sites and restores all 36
tables. JDBC executeUpdate becomes Mapper execute at the eight migrated
invocations; the full setters/getters/type evidence remains raw. Remaining
deadline milliseconds are checked separately on each side before comparing
SQL order. This is development evidence, not independent QA.
