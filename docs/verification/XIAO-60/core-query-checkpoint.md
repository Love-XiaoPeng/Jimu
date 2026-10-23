# B0/B1 核心业务及显式查询检查点

本批接续 `9dabdbeab9a8503f638809ae48c45d8a448bf8be`，继续唯一集成分支 `agent/agent/7aeccb14d7a9` 和草稿 [PR #22](https://github.com/Love-XiaoPeng/Jimu/pull/22)，base master。最终源码 SHA、tree、远端回读、JAR SHA256/大小及原始日志以本批独立附件 manifest 为准。不是整体重构验收，不启用初级、独审、QA、CI 或新功能。

## 本批已实现的边界

| 范围 | 实际业务实现 | 过渡存储与未迁范围 |
| --- | --- | --- |
| WorkerServiceImpl | claim 的完整分支：重验候选/run、旧 writer/UNKNOWN 防重放、排队/恢复额度、拒绝顺序、取消/到期、attempt/fence 与领取事实；quiescent 和 processStopped 的逐 task 事务及最后 worker 收据 | JdbcCoreRecords/claimCandidate/lockClaim 是具名过渡端口；renew/register/processes 和模块准入细节仍在 JdbcLedger，B2 迁真实 Mapper |
| ExecutionServiceImpl | stop 的 material-run STOPPED、未决效果/坏快照进入 NEEDS_RECONCILIATION、取消与失败终态决策 | fenced/terminal 保持原事务内存储；checkpoint、prepare、finish 尚在 JdbcLedger，未声称 Execution 全部拆完 |
| ModelDispatchServiceImpl | failed 的已知 usage/付费缺 usage 成本处理、session token 上界结算、未知保留、session 计账 | settleCost/settlementModel 仍为具名存储操作；dispatch/responded/成功结算和准备阶段尚未整体迁出 |
| InvocationServiceImpl | failed 的 RUNNING CAS、一次 unit 结算与失败事实、特殊 session 拒绝；uncertain 的 UNKNOWN/保留及 reconciliation 决策 | 准入/成功/原槽证明及插件特定提交尚在 JdbcLedger；失败接口的拒绝顺序保持原样 |
| SessionQueryContext / query adapter | 每次同步查询显式传 absolute deadline/累计资源；异步前授权资源传给后半段；同 DS/manager 的 REQUIRED 和 REQUIRES_NEW；JDBC/MyBatis 结果提供处逐行逐列实计，空结果一次 read，mapper 忽略的列和二进制仍计入，UTC DATETIME(6) 固定6位 | 当前 session 产品调用仍通过 scoped SessionJdbcTemplate；MyBatis 真实连接/结果适配已验证，不等于 session 业务 Mapper 已切换 |

核心 Service 不依赖 Spring/SQL/Map/MyBatis；事务经现有 LedgerUnitOfWork，原三类持久拒绝仍通过值结果提交后抛错，普通错误回滚。Service 不是空转发接口，但上述边界也不代表四模块所有用例迁完。JdbcCoreRecords 只在 B1 过渡期间保留；没有双写或第二套业务存储。

query view 是每次同步调用私有的 ledger/records 对象，复用唯一 DataSource、事务管理器和 UoW，并复制已验证的启动配置；不将权限/预算放入业务 ThreadLocal。SearchBudget 为该私有对象的普通字段；旧 v2/v3/proof 附加预算亦在 ResultSet 提供处拒绝，再收集后续字段。原模型匹配比较预算保留为独立计数，累计 proof/query 操作通过显式 LongConsumer 传递。仅驱动 SessionQueryDeadline 保留 ThreadLocal 窄例外，嵌套 scope 退出恢复外层，最终退出移除。

Statement 每次执行前缩短 network/server/query 时限；事务开始包括连接获取，提交前重验剩余时间；回滚后的 scope invalidation 用原 deadline 开 REQUIRES_NEW，不能重给两秒。删除 SELECT-prefix 动态长度改写，七处 step/invocation 查询直接带参数化正文长度谓词。缓存继续 STATEMENT/cacheEnabled=false。默认 durable=false 不创建 DS/SqlSession/interceptor；真实 durable=true 装配仍只有一个 DS/manager。

## B0 路径、全键和锁序

`capture-paths.cjs` 使用 JDK21 javac 对 master 与当前源码做 attributed symbol 追踪，处理重载、声明 override、lambda 工厂绑定、每方法有序调用、分支/循环/回调条件。生成：

- `evidence/entry-to-service.csv`：153 个基线入口声明到目标 Service、SQL/锁/事务节点索引；包含 HTTP/runtime/public persistence 及嵌套值声明，**不是153个业务端点**。
- `evidence/path-tracking.json.gz`：完整符号字典、图、参数/条件/行号、sourceHashes、基线153与当前222入口声明、623/620个字面量SQL调用点、动态/转发SQL点及验证组映射。解压后 symbols 解析 `mN`；每个 entry 的 reachable/sql/transactionNodes/locks 可回到有序 graph.calls，保留分支，不能把 alternatives 扁平化为一个实际锁序。
- 每个 SQL 记录涉及的表、接受方案的完整主键、tenant 提及与参数、锁种类及目标 Mapper。词法“出现列名”**不是 WHERE 全键证明**；`planned_*` statement 均标 PLANNED_NOT_LOADED，不是已加载业务 Mapper。
- 35张Flyway表与外部receipt的 product migration 全部 OPEN，actualBusinessMapper=null。真实 BaseMapper/XML 探针只证明装配与事务，不能关闭业务表。全键仍以 `docs/refactoring/XIAO-58/persistence.md` 为准：复合表不能 by-id，集合/聚合/CAS 使用原具名谓词；worker_process 是跨tenant设施记录，receipt 只读且不造主键。

| 本批实际路径 | 锁/事务与权限边界 | 本批差异证据 |
| --- | --- | --- |
| WorkerLoop → claim → WorkerService → candidate/lockClaim | tenant 候选在事务外；UoW 内 run → tenant budget → scope/source 相关锁 → 精确 task SKIP LOCKED，随后重验 run/fence/权限；旧 writer 不明且效果未知不领取 | 两 worker 正常任务；accepted/current 同一个 claim-recovery 驱动，过期旧 writer + UNKNOWN 不重放 |
| quiescent / processStopped → WorkerService → worker records | native writer 活跃先拒绝；逐 task 的 budget → task → attempt/结算，worker stopped 收据最后写，重复收据不重复释放 | 核心快照中 terminal 前后/重复 quiescent、processStopped 与 reservation/attempt 精确比较 |
| stop → ExecutionService → fenced(false) / terminal | run → budget → task；fence/lease/current attempt/worker 仍在同事务核验；未决效果不得清算成可重放 | stop-cancel 与 tool-unknown 的 accepted/current 终态及 held 对照 |
| modelFailed → ModelDispatchService → settlementModel | evaluation settlement → fenced(false)（run/budget/task）→ 原 dispatch/step/checkpoint 证明 → cost/tokens/metadata；无HTTP长IO | model-null/known/corrupt/missing；corrupt 的明确拒绝事实提交，missing 的404与无部分更新回滚 |
| toolFailed / uncertain → InvocationService → invocation records | 原特殊提交拒绝顺序；事务内 fenced(false) 后原 invocation CAS/预算/event；UNKNOWN 不释放不确定效果 | 同一个 old/new 驱动的 tool-failed（重复CAS/旧fence）与 tool-unknown |
| public/model session → query view → JDBC；Mapper适配探针 | READ_COMMITTED；2秒原 end、1秒lock wait；原 owner/current access/whole scope、源锁与 caller/原槽证明顺序不另排；ResultSet结果边界实计 | v4 search→READ实际消费、公开READ证明、旧新同schema快照、异步partial-body504、budget锁、v1–v3 read/search/trace |
| authority loss → afterCompletion → persistScopeInvalidation | 原事务已完成；同 DS/manager，原deadline内REQUIRES_NEW，budget → caller task → 单调 invalidated_at → metadata | 实际 installation epoch 变化使原查询回滚；后记invalidated_at并阻断正文，安全metadata仍200 |

全表/全入口**静态追踪库存已交付**，全入口逐语句的手工锁序及 WHERE 全键/tenant关闭仍 OPEN。继承 `lock-order-checkpoint.md` 的旧行号核查，并用新图定位现源码；动态 JDK 回调、框架绑定及 metadata 固定白名单的表达式仍须在 B2–B4 切换时逐项核对。本图不是运行期trace、全授权竞争证明或死锁穷举，原未知持锁者仍未知。

## 本批必要差异验证

统一沿 Maven verify 生命周期，固定同一新JAR，仅覆盖本批影响，不重跑无变化旧完整矩阵：

```text
mvnw.cmd -B -ntp spotless:apply
node docs/verification/XIAO-60/capture-paths.cjs
mvnw.cmd -B -ntp verify "-Dtest=PersistenceAssemblyTest,ModelFailurePolicyTest,PersistencePathTrackingTest,PriorSessionContractsTest,SessionContractsTest,SessionSearchContractsTest,SessionTraceContractsTest,SessionDeadlineTest,WorkerLoopTest,PackageBoundariesTest,RefactoringNegativeGatesTest,RefactoringFrozenInputsTest,ModelProtocolTest,ModelProfilesTest" "-Dit.test=PersistenceFoundationIT,SessionQueryAdaptersIT"
mvnw.cmd -B -ntp spotless:apply test "-Dtest=PackageBoundariesTest,RefactoringNegativeGatesTest"
```

定向 verify 已 exit0：108单测、3项集成，0失败/错误/skip，SpotBugs0。最后仅收窄测试门禁中的驱动 ThreadLocal 例外为完整包名，并将负例改为业务包中的同名类；再补验上述两个门禁类（4项），不重跑无变化的IT。补验前后产品 JAR 摘要相同，最终选中报告为104项未改用例加4项最新门禁用例；原verify日志完整保留。

需要进程级 `JIMU_IT_JAVA` 指向已核Linux Java21，`JIMU_XIAO55_JAR` 指向已接受旧JAR（SHA256 `4e0a485e997354197f625999d0836b7c75087666bedd678a8f90fe20aab6cb5e`）。缺任一资源明确失败。最终数量/退出码以附件报告为准；开发自测不是独审/QA。私有MySQL8.0.46、paid=false受控HTTP，无真实Key/付费/共享DB变更。

FoundationIT 对同一个旧/新 binary 驱动比较八组真实账本快照：model-null/known/corrupt/missing、tool-failed/unknown、claim-recovery、stop-cancel；另测同DS连接/BaseMapper、commit/rollback/持久拒绝、复合键tenant、二worker、幂等、SSE、授权下载、取消及schema22顺序回退。QueryAdapterScenario 在实际MySQL上验证Mapper/JDBC同计数（空结果/忽略列/NULL/binary/微秒时间）、read/bytes/ops的N与N+1、scratch拒绝、beforeCommit截止和无迟写/下一查询正常。

SessionQueryAdaptersIT 第一项验证v4完整proof/实际native消费、同库旧新公开history/search/READ快照、异步body与授权两阶段累计及绝对截止、锁超时不消费、撤销和回滚后失效。第二项只重验受影响的旧read/search/trace normal切片，不复跑todo等无变化完整矩阵。

负例门禁实际编译并拒绝Controller存储依赖、kernel Mapper依赖、借用驱动类名的业务ThreadLocal；source graph stale与924项冻结输入保留自动检查。新增反射例外只限 QueryResults 与 SessionQueryInterceptor 的标准JDBC代理，未豁免整个包。

本轮首次query验证因夹具把撤权后的安全metadata200误断言404而失败；修正夹具为实际installation epoch失效查询404及安全metadata200，保留首次日志，不把它写成产品故障已由独立复验关闭。首次基础检查点的旧完整verify exit1、Windows/proc限制与SourceIntegrity历史摘要失败继续保留，未跑旧完整矩阵消除记录。

## 关闭与剩余

已完成：四个Service的上述实际用例、显式context/提供处实计/绝对deadline适配、同DS/UoW/durable-off装配、自测驱动与静态路径库存。本批无DDL、快照/协议/冻结资源变化；V1–V22/schema22原样，未运行或等待GitHub CI。

仍开放：B0全入口逐语句锁序/full-key/tenant人工关闭；B1四模块其余用例下沉及端口收口；35表+receipt真实Mapper切换（全部OPEN）；B2–B5清除旧业务JDBC/过渡facade、值治理、全部163文件与V01–V12最终闭环、独审、QA及业务验收。不得把本批标为B0/B1整体关闭或据此自动激活B2。当前没有交给初级的冻结互斥切片，集成/query/事务/共享文件继续唯一写入。

本批无部署；回退仅验证私有库中停新进程→旧JAR读取→停旧进程→新JAR读取的顺序切换，Flyway checksum一致，没有混合worker写入。所有私有进程、容器、fixture及目录必须在附件cleanup中证实释放；生产回退和共享数据操作未执行。
