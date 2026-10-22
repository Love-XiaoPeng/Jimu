// Read-only evidence fetch. Run from repository root with Node 22+.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const revision = '571902028a75c64c1d767f4ae5fd099485f0ddf0';
const root = __dirname;
const selected = [
  'LICENSE',
  'packages/session/tool-working-directory/src/index.ts',
  'packages/session/tool-working-directory/package.json',
  'packages/session/working-directory/src/index.ts',
  'packages/session/working-directory/tests/working-directory.spec.ts',
  'packages/session/working-directory/tests/admission.spec.ts',
  'packages/core/session/src/index.ts',
  'packages/core/session/src/types.ts',
  'packages/fs/fs/src/index.ts',
  'packages/fs/fs/src/types.ts',
  'packages/fs/fs-local/src/index.ts',
  'packages/fs/fs-local/src/fsio.ts',
  'packages/fs/tool-fs/src/index.ts',
  'packages/fs/tool-fs/src/read-target.ts',
  'packages/fs/tool-fs/src/session-cwd.ts',
  'packages/fs/tool-fs/src/write.ts',
  'packages/fs/tool-fs/src/edit.ts',
  'packages/session/session-projection/src/index.ts',
  'packages/session/session-projection/src/types.ts',
  'packages/session/session-persistence/src/index.ts',
  'packages/session/session-persistence-jsonl/src/index.ts',
  'docs/subsystems/working-directory.md',
  'docs/persistence-changes/2026-10-05-working-directory-attribution.md',
  'snapshots/session/working-directory/session.v4.jsonl',
  'snapshots/session/working-directory-consumers/session.v4.jsonl',
];
async function bytes(url) {
  const r = await fetch(url, {headers: {'User-Agent': 'Jimu-XIAO-51-architecture'}});
  if (!r.ok) throw new Error(`${r.status}: ${url}`);
  return Buffer.from(await r.arrayBuffer());
}
(async () => {
  const treePath = path.resolve('target/xiao51-architecture/upstream-tree.json');
  const tree = fs.existsSync(treePath) ? JSON.parse(fs.readFileSync(treePath)) :
    JSON.parse(await bytes(`https://api.github.com/repos/deepseek-ai/deepseek-harness/git/trees/${revision}?recursive=1`));
  if (tree.truncated || tree.sha !== revision) throw new Error('Incomplete/wrong upstream tree');
  fs.mkdirSync(path.join(root, 'evidence/upstream'), {recursive: true});
  const results = await Promise.allSettled(selected.map(async source => {
    const entry = tree.tree.find(x => x.path === source && x.type === 'blob');
    if (!entry) throw new Error(`Missing source: ${source}`);
    const body = await bytes(`https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/${revision}/${source}`);
    const gitBlob = crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex');
    if (gitBlob !== entry.sha) throw new Error(`Blob mismatch: ${source}`);
    const local = `evidence/upstream/${source.replaceAll('/', '__')}`;
    fs.writeFileSync(path.join(root, local), body);
    return {source, revision, gitBlob, bytes: body.length,
      sha256: crypto.createHash('sha256').update(body).digest('hex'), local};
  }));
  const failures = results.filter(x => x.status === 'rejected');
  fs.writeFileSync(path.join(root, 'evidence/upstream-sources.json'), JSON.stringify({revision,
    treeTruncated: false, sources: results.filter(x => x.status === 'fulfilled').map(x => x.value),
    failures: failures.map(x => String(x.reason))}, null, 2) + '\n');
  if (failures.length) throw new AggregateError(failures.map(x => x.reason));
  console.log(`Verified ${results.length} fixed Git blobs; source hashes recorded.`);
})().catch(e => { console.error(e); process.exitCode = 1; });
