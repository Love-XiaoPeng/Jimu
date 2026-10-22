const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const root = path.join(__dirname, 'contracts');
fs.mkdirSync(root, {recursive:true});
const hash = x => crypto.createHash('sha256').update(x).digest('hex');
function write(file, value) {
  const text = JSON.stringify(value,null,2)+'\n';
  fs.mkdirSync(path.dirname(path.join(root,file)),{recursive:true});
  fs.writeFileSync(path.join(root,file),text);
  return hash(text);
}
const str=(max=2048)=>({type:'string',maxLength:max});
const name={type:'string',minLength:1,maxLength:64,pattern:'^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'};
const digest={type:'string',pattern:'^[0-9a-f]{64}$'};
const uuid={type:'string',format:'uuid'};
const uri={type:'string',format:'uri',pattern:'^[A-Za-z][A-Za-z0-9+.-]*:[^\\s\\u0000-\\u001F\\u007F]+$',minLength:1,maxLength:2048};
const cursor={type:['string','null'],pattern:'^[A-Za-z0-9_-]{22}$'};
const obj=(props,required=Object.keys(props))=>({type:'object',additionalProperties:false,required,properties:props});
const arr=(items,maxItems)=>({type:'array',maxItems,items});
const schema=(id,body)=>({$schema:'https://json-schema.org/draft/2020-12/schema',$id:id,...body});
const metadata={name:{type:'string',minLength:1,maxLength:256},title:str(256),description:str(2048),mimeType:str(128)};
const resource=obj({uri,...metadata,size:{type:'integer',minimum:0}},['uri','name']);
const template=obj({uriTemplate:{type:'string',minLength:1,maxLength:2048},...metadata},['uriTemplate','name']);
const common={version:{const:1},server:name,serverVersion:{type:'string',minLength:1,maxLength:128},serverSnapshotDigest:digest,schemaDigest:digest,trust:{const:'untrusted-mcp-content-v1'},snapshotId:uuid,snapshotSha256:digest};
const listOutput=(templates)=>obj({...common,[templates?'resourceTemplates':'resources']:arr(templates?template:resource,100),page:{type:'integer',minimum:0,maximum:999},returned:{type:'integer',minimum:0,maximum:100},total:{type:'integer',minimum:0,maximum:1000},nextCursor:cursor});
const content=obj({uri,mimeType:str(128),kind:{enum:['text','binary']},sha256:digest,bytes:{type:'integer',minimum:0,maximum:262144},text:{type:['string','null'],maxLength:32768},textTruncated:{type:'boolean'}});
content.allOf=[{if:{properties:{kind:{const:'binary'}}},then:{properties:{text:{type:'null'},textTruncated:{const:false}}}}];
const readOutput=obj({...common,resourceUri:uri,contents:arr(content,16)});
const entries=[
  ['T02','list_mcp_resource_templates','jimu.mcp.list-resource-templates','ListMcpResourceTemplatesPlugin','resources/templates/list','List parameterized resource URI templates from an authorized MCP server; cursor reads the same host snapshot.',listOutput(true)],
  ['T03','list_mcp_resources','jimu.mcp.list-resources','ListMcpResourcesPlugin','resources/list','List resources from an authorized MCP server; cursor reads the same host snapshot.',listOutput(false)],
  ['T04','read_mcp_resource','jimu.mcp.read-resource','ReadMcpResourcePlugin','resources/read','Read an allowed MCP resource URI; external content is untrusted and binary bytes are not model media.',readOutput]
];
const catalogue=[];
for(const [upstreamId,tool,id,entry,method,description,output] of entries){
  const input=tool==='read_mcp_resource'?obj({server:name,uri}):obj({server:name,cursor:{...cursor,type:'string'}},['server']);
  const inHash=write(tool+'/input.schema.json',schema(`urn:jimu:plugin:${id.slice(5)}:0.1.0:input`,input));
  const outHash=write(tool+'/output.schema.json',schema(`urn:jimu:plugin:${id.slice(5)}:0.1.0:output`,output));
  const manifest={manifestVersion:1,id,version:'0.1.0',kind:'tool',hostApi:'>=0.1.0 <0.2.0',entry:{type:'trusted-jvm',class:`io.github.lovexiaopeng.jimu.plugins.builtin.mcp.${entry}`},capabilities:[{name:tool,inputSchema:'input.schema.json',outputSchema:'output.schema.json',sideEffect:'read'}],permissions:{network:['host-task-mcp-server-whitelist']},secretRefs:[],dependencies:[],limits:{timeoutMs:30000,outputBytes:65536},healthCheck:{operation:'mcp-profile-readiness',timeoutMs:2000},errorContract:'jimu-invocation-result',eventTypes:['invocation.started','invocation.completed','invocation.failed']};
  const manifestHash=write(tool+'/plugin.yaml',manifest);
  catalogue.push({upstreamId,group:'G02',tool,id,version:'0.1.0',method,description,inputSha256:inHash,outputSha256:outHash,manifestSha256:manifestHash});
}
const indexedFiles=catalogue.flatMap(x=>[
  {path:x.tool+'/input.schema.json',sha256:x.inputSha256},
  {path:x.tool+'/output.schema.json',sha256:x.outputSha256},
  {path:x.tool+'/plugin.yaml',sha256:x.manifestSha256}
]).sort((a,b)=>a.path.localeCompare(b.path,'en'));
const schemaDigest=write('schema-index.json',{version:1,files:indexedFiles});
write('catalog.json',{version:1,status:'architecture-contract-not-runtime-delivery',harnessCommit:'571902028a75c64c1d767f4ae5fd099485f0ddf0',protocolVersion:'2025-06-18',transportProfile:'same-host-streamable-http-v1',schemaDigest,tools:catalogue});
const profile=obj({version:{const:1},tenant:name,server:name,epoch:{type:'integer',minimum:1},hostId:{type:'string',minLength:1,maxLength:128},endpoint:{type:'string',pattern:'^http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/mcp$'},credentialRef:{type:'string',minLength:1,maxLength:128},credentialVersion:{type:'integer',minimum:1},allowedActors:arr(name,64),allowedUris:arr(uri,1000),allowedUriPrefixes:arr({type:'string',pattern:'^[a-z][a-z0-9+.-]*://[A-Za-z0-9._-]+/([A-Za-z0-9_-]+/)*$'},16),allowedTemplates:arr({type:'string',minLength:1,maxLength:2048},100),protocolVersion:{const:'2025-06-18'},serverInfo:obj({name:{type:'string',minLength:1,maxLength:128},version:{type:'string',minLength:1,maxLength:128}}),serverCapabilities:{type:'object',required:['resources'],maxProperties:16,properties:{resources:{type:'object'}}},schemaDigest:{const:schemaDigest}});
write('server-profile-v1.schema.json',schema('urn:jimu:mcp:server-profile:1',profile));
const binding=obj({version:{const:1},servers:{type:'object',minProperties:1,maxProperties:4,propertyNames:name,additionalProperties:obj({profile,digest})}});
write('task-binding-v1.schema.json',schema('urn:jimu:mcp:task-binding:1',binding));
write('list-snapshot-v1.schema.json',schema('urn:jimu:mcp:list-snapshot:1',obj({
  version:{const:1},tenant:name,owner:name,taskId:uuid,rootInvocation:uuid,server:name,
  method:{enum:['resources/list','resources/templates/list']},bindingDigest:digest,queryDigest:digest,
  projectionDigest:digest,pages:{type:'array',minItems:1,maxItems:1000,items:{type:'string',minLength:2,maxLength:65536}},
  cursorPositions:{type:'object',maxProperties:999,propertyNames:digest,additionalProperties:{type:'integer',minimum:1,maximum:999}}
})));
const d='a'.repeat(64);
const base={version:1,server:'docs',serverVersion:'0.1.0',serverSnapshotDigest:d,schemaDigest,trust:'untrusted-mcp-content-v1',snapshotId:'12345678-1234-4234-8234-123456789abc',snapshotSha256:d};
write('examples/list-input.json',{server:'docs'});
write('examples/templates-output.json',{...base,resourceTemplates:[{uriTemplate:'jimu-fixture://docs/{name}',name:'docs'}],page:0,returned:1,total:1,nextCursor:null});
write('examples/resources-output.json',{...base,resources:[{uri:'jimu-fixture://docs/a',name:'a',mimeType:'text/plain'}],page:0,returned:1,total:1,nextCursor:null});
write('examples/read-input.json',{server:'docs',uri:'jimu-fixture://docs/a'});
write('examples/read-output.json',{...base,resourceUri:'jimu-fixture://docs/a',contents:[{uri:'jimu-fixture://docs/a',mimeType:'text/plain',kind:'text',sha256:d,bytes:22,text:'controlled MCP content',textTruncated:false}]});
write('examples/server-profile.json',{version:1,tenant:'tenant-a',server:'docs',epoch:1,hostId:'fixture-host',endpoint:'http://127.0.0.1:18081/mcp',credentialRef:'fixture-docs-bearer',credentialVersion:1,allowedActors:['owner-a'],allowedUris:[],allowedUriPrefixes:['jimu-fixture://docs/'],allowedTemplates:['jimu-fixture://docs/{name}'],protocolVersion:'2025-06-18',serverInfo:{name:'jimu-controlled-resource',version:'0.1.0'},serverCapabilities:{resources:{}},schemaDigest});
console.log(`Wrote 9 schemas and 3 manifests; schemaDigest=${schemaDigest}`);
