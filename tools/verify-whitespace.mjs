import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Exercise the same explicit Git rule used locally and in CI, without modifying source evidence.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'target', 'whitespace-evidence');
fs.mkdirSync(output, { recursive: true });
const fixture = fs.mkdtempSync(path.join(output, 'fixture-'));
const rule = 'blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol';
function git(args) {
  const result = spawnSync('git', args, { cwd: fixture, encoding: 'utf8' });
  if (result.error) throw result.error;
  return result;
}
function requireSuccess(args) {
  const result = git(args);
  if (result.status !== 0) throw new Error(result.stderr || result.stdout);
}
requireSuccess(['init', '--quiet']);
requireSuccess(['config', 'core.autocrlf', 'false']);
fs.copyFileSync(path.join(root, '.gitattributes'), path.join(fixture, '.gitattributes'));
const baseline = path.join(root, 'docs/baseline');
const filename = fs.readdirSync(baseline).find(name =>
  fs.readFileSync(path.join(baseline, name)).includes(Buffer.from('\r\n')));
if (!filename) throw new Error('Expected original CRLF source');
const source = path.join(baseline, filename);
const bytes = fs.readFileSync(source);
if (!bytes.includes(Buffer.from('\r\n'))) throw new Error('Expected original CRLF source');
fs.mkdirSync(path.join(fixture, 'docs/baseline'), { recursive: true });
const file = path.join(fixture, 'docs/baseline', filename);
fs.writeFileSync(file, bytes);
requireSuccess(['add', '.']);
const command = ['-c', 'core.whitespace=' + rule, 'diff', '--cached', '--check'];
const valid = git(command);
// A real trailing space before CRLF must still fail, including on a source-evidence path.
fs.writeFileSync(file, bytes.toString('utf8').replace('\r\n', ' \r\n'));
requireSuccess(['add', '.']);
const invalid = git(command);
const results = {
  rule,
  source: 'docs/baseline/' + filename,
  originalCrlf: { exit: valid.status, output: valid.stdout + valid.stderr },
  trailingSpace: { exit: invalid.status, output: invalid.stdout + invalid.stderr },
};
fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(results, null, 2) + '\n');
console.log(JSON.stringify(results, null, 2));
if (valid.status !== 0 || invalid.status !== 2 || !invalid.stdout.includes('trailing whitespace')) {
  process.exit(1);
}
