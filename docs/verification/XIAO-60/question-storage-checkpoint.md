本片仅迁 `JdbcLedger.questionStorage` 完整问题存储预算资格，parent `dee7a34ee326e8261f548e696eb764636999fab4`／tree `22796cf13f04406a052e32aa2e7fc4a57d6014c2`，沿原A分支和草稿PR22。准确源码SHA/tree、受测原JAR由manifest及原Git commit/tree/bundle绑定。本片是B2部分检查点，非B2完成／独审QA／总验收。

两轮实际受测均为第二轮原JAR `37eedf2f468b22793fc819cc0142bb820bd4c443c0f978e98bad757d6f073376`，44,586,186字节；第三轮打包候选另核生产entry，不替代实际受测原件。准确entry差异见analysis／manifest，七处接线与caller表达式冻结审计随包。

原JAR共1,617生产entry，相对旧9894c1af的1,611 entry，1,605原字节同／6新增（5class＋1XML），Ledger外五既有嵌套class独立解析仅LineNumberTable变化。第三轮候选517fbf32只ZIP元数据不同、全部生产entry同，未替代受测原品。

## 完整Service与原SQL

纯Java `QuestionStorageService`／impl和类型化惰性 `QuestionStorage` 端口承载全部contains短路→一次原汇总→可空Long在原long位置拆箱→`used + additional > 2097152`。没有todo的65536元数据预留、额外谓词、非负校验、Math.addExact、饱和、fallback、catch或新事务。混合／重复caps包含ask_user_question仍计量；原caps的String cast／split／NULL保持。非question只读caps，不读tenant/id、会话/query或SQL。端口构造只存ledger/task引用。

适配器在bytes()原位置按tenant String→task String求值，使用独立question域 `QuestionStorageMapper.bytes`／XML及原同DS/UoW/query。返回List<Long>再用Spring `DataAccessUtils.nullableSingleResult`，保持queryForObject的EmptyResultDataAccessException／IncorrectResultSizeDataAccessException及单行NULL；Service只在拆箱处NPE。不依赖todo私有port、不改已迁todoStorage。

```sql
SELECT COALESCE(OCTET_LENGTH(request_snapshot),0)+COALESCE(OCTET_LENGTH(goal),0)+COALESCE(OCTET_LENGTH(summary),0)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(response_snapshot),0)),0) FROM step WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(input_snapshot)+COALESCE(OCTET_LENGTH(output_snapshot),0)),0) FROM invocation WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(answer_snapshot),0)+COALESCE(OCTET_LENGTH(receipt_snapshot),0)),0) FROM user_question WHERE tenant_id=t.tenant_id AND task_id=t.id) FROM task t WHERE tenant_id=? AND id=?
```

实际SQL原串及两String绑定无变化；XML仅参数化替换。所有OCTET_LENGTH、COALESCE位置、SUM内部NULL、空子集合和三个相关子查询tenant/task键不改，无锁／JOIN／额外字段／查询／写入。标准MyBatis LongTypeHandler与原Spring SingleColumnRowMapper均getLong；非零时新侧少一次原wasNull，0/NULL仍wasNull，该getter差异由合同单测分列。MyBatis execute/getResultSet与原executeQuery差异不称全JDBC调用同。合法schema下本SQL非空task行用COALESCE得到非NULL值；SQL NULL和多行只合成，未制造违反CHECK/NOT NULL的行。

七处调用的原条件／位置／次数及caller-side additional表达式完全保留：answer的答案＋receipt＋16384原int表达式与AdmissionResult catch；openQuestion仅新执行固定16384；claim resume先snapshot再question131072→todo131072；prepared原request UTF8大小及重复计算；summary原<=8192后同一字节数；reserveModuleStorage question→todo；ModelDispatch原response UTF8长度*2L（无外加question guard，实参先求值）。源码审计确认其他Ledger方法词法不变，两个相邻调用者文件原字节同。

合同称responded.validateSummary的位置，在准确源码实际是 `JdbcExecutionRecords.Admission.validateSummary`，由 `ExecutionService.finish` 调用；本轮直接走公开 `ledger.finish` 原链，未将其写成modelResponded响应结算。ModelDispatch reserveQuestionStorage另保留原位置，本片不迁其业务。

保护608份其他生产Java／422既有资源及冻结文件，新增1XML；schema22/V1–V22、相邻Bean/bootstrap/POM、所有已迁Service、todoProjection21列／case／NULL不动。迁1旧JDBC点，SQL仍执行／消除0；库存静态163／动态29／bind549，不关闭整表。

## 覆盖、逐轮结果与封存

先核UserQuestionContractTest、UserQuestionIT／user_question_verify.py的历史问答／retention覆盖，原prepared／finish／model测试及已迁todoStorage Long合同；这些仅引用准确parent源码，不当当前head全矩阵验收。新增11 Service单测和6 port合同补contains／跳读、原caps cast/NULL/split、tenant→task先于session、单列Long转换及基数／NULL、原SQL／两参数映射、等于／超1／负数／溢出／原异常。

首轮62项封存：11Service＋6port＋1架构及追踪中1项过，另1追踪因捕获索引在格式化前生成、与格式化后源码hash不符而失败，verify1；未打包、未IT、未SpotBugs。封存实际source/class／编译回执／完整失败后，只刷新索引；生产／测试代码未因该失败修改。之后定向执行结果、准确制品和清理详见manifest／analysis.json；不将未重跑的17项单测称第二轮新执行。

第二轮2追踪＋1架构过／SpotBugs bugs/errors0，旧新各完成前8组direct结果；第9组question行显式INSERT因私有runtime角色权限不足而失败（SQLState42000/vendor1142），两侧driver1、IT／verify1。各9次外层恢复（包括失败夹具）、完整cleanup与原cause保留。封存后只为私有夹具补INSERT/UPDATE授权及助手有界phase／离线完成前缀核验，未改产品或driver。

第三轮仅剩余5对question计量／prepared成功拒绝／summary成功拒绝，复用准确第二轮原JAR，2追踪＋1架构＋1IT过、verify0／SpotBugs0；未重跑已过17单测或前8对。最终纯比较器明确接受第二轮原driver1和失败第9组，离线只重算其中8对完成前缀，另核第三轮5对；不伪造driver0或把失败行算成功。各轮助手／driver／编译件匹配自身执行原件，最终助手匹配第三轮原件。

首轮过时追踪gzip输入未单独封存；原失败日志中的expected／actual hash及首次capture回执仍保留，不能称已完整复现该旧索引输入。最终刷新索引已通过追踪检查，此原输入缺件继续OPEN。

每轮原件包括执行source/XML／driver／助手、编译maven-status/class、原日志、JUnit及制品绑定。主错cause/suppressed/next SQLException/SQLState/vendor、rollback／cleanup／落盘次错分存，不遮主错。没有机械注入SQL故障或等待自然故障。准备阶段错误路径／只读探测的工具回执保留披露，未独立封存的完整stderr仍属历史缺件。

## 有界真实链与证据范围

私有MySQL8、paid=false，新侧HTTP仅受理合法0.2.0 question任务，旧新试验前复制同一数据库。driver在私有外层事务内调用ledger.registerWorker／claim构造合法Lease并走prepare、finish；没有worker循环、provider请求、新工具执行／openQuestion／answer。未改数据库CURRENT_TIMESTAMP、socket/network／query或事务配置；仅夹具任务时间窗按原180秒更新并外层恢复，不改变查询绝对两秒或调用者deadline。

direct样本分列预算等于／超1／负数／signed overflow、混合重复caps、非question跳读、准确不存在task键EmptyResult、非ASCII摘要增量。direct additional是driver值，不称公开用户输入；不存在键不是实际DELETE。空step／invocation／user_question子集合来自受理后的QUEUED任务，数据库原汇总计量。

user_question计量样本先用合法prepare／fixture modelResponded得到关联step／attempt／invocation，再显式SQL插入符合FK/CHECK的OPEN行，证明NULL answer/receipt只计request；随后显式SQL改为ANSWERED、完整answer身份／delivery状态及原摘要，证明answer＋receipt非ASCII字节增量。原行／快照／摘要／长度分别保存；不是工具执行／真实问答事件生成证明。其modelResponded输入仅fixture，无provider。

该SQL行的mode为blocking、task的0.2.0 binding为timed，本样本仅证明数据库FK/CHECK合法行及原汇总计量，不主张经过QuestionContracts完整问答资格；未调用questionInput/openQuestion/answer验证该行。原mode及binding各存，不泛化为真实成功问答。

公开prepared请求及finish summary成功／必要预算拒绝分列，实际锁顺序、完整tenant／actor／installation绑定及原汇总两键／次数各存。setup内prepare／dispatch／fixture响应原查点不算被测调用次数，但执行来源完整保存；不将direct边界／显式SQL夹具当公开输入。拒绝后的原task／model_dispatch／reservation／event事实在内层处理后、外层rollback前另存，不能据此声称持久FAILED-read或独立后记。

这两种拒绝原内层回滚后task仍RUNNING、QUESTION_STORAGE/QUESTION_TASK/RUNNING仍RESERVED，QUEUE已RELEASED；prepared拒绝只有accepted/started，summary拒绝另有原model.prepared。没有新增model.rejected／FAILED／MODEL SETTLED结算，不把它写成todo响应拒绝的结算证明。

捕获恢复仅tenant-a二十一表（原二十表加user_question），不包括worker_process或整库。每次外层rollback后比本侧before/after；私有实例完整清理另证，不称持久提交／整库恢复。原串／SHA256／UTF8长度各存，生成ID／时间／Map.of仅比已证JSON字段；全SQL序列与storage／actor／installation完整bind分证，不泛化所有生成ID绑定或wire同。

两轮合计13对完成结果，28份原计量／Lease／成功及拒绝证明、28笔tenant-a二十一表捕获恢复（含第二轮两侧失败夹具各1笔），两份完整清理；失败夹具不计入成功结果对数。

## 剩余与边界

下一具体候选 `JdbcLedger.questionInput` 完整版本／binding／request摘要及历史pin codec资格，需核准确源码和已有覆盖后沿原A主线接续；本轮未混迁。本片不关闭B2／B0–B5，60保持in_progress／PR草稿，最终61/62 backlog。

35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径及全部历史失败缺件OPEN。63/64/66/70不重接、72固定旧head；不CI／合master／部署／真实Key计费／共享服务数据／daemon代理FRP重置，T48和新功能暂停。

仅回读本轮唯一规则 `01a174aa-3938-7a8f-958f-d8334923f83e`，绑定准确run `01a174a8-cbda-7b8e-9ee9-6b6435403835` 的completed/failed/cancelled、once及两小时；未重复创建／恢复。
