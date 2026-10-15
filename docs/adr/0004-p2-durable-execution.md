# ADR-0004：P2 持久执行、恢复与本机资源

日期：2026-10-15。状态：accepted（架构实施决定；P2 运行验收待实现）。事项：XIAO-36，承接 XIAO-35。基线：master `5439b517de6c8755810168a928b63a5bfd5d1948`、总体方案 0.3 §7/8/10、工程规范 0.2 和 ADR-0001–0003。

## 决定和依据

沿用单 Maven 制品、JDK21 / Boot4.1.1 / Spring AI2.0.1、现有包边界和唯一 ToolCallingAdvisor 循环。新增 MySQL 8.0/InnoDB 持久账本、WSL ext4 上的本机产物存储适配器；控制角色及两个 Java worker 为三个独立进程，共用**本次测试专有**数据库和存储根。无 broker、Redis、Graph、向量库或第二条编排链。

选择 MySQL 是因为本机已有可固定摘要的镜像，InnoDB 的短事务、行锁、SKIP LOCKED、唯一键和条件更新满足当前两 worker 实验。专用新容器不连接现存 MySQL，降低额外下载与设施成本。PostgreSQL 也能满足这些要求，当前没有为切换数据库增加资源与验证成本的需求。内存库或 SQLite 不能替代这里选定的跨进程领取/锁竞争证据。

本机存储适合本次所有进程位于同一 WSL 的规模；不提供跨主机共享、对象存储兼容或生产隔离承诺。未来跨主机才替换 `kernel.artifact.port` 的存储实现并重验发布/核对协议。协议不依赖文件绝对路径。

框架扩展点、实际资源结果、限制和后端命令草案见 [交接与证据](../verification/XIAO-36/architecture-handoff.md)。这次没有实现任务 API、数据库迁移或恢复内核；实现由后端接续。

## 1. 事实、标识与状态

所有可见性、唯一约束和子记录外键包含 `tenant_id`。task 归属 `owner_actor_id`，UUID 只作标识。时间保存 UTC、数据库比较租约时间；耗时使用单调时钟。持久字段带 `record_version`，模型/插件/政策快照带各自版本与摘要，不能序列化 Spring AI 对象或 Java 类名作为跨版本记录。

| 记录 | 最小事实与约束 | 状态和恢复规则 |
| --- | --- | --- |
| task | tenant/owner/session、request_digest、idempotency_key、deadline、policy_version、state/revision、current_attempt_id、fence、lease_until、cancel_requested_at、next_event_seq、ready_at | 沿基线 QUEUED/RUNNING/CANCEL_REQUESTED/SUCCEEDED/FAILED/CANCELLED/NEEDS_RECONCILIATION；最后一项暂停自动执行，须核对后 CAS 收敛 |
| task_attempt | task、worker_instance_id、fence、开始/租约/结束事实、配置快照引用；`UNIQUE(tenant,task,fence)` | ACTIVE/EXPIRED/STOPPED/SUCCEEDED/FAILED；EXPIRED 仅说明所有权失效，不证明旧进程已停止或远端无效果 |
| step | 创建它的 attempt_id、ordinal、稳定 step_id、kind、request_snapshot/digest、模型配置/消息/能力快照、完整 response_snapshot、当前 dispatch_id | MODEL 步骤 PREPARED→RUNNING→RESPONDED→COMPLETED，或 FAILED；只有完整响应落库后才允许生成 invocation；delta 不改变 RESPONDED |
| invocation | 稳定 invocation_id、step_id、创建 attempt_id/ordinal、tool_call_slot、effect_intent_id、输入摘要、plugin/version/digest、installation_epoch、规范输出及摘要、投影版本 | PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED/OUTCOME_UNKNOWN；成功和实际效果证据不可由旧回调覆盖；UNKNOWN 不可自动再执行 |
| event | tenant/task/seq、event_id、fact_id、基线 envelope v1、安全 payload | 与产生它的状态变更同事务；`UNIQUE(tenant,task,seq)`、`UNIQUE(tenant,task,fact_id)` |
| artifact | artifact_id、task/producer_invocation、upload_id、不可变 snapshot_key、hash/size/type/version、保留期、生产 fence | STAGING→VERIFIED→PUBLISHED；未发布可转 ABANDONED，保留期到期 EXPIRED；只有 PUBLISHED 出现在交付列表 |
| installation | tenant/plugin/version/digest、grants、status、revision/epoch | 与随制品插件目录分开；禁用/撤销递增 epoch，版本回滚也不能回退 epoch |
| reservation | tenant/task、可选 step/invocation、dispatch_id、预算桶/资源类别、预留量、实际/估计量、usage_quality、结算事实 | RESERVED→SETTLED/RELEASED，效果或费用不明确则 HELD_UNKNOWN；CAS/唯一结算键使回收幂等 |

保留基线 `UNIQUE(tenant,attempt_id,ordinal)`，但该 attempt 是**记录的创建尝试**。恢复创建新 attempt，不复制已存在的 step/invocation，不用新 attempt_id 计算逻辑副作用 ID。当前提交者由 task.current_attempt_id/fence 判定；原始记录的创建者是历史溯源。必要的重试派发用新 dispatch_id 和 reservation 记录，不新造一个逻辑工具调用。

`UNIQUE(tenant,step_id,tool_call_slot)` 保证同一完整模型响应的每个调用槽只有一个 invocation。slot 取持久响应中的顺序及原始 provider callId；provider callId 只作协议关联，不能单独充当幂等或授权键。`effect_intent_id` 在首次意图事务分配并永久复用，绑定 tenant/task/step/slot、动作/对象、参数摘要和固定插件版本。任何内容不同都不能沿用该效果 ID。

主要访问索引：task `(tenant,state,ready_at,id)` 领取和 `(tenant,state,lease_until,id)` 回收；task `(tenant,owner,created_at,id)` 查询；幂等唯一 `(tenant,owner,idempotency_key)`；step/invocation 按 tenant/task 及上述槽键恢复；invocation `(tenant,state,updated_at)` 核对；event `(tenant,task,seq)` 回放；artifact `(tenant,state,updated_at)` 清理和 `(tenant,task,id)` 授权列表；reservation `(tenant,status,task_id)` 回收。宿主按授权租户范围调度/扫描，不让租户请求绕过 tenant。只按这些实际访问模式建索引，不预建全量查询平台。

## 2. 短事务与领取

受理事务：认证/参数验证后，锁租户配额桶，先查 tenant/actor/key 的已有记录并比较摘要；相同请求返回已有任务，不重复占配额，即使队列已满也不能变成429。仅新请求检查队列上限，写 task+幂等摘要+队列预留+受理 event，COMMIT 后才返回 202。并发同 key 仍以唯一键兜底裁决；冲突后读已提交行比较规范摘要，不能先返回内存 task。摘要覆盖规范化业务请求和客户端请求上限；服务端政策另存快照。禁止用原始 JSON 键序判断不同请求。

worker 在独立短事务中使用 READ COMMITTED（领取连接显式设置）：

1. 固定顺序锁租户预算/并发桶，再 `SELECT ... FOR UPDATE SKIP LOCKED` 选择本租户到期可运行行；所有受理/领取/取消/结算路径遵循相同锁顺序，批次有界。
2. 校验 deadline、取消意图、待核对状态、现行授权和并发配额；task.fence 原子加一，创建 attempt，写 worker_instance_id/lease_until、RUNNING 及 event；队列/运行预留同事务转移。
3. 恢复只转移同一 task 的运行预留，不重复扣任务并发；逐次外部派发另有预算预留。过期但在途写效果不明的任务只进入核对，不靠“租约过期”放行同一写。
4. 提交后才能在事务外调用模型/工具。续租每 10 秒、初始租约 30 秒；续租只允许 `fence=expected AND worker=expected AND lease_until>DB_NOW`，不能使已经失效的租约复活。

worker 提交任何 step、调用结果、可靠事件、终态或产物发布时，必须在**同一事务**锁 task 并核对 `tenant/current_attempt/fence/lease_until/state/revision`。如涉及预算，先按统一顺序锁预算桶，再锁 task；纯续租只锁 task，不能随后反向请求预算锁。`UPDATE task ... WHERE ...` 影响行数必须为 1，否则整个事务回滚。子表先更新、随后在另一事务检查 token 是错误实现。无需把同一 fence 重复加在每个子表 UPDATE，只要 task 锁与验证覆盖整次原子提交且没有旁路写入口。

逻辑骨架（不是可复制迁移脚本）：

```sql
START TRANSACTION;
-- 若涉及预算：先以统一顺序锁定预算桶，再锁 task
SELECT state, revision, fence, lease_until FROM task
 WHERE tenant_id=? AND id=? FOR UPDATE;
-- 验证当前 attempt、fence、DB 时间、允许的状态和预期 revision
-- 写子事实；在 task 锁内分配 next_event_seq，写同库可靠 event
UPDATE task SET revision=revision+1 WHERE tenant_id=? AND id=?
 AND revision=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6);
-- 必须影响 1 行；否则 ROLLBACK 全部事实
COMMIT;
```

MySQL 用 `CURRENT_TIMESTAMP(6)`/DATETIME(6)，连接时区固定 UTC；严格 `lease_until > now`，等于即过期。不用 worker 本机时间判定所有权。可通过测试专有管理入口使租约到期并用屏障控制竞争，不靠几十秒 sleep 或修改全机时钟。

旧回调不直接提交领域结果。只能向单独、限额、脱敏的 observation 入口提交不可变诊断；核对协调者以当前 token 核验远端/对象证据，再写权威事实。fencing 只保护本库写入，不能撤销不支持 fencing 的远端操作。

## 3. 单循环的持久边界与恢复

Spring AI 类型只存在 `adapters.ai.springai`。`execution` 定义步骤协调端口；task/dispatch/invocation/budget 的公开用例承载账本语义。唯一 ToolCallingAdvisor 可有窄子类/内层边界 advisor，但不再加另一个工具循环或重试 Agent。ChatModel 的内部工具执行显式关闭，callback 解析回退关闭，只有宿主按现行权限提供的回调集合可见。

| 边界 | 正常路径 | 重启后的唯一依据 |
| --- | --- | --- |
| M0 请求准备 | 逐轮 before hook 通过公开用例原子保存规范消息、模型/选项、能力快照、step 和预算预留，再发 HTTP | 无 PREPARED 不发请求；PREPARED 未派发可继续当前步骤 |
| M1 请求已发、完整响应未存 | RUNNING+dispatch_id；网络等待不占 DB 事务 | 先前费用记 unknown/保留上界；只有尚无已准入工具时，可在剩余预算内为同一步新建派发，旧派发保留不确定成本；不可把请求失败当零费用 |
| M2 完整模型响应已存 | after hook 在执行任何工具前保存响应、usage 和固定工具槽；RESPONDED | 从当前步骤请求检查点返回**该已存响应**，无需再次请求模型；不从任务初始 prompt 重跑 |
| T0 工具意图 | manager 为每个槽分配/读取 invocation；网关复验身份/epoch/版本/Schema/fence，短事务记录意图、effect ID 和预留，才放行插件 | PENDING 且确定未派发可执行；RUNNING 需按效果分类核对；不能因没有结果行推断未执行 |
| T1 工具成功事实 | 输出经权威 Schema/大小/摘要校验，结果、usage、事件同事务提交，之后才把模型投影交给循环 | SUCCEEDED+输入/插件/输出摘要+完整 canonical 结果是成功证据；恢复返回存储结果并按同一投影版本构造 ToolResponseMessage，插件执行计数不增加 |
| M3 下一轮/结束 | 保存 assistant tool_calls 与对应结果消息形成的规范检查点，进入下个模型 step；最终响应和产物前置满足后提交终态 | M3 未存时由 M2 与逐槽调用事实重建同样历史；M3 已存则直接从下个步骤继续 |

内层模型边界 advisor 可在当前 step 已 RESPONDED 时返回恢复的 ChatClientResponse，以使唯一 ToolCallingAdvisor 消费已存响应；新的完整历史从当前检查点恢复。`ToolCallingManager` 包装器按槽绑定 callback 和稳定 invocation，默认 manager 只负责框架消息/工具调用转换。多工具响应逐槽串行准入；恢复已成功槽只取记录，未确认槽停止或核对，不能重新执行整批。

未知效果、租约失效、取消/预算阻断必须用宿主停止信号退出运行链，由执行用例提交对应状态；不能交给默认 ToolExecutionExceptionProcessor 转成普通文本让模型“继续试”。框架工具次数限制只作附加保护，内核持久预算才跨重启生效；框架默认 40/150 不能覆盖任务 8/20 限额。模型/HTTP SDK 自动重试关闭或纳入同一宿主 dispatch 预留和最多一次只读重试政策。

**禁止重放边界**：SUCCEEDED 永不调用插件；OUTCOME_UNKNOWN 永不自动重试；write/present 不能凭 retryable 再执行；不完整流不是完成响应；ChatMemory、日志、客户端收到响应或临时文件存在都不是成功证据。若成功输出损坏/缺失，停止并标记待核对/完整性错误，不能重新调用工具掩盖损坏。

P2 的受控文件/假服务效果也经网关：支持 effect ID 的假服务通过只读查询核对状态；本机受控写用 operationId、观察版本及不可变快照证据核对。对“不支持幂等且响应丢失”的假服务，仅记录 OUTCOME_UNKNOWN、EFFECT_OUTCOME_UNKNOWN 和 task.NEEDS_RECONCILIATION，禁止二次派发。真实付费、外部发送、shell/浏览器不启用。

## 4. 取消、完成和资源收敛

cancel 与成功提交均锁同一 task、CAS revision。成功先提交则 cancel 返回原终态；cancel 意图先提交则 RUNNING→CANCEL_REQUESTED，此后所有新模型/工具/发布准入拒绝，迟到成功结果可由当前持有者登记真实效果，但不能让 task 成功。QUEUED 且无在途调用可在取消事务直接 CANCELLED，回收队列预留并写事件。

RUNNING 取消返回 202 只表示取消意图已持久化。协作宽限 3 秒；可信 JVM 插件不能停止则 supervisor 回收整个专有 worker，并确认 PID/实例已退出。先确认执行停止及全部效果，再 CANCELLED/FAILED 并结算；效果不明优先 NEEDS_RECONCILIATION。过期租约、Future.cancel、线程 interrupt、连接断开均不能证明停止。控制重启后由持久取消意图继续回收。

task 的最终 CAS 同时核对“无未决 invocation、需要的 artifact 已发布、未先受理 cancel”；P2 的 fixture 产物成功不等同 P3 的网页报告质量验收。取消之后才上传完的对象保留为可核对未发布对象，不发 artifact.created。任何终态都不能被迟到 event、重复回调或 SSE 顺序改写。

## 5. 对外和内部契约差异

P1 只有 `/readyz`，以下是 P2 首次新增，无已上线任务调用方；保留基线 wire 状态/安全 code 与 web.fetch 唯一输出 Schema。后端提交 DTO/测试时以本节作为差异依据，不另造旧 runId/toolRunId。

认证采用本机测试专用签名 bearer token 和服务端 principal 映射：验证签名、issuer/audience/expiry，将受信 subject 映射为 tenant/actor/grants；两个租户和凭据分别生成，密钥只在测试配置。请求 body/header/model 参数的 tenant/actor 不建身份，未知身份字段以 400 拒绝。生产身份接入不在本次 fixture 验收范围。worker 是专用注册实例，instance_id 由宿主生成，不能来自工具参数；测试假服务 callback 只关联已登记 dispatch，调用号不是认证凭证。

| 接口 | 请求、成功响应 | 失败/约束 |
| --- | --- | --- |
| POST `/api/tasks` | 必需 Idempotency-Key（1–128 ASCII 可见字符）；body `{goal, capabilityIds?, resourceIds?, budget?}`，未知字段拒绝；goal 1–8192 UTF-8 字节；资源 ID 为授权句柄，预算只能收紧。202 `{taskId,state,revision,createdAt}`，Location 指向查询 | 相同 tenant/actor/key/摘要返回原 task 当前快照；不同摘要409；参数400、未认证401、现行能力拒绝403、配额429；数据库未提交/不可用503，不返回假202 |
| GET `/api/tasks/{id}` | `{taskId,state,revision,createdAt,updatedAt,cancelRequestedAt?,code?,summary?,artifacts[]}`；产物仅公开 id/type/size/hash/expiry，不返回路径或秘密 | 同 tenant 且 owner/明确授予者；所有不可见 task 统一404；错误体 `{code,message,traceId}` 不含 cause/SQL/参数 |
| POST `/api/tasks/{id}/cancel` | 无 body；202 为持久意图+当前快照；已终态200原快照；重复请求不增加取消事件/重复释放 | 不可见404；取消不回滚已经发生的效果；CAS 由用例内部处理，不让客户端提交 fence |
| GET `/api/tasks/{id}/events` | 基线 envelope v1，`text/event-stream`；可靠 id 为 `taskId:seq`，与 envelope.eventId 相同；Last-Event-ID 恢复 | 先鉴权再解析归属；无游标从首事件开始，超过保留范围410并给当前任务快照入口；未来seq400；不触发执行 |
| GET `/api/artifacts/{id}/download` | 授权代理流，Content-Type/长度/摘要/安全文件名；仅 PUBLISHED、保留有效、关联任务可见 | 不可见404；所有权内已过期410；每次请求重验身份和撤权。采用代理以满足即时撤权，不发可绕过鉴权的长期URL |
| 内部调用网关 | `admit(HostInvocationContext, PinnedTool, InputRef)` / `complete(dispatchId, CanonicalResult)` / `reconcile(evidence)`；纯稳定类型，不暴露 AI/DB 类型 | 上下文由 task/identity 用例构造，含 tenant/actor/task/attempt/fence/deadline/epoch/digest；插件不能自签；结果提交和发布都执行当前权威校验 |

budget 请求可选字段为 `deadlineSeconds/maxModelRequests/maxToolInvocations/maxCostMicros`，均正整数，不超过服务端政策；金额以固定 price_version/currency 的整数最小单位比较，不能用浮点。未配置货币额度时仍强制请求数/token/并发/字节配额，费用质量照实记 unknown/estimate，不能虚构价格或0成本。默认 deadline180秒、模型8次、工具20次，policy_version 固定；字段上限和边界测试由 DTO/Schema 一起版本化。

能力发现只返回当前 tenant/actor 已启用、授权、固定版本且 Schema 有效的工具。所有 query/history/event/artifact/连接凭据经相同权限用例。缓存键至少包含 tenant/actor、权限版本、installation epoch、插件/模型配置版本、资源/credential_ref 的身份及版本；禁止仅按 endpoint、conversationId 或 URL 共享。P2 不启用 MCP/跨任务 memory；当前任务检查点、发现和连接缓存必须隔离，不能用未实现的 MCP 链路冒称通过。

安装禁用/撤权在 DB 事务递增 epoch。发现缓存可以失效，但真实准入和 callback 必须读取当前 epoch 并拒绝旧能力对象；结果恢复也先验证当前访问权限。撤权之前已发生的效果仍保留受限事实，不自动对外展示或再发布；旧 worker 不能凭旧快照继续执行。审计不含秘密实值。

## 6. 预算和可靠事件

预算按任务、租户和安装的有效上限取最小值。一次准入短事务按固定顺序锁预算桶，验证 `committed + reserved + new <= limit`，同时写稳定 reservation 和调用意图；唯一 reservation/结算键防止重复预留/退款。队列、任务运行槽、模型次数/token/金额、工具次数、存储字节各有类别，不能用一个计数器概括全部。

完成时在同事务把预留转换为实际 usage，退还确定未使用部分；usage 缺失记 UNKNOWN 或有依据的 ESTIMATE，保留最坏上界/估计费用占用直至对账，不假记0。调用次数只要已派发就消费；取消只释放确定未派发或停止后可确认未使用资源，不退回已经花掉的模型调用。崩溃回收扫描 reservation 和 dispatch 事实，不仅按 TTL 加减；运行槽在确认停止/所有权收敛后释放，待核对费用与存储仍单独占用。

event seq 由持锁 task 分配，事务回滚同时回滚计数；同一 fact 重试不重复事件。提交后通知仅作唤醒，SSE 始终按 DB `seq > last` 有界读取，所以控制重启/通知丢失不漏事实。重连和每批回放重验 tenant/actor/权限版本；现存连接遇撤权关闭，不能只在建立连接时检查。

采用基线每 actor/task 2订阅、每订阅64事件且256KiB队列、单安全payload64KiB、保留7天；慢消费者断开并允许按游标恢复，不取消任务。eventId 的 task 部分和正整数 seq 做范围校验，游标不是授权凭证。token delta 如需展示使用单独非可靠事件，不占可靠 seq，不用于恢复。

## 7. 产物和迁移

先写 STAGING/upload_id 意图，再在事务外写专有 staging 对象；关闭流、限制字节、fsync、计算 hash/size、验证媒体类型和生产者后生成 VERIFIED 不可变快照。WSL ext4 可用 hard-link 的 create-if-absent 将专有 staging 文件放到 snapshot 区并 fsync 目录；不可用覆盖 rename 冒充不可变。对象路径只由宿主生成，拒绝路径/符号链接穿越；按 tenant 分目录，不做跨租户去重。

当前 token 下的发布短事务校验 task/cancel/producer/version/hash/size，写 PUBLISHED+artifact.created；task 成功另核对所需发布事实。DB 不与上传做分布式事务：对象先存在，数据库后引用。崩溃留下的 staging/已验证未登记快照通过 upload_id 与对象清单核对；初始1小时后，仅清理已确认无活动写者、无发布引用的专有对象。不能清理仍可恢复上传或未知写者持有的文件。发布重试同 artifact_id/producer/hash 幂等，不重复快照或事件。

下载以授权 artifact 行取不可变 key，受限 storage port 打开并校验快照，不接受用户宿主路径；API/事件/模型只含 artifact ID。P2 受控产物和受控文件效果不提前开放 P3 全量 workspace 工具。

后端从 `src/main/resources/db/migration/V1__p2_ledger.sql` 开始，采用 Boot 管理的 JDBC/MySQL 驱动及 Flyway Core+MySQL 支持模块，实际解析后固定依赖证据；不预填未验证版本。V1 包含本次真实用到的表/约束/索引和 schema_history；已发布脚本不修改。控制角色先迁移，worker 仅校验预期 schema/payload 版本；缺库/错版本/缺存储必须 readiness 失败、进程拒绝领取。

后续变更先扩展、兼容读写再收缩；记录 payload_version、migration 校验和、部署制品版本。旧 worker 遇未知状态/版本拒绝领取，不读成默认成功。P1 没有持久 task，无业务数据迁移。回滚停准入并收集全部专有 worker，保留库/对象/未知效果；P1 不能接管 P2 任务。测试复位可仅删除本次专有根/容器；不可在共享库执行 clean，也不靠生产 down migration 删除账本。

## 验证和交接

本决定解除架构与资源选择前置，可进入后端实现；框架正式运行验收仍以 P2 E1 各崩溃边界的实证为准。若这些钩子在最小实现中出现可复现且不能合理修正的必要边界缺口，再依据 ADR-0002 记录证据，整套评估备选；当前没有触发切换的证据。

先完成持久受理→领取→受控模型→真实 web.fetch 网关→结果提交→重启恢复不重放的第一条纵切，再接取消/预算/事件/产物并做 E3/E4。实现、独立审查、独立测试和父事项业务验收由产品经理按已建依赖启动，本项不重复派工。
