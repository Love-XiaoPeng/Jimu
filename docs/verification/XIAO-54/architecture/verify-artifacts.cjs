const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=__dirname,repo=path.resolve(root,'../../../..');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const read=p=>fs.readFileSync(path.resolve(root,p));
const assert=(ok,n)=>{if(!ok)throw Error(n);};
const catalog=JSON.parse(read('contracts/catalog-v3.json'));
for(const t of catalog.tools){
 const dir='contracts/'+t.name+'-'+t.version+'/';
 for(const [k,f] of Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'}))assert(sha(read(dir+f))===t[k],t.name+' '+f);
}
for(const [p,h] of Object.entries(catalog.schemas))assert(sha(read('contracts/'+p))===h,p);
const binding=JSON.parse(read('contracts/task-binding-v3.schema.json'));
assert(binding.properties.catalogDigest.const===sha(read('contracts/catalog-v3.json')),'catalog binding pin');
for(const [next,prior] of [['session_event_read-0.3.0','session_event_read-0.2.0'],['session_event_search-0.2.0','session_event_search-0.1.0']])
 assert(read('contracts/'+next+'/input.schema.json').equals(fs.readFileSync(path.join(repo,'src/main/resources/session/v2',prior,'input.schema.json'))),'input original bytes');
const upstream=JSON.parse(read('evidence/upstream-sources.json'));
assert(upstream.sources.length===48&&upstream.failures.length===0&&!upstream.treeTruncated,'upstream complete');
for(const s of upstream.sources){const b=read(s.local);assert(b.length===s.bytes&&sha(b)===s.sha256,s.source);assert(crypto.createHash('sha1').update(`blob ${b.length}\0`).update(b).digest('hex')===s.gitBlob,'git blob '+s.source);}
const baseline=JSON.parse(read('evidence/jimu-sources.json'));
assert(baseline.commit==='3e5f77549562691634c0a166d8b944ecdcdf1bb8','baseline');
for(const s of baseline.sources)assert(sha(fs.readFileSync(path.join(repo,s.source)))===s.sha256,'old product changed '+s.source);
const cases=JSON.parse(read('evidence/schema-cases.json'));
assert(cases.count===cases.passed.length&&cases.count>=52,'JVM cases');
console.log(`PASS catalog/binding pins, input byte reuse, ${upstream.sources.length} upstream blobs, ${baseline.sources.length} unchanged baseline files, ${cases.count} JVM schema/parser checks`);
