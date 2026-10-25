# XIAO-63 宿主调用网关切片

输入提交 `efba6c732f23634755402438f7d275502d8526a3`，tree `a360b902d47c1b60ca773c10fb119693e1edd74d`。本片是成员 2026-10-25 新授权的并行实现，不代表 XIAO-58 总重构完成。

## 实际调用与兼容边界

`DurableAgent.Session.manager → 原生 DefaultToolCallingManager → 绑定回调 → HostInvocationGateway.invoke(lease, stepId, call, input)`。

网关在原包内承担真实的宿主分派、输入/输出校验、admit、执行、结果提交和失败/UNKNOWN 处理。旧 `Session.invoke/invokeSlot` 及 session、todo、question、MCP 分支已删除，只有一套网关实现。工具槽仍由原 manager 按已持久化 Call 分组及顺序消费，网关不重新选择或分配槽，不持有跨调用的 lease/step 状态。

所有分派方法对指定输入提交做方法体对照：仅把原隐式 `lease`、`step.id()` 改为显式参数并调整格式，规范化后方法体完全一致。保留公开 ExecutionLedger 及原能力接口调用，当前 fence/授权/scope 重核继续由原账本负责；不加入新的持久实现。QUESTION_PAUSED、Directory/Todo/SessionCommitUnconfirmed 沿原 attempt 收尾传播；原 FileFailure 转换、失败与 UNKNOWN 分类、原输出字节及资源所有权保持。

公开 DurableAgent 构造器、run/close 和全部 wire record 不变，Schema 校验器随原构造流程创建；ProviderHttp 仍由 DurableAgent.close 释放。未修改模型请求/提交、快照编码、bootstrap、POM、Payloads、持久化、session/scope/transaction/query 或冻结资源。

## 验证

Windows / JDK 21.0.12，Maven Wrapper 3.9.9。命令在本片独立 worktree 执行，无 GitHub CI、真实 Key、收费请求或共享服务操作。

| 命令 | 结果 |
| --- | --- |
| `.\mvnw.cmd -B -ntp spotless:apply` | exit 0，仅格式化本片 Java |
| `.\mvnw.cmd -B -ntp validate test '-Dtest=HostInvocationGatewayTest,PackageBoundariesTest,RefactoringNegativeGatesTest,TodoContractTest,UserQuestionContractTest,SessionContractsTest,SessionSearchContractsTest,SessionTraceContractsTest,PriorSessionContractsTest,ModelProtocolTest,ModelInputBoundTest'` | exit 0；145 项，失败/错误/跳过均 0；含编译、Enforcer、Spotless、Checkstyle 和现有架构正反例 |
| `.\mvnw.cmd -B -ntp verify '-Dtest=HostInvocationGatewayTest' '-Dit.test=TodoIT,UserQuestionIT'` | exit 1；新增 5 项复验通过，打包成功，SpotBugs BugInstance/Error 均 0；真实 IT 两项均在缺 `JIMU_IT_JAVA` 的资源前置断言失败，未进入 MySQL/worker 业务执行 |

此轮打包 JAR SHA256：`ef5ceafb4ea121b74510d5e704837de1d8af46c2114658a5956a78590fb0d043`；仅作为打包记录，不声称真实业务验收通过。`git diff --check` exit 0。

新增 5 项风险测试通过真实 DurableAgent.run、唯一 advisor 和原生工具管理器回调执行：同名 todo 两槽按序、原 provider ID/lease/step/pin/输入和输出；question 暂停直达 QUESTION_PAUSED；todo DataAccessException 直达 TODO_UNCONFIRMED 且无下一模型步骤；session 不确定异常保持同一实例；输入不匹配不消费原槽。使用 paid=false 测试 profile 与冻结模型响应重放、内存账本替身，不进行模型网络请求，**不是实际数据库、HTTP 模型或全量业务验收**。

本机未配置 Linux Java21 与历史 V15/V16 制品，不借用其他开发者的私有 DB/容器。后续在集成私有实例核对必要 paid=false HTTP/真实槽恢复与暂停/提交不确定分支；不重跑无变化历史完整矩阵。

## 集成顺序与剩余边界

交付为一个基于上述检查点的代码提交，文件仅 DurableAgent、新 HostInvocationGateway、其专属测试和本文。由中级 A 在 PR22 当前实施分支串行 cherry-pick 本片提交；若其 DurableAgent 已继续演进，仅对齐本片字段/构造与回调委托，保留 A 的其他改动，不覆盖文件。然后在集成 SHA 上做必要独审、QA 和真实工具分派验证，再由 PM 核收；本片不合并 master 或 PR22。

本轮网关依照冻结范围保留在 springai adapter 包，尚未完成架构方案中的纯 Java execution 网关/attempt 用例、SpringAiSession 或 CheckpointCodec 总拆分。后续迁移由集成阶段核对，本片不增加转发 Service 壳或第二 advisor 循环。
