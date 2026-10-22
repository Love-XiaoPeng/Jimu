// Architecture artifacts only; does not modify application resources or register tools.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = __dirname;
const dialect = 'https://json-schema.org/draft/2020-12/schema';
function put(name, value) {
  const file = path.join(root, name);
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n');
}
function object(properties, required = Object.keys(properties)) {
  return {type: 'object', additionalProperties: false, properties, required};
}
function schema(id, body) { return {$schema: dialect, $id: `urn:jimu:directory:${id}:v1`, ...body}; }
const logical = {type: 'string', minLength: 1, maxLength: 512, pattern: '^/',
  description: 'Canonical task-logical absolute path. Runtime also checks UTF-16/UTF-8, components and fixed namespace; never an OS path.'};
const id = {type: 'string', format: 'uuid'};
const digest = {type: 'string', pattern: '^[0-9a-f]{64}$'};
const revision = {type: 'integer', minimum: 0, maximum: 20};
const source = object({invocationId: id, checkpointId: id, providerCallId: {type: 'string', minLength: 1, maxLength: 256},
  createdAttemptId: id, committedAttemptId: id, fence: {type: 'integer', minimum: 1},
  eventId: {type: 'string', pattern: '^[0-9a-f-]{36}:[1-9][0-9]*$'},
  outputSha256: digest, updatedAt: {type: 'string', format: 'date-time'}});
put('contracts/working_directory-0.1.0/input.schema.json', schema('input', object({
  cd: {type: 'string', minLength: 1, maxLength: 512,
    description: 'Existing logical directory; absolute starts /, relative uses current directory. Omit to read. Runtime rejects escapes and invalid Unicode.'},
}, [])));
put('contracts/working_directory-0.1.0/output.schema.json', schema('output', object({cwd: logical})));
put('contracts/working_directory-0.1.0/description.txt',
  'Read the task\'s current logical directory, or change it with cd to an existing logical directory. The fixed authorized root is /. Relative paths use the current directory; . and .. stay inside that root. Directories are ancestors of existing task files; empty directories are unavailable. This changes no host or process directory and grants no permissions.\n');
put('contracts/working_directory-0.1.0/plugin.yaml', {
  manifestVersion: 1, id: 'jimu.workspace.working_directory', version: '0.1.0', kind: 'tool',
  hostApi: '>=0.1.0 <0.2.0', entry: {type: 'trusted-jvm', class: 'io.github.lovexiaopeng.jimu.plugins.builtin.workspace.WorkingDirectoryPlugin'},
  capabilities: [{name: 'working_directory', inputSchema: 'input.schema.json', outputSchema: 'output.schema.json', sideEffect: 'write'}],
  permissions: {filesystem: ['host-task-workspace']}, secretRefs: [], dependencies: [],
  limits: {timeoutMs: 30000, outputBytes: 4096}, healthCheck: {operation: 'directory-ledger-workspace-readiness', timeoutMs: 2000},
  errorContract: 'jimu-invocation-result', eventTypes: ['invocation.started', 'invocation.completed', 'invocation.failed', 'working_directory.changed', 'working_directory.content_deleted'],
});
const policy = {
  version: 1, profile: 'local-logical-directory-v1', directoryVersion: 1, pathPolicyVersion: 1,
  workspaceFormat: 3, initialCwd: '/', directoryKind: 'file-ancestors', slotOrder: 'response-array-serial',
  capabilities: ['working_directory', 'read', 'write', 'edit'],
  maxPathUtf16: 512, maxPathUtf8Bytes: 2048, maxDirectoryInputBytes: 4096,
  maxDirectoryOutputBytes: 4096, maxToolInputBytes: 65535, maxToolOutputBytes: 65536,
  maxJsonDepth: 8, maxFileBytes: 32768, maxCurrentFileBytes: 262144,
  maxWorkspaceEnvelopeBytes: 2097152, maxScopeFilesystemBytes: 4194304, maxScopePartFiles: 8,
  maxDatabaseBytes: 2097152, metadataReserveBytes: 65536,
  reservedTaskBytes: 8388608, maxModelRequestBytes: 65536, maxCheckpointBytes: 131072,
  maxModelResponseBytes: 65536, maxCallsPerResponse: 4, maxModelRequests: 8,
  maxToolInvocations: 20, maxAttempts: 8, maxEvents: 256, maxSummaryBytes: 8192,
  retentionSeconds: 604800, timeoutMs: 30000, lockWaitSeconds: 2,
};
const bindingProps = Object.fromEntries(Object.entries(policy).map(([key, value]) => [key, {const: value}]));
bindingProps.namespace = digest;
bindingProps.catalogDigest = digest;
put('contracts/task-binding-v1.schema.json', schema('binding', object(bindingProps)));
const view = object({version: {const: 1}, taskId: id, asOfSeq: {type: 'integer', minimum: 0},
  revision, state: {enum: ['ABSENT', 'PRESENT', 'PURGING', 'PURGED']},
  cwd: {anyOf: [logical, {type: 'null'}]}, source: {anyOf: [source, {type: 'null'}]},
  retainUntil: {type: ['string', 'null'], format: 'date-time'}});
view.allOf = [
  {if: {properties: {state: {const: 'ABSENT'}}}, then: {properties: {revision: {const: 0}, cwd: {type: 'null'}, source: {type: 'null'}, retainUntil: {type: 'null'}}}},
  {if: {properties: {state: {const: 'PRESENT'}}}, then: {properties: {cwd: logical, retainUntil: {type: 'string'}}}},
  {if: {properties: {state: {enum: ['PURGING', 'PURGED']}}}, then: {properties: {cwd: {type: 'null'}, retainUntil: {type: 'string'}}}},
  {if: {properties: {revision: {const: 0}}}, then: {properties: {source: {type: 'null'}}}, else: {properties: {source}}},
];
put('contracts/directory-view-v1.schema.json', schema('view', view));
// YAML 1.2 permits JSON: plugin.yaml is the sole manifest authority.
const toolRoot = path.join(root, 'contracts/working_directory-0.1.0');
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const catalog = {version: 1, profile: policy.profile, directoryVersion: 1, pathPolicyVersion: 1,
  tools: [{name: 'working_directory', version: '0.1.0', pluginId: 'jimu.workspace.working_directory',
    inputSha256: sha(fs.readFileSync(path.join(toolRoot, 'input.schema.json'))),
    outputSha256: sha(fs.readFileSync(path.join(toolRoot, 'output.schema.json'))),
    manifestSha256: sha(fs.readFileSync(path.join(toolRoot, 'plugin.yaml'))),
    descriptionSha256: sha(fs.readFileSync(path.join(toolRoot, 'description.txt')))}]};
for (const name of ['read', 'write', 'edit']) {
  const base = path.resolve('src/main/resources/plugins/builtin', name);
  catalog.tools.push({name, version: '0.1.0', pluginId: `jimu.workspace.${name}`,
    inputSha256: sha(fs.readFileSync(path.join(base, 'input.schema.json'))),
    outputSha256: sha(fs.readFileSync(path.join(base, 'output.schema.json'))),
    manifestSha256: sha(fs.readFileSync(path.join(base, 'plugin.yaml'))),
    source: `5d90fab841559e7349701c8284642525bc00da9d:src/main/resources/plugins/builtin/${name}`});
}
put('contracts/catalog.json', catalog);
const taskId = '11111111-1111-4111-8111-111111111111';
const binding = {...policy, namespace: sha(`tenant-a:${taskId}`), catalogDigest: sha(fs.readFileSync(path.join(root, 'contracts/catalog.json')))};
put('contracts/examples/binding.json', binding);
const baseline = {version: 1, taskId, asOfSeq: 1, revision: 0, state: 'PRESENT', cwd: '/', source: null, retainUntil: '2026-10-30T00:00:00Z'};
put('contracts/examples/initial.json', baseline);
put('contracts/examples/absent.json', {...baseline, state: 'ABSENT', cwd: null, retainUntil: null});
const changed = {...baseline, asOfSeq: 18, revision: 1, cwd: '/子目录', source: {
  invocationId: '22222222-2222-4222-8222-222222222222', checkpointId: '33333333-3333-4333-8333-333333333333',
  providerCallId: 'call-cd-1', createdAttemptId: '44444444-4444-4444-8444-444444444444',
  committedAttemptId: '55555555-5555-4555-8555-555555555555', fence: 2,
  eventId: `${taskId}:17`, outputSha256: sha('{"cwd":"/子目录"}'), updatedAt: '2026-10-23T00:00:00Z'}};
put('contracts/examples/changed.json', changed);
for (const state of ['PURGING', 'PURGED']) put(`contracts/examples/${state.toLowerCase()}.json`, {...changed, state, cwd: null, asOfSeq: 30});
const cases = [];
function c(name, filename, value, valid) { cases.push({name, schema: filename, value, valid}); }
const input = 'working_directory-0.1.0/input.schema.json';
for (const value of [{}, {cd: '/'}, {cd: '.'}, {cd: '..'}, {cd: '../子目录'}, {cd: '😀'}, {cd: 'x'.repeat(512)}]) c('input positive ' + JSON.stringify(value).slice(0,40), input, value, true);
for (const value of [null, [], '', {cd: null}, {cd: 1}, {cd: ''}, {cd: 'x'.repeat(513)}, {cd: '/', taskId}, {sessionId: taskId}]) c('input rejected ' + JSON.stringify(value).slice(0,40), input, value, false);
c('output root', 'working_directory-0.1.0/output.schema.json', {cwd: '/'}, true);
c('output child', 'working_directory-0.1.0/output.schema.json', {cwd: '/a/子目录'}, true);
for (const value of [{}, {cwd: 'relative'}, {cwd: null}, {cwd: '/', root: '/host'}]) c('output rejected', 'working_directory-0.1.0/output.schema.json', value, false);
for (const name of ['initial', 'absent', 'changed', 'purging', 'purged']) c(name, 'directory-view-v1.schema.json', JSON.parse(fs.readFileSync(path.join(root, `contracts/examples/${name}.json`))), true);
c('initial source forbidden', 'directory-view-v1.schema.json', {...baseline, source: changed.source}, false);
c('changed source required', 'directory-view-v1.schema.json', {...changed, source: null}, false);
c('absent cannot expose cwd', 'directory-view-v1.schema.json', {...baseline, state: 'ABSENT', retainUntil: null}, false);
c('purged cannot expose cwd', 'directory-view-v1.schema.json', {...changed, state: 'PURGED'}, false);
c('binding', 'task-binding-v1.schema.json', binding, true);
c('binding unsupported', 'task-binding-v1.schema.json', {...binding, version: 2}, false);
c('binding fixed root', 'task-binding-v1.schema.json', {...binding, initialCwd: '/tmp'}, false);
c('binding no search mix', 'task-binding-v1.schema.json', {...binding, capabilities: [...policy.capabilities, 'glob']}, false);
put('evidence/schema-cases.json', cases);
console.log(`Generated 4 schemas, 1 authoritative manifest, catalog and ${cases.length} schema cases.`);
