# ADR-0009：todo_write 的任务投影与原子提交

日期：2026-10-22；事项：XIAO-50，G27/T66。状态：架构实施决定，待原后端实现及独审、QA、业务验收。

## 基线和问题

实际 GitHub master 为 `777a2e2ae47c3d5be7ac545648c0d90483124ff2`，tree 为 `6bfa20648fdf7124ff8a32f4c6c90ce0cfdfae95`。Multica checkout 停在 `0e630f28e39c157788886694e2b16dc0831bb571`，tree `8850a9d11f610257d87962585221b91a6bc19948`；Git HTTPS 直连失败。另以 GitHub API 固定 master，下载只读 archive，逐一核对 672 个 Git blob；archive 中 mvnw.cmd 的换行差异以固定 raw blob 纠正。取证目录不是 Git checkout，没有修改产品仓库。身份、摘要及失败记录见 evidence/。

已读仓库 AGENTS、README、CONTRIBUTING、总体方案0.3、工程规范0.2、工具清单1.1/G27、ADR-0004–0008，以及实际 V1–V16、唯一执行循环、鉴权、事件、问答与清理实现。上游固定 `571902028a75c64c1d767f4ae5fd099485f0ddf0` 的工具、types、Session、projection/provider、persistence、真实工具/投影/循环测试和 MIT 许可均按 Git tree 核验，见 sources.json。未运行上游测试，未执行产品纵切。

## 决定

1. 首批仅新增 `local-durable-todos-v1`：`capabilityIds=["todo_write"]`、无 resources/images，现有显式本地 `paid=false` 模型，禁止 BUSINESS/评测 scope/远端模型。独立 `todoVersion=1` binding 固定 `allowParallelInProgress=false` 及全部限额；不能由模型或 task JSON 选择策略、身份、session、来源或 revision。不混合旧 ask-only、MCP、工作区和报告 profile，不重新解释旧任务。
2. 工具为 `jimu.todo.write@0.1.0`，调用名仍为 `todo_write`。输入只有 todos；每项只有 content/status；返回上游实际 `{todos,counts:{pending,inProgress,completed}}`。完整替换并保序，ECMAScript trim 后非空且精确去重，允许零或一项进行中。云端描述明确“at most one”，修正上游描述要求 exactly one、实际却仅拒绝多项的不一致；合法数据语义保持。条目没有 id/priority/activeForm。
3. 一次已受理 task 对应一个 owning agent session 的一个用户 turn。新 task 在受理时创建 UNWRITTEN/revision0；attempt、模型 step、恢复和重连均不创建用户 turn，不清空。task 终态对应 turn/end，保留末次清单。下一用户请求创建另一个 task，从 null 开始；不为本批新增会话/续轮 API。
4. 沿用 MySQL/InnoDB 和唯一 ToolCallingAdvisor；新增窄 `plugin.api.TodoPort`、`kernel.task.api.TodoLedger` 和 V17。清单只在账本事务内成为事实，插件不访问 Repository。todo 专用路径绕过通用 admit→RUNNING→toolSucceeded：同一事务从 PENDING 直接提交成功、清单指针、来源、预算结算及可靠事件，消除 RUNNING 崩溃间隙。通用提交必须拒绝 todo_write，不能旁路。
5. M2 生成原有 invocation 时，宿主同时冻结 todo_expected_revision；同一响应内按持久槽顺序分配连续 revision。写入使用 current revision CAS；旧 fence、旧 revision、错误 task/step/slot/input/pin 均不能覆盖新状态。已成功 invocation 恢复只返回原输出，不重写投影；同内容的新调用仍新增 revision、事实和一次工具消费。
6. 只持久化一个最新投影指针，正文使用原 invocation.output_snapshot，避免重复存储与双事实。历史 invocation 保存每次替换的独立结果；投影不承担调度、任务验收、计划批准或任何外部授权。
7. owner 入口为 GET `/api/tasks/{taskId}/todos`。沿用 SignedIdentity、当前 actor/tenant/owner、安装 epoch/version/pin 检查。SSE envelope v1 不变，todo.updated 仅作失效通知；GET 返回同一事务读取的 asOfSeq。客户端先 GET，再以该游标连接现有 SSE；重连/410 后重新 GET。API 无 PUT/POST 清单写入口。
8. 每个 todo task 独立预留 2 MiB，使用新增 tenant todo_storage_held/limit（默认16 MiB），复用 reservation，最多容纳8个未清理的满额预留；不占满普通 profile 的 storage_held。所有正文副本、checkpoint、summary 和元数据均计入此有界预留。仅 todo task 应用新限额；其他租户和无 todo 能力的旧 task 不经过新门禁。
9. 内容保留至受理后7天，终态、所有历史写者已停止且无 UNKNOWN/未结占用后才原子清理；清掉 task/step/invocation 中全部正文副本，保留身份、来源、计数、原摘要、账本及 tombstone，然后释放本清单预留。不确定任务继续占用其原有界额度，不以 TTL 释放未知占用。不修改未知费用或旧账。
10. V17 扩展迁移；不改 V1–V16、ToolSnapshot v1、model snapshot v2/v3、checkpoint v2/v3、Response v2 和旧 Request 字节。binding 与投影为新表记录，六个 invocation 元数据列在旧行上均为 NULL。旧 V16 二进制有严格 schema gate，须证明 control/worker 均拒绝 V17。回退用支持 V17、todo 新准入关闭的制品；不降 schema、不删除历史数据。

## 取舍和代价

| 选择 | 依据与代价 |
| --- | --- |
| 同库原子投影指针＋原 invocation 结果 | 当前工具无外部效果，可用同一 InnoDB 事务完成全部权威事实；需要专用提交分支和 V17，避免伪装成通用外部工具 |
| 通用 admit 后另写清单 | 不采用；会留下 RUNNING/清单与结果不一致的崩溃窗口，恢复可能 UNKNOWN 或重复覆盖 |
| task 而非长期 session 聚合 | 当前 API 已有稳定 task/owner，且没有续轮入口；映射清楚即可保留上游清空/保留语义，不引入新 session 生命周期 |
| GET 快照＋原 SSE 失效通知 | 复用现有鉴权与有界事件队列；客户端要按通知重新 GET，正文不进入 SSE，重连不会执行模型/工具 |
| 单独 todo 存储额度 | 清单的滞留容量不会使旧 profile 误走新额度门禁；仍受显式租户预算和实际清理约束，无新增服务或存储设施 |

唯一实施合同为 implementation-contract.md 与 contracts/；migration-contract.sql 是 V17 设计，不曾在数据库执行。原后端负责入库、实现及实际迁移/事务/恢复证据。本轮不派发后端或其他成员，交回产品经理唯一接续。
