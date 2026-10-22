"""Architecture artifact checks; no network or product runtime execution."""
import hashlib
import json
from pathlib import Path
import jsonschema

root = Path(__file__).resolve().parent
contracts = root / 'contracts'
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
count = 0
for file in contracts.rglob('*.schema.json'):
    jsonschema.Draft202012Validator.check_schema(read(file))
    count += 1
catalog = read(contracts / 'catalog.json')
index = contracts / 'schema-index.json'
assert hashlib.sha256(index.read_bytes()).hexdigest() == catalog['schemaDigest']
for entry in read(index)['files']:
    assert hashlib.sha256((contracts / entry['path']).read_bytes()).hexdigest() == entry['sha256']
cases = [
    ('list_mcp_resources/input.schema.json', 'list-input.json'),
    ('list_mcp_resource_templates/input.schema.json', 'list-input.json'),
    ('read_mcp_resource/input.schema.json', 'read-input.json'),
    ('list_mcp_resources/output.schema.json', 'resources-output.json'),
    ('list_mcp_resource_templates/output.schema.json', 'templates-output.json'),
    ('read_mcp_resource/output.schema.json', 'read-output.json'),
    ('server-profile-v1.schema.json', 'server-profile.json'),
]
checks = []
for schema, example in cases:
    jsonschema.Draft202012Validator(read(contracts/schema), format_checker=jsonschema.FormatChecker()).validate(read(contracts/'examples'/example))
    checks.append({'case': example, 'result': 'PASS'})
negative = [
    ('list_mcp_resources/input.schema.json', {'server':'docs','endpoint':'http://127.0.0.1:1/mcp'}),
    ('list_mcp_resources/input.schema.json', {'server':'docs','cursor':'raw-upstream-cursor'}),
    ('list_mcp_resources/input.schema.json', {'server':'docs','cursor':None}),
    ('read_mcp_resource/input.schema.json', {'server':'docs','uri':'not a URI'}),
    ('read_mcp_resource/input.schema.json', {'server':'docs','uri':'jimu-fixture://docs/a','tenant':'tenant-b'}),
]
for schema, value in negative:
    errors = list(jsonschema.Draft202012Validator(read(contracts/schema), format_checker=jsonschema.FormatChecker()).iter_errors(value))
    assert errors, value
    checks.append({'case': value, 'result': 'REJECTED'})
profile = read(contracts/'examples/server-profile.json')
bad = dict(profile, endpoint='http://localhost:18081/mcp')
assert list(jsonschema.Draft202012Validator(read(contracts/'server-profile-v1.schema.json')).iter_errors(bad))
checks.append({'case':'DNS alias endpoint', 'result':'REJECTED'})
binding = {'version':1,'servers':{'docs':{'profile':profile,'digest':'a'*64}}}
jsonschema.Draft202012Validator(read(contracts/'task-binding-v1.schema.json')).validate(binding)
checks.append({'case':'task binding v1', 'result':'PASS'})
receipt = {'scope':'Architecture only; cross-field hash/URI/authorization checks and product execution not covered', 'schemasChecked':count, 'catalogFilesChecked':len(read(index)['files']), 'cases':checks}
(root/'evidence'/'contract-check.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'PASS {count} schemas, 9 indexed files, {len(checks)} example/negative checks')
