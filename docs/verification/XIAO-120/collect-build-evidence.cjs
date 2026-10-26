const fs=require('fs'),path=require('path'),cp=require('child_process'),crypto=require('crypto');
const repo=path.resolve(process.argv[2] || path.join(__dirname,'../../..')), workspace=path.dirname(repo);
const base='26f1c23be0c155c9b9c69126e66f9dbf394bc946', source='cfa2f51824cd84c1657bf6dcdc0f5cee83d5807a';
const git=args=>cp.execFileSync('git',['-c','core.longpaths=true','-C',repo,...args],{encoding:'utf8',maxBuffer:10*1024*1024}).trim();
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
git(['diff','--exit-code',source,'--','src']);
const tree=git(['rev-parse',source+'^{tree}']);
function blobs(ref) {return new Map(git(['ls-tree','-r',ref,'--','src']).split(/\r?\n/).map(line=>{const m=/^\d+ blob ([a-f0-9]+)\t(.+)$/.exec(line);return [m[2],m[1]];}));}
const old=blobs(base),now=blobs(source);
const changed=git(['diff','--name-status',base,source,'--','src']).split(/\r?\n/).map(line=>{const [status,file]=line.split('\t');return {file,status,baselineBlob:old.get(file)||null,sourceBlob:now.get(file)};});
fs.writeFileSync(path.join(__dirname,'source-blobs.json'),JSON.stringify({base,source,tree,mainTree:git(['rev-parse',source+':src/main']),testTree:git(['rev-parse',source+':src/test']),files:changed},null,2)+'\n');
const windowsLog=path.join(workspace,'xiao120-final-source-windows.log'),posixLog=path.join(workspace,'xiao120-final-posix.log');
for(const log of [windowsLog,posixLog])if(!fs.readFileSync(log,'utf8').includes('BUILD SUCCESS'))throw new Error('Build did not succeed: '+log);
const artifactDir=path.join(workspace,'xiao120-evidence');fs.mkdirSync(artifactDir,{recursive:true});
const windowsSuites=[...fs.readFileSync(windowsLog,'utf8').matchAll(/\[INFO\] Running ([\w.$]+)/g)].map(m=>m[1]);
const reportRows=[];
function collectReport(file,platform) {
 const header=/<testsuite\b[^>]*>/.exec(fs.readFileSync(file,'utf8'))[0];
 const attrs=Object.fromEntries([...header.matchAll(/([\w]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
 const row={platform,name:attrs.name,tests:+attrs.tests,failures:+attrs.failures,errors:+attrs.errors,skipped:+attrs.skipped,sha256:hash(file)};
 if(row.failures||row.errors||row.skipped)throw new Error('Unexpected test result: '+JSON.stringify(row));
 const dest=path.join(artifactDir,'reports',platform);fs.mkdirSync(dest,{recursive:true});fs.copyFileSync(file,path.join(dest,path.basename(file)));reportRows.push(row);
}
windowsSuites.forEach(name=>collectReport(path.join(repo,'target/surefire-reports','TEST-'+name+'.xml'),'windows'));
for(const file of fs.readdirSync(path.join(workspace,'xiao120-posix-reports')).filter(f=>f.endsWith('.xml')))collectReport(path.join(workspace,'xiao120-posix-reports',file),'posix');
const jar=path.join(repo,'target/jimu-0.1.0-SNAPSHOT.jar');
const results={base,source,sourceTree:tree,sourceCleanAfterBuild:true,jar:{name:path.basename(jar),bytes:fs.statSync(jar).size,sha256:hash(jar)},windows:{log:path.basename(windowsLog),exitCode:0,tests:reportRows.filter(r=>r.platform==='windows').reduce((n,r)=>n+r.tests,0),spotbugsBugs:0,spotbugsErrors:0},posix:{log:path.basename(posixLog),exitCode:0,tests:reportRows.filter(r=>r.platform==='posix').reduce((n,r)=>n+r.tests,0)},reports:reportRows};
if(results.windows.tests!==1615||results.posix.tests!==10)throw new Error('Unexpected final test counts');
fs.writeFileSync(path.join(__dirname,'build-evidence.json'),JSON.stringify(results,null,2)+'\n');
for(const file of fs.readdirSync(workspace).filter(f=>/^xiao120-.*\.log$/.test(f)))fs.copyFileSync(path.join(workspace,file),path.join(artifactDir,file));
fs.cpSync(__dirname,path.join(artifactDir,'verification'),{recursive:true});
console.log(JSON.stringify({source,tree,changedFiles:changed.length,existingMain:changed.filter(r=>r.status==='M'&&r.file.startsWith('src/main/')).length,newMain:changed.filter(r=>r.status==='A').length,windows:results.windows,posix:results.posix,jar:results.jar},null,2));
