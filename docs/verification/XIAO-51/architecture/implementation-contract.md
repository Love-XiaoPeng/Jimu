# XIAO-51 最小实施契约：任务逻辑目录

2026-10-23；架构阶段。本文、ADR-0010 和 contracts/ 是待实现设计；没有注册新工具、执行迁移或证明产品纵切通过。

## 1. 准确起点与本批选择

本次实际 checkout `HEAD=origin/master=5d90fab841559e7349701c8284642525bc00da9d`，tree `e6bbcac442abdc6d279e422dd76894c242485ab5`，开始时工作树干净。沿用总体方案0.3、工程规范0.2、清单1.1/G28/T67、ADR-0004/0005/0008/0009；冻结原件及 V1–V17 不动。`evidence/jimu-sources.json` 保存本次读取位置、Git blob 和 SHA256；上游25个原始文件逐一与固定 Git tree 核验，见 `evidence/upstream-sources.json`，许可见 THIRD_PARTY_NOTICES.md。

首批选择 **`local-logical-directory-v1`，能力集合恰为 `working_directory/read/write/edit`**，顺序按受理端原集合约定规范化，不能由请求选择 profile、根或路径政策。无 resources/images，无问答、todo、MCP、web_fetch、报告、present、glob/grep、shell 或其他能力混用。该范围足以完成本项要求的目录与文件纵切；现有搜索 profile 保持原根语义，本批明确拒绝把搜索混入新 profile。搜索回归验证旧行为；以后支持 cwd 搜索须另交包括 cursor 绑定的契约，不能本次偷偷套用当前目录。

默认 `jimu.working-directory.enabled=false`。沿用真实 owner 认证、`POST /api/tasks`、Idempotency-Key、installation 与固定 pin、`trusted-local-v1` 本地传输及完整输入 proof；只准显式 `paid=false`，拒绝 BUSINESS、evaluation scope、远端/付费/缺省 paid。control/worker 使用同一支持 V18 的 JAR。没有新增部署拓扑、服务、运行循环或用户 Session API。

工具 `jimu.workspace.working_directory@0.1.0`，调用名 `working_directory`，输入仅可选 `cd:string`，输出严格 `{cwd:string}`。JSON Schema2020-12、manifest、description 和 catalog 在 contracts/；JSON 是合法 YAML1.2，plugin.yaml 是唯一 manifest 源，catalog 摘要由脚本生成。read/write/edit 沿用现行0.1.0 schema/manifest/output pin，catalog 同时固定其输入/输出及 manifest 摘要。新路径语义由服务端 task binding v1 明示，不改旧工具资源或旧快照。

一个已受理 task 对应一个 owning Session 的本批执行上下文；新 task 初始 `/`、revision0。attempt、worker/control 重启及 owner 重连保持同一 task，不冒充新 Session/turn，不重置 cwd；另一个 task 从 `/` 开始。本批没有跨 task 续会话。

## 2. 上游事实与兼容差异

固定 Harness `571902028a75c64c1d767f4ae5fd099485f0ddf0`：

| 来源 | 已核事实 / 本云端决定 |
| --- | --- |
| `packages/session/tool-working-directory/src/index.ts` | 必须有 agent Session；省略 cd 调 ensure，提供 cd 调 set；canonical 为 `{cwd}`，render 仅 cwd 文本。本云端返回 JSON canonical，cwd 是云端逻辑绝对路径。 |
| `packages/session/working-directory/src/index.ts` | projection stateVersion1，原 header.cwd 不变；set 空字符串拒绝，fs.resolve 相对当前值，再 stat 确认为目录。相同目录不写 change/notice；每 Session 串行。ensure 会在目录消失后恢复 original/default；本云端没有原目录消失的日常操作，异常时明确失败，**不自动恢复或采用宿主默认目录**。 |
| 同目录 provider + `tests/working-directory.spec.ts`、`tests/admission.spec.ts` | 最新值作为必需 user context，按 JSON 字面值引用，不进 system prompt；执行请求在最终准入重新确认；并发相对切换串行、取消无提交、重放恢复。云端采用同一要求，以原 checkpoint 的 user 消息保存当前逻辑目录，可靠事件仅通知 owner。 |
| `packages/fs/tool-fs/src/session-cwd.ts`、`read-target.ts`、`write.ts`、`edit.ts` | 每次文件调用从 calling agent 的 ensure 得到 cwd，而非仅启动时取值；Jimu 在每个原槽首次执行时冻结解析结果，并用同一 canonical key 做观察/CAS/操作收据。 |
| `packages/fs/fs-local/src/{index,fsio}.ts` | 上游本地 provider 真实 stat/realpath/别名、宿主绝对路径；cwd 本身不是 containment。Jimu 根 `/` 只映射当前 tenant/task 的逻辑 namespace，不向模型提供任何宿主路径、symlink 别名或越界升级。 |
| Session/types、projection、persistence-jsonl、两个真实 snapshot | 原始创建目录和后续 change 分开，可从日志恢复；attribution 只表示来源。Jimu 用同库投影+原 invocation/output/events，source 不表示额外授权。没有复制 Cordis/TS 或另建执行循环。 |

上游有真实空目录与删除后的 ensure 恢复；Jimu 当前只有逻辑文件 map，没有目录实体、mkdir/delete/rename/symlink。故本批目录严格定义为 **根 `/`，或至少一个现存文件 key 的完整组件祖先**。空目录不可表达；将 `read a/witness.txt` 的真实缺失观察后 write 成功，即建立可进入的逻辑 `/a`。缺失观察本身不建目录；空文件是现存文件。此差异必须写入工具描述/运行说明和验收，不冒充完整 POSIX FS。不得为模拟上游而新增宿主 mkdir 或通用文件 API。

Jimu 沿用既有工具失败导致本次任务停止的行为；失败后 owner 仍可读最后 cwd，不在本批扩展成 Harness 的失败后继续排队策略。相同 cd 是成功新调用，有自己的工具账本，只有目录 transition 不增加。

## 3. 路径政策与目录存在性

namespace 固定为现行 `SHA256(tenant+":"+taskUUID)`；由受理端生成，binding 中固定，模型/HTTP 不可传入或覆盖。新 profile 的真实 workspace 仍是 format3，canonical 文件 key 仍是**根相对** `a/file.txt`，目录对外为**逻辑绝对** `/a`。`FilePort` 的旧 DTO 中 filePath 继续返回根相对 key，不回显原相对拼写；`cwd` 永远以 `/` 开头。

输入需是有效 Unicode scalar 序列：先严格 JSON，再检查 UTF-16 代理项配对及 UTF-8；不允许替换字符式解码/编码。原拼写和解析后绝对路径各≤512 UTF-16 code units、≤2048 UTF-8 bytes，去掉根 `/` 后旧 key 的界限仍满足。路径不 trim、不 NFC/NFKC、不大小写折叠、不展开 `~`/环境变量/URL/百分号。大小写、组合字符及 Unicode 分隔符相似字是不同普通名称。

以 `/` 为唯一分隔符；拒绝 `\`、冒号、U+0000–001F、U+007F、全空白字符串、`//`（包括 UNC/中间重复分隔符）。`/` 是根；目录允许一个末尾 `/`，文件路径末尾 `/` 拒绝。按逻辑组件从左到右消解 `.`、`..`，试图弹出根立即 `FS_PATH_DENIED`，即使最后能回到根内也拒绝。只做这种**虚拟名字的词法解析**，不调用宿主 Path/realpath；`file/..` 词法得到父目录，不声称等于 POSIX 路径逐组件 stat。

省略 cd 是读取，`{"cd":""}` 是 TOOL_INPUT_INVALID；`null`、错类型、额外字段、重复 JSON key、尾随值、坏 UTF-8、超限都拒绝。`{"cd":"."}`、`{"cd":"/"}` 是合法 SET；省略 cd 的 READ 不改变目录 revision。

| cwd / 输入 / 用途 | 预期 |
| --- | --- |
| `/` / `a` / cd，已写 `a/witness.txt` | `/a` |
| `/a` / `../b/.` / cd，已写 `b/witness.txt` | `/b` |
| `/a` / `/b/` / cd | `/b`，前提目录存在 |
| `/a` / `./witness.txt` / read/write/edit | canonical key `a/witness.txt` |
| `/a` / `/b/witness.txt` / read/write/edit | canonical key `b/witness.txt` |
| `/` / `..`，`/a/../../b`，`C:/x`，`\\server\share`，`//server` | FS_PATH_DENIED，不读取宿主 |
| `/` / `a/witness.txt` / cd | FS_NOT_DIRECTORY，cwd 不变 |
| `/` / `missing` / cd | FS_NOT_FOUND，cwd 不变 |

在 SecureWorkspaceStore 的现有跨进程锁、SecureDirectoryStream/NOFOLLOW 和完整 scope/digest 校验下读取目录证据，**不推进 observed**。目标若同时是文件和其他文件祖先则 `FS_NAMESPACE_CONFLICT`；新 profile 的每次 write 在同一文件事务中拒绝制造 `a` 与 `a/b` 这样的前缀冲突，且文件集合只增/替换、不删除。因本批无删除/重命名/链接，存在目录是单调事实，可信 adapter 取得的证据可在 DB 短事务提交前核对原 scope、目标、binding 与输入摘要；不在 DB 锁内等待文件 IO。旧 format2/3 的冲突状态原字节不改，新任务不复用旧 task 根。

证据只能来自宿主受限 port，不接受模型提交的 snapshot/digest/source。根外 symlink/目录替换仍由已有 FD/NOFOLLOW 边界拒绝；文件内容不是目录。后续增加删除/rename/空目录将破坏上述单调性，须新政策版本及重新设计协调，不能沿此契约直接添加。

## 4. 状态、顺序、提交和恢复

新增窄 `plugin.api.WorkingDirectoryPort`（受绑定的 `current()` / `change(String)` 返回 typed cwd）、`kernel.task.api.WorkingDirectoryLedger`（owner快照、冻结槽和原子目录提交）。插件只依赖公开端口；JSON、MySQL、SecureWorkspaceStore 和 lease 留在 adapter。复用唯一 ToolCallingAdvisor/DefaultToolCallingManager、原 OperationThreads/WorkerLoop/supervisor。

V18 新表/列的具体最小结构见 migration-contract.sql。task_directory 只保存当前 cwd、revision、最后**实际变更**来源与 binding/保留/清理信息；原 invocation.output_snapshot 仍是每个目录调用结果的唯一正文事实。invocation_path_binding 是一次冻结的路径与 base revision，不能把 invocation.input_snapshot 改成 canonical JSON；保留原 provider 参数及 input_digest。新 step 的三个目录上下文列仅对本 profile 有值，旧行均 NULL。

1. **受理**：按原 lock order `execution scope/run（存在时）→tenant budget→task`，核身份、4项安装/pin、模型/flag/限额，原受理事实与 task_directory 初值、固定 binding、DIRECTORY_STORAGE reservation 同次提交。无本能力的合法 task 不查新额度或建行。新 profile 首次文件调用前初始化 format3；拒绝不符头，不能缺失时将活动旧状态重建为空。workspace_state 先为 UNINITIALIZED：仅此状态允许创建空format3，若崩溃后已存在，必须验证它是本scope的空format3，然后DB标READY；READY后任何状态文件缺失均失败。所有业务文件操作只准READY，初始化DB结果不明也退出attempt裁决，不能误建第二个工作区。
2. **模型请求**：每个新请求增加一个必需 host user-context，内容按 JSON 字面值包含 `kind=working_directory_context,version=1,cwd,revision`；路径是数据，不能模板插值或混入 system 指令。prepare 事务校验它对应当前目录，冻结 step.directory_revision/cwd/binding_digest 与原 checkpoint/body/hash；dispatch 再核当前版本。恢复原 step 时使用原 checkpoint 精确字节和已有 response，不能用最新 cwd 重造旧 request 或重新派发已持久响应。仅真正的新下一 step 获取最新快照。保留 checkpoint v2、Response v2、model v2 的版本；内容新增是本 profile 新请求，不修改旧请求。
3. **M2**：在原 response/usage/invocation 事务按响应数组顺序记录槽。先整批校验4槽、累计20槽及用户收紧的 maxTools、输入/模型字节和总存储，providerCallId为有效Unicode且1–256个字符；超限保留真实模型 usage/失败事实，不部分创建可执行槽。这里**不能预先解析全部 file_path**：前面的 cd 尚未执行。每个槽位置以原 response 数组 index 校验，不能按字符串 slot 排序或按工具名分组执行。
4. **首次到达槽**：原任务锁中要求同 step 的所有较低 index 槽已 SUCCEEDED，且前 step 没有未决槽；FAILED/UNKNOWN 不允许跳过。先完整校验 task/owner/当前租约fence、binding、原 step/slot/providerId/input digest、4项当前 pin/epoch、flag、取消/截止和工具/存储预算。冻结 base cwd/revision、操作类型及 canonical paths/target（路径错误在此拒绝）；同一 `(tenant,invocation)` 只能 INSERT 一次，以后必须逐项验证相同值。文件槽的路径绑定与通用 admit→RUNNING 在同一个 DB 事务内；随后 FilePort 的 bind/观察冻结和每次 read/write/edit 只使用这些 canonical key。
5. **目录调用**：冻结后仍为 PENDING；在事务外由可信 workspace adapter检查当前/目标目录，随后短事务重验第4项、base revision CAS与目录证据，直接 **PENDING→SUCCEEDED**。目录变化时 `revision+1/cwd/最后source`、原 output/digest、实际提交 attempt/fence/time、一次 TOOL 消费/SETTLED、tool.dispatched / working_directory.changed / tool.completed 同次提交，提交尾再用 DB 当前时间条件校验 lease/deadline/cancel/fence。READ 和 SET 到同 cwd 同样原子提交原结果与一次工具消费，但不改目录 revision/source、不发 changed。读取工具并非免费绕过网关；owner GET 才无工具消费。
6. **恢复**：仍须当前身份/安装/pin、fence、flag和执行准入。已 SUCCEEDED 原目录 invocation 先核 source/结果摘要及原槽，再返回其**原 `{cwd}`**；即使当前目录已更新，也不重写投影、事件或预算。未成功目录槽继续用原冻结 base/target，当前 revision 不等则409 DIRECTORY_REVISION_CONFLICT，绝不采用新的 cwd 重解析。两个真正的新调用各有自己的事实；相同 cd 只共享“无 transition”的语义。
7. **结果不明**：目录 DB commit 抛数据库/事务异常且不能确认结果时，使用同 todo 模式的专用 `DirectoryCommitUnconfirmed` 退出 attempt；DurableAgent 最外层只捕获此类型并正常返回，不 toolFailed/uncertain/stop、不向模型返回失败、不继续下一槽/模型。WorkerLoop 前台 drain→quiescent→停止续租，下一合法 attempt 用原槽裁决提交/未提交。目录冻结 DB 事务结果不明也采用该退出路径；不得造新 invocation 重试。旧 RUNNING 文件工具仍按原 UNKNOWN/NEEDS_RECONCILIATION 规则，不将本协议泛化为自动重放文件写入。
8. **跨 worker**：新 profile 的过期 RUNNING 领取也必须确认**所有历史 attempts 的真实 writersQuiescent**，或由原 supervisor 证明原 worker 已停，才发新 fence；现行普通 expired claim 只处理 invocation 状态，不能直接拿来当该证明。存活但不可确认的写者继续占用。每次存储临界区及提交前沿原 checkpoint 再核 fence/取消/期限；所有文件观察与 CAS key 均为冻结 canonical path，切 cwd 不移动/清空 observed，不让 `a/x` 的观察授权 `b/x`。旧 fence 永远不能改 cwd、路径绑定或提交新结果。

例如 `[cd a, read x, cd ../b, read x]` 必须依次绑定 `/a`、`a/x`、`/b`、`b/x`，同一响应合法。恢复时前两个已成功槽返回原结果，后两个依原冻结/未冻结状态顺序处理。callback 不能相信 Spring 当前的串行实现作为唯一保护；DB predecessor barrier 才防错序或并发旁路。通用 admit/toolSucceeded/uncertain 必须拒绝 working_directory；目录专用路径也不得绕过文件工具的原准入/UNKNOWN治理。

## 5. Owner API、事件和错误

`GET /api/tasks/{taskId}/working-directory`，无 body/query，真实 Authorization bearer、现有 SignedIdentity/TaskFailure `{code,message,traceId}`、`Cache-Control:no-store`。无写目录 HTTP API。先身份、再 tenant+owner+task 查询，格式错误/跨租户/owner/不可见 task 均沿现行404 NOT_FOUND。当前 actor 或任一 task 安装撤销/epoch或pin漂移拒绝；无目录能力的合法旧 task 返回200 ABSENT，不要求目录安装、不创建状态。

响应遵循 directory-view-v1.schema.json：`version/taskId/asOfSeq/revision/state/cwd/source/retainUntil`。PRESENT 初始 cwd `/`/revision0/source null；实际变更后 revision>0，source 取最后 changed 的 invocation/checkpoint/providerCallId、创建 attempt、实际提交 attempt/fence、eventId、原 outputSha256、updatedAt。不能把创建 attempt 当提交者。PURGING/PURGED 的 cwd null，revision及不含路径正文的 source/摘要保留；ABSENT 为revision0/cwd/source/retainUntil null。读取者不能注入 source id。

先锁当前身份及4项 installation FOR SHARE，task水位、目录指针及其原来源 invocation/绑定/结果使用**一条 JOIN SELECT 的一致快照**，核 scope、原输出 digest、applied revision、source/event 对应关系。PURGING/PURGED按清理版本与retained_digests校验原结果摘要，不把tombstone当原输出重新hash；保留的安全pin/政策元数据继续用于当前授权。DB READ_COMMITTED 下多次独立 SELECT 不能拼 asOfSeq。正常终态、取消、截止或预算耗尽保留 owner 历史读取权；GET 可观察这些事实，但不做运行准入或复活任务。flag关闭不删除历史，actor/安装撤销仍拒绝读取。

沿用 SSE `/api/tasks/{id}/events` envelope v1 / `taskId:seq`，新 type 只有 `working_directory.changed` 和 `working_directory.content_deleted`，作为无路径正文的失效通知，artifactIds=[]/code=null。流程：GET S→原 SSE Last-Event-ID=`taskId:S.asOfSeq`→遇新 type/终态再GET；断连、25秒连接结束或410 CURSOR_EXPIRED 均先GET最新。终态最后GET一次即可收敛，不无限订阅；未来/跨task游标按旧400。GET、重连、空闲维护/轮询不制造模型、工具、事件或 revision。

| 失败 | HTTP/工具安全码；拒绝效果 |
| --- | --- |
| 未启用/非本地或付费/混合能力 | 403 DIRECTORY_NOT_AUTHORIZED / 400 INVALID_CAPABILITIES；不受理、不安装 |
| 执行中关闭flag/未知binding或路径版本 | 403 DIRECTORY_FEATURE_DISABLED / 409 DIRECTORY_BINDING_UNSUPPORTED |
| 空cd/JSON/类型/未知字段/Unicode/大小 | 400 TOOL_INPUT_INVALID；无目录状态变化 |
| 越根/Windows/UNC/分隔符政策 | 409 FS_PATH_DENIED；无宿主访问 |
| 无目录/目标文件/前缀冲突 | 409 FS_NOT_FOUND / FS_NOT_DIRECTORY / FS_NAMESPACE_CONFLICT；cwd不变 |
| 旧revision/错序/伪造来源 | 409 DIRECTORY_REVISION_CONFLICT / DIRECTORY_SLOT_ORDER / INVOCATION_MISMATCH |
| 通用旁路/损坏状态或结果 | 409 DIRECTORY_COMMIT_REQUIRED / SNAPSHOT_CORRUPT |
| 新存储/执行数量界限 | 429 DIRECTORY_STORAGE_LIMIT / DIRECTORY_EXECUTION_LIMIT；原预算更小则原码 |
| 清理后执行/重用受理key | 410 DIRECTORY_CONTENT_EXPIRED；不重新执行 |
| DB不可用/存储不可证明 | 503 LEDGER_UNAVAILABLE / 原 WORKSPACE_UNAVAILABLE；保留未知事实 |

已冻结/失败/运行记录保留原始事实；成功目录不因普通网络/响应丢失改为失败或覆盖后来的目录。输入/路径/目录内容不进日志或安全错误体，不授予部署、代码执行、付费、通知或外发权限。

## 6. 上限、存储、清理与升级

固定 binding 含 contracts/task-binding-v1.schema.json 的全部上限，用户原预算只能收紧：task≤180秒、模型派发≤8（含恢复）、工具invocation≤20、attempt≤8、每模型响应≤4槽、可靠event≤256（终态/清理预留2槽），summary≤8192字节；单工具≤min(剩余,30000ms)，DB锁等待≤min(剩余整秒,2秒)，文件锁等待也须可取消且有同一期限，不能沿用无界 file.lock 等待。目录输入/输出各≤4096，所有工具输入≤65535/输出≤65536，JSON深度≤8；模型实际request≤65536、checkpoint≤131072、canonical/provider response≤65536/maxOutputTokens≤4096。完整模型输入proof继续有效；拒绝不能改旧model snapshot来凑界限。

文件每份≤32768字节、当前文件总和≤262144。原 edit old/new 各16384字节限制继续保留。实际持久 workspace Envelope（含全部 receipt/binding/observed及历史内容）≤2MiB，不只计当前文件；现有128MiB的通用上限不能当新profile准入。每次保存前精确计数并在原原子存储事务内拒绝超限；本scope `.json` + **所有**旧/新 `.part` 实际字节总和≤4MiB、part个数≤8，崩溃遗留也计入，不能只算一个新旧文件对。用secure FD/NOFOLLOW核对本scope条目，超限明确拒绝；不为腾容量删除活动/未知写者的临时文件。

所有 DB 正文副本合计（task request/goal/summary/model/tools、binding与路径、step request/response/上下文、invocation input/output、usage原片段、清理摘要表）+65536字节元数据预留≤2MiB；普通元数据≤61440，终态/清理≤65536。局部最大值不保证能同时占满，先到总限额明确拒绝。每task独立预留8MiB、tenant新增 directory_storage_limit 默认64MiB；DIRECTORY_STORAGE 与旧 storage_held、todo/question额度分开。预留覆盖 DB≤2MiB+FS双份≤4MiB及余量；本profile无native/artifact其他存储。安装/任务/模型原配额仍保留。正文复制多少次就计多少次，不能把来源与摘要当完整原文的替代计数。

受理后7天保留。复用 LedgerMaintenance 每批最多32个本profile候选，在 LIMIT 前排除非确定终态、历史活动写者、UNKNOWN/HELD_UNKNOWN及未结真实调用，避免不可清理前缀饿死其他租户。清理是明确的跨存储协议：

1. DB短事务锁tenant→task，复核终态 SUCCEEDED/FAILED/CANCELLED、所有历史写者真实停止、无UNKNOWN/未结占用及到期。保存每份正文原摘要/字节数，删除request/goal/summary、step请求/响应/目录上下文、invocation输入/输出/路径绑定、usage原片段和目录证据中的路径及内容正文，替换显式 DIRECTORY_PURGED/tombstone或NULL；step.request_cleanup_state 使用新标记；task_directory 标 PURGING/cwd=NULL。保留无路径正文的tools/model pin、binding固定政策/namespace、task原owner、预算/调用/状态/来源摘要等鉴权和完整性元数据，不能清掉pin后让GET绕过当前安装校验。此标记先于任何磁盘删除，之后所有执行、初始化或受理key复用均拒绝；GET只读PURGING快照。
2. 事务外由受限 workspace adapter 用原 secure root FD 和 scope锁删除**该精确 scope**的 `.json` 与该scope、UUID格式的遗留 `.part`，不扫描其他task、不跟随链接；目录fsync后记录原state摘要/实际删除清单/收据。没有单独逻辑目录树需要递归删除。可保留空scope `.lock` 作为有界墓碑；它不得包含正文。磁盘删除失败/结果不明继续PURGING并持有额度，后续有界维护幂等补齐，不凭TTL释放。
3. DB短事务复核相同清理代次与删除收据，标PURGED，写唯一 content_deleted，CAS释放 DIRECTORY_STORAGE并扣 directory_storage_held。DB提交结果不明按持久状态再裁决；重复维护无重复事件/释放。NEEDS_RECONCILIATION、旧账/UNKNOWN一律保留。

由此不宣称 DB 与文件删除存在同一原子事务。清理/墓碑/原摘要均计入预留，异常遗留的本scope临时文件也计入删除证据；禁止活动任务 `.json` 丢失时自动新建根绕过既有状态。owner临时PURGING是显式接口状态。

只新增 V18。DB新表/step可空列/新tenant额度，旧 task/tool/model/checkpoint/Request 版本、V1–V17 bytes及旧行快照均不改变。不需 workspace format4：目录权威在DB，存储仍只处理已有 canonical key及format3；层级限制、较小配额只由新binding入口启用，不重新解释旧format3任务。旧V17 binary严格schema gate本来要求精确17，实际升级测试必须证明control/worker都拒绝V18。停专有旧worker后再迁移；不动187共享持久服务。回退用支持V18、关闭新flag的制品，不down migration/clean、不改旧pin或释放未知额度。

## 7. 原后端的实施顺序与可执行验收

先入库本契约、ADR、manifest/Schema/catalog和许可；核当前master/已有分支，尽早开 XIAO-51 关联 master 的草稿PR。首个可构建切片为“4能力受理→唯一循环读取根/进入由write建立的目录→owner GET”，随后完成槽路径绑定/恢复/清理/升级。两小时检查点交可构建切片、PR进展或证据化阻塞。禁止再派第二后端同时改同文件。

主要改动位置（以准确基线为准，提交后重新定位）：WorkspaceContracts/WorkspaceRuntime 与新增DirectoryContracts/Runtime；plugin/api窄port和薄WorkingDirectoryPlugin；JdbcLedger的submit/capabilities、prepare/dispatchModel/modelResponded、admit/claim/finish/cleanup；新WorkingDirectoryLedger/Controller；SecureWorkspaceStore增加只读目录证据、新profile层级/大小/锁期限与精确scope清理入口；DurableAgent定义/槽绑定/user context/专用commit-unconfirmed处理；V18、schema gate、运行角色SQL grants、默认flag及readiness。bootstrap只装配，内核不依赖具体插件。旧read/write/edit资源字节保持。

下表是**产品必须运行、当前架构回合未运行**的验收清单。后端提供 `WorkingDirectoryContractTest`、`WorkingDirectoryIT` 与 `tools/working_directory_verify.py --case <id> --evidence <dir>`，沿仓库已有IT/driver约定，可逐项执行且缺资源必须失败，不能skip。测试名称可按仓库惯例调整，但交接必须给出实际可执行命令。

| ID | 真实布置/触发 | 独立断言与原始证据 |
| --- | --- | --- |
| D01 | 私有MySQL、control/双worker、同一JAR、显式paid=false模型；响应1 `[cwd{},read a/witness.txt缺失,write a/witness.txt]`；响应2 `[cd a,read witness.txt,write witness.txt,edit witness.txt]`；响应3读cwd；下一step完成 | 初始`/`→`/a`；文件真实v/hash/内容是a下目标；所有原槽、TOOL/模型账/事件、source/API；summary直接finish，无伪报告或外发。≤8模型/20工具，记录SHA/tree/JAR SHA256。 |
| D02 | 同响应`[cd a,read x,cd ../b,read x]`；分别已写a/x与b/x为不同sentinel；call和stream；重复工具名交错 | 真正按数组index调用；a/x和b/x各自内容；不能按工具名分组/预解析；数据库并发错误先执行slot3返回DIRECTORY_SLOT_ORDER。 |
| D03 | 原response持久后未执行；路径冻结后；目录提交前回滚；COMMIT成功但回包前kill；worker/control重启、owner重连；旧成功cd在后续revision之后重放 | 原输入/checkpoint字节、原invocation/result/source不变；新attempt有真实fence；无重复cwd transition/事件/TOOL；旧结果返回原cwd不覆盖新目录；未冻结后续槽取前序最新值。 |
| D04 | 旧revision、伪造slot/provider/input/namespace/source；双worker抢同槽；旧活写者仅租约过期；文件RUNNING崩溃 | 旧fence/stale拒绝；未确认写者不发新fence；同一目录提交最多一次；一般文件UNKNOWN规则保持，不复活终态。保存进程身份、停止/静止证明。 |
| D05 | `/`、绝对/相对、`.`/`..`、空/未知/重复key/尾随值、Unicode/emoji/组合字符/大小写、边界N-1/N/N+1、slash/drive/UNC/编码路径 | 与§3和附带path-cases.json逐条核对；不静默截断/normalize Unicode/越根；file cd失败及missing cd前后cwd/revision/digest不变。创建a与a/b两顺序均拒绝冲突。 |
| D06 | read a/x后cd b、write/edit x；换回a；修改后CAS、相同别名/绝对相对；宿主/根/最终state symlink替换 | b/x没有借用a/x观察而成功，明确FS_NOT_OBSERVED/STALE；同canonical别名共享真实版本；host/sibling唯一sentinel永不读取；NOFOLLOW真实拒绝，未改变任何Java/宿主进程cwd。 |
| D07 | tenant/owner/task/actor/安装epoch/version/pin、flag关闭、撤权、取消/截止/预算与commit竞争，终态恢复 | 每入口及恢复均复核；拒绝不改cwd；提交先赢则历史事实保留；撤权不可GET，正常终态可GET；无本能力旧task ABSENT且可继续原profile。 |
| D08 | GET后订阅前插入change；25秒/断连/410/未来/跨task游标；同目录SET、重复GET、空闲轮询 | 快照asOfSeq与source同读，SSE回放接齐；no-op无changed，新调用独立TOOL；重连不派模型；路径正文不进入SSE/日志。 |
| D09 | 路径/工具/响应/checkpoint/文件/FS Envelope/DB/元数据/attempt/event/tenant配额真实上限，DB和文件锁竞争 | 精确字节/拒绝码；保留真实usage，不部分M2槽；锁可取消/有期限；目录额度满不阻断无目录旧profile或其他tenant。 |
| D10 | 到期与未到期；32个UNKNOWN/活动写者排在可清理对象之前；PURGING前/后、文件删除后DB提交前崩溃；不同tenant并存 | 仅安全终态清理全部DB/FS/临时副本，保留摘要、来源及旧账；PURGING显式可观测；未删完不释放，补齐后唯一释放及事件；活动/UNKNOWN始终保留。 |
| D11 | 用准确旧V17 JAR先造旧QUEUED/终态/问答WAITING_USER、todo及旧format2/3工作区；升级V18，再用旧binary连新库 | V1–V17 checksum/旧快照与原state字节不变；旧任务不凭空有binding；旧V17两角色拒绝新schema；新binding/路径未知版本明确拒绝；关闭flag回退仍读历史。 |
| D12 | 旧工作区read/write/edit、glob/grep及cursor、问答/todo/MCP/清单受影响定向回归；混合新profile+glob/present等 | 旧root/默认禁用native/Schema/pin/UNKNOWN与业务结果不变；新非法组合受理拒绝，不创建native进程或伪装支持cwd搜索。不重跑旧完整矩阵。 |

建议后端实现后的定向入口（当前类/driver尚不存在，不能称已运行）：

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp verify '-Dtest=WorkingDirectoryContractTest,WorkspaceToolContractTest,PackageBoundariesTest,TodoContractTest,UserQuestionContractTest,McpContractTest' '-Dit.test=WorkingDirectoryIT'
```

在同一准确完整源码SHA/构建上完成开发必要自测，再由原审查/QA独立取证。每项保存实际命令/退出码、真实协议/账本、故障屏障、未覆盖/失败与清理结果。原 `SourceIntegrityTest` 的ADR README冻结3446/实际3819失败保持，不能修改冻结记录追认或声称默认完整矩阵成功。不运行、启用、重跑或等待GitHub CI；不接真实Key/付费/生产，不改187两项持久服务、共享daemon/网络/代理。当前可用交付为本任务评论/附件和Git仓库，无飞书发送工具或外发行为。
