import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Each mutation runs in a separate task-local copy. No mutation touches the working tree.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const evidence = path.join(root, 'target', 'negative-gates');
fs.mkdirSync(evidence, { recursive: true });
const main = 'src/main/java/io/github/lovexiaopeng/jimu/';
const cases = [
  { name: 'format', marker: /The following files had format violations/, mutate(dir) {
    fs.appendFileSync(path.join(dir, main, 'plugin/api/ToolResult.java'), '\n  \n');
    const file = path.join(dir, main, 'plugins/builtin/webfetch/FetchInput.java');
    fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace('String url', 'String    url'));
  } },
  { name: 'banned-dependency', marker: /BannedDependencies failed/, mutate(dir) {
    const file = path.join(dir, 'pom.xml');
    const pom = fs.readFileSync(file, 'utf8');
    fs.writeFileSync(file, pom.replace('\n  <dependencies>\n', '\n  <dependencies>\n    <dependency><groupId>com.alibaba.cloud.ai</groupId><artifactId>spring-ai-alibaba-agent-framework</artifactId><version>1.1.2.2</version><exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>\n'));
  } },
  { name: 'kernel-to-plugin', marker: /Architecture Violation/, mutate(dir) {
    fs.writeFileSync(path.join(dir, main, 'kernel/plugin/application/ForbiddenDependency.java'), 'package io.github.lovexiaopeng.jimu.kernel.plugin.application;\n\nimport io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;\n\npublic record ForbiddenDependency(WebFetchPlugin plugin) {}\n');
  }, format: true },
  { name: 'system-exit', marker: /calls method <java.lang.System.exit\(int\)>/, mutate(dir) {
    fs.writeFileSync(path.join(dir, main, 'plugins/builtin/webfetch/ForbiddenExit.java'), 'package io.github.lovexiaopeng.jimu.plugins.builtin.webfetch;\n\npublic final class ForbiddenExit {\n  public void stop(int status) {\n    System.exit(status);\n  }\n}\n');
  }, format: true },
  { name: 'runtime-exit', marker: /calls method <java.lang.Runtime.exit\(int\)>/, mutate(dir) {
    fs.writeFileSync(path.join(dir, main, 'plugins/builtin/webfetch/ForbiddenExit.java'), 'package io.github.lovexiaopeng.jimu.plugins.builtin.webfetch;\n\npublic final class ForbiddenExit {\n  public void stop(int status) {\n    Runtime.getRuntime().exit(status);\n  }\n}\n');
  }, format: true },
  { name: 'runtime-halt', marker: /calls method <java.lang.Runtime.halt\(int\)>/, mutate(dir) {
    fs.writeFileSync(path.join(dir, main, 'plugins/builtin/webfetch/ForbiddenExit.java'), 'package io.github.lovexiaopeng.jimu.plugins.builtin.webfetch;\n\npublic final class ForbiddenExit {\n  public void stop(int status) {\n    Runtime.getRuntime().halt(status);\n  }\n}\n');
  }, format: true },
  { name: 'controller-to-plugin', marker: /Architecture Violation/, mutate(dir) {
    fs.writeFileSync(path.join(dir, main, 'api/ForbiddenController.java'), 'package io.github.lovexiaopeng.jimu.api;\n\nimport io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;\n\npublic record ForbiddenController(WebFetchPlugin plugin) {}\n');
  }, format: true },
  { name: 'static-null-dereference', marker: /NP_ALWAYS_NULL|NP_LOAD_OF_KNOWN_NULL_VALUE/, mutate(dir) {
    fs.writeFileSync(path.join(dir, main, 'kernel/plugin/application/StaticBug.java'), 'package io.github.lovexiaopeng.jimu.kernel.plugin.application;\n\npublic final class StaticBug {\n  public int fail() {\n    Object missing = null;\n    return missing.hashCode();\n  }\n}\n');
  }, format: true },
  { name: 'duplicate-plugin', marker: /Duplicate plugin identity/, mutate(dir) {
    const file = path.join(dir, main, 'bootstrap/RuntimeConfiguration.java');
    fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace('List.of(descriptor)', 'List.of(descriptor, descriptor)'));
  } },
  { name: 'bad-schema', marker: /Unsupported Schema dialect/, mutate(dir) {
    const file = path.join(dir, 'src/main/resources/plugins/builtin/webfetch/input.schema.json');
    fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace('draft/2020-12', 'draft-07'));
  } },
  { name: 'missing-worker-runtime', marker: /Cannot run program|CreateProcess error|No such file/, args: ['-Djimu.it.java=/missing-jimu-required-java-runtime'] },
];
const results = [];
for (const test of cases.filter(test => process.argv.length <= 2 || process.argv.slice(2).includes(test.name))) {
  const dir = fs.mkdtempSync(path.join(evidence, test.name + '-'));
  for (const entry of fs.readdirSync(root)) {
    if (['.git', 'target', '.idea'].includes(entry)) continue;
    fs.cpSync(path.join(root, entry), path.join(dir, entry), { recursive: true });
  }
  test.mutate?.(dir);
  const wrapper = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw';
  const args = ['-B', '-ntp', ...(test.format ? ['spotless:apply'] : []), 'verify', ...(test.args || [])];
  const run = spawnSync(wrapper, args, { cwd: dir, encoding: 'utf8', shell: process.platform === 'win32', timeout: 180000, maxBuffer: 16 * 1024 * 1024 });
  const log = (run.stdout || '') + (run.stderr || '');
  fs.writeFileSync(path.join(evidence, test.name + '.log'), log);
  const expectedFailure = run.status !== null && run.status !== 0 && test.marker.test(log);
  const result = { case: test.name, exit: run.status, expectedFailure, error: run.error?.code };
  results.push(result);
  console.log(JSON.stringify(result));
  fs.writeFileSync(path.join(evidence, 'results.json'), JSON.stringify(results, null, 2) + '\n');
  if (!expectedFailure) process.exit(1);
}
