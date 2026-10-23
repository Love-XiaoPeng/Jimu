// No product mutation. Resolve immutable upstream files and local baseline evidence.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=__dirname,sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const write=(p,v)=>{p=path.join(root,p);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v,null,2)+'\n');};
const git=(...a)=>cp.execFileSync('git',a,{encoding:'utf8'}).trim();
const revision='571902028a75c64c1d767f4ae5fd099485f0ddf0';
const previous=JSON.parse(fs.readFileSync(path.resolve(root,'../../XIAO-53/architecture/evidence/upstream-sources.json')));
const added=['packages/session-query/session-query/src/tracing.ts','packages/session-query/session-query/tests/tracing.spec.ts',
 'packages/core/session/src/surface.ts','packages/core/session/tests/surface.spec.ts',
 'packages/session/session-format-catalog/src/message-projections.ts'];
(async()=>{
 const response=await fetch(`https://api.github.com/repos/deepseek-ai/deepseek-harness/git/trees/${revision}?recursive=1`,{signal:AbortSignal.timeout(20000)});
 if(!response.ok)throw Error(response.status);const tree=await response.json();if(tree.sha!==revision||tree.truncated)throw Error('tree');
 const results=await Promise.allSettled([...new Set([...previous.sources.map(x=>x.source),...added])].map(async source=>{
  const entry=tree.tree.find(x=>x.path===source&&x.type==='blob');if(!entry)throw Error(source);
  const url=`https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/${revision}/${source}`;
  const res=await fetch(url,{signal:AbortSignal.timeout(20000)});if(!res.ok)throw Error(res.status+' '+source);
  const body=Buffer.from(await res.arrayBuffer());const gitBlob=crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex');if(entry.sha!==gitBlob)throw Error('blob '+source);
  const prior=previous.sources.find(x=>x.source===source);let local;
  if(prior){
   if(sha(body)!==prior.sha256)throw Error('historical mismatch');
   local=path.relative(root,path.resolve(root,'../../XIAO-53/architecture',prior.local)).replaceAll('\\','/');
   if(sha(fs.readFileSync(path.resolve(root,local)))!==sha(body))throw Error('local mismatch');
   const duplicate=path.join(root,'evidence/upstream/'+source.replaceAll('/','__'));
   // Delete only this run's verified duplicate; never a historical capture.
   if(fs.existsSync(duplicate)&&sha(fs.readFileSync(duplicate))===sha(body))fs.unlinkSync(duplicate);
  }else{local='evidence/upstream/'+source.replaceAll('/','__');fs.mkdirSync(path.dirname(path.join(root,local)),{recursive:true});fs.writeFileSync(path.join(root,local),body);}
  return {source,url,local,gitBlob,bytes:body.length,sha256:sha(body)};
 }));
 const failures=results.filter(x=>x.status==='rejected').map(x=>String(x.reason));
 write('evidence/upstream-sources.json',{revision,treeTruncated:false,checkedAt:new Date().toISOString(),sources:results.filter(x=>x.status==='fulfilled').map(x=>x.value),failures});
 if(failures.length)throw Error(failures.join('\n'));
 const sources=[...git('ls-files','src/main/resources/db/migration','src/main/resources/session').split('\n'),
  ...['JdbcLedger','JdbcSessionRecords'].map(x=>'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/'+x+'.java'),
  ...['SessionContracts','SessionSearchContracts','SessionSearchMatcher','SessionSearchBodyFilter','SessionQueryDeadline'].map(x=>'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/session/'+x+'.java'),
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent.java',
  'src/main/java/io/github/lovexiaopeng/jimu/api/SessionHistoryController.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/model/ModelProfiles.java',
  'src/main/java/io/github/lovexiaopeng/jimu/kernel/task/api/SessionEventLedger.java',
  'src/main/java/io/github/lovexiaopeng/jimu/bootstrap/DurableConfiguration.java',
  'tools/evaluation/application_grants.py','tools/session_cleanup_verify.py','tools/session_connection_verify.py'];
 write('evidence/jimu-sources.json',{commit:git('rev-parse','HEAD'),tree:git('rev-parse','HEAD^{tree}'),
  actualMaster:'3e5f77549562691634c0a166d8b944ecdcdf1bb8',branch:git('branch','--show-current'),
  sources:sources.map(source=>{const b=fs.readFileSync(source);return {source,bytes:b.length,sha256:sha(b),gitBlob:git('rev-parse',`HEAD:${source}`)};})});
 const lines=['# 原码证据定位','',`固定上游 ${revision}；下列行号均指向manifest中的不可变原文件。`,''];
 const anchors={
  'packages/experimental/tool-session-query/src/index.ts':['session_event_trace','timeoutMs: resolved.searchTimeoutMs'],
  'packages/experimental/tool-session-query/src/operations.ts':['executeEventTrace','traceEvent({','lastStepStartSeq'],
  'packages/experimental/tool-session-query/src/presentation.ts':['function formatEventTrace','Replacement chain:'],
  'packages/experimental/tool-session-query/src/workspace-access.ts':['callerOf','stateOf','authorizeTarget','assertObservedTargetAuthorized'],
  'packages/session-query/session-query/src/tracing.ts':['export function traceEvent','replacementChain','function analyzeEventLog','function eventSources'],
  'packages/core/session/src/surface.ts':['function surfaceOpOf','function assertSourceEventReferences','export function foldSurface'],
  'packages/core/agent-loop/src/index.ts':['turnBoundaryProjectionDefinition','lastStepStartSeq: event.seq'],
  'packages/session-query/session-query/src/index.ts':['async traceEvent'],
  'packages/session-query/session-query/src/corpus.ts':['async load'],
  'packages/session-query/session-query/tests/tracing.spec.ts':['returns direct replacement','checks target existence','non-surface sources'],
  'packages/experimental/tool-session-query/tests/tool-session-query.spec.ts':['Replacement chain: 1','Events cited directly as sources: none'],
 };
 for(const [source,needles] of Object.entries(anchors)){
  const entry=results.find(x=>x.status==='fulfilled'&&x.value.source===source)?.value;if(!entry)throw Error('anchor source');
  const rows=fs.readFileSync(path.resolve(root,entry.local),'utf8').split(/\r?\n/);
  lines.push(`## ${source}`,'',`Git blob ${entry.gitBlob}；本地证据 \`${entry.local}\`。`,'');
  for(const needle of needles){const index=rows.findIndex(x=>x.includes(needle));if(index<0)throw Error('anchor '+needle);lines.push(`- L${index+1}：\`${rows[index].trim()}\``);}
  lines.push('');
 }
 lines.push('## Jimu基线接入点','');
 for(const [source,needles] of Object.entries({
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcSessionRecords.java':['private static final Set<String> BODIES','private static final List<String> TABLES','Read read(','JsonNode verify(','Map<String, Object> receipt(','void purge('],
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java':['public String readOrResult','public String searchOrResult','sessions.receipt(committed)'],
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/session/SessionSearchBodyFilter.java':['class SessionSearchBodyFilter'],
  'src/main/java/io/github/lovexiaopeng/jimu/api/SessionHistoryController.java':['class SessionHistoryController'],
 })){
  const rows=fs.readFileSync(source,'utf8').split(/\r?\n/);lines.push(`### ${source}`,'');
  for(const n of needles){const i=rows.findIndex(x=>x.includes(n));if(i<0)throw Error('Jimu anchor '+n);lines.push(`- L${i+1}：\`${rows[i].trim()}\``);}lines.push('');
 }
 fs.writeFileSync(path.join(root,'evidence/source-map.md'),lines.join('\n'));
 console.log(`Verified ${results.length} upstream blobs and ${sources.length} baseline files.`);
})().catch(e=>{console.error(e);process.exitCode=1;});
