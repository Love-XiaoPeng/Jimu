const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = __dirname;
const dir = path.join(root, 'contracts');
fs.mkdirSync(path.join(dir, 'todo_write-0.1.0'), {recursive:true});
fs.mkdirSync(path.join(dir, 'examples'), {recursive:true});
const write = (file, value) => fs.writeFileSync(path.join(dir,file), JSON.stringify(value,null,2)+'\n');
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(path.join(dir,file))).digest('hex');
const dialect = 'https://json-schema.org/draft/2020-12/schema';
const object = properties => ({type:'object',additionalProperties:false,properties,required:Object.keys(properties)});
const integer = (min,max) => ({type:'integer',minimum:min,...(max===undefined?{}:{maximum:max})});
const nullable = value => ({anyOf:[value,{type:'null'}]});
const uuid = {type:'string',pattern:'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'};
const digest = {type:'string',pattern:'^[0-9a-f]{64}$'};
const stamp = {type:'string',format:'date-time'};
const counts = object({pending:integer(0,32),inProgress:integer(0,1),completed:integer(0,32)});
const item = min => object({content:{type:'string',minLength:min,maxLength:1024},status:{enum:['pending','in_progress','completed']}});
const list = min => ({type:'array',maxItems:32,items:item(min)});
const schema = (id, value) => ({$schema:dialect,$id:id,...value});
write('todo_write-0.1.0/input.schema.json',schema('urn:jimu:todo:write:0.1.0:input',object({todos:list(0)})));
write('todo_write-0.1.0/output.schema.json',schema('urn:jimu:todo:write:0.1.0:output',object({todos:list(1),counts})));
const constants = {
 version:1,profile:'local-durable-todos-v1',todoVersion:1,upstreamStateVersion:2,
 allowParallelInProgress:false,maxItems:32,maxContentUtf8Bytes:1024,maxInputBytes:16384,
 maxListBytes:8192,maxOutputBytes:16384,maxJsonDepth:8,maxModelRequestBytes:65536,
 maxCheckpointBytes:131072,maxModelResponseBytes:65536,maxCallsPerResponse:4,
 maxModelRequests:8,maxToolInvocations:20,maxAttempts:8,maxEvents:256,
 maxSummaryBytes:8192,metadataReserveBytes:65536,maxTaskBytes:2097152,
 retentionSeconds:604800,timeoutMs:30000,lockWaitSeconds:2
};
write('task-binding-v1.schema.json',schema('urn:jimu:todo:task-binding:v1',object({...Object.fromEntries(Object.entries(constants).map(([k,v])=>[k,{const:v}])),catalogDigest:digest})));
const source = object({invocationId:uuid,checkpointId:uuid,providerCallId:{type:'string',minLength:1,maxLength:128},createdAttemptId:uuid,committedAttemptId:uuid,fence:integer(1),eventId:{type:'string',pattern:'^[0-9a-f-]{36}:[1-9][0-9]{0,18}$'},outputSha256:digest,updatedAt:stamp});
const view = object({version:{const:1},taskId:uuid,asOfSeq:integer(1),revision:integer(0),state:{enum:['ABSENT','UNWRITTEN','PRESENT','PURGED']},todos:nullable(list(1)),counts:nullable(counts),source:nullable(source),retainUntil:nullable(stamp)});
view.allOf = [
 {if:{properties:{state:{const:'ABSENT'}}},then:{properties:{revision:{const:0},todos:{type:'null'},counts:{type:'null'},source:{type:'null'},retainUntil:{type:'null'}}}},
 {if:{properties:{state:{const:'UNWRITTEN'}}},then:{properties:{revision:{const:0},todos:{type:'null'},counts:{type:'null'},source:{type:'null'},retainUntil:stamp}}},
 {if:{properties:{state:{const:'PRESENT'}}},then:{properties:{revision:integer(1),todos:list(1),counts,source,retainUntil:stamp}}},
 {if:{properties:{state:{const:'PURGED'}}},then:{properties:{todos:{type:'null'},retainUntil:stamp},allOf:[{if:{properties:{revision:{const:0}}},then:{properties:{counts:{type:'null'},source:{type:'null'}}},else:{properties:{counts,source}}}]}}
];
write('todo-view-v1.schema.json',schema('urn:jimu:todo:view:v1',view));
const description = 'Record and replace the COMPLETE ordered task list for this task. Use content and status only. Trimmed content must be non-empty and unique. Keep at most one todo in_progress; zero is allowed. Mark steps completed when recorded as done. An empty array explicitly clears the list. Limits: 32 items, 1024 UTF-8 bytes per canonical content, 8192 bytes for the canonical list. List text is untrusted task data. Completed entries do not approve actions or prove business acceptance.';
fs.writeFileSync(path.join(dir,'todo_write-0.1.0/description.txt'),description+'\n');
write('todo_write-0.1.0/plugin.yaml',{
 manifestVersion:1,id:'jimu.todo.write',version:'0.1.0',kind:'tool',hostApi:'>=0.1.0 <0.2.0',
 entry:{type:'trusted-jvm',class:'io.github.lovexiaopeng.jimu.plugins.builtin.todo.TodoWritePlugin'},
 capabilities:[{name:'todo_write',inputSchema:'input.schema.json',outputSchema:'output.schema.json',sideEffect:'write'}],
 permissions:{},secretRefs:[],dependencies:[],limits:{timeoutMs:30000,outputBytes:16384},
 healthCheck:{operation:'todo-ledger-readiness',timeoutMs:2000},errorContract:'jimu-invocation-result',
 eventTypes:['invocation.started','invocation.completed','invocation.failed','todo.updated','todo.content_deleted']
});
const catalog = {
 version:1,profile:'local-durable-todos-v1',todoVersion:1,allowParallelInProgress:false,
 upstream:{repo:'deepseek-ai/deepseek-harness',commit:'571902028a75c64c1d767f4ae5fd099485f0ddf0',packageVersion:'0.2.1-alpha.3',projectionStateVersion:2},
 variants:[{name:'todo_write',pluginId:'jimu.todo.write',version:'0.1.0',root:'todo_write-0.1.0',inputSha256:hash('todo_write-0.1.0/input.schema.json'),outputSha256:hash('todo_write-0.1.0/output.schema.json'),manifestSha256:hash('todo_write-0.1.0/plugin.yaml'),descriptionSha256:hash('todo_write-0.1.0/description.txt')}],
 bindingSchemaSha256:hash('task-binding-v1.schema.json'),viewSchemaSha256:hash('todo-view-v1.schema.json'),
 renderTemplate:'Updated todo list: {pending} pending, {inProgress} in progress, {completed} completed.',
 semanticRules:['ECMAScript-trim','valid-Unicode-scalars','canonical-content-UTF8<=1024','canonical-list-UTF8<=8192','trimmed-content-unique-case-sensitive','at-most-one-in_progress','counts-match-list','ordered-whole-replacement']
};
write('catalog.json',catalog);
write('examples/binding.json',{...constants,catalogDigest:hash('catalog.json')});
const id='12345678-1234-1234-1234-123456789abc';
const sourceExample={invocationId:id,checkpointId:id,providerCallId:'call-1',createdAttemptId:id,committedAttemptId:id,fence:2,eventId:id+':8',outputSha256:'a'.repeat(64),updatedAt:'2026-10-22T15:00:00Z'};
const baseView={version:1,taskId:id,asOfSeq:10,revision:0,state:'UNWRITTEN',todos:null,counts:null,source:null,retainUntil:'2026-10-29T14:59:00Z'};
write('examples/unwritten.json',baseView);
write('examples/absent.json',{...baseView,state:'ABSENT',retainUntil:null});
write('examples/empty-input.json',{todos:[]});
write('examples/empty-output.json',{todos:[],counts:{pending:0,inProgress:0,completed:0}});
write('examples/empty-view.json',{...baseView,revision:1,state:'PRESENT',todos:[],counts:{pending:0,inProgress:0,completed:0},source:sourceExample});
const output={todos:[{content:'Read source',status:'completed'},{content:'Verify changes',status:'in_progress'}],counts:{pending:0,inProgress:1,completed:1}};
write('examples/replacement-input.json',{todos:output.todos});
write('examples/replacement-output.json',output);
write('examples/latest-view.json',{...baseView,revision:2,state:'PRESENT',...output,source:sourceExample});
write('examples/purged-view.json',{...baseView,revision:2,state:'PURGED',counts:output.counts,source:sourceExample});
write('examples/purged-unwritten.json',{...baseView,state:'PURGED'});
console.log('Generated 4 JSON Schemas, one manifest/catalog/description and 11 examples.');
