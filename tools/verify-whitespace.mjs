import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Exercise the same explicit Git rule used locally and in CI, without modifying source evidence.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'target', 'whitespace-evidence');
fs.mkdirSync(output, { recursive: true });
// Git objects can be read-only on Windows. Keep the disposable repository outside
// Maven's target tree and collect it here, rather than delegating it to Maven clean.
const temporary = fs.realpathSync(os.tmpdir());
const fixture = fs.mkdtempSync(path.join(temporary, 'jimu-whitespace-'));
const rule = 'blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol';
const results = { rule, fixture: path.basename(fixture), location: 'os.tmpdir' };
function git(args) {
  const result = spawnSync('git', args, { cwd: fixture, encoding: 'utf8' });
  if (result.error) throw result.error;
  return result;
}
function requireSuccess(args) {
  const result = git(args);
  if (result.status !== 0) throw new Error(result.stderr || result.stdout);
}
try {
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
  Object.assign(results, {
    source: 'docs/baseline/' + filename,
    originalCrlf: { exit: valid.status, output: valid.stdout + valid.stderr },
    trailingSpace: { exit: invalid.status, output: invalid.stdout + invalid.stderr },
  });
  if (valid.status !== 0 || invalid.status !== 2 || !invalid.stdout.includes('trailing whitespace')) {
    throw new Error('Whitespace checks did not produce the expected results');
  }
} catch (error) {
  results.error = error.message;
  process.exitCode = 1;
} finally {
  try {
    // Remove only the exact directory returned by this invocation's mkdtemp.
    // Do not scan for old fixtures, follow a substituted link, or clean the source tree.
    if (path.dirname(fixture) !== temporary ||
        !path.basename(fixture).startsWith('jimu-whitespace-') ||
        fs.lstatSync(fixture).isSymbolicLink() || fs.realpathSync(fixture) !== fixture) {
      throw new Error('Refusing cleanup outside the newly created temporary repository');
    }
    fs.rmSync(fixture, { recursive: true });
    results.cleanup = { scopeVerified: true, removed: !fs.existsSync(fixture) };
  } catch (error) {
    results.cleanup = { error: error.message };
    process.exitCode = 1;
  }
  fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(results, null, 2) + '\n');
  console.log(JSON.stringify(results, null, 2));
}
