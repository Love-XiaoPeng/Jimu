// Capture immutable upstream evidence. Does not run or change product code.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),https=require('node:https'),crypto=require('node:crypto');
const root=__dirname,repo=path.resolve(root,'../../../..');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const git=(...a)=>cp.execFileSync('git',a,{cwd:repo,encoding:'utf8'}).trim();
const write=(p,v)=>{p=path.join(root,p);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,typeof v==='string'?v:JSON.stringify(v,null,2)+'\n');};
function get(url){return new Promise((resolve,reject)=>{const r=https.get(url,{headers:{'User-Agent':'Jimu-XIAO-55'},timeout:20000},s=>{const b=[];s.on('data',x=>b.push(x));s.on('end',()=>s.statusCode===200?resolve(Buffer.concat(b)):reject(Error('HTTP '+s.statusCode+' '+url)));});r.on('timeout',()=>r.destroy(Error('timeout')));r.on('error',reject);});}
(async()=>{
 const revision='571902028a75c64c1d767f4ae5fd099485f0ddf0';
 const previousRoot=path.resolve(root,'../../XIAO-54/architecture');
 const previous=JSON.parse(fs.readFileSync(path.join(previousRoot,'evidence/upstream-sources.json')));
 const tree=JSON.parse(await get(`https://api.github.com/repos/deepseek-ai/deepseek-harness/git/trees/${revision}?recursive=1`));
 if(tree.sha!==revision||tree.truncated)throw Error('Incomplete upstream tree');
 const results=[];
 for(let offset=0;offset<previous.sources.length;offset+=8){
  const batch=await Promise.allSettled(previous.sources.slice(offset,offset+8).map(async prior=>{
   const body=await get(prior.url),local=path.relative(root,path.resolve(previousRoot,prior.local)).replaceAll('\\','/');
   const blob=crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex');
   if(hash(body)!==prior.sha256||blob!==tree.tree.find(x=>x.path===prior.source)?.sha||!body.equals(fs.readFileSync(path.resolve(root,local))))throw Error('Evidence mismatch '+prior.source);
   return {...prior,local,gitBlob:blob};
  })); results.push(...batch);
 }
 const failures=results.filter(x=>x.status==='rejected').map(x=>String(x.reason));
 const sources=results.filter(x=>x.status==='fulfilled').map(x=>x.value);
 write('evidence/upstream-sources.json',{revision,treeTruncated:false,checkedAt:new Date().toISOString(),sources,failures});
 if(failures.length)throw Error(failures.join('\n'));
 const all=git('ls-files','-z').split('\0').filter(Boolean);
 const baseline=all.filter(x=>x.startsWith('docs/baseline/')||x==='docs/adr/README.md'||x.startsWith('src/main/resources/session/')||x.startsWith('src/main/resources/db/migration/')||[
  'JdbcLedger.java','JdbcSessionRecords.java','DurableAgent.java','ModelProfiles.java','Tasks.java','SessionEventLedger.java','TaskController.java','SessionHistoryController.java','SessionSearchBodyFilter.java','SessionSearchMatcher.java','SessionQueryDeadline.java','application_grants.py'
 ].some(n=>x.endsWith('/'+n)));
 write('evidence/jimu-sources.json',{commit:git('rev-parse','HEAD'),tree:git('rev-parse','HEAD^{tree}'),sources:baseline.map(source=>{const b=fs.readFileSync(path.join(repo,source));return{source,bytes:b.length,sha256:hash(b),gitBlob:git('rev-parse',`HEAD:${source}`)};})});
 const anchors={
  'packages/experimental/tool-session-query/src/index.ts':['DEFAULT_MAX_SEARCH_RESULTS','DEFAULT_SEARCH_TIMEOUT_MS',"name: 'session_search'"],
  'packages/experimental/tool-session-query/src/input.ts':['const sessionSearchParameters','function buildSessionFilters','function materializeParentSessionIds','function normalizeQuery','function sequenceRange','const ISO_TIMESTAMP'],
  'packages/experimental/tool-session-query/src/operations.ts':['async function executeSessionSearch','sessionFilters.push({ kind: \'cwd\'','hit => hit.header.id !== caller.id','async function collectPages','items.length === maxResults','seen.has(page.nextCursor)'],
  'packages/experimental/tool-session-query/src/presentation.ts':['function formatSessionSearch','Best match:','formatEmptySessionSearch'],
  'packages/experimental/tool-session-query/src/workspace-access.ts':['function callerOf',"stateOf(agent.session, 'turnBoundary')",'function headerAuthorized','async function authorizeTarget','async function readTitles'],
  'packages/session-query/session-query/src/corpus.ts':['async listSessions','this._ctx.sessions.list()','async load','return b.header.createdAt'],
  'packages/session-query/session-query/src/index.ts':['abstract searchSessions','async filterSessions','async readTitleSnapshots'],
  'packages/session-query/session-query/src/documents.ts':['export function buildSessionEventSearchDocuments'],
  'packages/session-query/session-query-sqlite/src/index.ts':['SESSION_QUERY_SQLITE_DEFAULT_LIMIT = 20','SESSION_QUERY_SQLITE_MAX_LIMIT = 100','override async searchSessions','private async _reconcile','private async _observe','PARTITION BY session_id','ORDER BY match_count DESC, document_length ASC, time DESC, session_id ASC, seq DESC','LIMIT ? OFFSET ?','function selectedDocumentsSql','function observeLive'],
  'packages/session-query/session-query-sqlite/src/query.ts':['export function quoteFtsData','export function makeSnippet'],
  'packages/session-query/session-query-sqlite/src/schema.ts':['unicode61'],
  'packages/core/agent-loop/src/index.ts':['turnBoundaryProjectionDefinition','lastStepStartSeq: event.seq'],
  'packages/experimental/tool-session-query/tests/tool-session-query.spec.ts':['does not report a cap when only rejected hits remain','rejects current-session search without the turnBoundary fold'],
  'packages/session-query/session-query-sqlite/tests/sqlite.spec.ts':['searchSessions'],
 };
 const lines=['# 固定原码证据','',`上游 ${revision}；每项blob和本地路径在upstream-sources.json。未执行TypeScript测试；以下是已读断言和实现定位。`,''];
 for(const [source,needles] of Object.entries(anchors)){
  const entry=sources.find(x=>x.source===source);if(!entry)throw Error('Missing anchor source '+source);
  const rows=fs.readFileSync(path.resolve(root,entry.local),'utf8').split(/\r?\n/);
  lines.push(`## ${source}`,'',`blob ${entry.gitBlob}；\`${entry.local}\`。`,'');
  for(const needle of needles){const i=rows.findIndex(x=>x.includes(needle));if(i<0)throw Error('Missing anchor '+needle);lines.push(`- L${i+1}: \`${rows[i].trim()}\``);}lines.push('');
 }
 const localAnchors={
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java':['private void sessionAccess','private Map<String, Object> sessionOwner','public String readOrResult','public String searchOrResult'],
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcSessionRecords.java':['private static final Set<String> BODIES','private static final List<String> TABLES','void purge('],
  'src/main/java/io/github/lovexiaopeng/jimu/api/TaskController.java':['public record SubmitRequest','@PostMapping("/api/tasks")'],
  'src/main/resources/db/migration/V21__captured_session_event_read_provenance.sql':['PENDING','PURGED'],
 };
 for(const [source,needles] of Object.entries(localAnchors)){
  const rows=fs.readFileSync(path.join(repo,source),'utf8').split(/\r?\n/);lines.push(`## Jimu ${source}`,'');
  for(const needle of needles){const i=rows.findIndex(x=>x.includes(needle));if(i<0)throw Error('Missing local anchor '+needle);lines.push(`- L${i+1}: \`${rows[i].trim()}\``);}lines.push('');
 }
 write('evidence/source-map.md',lines.join('\n'));
 const remote=JSON.parse(await get('https://api.github.com/repos/Love-XiaoPeng/Jimu/branches/master'));
 write('evidence/actual-master.json',{checkedAt:new Date().toISOString(),sha:remote.commit.sha,tree:remote.commit.commit.tree.sha,local:git('rev-parse','HEAD'),localTree:git('rev-parse','HEAD^{tree}'),transport:'GitHub HTTPS API; git fetch failed with connect timeout. Existing accepted b31bcd8 objects imported read-only from prior project checkout; signed merge commit reconstructed and exact SHA verified.'});
 if(remote.commit.sha!==git('rev-parse','HEAD'))throw Error('master changed');
 console.log(`PASS ${sources.length} upstream blobs/MIT and ${baseline.length} baseline files; actual master/tree agree`);
})().catch(e=>{console.error(e);process.exitCode=1;});
