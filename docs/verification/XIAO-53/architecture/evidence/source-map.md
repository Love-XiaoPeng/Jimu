# 固定源码事实索引

仅为阅读证据；没有运行上游TS测试。上游SHA、每文件Git blob/摘要见upstream-sources.json；所有行号指该固定blob。

## packages/experimental/tool-session-query/src/index.ts

Git blob: `61e18cc8f57826d672ba95b6e1fc2dd6d93e8274`。

- L22: `DEFAULT_MAX_SEARCH_RESULTS` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts#L22)
- L25: `DEFAULT_SEARCH_TIMEOUT_MS` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts#L25)
- L76: `name: 'session_event_search'` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts#L76)

## packages/experimental/tool-session-query/src/input.ts

Git blob: `f9f5de66f252f15db32682a7ad88d348f3837890`。

- L69: `const eventSearchParameters` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/input.ts#L69)
- L126: `function normalizeQuery` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/input.ts#L126)
- L143: `function sequenceRange` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/input.ts#L143)
- L158: `function timestampRange` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/input.ts#L158)
- L179: `const ISO_TIMESTAMP` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/input.ts#L179)

## packages/experimental/tool-session-query/src/operations.ts

Git blob: `3d5fa17f317145da3655bc05743140ac6ee32acb`。

- L117: `async function executeEventSearch` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/operations.ts#L117)
- L129: `const stepStartSeq` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/operations.ts#L129)
- L244: `async function collectPages` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/operations.ts#L244)
- L262: `items.length === maxResults` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/operations.ts#L262)

## packages/experimental/tool-session-query/src/workspace-access.ts

Git blob: `9eabee4e4615c94597870cb40c4c4c73a06533a7`。

- L56: `function callerOf` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/workspace-access.ts#L56)
- L75: `async function authorizeTarget` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/workspace-access.ts#L75)
- L101: `function assertObservedTargetAuthorized` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/workspace-access.ts#L101)

## packages/experimental/tool-session-query/src/presentation.ts

Git blob: `659a48d17ba46ea16d11b36336323148f690a107`。

- L85: `function formatEventSearch` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/presentation.ts#L85)

## packages/session-query/session-query/src/index.ts

Git blob: `838ce4c74f6ef47b7442922eaefb742da03ba466`。

- L164: `abstract searchEvents` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/index.ts#L164)
- L280: `async filterEvents` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/index.ts#L280)
- L355: `async readEvent` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/index.ts#L355)

## packages/session-query/session-query/src/corpus.ts

Git blob: `7ab77166cf46d191aa133193d35a233accad9faa`。

- L62: `async listSessions` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/corpus.ts#L62)
- L98: `async load` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/corpus.ts#L98)
- L139: `async projectMany` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/corpus.ts#L139)

## packages/session-query/session-query/src/extraction.ts

Git blob: `198b11819796c6dd73a1156267aa7fb5793e8533`。

- L15: `function extractSessionEventText` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/extraction.ts#L15)
- L66: `function contentText` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/extraction.ts#L66)

## packages/session-query/session-query/src/documents.ts

Git blob: `8788c8ea35270340a866003edcc229efd7140ee2`。

- L36: `function buildSessionEventSearchDocuments` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/documents.ts#L36)
- L57: `function classifySurface` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/documents.ts#L57)

## packages/session-query/session-query/src/filters.ts

Git blob: `91ae640615860c07d28feeaa3841b5488afe6d53`。

- L105: `function compileSessionTextFilter` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query/src/filters.ts#L105)

## packages/session-query/session-query-sqlite/src/index.ts

Git blob: `7c95afa28d8d048179e1d434d30950e5d00022b8`。

- L79: `SESSION_QUERY_SQLITE_DEFAULT_LIMIT` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L79)
- L81: `SESSION_QUERY_SQLITE_MAX_LIMIT` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L81)
- L83: `SESSION_QUERY_SQLITE_SNIPPET_CHARS` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L83)
- L296: `override async searchEvents` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L296)
- L684: `private _queryEvents` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L684)
- L673: `ORDER BY match_count DESC, document_length ASC, time DESC, seq DESC` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L673)
- L797: `function selectedDocumentsSql` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L797)
- L961: `function page<` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/index.ts#L961)

## packages/session-query/session-query-sqlite/src/query.ts

Git blob: `5a67653911f449ad82e590283e61b024b5f4a5df`。

- L223: `function quoteFtsData` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/query.ts#L223)
- L269: `function makeSnippet` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/query.ts#L269)
- L318: `function normalizeQuery` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/query.ts#L318)
- L122: `function normalizeEventRequest` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/query.ts#L122)

## packages/session-query/session-query-sqlite/src/schema.ts

Git blob: `6ad031f77f73ee1d1e88ebe6f7963f5d1a856581`。

- L135: `tokenize = 'unicode61'` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/src/schema.ts#L135)

## packages/core/agent-loop/src/index.ts

Git blob: `ca4532af8c8007cab304e8838a07b0c83f384278`。

- L57: `turnBoundaryProjectionDefinition` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/core/agent-loop/src/index.ts#L57)
- L80: `case 'step/start'` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/core/agent-loop/src/index.ts#L80)
- L378: `register(turnBoundaryProjectionDefinition)` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/core/agent-loop/src/index.ts#L378)

## packages/session/session-projection/src/index.ts

Git blob: `ac6e63458f840f6f8b1c95541e3fbf62912eccd8`。

- L48: `export interface ProjectionDefinition` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session/session-projection/src/index.ts#L48)
- L127: `export interface ProjectionCheckpointRow` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session/session-projection/src/index.ts#L127)
- L320: `stateOf<` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session/session-projection/src/index.ts#L320)

## packages/experimental/tool-session-query/tests/tool-session-query.spec.ts

Git blob: `3d11ad3f859e704dd45400268f54009352300b1d`。

- L1561: `rejects a repeated internal cursor` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/tests/tool-session-query.spec.ts#L1561)
- L1591: `intersects current-session search` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/tests/tool-session-query.spec.ts#L1591)
- L1614: `returns no current-session hits` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/tests/tool-session-query.spec.ts#L1614)
- L1625: `requires a current step boundary` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/tests/tool-session-query.spec.ts#L1625)
- L1655: `without the turnBoundary fold` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/tests/tool-session-query.spec.ts#L1655)

## packages/experimental/tool-session-query/tests/sqlite-integration.spec.ts

Git blob: `566e34478a6218abb12a92b807384d5ac15468a4`。

- L41: `searches live prior-step history` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/tests/sqlite-integration.spec.ts#L41)
- L114: `fractional epoch-millisecond` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/tests/sqlite-integration.spec.ts#L114)

## packages/session-query/session-query-sqlite/tests/sqlite.spec.ts

Git blob: `122dc363dab8cdb3c89770ca6bc3983ee2ff1af8`。

- L399: `searches all surfaces` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/tests/sqlite.spec.ts#L399)
- L512: `literal phrase tokens` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/tests/sqlite.spec.ts#L512)
- L533: `ranks live and persisted` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/tests/sqlite.spec.ts#L533)
- L557: `positions snippets` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session-query/session-query-sqlite/tests/sqlite.spec.ts#L557)

## LICENSE

Git blob: `c1f7a78e89e4e4dc7b86664c3b3c76eb5eee1785`。

- L1: `MIT License` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/LICENSE#L1)
- L3: `Copyright (c) 2026 DeepSeek` — [固定原码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/LICENSE#L3)

## Jimu实际接入点（本次基线）

`src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcSessionRecords.java`

- L72: `static boolean task`
- L90: `Map<String, Object> history`
- L388: `void response(`
- L512: `JsonNode verify(`
- L652: `void purge(`
- L710: `void release(`

`src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java`

- L68: `private void sessionAccess`
- L105: `private <T> T sessionTransaction`
- L360: `public String readOrResult`
- L3502: `boolean sessionRead =`
- L7291: `Required migration unavailable`

`src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/session/SessionQueryDeadline.java`

- L47: `public SessionQueryDeadline`
- L122: `static DataSource source`

`src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/session/SessionContracts.java`

- L45: `public SessionContracts()`
- L168: `public SessionEventPort.Input input(String`
- L216: `public PublicText publicText`

`src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent.java`

- L181: `List.of("session_event_read")`
- L912: `private String invokeSession`

`src/main/java/io/github/lovexiaopeng/jimu/api/SessionHistoryController.java`

- L37: `@GetMapping`
