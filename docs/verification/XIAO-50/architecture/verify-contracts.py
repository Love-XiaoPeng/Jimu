"""Architecture contract examples and cross-language semantic fixtures; no product DB/runtime."""
from pathlib import Path
import copy
import hashlib
import json
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parent
C = ROOT / 'contracts'
E = ROOT / 'evidence'
load = lambda p: json.loads(p.read_text(encoding='utf-8'))
write = lambda p, v: p.write_text(json.dumps(v, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
schemas = {p.relative_to(C).as_posix(): load(p) for p in C.rglob('*.schema.json')}
for s in schemas.values():
    Draft202012Validator.check_schema(s)
validators = {p: Draft202012Validator(s, format_checker=FormatChecker()) for p, s in schemas.items()}
cases = []
def case(name, schema, value, valid=True):
    actual = validators[schema].is_valid(value)
    assert actual == valid, (name, actual, valid)
    cases.append(dict(name=name, schema=schema, value=value, valid=valid))

examples = {
 'binding':'task-binding-v1.schema.json',
 'absent':'todo-view-v1.schema.json','unwritten':'todo-view-v1.schema.json',
 'empty-view':'todo-view-v1.schema.json','latest-view':'todo-view-v1.schema.json',
 'purged-view':'todo-view-v1.schema.json','purged-unwritten':'todo-view-v1.schema.json',
 'empty-input':'todo_write-0.1.0/input.schema.json',
 'replacement-input':'todo_write-0.1.0/input.schema.json',
 'empty-output':'todo_write-0.1.0/output.schema.json',
 'replacement-output':'todo_write-0.1.0/output.schema.json'
}
for name, s in examples.items():
    case('example '+name, s, load(C/'examples'/f'{name}.json'))
inp='todo_write-0.1.0/input.schema.json'
out='todo_write-0.1.0/output.schema.json'
for name, value in [
 ('missing todos',{}),('unknown root',{'todos':[],'taskId':'other'}),
 ('null todos',{'todos':None}),('todos scalar',{'todos':'x'}),
 ('null item',{'todos':[None]}),('missing content',{'todos':[{'status':'pending'}]}),
 ('unknown item',{'todos':[{'content':'x','status':'pending','id':'x'}]}),
 ('invalid status',{'todos':[{'content':'x','status':'doing'}]}),
 ('coerced content',{'todos':[{'content':3,'status':'pending'}]}),
 ('null content',{'todos':[{'content':None,'status':'pending'}]}),
 ('33 items',{'todos':[{'content':str(i),'status':'pending'} for i in range(33)]}),
 ('1025 code points',{'todos':[{'content':'x'*1025,'status':'pending'}]})]:
    case(name,inp,value,False)
case('32 items',inp,{'todos':[{'content':str(i),'status':'pending'} for i in range(32)]})
for field, bad in [('version',2),('allowParallelInProgress',True),('maxItems',33),('catalogDigest','bad')]:
    b=load(C/'examples/binding.json'); b[field]=bad
    case('binding '+field,'task-binding-v1.schema.json',b,False)
b=load(C/'examples/binding.json'); b['session']='x'
case('binding unknown','task-binding-v1.schema.json',b,False)
for name, mutate in [
 ('present null',lambda x:x.update(todos=None)),
 ('present zero revision',lambda x:x.update(revision=0)),
 ('present no source',lambda x:x.update(source=None)),
 ('view unknown',lambda x:x.update(tenant='other'))]:
    v=load(C/'examples/latest-view.json'); mutate(v)
    case(name,'todo-view-v1.schema.json',v,False)
v=load(C/'examples/unwritten.json'); v['todos']=[]
case('unwritten not empty list','todo-view-v1.schema.json',v,False)
for name, mutate in [
 ('missing counts',lambda x:x.pop('counts')),
 ('bad count name',lambda x:x['counts'].update(in_progress=0)),
 ('negative count',lambda x:x['counts'].update(pending=-1)),
 ('two active counts',lambda x:x['counts'].update(inProgress=2)),
 ('result revision not tool field',lambda x:x.update(revision=1))]:
    o=load(C/'examples/empty-output.json'); mutate(o)
    case(name,out,o,False)
write(E/'schema-cases.json',cases)

semantics=[]
def semantic(name, todos, valid, expected=None, cloud_only=False, raw=None):
    semantics.append(dict(name=name, input={'todos':todos}, valid=valid,
                          expected=expected, cloudOnly=cloud_only, raw=raw))
def good(name,todos,expected=None):
    normalized=todos if expected is None else expected
    counts={'pending':0,'inProgress':0,'completed':0}
    for t in normalized:
        counts[{'pending':'pending','in_progress':'inProgress','completed':'completed'}[t['status']]]+=1
    semantic(name,todos,True,{'todos':normalized,'counts':counts})
good('empty',[])
good('zero active',[{'content':'x','status':'pending'}])
good('one active',[{'content':'x','status':'in_progress'}])
good('ordered replacement',[{'content':'b','status':'completed'},{'content':'a','status':'pending'}])
good('case-sensitive',[{'content':'A','status':'pending'},{'content':'a','status':'pending'}])
good('normalization forms preserved',[{'content':'é','status':'pending'},{'content':'e\u0301','status':'pending'}])
trim=[*range(9,14),0x20,0xA0,0x1680,*range(0x2000,0x200B),0x2028,0x2029,0x202F,0x205F,0x3000,0xFEFF]
for cp in trim:
    good(f'ECMAScript trim U+{cp:04X}',[{'content':chr(cp)+'x'+chr(cp),'status':'pending'}],[{'content':'x','status':'pending'}])
for cp in [0x0085,0x180E,0x200B]:
    good(f'preserve U+{cp:04X}',[{'content':chr(cp)+'x'+chr(cp),'status':'pending'}])
good('1024 UTF8 exact',[{'content':'😀'*256,'status':'pending'}])
semantic('1028 UTF8 rejects',[{'content':'😀'*257,'status':'pending'}],False,cloud_only=True)
semantic('empty content',[{'content':'','status':'pending'}],False)
semantic('whitespace only',[{'content':'\ufeff\u00a0 ','status':'pending'}],False)
semantic('trimmed duplicate',[{'content':' x ','status':'pending'},{'content':'x','status':'completed'}],False)
semantic('two active',[{'content':'x','status':'in_progress'},{'content':'y','status':'in_progress'}],False)
semantic('33 entries',[{'content':str(i),'status':'pending'} for i in range(33)],False,cloud_only=True)
semantic('total list bytes',[{'content':str(i)+'x'*1020,'status':'pending'} for i in range(9)],False,cloud_only=True)
semantic('input bytes',[],False,cloud_only=True,raw=' '*16385+'{"todos":[]}')
semantic('unpaired surrogate',[],False,cloud_only=True,raw='{"todos":[{"content":"\\ud800","status":"pending"}]}')
semantic('duplicate key',[],False,cloud_only=True,raw='{"todos":[],"todos":[]}')
semantic('trailing JSON',[],False,cloud_only=True,raw='{"todos":[]} {}')
write(E/'semantic-cases.json',semantics)

catalog=load(C/'catalog.json'); variant=catalog['variants'][0]
for field,file in [('inputSha256','input.schema.json'),('outputSha256','output.schema.json'),('manifestSha256','plugin.yaml'),('descriptionSha256','description.txt')]:
    assert variant[field]==hashlib.sha256((C/variant['root']/file).read_bytes()).hexdigest()
assert load(C/'examples/binding.json')['catalogDigest']==hashlib.sha256((C/'catalog.json').read_bytes()).hexdigest()
manifest=load(C/'todo_write-0.1.0/plugin.yaml')
assert manifest['id']=='jimu.todo.write' and manifest['limits']['outputBytes']==16384
result={'scope':'Architecture schemas/examples/hash checks only; no product runtime',
        'schemas':len(schemas),'schemaCases':len(cases),'valid':sum(c['valid'] for c in cases),
        'invalid':sum(not c['valid'] for c in cases),'semanticFixtures':len(semantics)}
write(E/'contract-check.json',result)
print(json.dumps(result,ensure_ascii=False))
