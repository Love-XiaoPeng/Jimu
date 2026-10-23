// Read-only contract/evidence integrity check. Also generates a browsable source map.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=__dirname, hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const read=f=>fs.readFileSync(path.join(root,f));
const json=f=>JSON.parse(read(f));
const check=(condition,detail)=>{if(!condition)throw Error(detail);};
const upstream=json('evidence/upstream-sources.json');
for(const source of upstream.sources) {
  const body=read(source.local);
  check(body.length===source.bytes&&hash(body)===source.sha256,`upstream sha256 ${source.source}`);
  check(crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex')===source.gitBlob,`blob ${source.source}`);
}
check(upstream.failures.length===0&&upstream.revision==='571902028a75c64c1d767f4ae5fd099485f0ddf0','upstream revision/failures');
const baseline=json('evidence/jimu-sources.json');
for(const item of baseline.sources) {
  const body=fs.readFileSync(item.source);
  check(body.length===item.bytes&&hash(body)===item.sha256,`changed baseline ${item.source}`);
}
const catalog=json('contracts/catalog-v2.json');
for(const tool of catalog.tools) {
  for(const [key,file] of Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',
    manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'}))
    check(hash(read(`contracts/${tool.name}-${tool.version}/${file}`))===tool[key],`pin ${tool.name}/${file}`);
}
for(const [file,digest] of Object.entries(catalog.schemas)) check(hash(read(`contracts/${file}`))===digest,`schema pin ${file}`);
const binding=json('contracts/examples/binding.json');
check(binding.catalogDigest===hash(read('contracts/catalog-v2.json')),'catalog pin');
const bindingSchema=json('contracts/task-binding-v2.schema.json');
check(Object.keys(binding).length===bindingSchema.required.length,'binding fields');
for(const [field,value] of Object.entries(binding)) check(JSON.stringify(bindingSchema.properties[field].const)===JSON.stringify(value),`binding ${field}`);
check(read('contracts/session_event_read-0.2.0/input.schema.json').equals(fs.readFileSync('src/main/resources/session/session_event_read-0.1.0/input.schema.json')),'read input bytes');
const wire=read('contracts/examples/search-output.wire.json');
const output=json('contracts/examples/search-output.json'),goal=json('contracts/examples/user-event.json');
check(JSON.stringify(JSON.parse(wire))===JSON.stringify(output),'wire/object');
check(output.items[0].event_sha256===hash(Buffer.from(JSON.stringify(goal))),'example source digest');
check(output.returned_count===output.items.length&&wire.length<=32768,'example count/bytes');
const selected=[
 ['packages/experimental/tool-session-query/src/index.ts',['DEFAULT_MAX_SEARCH_RESULTS','DEFAULT_SEARCH_TIMEOUT_MS',"name: 'session_event_search'"]],
 ['packages/experimental/tool-session-query/src/input.ts',['const eventSearchParameters','function normalizeQuery','function sequenceRange','function timestampRange','const ISO_TIMESTAMP']],
 ['packages/experimental/tool-session-query/src/operations.ts',['async function executeEventSearch','const stepStartSeq','async function collectPages','items.length === maxResults']],
 ['packages/experimental/tool-session-query/src/workspace-access.ts',['function callerOf','async function authorizeTarget','function assertObservedTargetAuthorized']],
 ['packages/experimental/tool-session-query/src/presentation.ts',['function formatEventSearch']],
 ['packages/session-query/session-query/src/index.ts',['abstract searchEvents','async filterEvents','async readEvent']],
 ['packages/session-query/session-query/src/corpus.ts',['async listSessions','async load','async projectMany']],
 ['packages/session-query/session-query/src/extraction.ts',['function extractSessionEventText','function contentText']],
 ['packages/session-query/session-query/src/documents.ts',['function buildSessionEventSearchDocuments','function classifySurface']],
 ['packages/session-query/session-query/src/filters.ts',['function compileSessionTextFilter']],
 ['packages/session-query/session-query-sqlite/src/index.ts',['SESSION_QUERY_SQLITE_DEFAULT_LIMIT','SESSION_QUERY_SQLITE_MAX_LIMIT','SESSION_QUERY_SQLITE_SNIPPET_CHARS','override async searchEvents','private _queryEvents','ORDER BY match_count DESC, document_length ASC, time DESC, seq DESC','function selectedDocumentsSql','function page<']],
 ['packages/session-query/session-query-sqlite/src/query.ts',['function quoteFtsData','function makeSnippet','function normalizeQuery','function normalizeEventRequest']],
 ['packages/session-query/session-query-sqlite/src/schema.ts',["tokenize = 'unicode61'"]],
 ['packages/core/agent-loop/src/index.ts',['turnBoundaryProjectionDefinition',"case 'step/start'",'register(turnBoundaryProjectionDefinition)']],
 ['packages/session/session-projection/src/index.ts',['export interface ProjectionDefinition','export interface ProjectionCheckpointRow','stateOf<']],
 ['packages/experimental/tool-session-query/tests/tool-session-query.spec.ts',['rejects a repeated internal cursor','intersects current-session search','returns no current-session hits','requires a current step boundary','without the turnBoundary fold']],
 ['packages/experimental/tool-session-query/tests/sqlite-integration.spec.ts',['searches live prior-step history','fractional epoch-millisecond']],
 ['packages/session-query/session-query-sqlite/tests/sqlite.spec.ts',['searches all surfaces','literal phrase tokens','ranks live and persisted','positions snippets']],
 ['LICENSE',['MIT License','Copyright (c) 2026 DeepSeek']],
];
let map='# 固定源码事实索引\n\n仅为阅读证据；没有运行上游TS测试。上游SHA、每文件Git blob/摘要见upstream-sources.json；所有行号指该固定blob。\n\n';
for(const [source,patterns] of selected) {
  const item=upstream.sources.find(x=>x.source===source);check(item,`missing source ${source}`);
  const lines=read(item.local).toString('utf8').split('\n');
  map+=`## ${source}\n\nGit blob: \`${item.gitBlob}\`。\n\n`;
  for(const pattern of patterns) {
    const index=lines.findIndex(line=>line.includes(pattern));check(index>=0,`missing pattern ${source}: ${pattern}`);
    map+=`- L${index+1}: \`${pattern}\` — [固定原码](${item.url.replace('raw.githubusercontent.com','github.com').replace('/deepseek-harness/','/deepseek-harness/blob/')}#L${index+1})\n`;
  }
  map+='\n';
}
map+='## Jimu实际接入点（本次基线）\n\n';
for(const source of baseline.sources.map(x=>x.source).filter(x=>/JdbcSessionRecords|JdbcLedger|SessionContracts|DurableAgent|SessionHistoryController|SessionQueryDeadline/.test(x))) {
  const lines=fs.readFileSync(source,'utf8').split('\n');
  const patterns=source.includes('JdbcSessionRecords')?['static boolean task','Map<String, Object> history','void bindReads','void response(','JsonNode verify(','void purge(','void release(']:
    source.includes('JdbcLedger')?['private void sessionAccess','private <T> T sessionTransaction','public String readOrResult','boolean sessionRead =','Required migration unavailable']:
    source.includes('DurableAgent')?['List.of("session_event_read")','private String invokeSession']:
    source.includes('SessionContracts')?['public SessionContracts()','public SessionEventPort.Input input(String','public PublicText publicText']:
    source.includes('SessionQueryDeadline')?['public SessionQueryDeadline','static DataSource source']:['@GetMapping'];
  map+=`\`${source}\`\n\n`;
  for(const pattern of patterns) {const index=lines.findIndex(x=>x.includes(pattern));if(index>=0)map+=`- L${index+1}: \`${pattern}\`\n`;}
  map+='\n';
}
fs.writeFileSync(path.join(root,'evidence/source-map.md'),map.trimEnd()+'\n');
const result={scope:'Architecture artifacts only; no product build/DB/worker/HTTP/semantic search tests',
  upstreamBlobs:upstream.sources.length,unchangedBaselineFiles:baseline.sources.length,
  pinnedTools:catalog.tools.length,searchOutputExampleBytes:wire.length,
  readInputByteIdentical:true,catalogAndBindingPins:true,exampleDigest:true};
fs.writeFileSync(path.join(root,'evidence/artifact-check.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));
