// Validate the architecture handoff against exact Git objects and document evidence.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto'),zlib=require('node:zlib');
const root=path.resolve(__dirname,'../../..'),ev=path.join(__dirname,'evidence');
const read=p=>fs.readFileSync(path.join(ev,p),'utf8').replace(/^\uFEFF/,'');
const json=p=>JSON.parse(read(p));
const git=(...a)=>cp.execFileSync('git',a,{cwd:root,encoding:'utf8',maxBuffer:32*1024*1024});
const assert=(v,m)=>{if(!v)throw Error(m);};
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const baseline=json('baseline-files.json');
assert(baseline.commit==='620d5e0e5bfe7b5da530f10997936466159ba34f','wrong baseline');
assert(git('rev-parse',baseline.commit+'^{tree}').trim()===baseline.tree,'wrong tree');
const entries=git('ls-tree','-r','-z',baseline.commit).split('\0').filter(Boolean).map(s=>{const [h,p]=s.split('\t');return {path:p,blob:h.split(' ')[2]};});
const objects=cp.execFileSync('git',['cat-file','--batch'],{cwd:root,input:entries.map(e=>e.blob).join('\n')+'\n',maxBuffer:128*1024*1024});
let cursor=0;const content=new Map();
for(const e of entries){const end=objects.indexOf(10,cursor),size=Number(objects.subarray(cursor,end).toString().split(' ')[2]);content.set(e.path,objects.subarray(end+1,end+1+size));cursor=end+size+2;}
assert(entries.length===1287&&baseline.files.length===entries.length,'inventory coverage');
for(const f of baseline.files)assert(content.has(f.path)&&sha(content.get(f.path))===f.sha256&&content.get(f.path).length===f.bytes,'baseline digest: '+f.path);
const java=json('java-inventory.json').files,mapping=json('file-mapping.json').files;
assert(java.length===163&&java.reduce((s,f)=>s+f.lines,0)===27685,'Java inventory');
assert(new Set(mapping.map(f=>f.source)).size===163&&java.every(f=>mapping.some(m=>m.source===f.path&&m.targets.length&&m.reason)),'file mapping incomplete');
const ddl=json('ddl-index.json');assert(ddl.length===22,'migration count');
const tables=ddl.flatMap(f=>f.statements.flatMap(s=>[...s.text.matchAll(/^CREATE TABLE (\w+)/gi)].map(m=>m[1])));
assert(tables.length===35&&new Set(tables).size===35,'table count');
const persistence=fs.readFileSync(path.join(root,'docs/refactoring/XIAO-58/persistence.md'),'utf8');
assert(tables.every(t=>persistence.includes('| '+t+'（')),'table mapping incomplete');
const changed=[...git('diff','--name-only','-z',baseline.commit).split('\0'),...git('ls-files','--others','--exclude-standard','-z').split('\0')].filter(Boolean);
const allowed=p=>['AGENTS.md','CONTRIBUTING.md','docs/architecture.md','docs/engineering.md','docs/adr/0015-business-services-and-mybatis-plus.md'].includes(p)||p.startsWith('docs/refactoring/XIAO-58/')||p.startsWith('docs/verification/XIAO-59/');
assert(changed.every(allowed),'out-of-scope change: '+changed.filter(p=>!allowed(p)).join(','));
const documents=changed.filter(p=>p.endsWith('.md'));let links=0;
for(const p of documents){const text=fs.readFileSync(path.join(root,p),'utf8');for(const m of text.matchAll(/\]\(([^)]+)\)/g)){const href=m[1].split('#')[0];if(!href||/^[a-z]+:/i.test(href))continue;const target=path.resolve(root,path.dirname(p),decodeURIComponent(href));assert(fs.existsSync(target),'broken link '+p+' -> '+href);links++;}}
assert(fs.readFileSync(path.join(root,'docs/adr/0015-business-services-and-mybatis-plus.md'),'utf8').includes('proposed'),'ADR acceptance overstated');
for(const s of json('official-sources.json').sources)assert(!s.error&&s.sha256&&s.quotations.every(q=>q.found),'official source incomplete: '+s.id);
for(const p of json('resolution-summary.json').artifacts){const bytes=fs.readFileSync(path.join(ev,p.path));const original=p.encoding==='gzip'?zlib.gunzipSync(bytes):bytes;assert(sha(original)===p.sha256&&original.length===p.bytes,'resolution artifact mismatch: '+p.path);}
const probe=zlib.gunzipSync(fs.readFileSync(path.join(ev,'compatibility-probe.txt.gz'))).toString('utf8');assert(probe.includes('PASS durable=false')&&probe.includes('PASS durable=true')&&probe.includes('database semantics NOT tested'),'probe incomplete');
git('diff','--check');git('diff','--cached','--check');
console.log(JSON.stringify({result:'PASS',baseline:baseline.commit,tree:baseline.tree,baselineHashes:entries.length,productionJava:java.length,mappedFiles:mapping.length,migrations:ddl.length,tables:tables.length,localLinks:links,changedFiles:changed.length,scope:'documentation and isolated evidence only; no product or DB acceptance'}));
