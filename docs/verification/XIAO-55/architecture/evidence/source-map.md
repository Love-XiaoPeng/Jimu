# 固定原码证据

上游 571902028a75c64c1d767f4ae5fd099485f0ddf0；每项blob和本地路径在upstream-sources.json。未执行TypeScript测试；以下是已读断言和实现定位。

## packages/experimental/tool-session-query/src/index.ts

blob 61e18cc8f57826d672ba95b6e1fc2dd6d93e8274；`../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__index.ts`。

- L22: `export const DEFAULT_MAX_SEARCH_RESULTS = 100`
- L25: `export const DEFAULT_SEARCH_TIMEOUT_MS = 30_000`
- L66: `name: 'session_search',`

## packages/experimental/tool-session-query/src/input.ts

blob f9f5de66f252f15db32682a7ad88d348f3837890；`../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__input.ts`。

- L45: `const sessionSearchParameters = {`
- L88: `function buildSessionFilters(args: SessionSearchArgs): SessionResultFilter[] {`
- L103: `function materializeParentSessionIds(values: readonly string[] | undefined): SessionIdValue[] | undefined {`
- L126: `function normalizeQuery(value: string): string {`
- L143: `function sequenceRange(`
- L179: `const ISO_TIMESTAMP =`

## packages/experimental/tool-session-query/src/operations.ts

blob 3d5fa17f317145da3655bc05743140ac6ee32acb；`../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__operations.ts`。

- L55: `async function executeSessionSearch(`
- L90: `sessionFilters.push({ kind: 'cwd', values: [cwd] })`
- L101: `hit => hit.header.id !== caller.id && workspaceAccess.recordAuthorized(hit, caller),`
- L244: `async function collectPages<T>(`
- L262: `if (items.length === maxResults) {`
- L268: `if (seen.has(page.nextCursor)) {`

## packages/experimental/tool-session-query/src/presentation.ts

blob 659a48d17ba46ea16d11b36336323148f690a107；`../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__presentation.ts`。

- L48: `function formatSessionSearch(`
- L71: ``   Best match: seq ${hit.bestMatch.seq} | ${hit.bestMatch.type} | ${hit.bestMatch.surface} | ${formatTime(hit.bestMatch.time)}`,`
- L53: `if (collected.items.length === 0) return formatEmptySessionSearch()`

## packages/experimental/tool-session-query/src/workspace-access.ts

blob 9eabee4e4615c94597870cb40c4c4c73a06533a7；`../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__workspace-access.ts`。

- L56: `function callerOf(exec: ToolRunContext, ctx: Context): Caller {`
- L67: `boundary: ctx.sessionProjections.stateOf(agent.session, 'turnBoundary'),`
- L96: `function headerAuthorized(header: SessionHeader, caller: Caller): boolean {`
- L75: `async function authorizeTarget(`
- L137: `async function readTitles(`

## packages/session-query/session-query/src/corpus.ts

blob 7ab77166cf46d191aa133193d35a233accad9faa；`../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query__src__corpus.ts`。

- L62: `async listSessions(signal?: AbortSignal): Promise<SessionRecord[]> {`
- L76: `for (const session of this._ctx.sessions.list()) {`
- L98: `async load(sessionId: SessionId, signal?: AbortSignal): Promise<LogicalSession> {`
- L314: `return b.header.createdAt - a.header.createdAt || a.header.id.localeCompare(b.header.id)`

## packages/session-query/session-query/src/index.ts

blob 838ce4c74f6ef47b7442922eaefb742da03ba466；`../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query__src__index.ts`。

- L153: `abstract searchSessions(`
- L206: `async filterSessions(`
- L251: `async readTitleSnapshots(`

## packages/session-query/session-query/src/documents.ts

blob 8788c8ea35270340a866003edcc229efd7140ee2；`../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query__src__documents.ts`。

- L36: `export function buildSessionEventSearchDocuments(`

## packages/session-query/session-query-sqlite/src/index.ts

blob 7c95afa28d8d048179e1d434d30950e5d00022b8；`../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query-sqlite__src__index.ts`。

- L79: `export const SESSION_QUERY_SQLITE_DEFAULT_LIMIT = 20`
- L81: `export const SESSION_QUERY_SQLITE_MAX_LIMIT = 100`
- L268: `override async searchSessions(`
- L407: `private async _reconcile(signal: AbortSignal | undefined): Promise<PersistenceBinding> {`
- L495: `private async _observeStable(`
- L672: `PARTITION BY session_id`
- L679: `ORDER BY match_count DESC, document_length ASC, time DESC, session_id ASC, seq DESC`
- L680: `LIMIT ? OFFSET ?`
- L797: `function selectedDocumentsSql(): { sql: string } {`
- L871: `function observeLive(session: Session): ObservedSession {`

## packages/session-query/session-query-sqlite/src/query.ts

blob 5a67653911f449ad82e590283e61b024b5f4a5df；`../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query-sqlite__src__query.ts`。

- L223: `export function quoteFtsData(query: string): string {`
- L269: `export function makeSnippet(markedText: string, maxChars: number): string {`

## packages/session-query/session-query-sqlite/src/schema.ts

blob 6ad031f77f73ee1d1e88ebe6f7963f5d1a856581；`../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query-sqlite__src__schema.ts`。

- L135: `tokenize = 'unicode61'`

## packages/core/agent-loop/src/index.ts

blob ca4532af8c8007cab304e8838a07b0c83f384278；`../../XIAO-52/architecture/evidence/upstream/packages__core__agent-loop__src__index.ts`。

- L57: `export const turnBoundaryProjectionDefinition = {`
- L83: `lastStepStartSeq: event.seq,`

## packages/experimental/tool-session-query/tests/tool-session-query.spec.ts

blob 3d11ad3f859e704dd45400268f54009352300b1d；`../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__tests__tool-session-query.spec.ts`。

- L1295: `it('does not report a cap when only rejected hits remain after the authorized limit', async () => {`
- L1655: `it('rejects current-session search without the turnBoundary fold', async () => {`

## packages/session-query/session-query-sqlite/tests/sqlite.spec.ts

blob 122dc363dab8cdb3c89770ca6bc3983ee2ff1af8；`../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query-sqlite__tests__sqlite.spec.ts`。

- L274: `await expect(service.searchSessions({ query: 'needle' }))`

## Jimu src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java

- L79: `private void sessionAccess(Map<String, Object> task) {`
- L261: `private Map<String, Object> sessionOwner(Identity identity, String taskId) {`
- L507: `public String readOrResult(Lease lease, String stepId, Call call, String version, String digest) {`
- L688: `public String searchOrResult(`

## Jimu src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcSessionRecords.java

- L113: `private static final Set<String> BODIES =`
- L125: `private static final List<String> TABLES =`
- L1158: `void purge(Map<String, Object> task) {`

## Jimu src/main/java/io/github/lovexiaopeng/jimu/api/TaskController.java

- L36: `public record SubmitRequest(`
- L63: `@PostMapping("/api/tasks")`

## Jimu src/main/resources/db/migration/V21__captured_session_event_read_provenance.sql

- L22: `((outcome='PENDING' AND content_state IN ('PRESENT','PURGED') AND read_sources_snapshot IS NULL`
- L22: `((outcome='PENDING' AND content_state IN ('PRESENT','PURGED') AND read_sources_snapshot IS NULL`
