本片仅迁 `JdbcLedger.questionMessages`，parent `9666d2617694899b982db926a332230cec39caa3`／tree `54afba5f5608e4f8bd1359a1a674c6e128168e47`，沿原A分支／PR22草稿。准确新SHA/tree由manifest和Git原对象绑定。仅B2部分检查点，不是独审QA／总验收。

受测原JAR SHA256 `5663d8f3fb5b58d318c9ef7e6817bce01581fbc99c8a9c9f572361b9119b166e`，44,602,061字节／1,631生产entry。直接读旧124eee36／新原JAR全部1,625／1,631生产entry：1,619既有entry字节同／6新增（5class、1XML）；5个既有嵌套class独立解析仅LineNumberTable变化，原LateQuestionReply class字节同。其余621生产Java／423既有资源、Bean/bootstrap/POM及schema22/V1–V22冻结。

## Service、惰性及接线

纯Java接口／Service／impl与泛型行／questions／answers端口完整承载新建可变LinkedHashMap→原questionTask→tenant→id→单次查询→逐行hash/拒绝→put→返回。非question跳过字段／query／会话，原contains、caps的cast/NULL/split复用；不加flag、pin、PURGED、身份或其他guard。端口构造只存引用与单项input callback，不读字段。Service不依赖adapters/MyBatis/Jackson/plugins，不增catch、事务或异常翻译。

每行保留answer第一次读取→原Payloads.hash→digest→hash结果.equals。通过后put的key先独立id，再encode参数常量1/kind/tool→第二id→provider→原questionInput(...).questions()→第二answer→原JsonNode read→原私有LateQuestionReply构造/json→put。没有缓存id/answer或提前input；原questionInput四OR、两次request及历史pin/codec仍原样。新增Ledger.encodeQuestionReply仅为原私有record/json单项桥接，record不移出、不改字段或Payloads配置。重复/NULL key仍原LinkedHashMap覆盖与初次插入次序，多行／变化回调／非法NULL类型仅单测。

三处caller和其他Ledger方法词法不变：公开pending仍leaseTx→fenced(true)→本片→values.stream.toList；prepareProjections仍原checkpoint解析后entrySet、user角色和content原串命中才原条件UPDATE/count拒绝、最后clearQuestionContinuation；pendingQuestions仍额外question谓词&&本片非空，在原ExecutionService.finish位置。未迁这些写入／事件／pause／结算，未运行新的finish样本。DurableAgent位置、完整权限/锁/DS/UoW/queryView/两秒绝对deadline、共享fence13处及session边界不改。

## 原SQL与行合同

先核现有question Mapper；新增精确QuestionMessagesMapper.pending XML，不采用实体CRUD或拆读。保留原实串，仅?替换为参数绑定：

```sql
SELECT q.*,i.provider_id FROM user_question q JOIN invocation i ON i.tenant_id=q.tenant_id AND i.id=q.invocation_id WHERE q.tenant_id=? AND q.task_id=? AND q.delivery_state='PENDING' ORDER BY q.accepted_seq
```

参数原位置tenant String→task String，全部求值后才进入同query/会话。保留全部tenant/JOIN键、q.*、provider_id、PENDING及accepted_seq，无新增锁、谓词、排序键、LIMIT或查询。星号门禁只放行该精确Mapper-ID＋完整原SQL；其他共享查询门禁不变。

XML结果LinkedHashMap，沿用现有callSettersOnNulls和原驱动类型；实际每侧34列的键序/case、类型、NULL分布逐项旧新比较，且全部值核本侧已存问题／invocation。包括LocalDateTime，未改数据库时间。原JdbcTemplate与MP执行/映射API不同；本轮未采集逐getter/setter方法名，不声称全部JDBC调用相同或wire字节通用相同。两键值、完整actor/installation锁及每侧原fence绑定分证。采集器只拦prepareStatement，报告的SQL全序限定所记录prepared SQL，不包含普通Statement。

迁本片1旧JDBC点，SQL仍执行／消除0；库存实计161静态／29动态／544bind；task/user_question等表仍OPEN。

## 验证、执行原件及边界

先核UserQuestionContractTest/UserQuestionIT、user_question_verify.py及准确35d7a80a的direct late证据，仅引用历史来源，未当当前head验收。一次完整定向verify0/BUILD SUCCESS：6Service＋3port＋2追踪＋1架构＋1真实IT全过、无skip；SpotBugs bugs/errors0。实际命令为 `verify -Dtest=QuestionMessagesServiceTest,QuestionMessagesPortTest,PersistencePathTrackingTest,PackageBoundariesTest -Dit.test=B2QuestionMessagesIT -Djimu.question.messages.evidence=b2-question-messages-run1`。

测试覆盖构造跳读、四段实参到查询顺序、每行摘要首错、第二id/answer可变化、key/provider/input/read/encode次序、可变Map/NULL与重复key、所有到达点RuntimeException/Error/TaskFailure原实例传播及真实加载XML。不可表达类型/NULL/重复行仅合成；真实每次一行，未称真实多行排序或PK重复。

私有paid=false MySQL8，HTTP仅新侧受理任务后复制旧新库。registerWorker/claim仅构造Lease，无worker循环/provider/新工具/付费。setup使用原prepared/dispatch/modelResponded形成模型响应与invocation，再显式SQL置合法已存timed CONTINUED问题和settled TOOL，pin/binding/mode/request均0.2.0一致；答案经原answer setup形成PENDING及其事件。这些是有界夹具和setup调用，非openQuestion/真实工具/provider生成问题，不冒称完整真实问答执行链；没有重开response六组。任务窗口在私有外层事务合法续期，未改query/socket/事务配置。

四对完成样本：非空公开pendingQuestionMessages实际fenced链；坏answer摘要409；正确answer摘要、正确questionInput下坏answer JSON原IllegalArgumentException；公开prepare生成新step，原prepareProjections实际命中content并将问题CHECKPOINTED，delivery_step_id等于本侧返回step。负样本都先通过fenced/access，JSON样本保留原栈的read位置；未把request codec拒绝称answer解析错误。公开消息原串旧新相同，单独保存摘要/UTF8长度；prepare的step/attempt/时间/Map.of只比已证字段与本侧关系。

12份原夹具/成功证明、8份Lease、8份完整联表行证明分别封存原串/摘要/长度；每侧Lease五键核本侧task，原commitLiveFence完整五bind另核（其第三键为revision，非worker）。8次tenant-a21表捕获事实外层恢复，worker_process未纳入；1份完整清理，非整库/持久提交/成功工具read/FAILED-read/独立后记。无DELETE、SQL故障注入、自然08S01或UNKNOWN恢复。

98项run1源码/class/编译回执/完整日志与受测原JAR关联封存；原封存源助手路径误指上片，正确当前助手已在actual/executed按实际执行独立保存，另附源码补件与字节匹配回执，不改原98项/索引。编译前私有questionInput访问错误由惰性单项callback修正；独立审计脚本括号/替换SHA与补件目录误枚举错误均保留原脚本/stderr，只修核验器。无本片产品/单测/IT/夹具执行失败。准备只读路径/CLI误用的未完整独立stderr与历史缺件OPEN，不用准备错误代替测试结果。

## 剩余

下一具体候选为JdbcLedger.questionResume完整唤醒状态迁移资格、原单条CAS及event单项端口；需PM核准确源码/空闲后续派，本轮未迁。B2/B0–B5未完；60保持in_progress/PR草稿，61/62 backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read/独立后记、其他真实缺行、自然08S01/709ms/冷路径和全部历史失败缺件OPEN。

保护已迁全部Service、todoProjection完整21列/case/NULL、冻结字段序/字节摘要/数据库时间及schema22。63/64/66/70不重接，72固定旧head；不CI/合master/部署/真实Key付费/共享数据服务/daemon代理FRP重置，T48与新功能暂停。当前run `01a174ff-def6-703f-9f68-8ac703e0af23` 唯一规则 `01a17500-bc7f-7a91-8103-d588dcb1191b` 已回读准确三终态/once/两小时，未恢复旧规则。
