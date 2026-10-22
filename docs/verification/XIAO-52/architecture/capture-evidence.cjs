const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cp = require('node:child_process');
const root = __dirname;
const base = '270a5d15611f5d7e59bbbd1d96c94d07567fc858';
const git = (...args) => cp.execFileSync('git',args,{maxBuffer:32*1024*1024});
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const selected = [
  'AGENTS.md','README.md','CONTRIBUTING.md','docs/baseline/README.md',
  'docs/baseline/XIAO-33-工程规范-v0.2.md','docs/baseline/XIAO-33-可行性与总体方案-v0.3.md',
  'docs/baseline/XIAO-33-tool-migration-evidence-v1.1.md','docs/baseline/XIAO-33-harness-tool-inventory-v1.1.json',
  'docs/architecture.md','docs/adr/0004-p2-durable-execution.md','docs/adr/0009-task-todo-projection.md',
  'docs/implementation-p4-working-directory.md','docs/implementation-p4-todos.md',
  'src/main/java/io/github/lovexiaopeng/jimu/kernel/task/api/Tasks.java',
  'src/main/java/io/github/lovexiaopeng/jimu/kernel/task/api/ExecutionLedger.java',
  'src/main/java/io/github/lovexiaopeng/jimu/kernel/task/api/WorkingDirectoryLedger.java',
  'src/main/java/io/github/lovexiaopeng/jimu/plugin/api/ExecutionContext.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/OpenAiProtocol.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/events/DatabaseEventFeed.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/model/ModelProfiles.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/directory/DirectoryContracts.java',
  'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/directory/DirectoryRuntime.java',
  'src/main/java/io/github/lovexiaopeng/jimu/api/TaskController.java',
  'src/main/resources/directory/catalog.json','src/main/resources/directory/examples/binding.json',
  'src/main/resources/directory/working_directory-0.1.0/plugin.yaml','tools/evaluation/application_grants.py','pom.xml',
];
const migrations = git('ls-tree','-r','--name-only',base,'src/main/resources/db/migration').toString().trim().split('\n');
function source(file) {
  const bytes = git('show',`${base}:${file}`);
  const worktree = fs.readFileSync(file);
  return {path:file,revision:base,gitBlob:git('rev-parse',`${base}:${file}`).toString().trim(),
    bytes:bytes.length,sha256:hash(bytes),worktreeSha256:hash(worktree),worktreeExact:bytes.equals(worktree)};
}
const records=selected.map(source);
const frozen=migrations.map(source);
if (frozen.length !== 18 || [...records,...frozen].some(x=>!x.worktreeExact))
  throw Error('Baseline worktree mismatch/migration count');
const baseline={revision:base,tree:git('rev-parse',`${base}^{tree}`).toString().trim(),
  branch:git('branch','--show-current').toString().trim(),
  originMaster:git('rev-parse','origin/master').toString().trim(),sources:records};
fs.writeFileSync(path.join(root,'evidence/jimu-sources.json'),JSON.stringify(baseline,null,2)+'\n');
fs.writeFileSync(path.join(root,'evidence/frozen-migrations.json'),JSON.stringify({revision:base,
  assertion:'V1-V18 unchanged; design SQL not applied',sources:frozen},null,2)+'\n');
const upstream=JSON.parse(fs.readFileSync(path.join(root,'evidence/upstream-sources.json')));
for(const entry of upstream.sources) {
  const bytes=fs.readFileSync(path.join(root,entry.local));
  const blob=crypto.createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
  if (blob!==entry.gitBlob || hash(bytes)!==entry.sha256) throw Error(`Bad upstream ${entry.source}`);
}
const entries=[
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/kernel/task/api/Tasks.java','record Event('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java','public List<Event> events('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java','private void event('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java','private Map<String, Object> fenced('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java','public Step prepare('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java','public void modelResponded('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java','public void check()'],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence/JdbcLedger.java','private boolean writersQuiescent('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent.java','private void runAttempt('],
  ['Jimu','src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/model/ModelProfiles.java','public void requireTodo('],
  ['Upstream','packages/experimental/tool-session-query/src/index.ts',"name: 'session_event_read'"],
  ['Upstream','packages/experimental/tool-session-query/src/operations.ts','async function executeEventRead('],
  ['Upstream','packages/experimental/tool-session-query/src/operations.ts','const stepStartSeq ='],
  ['Upstream','packages/experimental/tool-session-query/src/input.ts','function assertNonNegativeSafeInteger('],
  ['Upstream','packages/experimental/tool-session-query/src/workspace-access.ts','function callerOf('],
  ['Upstream','packages/experimental/tool-session-query/src/workspace-access.ts','async function authorizeTarget('],
  ['Upstream','packages/experimental/tool-session-query/src/workspace-access.ts','function assertObservedTargetAuthorized('],
  ['Upstream','packages/experimental/tool-session-query/src/presentation.ts','function formatEventRead('],
  ['Upstream','packages/experimental/tool-session-query/src/presentation.ts','function formatNeighbor('],
  ['Upstream','packages/session-query/session-query/src/index.ts','async readEvent('],
  ['Upstream','packages/session-query/session-query/src/index.ts','private async _readEvent('],
  ['Upstream','packages/session-query/session-query/src/corpus.ts','async load(sessionId:'],
  ['Upstream','packages/session-query/session-query-sqlite/src/index.ts','export class SqliteSessionQueryEngine extends SessionQueryEngine'],
  ['Upstream','packages/session-query/session-query/src/config.ts','SESSION_QUERY_READ_WINDOW_MAX ='],
  ['Upstream','packages/session/session-projection/src/index.ts','stateOf<K'],
  ['Upstream','packages/core/agent-loop/src/index.ts','export const turnBoundaryProjectionDefinition ='],
  ['Upstream','packages/experimental/tool-session-query/tests/tool-session-query.spec.ts','renders unabridged fenced target JSON'],
  ['Upstream','packages/session-query/session-query/tests/session-query.spec.ts','returns a bounded detached raw-event window'],
];
const lines=['# Source map','','Line numbers refer to the exact revisions in the source manifests. These are inspection evidence, not executed upstream/product tests.','',
  '| Repository | File:line | Inspected anchor |','| --- | --- | --- |'];
for(const [repo,file,needle] of entries) {
  const body=repo==='Jimu'?git('show',`${base}:${file}`).toString():fs.readFileSync(path.join(root,upstream.sources.find(x=>x.source===file).local),'utf8');
  const line=body.split('\n').findIndex(x=>x.includes(needle))+1;
  if(!line) throw Error(`Missing anchor ${file}: ${needle}`);
  lines.push(`| ${repo} | \`${file}:${line}\` | \`${needle}\` |`);
}
fs.writeFileSync(path.join(root,'evidence/source-map.md'),lines.join('\n')+'\n');
console.log(`Verified ${records.length} Jimu sources, ${frozen.length} frozen migrations, ${upstream.sources.length} upstream blobs; wrote line map.`);
