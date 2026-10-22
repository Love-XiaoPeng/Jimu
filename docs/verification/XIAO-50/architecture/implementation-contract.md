# XIAO-50 最小实施合同 v1

本合同以 ADR-0009、contracts/catalog.json 和其中固定摘要为唯一新契约。它是架构交接，不是实现、性能或产品验收报告。原后端随同一个实际 master 的实现 PR 入库；不修改冻结基线追认实现。

## 1. 装配与模块

默认 `jimu.todos.enabled=false`。启用仍要求当前 tenant/actor 对已安装 `todo_write` 的授权和 pin；manifest ID `jimu.todo.write`，installation 表继续使用现行 capability key `todo_write`。能力发现只在 flag、授权、固定 catalog、Schema 和本地 paid=false profile 全满足时新增此名字。只接受 `["todo_write"]`；混合列表、resourceIds 非空、images 字段、付费/BUSINESS/评测 scope 均拒绝。flag 关闭阻止新任务/执行/恢复，不删除历史，当前 owner 的历史读取仍按安装及授权检查。

采用现有 ModelProfiles.fixture()/localEvaluation() 的既有本机传输准入，不扩展 endpoint 或凭据方式。model.paid 必须是显式 false，不能以缺省值伪造此证据。非付费 profile 不接受 maxCostMicros，仍复用模型次数、token、租户并发和工具预算。HTTP POST /api/tasks 不新增身份/策略字段，Idempotency-Key 仍按原语义使用；同 key 重试先找原记录，不重复占 todo quota。已实际PURGED的todo task，POST同key在重新验证身份/原binding后返回410 TODO_CONTENT_EXPIRED，不解析tombstone成旧Request、不重新受理；GET历史tombstone仍可读，幂等key不会因清理而可复用。

新增 `plugins.builtin.todo.TodoWritePlugin`（只依赖 plugin.api），`TodoPort`（类型化 Item/Counts/Result），`TodoLedger`（下述用例），`adapters.runtime.todo` 的契约和绑定。Ledger 实现仍在 adapters.persistence；Controller 仅依赖 kernel API。TodoPlugin 的 port 来自宿主绑定的 invocation；不向插件暴露可任意选择 task/session 的入口。

最小用例签名（参数类型沿用现有公开 Lease/Call/Identity）：

```java
// Host adapter binds this to the immutable original checkpoint slot.
TodoPort.Result replaceOrResult(Lease lease, String stepId, Call call,
                               String pluginVersion, String outputSchemaDigest);
TodoView getTodos(Tasks.Identity identity, String taskId);
```

预期 revision 从数据库 invocation 取，不能作为模型输入；插件调用 port.replace(canonicalTodos) 时，runtime 必须证明它与持久 call.input 的规范化结果完全相同。wire 字节仍由 Payloads.json 排序，不能用 Python/Node 的序列化替代 JVM 权威结果。model ToolResponseMessage 使用这个 canonical JSON；上游额外的 render 文本是 `Updated todo list: N pending, N in progress, N completed.`，可作 owner 客户端呈现，不替换持久结果。Jimu 不实现 Cordis 对象或新的 TS 运行环境。

DurableAgent 新增 todoOnly 分支：不 prepareEvaluation、不调用 workspace、不走默认 summary artifact 发布；无检查点时只从原 user goal 启动；结束由现有 ledger.finish 提交有界 summary。definitions 显式解析 todo catalog，invoke 在通用工作区/未知效果 catch 前进入 todo 专用路径。不得把数据库连接不明的事务结果变成普通工具文本或再次调模型。旧 ask-only 的暂停/迟到回答逻辑保持原条件，todo 不进入 WAITING_USER/RESUME_READY。

## 2. 工具语义

权威 Schema 和 description.txt 附在 contracts/todo_write-0.1.0/。输入只有必需 todos 数组，每项只有必需 content:string 与 status:pending|in_progress|completed，所有层未知字段、重复 JSON key、额外 JSON 值、null/类型强转拒绝。JSON 最大深度8，先按原始 UTF-8 16384字节界限读取，再解析。拒绝不成对的 UTF-16 surrogate，防止 UTF-8 替换字符造成摘要/去重分歧。

content 使用 ECMAScript String.trim 的 WhiteSpace＋LineTerminator 集合，明确为 U+0009–000D、0020、00A0、1680、2000–200A、2028、2029、202F、205F、3000、FEFF；仅去首尾，不能直接用 Java trim()/strip()。去重按 trim 后原字符串精确相等，大小写、内部空格、组合字符形式保持。U+0085、180E、200B 不在 trim 集合；它们不是本工具新增的非法字符。canonical content 非空、UTF-8≤1024字节，Schema 原 content≤1024个 Unicode code point；canonical todos 数组的 Payloads.json UTF-8≤8192字节。超出任一界限拒绝，不截断/自动合并。

最多32项；零或一项 in_progress 合法，多项拒绝。todos=[] 是成功完整替换；未首次写入才是 null。保留原顺序，不能按状态排序或自动把 completed 改回 pending。counts 精确由规范清单计算，字段为 pending/inProgress/completed；工具结果不附 revision/source/state 包装。

正常一次新调用，即使内容相同或为空，也递增清单 revision 一次并消费工具次数一次；相同 invocation 的恢复不递增。completed 只是模型记录的步骤状态，task 成功由原 finish 前置独立判断；允许模型最终留下 pending，不将清单完结作为新业务验收 gate。

## 3. 持久结构、访问和事务

V17 结构见 migration-contract.sql：新增一张 task_todo_projection；其正文只引用最新 invocation.output_snapshot，保存单调 revision、固定 binding、最新来源、计数、摘要及清理事实；旧 invocation 增加六个 nullable todo 元数据列（expected/applied revision、实际提交 attempt/fence、更新事件 seq/时间），每次历史调用各自保留提交溯源，不能只在可被覆盖的最新指针上保存。tenant 增加独立 todo 存储预算。主键按 tenant/task 快照读写；cleanup 索引仅服务有界过期扫描；无全局内容搜索、队列或条目表。

受理事务沿用 evaluation parent（如有）→tenant_budget→task 的现有顺序；todo profile 不能有 evaluation parent。新增投影 revision0、UNWRITTEN、binding 与摘要、retain_until=DB受理时间+604800秒，以及 `<task>:todo-storage` 的 TODO_STORAGE/2097152/RESERVED。tenant.todo_storage_held 同事务增加，超额429；普通 storage_held 不参与此独立额度。旧 task 不补投影、不做内容迁移。

M2 的 modelResponded 在原完整 response/usage/原 invocation 创建事务中，为每个 todo 槽冻结 expected revision：设当前投影为 r，按响应工具槽顺序第 k 个 todo（k从0开始）期望 r+k。必须限制累计 invocation≤task.max_tools 且≤20，单响应≤4；不能先执行任一工具。原字符串的Unicode有效性必须在UTF-8摘要/字节数计算前验证，不能用替换字符生成input_digest；不合格响应保留原dispatch/usage与安全失败摘要，不创建可执行槽。重放已持久 M2 不重建调用、不更新 expected revision。单一模型 step/manager 的原槽顺序为唯一串行顺序。

replaceOrResult 是一个短 leaseTx，顺序如下：

1. 按现行锁顺序锁 tenant 和 task，复核当前 lease/attempt/worker/fence/DB租约、owner actor enabled/权限、tool capability、取消、绝对 deadline、显式本地 paid=false/model pin、安装 enabled/epoch/version/digest、binding版本/摘要/catalog。读取和恢复不绕过现行授权。
2. 以 tenant/task/step/slot 取持久 invocation，核对 providerId、tool、原 input_snapshot SHA、plugin pin 与 step 属于同一 task 且为已完整持久模型响应。输入从持久 snapshot 解析；方法的 call/插件规范结果只可与其相等，不能用调用者参数签发来源。验证新元数据版本及范围。
3. 若 invocation=SUCCEEDED：校验其 output SHA/Schema、applied=expected+1 和该调用原来源事实，然后返回原结果。此分支先于 current projection revision CAS；即使后续调用已把投影推进到 r+2，也不能把旧结果写回，也不能要求当前指针仍指它。不得创建额外 reservation/event、消费预算或重写 applied revision。当前终态、撤权/取消/截止、旧 fence 仍先拒绝，不把终态任务唤活。
4. 新执行仅接受 PENDING，且 applied revision NULL；锁 projection 并比较 revision=expected，否则409 TODO_REVISION_CONFLICT。校验 storage/input/output、工具预算及计划条数/字节/并行规则。只读预计算失败不改变清单；若任务随后按现有 stop 失败，该 task.failed 是真实独立事实。
5. 同事务：消费 task.tools_used/tenant.tool_used 一次，写 TOOL reservation（SETTLED/actual1/KNOWN）；invocation PENDING→SUCCEEDED，写 canonical output+SHA+applied revision和实际提交attempt/fence/事件seq/时间；projection以 `WHERE revision=expected` CAS到 revision+1，指向本 invocation/step，记录同一来源、counts、output digest；按既有 event helper 分配 tool.dispatched、todo.updated、tool.completed。fact_id 分别 `intent:<id>`、`todo-write:<id>`、`result:<id>`，UNIQUE fact 兜底；projection.updated_seq与invocation.todo_event_seq均记录 todo.updated 的 seq。
6. 最终task条件更新再核 DB 当前 deadline/lease/fence/cancel，影响1行，否则全部回滚；投影CAS也必须影响1行。输出所有权只在 COMMIT 后返回。MySQL 锁等待最多2秒且不超过剩余操作/任务时间；专有连接设置须恢复，SQL/事务超时非零退出，不能 Future.cancel 后假定回滚成功。整个工具有效期限=min(task剩余,30000ms)。

generic admit()/toolSucceeded()/uncertain 的 todo 旁路必须拒绝 TODO_COMMIT_REQUIRED；不能把 todo 留成已提交 RUNNING。现有 claim 对一般 RUNNING invocation 的 UNKNOWN 规则无需放宽。数据库 COMMIT 响应丢失时，不新造 invocation、不调用通用 uncertain；退出本 attempt，使用相同稳定槽由下一合法 attempt 读取。未提交时仍PENDING，已提交时SUCCEEDED，恢复均按同一事务路径裁决。DB不可用503；绝不以异常推定“未发生”。

现有 WorkerLoop 会对未捕获 RuntimeException 调 ledger.stop，所以“退出 attempt”必须具体实现：仅在 todo 账本事务抛 DataAccessException/TransactionException、结果不能确认时，由 todo runtime 转成内部专用 `TodoCommitUnconfirmed`；DurableAgent.run 最外层仅捕获这一类型并正常返回，不发普通工具回复、不调用 stop/uncertain，不继续模型。finally 仍由原 WorkerLoop 排空 operations→quiescent→停止续租；DB恢复、原租约失效后由原 claim 以新 fence 领取同task，原槽裁决。此标记不新增持久状态，不等于 task成功，不携带正文或底层SQL到模型/日志。已知输入/权限/预算拒绝不走此路径。若清算/排空不能确认，保留原占用/未知事实，不能假设安全重启。

## 4. 用户读取与重连

GET `/api/tasks/{taskId}/todos` 无 body/query，沿用 Authorization bearer 和现有 TaskFailure `{code,message,traceId}`，响应 `Cache-Control:no-store`。先复核身份，再按 tenant/owner 查 task；错误 UUID、其他tenant/owner/task 均404 NOT_FOUND。当前 actor disabled/撤权、安装 epoch/version/pin 不符返回既有403/409安全码；数据库故障503 LEDGER_UNAVAILABLE；正文/摘要损坏409 SNAPSHOT_CORRUPT。正文不进入日志或错误体。

响应严格按 contracts/todo-view-v1.schema.json：version1、taskId、asOfSeq、revision、state、todos、counts、source、retainUntil。state 为 ABSENT/UNWRITTEN/PRESENT/PURGED：

| 情形 | revision / todos / counts / source / retainUntil |
| --- | --- |
| 合法可见的无 todo task | 0 / null / null / null / null；200 ABSENT，不要求安装 todo，不建行、不占预算 |
| 授权 todo task 尚未写 | 0 / null / null / null / 受理+7天；200 UNWRITTEN |
| 写入空清单 | ≥1 / [] / 三计数0 / 完整source / 固定保留时间；200 PRESENT |
| 正常清单 | ≥1 / 完整最新数组 / 准确counts / 完整source / 固定保留时间；200 PRESENT |
| 实际清理后 | 保留revision / null / 最后counts（原未写则null）/ 原source（原未写则null）/ 原保留时间；200 PURGED |

source 区分原 invocation 创建 attempt 和实际提交 attempt，含 invocationId、checkpointId=step.id、providerCallId、createdAttemptId、committedAttemptId、fence、eventId=`taskId:todo.updated.seq`、outputSha256、updatedAt。不能将 invocation.attempt_id 误称恢复提交者。读取必须核对最新指针与 invocation.todo_applied_revision、output digest/规范清单/counts 一致，不接受任意 source id。

GET 的 task.next_event_seq、投影和来源正文必须用一个 JOIN SELECT 在同一快照读取；不得以现有 READ_COMMITTED helper 中多次SELECT冒充一致快照。先按现行 identity/epoch 的 FOR SHARE 规则锁定授权，随后单条投影查询返回全部快照字段；asOfSeq不能从另一个时刻拼接。读取、重复GET及空闲SSE轮询不改 revision/event/调用计数。GET复核当前取消/截止/预算事实但不做执行准入：取消、截止、额度耗尽和正常终态不抹除历史读取权；写入和恢复仍拒绝这些阻断条件，安装或actor撤权则GET也拒绝。所有新写入沿用当前 actor_access/installation 的 FOR SHARE，在提交前持续持有，避免撤权与提交错序。

现有 SSE `/api/tasks/{id}/events`、envelope eventVersion1、`taskId:seq` 和每actor/task2订阅、批64条保持。新 type 为 todo.updated、todo.content_deleted，code=null、artifactIds=[]，不携带正文、不新增 event payload 协议。客户端：GET快照S→订阅 Last-Event-ID=`taskId:S.asOfSeq`→遇上述通知/终态后GET；只采用较新revision，PRESENT→PURGED按水位前进；重连总是先GET。GET与subscribe间发生的更新由原DB回放补齐；相同失效通知可合并重读。游标410 CURSOR_EXPIRED 时GET最新，再从最新asOfSeq接续；未来游标400、其他task游标400。终态旧快照可读，不新开模型或恢复旧工具。

沿用现有 task GET 判断终态；终态后再GET一次todos即收敛，无需继续SSE。现有 events 在所有事件均早于7天时会对任何游标返回410；此时终态客户端直接采用鉴权快照，不循环重连。快照读取保留7天与SSE游标保留是不同边界，不能把410当作清单不存在或重新执行授权。

## 5. 有界政策与清理

这些是新 profile v1 的固定上限，模型和客户端只能沿原预算收紧，不能放宽；改变限额语义需新 binding/profile/catalog，不能原地改 snapshot：

| 项 | 上限 |
| --- | --- |
| todo条数 / canonical单条UTF-8 / 数组JSON | 32 / 1024字节 / 8192字节 |
| 原工具输入 / 工具输出 / JSON深度 | 16384 / 16384字节 / 8 |
| 模型请求含Schema / checkpoint / canonical response | 65536 / 131072 / 65536字节 |
| 单模型HTTP responseBytes / 单响应调用槽 | 65536字节 / 4 |
| 任务deadline / 模型派发总数 / 工具调用总数 | 原上限180秒 / 8（含恢复重派）/ 20 |
| task累计invocation / attempts / 可靠event | 20 / 8 / 256（保留终态与清理2槽） |
| summary / 保留元数据预留 / 单task总正文与元数据 | 8192 / 65536 / 2097152字节 |
| 同租户清单独立预留额度 / 内容保留 / cleanup批 | 16777216字节 / 受理后604800秒 / 32 task |
| 单工具deadline / DB lock wait | min(任务剩余,30000ms) / ≤2秒且≤剩余时间 |

当前输入上下文token/租户模型和工具配额仍取现有更小值。若 profile模型的 maxOutputTokens>4096、responseBytes>65536 或输入限制不受65536字节约束，todo准入拒绝，不能悄悄重写旧 model snapshot。没有隐式重试、后台清理线程的新生命周期或外部服务。

新增失败统一使用现有安全错误体：flag/本地profile不符403 TODO_NOT_AUTHORIZED（执行中flag关闭403 TODO_FEATURE_DISABLED）；输入/trim/重复/并行/字段/UTF-8界限400 TOOL_INPUT_INVALID；不支持binding版本409 TODO_BINDING_UNSUPPORTED；来源/槽不符409 INVOCATION_MISMATCH；stale revision409 TODO_REVISION_CONFLICT；旁路409 TODO_COMMIT_REQUIRED；总正文/独立预留额度429 TODO_STORAGE_LIMIT；attempt/event cap429 TODO_EXECUTION_LIMIT；新catalog缺失/不完整503 TODO_NOT_CONFIGURED。现有鉴权/安装/fence/取消/deadline/model/tool预算码继续原样使用。所有拒绝不写todo.updated，不静默修复。工具输出超限/不合法是宿主完整性失败409 TOOL_OUTPUT_TOO_LARGE/SNAPSHOT_CORRUPT，不能返回伪成功。

所有新写入口在同一task锁内做精确UTF-8 byte计数及预留检查；计入 task.request_snapshot/goal/summary/model_snapshot/tools_snapshot、binding、所有step.request/response（含重复requestBody与消息）、所有invocation.input/output、model_dispatch.usage_snapshot、retained_digests；metadata固定上界也须计入。完整范围由后端列到验证报告，不能只计最新数组。复制的相同正文每份都计。新正文写入后总量+65536元数据预留≤2MiB；terminal/清理保留项受预留硬上限约束。各局部最大值不保证可同时占满，先到总预算者明确失败 TODO_STORAGE_LIMIT。模型响应不合格仍保留原有真实 dispatch/usage/摘要/失败事实，不制造可执行槽。

到期只由现有 LedgerMaintenance 的有界扫描处理本表，未到期/无表行立即跳过；无todo task 和其他tenant不做内容检查。锁 tenant→task，复核终态 SUCCEEDED/FAILED/CANCELLED、writersQuiescent 包含所有历史attempt、无OUTCOME_UNKNOWN、无HELD_UNKNOWN及未结真实调用占用。NEEDS_RECONCILIATION 继续保留且不释放未知额度。

一个清理事务内：将该todo task 的全部正文副本（包括goal、summary、request、step、invocation和可能回显正文的usage原始片段）替换为明确版本tombstone/NULL；保留原SHA/字节数、身份/时间/状态/调用预算/UNKNOWN事实和source/counts；step.request_cleanup_state 使用新的 TODO_PURGED 标记，恢复在解析旧checkpoint前拒绝。保留元数据不含正文。投影state置PURGED，写一次 `todo-purged`/todo.content_deleted，再将TODO_STORAGE reservation唯一CAS为RELEASED，并扣独立todo_storage_held。任何失败回滚，不先扣占用；重复清理无额外event/释放。清理后GET返回tombstone，不把null当未写。

## 6. 迁移、验证与交接完成条件

实际迁移新增 V17，原脚本字节和checksum不动。保留新表协议v1，task/step/invocation旧record_version和原快照版本不变；新元数据NULL不参与旧序列化。control迁移前收集/停止专有旧worker，不在187持久服务操作；旧V16 control/worker在V17库必须通过原严格schema gate真实退出。回退用V17制品关闭todo新准入；不跑down migration/clean。

后端先核实际master/工作树，保留本附件来源摘要，将ADR/合同/manifest/Schema/catalog/许可证入库，尽早开关联master草稿PR；首个可构建切片是“受理todo-only→唯一循环一次write→owner GET”。产品经理接续派工后，两小时检查点交可构建切片/PR进展或真实阻塞。

必要差异验收：

1. 原始上游shape及本批边界：空/单/多项、保序/替换、每种状态、零/一/多active、trim含NBSP/FEFF、大小写/组合字符精确去重、重复内容/未知字段/重复key/无效Unicode/32与33/UTF-8字节/JSON各界限，无静默截断。
2. 私有MySQL＋真实control/双worker＋同一JAR＋显式本地paid=false模型，经唯一循环记录→替换/完成步骤→owner读→重启worker与control/重连读最新→task.finish；summary不产生新文件、无后台命令/外部通知。记录SHA/tree/JAR SHA256/命令退出码/协议/账本。
3. 屏障注入：M2后未写、提交前回滚、COMMIT成功但返回前崩溃、响应丢失恢复、旧已成功槽在后续revision已提交后恢复、同invocation双worker、两个不同新invocation同内容、同响应多个todo槽、旧revision/旧fence。断言每次原子投影/结果/event/TOOL reservation，重放无重复tool_used/费用，真实新调用各有事实。
4. actor禁用、安装禁用/epoch/版本/pin、跨租户/owner/task/伪造step/slot/input/source、取消/截止与提交竞争、终态恢复拒绝；无todo owner读ABSENT且原路径可继续。撤权保留真实历史，不能绕过鉴权读取。
5. GET与SSE接续间插入更新、Last-Event-ID回放、断连/25秒重连、410旧游标、未来/跨task游标、读取一致水位、空闲轮询不制造事件、终态最终快照可读。正文只按数据渲染。
6. 各额度真实拒绝、attempt/event cap、锁竞争时间、租户todo额度满仍可走无todo任务/另一tenant、到期/未到期/活动旧写者/UNKNOWN不清理、所有正文副本实际清理后才释放，重复清理幂等。注入Clock/DB时间屏障，不用长sleep冒充证据。
7. 真V16旧二进制先造：已终态问答、WAITING_USER问答、RESPONDED且旧成功工具的未完任务、旧QUEUED普通任务；升级V17前后原task/tool/model/checkpoint/invocation字节及checksum相同，新增binding不授予旧task；旧问答可按旧profile恢复。旧binary拒绝新schema，todo未知binding/元数据版本拒绝。只做旧问答、MCP、工作区受影响定向回归，不重跑旧全矩阵。

本架构回合只验证可检查的契约、Unicode和来源。并发/原子性/真实崩溃/授权/迁移/cleanup均是后端与原审查、QA必须提供的运行证据；没有以探针模型声称这些产品保证已通过。不运行/启用/重跑/等待GitHub CI。
