// Read-only baseline inventory, not a Java parser or a replacement for ArchUnit.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../..');
const base = '620d5e0e5bfe7b5da530f10997936466159ba34f';
const git = (...args) => cp.execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 32*1024*1024 }).trim();
const entries = git('ls-tree','-r','-z',base).split('\0').filter(Boolean).map(s=>{const [head,p]=s.split('\t');return{path:p,blob:head.split(' ')[2]};});
const objects=cp.execFileSync('git',['cat-file','--batch'],{cwd:root,input:entries.map(e=>e.blob).join('\n')+'\n',maxBuffer:128*1024*1024});
const contents=new Map();let cursor=0;
for(const e of entries){const end=objects.indexOf(10,cursor);const size=Number(objects.subarray(cursor,end).toString().split(' ')[2]);contents.set(e.path,objects.subarray(end+1,end+1+size));cursor=end+size+2;}
const raw = p => contents.get(p);
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const write = (p, x) => { const dest = path.join(__dirname, 'evidence', p); fs.mkdirSync(path.dirname(dest), {recursive:true}); fs.writeFileSync(dest, typeof x === 'string' ? x : JSON.stringify(x,null,2)+'\n'); };
const paths = entries.map(e=>e.path);
const files = paths.map(p => { const b=raw(p); return {path:p,bytes:b.length,sha256:sha(b)}; });
write('baseline-files.json',{commit:base,tree:git('rev-parse',`${base}^{tree}`),files});
const prefix = 'src/main/java/io/github/lovexiaopeng/jimu/';
const java = paths.filter(p => p.startsWith(prefix)&&p.endsWith('.java')).map(p=>{
  const text=raw(p).toString('utf8'); const lines=text.split(/\r?\n/); if(lines.at(-1)==='')lines.pop();
  return {path:p,lines:lines.length,package:text.match(/^package ([\w.]+);/m)[1],imports:[...text.matchAll(/^import (?:static )?([\w.]+);/gm)].map(m=>m[1]),methods:lines.flatMap((s,i)=>/^  \S/.test(s)&&/^  (?:(?:public|private|protected|static|final|synchronized) )*(?:[\w.<>?,\[\] ]+ )?\w+\(/.test(s)&&!/^  (if|for|while|return|throw|switch)/.test(s)?[{line:i+1,text:s.trim()}]:[])};
});
write('java-inventory.json',{scope:'all baseline production Java files; imports only, fully-qualified references are not included in import edges',files:java});
const edges={};
for(const f of java)for(const i of f.imports.filter(i=>i.startsWith('io.github.lovexiaopeng.jimu.'))){ const from=f.package.replace('io.github.lovexiaopeng.jimu.','');const to=i.replace('io.github.lovexiaopeng.jimu.','').split('.').slice(0,-1).join('.'); const k=from+' -> '+to;(edges[k]??=[]).push(f.path); }
write('import-edges.json',Object.entries(edges).sort().map(([edge,files])=>({edge,files:[...new Set(files)]})));
write('largest-files.md','# 基线生产 Java 规模\n\n逐文件行数与 import 见 java-inventory.json；行数不是自动判定缺陷的阈值。\n\n| 文件 | 行数 |\n| --- | ---: |\n'+java.sort((a,b)=>b.lines-a.lines).slice(0,25).map(f=>`| ${f.path.slice(prefix.length)} | ${f.lines} |`).join('\n')+'\n');
const sqlFiles=paths.filter(p=>p.startsWith('src/main/resources/db/migration/')&&p.endsWith('.sql'));
const ddl=sqlFiles.map(p=>({path:p,statements:raw(p).toString('utf8').split(/\r?\n/).flatMap((text,i)=>/CREATE TABLE|ALTER TABLE|CREATE (?:UNIQUE )?INDEX/i.test(text)?[{line:i+1,text:text.trim()}]:[])}));
write('ddl-index.json',ddl);
const jdbc=java.filter(f=>/adapters\/persistence\//.test(f.path));
write('persistence-methods.md','# 基线持久化入口及辅助方法\n\n按声明首行索引；具体语义须读方法体，不能把此清单当调用图。\n\n'+jdbc.map(f=>'## '+f.path.slice(prefix.length)+'\n\n'+f.methods.map(m=>'- L'+m.line+': `'+m.text+'`').join('\n')).join('\n\n')+'\n');
const literals=[];
for(const f of java){const ls=raw(f.path).toString('utf8').split(/\r?\n/);ls.forEach((text,i)=>{if(/\b\d{3,}(?:[lLdDfF])?\b|"(?:[A-Z][A-Z_0-9]{2,}|[a-z]+[._-][a-z._-]+)"/.test(text))literals.push({path:f.path,line:i+1,text:text.trim()});});}
write('literal-candidates.json',{note:'lexical candidate index; includes technical literals and SQL. Classification and decisions are in magic-values.md.',candidates:literals});
console.log(JSON.stringify({commit:base,trackedFiles:files.length,productionJava:java.length,lines:java.reduce((n,f)=>n+f.lines,0),migrations:sqlFiles.length,persistenceJava:jdbc.length,literalCandidates:literals.length}));
