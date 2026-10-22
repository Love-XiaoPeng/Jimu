const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = __dirname;
const hash = body => crypto.createHash('sha256').update(body).digest('hex');
function files(dir) {
  return fs.readdirSync(dir, {withFileTypes: true}).flatMap(entry => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? files(file) : [file];
  });
}
const manifestPath = path.join(root, 'PACKAGE-MANIFEST.json');
const current = files(root).filter(p => p !== manifestPath).map(file => {
  const body = fs.readFileSync(file);
  return {path: path.relative(root, file).replaceAll('\\', '/'), bytes: body.length, sha256: hash(body)};
}).sort((a,b) => a.path.localeCompare(b.path, 'en'));
if (process.argv.includes('--record')) fs.writeFileSync(manifestPath, JSON.stringify({version: 1, files: current}, null, 2) + '\n');
if (JSON.stringify(JSON.parse(fs.readFileSync(manifestPath)).files) !== JSON.stringify(current)) throw Error('Package file set/hash mismatch');
const upstream = JSON.parse(fs.readFileSync(path.join(root, 'evidence/upstream-sources.json')));
if (upstream.failures.length || upstream.sources.length !== 25) throw Error('Incomplete upstream evidence');
for (const s of upstream.sources) {
  const body = fs.readFileSync(path.join(root, s.local));
  const blob = crypto.createHash('sha1').update(`blob ${body.length}\0`).update(body).digest('hex');
  if (hash(body) !== s.sha256 || blob !== s.gitBlob || body.length !== s.bytes) throw Error(s.source);
}
console.log(`PASS ${current.length} package files; 25 upstream Git blobs; 17 baseline migration hashes recorded. No product runtime proof.`);
