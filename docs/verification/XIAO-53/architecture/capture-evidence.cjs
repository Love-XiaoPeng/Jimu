// Architecture evidence only. No application dependency or product mutation.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const previous = path.resolve(__dirname, '../../XIAO-52/architecture');
const sha256 = b => crypto.createHash('sha256').update(b).digest('hex');
const write = (file, value) => {
  fs.mkdirSync(path.dirname(path.join(__dirname, file)), {recursive:true});
  fs.writeFileSync(path.join(__dirname, file), JSON.stringify(value,null,2)+'\n');
};
const git = (...args) => cp.execFileSync('git', args, {encoding:'utf8'}).trim();
(async () => {
  const revision='571902028a75c64c1d767f4ae5fd099485f0ddf0';
  const old = JSON.parse(fs.readFileSync(path.join(previous,'evidence/upstream-sources.json')));
  const selected = [...old.sources.map(x=>x.source),
    'packages/session-query/session-query/src/filters.ts',
    'packages/session-query/session-query/src/cursor.ts'];
  const response = await fetch(`https://api.github.com/repos/deepseek-ai/deepseek-harness/git/trees/${revision}?recursive=1`, {signal:AbortSignal.timeout(30000)});
  if(!response.ok) throw Error(`tree ${response.status}`);
  const tree=await response.json();
  if(tree.sha!==revision||tree.truncated) throw Error('wrong/incomplete tree');
  const results=await Promise.allSettled(selected.map(async source=>{
    const entry=tree.tree.find(x=>x.path===source&&x.type==='blob');
    if(!entry) throw Error(`missing ${source}`);
    const url=`https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/${revision}/${source}`;
    const result=await fetch(url,{signal:AbortSignal.timeout(30000)});
    if(!result.ok) throw Error(`${result.status} ${source}`);
    const body=Buffer.from(await result.arrayBuffer());
    const blob=crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex');
    if(blob!==entry.sha) throw Error(`blob mismatch ${source}`);
    const prior=old.sources.find(x=>x.source===source);
    if(prior&&prior.sha256!==sha256(body)) throw Error(`prior evidence mismatch ${source}`);
    // Existing immutable blobs are referenced, not duplicated into this delivery.
    const local=prior ? `../../XIAO-52/architecture/${prior.local}` : `evidence/upstream/${source.replaceAll('/','__')}`;
    if(!prior) {
      fs.mkdirSync(path.dirname(path.join(__dirname,local)),{recursive:true});
      fs.writeFileSync(path.join(__dirname,local),body);
    }
    return {source,url,gitBlob:blob,bytes:body.length,sha256:sha256(body),local};
  }));
  const failed=results.filter(x=>x.status==='rejected').map(x=>String(x.reason));
  write('evidence/upstream-sources.json',{revision,treeTruncated:false,checkedAt:new Date().toISOString(),
    sources:results.filter(x=>x.status==='fulfilled').map(x=>x.value),failures:failed});
  if(failed.length) throw Error(failed.join('\n'));
  const sources=[...git('ls-files','src/main/resources/db/migration','src/main/resources/session').split('\n'),
    'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcSessionRecords.java',
    'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java',
    'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/session/SessionQueryDeadline.java',
    'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/session/SessionContracts.java',
    'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/model/ModelProfiles.java',
    'src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent.java',
    'src/main/java/io/github/lovexiaopeng/jimu/api/SessionHistoryController.java',
    'src/main/java/io/github/lovexiaopeng/jimu/plugin/api/SessionEventPort.java',
    'src/main/java/io/github/lovexiaopeng/jimu/kernel/task/api/SessionEventLedger.java',
    'src/main/java/io/github/lovexiaopeng/jimu/bootstrap/DurableConfiguration.java',
    'tools/evaluation/application_grants.py',
    'tools/session_connection_verify.py','tools/session_cleanup_verify.py'];
  write('evidence/jimu-sources.json',{commit:git('rev-parse','HEAD'),tree:git('rev-parse','HEAD^{tree}'),
    originMaster:git('rev-parse','origin/master'),branch:git('branch','--show-current'),
    sources:sources.map(source=>{const body=fs.readFileSync(source);return {source,bytes:body.length,sha256:sha256(body),gitBlob:git('rev-parse',`HEAD:${source}`)};})});
  console.log(`Verified ${results.length} upstream Git blobs; ${sources.length} baseline Jimu files.`);
})().catch(e=>{console.error(e);process.exitCode=1;});
