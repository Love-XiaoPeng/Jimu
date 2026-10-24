# B2 ModelDispatch 部分检查点

本批从 `35f8c3e157edd2ab09961a4df0a138be21361034` 接续原分支及草稿 PR22，仅推进已激活的 B2。准确提交/tree、受测 JAR、运行原始结果及清理以本批附件 manifest 为准。B2 未整体完成，35 张表＋外部只读 receipt 全部 OPEN；最终独审、QA、业务验收和合并仍待完整交付。

## 实际调用链及替换

| 入口 | Service / 业务规则 | 实际持久化和表 | 边界 |
| --- | --- | --- | --- |
| Worker → `JdbcLedger.modelSnapshot/tools` | `ModelDispatchServiceImpl.snapshot/tools` | `MapperModelWorkflows` → 已有 Task/EvaluationBudget/Worker Mapper | 原 lease admission、历史快照/能力校验和事务保持 |
| Worker → `dispatchModel` | `ModelDispatchServiceImpl.dispatch`、`ModelEvaluationAdmission`：请求摘要、预算、分组评估配额及活动调用拒绝 | `MapperModelWorkflows` → Task/TenantBudget/EvaluationBudget/Reservation/Step/ModelDispatch Mapper；model_dispatch 普通新增使用 MP insert | evaluation→budget→task 锁前缀及模块后续锁维持原顺序；一事务保留 cost/token 预留及 event/fence |
| Worker → `modelResponded` | `ModelDispatchServiceImpl.responded`、`ModelCostSettlement`：历史价格绑定、实耗、UNKNOWN、越界、拒绝提交及调用意图 | `MapperModelCosts`、`MapperModelWorkflows` → 上述 Mapper＋Invocation；invocation 普通新增使用 MP insert | 实耗与拒绝同事务持久化，事务外抛拒绝；不会重放 provider/工具效果 |
| Worker → `modelFailed` | `ModelDispatchServiceImpl.failed`、`ModelCostSettlement` | `MapperModelDispatchRecords`、`MapperModelCosts` → ModelDispatch/Reservation/Task/TenantBudget/EvaluationBudget | 原 proof-invalid 特殊提交白名单和失败 UNKNOWN/token/session 结算保留 |

`evidence/b2-model-removed-sql.json` 是逐调用点删除清单：55 处旧 SQL，其中53处改为45个固定 XML statement（44个新增，复用已有 `EvaluationBudgetMapper.stopRun`），2处普通新增复用 MP BaseMapper 注入的 insert。原 `JdbcModelWorkflows` 删除，`JdbcCoreRecords.model` 及 `JdbcLedger.settlementModel/settleCost/admitPlanDispatch` 删除；按任务读取历史付费拒绝的 `modelRejection` 同步改用 Mapper。原文件、原 SQL、替代文件、Service、全键参数及绑定顺序可逐项核对。共享 lease admission 的材料后验拒绝 STOPPED 更新也已进入固定 `EvaluationBudgetMapper.stopTaskRun`，保留原先提交拒绝边界。没有新增第二套写入。

复合表不虚构单列主键，不注入按 id 操作；新增实体覆盖本批实际 insert 字段，免费模型的价格空值显式写入。固定 MP insert statement 使用实体字段和显式 query 参数，沿用同一 SqlSessionTemplate、DS/UoW、查询逐行实计及绝对 deadline 插件，无 ThreadLocal。`kernel/budget/api/ModelCost` 发布原有成本算法，内核跨域依赖通过公开 API；没有复制算法或引入框架依赖。

适用锁序仍以当前调用顺序为准，历史行号不能当作关闭证据。本批不改变媒体修复的 evaluation→budget→task 前缀；完整键、tenant、fence/权限、幂等、UNKNOWN、持久拒绝和原子结算保持。会话、todo、directory、材料、scope、storage 等既有模块校验/计量端口仍有旧实现，不能据此关闭其表或宣称本调用树所有传递 SQL 已消除。

## 定向验证及失败保留

最终使用 verify 生命周期，选定35项单测及4项 `B2ModelDispatchIT`，需要已核 Linux Java21 与私有 MySQL8.0.46；缺资源明确失败。只覆盖本批差异，不执行无变化的旧完整矩阵。准确命令、exit、JUnit、SpotBugs、失败历史与清理原件见附件。

```text
mvnw.cmd -B -ntp spotless:apply
node docs/verification/XIAO-60/capture-paths.cjs
node docs/verification/XIAO-60/review-paths.cjs
mvnw.cmd -B -ntp verify "-Dtest=ModelFailurePolicyTest,ModelCostSettlementTest,B2MapperContractsTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringNegativeGatesTest,RefactoringFrozenInputsTest,PackageBoundariesTest,ModelProfilesTest,ModelProtocolTest" "-Dit.test=B2ModelDispatchIT"
```

- 同一 Java 驱动对准确35f旧 JAR及本版执行，比较8项原结算、22项原工作流及15项合成 paid-run 模型账本快照。覆盖退款/实计、UNKNOWN/部分越界/算术溢出、失败实耗、损坏历史价格证明的持久拒绝、缺 token 预留回滚、拒绝载荷不写工具意图、跨 tenant/fence、双物理连接响应/准入争抢，以及实际 evaluation UPDATE 后注入失败并检查 run/租户/任务/预留/调用/步骤/event 原子回滚和重试。
- 新驱动记录连接 ID、autocommit、commit/rollback、SQL；私有 MySQL general log 保留实际绑定参数。测试注入 `1644/HY000` 仅表示明确的合成故障，不混同历史1205。
- 私有 loopback provider＋实际双 worker 覆盖 known/unknown/overrun/M2 崩溃恢复，检查 provider 不重放、真实计账持久化，以及 run 停止经 control 重启后继续拒绝。只有合成 paid-run 数据，不用真实 Key、外部计费或共享数据。
- W1/W2 冻结评估计划真实模型轮次、报告及授权下载；source-body 后验材料拒绝保持已发生工具结算、STOPPED、不发布、不再消耗下一次模型额度。另以明确标注的合成阶段 A 前置/qualification，仅执行一个真实 W3 阶段 B 绑定、6轮调用、分组配额及报告路径；合成质量资格不构成独立评分验收。模块 question/todo/directory 及 session read/search/trace 响应挂钩按影响定向复验。
- 保留首次 Mockito 缺依赖编译失败（改为手写端口替身，未加依赖）、跨 budget 内部域的 ArchUnit 失败（改公开 API）、首次 verify 的计划脚本误入历史 W4 矩阵并主动中止记录。model-cutover 的材料子矩阵现于 W1/W2 source-body 结束后返回，阶段 B 只另跑上述单个 W3 路径，历史默认行为不变。原失败、中止和清理不写成通过，后续实际结果按日志留存。

历史1205纠正沿前批：原始 JDBC 观测是 `1205/40001`，旧摘要 `HY000` 不准确，没有证据证明服务端与采集层进行了何种转换；两份历史原记录/归档保持原字节。本批不重新制作整套历史附件，不改变付费视觉原拒绝和覆盖限制。

本批首条 verify exit1：35单测通过，4项集成中3项通过、1项因上述计划脚本主动中止失败；SpotBugs0。8份私有实例清理回执均完整。中间 JAR 摘要 `7f2b1550a5656e920ce40e0e3a8dc3d0b3015966111b180cd9afb244e011fada`，不能作为55处最终实现的同版通过证据。

最终 verify exit0、BUILD SUCCESS：35单测＋4项定向集成全部0失败/错误/skip，SpotBugs0；8份最终私有实例清理回执均确认全部子进程/受控服务退出、容器及私有目录删除。受测 JAR 为43,555,051 bytes，SHA256 `1d6d9b9cc1483d88d5916c4a88302940b5d5b12a284d27e7ff549cd2483d2b1b`；全部8个实际 fixture 的摘要一致。路径库存从35f的520处降为465处 JDBC 字面调用，仍有24处动态构造，153个原入口仅有静态复核和本批指定运行证据，不等同所有路径独立验收。产品 JAR 内旧适配器类已移除，测试驱动未打包。最终源码/tree 以本批 manifest 为准。

## 仍开放、下一切片和回退

下一具体 B2 切片先收口模型与 task 受理共享的 `plan/bindEvaluation/checkEvaluation` 评估绑定校验：其 SQL 已迁移，本批仅将分组配额和成本结算规则下沉，完整绑定/资格校验仍由适配器持有，不能算整个 ModelDispatch 业务闭环已下沉。随后推进 Invocation `admit/success/UNKNOWN` 完整 Mapper 调用链，再完成 task/artifact/operator、受理与下载业务下沉、剩余旧 SQL 删除及35表＋receipt逐项实际路径证据。scope 组合、维护锁排列及材料路径按后续迁移影响补证。模块完整实现治理和全部清理仍属尚未激活的 B3–B5；不提前派初级、独审或QA。当前没有需要成员补授权或信息的实施阻塞。

无 DDL、协议、冻结限额或业务功能变化。需要撤销本批时以35f源码/对应旧 JAR作阶段回退候选；同库旧新对照只证明覆盖场景的相容性，不代表生产部署/混跑/数据回退已获验证或授权。保持唯一写入者后另行处理。未部署、不运行或等待 GitHub CI。
