// Architecture artifacts only. Does not modify product resources or old contracts.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=__dirname, old=path.resolve(root,'../../../../src/main/resources/session/v2');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const load=p=>JSON.parse(fs.readFileSync(path.join(old,p)));
const write=(p,v)=>{p=path.join(root,'contracts',p);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v,null,2)+'\n');};
const text=(p,v)=>{p=path.join(root,'contracts',p);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,v);};
const obj=properties=>({type:'object',additionalProperties:false,properties,required:Object.keys(properties)});
const seq={type:'integer',minimum:0,maximum:127};
const safe={type:'integer',minimum:0,maximum:9007199254740991};
const uuid={type:'string',pattern:'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'};
const digest={type:'string',pattern:'^[0-9a-f]{64}$'};
const schema=(id,body)=>({$schema:'https://json-schema.org/draft/2020-12/schema',$id:id,...body});
const names=['session_event_read','session_event_search','session_event_trace'];
const versions=['0.3.0','0.2.0','0.1.0'];
const event=load('event-v2.schema.json');
event.$id='urn:jimu:session-history:3:event';event.properties.version.const=3;
event.properties.source_event_seqs={type:'array',maxItems:9,uniqueItems:true,items:seq};
event.required.push('source_event_seqs');
const calls=event.allOf.find(x=>x.if.properties.type.const==='model.response').then.properties.data.properties.calls.items;
calls.properties.name.enum=names;
calls.allOf.push({if:{properties:{name:{const:'session_event_trace'}}},then:{properties:{arguments:{type:'string',maxLength:1024}}}});
event.allOf.push({if:{properties:{type:{const:'session.read'},data:{properties:{outcome:{const:'SUCCEEDED'}},required:['outcome']}}},then:{properties:{source_event_seqs:{minItems:1}}},else:{properties:{source_event_seqs:{maxItems:0}}}});
write('event-v3.schema.json',event);
const read=load('session_event_read-0.2.0/output.schema.json');read.$id='urn:jimu:session-event-read:0.3.0:output';
read.properties.history_version.const=3;read.properties.target=structuredClone(event);delete read.properties.target.$id;delete read.properties.target.$schema;
write('session_event_read-0.3.0/output.schema.json',read);
text('session_event_read-0.3.0/input.schema.json',fs.readFileSync(path.join(old,'session_event_read-0.2.0/input.schema.json')));
text('session_event_search-0.2.0/input.schema.json',fs.readFileSync(path.join(old,'session_event_search-0.1.0/input.schema.json')));
const search=load('session_event_search-0.1.0/output.schema.json');search.$id='urn:jimu:session-event-search:0.2.0:output';search.properties.history_version.const=3;
write('session_event_search-0.2.0/output.schema.json',search);
const target={...structuredClone(read.properties.before.items)};target.properties.event_sha256=digest;target.required.push('event_sha256');
const trace=schema('urn:jimu:session-event-trace:0.1.0:output',obj({
 version:{const:1},session_id:uuid,history_version:{const:3},content_policy:{const:'public-v1'},
 boundary:{enum:['pre-step','owner-snapshot']},upper_seq:seq,
 relation_policy:{const:'read-receipt-sources-v1'},replacement_policy:{const:'unsupported'},
 target,source_event_seqs:{type:'array',maxItems:9,uniqueItems:true,items:seq},
 derived_event_seqs:{type:'array',maxItems:20,uniqueItems:true,items:seq},
}));
write('session_event_trace-0.1.0/output.schema.json',trace);
write('session_event_trace-0.1.0/input.schema.json',schema('urn:jimu:session-event-trace:0.1.0:input',{
 ...obj({seq:safe,session_id:uuid}),required:['seq'],
}));
const descriptions=[
 'Read one full stored public-v3 event and optional neighboring summaries in the frozen authorized prefix. Successful model reads capture their actual event sources; earlier output bodies are never expanded.\n',
 'Search stored public user/model content in the frozen current-task prefix with the unchanged public-content-literal-v1 policy. Follow actual seq hits with read or trace.\n',
 'Trace direct sources and reverse references captured by successful model read receipts in this task. This is read provenance, not model causation or surface replacement. Lists are complete only for this policy and frozen prefix; use read to inspect an actual seq.\n',
];
for(let i=0;i<3;i++){
 const dir=names[i]+'-'+versions[i];
 const manifest=load('session_event_search-0.1.0/plugin.yaml');
 manifest.id='jimu.session.event_'+['read','search','trace'][i];manifest.version=versions[i];
 manifest.entry.class='io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEvent'+['Read','Search','Trace'][i]+'Plugin';
 manifest.capabilities[0].name=names[i];
 write(dir+'/plugin.yaml',manifest);text(dir+'/description.txt',descriptions[i]);
}
for(const name of ['context','owner-history']){const s=load(name+'-v2.schema.json');s.$id=s.$id.replace(':2:',':3:');s.properties.version.const=3;write(name+'-v3.schema.json',s);}
const binding=load('task-binding-v2.schema.json');binding.$id='urn:jimu:session-event-trace:0.1.0:task-binding-v3';
for(const [k,v] of Object.entries({version:3,profile:'local-session-event-trace-v3',historyVersion:3,eventVersion:3,capabilities:names,
 relationPolicy:'read-receipt-sources-v1',maxTraceInputBytes:1024,maxTraceRows:128,maxTraceEventBytes:2097152,
 maxTraceReadBytes:8388608,maxTraceScratchBytes:8388608,maxTraceSourceReads:1024,maxTraceEdges:180,
 maxTraceDirectSources:9,maxTraceDirectDerived:20,maxTraceResultNodes:30,maxTraceDepth:1,maxTraceOperations:1048576,
 maxReadSourceSnapshotBytes:1024})){
 binding.properties[k]={const:v};if(!binding.required.includes(k))binding.required.push(k);
}
write('task-binding-v3.schema.json',binding);
write('read-sources.schema.json',schema('urn:jimu:session-history:3:read-sources',{
 type:'array',maxItems:9,items:obj({seq,event_sha256:digest}),
}));
const catalog={version:3,profile:'local-session-event-trace-v3',historyVersion:3,eventVersion:3,contentPolicy:'public-v1',
 relationPolicy:'read-receipt-sources-v1',tools:names.map((name,i)=>{
 const dir=name+'-'+versions[i];const entry={name,version:versions[i],pluginId:'jimu.session.event_'+['read','search','trace'][i]};
 for(const [k,f] of Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'}))entry[k]=hash(fs.readFileSync(path.join(root,'contracts',dir,f)));
 return entry;}),schemas:{},upstream:{revision:'571902028a75c64c1d767f4ae5fd099485f0ddf0',license:'MIT'},
 compatibility:'ADR-0013 and implementation-contract.md; product decision required for reduced relationship semantics'};
for(const name of ['event-v3','context-v3','owner-history-v3','read-sources'])catalog.schemas[name+'.schema.json']=hash(fs.readFileSync(path.join(root,'contracts',name+'.schema.json')));
write('catalog-v3.json',catalog);
binding.properties.catalogDigest={const:hash(fs.readFileSync(path.join(root,'contracts/catalog-v3.json')))};
write('task-binding-v3.schema.json',binding);
const example=Object.fromEntries(Object.entries(binding.properties).map(([k,v])=>[k,v.const]));
example.catalogDigest=hash(fs.readFileSync(path.join(root,'contracts/catalog-v3.json')));write('examples/binding.json',example);
const user=load('examples/user-event.json');user.version=3;user.source_event_seqs=[];write('examples/user-event.json',user);
const receipt={version:3,session_id:user.session_id,seq:4,type:'session.read',time:'2026-10-23T00:00:01.000000Z',
 source:{kind:'invocation',step_id:'00000000-0000-4000-8000-000000000101',invocation_id:'00000000-0000-4000-8000-000000000102'},
 content_policy:'public-v1',redacted:false,data:{seq:0,before:0,after:0,upper_seq:0,outcome:'SUCCEEDED',code:null,output_sha256:'a'.repeat(64),output_bytes:600},source_event_seqs:[0]};
write('examples/read-receipt.json',receipt);
const summary={seq:0,type:user.type,time:user.time,source:user.source,redacted:false,text:user.data.content,summary_truncated:false,event_sha256:hash(Buffer.from(JSON.stringify(user)))};
const output={version:1,session_id:user.session_id,history_version:3,content_policy:'public-v1',boundary:'pre-step',upper_seq:5,relation_policy:'read-receipt-sources-v1',replacement_policy:'unsupported',target:summary,source_event_seqs:[],derived_event_seqs:[4]};
write('examples/trace-output.json',output);
write('examples/trace-input.json',{seq:0});
write('examples/read-sources.json',[{seq:0,event_sha256:summary.event_sha256}]);
const ro=load('examples/read-output.json');ro.history_version=3;ro.target=user;write('examples/read-output.json',ro);
for(const n of ['context','owner-history']){const e=load('examples/'+n+'.json');e.version=3;write('examples/'+n+'.json',e);}
const so=load('examples/search-output.json');so.history_version=3;write('examples/search-output.json',so);
console.log('Generated v3 architecture contracts; no product files changed.');
