const fs = require('fs'), path = require('path'), cp = require('child_process'), crypto = require('crypto');
const repo = path.resolve(process.argv[2] || path.join(__dirname, '../../..'));
const base = '26f1c23be0c155c9b9c69126e66f9dbf394bc946';
const git = args => cp.execFileSync('git', ['-c','core.longpaths=true','-C',repo,...args], {encoding:'utf8',maxBuffer:10*1024*1024});
const hash = text => crypto.createHash('sha256').update(text.replace(/\r\n/g,'\n')).digest('hex');
const root = 'src/main/java/io/github/lovexiaopeng/jimu/';
const baseline = git(['ls-tree','-r','--name-only',base,'--','src']).trim().split(/\r?\n/);
const protectedFiles = baseline.filter(f => f.startsWith(root+'bootstrap/') || f.includes('/persistence/query/') || f.includes('/persistence/transaction/') || f.includes('/kernel/transaction/') || f.endsWith('/SessionQueryDeadline.java') || f.endsWith('/SessionQueryContext.java') || f.startsWith('src/main/resources/'));
const records = protectedFiles.map(file => {
  const old = git(['show',base+':'+file]), now = fs.readFileSync(path.join(repo,file),'utf8');
  return {file,baselineBlob:git(['rev-parse',base+':'+file]).trim(),sha256:hash(old),unchanged:hash(old)===hash(now)};
});
const file = root+'adapters/persistence/JdbcLedger.java';
function blocks(text) {
  const masked = text.replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|\/\*[\s\S]*?\*\//g, x => x.replace(/[^\r\n]/g,' '));
  const regex = /^  (?:(?:public |private )?JdbcLedger\(|(?:private )?<T> T (?:sessionTransaction|sessionOwnerRead|sessionSearchOwnerRead|tx|leaseTx|directoryTransaction|scopeRequiresNew)\(|private JdbcLedger queryView\(|private record QueryViewConfiguration\(|private final class ScopeInvalidation\b|void invalidateScopeAfterTransaction\()/gm;
  return [...masked.matchAll(regex)].map(m => {
    const start=m.index, open=masked.indexOf('{',start);let end=open+1, depth=1;
    while(depth) {if(masked[end]==='{')depth++;if(masked[end]==='}')depth--;end++;}
    return {signature:text.slice(start,open).trim(),text:text.slice(start,end)};
  });
}
const old=blocks(git(['show',base+':'+file])), now=blocks(fs.readFileSync(path.join(repo,file),'utf8'));
if(old.length!==now.length || old.length!==17) throw new Error('Frozen Ledger block count changed: '+old.length+'/'+now.length);
old.forEach((block,i) => records.push({file,signature:block.signature,sha256:hash(block.text),unchanged:hash(block.text)===hash(now[i].text)}));
fs.writeFileSync(path.join(__dirname,'frozen-boundaries.json'),JSON.stringify({base,comparison:'UTF8 text SHA256, only CRLF normalized to LF; entire protected files and 17 Ledger blocks including all constructors, transaction/query wrappers and synchronization',records},null,2)+'\n');
const failed=records.filter(r=>!r.unchanged);console.log(JSON.stringify({checked:records.length,failed},null,2));if(failed.length)process.exitCode=1;
