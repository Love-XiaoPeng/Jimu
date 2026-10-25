// Materialize the reviewed predicate inventory. This is source review evidence, never a Mapper cutover.
const fs=require('fs'),path=require('path'),crypto=require('crypto'),zlib=require('zlib');
const root=path.resolve(__dirname,'../../..'),capture=JSON.parse(fs.readFileSync(path.join(root,'target/xiao60/path-tracking.json'),'utf8'));
const hash=value=>crypto.createHash('sha256').update(value).digest('hex');
const name=id=>id.slice(id.indexOf('#')+1).replace(/^<[^>]+>/,'').split('(')[0];
const family={
  FENCE:{transaction:'Caller owns required/lease admission or explicit query transaction; fenced does not open a transaction.',locks:['evaluation_budget(tenant,run), if present; admission resolves current target run','tenant_budget(tenant)','task(tenant,id) FOR UPDATE','actor_access / installation FOR SHARE only on admission','module projection locks / exact CAS / event-reservation writes'],rejection:'false permits settlement of existing effects after cancel/deadline; true requires current authorization, materials, deadline and cancellation. Scope/model proof exceptions use the exact tx allowlist.'},
  CLAIM:{transaction:'maintainQuestions runs its own bounded transactions first; candidate is read outside; each exact candidate uses required.',locks:['evaluation_budget resolved from tenant/candidate run','tenant_budget','scope source task IDs distinct+sorted, only when prior-session task','exact task candidate FOR UPDATE SKIP LOCKED; run binding rechecked','attempt / invocation / reservation / event writes'],rejection:'UNKNOWN effects are never replayed; stopped-writer receipts precede release/reclaim; queued/resume/expired branches are distinct.'},
  EXECUTION:{transaction:'LeaseUnitOfWork.admission -> original leaseTx -> required -> directoryTransaction.',locks:['FENCE(true)','checkpoint latest step read; material proof before digest/version/cleanup checks','prepare same-request replay OR previous completeness -> media/projection -> event -> final directory/session fence','finish summary checks -> question pause OR report/facts -> terminal'],rejection:'Material mismatch stops shared evaluation run and commits before throwing outside. QuestionSuspended is thrown after committed pause. All ordinary refusal paths roll back.'},
  MODEL_DISPATCH:{transaction:'admission -> leaseTx; model HTTP remains outside.',locks:['evaluation(lease)','FENCE(true), including reentrant evaluation/budget/task','tenant budget re-read (same locked row)','exact step(tenant,id,task) -> session/directory validation','cost/token reservations / model_dispatch / step RUNNING / event / final directory fence'],rejection:'Body/config/material/input -> paid run cost room -> dispatch/model/token limits -> plan -> module storage; preserve this refusal order.'},
  MODEL_RESPONSE:{transaction:'required; outcome read and public refusal after commit.',locks:['evaluation(lease,false)','FENCE(false)','exact step(tenant,id,task) and original dispatch binding','cost proof/actual usage -> constrained payload validation -> token accounting','response/intent/bindings/events in same transaction'],rejection:'Invalid constrained payload and scope/tool input rejection commit known usage + REJECTED audit without executable intents; missing reservation still rolls back with original error. Oversized question holds UNKNOWN, never invents usage.'},
  MODEL_FAILED:{transaction:'required, including deliberately committed proof-invalid tx result.',locks:['evaluation(false)','FENCE(false)','original model_dispatch(tenant,id,task) DISPATCHED proof','cost and token reservation settlement / session accounting'],rejection:'Missing receipt 404 rolls back; corrupt historical proof retains UNKNOWN and STOPPED; failed free non-session usage stays UNKNOWN per original contract.'},
  INVOCATION:{transaction:'Bypass refusals precede leaseTx/admission; then required/directory settings.',locks:['FENCE(true)','tenant budget re-read','invocation by tenant+step+slot+task','directory original-slot proof/freeze where applicable','installation by tenant+tool, snapshot pins/epoch','task+tenant charge / reservation / RUNNING / event / directory fence'],rejection:'Binding -> material -> pin -> confirmed-result digest replay -> PENDING -> budgets. Refusals roll back; RUNNING/UNKNOWN cannot execute again.'},
  TOOL_SUCCESS:{transaction:'required -> directoryTransaction; material rejection result thrown after commit.',locks:['FENCE(false)','directory only: FENCE(true), output bytes/storage','exact producer invocation(tenant,task,id)','image/web immutable result proof','RUNNING->SUCCEEDED CAS / reservation SETTLED / event / final directory fence','material postcheck; STOPPED evaluation written on mismatch'],rejection:'Confirmed tool result, accounting and STOPPED commit together on material mismatch; all other errors roll back. MCP bypass requires the explicit internal commit path.'},
  SETTLEMENT:{transaction:'required; uncertain bypass proof occurs before transaction.',locks:['FENCE(false)','invocation exact tenant+task+id CAS','reservation exact tenant+id','event/terminal and stopped-writer release as applicable'],rejection:'Failure settles only confirmed running unit; uncertainty retains HELD_UNKNOWN and needs reconciliation.'},
  WORKER_RECEIPT:{transaction:'processStopped/quiescent required per task; renew/register single autocommit statement; processes read only.',locks:['processStopped global worker/native receipt check','mark worker stopped then enumerate worker task keys','per key tenant_budget -> task -> attempt receipts -> releaseRun/reconcile'],rejection:'Worker ID is infrastructure authority supplied by supervisor, not tenant input. Renew has full task+attempt+worker+fence+live predicates.'},
  OWNER:{transaction:'required; HTTP authentication precedes Service/ledger owner access.',locks:['identity/owner visibility and current access (branch order retained in method call plan)','prior-session branches: tenant budget then ordered scope task locks as explicitly called','task/projection/fact reads; task cancel mutates under required'],rejection:'Other tenant/actor receives original visibility error; authorization hints never replace current access. get scope-refusal uses afterCompletion invalidation.'},
  QUESTION:{transaction:'required/leaseTx per entry; delivery/pause commits before outer suspension.',locks:['owner paths: tenant_budget -> visible task/current access -> exact question row FOR UPDATE when answering','execution paths: FENCE(true) -> exact invocation slot -> question row','answer revision+answer_id CAS / task next accepted seq / tool and answer receipt'],rejection:'Question owner/task/request/key bound together; id-only followup query follows previously locked exact row. Polling has no execution side effect.'},
  QUERY:{transaction:'Explicit SessionQueryContext absolute deadline, same DS/UoW; 2s is an upper bound, not refreshed at each layer.',locks:['owner query: current identity -> tenant budget / ordered source task locks as selected -> owner task','execution query: FENCE plus exact original slot -> projection/binding FOR UPDATE','history/scope projection locks -> bounded source reads -> final current-scope checks','receipt/CAS/accounting and commit deadline on same connection'],rejection:'Search/query lock, row/column access, scratch and statements charge explicit cumulative resources. Scope invalidation uses afterCompletion on rollback too, with original absolute end.'},
  DIRECTORY:{transaction:'leaseTx/directoryTransaction for execution; required for owner projection.',locks:['FENCE(true) unless explicitly settlement false','directory projection / original step+slot / pinned installation proof','immutable path binding, revision CAS, event; final deadline/fence check'],rejection:'Filesystem effects outside SQL transaction. Original canonical paths and directory revision cannot be recomputed on recovery.'},
  TODO:{transaction:'replace uses required + connection lock wait configuration; owner GET required.',locks:['FENCE / exact original response step+slot FOR UPDATE','task_todo_projection(tenant,task)','invocation PENDING CAS and projection expected revision CAS / settled unit / event / final fence'],rejection:'Response order freezes consecutive expected revisions; same content new invocation remains a new charged fact.'},
  ARTIFACT:{transaction:'leaseTx per stage/verify/publish/present; owner download required; cleanup phases separated.',locks:['FENCE(true) -> tenant budget as called','artifact full tenant/id or named task/producer/receipt collection','reservation full tenant/id / event with task sequence'],rejection:'File/FS read/write/delete stays outside DB transaction; publisher proof, file receipt and event commit together. Expired/unknown facts are not released by inference.'},
  MEDIA:{transaction:'Original leaseTx; source/normalizer/storage IO outside transaction.',locks:['reserveImage/imageSucceeded begin FENCE(true): resolved evaluation -> tenant_budget -> original scope/task; reserve only re-reads its held budget after fence','image input/object exact tenant+image/input ID with task+producer predicates','media reservation, storage actuals, invocation success and event share original REQUIRED transaction'],rejection:'Minimal media-order repair per architect decision 01a16928-9698-7670-b3fa-7856964edf27; original admission, settlement CAS and special rejection commits retained. Directed runtime evidence: media-lock-checkpoint.md.'},
  MCP:{transaction:'leaseTx for admission/list and required for response; commit joins existing required/directory transaction.',locks:['FENCE as explicitly called -> mcp server FOR SHARE','original invocation -> bounded IO ordinal/phase','result/reservation full keys -> cursor token binding -> internal tool commit'],rejection:'Current provider authorization and cursor/root/query/expiry are required; network IO outside DB transaction.'},
  SEARCH:{transaction:'leaseTx for prepare; required for host stop/storage callbacks; global host reads outside.',locks:['prepare FENCE(true) -> budget -> invocation / worker host identity -> native run/storage reservation','stop full tenant+root+task+effect+worker -> identity digest receipt INSERT IGNORE -> STOPPED','delete storage: budget -> task -> native run -> reservation once'],rejection:'Unknown process is not stopped by Future.cancel; exact host process proof is required before release. Global host scans intentionally span tenants.'},
  MAINTENANCE:{transaction:'Each candidate is read outside, then required per tenant/task; filesystem phase outside; final receipt/release in separate required.',locks:['bounded cross-tenant candidate inventory (limits retained per SQL)','derive tenant+task from row; tenant_budget -> ordered scope tasks/task','recheck terminal/content/writer/UNKNOWN/projection state','mark -> external delete -> re-lock and release once'],rejection:'Not a public owner path; no input-supplied arbitrary tenant. Failed deletion and UNKNOWN keep reservations held. Session invalidation retains original absolute end.'},
  OPERATOR:{transaction:'Explicit trusted operator bindOnce transaction; readback/assessment read only.',locks:['evaluation_budget(tenant,run) FOR UPDATE','existing execution scope','tenant_budget(tenant)','accounting/task/dispatch/reservation aggregates and proof -> immutable scope insert'],rejection:'Operator receives explicit trusted tenant/run and read-only provisioning receipt; never auto-provisions scope or paid authorization.'},
  INFRA:{transaction:'Driver/transaction callbacks use owner UoW; no independent business transaction.',locks:['connection-local timeout setting/reset only; schema/readiness reads have no business row lock'],rejection:'Only SessionQueryDeadline driver family retains ThreadLocal, finally cleared; beforeCommit checks original deadline; no-DB mode creates no SQL objects.'},
  VALUE:{transaction:'None.',locks:[],rejection:'Constructor/config/value/codec declaration, not an independent business SQL entry.'}
};
function policy(e){const id=e.entry,n=name(id),type=id.split('#')[0];
 if(/Payloads|ModelCosts/.test(type))return 'VALUE';
 if(/\$.*#(?:<[^>]+>)?(?:afterCompletion|beforeCommit|queryForObject|queryForList)/.test(id))return /ScopeInvalidation/.test(id)?'QUERY':'INFRA';
 if(n===type.split('.').at(-1).split('$').at(-1)||n==='Command'||n==='Input'||/^(.*Enabled|directoryWorkspace|imageStorage|mcpSettings|nativeSearchAvailability)$/.test(n))return 'VALUE';
 if(/ExecutionScope/.test(type)||/WebFetchReconciliation/.test(type))return 'OPERATOR';
 if(/LedgerMaintenance/.test(type))return 'MAINTENANCE';
 if(/WorkerLoop/.test(type)||n==='claim')return 'CLAIM';
 if(['checkpoint','prepare','finish'].includes(n))return 'EXECUTION';
 if(n==='dispatchModel')return 'MODEL_DISPATCH';if(n==='modelResponded')return 'MODEL_RESPONSE';if(n==='modelFailed')return 'MODEL_FAILED';
 if(n==='admit')return 'INVOCATION';if(n==='toolSucceeded')return 'TOOL_SUCCESS';if(['toolFailed','uncertain','stop'].includes(n))return 'SETTLEMENT';
 if(['quiescent','processStopped','registerWorker','processes','renew'].includes(n))return 'WORKER_RECEIPT';
 if(/cleanup|maintain/i.test(n))return 'MAINTENANCE';
 if(/SessionSearchController/.test(type)||/scope|session|Session|readOrResult|searchOrResult|traceOrResult/.test(n+type))return 'QUERY';
 if(/Question|question|answer|Answer|openOrResult/.test(n+type))return 'QUESTION';
 if(/Todo|todo|replaceOrResult/.test(n+type))return 'TODO';
 if(/Directory|directory/.test(n+type))return 'DIRECTORY';
 if(/Image|image|media/.test(n+type))return 'MEDIA';
 if(/Mcp|mcp/.test(n+type))return 'MCP';
 if(/Search|search/.test(n+type))return 'SEARCH';
 if(/artifact|Artifact|Report|report|stage|verified|publish|presentSucceeded|download/.test(n+type))return 'ARTIFACT';
 if(['modelSnapshot','toolSnapshot','evaluationFile','bindEvaluationFile'].includes(n))return 'FENCE';
 if(['check','ready'].includes(n)||/Readiness/.test(type))return 'INFRA';
 return 'OWNER';
}
function sqlReview(s){
 const args=s.parameters,placeholders=[];let quoted=false;
 for(let i=0;i<s.sql.length;i++){if(s.sql[i]==="'"){if(quoted&&s.sql[i+1]==="'"){i++;continue;}quoted=!quoted;}else if(!quoted&&s.sql[i]==='?')placeholders.push({position:i,argument:args[placeholders.length],context:s.sql.slice(Math.max(0,i-65),Math.min(s.sql.length,i+45)).replaceAll('\n',' ')});}
 if(placeholders.length!==args.length)throw Error('Unmapped bind '+s.id);
 let intent,reason;
 const m=name(s.method),sql=s.sql;
 if(/information_schema|flyway_schema_history|^SELECT (?:@@|CURRENT_TIMESTAMP|DATABASE\()|^SET SESSION/i.test(sql)){
   intent='INFRASTRUCTURE_READ_OR_CONNECTION_SETTING';reason='Schema22, fixed metadata/table-existence, DB identity/time or per-connection setting. No tenant business row selection.';
 }else if(/jimu_provisioning_receipt/.test(sql)){intent='EXTERNAL_READ_ONLY_RECEIPT';reason='Trusted tenant only; table existence then 0/1/multiple row failure contract. No DDL, key invention or write.';
 }else if(!/tenant_id\s*=\s*\?/i.test(sql)&&!/^(INSERT)/i.test(sql)){
   intent=/worker_process/.test(sql)&&!s.tables.some(t=>t!=='worker_process')?'HOST_PROCESS_KEY_OR_COLLECTION':'GLOBAL_CANDIDATE_OR_HOST_RECEIPT';
   reason='Intentional supervisor/maintenance scan or worker receipt. Candidate carries tenant+task; mutations re-enter locked tenant scope. Preserve exact LIMIT/host/worker and joined tenant predicates.';
 }else if(/^INSERT/i.test(sql)){intent='INSERT_WITH_EXPLICIT_KEY_AND_PARENT';reason='Keys and parent relationships are bound explicitly in the INSERT/SELECT list; UUID/ordinal/token/digest supplied by host under parent locks. Preserve schema unique constraints.';
 }else if(/^SELECT\s+(?:COUNT|COALESCE\(SUM|COALESCE\(MAX)/i.test(sql)||/Storage|Metadata|metadata|account/i.test(m)){
   intent='NAMED_SCOPED_AGGREGATE_OR_METADATA';reason='Not by-ID CRUD. Exact tenant/task/run filters and correlated tenant joins define the accounting set; no widening or generic selectById replacement.';
 }else if(/(?:owner_actor_id=\? AND idempotency_key=\?|step_id=\? AND slot=\?|task_id=\? AND step_id=\? AND slot=\?|fact_id=\?|token_digest=\?)/.test(sql)){
   intent='NAMED_ALTERNATE_KEY_OR_ORIGINAL_SLOT';reason='Intentional idempotency/fact/provider slot/cursor lookup under tenant+parent scope; retain all predicates and unique constraints, not merely primary ID.';
 }else{intent='EXACT_PREDICATE_OR_SCOPED_SET';reason='Review exact predicates below: full composite key for one-row operations; task/run/attempt/state collection and CAS remain named operations. A referenced key column alone is not evidence of a single-row operation.';}
 const clauses=[];for(const match of sql.matchAll(/\b(?:WHERE|ON|HAVING)\b/gi))clauses.push({at:match.index,text:sql.slice(match.index).replaceAll('\n',' ')});
 // Examine predicates, never SELECT/SET column occurrences. Complex joins and collections
 // deliberately remain named operations, with the complete SQL and bind expressions retained.
 const where=sql.match(/\bWHERE\b([\s\S]*)/i)?.[1]??'';
 const references=[...sql.matchAll(/\b(?:FROM|JOIN|UPDATE|INTO)\s+([a-z_]+)(?:\s+(?:AS\s+)?([a-z_]+))?/gi)]
   .map(m=>({table:m[1],alias:m[2]&&!/^(WHERE|SET|JOIN|ON|ORDER|LIMIT|FOR|VALUES)$/i.test(m[2])?m[2]:null}));
 const rowSelection=s.keyContracts.map(k=>{
   const ref=references.find(r=>r.table===k.table),prefix=ref?.alias?ref.alias+'\\.':'';
   const keyPredicates=(k.primaryKey??[]).map(column=>({column,clauses:[...where.matchAll(new RegExp('(?<![a-z_0-9.])'+prefix+column+'\\s*=\\s*(?:\\?|[a-z_]+\\.[a-z_]+)', 'gi'))].map(m=>m[0])}));
   const insert=sql.match(/^INSERT(?: IGNORE)? INTO\s+\w+\s*\(([^)]+)\)/i);
   return {table:k.table,primaryKey:k.primaryKey??null,keyPredicates,
     selection:insert?'EXPLICIT_INSERT_COLUMNS':keyPredicates.length&&keyPredicates.every(p=>p.clauses.length)&&!(/\bOR\b|\bUNION\b/i.test(where))?'ALL_KEY_EQUALITIES_IN_WHERE':'NAMED_SET_ALTERNATE_KEY_JOIN_OR_BRANCH',
     insertColumns:insert?.[1].split(',').map(c=>c.trim())??null,
     setScope:where,limit:sql.match(/\bLIMIT\s+[^;]+/i)?.[0]??null,
     note:'Equality inventory is not an independent uniqueness proof; use schema key, concrete bound origins, joins, parent lock and branch call plan together. Named sets are not eligible for by-ID substitution.'};
 });
 return {...s,sqlSha256:hash(sql),reviewKind:intent,rationale:reason,binds:placeholders,predicateClauses:clauses,rowSelection,
   tenantOrigin:args.filter(a=>/tenant|identity|actor|lease|scope|task|caller|run\./i.test(a)),
   lockOwner:'Follow entry policy plus ordered method call plan; row mutation locks are inherited from parent transaction, never inferred from this statement alone.',
   mapperCutover:'OPEN',runtimeCoverage:'Mapped at entry/family level; not every source statement was executed in this batch.'};
}
const replacements={
  'io.github.lovexiaopeng.jimu.api.TaskController$BudgetRequest':'io.github.lovexiaopeng.jimu.api.task.dto.BudgetRequest',
  'io.github.lovexiaopeng.jimu.api.TaskController$SubmitRequest':'io.github.lovexiaopeng.jimu.api.task.dto.SubmitRequest',
  'io.github.lovexiaopeng.jimu.api.TaskController.BudgetRequest':'io.github.lovexiaopeng.jimu.api.task.dto.BudgetRequest',
  'io.github.lovexiaopeng.jimu.api.TaskController$Envelope#Envelope':'io.github.lovexiaopeng.jimu.api.task.vo.TaskEventResponse#TaskEventResponse',
  'io.github.lovexiaopeng.jimu.adapters.persistence.Payloads':'io.github.lovexiaopeng.jimu.adapters.codec.Payloads',
  'io.github.lovexiaopeng.jimu.adapters.persistence.ModelCosts':'io.github.lovexiaopeng.jimu.kernel.budget.domain.ModelCosts',
  'io.github.lovexiaopeng.jimu.api.ReadinessController$ReadinessResponse':'io.github.lovexiaopeng.jimu.api.readiness.vo.ReadinessResponse',
  'io.github.lovexiaopeng.jimu.api.ReadinessController':'io.github.lovexiaopeng.jimu.api.readiness.controller.ReadinessController'
};
const methods=capture.current.graph;
const movedCallback={
  'io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger$1#afterCompletion(int)':'io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction$1#afterCompletion(int)',
  'io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger$1#beforeCommit(boolean)':'io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction$1#beforeCommit(boolean)'
};
const entries=capture.baseline.entries.map((e,index)=>{
 let id=e.entry;for(const [old,current]of Object.entries(replacements))id=id.replace(old,current);
 id=movedCallback[id]??id;
 const removedWrapper=/JdbcLedger\$2#(?:<T>)?(?:queryForObject|queryForList)/.test(id);
 if(removedWrapper)id='io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionJdbcTemplate#<T>query(java.lang.String,org.springframework.jdbc.core.ResultSetExtractor<T>)';
 let current=methods[id];
 if(!current){const candidates=Object.values(methods).filter(m=>m.id.split('#')[0]===id.split('#')[0]&&name(m.id)===name(id));if(candidates.length===1)current=candidates[0];}
 const p=policy(e),service=e.entry.includes('SessionSearchController')?'SessionScopeService':
   /JdbcLedger\$(?:1|2|ScopeInvalidation)#/.test(e.entry)?'SessionHistory/ScopeService query adapter':
   /#check\(/.test(e.entry)?'ReadinessService':e.targetService;
 const source=current?.id;
 const live=capture.current.entries.find(c=>c.entry===source);
 return {baselineIndex:index,baselineEntry:e.entry,currentEntry:source??null,targetService:service,policy:p,
   disposition:p==='VALUE'?'NOT_AN_INDEPENDENT_SQL_PATH':/Service$/.test(service)&&/Worker|ExecutionService|ModelDispatch|InvocationService/.test(service)?'CORE_SERVICE_IMPLEMENTED_SQL_CUTOVER_OPEN':'SOURCE_PATH_REVIEWED_MODULE_IMPLEMENTATION_DEFERRED',
   mappingNote:removedWrapper?'Old queryForObject/queryForList wrapper removed. Both now inherit Spring JDBC overload dispatch to SessionJdbcTemplate.query, then QueryResults.meter on the actual ResultSet; no SELECT-prefix rewrite.':source?'Current symbol matched; use current calls/parameters, never baseline line numbers.':'UNRESOLVED: not closure evidence.',
   currentFile:current?.file??null,sql:live?.sql??[],mapperStatements:live?.mapperStatements??[],transactionNodes:live?.transactionNodes??[],
   callPlan:current?.calls.map(c=>({target:c.target,select:c.select,arguments:c.arguments,conditions:c.conditions,callbackOwners:c.callbackOwners,sql:c.sql_id??null}))??[],
   validation:p==='VALUE'?[]:['EXECUTION','MODEL_DISPATCH','MODEL_RESPONSE','INVOCATION','TOOL_SUCCESS'].includes(p)?['CORE_WORKFLOW_DIFFERENTIAL_22']:
     ['CLAIM','MODEL_FAILED','SETTLEMENT','WORKER_RECEIPT'].includes(p)?['CORE_SETTLEMENT_DIFFERENTIAL_8','FOUNDATION_WORKER_TRANSACTION_SLICES']:
     p==='MEDIA'?['MEDIA_LOCK_DIRECTED_MYSQL']:p==='QUERY'||p==='INFRA'?['QUERY_ADAPTERS','LEGACY_READ_SEARCH_TRACE']:
     ['DIRECTORY','TODO','QUESTION'].includes(p)?['CORE_MODULE_NORMAL_HOOKS']:['SOURCE_REVIEW_ONLY_THIS_BATCH'],
   validationBoundary:'These are applicable scenario groups, not automatic executed coverage for this entry. Module normal hooks cover integration only; other module refusal/race matrices were not rerun.'};
});
if(entries.some(e=>!e.currentEntry))throw Error('Unmapped baseline entry');
const dynamic=capture.current.dynamicSql.map(s=>({...s,review:
 s.method==='io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords#updatePreparedStep(java.lang.Object)'&&s.arguments[0]==='"io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper.update"'?'FIXED_BASE_MAPPER_UPDATE; original three step projection fields and tenant/id LambdaUpdateWrapper, explicit query; runtime prepare/checkpoint and fault rollback evidence required':
 s.method==='io.github.lovexiaopeng.jimu.adapters.persistence.MapperWorkerRecords#markWorkerStopped(java.lang.String)'&&s.arguments[0]==='null'&&s.arguments[1]?.includes('WorkerProcessEntity::getId, worker')?'BASE_MAPPER_LAMBDA; fixed worker_process stopped=TRUE by sole primary key id; cross-tenant host metadata, no external SQL':
 !s.arguments.length?'NON_SQL_VALUE_ACCESSOR_OR_RECORD_COUNTER':
 s.arguments[0]==='sql'?'INTERNAL_PARAMETERIZED_FORWARDER; inspect concrete callers in this same inventory':
 /SET SESSION/.test(s.arguments[0])?'FIXED_SESSION_SETTING_PLUS_BOUNDED_INTEGER_OR_PRIOR_DRIVER_VALUE; no external SQL fragment':
 /Metadata|metadata|purge/.test(s.method)?'FIXED_TABLE/COLUMN_WHITELIST; tenant plus task/id/caller_task_id key selected by trusted table; scoped collection, not single-row CRUD':
 /reportContext/.test(s.method)?'TWO_FIXED_SELECT_VARIANTS; optional fixed plugin_version literal; tenant/task remain bound':
 /cleanupImages/.test(s.method)?'TWO_FIXED_BUDGET_UPDATE_VARIANTS; bytes and tenant remain bound':'REVIEW_REQUIRED'}));
if(dynamic.some(s=>s.review==='REVIEW_REQUIRED'))throw Error('Unreviewed dynamic SQL');
const result={baseline:capture.baselineSource,sourceHashes:capture.sourceHashes,scope:'B0 source-path/predicate/lock-boundary review with actual B2 partial Mapper links; NOT whole-table closure or independent runtime acceptance',
  policies:family,entries,statements:capture.current.sql.map(sqlReview),dynamic,
  remaining:['35 tables plus read-only receipt business Mapper cutover OPEN','MEDIA repaired prefix must be retained at actual Mapper cutover; synthetic paid seeds do not enable paid vision','Runtime evidence covers selected core/media/query races, not every interleaving or unknown historical lock holder','B2 activated and partially implemented; remaining B2 call chains continue; B3-B5 not activated','Independent review/QA not activated']};
const json=JSON.stringify(result);fs.writeFileSync(path.join(__dirname,'evidence/reviewed-paths.json.gz'),zlib.gzipSync(json));
const csv=['baseline_index,baseline_entry,current_entry,target_service,policy,disposition,sql_count,actual_mapper_statements',...entries.map(e=>[e.baselineIndex,e.baselineEntry,e.currentEntry??'',e.targetService,e.policy,e.disposition,e.sql.length,e.mapperStatements.join(' | ')].map(v=>'"'+String(v).replaceAll('"','""')+'"').join(','))].join('\n')+'\n';
fs.writeFileSync(path.join(__dirname,'evidence/reviewed-entries.csv'),csv);
fs.writeFileSync(path.join(root,'target/xiao60/reviewed-paths.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({entries:entries.length,statements:result.statements.length,dynamic:dynamic.length,binds:result.statements.reduce((n,s)=>n+s.binds.length,0),unmatched:entries.filter(e=>!e.currentEntry).map(e=>({index:e.baselineIndex,entry:e.baselineEntry}))}));
