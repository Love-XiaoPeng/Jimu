const fs=require('fs'),path=require('path'),cp=require('child_process'),crypto=require('crypto');
const root=path.resolve(__dirname,'../../..'),out=path.join(root,'target/xiao60'),base='620d5e0e5bfe7b5da530f10997936466159ba34f';
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
const baseline=path.join(out,'baseline-source');fs.mkdirSync(baseline,{recursive:true});
cp.execFileSync('git',['archive',base,'--output='+path.join(out,'baseline-source.tar'),'src/main/java'],{cwd:root});
cp.execFileSync('tar',['-xf',path.join(out,'baseline-source.tar'),'-C',baseline],{cwd:root});
for(const [label,source] of [['baseline',baseline],['current',root]]) {
 cp.execFileSync('java',['tools/PersistenceTrace.java',source,path.join(out,label+'-callgraph.json'),root],{cwd:root,stdio:['ignore','pipe','pipe']});
}
const tables=JSON.parse(fs.readFileSync(path.join(__dirname,'evidence/table-tracking.json'),'utf8'));
const contracts={};
for(const row of fs.readFileSync(path.join(root,'docs/refactoring/XIAO-58/persistence.md'),'utf8').split('\n')) {
 const fields=row.split('|').map(s=>s.trim());
 if(fields.length<6)continue;
 const table=tables.find(t=>fields[1].startsWith(t.table+'（'));
 if(table) {
   const [module,type]=fields[3].split('/').map(s=>s.trim());
   contracts[table.table]={primaryKey:fields[2].split(',').map(s=>s.trim()),module,mapper:type+'Mapper',keyPolicy:'all keys for single-row CRUD; named bounded collection/CAS/aggregate operations preserve their original predicates'};
 }
}
contracts.jimu_provisioning_receipt={primaryKey:null,module:'model',mapper:'ProvisioningReceiptMapper',keyPolicy:'external schema; read-only table-exists/0/1/multiple-row semantics; never invent a key or provision'};
if(Object.keys(contracts).length!==36)throw new Error('Missing approved full-key contract');
const old=JSON.parse(fs.readFileSync(path.join(out,'baseline-callgraph.json'),'utf8'));
const current=JSON.parse(fs.readFileSync(path.join(out,'current-callgraph.json'),'utf8'));
const packageRoot='io.github.lovexiaopeng.jimu.';
const name=id=>id.slice(id.indexOf('#')+1).split('(')[0];
function owner(id) {
 const n=name(id),type=id.split('#')[0];
 if(/WorkerLoop|ZombieReaper/.test(type)) return 'WorkerService';
 if(/LedgerMaintenance/.test(type)) return 'module cleanup Services';
 if(/ReadinessController/.test(type)) return 'ReadinessService';
 if(/SessionSearchController/.test(type)) return 'SessionScopeService';
 if(/Payloads/.test(type)) return 'SnapshotCodec (no business Service)';
 if(/ModelCosts/.test(type)) return 'ModelCosts (budget domain)';
 if(/ExecutionScopeBinding|ExecutionScopes|ExecutionScopeOperator/.test(type)) return 'ExecutionScopeService';
 if(/WebFetchReconciliation/.test(type)) return 'WebFetchAssessmentService';
 if(/Mcp|mcp/i.test(n+type)) return 'McpService';
 if(/Image|image|media/.test(n+type)) return 'ImageService';
 if(/prior|scope|session|Session|readOrResult|searchOrResult|traceOrResult/.test(n+type)) return /scope|prior/i.test(n+type)?'SessionScopeService':'SessionHistoryService';
 if(/question|Question|answer|Answer|openOrResult/.test(n+type)) return 'UserQuestionService';
 if(/todo|Todo|replaceOrResult/.test(n+type)) return 'TodoService';
 if(/directory|Directory|commitDirectory|freezeDirectory/.test(n+type)) return 'WorkingDirectoryService';
 if(/search|Search/.test(n+type)) return 'NativeSearchService';
 if(/claim|renew|quiescent|processes|processStopped|registerWorker/.test(n)) return 'WorkerService';
 if(/model|Model|toolSnapshot/.test(n)) return 'ModelDispatchService';
 if(/artifact|Artifact|report|Report|stage|verified|publish|presentSucceeded|download/.test(n)) return 'ArtifactService';
 if(/admit|toolSucceeded|toolFailed|uncertain|observeWebFetch|preserveWebFetchResult/.test(n)) return 'InvocationService';
 if(/checkpoint|prepare|finish|stop/.test(n)) return 'ExecutionService';
 return 'TaskService';
}
function capture(graph,label) {
 const bindings={};
 for(const method of Object.values(graph)) for(const from of [...method.dispatch_from,...method.lambda_ports]) {
   if(!from.startsWith(packageRoot))continue;
   (bindings[from]??=[]).push(method.id);
 }
 const reachable=entry=>{const seen=new Set(),queue=[entry];while(queue.length){const id=queue.shift();if(seen.has(id)||!graph[id])continue;seen.add(id);for(const call of graph[id].calls){if(graph[call.target])queue.push(call.target);for(const bound of bindings[call.target]??[])queue.push(bound);}}return [...seen].sort();};
 const sql=[],dynamicSql=[];
 for(const method of Object.values(graph)) for(const call of method.calls) {
   if(!call.sql && /#(?:rows|one|query|queryForList|queryForObject|update|execute|prepareStatement)\(/.test(call.target??'') && /(?:persistence|org.springframework.jdbc|java.sql)/.test(method.id+' '+call.target))
     dynamicSql.push({method:method.id,file:method.file,line:call.line,target:call.target,arguments:call.arguments,conditions:call.conditions,state:'EXPRESSION_OR_FORWARDER; inspect callers and fixed whitelist, not an external SQL permission'});
 }
 for(const method of Object.values(graph)) for(const [index,call] of method.calls.entries()) if(call.sql) {
   const text=call.sql,used=tables.filter(t=>new RegExp('\\b'+t.table+'\\b','i').test(text)).map(t=>t.table);
   const item={id:sha(method.id+'@'+index+'@'+text).slice(0,20),method:method.id,file:method.file,line:call.line,sql:text,sourceArguments:call.arguments,parameters:call.arguments.slice(1).filter(arg=>!arg.endsWith('.class')),conditions:call.conditions,callbackOwners:call.callbackOwners,tables:used,lock:/FOR UPDATE|FOR SHARE|LOCK IN SHARE/i.test(text)?'explicit-row-lock':/^(UPDATE|DELETE|INSERT)/i.test(text)?'mutation-locks':'read',tenantMention:/tenant_id/i.test(text),keyContracts:used.map(table=>({table,...contracts[table],columnsMentioned:contracts[table].primaryKey?.filter(k=>new RegExp('\\b'+k+'\\b','i').test(text))??[],review:'lexical occurrence is not a WHERE/full-key proof; retain statement predicates and bound arguments'})),targetMapperStatements:used.map(table=>({mapper:contracts[table].mapper,statement:'planned_'+sha(text).slice(0,12),state:'PLANNED_NOT_LOADED'}))};
   sql.push(item);call.sql_id=item.id;
 }
 const roots=Object.values(graph).filter(m=>(m.modifiers.includes('public') && (/\/adapters\/persistence\//.test(m.file)||/\/api\/.*Controller.java$/.test(m.file)||/ExecutionScopeOperator/.test(m.file)) || /\/(WorkerLoop|LedgerMaintenance|ZombieReaper).java$/.test(m.file) && /^(tick|run|start)$/.test(name(m.id))) && !/#(Jdbc|<init>)/.test(m.id));
 const entries=roots.map(m=>{const nodes=reachable(m.id),statements=sql.filter(s=>nodes.includes(s.method));return {entry:m.id,file:m.file,line:m.line,entryKind:/\/api\//.test(m.file)?'HTTP_ADAPTER':/\/adapters\/runtime\//.test(m.file)?'RUNTIME_ENTRY':m.id.includes('$')?'NESTED_CALLBACK_OR_VALUE':'PERSISTENCE_PUBLIC_DECLARATION',targetService:owner(m.id),reachable:nodes,sql:statements.map(s=>s.id),transactionNodes:nodes.filter(id=>/#(tx|leaseTx|sessionTransaction|sessionOwnerRead|sessionSearchOwnerRead|required|requiresNew|directoryTransaction)\(/.test(id)),locks:statements.filter(s=>s.lock!=='read').map(s=>s.id),verification:owner(m.id).startsWith('Session')?['V07','V08','V09','V10','V12']:owner(m.id)==='ModelDispatchService'?['V03','V04','V06','V12']:['V03','V05','V06','V10','V12'],state:'TRACKED; MIGRATION_OPEN',notes:'Ordered per-method calls and conditions are in graph; do not flatten alternatives into one claimed lock order.'};});
 const keep=new Set(entries.flatMap(e=>e.reachable));
 const graphSubset=Object.fromEntries([...keep].sort().map(id=>[id,graph[id]]));
 return {label,entries,sql,dynamicSql,bindings,graph:graphSubset};
}
const snapshots={baseline:capture(old,'accepted source'),current:capture(current,'working implementation')};
// Fixed XML statements are actual Mapper calls, separate from the remaining JDBC literals.
const mapperSql=[];
function mapperFiles(folder){return fs.readdirSync(folder,{withFileTypes:true}).flatMap(e=>e.isDirectory()?mapperFiles(path.join(folder,e.name)):[path.join(folder,e.name)]);}
for(const file of mapperFiles(path.join(root,'src/main/resources/mapper')).filter(f=>f.endsWith('.xml'))) {
 const xml=fs.readFileSync(file,'utf8'),namespace=xml.match(/<mapper namespace="([^"]+)"/)[1];
 for(const item of xml.matchAll(/<(select|insert|update|delete)\s+id="([^"]+)"[^>]*>([\s\S]*?)<\/\1>/g)) {
  const sql=item[3].replace(/<!\[CDATA\[|\]\]>/g,'').trim();
  mapperSql.push({id:namespace+'.'+item[2],namespace,method:item[2],file:path.relative(root,file).replaceAll('\\','/'),sql,tables:tables.filter(t=>new RegExp('\\b'+t.table+'\\b','i').test(sql)).map(t=>t.table),parameters:[...sql.matchAll(/#\{(\w+)\}/g)].map(m=>m[1])});
 }
}
for(const entry of snapshots.current.entries) {
 const calls=entry.reachable.flatMap(id=>current[id]?.calls??[]);
 entry.mapperStatements=mapperSql.filter(statement=>calls.some(call=>call.target?.startsWith(statement.namespace+'#'+statement.method+'('))).map(s=>s.id);
}
const liveFiles=[...new Set(Object.values(current).map(m=>m.file))];
const sourceHashes=Object.fromEntries(liveFiles.sort().map(file=>[file,sha(fs.readFileSync(path.join(root,file)))]));
for(const file of [...new Set(mapperSql.map(s=>s.file))])sourceHashes[file]=sha(fs.readFileSync(path.join(root,file)));
const tableStates=tables.map(table=>({...table,state:'OPEN',actualBusinessMapper:mapperSql.filter(s=>s.tables.includes(table.table)).map(s=>s.id),sql:snapshots.current.sql.filter(s=>s.tables.includes(table.table)).map(s=>s.id),note:table.table==='jimu_provisioning_receipt'?'EXTERNAL_READ_ONLY; no repository DDL or ownership inferred':'B2 partial call-chain cutover; remaining literal/dynamic/module readers keep the table OPEN. XML presence alone does not close a table.'}));
const result={baselineSource:base,sourceHashes,mapperSql,boundaries:['javac resolves overloaded symbols and declared overrides; lambda bodies are attributed to their factory node','Dynamic JDK callbacks, reflection, framework binding and external execution remain explicit; this is not a runtime trace or exhaustive interleaving proof','Per-source conditions/parameters are exact; proposed Mapper statement IDs are not product Mapper coverage','Inherited lock scope, full composite keys, global maintenance scans and tenant exceptions still need statement-by-statement cutover review','Mapper XML is separately inventoried and linked to statically reachable typed method calls; BaseMapper-generated metadata operations require their runtime evidence'],tables:tableStates,...snapshots};
// Normalized symbol dictionary keeps the complete graph reviewable without repeating long Java signatures.
const symbols=[...new Set(JSON.stringify(result).match(/(?:io\.github\.lovexiaopeng\.jimu\.[^"\\]*#[^"\\]*)/g)??[])].sort();
const ids=new Map(symbols.map((s,i)=>[s,'m'+i]));
function normalize(value){if(typeof value==='string')return ids.get(value)??value;if(Array.isArray(value))return value.map(normalize);if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[ids.get(k)??k,normalize(v)]));return value;}
const compact={symbols:Object.fromEntries(symbols.map((s,i)=>['m'+i,s])),...normalize(result)};
const raw=Buffer.from(JSON.stringify(compact));
const file=path.join(__dirname,'evidence/path-tracking.json.gz');fs.writeFileSync(file,require('zlib').gzipSync(raw));
fs.writeFileSync(path.join(out,'path-tracking.json'),JSON.stringify(result,null,2)+'\n');
const csv=['baseline_entry,target_service,sql_literals,lock_sites,transaction_nodes',...snapshots.baseline.entries.map(e=>[e.entry,e.targetService,e.sql.length,e.locks.length,e.transactionNodes.join(' | ')].map(v=>'"'+String(v).replaceAll('"','""')+'"').join(','))].join('\n')+'\n';
fs.writeFileSync(path.join(__dirname,'evidence/entry-to-service.csv'),csv);
const tableCsv=['table,state,actual_mapper_statements,remaining_jdbc_literal_sites,migration',...tableStates.map(t=>[t.table,t.state,t.actualBusinessMapper.join(' | '),t.sql.length,t.migration??'EXTERNAL_READ_ONLY'].map(v=>'"'+String(v).replaceAll('"','""')+'"').join(','))].join('\n')+'\n';
fs.writeFileSync(path.join(__dirname,'evidence/b2-table-status.csv'),tableCsv);
console.log(JSON.stringify({baselineEntries:snapshots.baseline.entries.length,currentEntries:snapshots.current.entries.length,baselineSql:snapshots.baseline.sql.length,currentSql:snapshots.current.sql.length,tables:tableStates.length,rawBytes:raw.length,compressedBytes:fs.statSync(file).size}));
