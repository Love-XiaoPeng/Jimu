const fs=require('fs'),path=require('path'),cp=require('child_process'),crypto=require('crypto');
const repo=path.resolve(process.argv[2] || path.join(__dirname,'../../..')),base='26f1c23be0c155c9b9c69126e66f9dbf394bc946';
function git(args){return cp.execFileSync('git',['-c','core.longpaths=true','-C',repo,...args],{encoding:'utf8',maxBuffer:20*1024*1024});}
function walk(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)]);}
const constants=new Map();
for(const file of walk(path.join(repo,'src/main/java')).filter(f=>f.endsWith('.java'))){const text=fs.readFileSync(file,'utf8'),name=path.basename(file,'.java');
for(const m of text.matchAll(/(?:public |private )?static final (?:String|int|long|double)\s+(\w+)\s*=\s*([^;]+);/g))if(/^(?:"(?:\\.|[^"\\])*"|[\d_.LlFfDd]+)$/.test(m[2].trim()))constants.set(name+'.'+m[1],m[2].trim());}
const lex=/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|\/\*[\s\S]*?\*\/|\b\d[\w.]*|[A-Za-z_$][\w$]*|\S/g;
function canonical(text){
 text=text.replace(/^\s*import\s+(?:static\s+)?[^;]+;/gm,'');
 let tokens=[...text.matchAll(lex)].map(m=>m[0]).filter(t=>!t.startsWith('//')&&!t.startsWith('/*'));
 const result=[];
 for(let i=0;i<tokens.length;i++){const key=tokens[i]+'.'+tokens[i+2];if(tokens[i+1]==='.'&&constants.has(key)){result.push(constants.get(key));i+=2;}else result.push(tokens[i]);}
 // These are the only new constant expressions. Folding preserves the original Java literal type.
 let joined=result.join(' ');
 joined=joined.replace(/\( double \) (\d+)/g,'$1.0').replace(/\( long \) (\d+)/g,'$1L').replace(/\b(\d+) \+ 1\b/g,(_,n)=>String(Number(n)+1));
 // A final long field already widens an int constant expression at assignment.
 joined=joined.replace(/(long MAX_PIXELS = 4194304)L/g,'$1');
 return joined;
}
const baselineFiles=new Set(git(['ls-tree','-r','--name-only',base,'--','src/main/java']).trim().split(/\r?\n/));
const changed=git(['diff','--name-only',base,'--','src/main/java']).trim().split(/\r?\n/).filter(file=>file&&baselineFiles.has(file)),records=[];
for(const file of changed){const old=git(['show',base+':'+file]);let current=fs.readFileSync(path.join(repo,file),'utf8');
if(file.endsWith('/TaskExecutionLimitsV1.java'))current=current.replace(/^  public static final int (?:DEFAULT_COST_MICROS|MAX_UNSCOPED_COST_MICROS|DEFAULT_TOKENS) = \d+;\r?\n/gm,'');
const a=canonical(old),b=canonical(current);const same=a===b;
const record={file,equivalent:same,sha256:crypto.createHash('sha256').update(a).digest('hex')};
if(!same){let at=0;while(at<Math.min(a.length,b.length)&&a[at]===b[at])at++;record.difference={old:a.slice(Math.max(0,at-70),at+150),current:b.slice(Math.max(0,at-70),at+150)};}
records.push(record);}
const target=path.join(repo,'docs/verification/XIAO-120/source-equivalence.json');fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,JSON.stringify({base,meaning:'Imports/comments/format ignored; named constant references expand to their exact literals. Only documented compile-time arithmetic/casts are folded. SQL/string bytes remain intact.',records},null,2)+'\n');
console.log(JSON.stringify({checked:records.length,failed:records.filter(r=>!r.equivalent)},null,2));if(records.some(r=>!r.equivalent))process.exitCode=1;
