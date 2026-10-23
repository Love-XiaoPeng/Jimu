// Evidence only; Node is not an application runtime dependency.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const revision = '571902028a75c64c1d767f4ae5fd099485f0ddf0';
const selected = [
  'LICENSE', 'docs/subsystems/session-query.md', 'docs/subsystems/session-projection.md',
  'packages/experimental/tool-session-query/package.json',
  ...['index', 'input', 'operations', 'presentation', 'service-boundary', 'workspace-access']
    .map(x => `packages/experimental/tool-session-query/src/${x}.ts`),
  ...['tool-session-query', 'sqlite-integration'].map(x => `packages/experimental/tool-session-query/tests/${x}.spec.ts`),
  ...['index', 'types', 'config', 'sources', 'cold-read', 'corpus', 'observation', 'extraction', 'documents']
    .map(x => `packages/session-query/session-query/src/${x}.ts`),
  'packages/session-query/session-query/tests/session-query.spec.ts',
  ...['index', 'query', 'schema'].map(x => `packages/session-query/session-query-sqlite/src/${x}.ts`),
  'packages/session-query/session-query-sqlite/tests/query.spec.ts',
  'packages/session-query/session-query-sqlite/tests/sqlite.spec.ts',
  'packages/session/session-projection/src/index.ts', 'packages/session/session-projection/src/types.ts',
  'packages/session/session-persistence/src/index.ts', 'packages/session/session-persistence/src/handle.ts',
  'packages/session/session-persistence/src/storage-contract.ts',
  'packages/session/session-persistence-jsonl/src/index.ts', 'packages/session/session-persistence-jsonl/src/storage.ts',
  'packages/core/session/src/index.ts', 'packages/core/session/src/types.ts',
  'packages/core/agent/src/projection.ts', 'packages/core/agent/src/index.ts', 'packages/core/agent/src/types.ts',
  'packages/core/agent-loop/src/index.ts', 'packages/core/agent-loop/src/agent.ts',
];
(async () => {
  const treeFile = 'target/xiao52-architecture/upstream-tree.json';
  let tree;
  if (fs.existsSync(treeFile)) tree = JSON.parse(fs.readFileSync(treeFile));
  else {
    const result = await fetch(`https://api.github.com/repos/deepseek-ai/deepseek-harness/git/trees/${revision}?recursive=1`,
      {signal:AbortSignal.timeout(30000)});
    if (!result.ok) throw Error(`tree ${result.status}`);
    tree = await result.json();
    fs.mkdirSync(path.dirname(treeFile), {recursive:true});
    fs.writeFileSync(treeFile, JSON.stringify(tree));
  }
  if (tree.sha !== revision || tree.truncated) throw Error('wrong/incomplete tree');
  fs.mkdirSync(path.join(__dirname, 'evidence/upstream'), {recursive:true});
  const results = await Promise.allSettled(selected.map(async source => {
    const entry = tree.tree.find(x => x.path === source && x.type === 'blob');
    if (!entry) throw Error(`missing ${source}`);
    const url = `https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/${revision}/${source}`;
    const response = await fetch(url, {signal:AbortSignal.timeout(30000)});
    if (!response.ok) throw Error(`${response.status} ${source}`);
    const body = Buffer.from(await response.arrayBuffer());
    const blob = crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex');
    if (blob !== entry.sha) throw Error(`blob mismatch ${source}`);
    const local = `evidence/upstream/${source.replaceAll('/', '__')}`;
    fs.writeFileSync(path.join(__dirname, local), body);
    return {source, url, gitBlob:blob, bytes:body.length,
      sha256:crypto.createHash('sha256').update(body).digest('hex'), local};
  }));
  const failed = results.filter(x => x.status === 'rejected');
  fs.writeFileSync(path.join(__dirname, 'evidence/upstream-sources.json'), JSON.stringify({revision,
    treeTruncated:false, sources:results.filter(x=>x.status==='fulfilled').map(x=>x.value),
    failures:failed.map(x=>String(x.reason))}, null, 2)+'\n');
  if (failed.length) throw Error(JSON.stringify(failed.map(x=>String(x.reason))));
  console.log(`Verified ${results.length} fixed upstream Git blobs.`);
})().catch(e=>{console.error(e);process.exitCode=1;});
