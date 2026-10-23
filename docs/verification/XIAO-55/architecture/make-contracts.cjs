// Normative candidates only. No product registration or runtime code generation.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.join(__dirname,'contracts'),old=path.resolve(__dirname,'../../../../src/main/resources/session/v3');
const clone=x=>JSON.parse(JSON.stringify(x));
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const load=p=>JSON.parse(fs.readFileSync(path.join(old,p)));
const obj=p=>({type:'object',additionalProperties:false,properties:p,required:Object.keys(p)});
const integer=(minimum,maximum)=>({type:'integer',minimum,maximum});
const text=maxLength=>({type:'string',maxLength});
const uuid={type:'string',pattern:'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'};
const digest={type:'string',pattern:'^[0-9a-f]{64}$'};
const timestamp={type:'string',minLength:20,maxLength:32,pattern:'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$',description:'Host additionally validates Gregorian dates, offsets, inclusive UTC microsecond ranges and ordering.'};
const seq=integer(0,127),safeSeq=integer(0,9007199254740991),types=['user.message','step.started','model.response','session.read','turn.ended'];
const list=(items,maxItems,minItems=1)=>({type:'array',minItems,maxItems,uniqueItems:true,items});
const schema=(name,value)=>({$schema:'https://json-schema.org/draft/2020-12/schema',$id:'urn:jimu:session-search:1:'+name,...value});
function write(p,v){p=path.join(root,p);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,typeof v==='string'||Buffer.isBuffer(v)?v:JSON.stringify(v,null,2)+'\n');}
const input=obj({query:{type:'string',minLength:1,maxLength:256,pattern:'^[^\\u0000]*$',description:'Required nonblank public-content-literal-v1 query. Before and after ECMAScript whitespace normalization <=256 code points/1024 UTF-8 bytes; no regex, FTS, casefold or Unicode normalization.'},
 session_ids:list(uuid,8),created_at_from:timestamp,created_at_to:timestamp,parent_session_ids:list(uuid,8),include_root_sessions:{type:'boolean'},
 availability:list({enum:['live','persisted']},2),event_seq_from:safeSeq,event_seq_to:safeSeq,event_time_from:timestamp,event_time_to:timestamp,
 event_types:list({enum:types},5),event_surfaces:list({enum:['current','shadowed','log-only']},3)});
input.required=['query'];
for(const field of ['parent_session_ids','include_root_sessions','availability','event_surfaces'])input.properties[field].description='Upstream syntax retained. Every valid supplied value, including false, is rejected with SESSION_SEARCH_FILTER_UNSUPPORTED; trusted facts are absent. Omit this field.';
write('session_search-0.1.0/input.schema.json',schema('input',input));
const refs=list(obj({session_id:uuid,seq,event_sha256:digest}),9,0);
write('read-refs.schema.json',schema('read-refs',refs));
const event=load('event-v3.schema.json');event.$id='urn:jimu:session-history:4:event';event.properties.version={const:4};
delete event.properties.source_event_seqs;event.properties.source_event_refs=refs;
event.required=event.required.map(x=>x==='source_event_seqs'?'source_event_refs':x);
const read=event.allOf.find(x=>x.if.properties.type.const==='session.read'&&x.then.properties.data);
read.then.properties.data.properties.target_session_id=uuid;read.then.properties.data.required.push('target_session_id');
const relation=event.allOf.at(-1);relation.then.properties={source_event_refs:{minItems:1}};relation.else.properties={source_event_refs:{maxItems:0}};
const model=event.allOf.find(x=>x.if.properties.type.const==='model.response').then.properties.data.properties.calls.items;
model.properties.name={enum:['session_event_read','session_search']};model.properties.arguments=text(4096);
model.allOf=[{if:{properties:{name:{const:'session_event_read'}}},then:{properties:{arguments:text(1024)}}}];
write('event-v4.schema.json',event);
write('session_event_read-0.4.0/input.schema.json',fs.readFileSync(path.join(old,'session_event_read-0.3.0/input.schema.json')));
const readOut=load('session_event_read-0.3.0/output.schema.json');readOut.$id='urn:jimu:session-event-read:0.4.0:output';
readOut.properties.history_version={const:4};readOut.properties.boundary={enum:['pre-step','owner-snapshot','scoped-admission']};readOut.properties.target=clone(event);
write('session_event_read-0.4.0/output.schema.json',readOut);
const match=obj({seq,type:{enum:['user.message','model.response']},time:timestamp,source:clone(event.properties.source),redacted:{type:'boolean'},event_sha256:digest,snippet:text(240),left_truncated:{type:'boolean'},right_truncated:{type:'boolean'}});
const result=obj({version:{const:1},caller_session_id:uuid,scope_sha256:digest,boundary:{const:'scoped-admission'},content_policy:{const:'public-v1'},match_policy:{const:'public-content-literal-v1'},ordering:{const:'occurrences-desc,length-asc,time-desc,session-id-asc,seq-desc'},returned_count:integer(0,4),capped:{type:'boolean'},items:{type:'array',maxItems:4,items:obj({session_id:uuid,created_at:timestamp,history_version:{const:4},upper_seq:seq,best_match:match})}});
write('session_search-0.1.0/output.schema.json',schema('output',result));
const admission=obj({goal:{type:'string',minLength:1,maxLength:8192},history_session_ids:list(uuid,8,0)});
write('admission-input.schema.json',schema('admission',admission));
const taskView=obj({taskId:uuid,state:{enum:['QUEUED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED','NEEDS_RECONCILIATION']},revision:integer(0,9007199254740991),createdAt:timestamp,updatedAt:timestamp,cancelRequestedAt:{anyOf:[timestamp,{type:'null'}]},code:{anyOf:[text(64),{type:'null'}]},summary:{anyOf:[text(8192),{type:'null'}]},artifacts:{type:'array',maxItems:0}});
write('admission-output.schema.json',schema('admission-output',taskView));
const target=obj({session_id:uuid,history_version:{const:4},binding_sha256:digest,pins_sha256:digest,upper_seq:seq,created_at:timestamp,retain_until:timestamp});
const scope=obj({version:{const:1},caller_session_id:uuid,tenant_id:{type:'string',minLength:1,maxLength:64},owner_actor_id:{type:'string',minLength:1,maxLength:64},caller_binding_sha256:digest,caller_tools_sha256:digest,authority_policy:{const:'explicit-terminal-independent-v4'},created_at:timestamp,retain_until:timestamp,targets:{type:'array',maxItems:8,items:target}});
write('authority-scope.schema.json',schema('authority',scope));
const context=load('context-v3.schema.json');context.$id='urn:jimu:session-history:4:context';context.properties.version={const:4};
context.properties.scope_sha256=digest;context.properties.retain_until=timestamp;
context.properties.prior_sessions={type:'array',maxItems:8,items:obj({session_id:uuid,upper_seq:seq})};
context.required.push('scope_sha256','retain_until','prior_sessions');write('context-v4.schema.json',context);
const owner=load('owner-history-v3.schema.json');owner.$id='urn:jimu:session-history:4:owner-history';owner.properties.version={const:4};
owner.properties.scope_sha256=digest;owner.properties.scope_state={enum:['ACTIVE','REVOKED','INVALIDATED']};owner.required.push('scope_sha256','scope_state');
write('owner-history-v4.schema.json',owner);
write('session_search-0.1.0/description.txt','Search explicitly authorized prior terminal independent v4 sessions in the immutable host scope. Current session is excluded. Return one strongest public user/model event per matching session, at most four sessions; inspect capped and snippet truncation. Matching is case-sensitive literal substring after ECMAScript whitespace normalization, without regex/FTS or Unicode folding. Optional session_ids only narrows the original scope; event and creation bounds are inclusive. parent_session_ids, include_root_sessions, availability and event_surfaces are unsupported and must be omitted. Use each returned session_id and seq in session_event_read in a later model step to verify the full retained public event. IDs and search results do not grant authority.\n');
write('session_event_read-0.4.0/description.txt','Read one complete stored public-v1 history-v4 event with at most four summaries on each side. Omit session_id for current task pre-step history. Explicit prior session_id must already belong to the immutable host scope and is read at its admission cut; current authorization, pins and source retention are independently checked. Attempts/reconnects are the same session. Results are not upstream raw SessionEvent wire or SSE seq. Read receipts expose bounded actual cross-session refs, never nested tool outputs.\n');
const tools=[];
for(const [name,version,id,klass,outputBytes]of[['session_event_read','0.4.0','jimu.session.event_read','SessionEventReadPlugin',32768],['session_search','0.1.0','jimu.session.search','SessionSearchPlugin',16384]]){
 const manifest={manifestVersion:1,id,version,kind:'tool',hostApi:'>=0.1.0 <0.2.0',entry:{type:'trusted-jvm',class:'io.github.lovexiaopeng.jimu.plugins.builtin.session.'+klass},capabilities:[{name,inputSchema:'input.schema.json',outputSchema:'output.schema.json',sideEffect:'read'}],permissions:{},secretRefs:[],dependencies:[],limits:{timeoutMs:2000,outputBytes},healthCheck:{operation:'session-event-ledger-readiness',timeoutMs:2000},errorContract:'jimu-invocation-result',eventTypes:['invocation.started','invocation.completed','invocation.failed','session_history.changed','session_history.content_deleted']};
 write(`${name}-${version}/plugin.yaml`,manifest);
 tools.push({name,version,pluginId:id,...Object.fromEntries(Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'}).map(([k,f])=>[k,sha(fs.readFileSync(path.join(root,`${name}-${version}`,f)))]))});
}
const catalog={version:4,profile:'local-session-search-v4',historyVersion:4,eventVersion:4,contentPolicy:'public-v1',tools,
 upstream:{revision:'571902028a75c64c1d767f4ae5fd099485f0ddf0',entryBlob:'61e18cc8f57826d672ba95b6e1fc2dd6d93e8274',license:'MIT'},
 schemas:Object.fromEntries(['event-v4.schema.json','context-v4.schema.json','owner-history-v4.schema.json','authority-scope.schema.json','read-refs.schema.json','admission-input.schema.json','admission-output.schema.json'].map(p=>[p,sha(fs.readFileSync(path.join(root,p)))])),compatibility:'implementation-contract.md sections 1-6; ADR-0014 proposed'};
write('catalog-v4.json',catalog);
const binding=load('examples/binding.json');
for(const k of Object.keys(binding))if(k.startsWith('maxTrace')||k==='relationPolicy')delete binding[k];
Object.assign(binding,{version:4,profile:catalog.profile,historyVersion:4,eventVersion:4,allowedSessions:'current-task-plus-explicit-terminal-independent-v4',boundary:'pre-step-or-scoped-admission',capabilities:tools.map(x=>x.name),maxInputBytes:4096,maxNormalizedQueryBytes:4096,maxSearchRows:1024,maxSearchEventBytes:4194304,maxSearchReadBytes:16777216,maxSearchScratchBytes:16777216,maxSearchSourceReads:4096,maxSearchComparisons:16777216,maxSearchResults:4,maxSearchOutputBytes:16384,maxSearchOperations:2097152,maxScopeTargets:8,maxScopeBytes:4096,maxReadSourceSnapshotBytes:2048,authorityVersion:1,authorityPolicy:'explicit-terminal-independent-v4',retentionPolicy:'earliest-source-or-caller-seven-days',maxDeadlineSeconds:180,catalogDigest:sha(fs.readFileSync(path.join(root,'catalog-v4.json'))),ordering:'occurrences-desc,length-asc,time-desc,session-id-asc,seq-desc'});
write('examples/binding.json',binding);write('task-binding-v4.schema.json',schema('binding',obj(Object.fromEntries(Object.entries(binding).map(([k,v])=>[k,{const:v}])))));
const caller='00000000-0000-4000-8000-000000000055',sourceA='00000000-0000-4000-8000-000000000051',sourceB='00000000-0000-4000-8000-000000000052';
const created='2026-10-23T06:00:00Z',expires='2026-10-30T05:00:00Z';
const scopeValue={version:1,caller_session_id:caller,tenant_id:'tenant-fixture',owner_actor_id:'owner-fixture',caller_binding_sha256:sha(JSON.stringify(binding)),caller_tools_sha256:'c'.repeat(64),authority_policy:binding.authorityPolicy,created_at:created,retain_until:expires,targets:[sourceA,sourceB].map(session_id=>({session_id,history_version:4,binding_sha256:'a'.repeat(64),pins_sha256:'b'.repeat(64),upper_seq:4,created_at:'2026-10-23T05:00:00Z',retain_until:expires}))};
write('examples/scope.json',scopeValue);const scopeDigest=sha(JSON.stringify(scopeValue));
write('examples/admission.json',{goal:'检索两个明确授权的既往会话，并逐项读取核查。',history_session_ids:[sourceA,sourceB]});
write('examples/admission-empty-scope.json',{goal:'产生独立来源会话的公开事实。',history_session_ids:[]});
write('examples/admission-output.json',{taskId:caller,state:'QUEUED',revision:0,createdAt:created,updatedAt:created,cancelRequestedAt:null,code:null,summary:null,artifacts:[]});
const user=load('examples/user-event.json');user.version=4;user.session_id=sourceA;user.time='2026-10-23T05:00:00Z';delete user.source_event_seqs;user.source_event_refs=[];user.data.content='积木检索历史事实 🧱';
write('examples/user-event.json',user);
const publicRef={session_id:sourceA,seq:0,event_sha256:sha(JSON.stringify(user))};write('examples/read-refs.json',[publicRef]);
const readOutput=load('examples/read-output.json');readOutput.history_version=4;readOutput.session_id=sourceA;readOutput.upper_seq=4;readOutput.boundary='scoped-admission';readOutput.target=user;
write('examples/read-output.json',readOutput);
const receipt=load('examples/read-receipt.json');receipt.version=4;receipt.session_id=caller;receipt.time=created;delete receipt.source_event_seqs;receipt.source_event_refs=[publicRef];receipt.data.target_session_id=sourceA;receipt.data.upper_seq=4;
receipt.data.output_sha256=sha(JSON.stringify(readOutput));receipt.data.output_bytes=Buffer.byteLength(JSON.stringify(readOutput));write('examples/read-receipt.json',receipt);
const item=session_id=>({session_id,created_at:'2026-10-23T05:00:00Z',history_version:4,upper_seq:4,best_match:{seq:0,type:'user.message',time:user.time,source:user.source,redacted:false,event_sha256:session_id===sourceA?publicRef.event_sha256:sha(JSON.stringify({...user,session_id})),snippet:user.data.content,left_truncated:false,right_truncated:false}});
const resultValue={version:1,caller_session_id:caller,scope_sha256:scopeDigest,boundary:'scoped-admission',content_policy:'public-v1',match_policy:binding.matchPolicy,ordering:binding.ordering,returned_count:2,capped:false,items:[item(sourceA),item(sourceB)]};
write('examples/search-input.json',{query:'积木',session_ids:[sourceA,sourceB],event_seq_from:0,event_seq_to:9007199254740991,event_types:['user.message']});
write('examples/search-output.json',resultValue);write('examples/empty-output.json',{...resultValue,returned_count:0,items:[]});
const ctx=load('examples/context.json');ctx.version=4;ctx.session_id=caller;ctx.scope_sha256=scopeDigest;ctx.retain_until=expires;ctx.prior_sessions=scopeValue.targets.map(x=>({session_id:x.session_id,upper_seq:x.upper_seq}));write('examples/context.json',ctx);
const own=load('examples/owner-history.json');own.version=4;own.session_id=caller;own.scope_sha256=scopeDigest;own.scope_state='ACTIVE';own.retain_until=expires;write('examples/owner-history.json',own);
write('http-catalog.json',{version:1,authentication:'existing Authenticator; host Identity only',routes:[
 {method:'POST',path:'/api/session-search/tasks',input:'admission-input.schema.json',output:'admission-output.schema.json',success:202,idempotency:'existing tenant/actor/Idempotency-Key; includes canonical target list'},
 {method:'POST',path:'/api/tasks/{callerId}/session-history/session-search',input:'session_search-0.1.0/input.schema.json',output:'session_search-0.1.0/output.schema.json',success:200},
 {method:'DELETE',path:'/api/tasks/{callerId}/session-search-scope',input:null,output:null,success:204,idempotency:'irreversible owner revocation, repeat returns 204'}],errors:{401:'UNAUTHENTICATED',400:['TOOL_INPUT_INVALID','SESSION_SEARCH_FILTER_UNSUPPORTED'],403:'SESSION_SEARCH_DISABLED',404:'SESSION_SCOPE_UNAVAILABLE',409:['IDEMPOTENCY_CONFLICT','SESSION_HISTORY_CORRUPT'],429:'SESSION_HISTORY_LIMIT',503:'SESSION_QUERY_UNAVAILABLE',504:'SESSION_QUERY_TIMEOUT'},details:'implementation-contract.md sections 2, 5'});
console.log('Generated v4 normative schemas, two manifests, pinned catalog, host HTTP catalog and examples.');
