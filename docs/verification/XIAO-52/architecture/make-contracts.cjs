// Architecture artifacts only. Run from repo root; no runtime dependency is introduced.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.join(__dirname, 'contracts');
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const obj = properties => ({type:'object', additionalProperties:false, properties, required:Object.keys(properties)});
const integer = (minimum, maximum) => ({type:'integer', minimum, maximum});
const text = maxLength => ({type:'string', maxLength});
const nullable = value => ({anyOf:[value,{type:'null'}]});
const uuid = {type:'string', pattern:'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'};
const digest = {type:'string',pattern:'^[0-9a-f]{64}$'};
const seq = integer(0,127);
const types = ['user.message','step.started','model.response','session.read','turn.ended'];
const type = {enum:types};
const schema = (name, value) => ({$schema:'https://json-schema.org/draft/2020-12/schema',
  $id:`urn:jimu:session-event-read:0.1.0:${name}`, ...value});
function write(name, value) {
  const location = path.join(root, name);
  fs.mkdirSync(path.dirname(location), {recursive:true});
  fs.writeFileSync(location, typeof value === 'string' ? value : JSON.stringify(value,null,2)+'\n');
}
const input = obj({session_id:uuid,seq:integer(0,9007199254740991),before:integer(0,4),after:integer(0,4)});
input.required=['seq'];
write('session_event_read-0.1.0/input.schema.json',schema('input',input));
const call = obj({id:{type:'string',minLength:1,maxLength:128,pattern:'^[A-Za-z0-9_-]+$'},
  type:{const:'function'},name:{const:'session_event_read'},arguments:text(1024)});
const source = obj({kind:{enum:['request','step','model','invocation','task']},
  step_id:nullable(uuid),invocation_id:nullable(uuid)});
const event = obj({version:{const:1},session_id:uuid,seq,type,time:{type:'string',format:'date-time'},
  source,content_policy:{const:'public-v1'},redacted:{type:'boolean'},data:{type:'object'}});
event.allOf = [
  ['user.message',obj({content:text(8192)}),'request'],
  ['step.started',obj({ordinal:integer(1,8)}),'step'],
  ['model.response',obj({content:text(16384),calls:{type:'array',maxItems:4,items:call}}),'model'],
  ['session.read',obj({seq:integer(0,9007199254740991),before:integer(0,4),after:integer(0,4),upper_seq:seq,
    outcome:{enum:['SUCCEEDED','FAILED']},code:nullable({type:'string',minLength:1,maxLength:64}),
    output_sha256:nullable(digest),output_bytes:integer(0,32768)}),'invocation'],
  ['turn.ended',obj({state:{enum:['SUCCEEDED','FAILED','CANCELLED','NEEDS_RECONCILIATION']},
    code:nullable({type:'string',maxLength:64})}),'task'],
].map(([kind,data,sourceKind])=>({if:{properties:{type:{const:kind}}},
  then:{properties:{data,source:{properties:{kind:{const:sourceKind}}}}}}));
write('event-v1.schema.json',schema('event-v1',event));
const neighbor = obj({seq,type,time:{type:'string',format:'date-time'},source,redacted:{type:'boolean'},
  text:text(256),summary_truncated:{type:'boolean'}});
const output = obj({version:{const:1},session_id:uuid,history_version:{const:1},
  content_policy:{const:'public-v1'},boundary:{enum:['pre-step','owner-snapshot']},upper_seq:seq,
  window:obj({from_seq:seq,to_seq:seq,before_requested:integer(0,4),after_requested:integer(0,4)}),
  target:event,before:{type:'array',maxItems:4,items:neighbor},after:{type:'array',maxItems:4,items:neighbor}});
write('session_event_read-0.1.0/output.schema.json',schema('output',output));
const recent = {type:'array',maxItems:8,items:obj({seq,type})};
const context = obj({kind:{const:'session_event_context'},version:{const:1},session_id:uuid,
  upper_seq:seq,user_message_seq:{const:0},recent});
write('context-v1.schema.json',schema('context-v1',context));
write('owner-history-v1.schema.json',schema('owner-history-v1',obj({version:{const:1},session_id:uuid,
  content_state:{enum:['PRESENT','PURGED']},upper_seq:seq,recent,user_message_seq:{const:0},
  as_of_sse_seq:integer(1,256),retain_until:{type:'string',format:'date-time'},readable:{type:'boolean'}})));
const description='Read one complete stored public Jimu event by its zero-based history seq, with up to four preceding and four following summaries. Omit session_id for this task; other task sessions are unavailable. Use seq references from session_event_context. The visible history ends before this model step. Events use the public-v1 redaction policy; read receipts contain result digests, never nested result bodies. This is the Jimu native public log, not an upstream SessionEvent or the SSE sequence.\n';
write('session_event_read-0.1.0/description.txt',description);
write('session_event_read-0.1.0/plugin.yaml',{
  manifestVersion:1,id:'jimu.session.event_read',version:'0.1.0',kind:'tool',hostApi:'>=0.1.0 <0.2.0',
  entry:{type:'trusted-jvm',class:'io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEventReadPlugin'},
  capabilities:[{name:'session_event_read',inputSchema:'input.schema.json',outputSchema:'output.schema.json',sideEffect:'read'}],
  permissions:{},secretRefs:[],dependencies:[],limits:{timeoutMs:2000,outputBytes:32768},
  healthCheck:{operation:'session-event-ledger-readiness',timeoutMs:2000},errorContract:'jimu-invocation-result',
  eventTypes:['invocation.started','invocation.completed','invocation.failed','session_history.changed','session_history.content_deleted']});
const pin = {};
for(const [key,file] of Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',
  manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'})) {
  pin[key]=hash(fs.readFileSync(path.join(root,'session_event_read-0.1.0',file)));
}
const catalog={version:1,profile:'local-session-event-read-v1',historyVersion:1,contentPolicy:'public-v1',
  tools:[{name:'session_event_read',pluginId:'jimu.session.event_read',version:'0.1.0',...pin}],
  upstream:{revision:'571902028a75c64c1d767f4ae5fd099485f0ddf0',
    entryBlob:'61e18cc8f57826d672ba95b6e1fc2dd6d93e8274',license:'MIT'},
  schemas:Object.fromEntries(['event-v1.schema.json','context-v1.schema.json','owner-history-v1.schema.json']
    .map(file=>[file,hash(fs.readFileSync(path.join(root,file)))])),
  compatibility:'implementation-contract.md section 2'};
write('catalog.json',catalog);
const binding={version:1,profile:'local-session-event-read-v1',historyVersion:1,contentPolicy:'public-v1',
  allowedSessions:'current-task',boundary:'pre-step',capabilities:['session_event_read'],
  maxInputBytes:1024,maxJsonDepth:4,maxWindowSide:4,maxNeighbors:8,maxEventBytes:16384,
  maxEvents:128,maxSummaryCodePoints:256,maxSummaryTextBytes:1024,maxSummaryJsonBytes:1536,
  maxOutputBytes:32768,maxReadRows:9,maxReadScratchBytes:1048576,maxContextBytes:4096,maxRecent:8,
  timeoutMs:2000,lockWaitSeconds:1,maxModelRequestBytes:65536,maxCheckpointBytes:131072,
  maxModelResponseBytes:16384,maxCallsPerResponse:4,maxModelRequests:8,maxToolInvocations:20,
  maxAttempts:8,maxReliableEvents:256,maxSummaryBytes:8192,maxDatabaseBodyBytes:7340032,
  maxMetadataBytes:65536,reservedTaskBytes:8388608,retentionSeconds:604800,
  catalogDigest:hash(fs.readFileSync(path.join(root,'catalog.json')))};
write('examples/binding.json',binding);
write('task-binding-v1.schema.json',schema('task-binding-v1',obj(Object.fromEntries(
  Object.entries(binding).map(([key,value])=>[key,{const:value}])))));
const id='00000000-0000-4000-8000-000000000052';
const step='00000000-0000-4000-8000-000000000053';
const invocation='00000000-0000-4000-8000-000000000054';
const at='2026-10-23T00:00:00.000000Z';
const goal={version:1,session_id:id,seq:0,type:'user.message',time:at,
  source:{kind:'request',step_id:null,invocation_id:null},content_policy:'public-v1',redacted:false,
  data:{content:'核查早先消息：积木支持中文与 Unicode 🧱。'}};
const start={...goal,seq:1,type:'step.started',source:{kind:'step',step_id:step,invocation_id:null},data:{ordinal:1}};
const model={...goal,seq:2,type:'model.response',source:{kind:'model',step_id:step,invocation_id:null},
  data:{content:'先确认用户提出的核查内容。',calls:[{id:'read-1',type:'function',name:'session_event_read',arguments:'{"seq":0}'}]}};
const out={version:1,session_id:id,history_version:1,content_policy:'public-v1',boundary:'pre-step',upper_seq:2,
  window:{from_seq:0,to_seq:2,before_requested:1,after_requested:1},target:start,
  before:[{seq:0,type:goal.type,time:at,source:goal.source,redacted:false,text:goal.data.content,summary_truncated:false}],
  after:[{seq:2,type:model.type,time:at,source:model.source,redacted:false,text:model.data.content,summary_truncated:false}]};
out.upper_seq=3;
write('examples/input.json',{seq:1,before:1,after:1});
write('examples/current-input.json',{session_id:id,seq:0});
write('examples/user-event.json',goal);
write('examples/response-event.json',model);
write('examples/read-output.json',out);
const firstOutput={...out,upper_seq:0,window:{from_seq:0,to_seq:0,before_requested:0,after_requested:0},
  target:goal,before:[],after:[]};
write('examples/first-read-output.json',firstOutput);
write('examples/first-read-output.wire.json',JSON.stringify(firstOutput));
const receipt={...goal,seq:3,type:'session.read',source:{kind:'invocation',step_id:step,invocation_id:invocation},
  data:{seq:0,before:0,after:0,upper_seq:0,outcome:'SUCCEEDED',code:null,
    output_sha256:hash(JSON.stringify(firstOutput)),output_bytes:Buffer.byteLength(JSON.stringify(firstOutput))}};
write('examples/read-receipt.json',receipt);
const refs=[{seq:0,type:goal.type},{seq:1,type:start.type},{seq:2,type:model.type},{seq:3,type:receipt.type}];
write('examples/context.json',{kind:'session_event_context',version:1,session_id:id,upper_seq:3,user_message_seq:0,recent:refs});
write('examples/owner-history.json',{version:1,session_id:id,content_state:'PRESENT',upper_seq:3,user_message_seq:0,
  recent:refs,as_of_sse_seq:8,retain_until:'2026-10-30T00:00:00.000000Z',readable:true});
write('examples/trace.json',{scope:'Illustrative new-profile facts, not a product test result',
  step1:{upper_seq:0,events:[goal,start,model,receipt],result:firstOutput},
  step2:{context_upper_seq:3,input:{seq:1,before:1,after:1},result:out}});
console.log('Wrote versioned event/read/context/owner/binding schemas, manifest, catalog and examples.');
