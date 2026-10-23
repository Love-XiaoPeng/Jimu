const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=__dirname,repo=path.resolve(root,'../../../..');
const digest=b=>crypto.createHash('sha256').update(b).digest('hex');
const files=[];
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isDirectory())walk(p);else if(e.isFile()&&e.name!=='PACKAGE-MANIFEST.json')files.push(p);}}
walk(root);files.push(path.join(repo,'docs/adr/0014-explicit-prior-session-search-scope.md'),path.join(repo,'.gitattributes'));
for(const row of JSON.parse(fs.readFileSync(path.join(root,'evidence/upstream-sources.json'))).sources)files.push(path.resolve(root,row.local));
for(const row of JSON.parse(fs.readFileSync(path.join(root,'evidence/jimu-sources.json'))).sources)files.push(path.join(repo,row.source));
const records=[...new Set(files)].map(p=>{const b=fs.readFileSync(p);return{path:path.relative(repo,p).replaceAll('\\','/'),bytes:b.length,sha256:digest(b)};}).sort((a,b)=>a.path.localeCompare(b.path,'en'));
const manifest={version:1,baseline:'d6d2d28a8f4fb3c2f05ed2bd410b1d5f65a16fe1',baselineTree:'af0e11992efdc00d3e8bc6d656d9aeda30159a90',scope:'Architecture candidate, referenced immutable source evidence and baseline files; no product implementation or JAR',files:records};
fs.writeFileSync(path.join(root,'PACKAGE-MANIFEST.json'),JSON.stringify(manifest,null,2)+'\n');
if(process.argv[2]){
 const archive=path.resolve(process.argv[2]);
 cp.execFileSync('git',['archive','--format=zip','--output='+archive,'HEAD',...records.map(x=>x.path),path.relative(repo,path.join(root,'PACKAGE-MANIFEST.json')).replaceAll('\\','/')],{cwd:repo});
 console.log(JSON.stringify({archive:path.basename(archive),files:records.length+1,bytes:fs.statSync(archive).size,sha256:digest(fs.readFileSync(archive))}));
}else console.log('Prepared '+records.length+' file digest records; manifest excludes itself.');
