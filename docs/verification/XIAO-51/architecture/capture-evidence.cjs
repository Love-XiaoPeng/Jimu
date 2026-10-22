// Run at repository root. Records source identity without copying application code.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cp = require('node:child_process');
const root = __dirname;
const base = '5d90fab841559e7349701c8284642525bc00da9d';
const git = (...args) => cp.execFileSync('git', args);
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const code = 'src/main/java/io/github/lovexiaopeng/jimu/';
const sources = [
  ['AGENTS.md', '# 积木'], ['README.md', '# 积木'], ['CONTRIBUTING.md', '# 参与'],
  ['docs/baseline/README.md', '# 已验收'],
  ['docs/baseline/XIAO-33-可行性与总体方案-v0.3.md', '## 7. API'],
  ['docs/baseline/XIAO-33-工程规范-v0.2.md', '## 3. 依赖'],
  ['docs/baseline/XIAO-33-tool-migration-evidence-v1.1.md', '| G28'],
  ['docs/baseline/XIAO-33-harness-tool-inventory-v1.1.json', '"id": "T67"'],
  ['docs/architecture.md', '#'], ['docs/adr/0005-workspace-edit-native-search.md', '#'],
  ['docs/adr/0009-task-todo-projection.md', '#'], ['docs/implementation-p4-todos.md', '#'],
  [code + 'adapters/storage/SecureWorkspaceStore.java', '  public record State('],
  [code + 'adapters/runtime/contract/WorkspaceRuntime.java', '  public void prepareEvaluation('],
  [code + 'adapters/runtime/contract/WorkspaceContracts.java', '  public static String description('],
  [code + 'adapters/runtime/contract/SchemaContract.java', '  public SchemaContract('],
  [code + 'adapters/runtime/search/NativeSearchRuntime.java', '  public SearchPort bind('],
  [code + 'adapters/runtime/search/NativeSearchQuery.java', 'public record'],
  [code + 'adapters/persistence/JdbcLedger.java', '  public Optional<Lease> claim('],
  [code + 'adapters/ai/springai/DurableAgent.java', '    private List<Call> slots('],
  [code + 'adapters/runtime/worker/WorkerLoop.java', 'operations.awaitDrained()'],
  [code + 'adapters/runtime/worker/LedgerMaintenance.java', '  public void start('],
  [code + 'kernel/task/api/ExecutionLedger.java', 'public interface'],
  [code + 'kernel/task/api/TodoLedger.java', 'public interface'],
  [code + 'api/TodoController.java', '@GetMapping'],
  [code + 'plugin/api/FilePort.java', 'public interface'],
  ['src/main/resources/todo/todo_write-0.1.0/plugin.yaml', 'manifestVersion'],
];
const result = sources.map(([source, anchor]) => {
  const body = git('show', `${base}:${source}`);
  const gitBlob = git('rev-parse', `${base}:${source}`).toString().trim();
  if (git('hash-object', source).toString().trim() !== gitBlob) throw Error(`Source changed: ${source}`);
  const line = body.toString('utf8').split('\n').findIndex(x => x.includes(anchor)) + 1;
  if (!line) throw Error(`Missing anchor: ${source}`);
  return {source, revision: base, gitBlob, bytes: body.length, sha256: sha(body), line, anchor};
});
const migrations = git('ls-tree', '-r', '--name-only', base, 'src/main/resources/db/migration').toString().trim().split('\n')
  .filter(p => /\/V(?:[1-9]|1[0-7])__/.test(p)).map(source => {
    const body = git('show', `${base}:${source}`);
    if (git('hash-object', source).toString().trim() !== git('rev-parse', `${base}:${source}`).toString().trim()) throw Error(`Migration changed: ${source}`);
    return {source, bytes: body.length, sha256: sha(body)};
  });
if (migrations.length !== 17) throw Error(`Expected 17 frozen migrations, got ${migrations.length}`);
fs.writeFileSync(path.join(root, 'evidence/jimu-sources.json'), JSON.stringify({revision: base,
  tree: git('rev-parse', `${base}^{tree}`).toString().trim(), sourceCount: result.length, sources: result}, null, 2) + '\n');
fs.writeFileSync(path.join(root, 'evidence/frozen-migrations.json'), JSON.stringify({revision: base, migrations}, null, 2) + '\n');
fs.writeFileSync(path.join(root, 'evidence/source-map.md'), '# 本次源码位置\n\n基于 `' + base + '`；仅列核验入口，非产品修改列表。\n\n' + result.map(x => `- \`${x.source}:${x.line}\` — \`${x.anchor.trim()}\``).join('\n') + '\n');
console.log(`Verified ${result.length} reviewed baseline blobs and 17 unchanged migrations.`);
