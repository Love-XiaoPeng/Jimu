本片仅迁JdbcLedger.questionNow，parent `c217746baf74a9b0cf2ac7ea7f80b0d3dfabceb4`／tree `6dc6f76f941697f775b060afcf8e37b00cac201b`。原A唯一集成写入，原分支agent/agent/7aeccb14d7a9／PR22草稿。准确新SHA/tree由manifest及Git原对象绑定。仅B2部分检查点，开发自测，非独审QA／总验收。

受测原JAR SHA256 `5490d94f373d47d4d5a4a61ba29403460bf20a3c9d9b7370245d6570391897f8`，44,630,608字节／1,661生产entry。准确旧29d39520与新原品全部1655／1661 entry直接读取：1647既有原字节同／6新增，七既有嵌套class独立解析仅LineNumberTable变化。646其他生产Java／428既有资源、全部已迁Service、相邻Bean/bootstrap/POM、冻结文件与schema22/V1–V22不变。

## 完整时钟Service与原读取语义

纯JavaQuestionClock接口、QuestionClockService接口与impl：每次只惰性调用一次LocalDateTime read，然后原.toInstant(ZoneOffset.UTC)返回；UTC转换属于Service，不留Mapper/adapter，不返回已经转换的Instant。构造只保存Ledger依赖引用，不取query/会话/时间或环境。没有task/tenant/id/身份/questionTask/flag/pin/模型/PURGED/状态guard、catch/fallback/retry/校验/事务；无跨调用缓存，不改Instant.now/Clock/systemDefault/毫秒精度。NULL读值在原转换位置NPE，原RuntimeException/DataAccessException/Error实例原样传播，无默认值或TaskFailure翻译。

先核Question域Mapper、SessionEventAppendMapper.clock、TodoRetentionMapper.now及LedgerMapperSessions配置，新增Question域专属Mapper/XML，不借跨聚合私有port。实际完整SQL仅：

```sql
SELECT CURRENT_TIMESTAMP(6)
```

单列零SQLbind，resultType=java.time.LocalDateTime，实际Mapper返回List<LocalDateTime>，adapter沿Spring DataAccessUtils.nullableSingleResult提取。空行EmptyResultDataAccessException（expected1/actual0）、多行IncorrectResultSizeDataAccessException（expected1/actual2）、一行NULL返回后Service NPE仅合成边界；原固定MySQL表达式真实只一行一列非NULL，不改SQL造这些行，不称真实空/多/NULL/非法类型已验。未改共用one/rows/JdbcTemplate、全局cacheEnabled=false/LocalCacheScope.STATEMENT或装配配置。SQL错误未机械注入；原DataAccessException实例从port到Service的传播仅合成证明，JdbcTemplate与MyBatis-Spring的自然驱动错误翻译/文案未新触发验证，不声称所有错误API/消息逐字相同。

## 两处原接线与计量边界

listQuestions与answer完整方法词法不变，其他原Ledger方法均不改。list仍tx/bucket→questionVisible完整identity/owner/locked/access→非question直接空items（无时钟）→question才一次questionNow→原q.*联表/ordinal→每行questionInput/固定字段/answerable/answers→原响应字节上限；同一本次now供循环，不每行读。answer仍tx/bucket/可见/q FOR UPDATE/previous幂等提前返回（无时钟），新分支才一次时钟，随后原isBefore等号边界/terminal/cancel/CLOSED/flag/due-OPEN续问/重读/input/canonical/storage/答案/receipt/事件，原catch和事务外outcome不动。非question/幂等无时钟由准确源码和历史问答覆盖说明，本轮不机械补全组合或真实等号等待。维护未迁/未新跑。

原普通JdbcTemplate无参queryForObject走Statement，新Mapper走PreparedStatement；原queryView的SessionJdbcTemplate与新Mapper显式queryContext均使用同DS/UoW，真实直读样本同外层事务/同连接核对。两次普通调用＋两次沿原queryView的调用分别各一SQL；不跨调用缓存，无新增第二SQL时间对证。queryView由原私有方法反射构造，prior reads/bytes/operations=7/100/13，原System.nanoTime()+2秒绝对截止不重启；两次后9/210/17，截止值前后相同，末尾remainingMillis通过。每结果1read/1row+1column、列标签CURRENT_TIMESTAMP(6)、LocalDateTime计量字节为8+20+27=55，原ResultSet实际列值与返回值分证。此为有界私有queryView实测，不称scope成功工具read或新增beforeCommit验收。

QueryStatements原setNetworkTimeout、SET SESSION max_execution_time、setQueryTimeout仍由既有SessionJdbcTemplate/Mapper拦截器执行；设置与剩余时间实测分别存SETTINGS/SQL数组，只在各自有效正剩余范围内比较，不要求旧新耗时数字相等。没有更改queryView起点、累计meter、deadline、socket/network/事务/时间配置。普通路径两侧实际均setQueryTimeout(5)，旧Ledger原db.setQueryTimeout(5)与既有Mapper默认5秒均未改。queryView两侧每次先5秒再按实际剩余设置2秒，各两次network/SET SESSION设置；旧剩余1468/1437ms、新1558/1520ms只作本侧事实，不等值比较。采集器仅新增观测，Statement.getConnection包装仍委托同一底层连接，没有新产品SQL。

实际旧Statement.executeQuery→新PreparedStatement.execute/getResultSet；两侧typed getObject均请求java.time.LocalDateTime，旧索引1→新列标签CURRENT_TIMESTAMP(6)，真实返回LocalDateTime。普通每次一个typed getter；queryView每次另有一个原计量getObject(1)，本样本也返回LocalDateTime，额外getter只用于计量、不产生第二时钟SQL。调用次数详见sql-comparison和完整原日志；两种API差异分列，不称全部JDBC调用相同。普通Statement现已采集，本片不沿用上片只prepared的限制；采集不是声称捕获全部JDBC API。

迁1旧JDBC点，SQL仍一条/消除0，库存153静态/29动态/520bind，不预报收益或关闭表。

## 验证与实际样本

准确parent下UserQuestionContractTest/UserQuestionIT/user_question_verify.py、QuestionContinuationScenario、既有时钟Mapper及QueryAdapterScenario/SessionQueryAdaptersIT计量覆盖已核，只引用旧head，不当本head新验收。新增4Service测试覆盖构造/调用惰性、单次一次/跨次重读、epoch/nano（零与非零微秒、跨日/负epoch）精确UTC、NULL与原异常实例；3port测试覆盖nullableSingleResult、加载真实SQL/LocalDateTime/零bind和既有缓存配置。未改系统默认时区/数据库时间，不sleep等时间变化。

run1封存26项；run2封存95项。run1 Checkstyle因新增单测六处星号导入失败/verify1，未到compiler/单测/package/IT/SpotBugs；完整原日志/规则错误保留。该轮封存target class是前轮baseline编译件，compile-state明确prior-build only，离线按旧原JAR核对，不冒称本源码编译成功。封存后仅改测试显式导入，产品不改。run2完整定向verify0/BUILD SUCCESS：4Service＋3port＋2追踪＋1架构＋1真实IT通过，无skip，SpotBugs bugs/errors0，两driver0。实际source/class/编译回执/完整日志逐轮封存，下一执行前保留原失败；测试导入失败不是产品或夹具执行失败。封存工具误指前片continuation助手，原source/tools额外保留了旧助手；正确实际执行clock助手已在运行前actual/query/executed中封存并随原95项索引保存。首版离线分析器误读缺失source/tools路径而FileNotFoundError，原脚本/stderr独立保留；仅修读取路径，源码提交匹配和核验器均以该正确原执行快照为准，原封存/索引未改，不重跑Java或数据库。

三个有界旧新私有paid=false样本：

- direct-clock：同外层事务普通两次、原queryView两次。逐次以本次ResultSet typed getObject实际读出的LocalDateTime，对应本次返回Instant的epoch秒/nano，nano为1000整倍数；无需两次时间不同，不冻结/重放时钟，不用额外SELECT冒充。queryView的两次计量与原设置/绝对截止分别核对，生成纳秒截止不跨旧新等值。
- owner-list：显式合法非空OPEN问题，原tenant/owner/actor/installation完整锁链在时钟之前，时钟一次在q.*联表之前；本侧字段/revision/phase/questions/answerable和有效时间窗核对，原问题/事件不改。
- new-answer：公开owner新foreground答案，原q锁/previous查点后时钟一次，在ANSWERED UPDATE之前；本侧合法未来due/expiry，原ToolResult→tool.completed→Resume/task.resumable→question.answered及TOOL_RESULT receipt。未到期，非续问样本；非公开用户可传任意output或数据库时刻。

每次实际ResultSet LocalDateTime原串、direct返回Instant原串及各自摘要/长度、完整driver返回原串分存，旧新独立当前时刻不判相等；public内部Instant未作为API字段暴露，按原转换源码/本次原LocalDateTime与资格窗口核证，不冒充捕获了public内部局部Instant实例。生成ID/时间/Map.of只比已证字段与本侧关系。12份原夹具/成功证明、6份setup Lease、6笔tenant-a21表外层捕获事实恢复及1清理；worker_process未纳入，不称整库或持久提交。

HTTP仅新侧setup；registerWorker/claim与原prepare/dispatch/modelResponded写已存响应/intent，无worker循环/新工具/provider。显式FK/CHECK合法timed0.2.0 OPEN问题/WAITING_USER invocation/TOOL RESERVED/step，与pin/binding/request/时间窗相容，非openOrResult/工具/provider/真实问答事件生成。setup task WAITING_USER、attempt PAUSED且fence+1并清活动字段，Lease指向PAUSED attempt，非有效执行租约。私有合法task/question窗口设置单列，不更改产品时钟/连接/事务配置。主错cause/suppressed/nextSQLException/SQLState/vendor及rollback/cleanup/落盘次错分存机制保留，UNKNOWN不恢复重放。无DELETE/非法NULL/schema行/SQL故障/自然08S01/UNKNOWN恢复；准备只读探测stderr缺件继续OPEN。

## 剩余与门禁

run `01a1758e-abae-74e6-abc1-c003f1b655a1`，原唯一PM规则 `01a1758f-3750-7a95-a9dc-e5f60ea91df4` 绑定本run completed/failed/cancelled＋7200秒，不恢复旧规则。准确SHA/tree/受测原JAR分片、源码/class/编译/日志/原失败/清理及离线核验器随包。

下一具体候选为仍未迁的listQuestions完整问题列表展示资格/原联表/顺序字段构造/answerable/响应字节限额，复用既有Visible/Input/Clock；待PM按准确源码核定，本片不混迁。answer/openOrResult/maintainQuestions等编排仍待后续切片。B2/B0–B5未完，60保持in_progress/PR22草稿，B2完整才核B3，61/62 backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read/独立后记、其他真实缺行、自然08S01/709ms/冷路径及历史失败缺件OPEN。

tenant/whole-scope/fence/权限/原锁序/同DS/UoW、baseAccess四独立资格/question专属else/MCP、access原catch/403 afterCompletion去重/REQUIRES_NEW后记、特殊拒绝结算/事务外outcome、commitFence13处/session边界、todoProjection完整21列/case/NULL、冻结值类型/字段序/字节摘要/数据库时间及schema22保持。63/64/66/70不重接，72固定旧head；无CI/合master/部署/真实Key付费/共享服务维护/daemon代理FRP重置，T48及新功能暂停。仅开发自测，不称独审QA/总验收/成功工具read/FAILED-read/独立后记。
