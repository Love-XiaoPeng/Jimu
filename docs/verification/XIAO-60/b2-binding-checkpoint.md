# B2 共享评估绑定部分检查点

从 `980bed36b8fb781a80950781d1c9098bbf572f97`、tree `bc019977e19fa9baf5c49a13cd071bba56966392` 接续原分支和草稿 PR22。源码/tree、实际受测同版 JAR、运行结果及清理以本批 manifest 为准。此批只收口共享评估绑定切片；B2 未整体完成，35张表＋只读 receipt 均 OPEN，开发自测不替代独审、QA、业务验收或合并。

## 真实调用路径与规则归属

| 实际入口 | 共享 Service 路径 | 同事务持久化/边界 |
| --- | --- | --- |
| TaskController → TaskServiceImpl → JdbcLedger.submit | bindEvaluation → EvaluationBindingServiceImpl.bind/plan/qualify | 原 submit UoW；run 和 tenant 已在受理前缀锁定；MapperEvaluationBindings 按原分支重入 tenant 锁。受理其它策略、普通新增和投影仍待后续 task 切片 |
| Worker → ModelDispatchServiceImpl.snapshot → MapperModelWorkflows.lockModelSnapshot | checkEvaluation → EvaluationBindingServiceImpl.check/bind/plan/qualify | 原 LeaseUnitOfWork admission；evaluation→task/fence 前缀保持；共享校验内按需读取原 tenant 锁 |
| Worker → ModelDispatchServiceImpl.dispatch → MapperModelWorkflows.lockAdmission | validateEvaluationAndMaterial → 同一 check/bind；planAllowance → 同一 plan | 原 evaluation→budget→task→step 锁；材料校验和已有分组配额之后继续原预留/写入流程 |
| 其它已有 lease/whole-scope 再核路径 → JdbcLedger.checkEvaluation | 同一 check/bind/plan/qualify | businessTask 仍走既有 requireBusiness 边界；没有把评估计划当作业务执行授权 |

`EvaluationBindingServiceImpl` 真实持有规范计划/摘要/版本/上限比较、当前配置及付费/stream 匹配、首次整组额度、阶段B OPEN/UNKNOWN/资格与前置任务、阶段B剩余额度、冻结任务绑定/列校验和拒绝优先级。`EvaluationPlan` 是JDK值契约；原字段、评分阈值、成本向上取整和冻结限额不变。`RuntimeEvaluationPlanCodec` 只做既有 JSON、摘要和规范资源加载；`ModelProfiles.checkEvaluationModel` 继续保持原profile/严格本地测试替身能力/价格验证，没有扩大权限。

`MapperEvaluationBindings` 不接收外部SQL、不作资格判定，直接复用调用者 `ledger.mappers` 与 `queryContext()`，不创建DS、事务、SqlSession或上下文副本。端口返回 Run/Room/Screening/Selection，不把数据库Map带入内核。

| 类型化事实 | 原有实际 Mapper/SQL | 键与锁 |
| --- | --- | --- |
| run | EvaluationBudgetMapper.selectRunForUpdate；调用者先锁定后传入 | tenant_id＋run id，FOR UPDATE；原query实计和deadline |
| conflictingBinding | TaskMapper.selectConflictingEvaluationBinding | tenant＋evaluation_run_id；计划digest及阶段B资格digest谓词 |
| hasTask | TaskMapper.selectExistingEvaluationTask | tenant＋run＋evaluation_snapshot非空，LIMIT1 |
| hasUnknown | ReservationMapper.selectEvaluationUnknown | reservation/task tenant和task完整联结，tenant＋run＋HELD_UNKNOWN，LIMIT1 |
| screening | TaskMapper.selectEvaluationScreening | tenant＋run＋阶段A；Service逐候选/样本核定唯一终态和finalist成功 |
| hasPhaseBTask | TaskMapper.selectPhaseBEvaluationTask | tenant＋run＋阶段B，LIMIT1 |
| lockTenantRoom | 原 JdbcLedger.bucket → TenantBudgetMapper.selectBudgetForUpdate | tenant完整键，FOR UPDATE；初次整组及首次阶段B分支的原位置 |

没有提前加载上述查询，没有因run额度已不足而提前跳过tenant锁，也没有在已有阶段B绑定时新增tenant锁。check仍先完成当前绑定/资格校验，再检查task摘要/解析/列；保留旧拒绝优先级。Service加入调用者已开启事务，无独立提交、补偿、重试或网络IO。原实计/绝对deadline、持久拒绝白名单、原子结算、fence/权限、scope和媒体修复顺序继续适用。

## 删除清单与表项

- 删除 JdbcLedger.plan 的规范计划/配置/冲突规则正文；只保留现有入口到共享Service的适配。
- 删除 JdbcLedger.bindEvaluation 的首次矩阵/stream/阶段B资格、screening及剩余额度规则正文。
- 删除 JdbcLedger.checkEvaluation 的冻结评估绑定规则正文；保留businessTask转入既有业务准入的边界。
- 删除 runtime EvaluationPlans.qualify；资格规则由共享Service持有。嵌套值移到kernel/model/api；taskCost公式只保留一份，原工具入口委托此公式。
- **本批新增/删除旧SQL均为0**：上述5个事实查询上批已迁Mapper，本批把业务规则与事实端口分开，不把此前55处SQL替换重复计为新成果。原静态JDBC字面调用仍465、动态构造24、参数绑定1424，153个原入口库存不是实际业务全覆盖。35表＋receipt仍全部OPEN。

## 必要验证与限制

使用 `verify` 生命周期，选择共享绑定/资格、profile/protocol、真实持久装配（含durable=false不接DB）、Mapper/路径库存、冻结输入和架构门禁；集成入口为 `B2EvaluationBindingIT`。实际数量、exit、SpotBugs、JAR和清理以最终日志/manifest为准。

重跑前必须整体归档并移走 `target/failsafe-reports/b2-binding`，确认该目录不存在；不得复制后继续复用旧输出目录。工作区副本和原始证据只在对应尝试的独立目录保存。

```text
mvnw.cmd -B -ntp verify "-Dtest=EvaluationBindingServiceTest,EvaluationPlansTest,ModelProfilesTest,ModelProtocolTest,PersistenceAssemblyTest,PersistencePathTrackingTest,B2MapperContractsTest,RefactoringNegativeGatesTest,RefactoringFrozenInputsTest,PackageBoundariesTest" "-Dit.test=B2EvaluationBindingIT"
```

同一Java驱动对准确980bed3旧JAR/新JAR执行真实submit与modelSnapshot：计划损坏/非规范、首次run和tenant矩阵不足、冻结快照/列/选择漂移、冲突绑定、阶段B缺资格/坏分数/缺前置/finalist失败/额度不足/UNKNOWN、正常绑定、拒绝账目回滚及双连接同时绑定同一样本。另比较规范计划、正常task绑定JSON/摘要和限额原字节。私有MySQL general log记录实际连接、SQL、事务commit/rollback；合成阶段A终态与qualification只用于分支覆盖，不是实际评分或历史业务成功证明。驱动在证据完成后显式终结其故障夹具，不冒充损坏快照的产品取消成功。

受控本地provider下的W1、W2、W2 T1进程恢复以及合成qualification的W3保留实际模型轮次、计账、报告和授权下载断言。不运行旧完整矩阵；不扩大为真实付费、模型质量或付费视觉验收。失败/中间制品/未覆盖项如实保留，原有历史附件不重制。

历史1205仍以原始JDBC观测 `1205/40001` 为准确记录；旧摘要 `HY000` 不准确，没有证据推断服务端或采集层转换。两份旧原记录、历史失败和付费视觉原拒绝保留，不以本批成功抹除。

本批首次verify exit1：40单测通过、SpotBugs 0，两项集成失败。worker限制参数接错主分支，误入旧材料路径后定向中止；直接对照配置未声明关闭评估计划，4096输出上限被profile校验拒绝。原驱动、日志、中间JAR及两份完整清理回执保留，不计为产品路径通过。

第二次verify exit1：40单测通过、SpotBugs 0，两项集成仍失败。准确旧JAR的直接受理先被 `CAPABILITY_REVOKED` 拒绝，因夹具只安装web_fetch，尚未进入本次规则；改用既有ReportExperiment提供实际read/write/present安装和workspace。W1及W2恢复完成，但旧worker在宿主收据核实前已被父进程reap，旧attempt仍quiescent=0、run预留保留、tenant running=1，随后W3保持QUEUED并超时；没有model dispatch或评估规则拒绝。原事实和清理保留。绑定恢复夹具改为先kill精确子PID、保持未reap直至产品按PID/start/boot/namespace记录静止事实，再回收并恢复；不改产品证明/释放规则、不清空配额、不扩大running限额。

第三次verify exit1：40单测通过、SpotBugs 0；worker集成的W1/W2恢复/槽释放/W3通过，对照集成在准确旧JAR先拒绝。原“selection-drift”同时把profile.stream从false改成true，先触发既有 `MODEL_CONFIG_UNAVAILABLE`，没有进入共享绑定，测试原期待错误。夹具现保留相同非stream profile、只把评估选择改为B/W3，核定应先拒绝缺失资格的 `EVALUATION_PHASE_LOCKED`；另将worker ID包含运行标签，避免旧/新驱动之间复用主键。第三次原驱动/日志/清理与真实worker通过证据保留，不能把错误期待改写成产品失败或整轮通过。

第四次verify exit1：40单测通过、SpotBugs 0，worker集成通过；旧JAR直接对照完成normal及计划/初始额度拒绝共8项，随后snapshot-digest注册worker时触发 `Data too long for column 'id'`。夹具将完整run标签拼入worker ID导致超出原字段长度，未进入该项待测modelSnapshot。现将同一唯一输入编码为36字符UUID，保持旧/新运行隔离；产品字段和实现不变。原驱动、原始异常、两项集成结果及完整清理保留。

第五次verify exit1：新夹具表达式未符合Spotless格式，构建在format检查停止，未执行测试。原日志保留，随后执行Spotless格式化再验证，不计作产品测试失败或通过。

第六次verify exit1：40单测、全部worker业务断言及21项旧/新绑定结果、2组冻结字节比较均通过，SpotBugs 0；两项集成最终仍失败。执行者归档第四轮时复制而未移走输出目录，导致ReportExperiment.cleanup的workspace复制遇到FileExistsError；finally仍完成两套实际进程/容器/root清理，清理回执时间为本轮。该输出目录含前轮残留文件，完整保留为sixth-runtime并明确不能当作隔离证据。第七轮以不存在的空输出目录启动，重新收集独立证据；产品代码不变。

最终第七次verify于2026-10-24 13:42:19+08:00完成，exit0、BUILD SUCCESS：40单测＋2项定向集成通过，SpotBugs bugs/errors均0。独立输出中的21项旧/新决定、2组冻结字节比较、W1/W2恢复/运行槽释放/W3及19轮受控模型请求通过；两份最终清理回执均确认进程退出、fixture停止、私有容器及root移除，无清理失败。general log的正常A、首次B及初始cost拒绝三条分支归一化SQL结构/顺序一致；竞争记录各有两个物理连接、一次commit和一次rollback，不代表穷举所有调度。

受测JAR为43,570,261 bytes，SHA256 `1b1d551cd83e572728c76b6a392efc89357d95bfddb9420d7da840493176f817`；830个编译文件逐项与JAR比对相同，旧适配器嵌套值类型及测试驱动未打包。源码SHA/tree和远端回读见本批manifest。以上均为开发自测，不替代独立审查、QA或真实业务/付费验收；此前六轮原始失败记录继续保留。

## 剩余与接续

下一实际已授权B2切片为 Invocation `admit/success/UNKNOWN` 的完整Service/类型化端口/Mapper调用链及对应旧SQL删除；之后task/artifact/operator、受理/下载业务下沉和剩余业务证据逐项关闭。当前共享业务scope/material/session等传递路径和更广task准入仍有旧实现，不以本切片或静态Mapper存在关闭整个调用树/任何表。

B3–B5、初级、61独审和62QA仍未激活；当前无成员决策/新授权缺口。无DDL/协议/冻结限额/业务新功能，无部署、真实Key/外部计费或共享数据操作，不运行或等待GitHub CI。无数据迁移时可按阶段回退到980bed3与其原JAR，保持唯一写入；本机对照不是生产混跑或部署授权。
