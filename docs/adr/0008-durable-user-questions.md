# ADR-0008：持久用户问答与同一执行循环恢复

状态：架构实施决定；运行验收待后端、独审和 QA 完成。日期：2026-10-22；事项：XIAO-49，G04/T11。

## 基线与问题

实际 GitHub API master 与 Multica 新 checkout 均为 `0e630f28e39c157788886694e2b16dc0831bb571`，tree `8850a9d11f610257d87962585221b91a6bc19948`。工作树检查为空；没有修改产品代码或冻结基线。API 回读时间及 git 直连失败保留在 `evidence/master-baseline.json`。构建/部署/产品纵切没有在本架构回合执行。

沿用总体方案 0.3、工程规范 0.2、ADR-0001–0007、MySQL/InnoDB、单制品 control/worker 和唯一 `ToolCallingAdvisor`。V15 当前只允许 QUEUED/RUNNING 的领取；任意遗留 RUNNING invocation 会转 UNKNOWN。同步 callback 不能直接等待人的内存 promise，也不能把暂停当成异常失败或 UNKNOWN。

固定 Harness `571902028a75c64c1d767f4ae5fd099485f0ddf0` 的真实工具、service、projection、timed-wait、Web answerer、composer、测试和 MIT 许可均已按 tree Git blob 核验。原始文件及摘要见 `evidence/sources.json`。不修改工具清单 1.1 中 T11 的历史 blocking Schema。

## 决定

1. 首批采用受控 **ask-only task profile**：`capabilityIds=["ask_user_question"]`，resourceIds 为空，使用现有显式本地模型装配且 `paid=false`；不混入 BUSINESS、评测执行 scope、MCP、图片、工作区、报告或其他工具。任务继续使用现有受理、授权、预算、模型检查点与网关，最后提交 summary，不自动创建文件/产物。该限制是本批版本化云端差异，不代表后续混合能力已实现。
2. 最小真实用户入口是现有 control API 新增两个 owner 鉴权路由：GET `/api/tasks/{taskId}/questions` 与 POST `/api/tasks/{taskId}/questions/{requestId}/answers`。直接使用原 `SignedIdentity` / `Authenticator` 和当前 owner/installation 检查。API 客户端即可读取并提交，真实受控验证必须走 HTTP。源码没有前端工程、页面、静态 UI 或现成问答界面；**本批不需要原前端改界面，也不新造站点**。本地现有 HS256 fixture 身份仅限已存在的受控认证配置，不新增答案后门或信任 body 身份。
3. 默认安装 `jimu.interaction.ask-user@0.1.0`，模型仅见 `questions`，阻塞指逻辑上等答案才继续，等待受 task 原绝对 deadline 约束。宿主明确选择 `0.2.0` 才提供 timed Schema（增加 `timeout`，默认 120，允许 -1 或 1–180 秒）。安装版本和新独立 binding 固定模式，模型不能切换模式/接收人。两版不能同时出现在同一任务 catalog；沿用现有按 pin 选唯一版本的装配方式。
4. 新增 V16 和 question 协议 v1。创建问题、工具次数结算、invocation `PENDING→WAITING_USER`、task `RUNNING→WAITING_USER`、失效旧 fence/lease、可靠事件在**同一短事务**。不先调用通用 `admit()` 留下 RUNNING 间隙。暂停从栈展开返回 worker；finally 排空实际 operations 后写 quiescent，才按既有所有历史写者规则释放运行槽。回答/超时可以先写 RESUME_READY，但有旧写者或未释放槽时不得领取。
5. 用现有数据库维护/领取循环扫描持久 due/RESUME_READY；不引入 broker、第二 Agent 循环、通知服务或本机 timer 作为恢复事实。恢复创建新 attempt/fence，重放原模型响应和原槽，已成功槽只读原结果；新问题不重复创建、次数不重复消费。停止/租约丢失/UNKNOWN 仍遵循既有保守规则。
6. timed 的工具结算和问题结算分开：foreground due 到达后 invocation 成功持久化 `{pending:true,callId,message}`；问题为 CONTINUED，直到 task deadline/终态仍可回答。迟到答案持久化为来源绑定的普通 user 数据消息，在下一个尚未 prepare 的模型请求内只收录一次；不修改原 pending 结果，不再次调用原工具。终态任务不复活；终态先提交则关闭未答问题并拒绝新答案。
7. 不改变已发布 ToolSnapshot v1、model snapshot、checkpoint v2/v3、旧 Request 的序列化或 task record_version 1/2/3。question binding 与保留状态是新增 nullable 列；旧任务保持 NULL、原字节和原分支。V16 前必须排除旧二进制并发；旧二进制严格 schema gate 拒绝 V16。回滚使用支持 V16 且新问答准入关闭的制品，不删除迁移/旧账或下调 schema_history。

## 框架暂停依据

保留当前逐槽 callback 机制，新增窄 `plugin.api.UserQuestionPort`；插件只提交类型化问题，不访问 kernel/JDBC。绑定 runtime 调用 `kernel.task.api.UserQuestionLedger.openOrResult`。该操作返回已提交答案，或在持久暂停成功后由 runtime 抛出专用栈展开标记 `QuestionSuspended extends RuntimeException`；这不是 `ToolExecutionException`、`TaskFailure` 或模型输出。

`DurableAgent` 的专用 ask 分支必须在通用 catch/uncertain 之前传播标记；run 的最外层捕获该标记后正常返回。只有这一个标记可走正常暂停路径，其他错误仍失败；不通过字符串错误码放行任意异常。不更改 ToolResult 的通用输出契约。

本机实际 Spring AI 2.0.1 的 javap 显示 DefaultToolCallingManager callback 只捕获 ToolExecutionException，advisor 只捕获 ToolCallLimitExceededException。`SuspensionProbe.java` 经同仓库离线解析的 runtime classpath 实跑 call 与 stream：标记保持对象身份，均仅一次模型请求、前槽一次、ask 一次、后槽零次，无错误工具回复或后继模型调用。stream 的框架 MessageAggregator 记录两条 ERROR 日志，原文已保留；这不是产品任务失败证据，也不声称生产日志行为已处理。后端需用实际 ledger 证明暂停不会写 stop/uncertain，并保持日志不含问题正文。

## 取舍

| 方式 | 结论与代价 |
| --- | --- |
| 现有 MySQL 状态 + 同一 advisor 重入 | 采用。需 V16、窄暂停信号及专用原子账本分支；复用既有授权、pin、fence、预算与恢复证据 |
| 内存 Future / 保持同步 worker 等待 | 不满足重启恢复、释放执行和持久事实要求 |
| 新调度平台/broker/另一图循环 | 当前无需新增设施；增加跨系统提交和恢复协调成本 |
| 新 Web 页面 / 外部通知 | 当前没有可沿用界面；API 已能交付受鉴权最小入口，不扩大到站点、飞书或用户通知 |

详细请求、状态、事务、限额、迁移与验收见 `implementation-contract.md` 和 `contracts/`。本包为交接制品，由原后端随唯一实现 PR 纳入仓库；本回合未创建产品代码 PR。产品经理负责下一次唯一派工。
