# XIAO-60 B0/B1 基础检查点

> 本页保留 `9dabdbea` 首次基础检查点及其失败记录。接续实现、最新验证和关闭/剩余清单见 [B0/B1 核心业务及显式查询检查点](core-query-checkpoint.md)；不能将本页过去的“未实现”说明或旧测试数量当作新源码结论。全项仍为 in_progress。

本目录记录过渡实现，不是整体重构验收。集成分支 `agent/agent/7aeccb14d7a9`，[草稿 PR #22](https://github.com/Love-XiaoPeng/Jimu/pull/22) 目标 master；继承未合并方案提交 `bcb63c57328d64cbe0efbaeee3575dbbea3d0274`。2026-10-23 独立回读 master 仍为 `620d5e0e5bfe7b5da530f10997936466159ba34f`。ADR-0015 和提案已同步 XIAO-58 产品经理核定引用，没有改写 XIAO-59 原始候选证据。

## 实际实现

- MP Boot4 starter 3.5.17、唯一有界 DriverManagerDataSource、唯一 Spring 事务管理器、SqlSessionFactory/Template 与真实 LedgerUnitOfWork。默认 durable=false 不创建 DS/SqlSession；durable=true 缺配置/资源仍失败。schema22 检查已通过实际 Mapper/XML 执行，一级缓存为 STATEMENT、二级缓存禁用。
- 普通任务能力组合、资源拒绝、默认/上界预算及目标/幂等 key 规则移到纯 Java TaskServiceImpl；TaskController 保留认证、严格 JSON、body 上界和 HTTP/SSE 映射。ArtifactServiceImpl 在账本授权后读取存储，Controller 不再引用 ArtifactStore。
- 普通账本 tx 经 Spring UoW；原三类明确持久拒绝及 leaseTx 材料拒绝仍返回事实后提交、事务外抛错。会话专用事务、scope afterCompletion、socket/deadline 业务方法体未迁写，与 Mapper 共享同一 DS/事务管理器；没有双写。
- Payloads 到 adapters/codec，ModelCosts 到 kernel/budget/domain。`capture-baseline.cjs` 核对两类完整 class body 未变；所有 live src 调用已更新，冻结评测源码不改。新增架构规则禁止内核依赖 MP/MyBatis/Jackson/SQL、Controller 访问存储、runtime 依赖 persistence；仅精确登记 operator 类及其 Command 在 B2 前的过渡例外。

## B0 输入和仍开放的迁移

`capture-baseline.cjs` 固定 924 项原资源/迁移/历史证据摘要，35 张 Flyway 表 + 外部只读 receipt，以及 349 条旧持久化声明词法索引。原 163 文件映射完整继承。索引不是完整 AST/调用图，OPEN 表示尚未逐方法切换和关闭；本检查点**没有把任何业务表计为 Mapper 迁移完成**。

从 XIAO-55 附件下载最终 REVIEW-55-02 的11分片，逐片、合并归档和提取 JAR 均核 SHA256。旧 JAR 为 `4e0a485e997354197f625999d0836b7c75087666bedd678a8f90fe20aab6cb5e`，旧源码 `dfed9348065123ea1b6634425f85ecbd6cc54572`，tree 与锁定 master 相同。只作同库对照/回退，不作为新制品通过证据。

准入差异 fixture 是从准确基线取得的原 TaskController 字节，测试先核摘要，再仅更名编译旧入口，与新入口做65例配对比较：默认值、能力/资源拒绝顺序、幂等 key、goal/预算边界、严格 JSON、认证拒绝及持久请求快照/响应形状。该夹具不打入产品。

[锁序检查点](lock-order-checkpoint.md) 明列已核实路径，未列及范围推断的声明仍需逐入口核查。B0 全路径追踪关闭与 B1 Worker/Execution/Model/Invocation 实际 Service/impl、显式 QueryContext/Mapper 逐行计量和截止适配仍未完成。TaskService 的读/取消及 ArtifactService 暂经 Tasks 端口；旧测试构造保留一处受限装配。B2–B5 尚未启动，不可交总独审/QA、合并或部署。

## 实际验证及命令

原始日志和最终源码/tree/同一 JAR 摘要以本检查点附件 manifest 为准。

| 执行 | 结果/适用边界 |
| --- | --- |
| `mvnw.cmd -B -ntp --version` | Java21.0.12.1 / Maven3.9.9；0 |
| `node docs/verification/XIAO-60/capture-baseline.cjs` | 0；924项冻结输入、36表索引、349声明、两类迁移 body 比对 |
| 首次 `mvnw.cmd -B -ntp validate test` | exit1；322测试、1历史 SourceIntegrityTest（ADR索引3446/实际3819）、15 Windows POSIX/proc错误，0 skip。保留失败，没有修改历史manifest消错 |
| 新增装配/准入/架构定向复验 | 4测试、0失败/错误/skip；旧测试夹具序列化 Instant 的测试工具问题、operator Command漏列的架构过渡例外已修正，原失败日志保留 |
| 首次定向 verify | 101单测通过，RuntimeIT 3项通过，SpotBugs 0；PersistenceFoundationIT 因命令未传 Linux JDK 环境失败，exit1，不算通过 |
| 补齐环境后 PersistenceFoundationIT | exit0、1项真实私有 MySQL8.0.46，全部子进程/容器/私有目录清理完成；最终 verify 含新增负例/冻结门禁，见附件最新日志 |

最终检查命令沿 verify 生命周期，限定本批差异，不重跑旧完整 P2/P3 或 GitHub CI：

```text
mvnw.cmd -B -ntp spotless:apply
mvnw.cmd -B -ntp verify "-Dtest=PersistenceAssemblyTest,TaskAdmissionDifferentialTest,PackageBoundariesTest,RefactoringNegativeGatesTest,RefactoringFrozenInputsTest,SessionDeadlineTest,ModelProtocolTest,ModelProfilesTest,EvaluationPlansTest,PriorSessionContractsTest,SessionContractsTest,SessionSearchContractsTest,SessionTraceContractsTest" "-Dit.test=PersistenceFoundationIT"
mvnw.cmd -B -ntp dependency:tree "-DoutputFile=target/xiao60/dependency-tree.txt" help:effective-pom "-Doutput=target/xiao60/effective-pom.xml"
```

真实 IT 需要进程级 `JIMU_IT_JAVA` 指向已核 Linux Java21，`JIMU_XIAO55_JAR` 指向上述验过摘要的旧 JAR；缺环境明确失败。私有 fixture 使用已有受控 paid=false HTTP，没有真实 Key、付费或生产数据。验证 MP/JDBC 同连接、commit/rollback/持久拒绝结果、缓存关闭、BaseMapper、复合键全键tenant、旧新 binary 同库读取与幂等、双worker、授权下载、SSE和取消；test Entity 只是装配探针，不能当作35表产品实现。

## 未覆盖和回退

V01/V02/V03/V05/V06/V12仅覆盖上述 B0/B1 差异，未声称完整矩阵关闭。会话实计/deadline 的 Mapper 迁移、全部真实授权交错、UNKNOWN/结算/清理、35表+receipt产品路径及B5删除仍待后续批次。本批保留原未知持锁者、历史完整verify exit1、SourceIntegrity失败、未穷举覆盖等限制；新增门禁对比真实 baseline Git 字节，不能抹去历史 sources.json 的不一致。

无DDL或数据协议变化，V1–V22和schema22原样。私有实测停新control、用接受旧JAR读取新任务，再停旧control恢复新JAR读取旧任务，迁移checksum全程不变；没有混合新旧worker写入。真实部署/生产回退没有执行或授权，仍按父项指定唯一写入者后另行操作。
