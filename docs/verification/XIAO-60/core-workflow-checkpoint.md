# B0/B1 核心工作流与路径复核

本文件保留 ff52841 批次记录。后续媒体锁序的架构结论、两处最小修改、当前定向验证及剩余边界见 `media-lock-checkpoint.md`；下文“媒体仍 OPEN”是该历史检查点的状态，不代表当前方法仍保留反向前缀。当前 SQL/符号捕获已随媒体修复刷新，35 表＋receipt 迁移仍全部 OPEN。

接续源码 `363214ae30b5bde6f08c5d4c4bff153f3a1a3abd`，唯一分支 `agent/agent/7aeccb14d7a9`，草稿 PR #22，base master。接续前已回读分支及 PR head 为该 SHA，master 为 `620d5e0e5bfe7b5da530f10997936466159ba34f`。最终源码/tree/同版 JAR/远端回读/原始报告见本批附件 manifest；开发自测不代表独立验收。

## 实际业务实现与存储边界

| ServiceImpl | 本批新增实际编排 | 原有与过渡边界 |
| --- | --- | --- |
| Worker | renew 的精确 lease/fence/live CAS；register、processes | 已提取 claim、quiescent、processStopped 不重复实现；基础设施 worker ID 跨 tenant，不是用户提供的 tenant 授权 |
| Execution | checkpoint 材料、清理、版本、摘要；prepare 重放、ordinal、前步完整性、模块验证、media/step/projection/event/final fence；finish summary、问题暂停、报告/未决检查、summary/terminal | stop 保留；具名 ExecutionRecords 绑定原 task 锁；模块特定校验暂经适配器，后续模块批次迁移 |
| ModelDispatch | model/tool snapshot；dispatch 摘要、配置、材料、输入、付费成本与请求预算、token/次数、plan/storage、dispatch/reservation/event；responded 原 dispatch 绑定、成本、受限载荷、已知/UNKNOWN token、拒绝审计、原槽顺序和绑定 | failed 保留；模块验证和计账端口保留精确业务名称，不暴露 SQL/Map；模型 HTTP 在事务外 |
| Invocation | admit 特殊提交拒绝顺序、binding/material/pin、成功摘要重放、PENDING/预算、unit/RUNNING/event/fence；succeeded/internal MCP commit 的生产者、证明、CAS、结算、event、材料后验 | failed/uncertain 保留；插件专属提交仍归模块；未知效果不能再次执行 |

规则编排在纯 Java ServiceImpl，内核无 Spring/JDBC/MyBatis/Map 依赖。JdbcLedger 的上述 public 入口转向 Service；新增三组具名 JDBC records/workflow 端口用于同事务存储及后续模块钩子，不是业务 Mapper 已切换。LeaseUnitOfWork.admission/directory 复用原 leaseTx/directoryTransaction，普通 required 仍使用原 UoW；没有第二套事务、双写或新的 DataSource。

保持三类普通持久拒绝 allowlist、lease 材料拒绝事务外抛错。受限模型响应拒绝提交已知实际 usage 与 REJECTED 审计，不生成执行意图；普通模型响应在取消后可结算并记录意图，后续新效果准入拒绝取消。工具成功的材料后验失败保留已确认结果和结算，同时 STOPPED 后事务外抛错。问题暂停也先提交后抛出 suspension。其他错误正常回滚。

显式 SessionQueryContext、提供处逐行逐列实计、累计资源和绝对 deadline 沿用已实现契约；query/afterCompletion 与 Mapper 使用同 DS/manager/UoW，beforeCommit 不刷新 deadline。durable=false 仍无 DB 依赖；V1–V22、冻结资源、协议和依赖版本不变。

## B0 当前路径复核

`capture-paths.cjs` 的 attributed javac 图固定基线153个入口声明（含值对象/回调，不等于153个业务端点），覆盖当前 Service/records 调用、参数、条件和 callbackOwners。SQL 识别改为 SQL 动词后必须有空白，排除 created_at/SETTLED 等词法误报：基线592、当前581个真实字面量调用点，另23个动态/转发候选；不能用旧623/620与新计数之差推断业务语句被删除。

`review-paths.cjs` 生成 `evidence/reviewed-entries.csv` 和 `reviewed-paths.json.gz`：153项逐一对应现符号、目标 Service、事务/锁/拒绝策略及有序分支调用；全部581语句保留原 SQL、SHA256、1746个参数位置/实际表达式、WHERE/ON/HAVING、表主键、WHERE键等式、集合/聚合/幂等/原槽/全局扫描/只读receipt分类。SELECT/SET列名不算键谓词；多表关联、集合和CAS保留具名条件，不能替换成通用 by-ID。动态点明确为固定白名单、固定分支或内部参数化转发，不接受外部 SQL。

entry.reachable/sql是包含接口回调候选的保守并集，不能把每个候选都当成该入口实际执行的语句。逐路径判断使用graph中的具体调用参数、lambda工厂、callbackOwners、条件与上述人工分支策略；共享事务执行器的候选并集不构成一条统一锁序。验证组是适用场景映射，SOURCE_REVIEW_ONLY明确表示本批未执行该模块完整场景。

旧两个事务回调映射到 SessionQueryTransaction 的 afterCompletion/beforeCommit；旧 queryForObject/queryForList 包装映射到 SessionJdbcTemplate.query→QueryResults.meter，实际 ResultSet 提供处计量。旧行号和“出现主键列”均不是当前关闭证据。

| 分支 | 当前事务与锁序核实 | 验证及限制 |
| --- | --- | --- |
| fenced/admission | 原 caller 事务；evaluation(lease, admission)→tenant budget→精确 task→当前访问/pin/material/fence；false允许既有结果结算 | core old/new 故障/取消/旧 fence；并非所有模块都只走此顺序 |
| claim | 事务外候选；事务内 run→budget→去重排序scope source→精确 task SKIP LOCKED→重验run | 双 worker及原恢复差异；未知writer/effect不重放 |
| execution/model/invocation | Service进入原 admission/required；各原槽、绑定、预算、CAS、event及后置fence保留顺序 | 新22组old/new工作流＋原8组结算；prepare/dispatch/success真实双连接barrier竞争 |
| owner/query/scope | 原当前身份→具体分支的budget/source/task→scope/projection/原槽→正文/证明；显式原deadline提交前复核 | 受影响 query adapter、旧read/search/trace切片；scope失效在原事务结束后REQUIRES_NEW，仍用原deadline |
| worker/maintenance | global candidate/host扫描有意跨tenant；每次变更使用可信候选tenant/task并加锁重验；worker收据逐task事务 | 基础装配/worker/清理；不把基础设施范围当作tenant遗漏 |
| media例外 | reserveImage/imageSucceeded继承 budget→fenced(evaluation→budget→task) | 源码已明确；付费run下反向持锁交错风险仍OPEN，不在B1擅自重排，也未验证关闭 |

源码路径/参数/分支边界复核与实际业务 Mapper 切换是两件工作。**35张表＋外部只读receipt全部OPEN**；receipt保持只读和0/1/多行语义，不虚构主键。静态图不代表全部运行分支、框架回调或并发排列已执行。媒体反向锁序、原现场未知持锁者和未覆盖模块风险保留；不能据本附件宣布B0无剩余风险或自动激活B2。

## 差异验证与保留失败

CoreWorkflowScenario 用同一public API驱动分别运行accepted/current binary，比对22组规范化task/step/dispatch/invocation/reservation/event快照：checkpoint空/坏摘要/清理、prepare重放/未完、dispatch摘要/额度、response已知/未知/非法/取消、admit绑定/pin/未决/重放、成功重复、finish未决/成功、跨tenant及三个barrier竞争。仅过滤UUID/时间；次数、账目、状态及拒绝保持比较。私有MySQL general log保存实际SQL、连接thread及事务边界，不能把探针SQL当成产品Mapper。

问题/todo/directory只跑受影响normal集成切片；query adapter与原read/search/trace切片验证响应/prepare钩子、同DS及deadline。无变化的旧完整矩阵不重复执行。最终命令、测试数量、exit、JAR hash和cleanup以附件原始证据为准。

最终定向 `mvnw.cmd -B -ntp verify` exit0：109项单测、4项集成，0失败/错误/skip，SpotBugs0；使用与前批相同的14个单测类和PersistenceFoundationIT、SessionQueryAdaptersIT。本批新增SQL精确对照单测及模块hooks集成，因此数量从108/3增加为109/4。8组结算＋22组工作流old/new快照一致；问题/todo/directory及query/legacy的8个私有fixture均清理完成。实际SQL原文另有22个task→connection→lock/commit/rollback行号索引，便于核查事务边界；不推断未执行分支。

```text
mvnw.cmd -B -ntp verify "-Dtest=PersistenceAssemblyTest,ModelFailurePolicyTest,PersistencePathTrackingTest,PriorSessionContractsTest,SessionContractsTest,SessionSearchContractsTest,SessionTraceContractsTest,SessionDeadlineTest,WorkerLoopTest,PackageBoundariesTest,RefactoringNegativeGatesTest,RefactoringFrozenInputsTest,ModelProtocolTest,ModelProfilesTest" "-Dit.test=PersistenceFoundationIT,SessionQueryAdaptersIT"
```

本批保留：提取脚本把SQL表名step误写为ledger.step造成首轮MySQL失败，修正后新增“新records SQL必须精确来自accepted语句”的门禁；普通取消后响应的夹具错误预期及修正过程；随后夹具在取消后读取pin被拒绝，改为取消前取得原pin；架构门禁拒绝跨模块snapshot.port，摘要契约改为发布在snapshot.api，没有扩大豁免；格式/编译/路径review工具失败。旧完整verify、SourceIntegrity、POSIX/proc限制及原query夹具失败继续保留，未重写成成功。

## 关闭与剩余

B1上述核心剩余用例已落真实Service/typed port；B0补齐当前符号、精确参数/谓词、分支锁/事务/特殊拒绝及适用验证映射。B0媒体反向锁序并发证明仍OPEN；全表Mapper切换、后续模块钩子拆分、过渡facade/JDBC删除、值治理与B2–B5未激活，独审/QA和最终业务验收未进行。XIAO-60继续in_progress，PR仍草稿，由产品经理核定下一批范围。未交初级切片，无新增互斥文件委派。

仅私有schema验证顺序停新→旧binary读取→停旧→新binary读取，checksum不变；没有共享数据、部署、真实付费调用或CI。附件cleanup须确认全部私有子进程/容器/fixture/目录释放。
