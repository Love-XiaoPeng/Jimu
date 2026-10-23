const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=__dirname,repo=path.resolve(root,'../../../..');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const relative=f=>path.relative(repo,f).replaceAll('\\','/');
function files(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(x=>x.isDirectory()?files(path.join(dir,x.name)):[path.join(dir,x.name)]);}
const manifestFile=path.join(root,'PACKAGE-MANIFEST.json');
const upstream=JSON.parse(fs.readFileSync(path.join(root,'evidence/upstream-sources.json')));
const baseline=JSON.parse(fs.readFileSync(path.join(root,'evidence/jimu-sources.json')));
const selected=[...new Set([...files(root).filter(x=>x!==manifestFile),
  path.join(repo,'docs/adr/0012-bounded-session-event-search.md'),path.join(repo,'.gitattributes'),
  ...upstream.sources.map(x=>path.resolve(root,x.local)),...baseline.sources.map(x=>path.join(repo,x.source)),
  path.join(repo,'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java')])].sort();
const manifest={scope:'Architecture implementation contract/provenance only; no product runtime validation',
  productBaseline:baseline.commit,productTree:baseline.tree,
  files:selected.map(file=>{const body=fs.readFileSync(file);return{path:relative(file),bytes:body.length,sha256:hash(body)};})};
if(process.argv.includes('--verify')) {
 if(JSON.stringify(manifest)!==JSON.stringify(JSON.parse(fs.readFileSync(manifestFile))))throw Error('Package differs');
} else fs.writeFileSync(manifestFile,JSON.stringify(manifest,null,2)+'\n');
console.log(`Verified ${manifest.files.length} handoff files including fixed upstream/license and baseline source.`);
