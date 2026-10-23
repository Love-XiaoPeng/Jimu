const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const root=__dirname;
const repo=path.resolve(root,'../../../..');
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
function files(dir) {
  return fs.readdirSync(dir,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()
    ? files(path.join(dir,entry.name)) : [path.join(dir,entry.name)]);
}
const relative=file=>path.relative(repo,file).replaceAll('\\','/');
const manifestFile=path.join(root,'PACKAGE-MANIFEST.json');
const selected=[...files(root).filter(x=>x!==manifestFile),
  path.join(repo,'docs/adr/0011-authorized-session-event-read.md'),path.join(repo,'.gitattributes')].sort();
const manifest={scope:'Architecture design and provenance, not product runtime validation',
  productBaseline:'270a5d15611f5d7e59bbbd1d96c94d07567fc858',
  files:selected.map(file=>{const bytes=fs.readFileSync(file);return{path:relative(file),bytes:bytes.length,sha256:hash(bytes)};})};
if(process.argv.includes('--verify')) {
  const expected=JSON.parse(fs.readFileSync(manifestFile));
  if(JSON.stringify(expected)!==JSON.stringify(manifest)) throw Error('Package manifest differs');
} else fs.writeFileSync(manifestFile,JSON.stringify(manifest,null,2)+'\n');
const catalog=JSON.parse(fs.readFileSync(path.join(root,'contracts/catalog.json')));
const entry=catalog.tools[0];
for(const [key,file] of Object.entries({inputSha256:'input.schema.json',outputSha256:'output.schema.json',manifestSha256:'plugin.yaml',descriptionSha256:'description.txt'}))
  if(hash(fs.readFileSync(path.join(root,'contracts/session_event_read-0.1.0',file)))!==entry[key]) throw Error('Catalog pin mismatch');
for(const [file,digest] of Object.entries(catalog.schemas))
  if(hash(fs.readFileSync(path.join(root,'contracts',file)))!==digest) throw Error('Public schema pin mismatch');
const binding=JSON.parse(fs.readFileSync(path.join(root,'contracts/examples/binding.json')));
if(binding.catalogDigest!==hash(fs.readFileSync(path.join(root,'contracts/catalog.json')))) throw Error('Binding pin mismatch');
const event=JSON.parse(fs.readFileSync(path.join(root,'contracts/event-v1.schema.json')));
const output=JSON.parse(fs.readFileSync(path.join(root,'contracts/session_event_read-0.1.0/output.schema.json')));
delete event.$schema;delete event.$id;
if(JSON.stringify(event)!==JSON.stringify(output.properties.target)) throw Error('Event/output Schema diverged');
const wire=fs.readFileSync(path.join(root,'contracts/examples/first-read-output.wire.json'));
const receipt=JSON.parse(fs.readFileSync(path.join(root,'contracts/examples/read-receipt.json')));
if(receipt.data.output_sha256!==hash(wire)||receipt.data.output_bytes!==wire.length) throw Error('Receipt wire binding mismatch');
console.log(`Verified ${selected.length} package files, catalog/schema/binding pins, embedded event schema and exact receipt bytes.`);
