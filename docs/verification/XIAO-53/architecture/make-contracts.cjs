// Normative architecture artifacts. No product implementation is generated.
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const root=path.join(__dirname,'contracts'), old=path.resolve('src/main/resources/session');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const clone=x=>JSON.parse(JSON.stringify(x));
const load=name=>JSON.parse(fs.readFileSync(path.join(old,name)));
const obj=p=>({type:'object',additionalProperties:false,properties:p,required:Object.keys(p)});
const integer=(minimum,maximum)=>({type:'integer',minimum,maximum});
const text=maxLength=>({type:'string',maxLength});
const seq=integer(0,127), safeSeq=integer(0,9007199254740991);
const uuid={type:'string',pattern:'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'};
const digest={type:'string',pattern:'^[0-9a-f]{64}$'};
const types=['user.message','step.started','model.response','session.read','turn.ended'];
const schema=(name,value)=>({$schema:'https://json-schema.org/draft/2020-12/schema',
  $id:`urn:jimu:session-event-search:0.1.0:${name}`,...value});
function write(name,value) {
  fs.mkdirSync(path.dirname(path.join(root,name)),{recursive:true});
  fs.writeFileSync(path.join(root,name),Buffer.isBuffer(value)||typeof value==='string'?value:JSON.stringify(value,null,2)+'\n');
}
const timestamp={type:'string',minLength:20,maxLength:32,
  pattern:'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$',
  description:'Strict Gregorian/offset/UTC-microsecond semantics are additionally validated by the host.'};
const input=obj({query:{type:'string',minLength:1,maxLength:256,pattern:'^[^\\u0000]*$',
    description:'Required nonblank literal query; <=1024 UTF-8 bytes before and after ECMAScript whitespace normalization. Case-sensitive public-content-literal-v1; no regex, FTS syntax or Unicode folding.'},
  session_id:uuid,seq_from:safeSeq,seq_to:safeSeq,time_from:timestamp,time_to:timestamp,
  event_types:{type:'array',minItems:1,maxItems:5,uniqueItems:true,items:{enum:types}},
  surfaces:{type:'array',minItems:1,maxItems:3,uniqueItems:true,items:{enum:['current','shadowed','log-only']},
    description:'Upstream parameter retained for explicit compatibility: every valid supplied value is rejected with SESSION_SEARCH_FILTER_UNSUPPORTED. Jimu stores no surface projection. Omit this field.'}});
input.required=['query'];
write('session_event_search-0.1.0/input.schema.json',schema('input',input));
const event=load('event-v1.schema.json');
event.$id='urn:jimu:session-history:2:event';
event.properties.version={const:2};
const model=event.allOf.find(x=>x.if.properties.type.const==='model.response');
const call=model.then.properties.data.properties.calls.items;
call.properties.name={enum:['session_event_read','session_event_search']};
call.properties.arguments=text(2048);
call.allOf=[{if:{properties:{name:{const:'session_event_read'}}},then:{properties:{arguments:text(1024)}}}];
write('event-v2.schema.json',event);
const out=load('session_event_read-0.1.0/output.schema.json');
out.$id='urn:jimu:session-event-read:0.2.0:output';
out.properties.history_version={const:2}; out.properties.target=event;
write('session_event_read-0.2.0/input.schema.json',fs.readFileSync(path.join(old,'session_event_read-0.1.0/input.schema.json')));
write('session_event_read-0.2.0/output.schema.json',out);
const hit=obj({seq,type:{enum:['user.message','model.response']},time:{type:'string',format:'date-time'},source:clone(event.properties.source),
  redacted:{type:'boolean'},event_sha256:digest,snippet:text(240),left_truncated:{type:'boolean'},right_truncated:{type:'boolean'}});
const output=obj({version:{const:1},session_id:uuid,history_version:{const:2},content_policy:{const:'public-v1'},
  boundary:{enum:['pre-step','owner-snapshot']},upper_seq:seq,match_policy:{const:'public-content-literal-v1'},
  ordering:{const:'occurrences-desc,length-asc,time-desc,seq-desc'},returned_count:integer(0,16),
  capped:{type:'boolean'},items:{type:'array',maxItems:16,items:hit}});
write('session_event_search-0.1.0/output.schema.json',schema('output',output));
for(const name of ['context','owner-history']) {
  const value=load(`${name}-v1.schema.json`); value.$id=`urn:jimu:session-history:2:${name}`;
  value.properties.version={const:2};write(`${name}-v2.schema.json`,value);
}
const searchDescription='Search earlier stored public user/model content in this task only. query is a required nonblank case-sensitive literal substring after ECMAScript whitespace normalization; there is no regex, wildcard, FTS, casefold or Unicode normalization. Optional seq/time bounds are inclusive and event_types are ORed. The current model step and all its tool slots are excluded. Only user.message and model.response content are searchable; tool arguments, receipts, outputs and metadata are excluded. surfaces is unsupported and must be omitted. Return at most 16 ranked hits, with explicit capped and snippet truncation flags; narrow query/filters when capped. Use returned actual seq in session_event_read during a later model step to verify the complete retained public event.\n';
const readDescription='Read one complete stored public history-v2 Jimu event by zero-based seq, with up to four preceding/following summaries. Omit session_id for this task; all other tasks are unavailable to the model. Use session_event_search hits or session_event_context actual seq references. The current model step and all its tool slots are excluded. Public-v1 redaction is fixed at capture; public search call query arguments can be redacted. Read receipts contain digests, never nested outputs. This is not the upstream SessionEvent wire or SSE sequence.\n';
write('session_event_search-0.1.0/description.txt',searchDescription);
write('session_event_read-0.2.0/description.txt',readDescription);
for(const [name,version,id,klass] of [
  ['session_event_search','0.1.0','jimu.session.event_search','SessionEventSearchPlugin'],
  ['session_event_read','0.2.0','jimu.session.event_read','SessionEventReadPlugin']]) {
  const manifest={manifestVersion:1,id,version,kind:'tool',hostApi:'>=0.1.0 <0.2.0',
    entry:{type:'trusted-jvm',class:`io.github.lovexiaopeng.jimu.plugins.builtin.session.${klass}`},
    capabilities:[{name,inputSchema:'input.schema.json',outputSchema:'output.schema.json',sideEffect:'read'}],
    permissions:{},secretRefs:[],dependencies:[],limits:{timeoutMs:2000,outputBytes:32768},
    healthCheck:{operation:'session-event-ledger-readiness',timeoutMs:2000},errorContract:'jimu-invocation-result',
    eventTypes:['invocation.started','invocation.completed','invocation.failed','session_history.changed','session_history.content_deleted']};
  write(`${name}-${version}/plugin.yaml`,manifest); // JSON is valid YAML; plugin.yaml is the authoritative manifest.
}
const tools=[['session_event_read','0.2.0','jimu.session.event_read'],['session_event_search','0.1.0','jimu.session.event_search']].map(([name,version,pluginId])=>({name,version,pluginId,...Object.fromEntries(Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'}).map(([key,file])=>[key,hash(fs.readFileSync(path.join(root,`${name}-${version}`,file)))]))}));
const catalog={version:2,profile:'local-session-event-search-v2',historyVersion:2,eventVersion:2,contentPolicy:'public-v1',tools,
  upstream:{revision:'571902028a75c64c1d767f4ae5fd099485f0ddf0',entryBlob:'61e18cc8f57826d672ba95b6e1fc2dd6d93e8274',license:'MIT'},
  schemas:Object.fromEntries(['event-v2.schema.json','context-v2.schema.json','owner-history-v2.schema.json'].map(file=>[file,hash(fs.readFileSync(path.join(root,file)))])),
  compatibility:'implementation-contract.md sections 1-4'};
write('catalog-v2.json',catalog);
const binding=load('examples/binding.json');
Object.assign(binding,{version:2,profile:'local-session-event-search-v2',historyVersion:2,eventVersion:2,
  capabilities:['session_event_read','session_event_search'],maxInputBytes:2048,maxReadInputBytes:1024,
  maxNormalizedQueryBytes:2048,maxQueryCodePoints:256,maxQueryBytes:1024,
  matchPolicy:'public-content-literal-v1',ordering:'occurrences-desc,length-asc,time-desc,seq-desc',
  maxSearchRows:128,maxSearchEventBytes:2097152,maxSearchReadBytes:8388608,maxSearchScratchBytes:8388608,
  maxSearchSourceReads:1024,maxSearchComparisons:8388608,maxSearchResults:16,maxSnippetCodePoints:240,
  maxSnippetTextBytes:960,maxSnippetJsonBytes:1536,maxSearchHitBytes:2048,
  catalogDigest:hash(fs.readFileSync(path.join(root,'catalog-v2.json')))});
write('examples/binding.json',binding);
write('task-binding-v2.schema.json',schema('task-binding-v2',obj(Object.fromEntries(Object.entries(binding).map(([key,value])=>[key,{const:value}])))));
const first=load('examples/first-read-output.json');first.history_version=2;first.target.version=2;
const id=first.session_id, goal=first.target;goal.data.content='积木验证 Unicode 🧱，核查历史内容。';
write('examples/read-output.json',first);
write('examples/user-event.json',goal);
const response=load('examples/response-event.json');response.version=2;
response.data.calls=[{id:'search-1',type:'function',name:'session_event_search',arguments:'{"query":"积木"}'},
  {id:'read-1',type:'function',name:'session_event_read',arguments:'{"seq":0}'}];
write('examples/model-event.json',response);
const result={version:1,session_id:id,history_version:2,content_policy:'public-v1',boundary:'pre-step',upper_seq:0,
  match_policy:binding.matchPolicy,ordering:binding.ordering,returned_count:1,capped:false,
  items:[{seq:0,type:'user.message',time:goal.time,source:goal.source,redacted:false,
    event_sha256:hash(JSON.stringify(goal)),snippet:goal.data.content,left_truncated:false,right_truncated:false}]};
write('examples/search-input.json',{query:'积木',seq_from:0,seq_to:9007199254740991,event_types:['user.message']});
write('examples/search-output.json',result);
write('examples/search-output.wire.json',JSON.stringify(result));
write('examples/empty-output.json',{...result,returned_count:0,items:[]});
for(const name of ['context','owner-history']) {
  const value=load(`examples/${name}.json`);value.version=2;write(`examples/${name}.json`,value);
}
write('examples/normalized-query.json',{query:'积木',session_id:id,seq_from:0,seq_to:9007199254740991,
  time_from:null,time_to:null,event_types:['user.message']});
console.log('Generated explicit v2 contracts, two pinned manifests, catalog, binding and examples.');
