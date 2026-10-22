const fs=require('node:fs');
const path=require('node:path');
const root=__dirname;
const src=fs.readFileSync(path.join(root,'evidence/packages__todo__tool-todo__src__index.ts'),'utf8');
const start=src.indexOf('function toTodoList(');
const end=src.indexOf('\n}',start)+2;
let fn=src.slice(start,end);
fn=fn.replace(fn.split('\n')[0],'function toTodoList(raw, allowParallel) {')
  .replace('const todos: TodoItem[] = []','const todos = []')
  .replace('new Set<string>()','new Set()')
  .replace("item.status as TodoItem['status']",'item.status');
const normalize=Function(fn+'; return toTodoList;')();
let checked=0,cloudOnly=0;
for(const c of JSON.parse(fs.readFileSync(path.join(root,'evidence/semantic-cases.json'),'utf8'))) {
  if(c.cloudOnly) {cloudOnly++;continue;}
  let valid=true,value;
  try {value=normalize(c.input.todos,false);} catch {valid=false;}
  if(valid!==c.valid || valid && JSON.stringify(value)!==JSON.stringify(c.expected.todos)) throw Error(c.name);
  checked++;
}
console.log(`PASS extracted fixed upstream toTodoList body: ${checked} matching cases; ${cloudOnly} explicit cloud-bound/strict-JSON cases excluded.`);
console.log('Only the source body was evaluated after removing TypeScript annotations; full upstream package/tests were not run.');
