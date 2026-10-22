# ADR-0011：授权会话事件正文与冻结读取

日期：2026-10-23；事项：XIAO-52 / G22 / T44。状态：accepted（本批实施约束；运行验收尚未执行）。

基线：Jimu `270a5d15611f5d7e59bbbd1d96c94d07567fc858`，tree `a64d3ac4593de748c50df15ebb53f71d4bdcc0c2`。上游固定 `571902028a75c64c1d767f4ae5fd099485f0ddf0`，工具入口 blob `61e18cc8f57826d672ba95b6e1fc2dd6d93e8274`；逐文件来源及 MIT 许可见 `../verification/XIAO-52/architecture/evidence/`。

当前 `event` 表和 `Tasks.Event` 是可靠状态通知，没有 Session 用户/模型事件正文；`step`、`invocation` 有恢复快照，但不是统一的公开事件序列。不能把 SSE 状态摘要称为上游完整事件，也不能向模型暴露内部 checkpoint、模型配置、凭据或任意数据库行。

本批决定：

1. 仅新增默认关闭的 `local-session-event-read-v1`，唯一工具 `jimu.session.event_read@0.1.0` / `session_event_read`；显式本地 `paid=false` 模型、180 秒、8 模型请求、20 工具调用。不混入其他 profile，不引入 TS/Cordis、SQLite、第二循环、服务或部署变更。
2. 一个新 task 是本批一个 Session、一个用户 turn；`session_id=taskId`。attempt、worker 接管、模型 step 和重连不创建 Session。首批模型可见集合严格为当前 task，显式其他 ID（包括同 owner 的其他 task）统一拒绝。owner 可经认证 API 读自己这些 task 的保留历史，包括终态；没有续轮、跨 task 授权或旧任务回填。
3. V19 新增任务绑定、不可变 `session_public_event` 和读取绑定；事件自 0 连续编号，与正整数 SSE seq 分属两个明确命名的空间。完整保存本批定义的用户消息、步骤起点、已接纳模型消息、读取收据和终态事件。每次实际消息/事实入库时同事务捕获公开字段及来源；不在读取时拼接可能已变化的状态。
4. 公开正文采用固定 `public-v1` 脱敏投影；目标返回该存储事件的全部字段和正文，禁止截断。它不是上游 SessionEvent wire 或模型原始协议：无 system/developer 隐藏内容、思维链、原始 stream、私有 meta、宿主配置、HTTP header/错误或文件正文。清楚标记投影版本和是否脱敏。相邻摘要是确定性文本前缀，显式标记摘要截断。
5. 每个新 step 在 M1 冻结之前已提交的日志上界和宿主上下文；checkpoint 仍用旧版本，绑定另存。工具只读该 step 上界内的事件；本 step 的模型响应、读取收据、同响应其他工具和之后并发追加不可见。上下文给真实 session ID、上界和最近 8 个事件引用；恢复使用原上下文和上界。
6. `session.read` 原事件是完整的读取收据，只含输入、上界、结果状态、摘要和字节数；结果正文只存原 invocation.output_snapshot，不嵌入日志，不跟随结果引用。因而读取历史收据不会展开之前读取的输出。此项与上游 `tool/result` 保存模型结果正文有明确差异。
7. 采用与 todo/目录相同的一库提交模式：原响应槽验证后，读取、每条结果授权/来源核验、冻结范围、原输出、PENDING→SUCCEEDED、一次工具消费和可靠事件同事务提交；安全确定失败也绑定原 code、只消费一次。没有外部效果，不先提交 RUNNING；禁止通用 admit/toolSucceeded/uncertain 旁路。提交不确定时由原槽恢复，不能另记失败覆盖已成功结果。
8. 事务复核 immutable lease 的 tenant/actor/task、当前 actor 权限、owner、安装 epoch/version/digest、binding、当前 fence/租约、取消、截止、终态和保留。成功重放先复核当前授权，再返回原字节，不再查目标；历史查询不授权重启终态任务。
9. 所有新存储副本纳入本 profile 独立 8 MiB/task、默认 64 MiB/tenant 的预留；正文 DB 7 MiB，元数据 64 KiB，留有收尾空间。128 原事件、16 KiB/事件、至多 8 相邻摘要、32 KiB 输出、2 秒查询、PK 范围最多 9 行。不因大 seq、稀疏或未知版本扫描整个日志。
10. 受理后 7 天是读取/保留上限；真正终态、所有历史写者静止、无 UNKNOWN 或未结占用后，原子清除日志、原输出、checkpoint、响应、goal/summary 等全部正文副本并保留墓碑/原摘要；唯一释放正文预留，留下的有界元数据继续计入本租户 history 存储占用，避免清理后无界积累。未知占用不由 TTL 释放。关闭 flag 阻止新准入和工具恢复，owner 的既有授权历史读取及清理仍可用。
11. V1–V18、所有旧 profile 及旧快照字节保持。V19 只扩展，既有行新列为 NULL；旧 control/worker 由严格 schema gate 拒绝 19。回退采用理解 V19 且关闭新准入的制品，不能降 schema 或删历史。

备选：复用 SSE 无法交付正文；临时展开 step/invocation 会把内部快照当公共协议并引入来源、清理和并发变化；新建通用 Session/搜索服务超出一个工具的需求。新增窄表和公开日志可保留一次提交的事实与有界读取，代价是 V19、明确的公开投影和本批 profile 专用保留路径。

唯一实施合同为 `../verification/XIAO-52/architecture/implementation-contract.md`、`contracts/` 与 `migration-contract.sql`。后端负责运行实现、真实 MySQL 升级/事务/恢复、必要回归；原审查、原 QA 和产品验收仍独立执行。本 ADR 不声称这些验证已完成。
