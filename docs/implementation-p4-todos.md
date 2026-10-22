# XIAO-50：持久 todo_write

ADR-0009 与 `docs/verification/XIAO-50/architecture/implementation-contract.md` 固定本批契约。附件 ZIP SHA256 为 `3b8b8a861ccb7d606ebf65ea52b10d3739f1f4a7da40d79efd71b3e1292c18a9`，原始契约、Schema、源码、许可证与探针原字节存于该目录；冻结基线不改。本实现仍待准确源码的独立审查、QA 和业务验收。

## 启用和受理

默认 `jimu.todos.enabled=false`。control/worker 都必须使用支持 V17 的同一制品。操作员启用 flag，按既有安装机制授予 `todo_write`，固定 `0.1.0`、输出 Schema SHA256 `5aaeeadb3ce3e7e8c34e991dcc85b87cf4ce5e9502163b9b8668523bf49df99c`；运行身份需要 `task_todo_projection` 的 SELECT/INSERT/UPDATE 权限，生成 SQL 的工具已同步。没有自动安装入口。

仅允许显式配置 `paid=false` 的本地 profile。继续使用原 `trusted-local-v1` 本机传输准入，拒绝 BUSINESS、evaluation scope、付费、远程模型和缺省 paid 值。profile 必须有现行完整输入字节 proof，maxOutputTokens≤4096、responseBytes≤65536；现有更紧限额继续生效。没有 token 上界 proof 的模型不因此获得新许可。

沿用 `POST /api/tasks`、Authorization 和 Idempotency-Key。`capabilityIds=["todo_write"]`，无 resources/images，不混合问答、MCP、工作区或报告。一个 task 对应一个用户 turn；新 task 从 UNWRITTEN/revision0 开始，attempt 重启不清空。task 完成不要求所有条目 completed。清单是模型记录的数据，不授权调度、外部发送、付费、部署或验收。

## 写入与恢复

唯一 ToolCallingAdvisor 循环广告固定 manifest/Schema/description。输入只有 todos；每项只有 content/status，使用 ECMAScript trim、非空及精确去重，保序完整替换，允许零或一项 in_progress。空数组是成功写入，区别于未写。最多32项，canonical 单条 UTF-8≤1024、数组≤8192、原输入/输出≤16384，JSON 深度≤8；拒绝重复 key、尾随值、未知字段、类型强转和无效 surrogate，不截断。

M2 在原 response/usage/invocation 事务按槽顺序冻结 expected revision。插件只能通过宿主绑定的 port 提交该原槽。专用短事务复核 task/actor/安装/pin/当前 fence、取消/截止、binding、原 response 与槽、输入摘要及预算；PENDING 直接变为 SUCCEEDED。清单指针、原输出、revision、实际提交 attempt/fence/time、工具消费与 tool.dispatched/todo.updated/tool.completed 一并提交，最后以 DB 当前 deadline/lease/fence 再做条件写。

成功 invocation 的恢复先验证原来源和输出，再返回原结果；后续清单 revision 更高也不覆盖。相同内容的新调用仍有独立事实和一次工具消费。通用 admit/toolSucceeded/uncertain 拒绝 todo 旁路。专用事务数据库结果不明时仅退出 attempt，排空后停止续租，由既有新 fence 领取原槽裁决；不推定未发生，不向模型返回普通失败文本，不调用通用 uncertain/stop。

## Owner 读取与重连

`GET /api/tasks/{taskId}/todos` 沿用真实身份与 owner/tenant 边界，返回 no-store。无 todo task 返回 ABSENT，不要求 todo 安装；todo task 返回 UNWRITTEN、PRESENT 或 PURGED，包含 revision/asOfSeq、todos/counts、原 invocation/checkpoint/创建 attempt/提交 attempt/fence/eventId/SHA/updatedAt 和 retainUntil。取消、截止、额度耗尽或正常终态保留历史读取权；actor/安装撤权和 pin 漂移仍拒绝读取。flag 关闭仅阻止新受理/执行。

水位、最新投影与来源正文通过一次 JOIN SELECT 读取，身份/安装锁持续至读事务结束。先 GET，再以 `Last-Event-ID=taskId:asOfSeq` 接原 SSE；收到 todo.updated、todo.content_deleted 或终态后重新 GET。通知不含正文。断连/25秒连接结束时先 GET；410 CURSOR_EXPIRED 同样先取最新快照。终态再 GET 一次即可收敛，不需循环订阅。读取或空闲轮询不创建模型、工具或事件。

## 配额、保留和升级

新表独立预留每 task 2 MiB，tenant 默认16 MiB。旧 storage_held 不混用。所有正文副本计入：task request/goal/summary/model/tools、binding、step request/response（包含重复的请求体与消息）、invocation input/output、model_dispatch usage 和 retained_digests；写前加固定65536字节元数据预留。固定字段另以 UTF-8 标量值、每字段8字节、每行32字节的保守表示逐次核算，普通事件限61440，终态/删除可用65536，保留收口空间。超过正文或元数据容量明确 TODO_STORAGE_LIMIT。

最多8次模型派发、20个 invocation、8个 attempt、256个可靠事件，终态/删除预留事件槽；summary≤8192、请求体≤65536、checkpoint≤131072、模型 canonical response≤65536、单响应≤4槽。各局部上限不能同时占满，先到总限额者失败。锁等待≤2秒且≤剩余整秒；最终提交再核真实 DB 时间。没有外部调用、隐式重试或新增清理线程。

现有维护器每次最多扫描32个过期投影。受理后7天、确定终态、所有历史写者静止、无 UNKNOWN/未结调用才清理。一个事务删除 task/step/invocation/usage 中所有正文副本，保留摘要/长度、身份、来源、计数、状态和旧账，写 TODO_PURGED/todo.content_deleted，再唯一释放 TODO_STORAGE。重复清理无重复事件或扣减；不确定状态继续持有。清理后的相同受理 key 返回410，不能重新执行。

只新增 V17，V1–V16 不改，旧行的六个 invocation 新列为 NULL。新准入、投影和 binding 单独版本化，不改变原 task/tool/model/checkpoint 字节。升级前停专有旧 worker；旧 V16 control/worker 的严格 schema gate 拒绝 V17。回退使用支持 V17、todo flag 关闭的制品，不做 down migration/clean。

## 本地定向验证

Windows 入口（JIMU_IT_JAVA 是现有 Linux Java21，JIMU_V16_JAR 是由准确旧 master 构建的制品）：

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp verify '-Dtest=TodoContractTest,PackageBoundariesTest,UserQuestionContractTest,McpContractTest,ModelInputBoundTest,ModelProtocolTest,ModelProfilesTest,WorkspaceToolContractTest' '-Dit.test=TodoIT'
```

TodoIT 在前台运行私有 MySQL、真实 control/双 worker 和显式 paid=false 模型，调用 `tools/todo_verify.py` 及 `tools/todo_migration_verify.py`。需要 Docker/WSL/ext4/Java21；缺必需资源失败。日志、协议、账本、原失败及清理记录写入 evidence 目录。禁止运行或等待 GitHub CI，不重跑旧 P2/P3 全矩阵。

已在准确旧 master 复现一个既有门禁问题：SourceIntegrityTest 仍记录 docs/adr/README.md 为3446字节，而 master 实际为3819字节。本批保留旧源与该原失败，不修改冻结摘要追认历史。定向命令明确列出本批必要测试，后端交接报告应一并列明此限制。
