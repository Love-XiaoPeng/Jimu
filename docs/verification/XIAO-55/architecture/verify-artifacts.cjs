const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=__dirname,repo=path.resolve(root,'../../../..');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const read=p=>fs.readFileSync(path.resolve(root,p));
const json=p=>JSON.parse(read(p));
const assert=(condition,message)=>{if(!condition)throw Error(message);};
const catalog=json('contracts/catalog-v4.json');
for(const tool of catalog.tools){
 const dir=`contracts/${tool.name}-${tool.version}/`;
 for(const [key,file]of Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'}))assert(sha(read(dir+file))===tool[key],tool.name+' '+file);
}
for(const [file,digest]of Object.entries(catalog.schemas))assert(sha(read('contracts/'+file))===digest,file);
const binding=json('contracts/examples/binding.json');
assert(binding.catalogDigest===sha(read('contracts/catalog-v4.json')),'Catalog binding');
assert(JSON.stringify(binding.capabilities)===JSON.stringify(['session_event_read','session_search']),'Two capability profile');
assert(read('contracts/session_event_read-0.4.0/input.schema.json').equals(fs.readFileSync(path.join(repo,'src/main/resources/session/v3/session_event_read-0.3.0/input.schema.json'))),'Read input bytes preserved');
const evidence=json('evidence/upstream-sources.json');
assert(evidence.revision==='571902028a75c64c1d767f4ae5fd099485f0ddf0'&&evidence.sources.length===48&&evidence.failures.length===0&&!evidence.treeTruncated,'Upstream completeness');
for(const s of evidence.sources){const body=read(s.local);assert(sha(body)===s.sha256&&body.length===s.bytes,s.source);assert(crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex')===s.gitBlob,'blob '+s.source);}
const baseline=json('evidence/jimu-sources.json');
assert(baseline.commit==='d6d2d28a8f4fb3c2f05ed2bd410b1d5f65a16fe1'&&baseline.tree==='af0e11992efdc00d3e8bc6d656d9aeda30159a90','Accepted master/tree');
for(const s of baseline.sources)assert(sha(fs.readFileSync(path.join(repo,s.source)))===s.sha256,'Changed baseline '+s.source);
const scope=json('contracts/examples/scope.json'),result=json('contracts/examples/search-output.json'),context=json('contracts/examples/context.json');
assert(result.scope_sha256===sha(JSON.stringify(scope))&&context.scope_sha256===result.scope_sha256,'Authority bytes and result/context pins');
assert(scope.caller_binding_sha256===sha(JSON.stringify(binding)),'Caller binding pin');
assert(result.returned_count===result.items.length&&new Set(result.items.map(x=>x.session_id)).size===result.items.length,'One representative per session');
assert(result.items.every(x=>scope.targets.some(t=>t.session_id===x.session_id)&&x.session_id!==result.caller_session_id),'Explicit prior scope');
const full={...scope,targets:Array.from({length:8},(_,i)=>({...scope.targets[0],session_id:`00000000-0000-4000-8000-${String(i).padStart(12,'0')}`}))};
assert(Buffer.byteLength(JSON.stringify(full))<=4096,'Eight-target authority capacity');
assert(full.targets.every(x=>Buffer.byteLength(JSON.stringify(x))<=512),'Target snapshot capacity');
const refs=Array.from({length:9},(_,seq)=>({session_id:scope.targets[0].session_id,seq,event_sha256:'a'.repeat(64)}));
assert(Buffer.byteLength(JSON.stringify(refs))<=2048,'Nine cross-session refs capacity');
const cases=json('evidence/schema-cases.json');assert(cases.count===cases.passed.length&&cases.count>=69,'JVM candidate probe');
const changed=cp.execFileSync('git',['diff','--name-only',baseline.commit],{cwd:repo,encoding:'utf8'}).trim().split('\n').filter(Boolean);
assert(changed.every(x=>x==='.gitattributes'||x==='docs/adr/0014-explicit-prior-session-search-scope.md'||x.startsWith('docs/verification/XIAO-55/architecture/')),'Only architecture artifacts changed');
const report={upstreamBlobs:48,unchangedBaselineFiles:baseline.sources.length,schemaParserChecks:cases.count,eightTargetAuthorityBytes:Buffer.byteLength(JSON.stringify(full)),nineReadRefsBytes:Buffer.byteLength(JSON.stringify(refs)),productImplemented:false,runtimeTestsRun:false};
fs.writeFileSync(path.join(root,'evidence/artifact-check.json'),JSON.stringify(report,null,2)+'\n');
console.log('PASS '+JSON.stringify(report));
