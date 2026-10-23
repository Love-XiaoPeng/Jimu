const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=__dirname,repo=path.resolve(root,'../../../..');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const manifestFile=path.join(root,'PACKAGE-MANIFEST.json');
if(process.argv.includes('--verify')){
 const m=JSON.parse(fs.readFileSync(manifestFile));
 for(const s of m.files){const b=fs.readFileSync(path.join(repo,s.path));if(b.length!==s.bytes||sha(b)!==s.sha256)throw Error(s.path);}
 console.log('PASS package '+m.files.length+' files');
}else{
 const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
 const upstream=JSON.parse(fs.readFileSync(path.join(root,'evidence/upstream-sources.json')));
 const baseline=JSON.parse(fs.readFileSync(path.join(root,'evidence/jimu-sources.json')));
 const files=[...walk(root).filter(x=>x!==manifestFile),...upstream.sources.map(x=>path.resolve(root,x.local)),
  ...baseline.sources.map(x=>path.join(repo,x.source)),
  ...['docs/adr/0011-authorized-session-event-read.md','docs/adr/0012-bounded-session-event-search.md',
   'docs/adr/0013-captured-session-event-read-provenance.md','docs/baseline/XIAO-33-可行性与总体方案-v0.3.md',
   'docs/baseline/XIAO-33-工程规范-v0.2.md','docs/baseline/XIAO-33-tool-migration-evidence-v1.1.md',
   'docs/baseline/XIAO-33-harness-tool-inventory-v1.1.json'].map(x=>path.join(repo,x))];
 const rows=[...new Set(files)].map(f=>{const b=fs.readFileSync(f);return {path:path.relative(repo,f).replaceAll('\\','/'),bytes:b.length,sha256:sha(b)};}).sort((a,b)=>a.path.localeCompare(b.path));
 fs.writeFileSync(manifestFile,JSON.stringify({version:1,baseline:'3e5f77549562691634c0a166d8b944ecdcdf1bb8',scope:'architecture candidate; product decision required; no runtime acceptance',files:rows},null,2)+'\n');
 console.log('Recorded package '+rows.length+' files');
}
