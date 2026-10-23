# 原码证据定位

固定上游 571902028a75c64c1d767f4ae5fd099485f0ddf0；下列行号均指向manifest中的不可变原文件。

## packages/experimental/tool-session-query/src/index.ts

Git blob 61e18cc8f57826d672ba95b6e1fc2dd6d93e8274；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__index.ts`。

- L54：`+ 'session_trace, session_event_trace, or session_event_read when you need lineage, relationships, or exact data.'`
- L70：`timeoutMs: resolved.searchTimeoutMs,`

## packages/experimental/tool-session-query/src/operations.ts

Git blob 3d5fa17f317145da3655bc05743140ac6ee32acb；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__operations.ts`。

- L203：`async function executeEventTrace(`
- L214：`ctx.sessionQuery.traceEvent({ sessionId, seq }, exec.signal))`
- L129：`const stepStartSeq = caller.boundary?.lastStepStartSeq`

## packages/experimental/tool-session-query/src/presentation.ts

Git blob 659a48d17ba46ea16d11b36336323148f690a107；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__presentation.ts`。

- L149：`function formatEventTrace(`
- L158：``Replacement chain: ${seqList(trace.replacementChain)}`,`

## packages/experimental/tool-session-query/src/workspace-access.ts

Git blob 9eabee4e4615c94597870cb40c4c4c73a06533a7；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__src__workspace-access.ts`。

- L56：`function callerOf(exec: ToolRunContext, ctx: Context): Caller {`
- L67：`boundary: ctx.sessionProjections.stateOf(agent.session, 'turnBoundary'),`
- L75：`async function authorizeTarget(`
- L101：`function assertObservedTargetAuthorized(`

## packages/session-query/session-query/src/tracing.ts

Git blob 0cd3fccfbb8e5f740bd88014d36b52bf23a4740a；本地证据 `evidence/upstream/packages__session-query__session-query__src__tracing.ts`。

- L71：`export function traceEvent(`
- L86：`const replacementChain: SessionSeq[] = []`
- L181：`function analyzeEventLog(`
- L222：`function eventSources(event: SessionEvent): readonly SessionSeq[] {`

## packages/core/session/src/surface.ts

Git blob 0bb84c030ef595b3d9a17116e7dc7b9deb26369c；本地证据 `evidence/upstream/packages__core__session__src__surface.ts`。

- L302：`function surfaceOpOf(event: SessionEvent): SurfaceOp | undefined {`
- L330：`function assertSourceEventReferences(`
- L594：`export function foldSurface(events: readonly SessionEvent[], projections: readonly SessionMessageProjection[] = []): SurfaceFoldResult {`

## packages/core/agent-loop/src/index.ts

Git blob ca4532af8c8007cab304e8838a07b0c83f384278；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__core__agent-loop__src__index.ts`。

- L57：`export const turnBoundaryProjectionDefinition = {`
- L83：`lastStepStartSeq: event.seq,`

## packages/session-query/session-query/src/index.ts

Git blob 838ce4c74f6ef47b7442922eaefb742da03ba466；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query__src__index.ts`。

- L340：`async traceEvent(request: SessionEventTraceRequest, signal?: AbortSignal): Promise<SessionEventTraceObservation> {`

## packages/session-query/session-query/src/corpus.ts

Git blob 7ab77166cf46d191aa133193d35a233accad9faa；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__session-query__session-query__src__corpus.ts`。

- L98：`async load(sessionId: SessionId, signal?: AbortSignal): Promise<LogicalSession> {`

## packages/session-query/session-query/tests/tracing.spec.ts

Git blob 4f97cdb72a1ddcf3cf3a3205952b8a179ec95061；本地证据 `evidence/upstream/packages__session-query__session-query__tests__tracing.spec.ts`。

- L311：`it('returns direct replacement and cited source-event links in their contract order', async () => {`
- L416：`it('checks target existence before surface or source-event analysis', async () => {`
- L447：`['non-surface sources', [`

## packages/experimental/tool-session-query/tests/tool-session-query.spec.ts

Git blob 3d11ad3f859e704dd45400268f54009352300b1d；本地证据 `../../XIAO-52/architecture/evidence/upstream/packages__experimental__tool-session-query__tests__tool-session-query.spec.ts`。

- L2013：`expect(text(result)).toContain('Replacement chain: 1')`
- L2014：`expect(text(result)).toContain('Events cited directly as sources: none')`

## Jimu基线接入点

### src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcSessionRecords.java

- L66：`private static final Set<String> BODIES =`
- L77：`private static final List<String> TABLES =`
- L512：`Read read(Map<String, Object> task, SessionEventPort.Input input, long upper, String boundary) {`
- L577：`JsonNode verify(Map<String, Object> task, Map<String, Object> r) {`
- L704：`Map<String, Object> receipt(Map<String, Object> b) {`
- L804：`void purge(Map<String, Object> task) {`

### src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java

- L441：`public String readOrResult(Lease lease, String stepId, Call call, String version, String digest) {`
- L603：`public String searchOrResult(`
- L586：`sessions.receipt(committed),`

### src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/session/SessionSearchBodyFilter.java

- L18：`public final class SessionSearchBodyFilter implements Filter {`

### src/main/java/io/github/lovexiaopeng/jimu/api/SessionHistoryController.java

- L23：`public final class SessionHistoryController {`
