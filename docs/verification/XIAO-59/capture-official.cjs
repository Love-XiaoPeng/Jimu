// Capture hashes and bounded quotations from primary documentation and published POMs.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const out=path.join(__dirname,'evidence');fs.mkdirSync(out,{recursive:true});
const sources=[
 ['mp-install','https://baomidou.com/en/getting-started/install/',['Spring Boot4','3.5.13','mybatis-plus-spring-boot4-starter','mybatis-spring-boot-starter']],
 ['mp-wrapper','https://baomidou.com/en/guides/wrapper/',['LambdaQueryWrapper','allEq','last','SQL']],
 ['mp-annotation','https://baomidou.com/en/reference/annotation/',['TableId','IdType.INPUT','updateStrategy','EnumValue']],
 ['mp-transaction','https://baomidou.com/en/guides/batch-operation/',['Transaction','flushStatements','MybatisBatch']],
 ['mybatis-spring-tx','https://mybatis.org/spring/transactions.html',['must be the same','DataSourceTransactionManager','transaction','commit']],
 ['spring-tx-proxy','https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html',['self-invocation','proxy','rollback','public']],
 ['spring-sse','https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html',['SseEmitter','onTimeout','onCompletion','disconnect']],
 ['spring-ai-tools','https://docs.spring.io/spring-ai/reference/api/tools.html',['ToolCallingAdvisor','ToolCallingManager','ToolCallback']],
 ['boot-requirements','https://docs.spring.io/spring-boot/system-requirements.html',['Java','Spring Framework','Maven']],
 ['mp-metadata','https://repo.maven.apache.org/maven2/com/baomidou/mybatis-plus-spring-boot4-starter/maven-metadata.xml',['3.5.17','lastUpdated']],
 ['mp-starter-pom','https://repo.maven.apache.org/maven2/com/baomidou/mybatis-plus-spring-boot4-starter/3.5.17/mybatis-plus-spring-boot4-starter-3.5.17.pom',['4.0.1','mybatis-spring','4.0.0','spring-boot-starter-jdbc']],
 ['mp-core-pom','https://repo.maven.apache.org/maven2/com/baomidou/mybatis-plus-core/3.5.17/mybatis-plus-core-3.5.17.pom',['mybatis','version']],
 ['boot-pom','https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom',['spring-framework.version','jackson-bom.version','mysql.version','hikaricp.version','flyway.version']],
 ['ai-pom','https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom',['2.0.1','spring-ai-client-chat','spring-ai-model']],
];
const proxy=process.env.JIMU_AUDIT_PROXY;
function run(item){return new Promise(resolve=>{
 const [id,url,needles]=item,args=[...(proxy?['--proxy',proxy]:[]),'--location','--fail','--silent','--show-error','--max-time','40',url];
 const p=cp.spawn('curl.exe',args,{windowsHide:true});const chunks=[],errors=[];p.stdout.on('data',b=>chunks.push(b));p.stderr.on('data',b=>errors.push(b));p.on('error',e=>resolve({id,url,error:String(e)}));
 p.on('close',code=>{if(code)return resolve({id,url,exitCode:code,error:Buffer.concat(errors).toString()});const b=Buffer.concat(chunks);const raw=b.toString('utf8');const text=(/\.(xml|pom)$/.test(url)?raw:raw.replace(/<script\b[\s\S]*?<\/script>/gi,'').replace(/<style\b[\s\S]*?<\/style>/gi,'').replace(/<[^>]*>/g,' ')).replace(/\s+/g,' ').replace(/&gt;/g,'>').replace(/&lt;/g,'<').replace(/&quot;/g,'"').replace(/&amp;/g,'&');
 resolve({id,url,retrievedAt:new Date().toISOString(),bytes:b.length,sha256:crypto.createHash('sha256').update(b).digest('hex'),quotations:needles.map(needle=>{const at=text.toLowerCase().indexOf(needle.toLowerCase());return {needle,found:at>=0,excerpt:at<0?null:text.slice(Math.max(0,at-140),at+needle.length+420)};})});});
});}
(async()=>{const rows=[];for(let n=0;n<sources.length;n+=5)rows.push(...await Promise.all(sources.slice(n,n+5).map(run)));fs.writeFileSync(path.join(out,'official-sources.json'),JSON.stringify({note:'Live documentation is mutable; hashes refer to fetched bytes, bounded excerpts below. Published version-specific POMs and local resolution govern the candidate.',sources:rows},null,2)+'\n');console.log(rows.map(r=>({id:r.id,ok:!r.error,missing:r.quotations?.filter(q=>!q.found).map(q=>q.needle),error:r.error})));if(rows.some(r=>r.error))process.exitCode=1;})();
