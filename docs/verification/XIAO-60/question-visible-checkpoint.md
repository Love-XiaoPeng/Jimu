本片仅迁 `JdbcLedger.questionVisible` 完整 identity／owner 三键资格，parent `35d7a80aece40612124f9cbbb301d6ee18a8b2a5`／tree `ad4a03e015e21889cc746dd55a6531b908eb6529`，沿原 A 分支和草稿 PR22。准确提交 SHA/tree、受测原 JAR、执行源及编译件由交付 manifest、Git 原对象／bundle 绑定。本片仅为 B2 部分检查点，非 B2 完成、独审 QA 或总验收。

受测原 JAR `124eee36fbdcf7a8228dc315ffb13d8d729ae7c28eb6789a625953d093d0c43e`，44,595,212 字节／1,625 生产 entry。直接读旧 a53263c6／新原 JAR 全部 1,621／1,625 entry：1,613 原字节同／4 新增 class；七个既有 Ledger 嵌套 class 独立解析，仅 LineNumberTable 变化。无新增资源／Mapper 文件。

## 完整 Service 与真实单列读取

纯 Java `QuestionVisibilityService`／impl 和类型化惰性 `QuestionVisibility<T>` 端口完整承载原 identity → owner 查询 → isEmpty 拒绝 → 第二 tenant／taskId → locked → access → 返回编排。构造只存 ledger／identity／taskId 引用，不解引用成员、取会话或 query。没有 questionTask／flag／pin／PURGED／UUID／非空等新 guard，没有新 catch、fallback、事务或错误统一翻译。

Service 首先且仅一次复用原 `ledger.identity(identity)`，仍由已迁 TaskIdentityService 核 actor 锁／enabled。成功后才依次 tenant → taskId → actor 到达 owner 查询；单列结果仅判断 isEmpty，空行仍 404/TASK_NOT_FOUND，非空不读 id、不 cast、不要求 size1。多行只合成，真实复合 PK 无法表达同三键多行。

先核 TaskVisibilityMapper／TaskLookupMapper，复用前者真实 MP selectMaps；独立 wrapper 显式 `select("id")`，不改变共享 Mapper／Entity 的默认完整行或 hint 投影。原实际 SQL：

```sql
SELECT id FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=?
```

本片实际 MP SQL：

```sql
SELECT id FROM task WHERE (tenant_id = ? AND id = ? AND owner_actor_id = ?)
```

只有 WHERE 括号／空格差异，三键仍完整 tenant／task／owner、原顺序和 String 值。参数全部求值后才进入端口取原 nullable query／同 UoW 会话。无锁、额外谓词、SELECT*、COUNT／EXISTS、排序／LIMIT、别名、JOIN、预读、第二查询或实体全行读取。

真实 owner 读取的三个 setter 两侧均 setString；原 JdbcTemplate 映射 `getObject(1)`，新 MP 映射 `getString("id")`，实际值两侧均 java.lang.String，原 getter 原件各存。Service 本来不读返回 id。执行由原 executeQuery 转为 MP execute／getResultSet 路径，不声称全部 JDBC 调用相同；未新增手工异常翻译。NULL 三键仅真实加载 BoundSql／参数映射单测，未称真实 NULL bind／SQL 行验证，也未泛化 JDBC setNull 类型一致。

非空后 Service 再次调用 tenant()，不缓存前值；随后原 `ledger.locked(tenant,taskId)` 保持 sessionScopes.lock（包括非 session 的原 callerCapabilities 查点及必要目标全序锁）→同 query 的 selectTaskForUpdate→first。未把 owner 存在行当 task 返回。完整锁行的列／case、live／within_deadline、原 NULL／值类型由未改 Mapper 和本轮 direct 行证明分证。锁行缺失的原 first 首错只单测传播／源码引用，未造 owner 与锁行间真实消失。

锁行成功后只一次原 access(task)，返回同一个 Map 实例；实例身份由 Service 单测断言，真实 direct 另核完整值／键序／类型，不能跨 JVM 把相等行称同一 Java 实例。通用 visible 的提前 scope 锁及其不同 catch 未套入；identity／locked／access 内部、baseAccess 四独立资格、question 专属 else／MCP、scope403 afterCompletion／REQUIRES_NEW 后记等原样保护。

三处直接 caller 词法不变：listQuestions 的 tx→bucket→本片，随后非 question／时钟／联表／view／Input；authorizeAnswer 即使弃返回也完整 access，随后原问题三键；answer 的本片仍在 q FOR UPDATE、previous 查询及新旧分支前。外层 bucket／tx、原 answer catch／AdmissionResult／事件／receipt、共享 fence13处和 session 完成边界均未移动。

617 份其他生产 Java／423 份既有资源及冻结文件、schema22/V1–V22、Bean/bootstrap/POM、已迁全部 Service、todoProjection 完整21列／case／NULL 不变。迁本片1个旧 JDBC 点，SQL仍执行／消除0；库存静态162／动态29／bind546，task 表仍 OPEN，不关整表。

## 覆盖、原件与真实范围

先核准确 parent 的 UserQuestionIT／user_question_verify.py 跨 owner／tenant、身份／安装禁用与缺行覆盖，以及 TaskIdentityServiceTest 和已迁完整锁行／scope 覆盖，仅作历史来源，不当当前新 head 验收。未重跑完整问答／retention／response／模型／session 矩阵或 CI。

本轮一次完整定向 verify0／BUILD SUCCESS：4 Service＋3 port＋2 追踪＋1 架构＋1 真实 IT 全过，无 skip；SpotBugs bugs/errors 均0。新增测试覆盖构造惰性、identity 首错、三实参到查询的顺序、空／非空／合成多行、不读 id、第二 tenant 可变化、locked→access、原完整行实例，以及每个到达点 RuntimeException／Error／TaskFailure 原传播。真实加载 MP 的单列／三键与 NULL 参数映射另证；不可表达的 NULL／多行／变化回调只单测。

实际 Windows Maven Wrapper 命令：`verify -Dtest=QuestionVisibilityServiceTest,QuestionVisibilityPortTest,PersistencePathTrackingTest,PackageBoundariesTest -Dit.test=B2QuestionVisibilityIT -Djimu.question.visible.evidence=b2-question-visible-run1`。先 spotless:apply，再 capture/review 索引，之后执行；96项本轮源码／class／编译回执／Maven stdout、stderr和exit／JUnit／SpotBugs／实际driver与助手／制品关联／清理已封存。无未预期测试、产品或夹具执行失败；预期403／404保留完整原异常链。

私有 MySQL8、paid=false，仅新侧 HTTP 受理一个合法 queued question 任务；旧新试验前复制同一库。没有真实问题行、显式问题夹具、registerWorker／claim、模型响应、worker循环、provider、新工具或付费。额外 enabled mallory actor 是私有身份夹具；身份禁用 UPDATE 仅在试验外层事务内并恢复。任务时间窗、数据库时钟、socket/network、query／事务设置均未调整。

五对实际样本：direct questionVisible 返回完整锁行；owner 公开 listQuestions 返回空 items（本片已到达完整 access，未声称 questionInput 非空链新跑）；authorizeAnswer 完整资格后由原问题三键报 QUESTION_NOT_FOUND；非 owner 的有效 mallory 身份先通过，再在 owner 空行报 TASK_NOT_FOUND，尚未 scope／task锁／access；disabled alice 在原 identity 报 ACCESS_REVOKED，尚未 owner 查询。问题缺行是从未插入的准确 key，不是实际 DELETE；未造不存在 task／非法 FK／CHECK 行。

全 SQL 顺序只规范本片 MP WHERE 格式；三 owner bind、actor／installation 锁绑定、callerCapabilities、完整 task 锁与后续问题三键逐项核验。本轮这些有界只读调用的每一 SQL bind 序列实际均同，另存 all-bind-proof；不泛化其他路径或生成 ID。三条正向到达链均为 identity actor → owner → callerCapabilities → 完整 task FOR UPDATE → access actor／installation；authorize 再问题三键。session task 的目标全序锁仅引用未改实现／既有证据，本轮没有伪造 session 运行样本。

14份原夹具／成功行证明分别存原串、SHA256和UTF8长度；其中10份试验前 task 证明、4份 direct／owner-list 成功证明。direct 完整锁行含 live=NULL／lease_until=NULL、within_deadline 实值，所有键序／类型／NULL与本侧已存 task 关系及旧新结果另核。原字段顺序、Map.of 序列化原串各存，JSON比较范围明确，不称全部 wire 字节同。

10次恢复仅核 tenant-a 二十一表捕获事实的本侧外层 before/after；worker_process 未纳入，不称整库恢复或持久提交。所有子进程及私有容器／目录清理另有1份完整回执。本片没有业务 SQL 故障注入、自然08S01、UNKNOWN恢复、成功工具read、持久FAILED-read或独立后记。

准备只读探测的错误文件路径回执保留，未独立完整封存的准备stderr及全部历史失败缺件继续 OPEN；没有把它们当产品通过证据。主错cause/suppressed/next SQLException/SQLState/vendor、rollback／cleanup／落盘次错按角色原样保存，无次错遮主错。

## 剩余与下一具体片

下一具体候选 `JdbcLedger.questionMessages` 完整待答消息／answer摘要资格、原PENDING联表及late reply构造，需PM核准确源码和空闲后实际续派；本轮未混迁。B2／B0–B5未完，60保持in_progress／PR22草稿，61/62 backlog。

35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径与全部历史缺口OPEN。63/64/66/70不重接，72固定旧head；不CI／合master／部署／真实Key计费／共享服务数据／daemon代理FRP重置，T48与新功能暂停。

仅回读本轮唯一规则 `01a174e4-39d3-7e47-9347-7eb1443a9e72`，严格绑定准确run `01a174e3-9bdd-7abf-86a1-bf2acd86a5a5` 的completed/failed/cancelled、once及两小时，未新建或恢复旧规则。
