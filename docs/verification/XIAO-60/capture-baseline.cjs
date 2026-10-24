// Immutable B0 inputs and an explicitly OPEN migration ledger; not a Java/SQL semantic proof.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const baseline=JSON.parse(read('docs/verification/XIAO-59/evidence/baseline-files.json'));
const output=path.join(__dirname,'evidence');fs.mkdirSync(output,{recursive:true});
function write(name,data){fs.writeFileSync(path.join(output,name),JSON.stringify(data,null,2)+'\n');}
const frozen=baseline.files.filter(f=>/^(src\/main\/resources\/|docs\/(baseline|adr|verification)\/|tools\/evaluation\/frozen\/)/.test(f.path));
for(const f of frozen){const actual=fs.readFileSync(path.join(root,f.path));if(sha(actual)!==f.sha256)throw new Error('Frozen input changed: '+f.path);}
write('frozen-inputs.json',{source:baseline.commit,tree:baseline.tree,files:frozen});
const ddl=JSON.parse(read('docs/verification/XIAO-59/evidence/ddl-index.json'));
const tables=ddl.flatMap(m=>m.statements.flatMap(s=>{const name=s.text.match(/^CREATE TABLE (\w+)/i)?.[1];return name?[{table:name,migration:m.path,line:s.line,state:'OPEN',mapper:null,verification:null}]:[];}));
if(tables.length!==35)throw new Error('Expected 35 tables');
tables.push({table:'jimu_provisioning_receipt',managedByFlyway:false,state:'OPEN',mapper:null,verification:null});
write('table-tracking.json',tables);
const mapping=JSON.parse(read('docs/verification/XIAO-59/evidence/file-mapping.json'));
write('file-tracking.json',{source:baseline.commit,note:'Original target/owner mapping retained; only codec/cost relocation and Task admission/download have started. No business Mapper table is CLOSED.',mapping});
const inventory=JSON.parse(read('docs/verification/XIAO-59/evidence/java-inventory.json'));
const owner=(p,line)=>{
 if(p.endsWith('/JdbcLedger.java')) {
  if(line<1951)return 'SessionHistoryService/SessionScopeService';
  if(line<2629)return 'WorkingDirectoryService';
  if(line<3251)return 'TodoService';
  if(line<3811)return 'UserQuestionService';
  if(line<4186)return 'McpService';
  if(line<4350)return 'ImageService/shared transaction';
  if(line<5589)return 'TaskService/ArtifactService';
  if(line<6214)return 'WorkerService';
  if(line<6622)return 'ExecutionService';
  if(line<7247)return 'ModelDispatchService';
  if(line<7613)return 'InvocationService';
  if(line<7849)return 'NativeSearchService';
  return 'ArtifactService/ExecutionService/maintenance (operation owner to verify)';
 }
 return /Session/.test(p)?'SessionHistoryService/SessionScopeService':/Mcp/.test(p)?'McpService':/Image/.test(p)?'ImageService':/Scope/.test(p)?'ExecutionScopeService':'operation owner to verify';
};
const methods=inventory.files.filter(f=>f.path.includes('/adapters/persistence/')).flatMap(f=>f.methods.map(m=>({sourceFile:f.path,sourceLine:m.line,declaration:m.text,proposedOwner:owner(f.path,m.line),state:/\/(Payloads|ModelCosts)\.java$/.test(f.path)?'RELOCATED_BODY_UNCHANGED':'OPEN',targetSql:null,verification:null})));
write('method-tracking.json',{note:'Lexical declaration index inherited from XIAO-59; range owner is a planning aid, not a checked call graph. Each operation/SQL needs a verified unique final target in B2-B5.',methods});
write('baseline-facts.json',{source:baseline.commit,tree:baseline.tree,proposal:'bcb63c57328d64cbe0efbaeee3575dbbea3d0274',schema:22,managedTables:35,externalReadOnlyTables:1,productionJava:163,frozenFiles:frozen.length,oldJarSha256:'4e0a485e997354197f625999d0836b7c75087666bedd678a8f90fe20aab6cb5e',historicalFailures:['complete verify exit1','unknown XIAO-55 lock holder','ADR index frozen3446/actual3819','coverage not exhaustive'],apiFiles:baseline.files.filter(f=>f.path.includes('/jimu/api/'))});
const relocated=[];
for(const [name,target] of [['Payloads','adapters/codec'],['ModelCosts','kernel/budget/domain']]) {
 const prefix='src/main/java/io/github/lovexiaopeng/jimu/';
 const old=cp.execFileSync('git',['show',baseline.commit+':'+prefix+'adapters/persistence/'+name+'.java'],{cwd:root,encoding:'utf8'}).replaceAll('\r\n','\n');
 const current=read(prefix+target+'/'+name+'.java').replaceAll('\r\n','\n');
 const oldBody=old.slice(old.indexOf('{')),currentBody=current.slice(current.indexOf('{'));
 if(oldBody!==currentBody)throw new Error('Relocated body changed: '+name);
 relocated.push({name,target:prefix+target+'/'+name+'.java',bodySha256:sha(oldBody),bodyUnchanged:true});
}
write('relocated-bodies.json',relocated);
console.log(JSON.stringify({frozenFiles:frozen.length,tables:tables.length,methodDeclarations:methods.length,allBusinessMappersOpen:true}));
