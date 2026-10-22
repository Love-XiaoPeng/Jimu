# XIAO-52：session_event_read 最小实施合同

2026-10-23。规范词“必须/拒绝”是本批实施约束。架构源码基线与许可摘要见 `evidence/jimu-sources.json`、`evidence/upstream-sources.json`。本包为设计与证据，产品代码、V19 和真实业务验证由原后端接续。ADR 为 `docs/adr/0011-authorized-session-event-read.md`。

## 1. 装配与兼容边界

新增 `jimu.session-event-read.enabled=false`；控制层与 worker 显式启用才可发现、受理或执行。安装记录沿用现有 capability 名作为 `installation.plugin_id` 的习惯，值为 `session_event_read`；manifest ID 为 `jimu.session.event_read`。安装 version、digest、epoch 和 ToolSnapshot v1 继续校验，不以 manifest ID 替换旧表值。

新受理 task 恰为 `capabilityIds=["session_event_read"]`、resources 为空、无 images；拒绝 BUSINESS/评测 scope，模型必须通过现有本地 endpoint 校验且显式 `paid=false`，不得回退 fixture/远端/付费模型。预算继续从现有 task 请求缩小，不能大于 180 秒、8 模型请求、20 工具调用、8 attempts、4 calls/response。所有 worker 分支、capabilities 广告、幂等提交重放和单工具发现都须采用本绑定。旧 profile 不进入本批存储/输入策略。

模型准入沿用 `ModelProfiles.requireTodo/requireDirectory` 的实际本地条件：非内置 fixture、test-mode、trusted-local-v1、127.0.0.1 或 [::1]、paid 配置原文为 false、无 business/evaluation/scope、现有完整请求 proof。保留现行更紧 token 限额；本批 transport responseBytes 另须 <=16384，requestBody/checkpoint 上限按下文。仅增加等价的 requireSessionRead 入口，不放宽旧 profile 或复用一个含义不同的 TODO 错误码。正常模型仍经唯一真实协议路径调用本地受控模型服务。

模块只新增窄 `plugin.api.SessionEventPort`（已绑定当前调用的 read(input)）、`kernel.task.api.SessionEventLedger`（上下文、原槽 readOrResult、owner 快照/单事件读取），及现有 adapter 中的事务/严格契约实现。插件只依赖公开 port，不拿 Identity、Lease、SQL 或宿主配置；受信 adapter 闭包绑定原 lease/step/slot。bootstrap 只装配。沿用唯一 ToolCallingAdvisor 和原 manager 串行槽执行。

`DurableAgent.runAttempt` 必须像现有 todo-only 分支一样将本 profile 排除工作区初始化，并走 summary→ledger.finish 的无产物完成路径；不能落入通用“有工具则上传 summary 产物”分支。不为只读历史初始化文件工作区或制造 artifact。最终 summary 仍做 Unicode/字节/公开安全边界与副本额度检查。已发模型请求的真实 usage 结算在关闭 flag/撤权后仍保留；该结算不授权接纳新调用或暴露被拒响应正文。

新表的 SELECT 与 INSERT/UPDATE 权限须同步到现有 `tools/evaluation/application_grants.py` 的可审阅 SQL 生成器；本轮不执行授权 SQL，不新增数据/部署权限。沿现有维护器接入有界清理，不创建第二个清理服务或线程。

## 2. 固定上游事实与云端差异

| 语义 | 固定上游真实行为 | 本批 Jimu 决定 |
| --- | --- | --- |
| 参数 | 必需 seq；可选 session_id/before/after；省略 session 为 caller；省略邻接数为 0 | 同名及默认值；严格额外字段、重复键及数值词法验证；ID 只接受规范小写 UUID |
| 序列 | SessionSeq 是非负 safe integer；events[seq]，零起始连续数组 | native 日志零起始连续；不是 SSE seq、attempt ordinal 或模型猜测的编号 |
| 窗口 | readEvent 取 max(0,seq-before) 至 min(log末尾,seq+after)，包含目标；不存在目标报 EVENT_NOT_FOUND | 相同端点裁剪；上界进一步受原 step 限制；每侧最多 4，总邻接最多 8；内部缺号报 CORRUPT |
| 结果 | 工具返回文本，fenced JSON 完整目标，邻接调用 extractSessionEventText；邻接文本未另作长度截断 | 返回有版本的 JSON 对象，完整**已存公开事件**和有界摘要；摘要截断有标记，目标/整体超限报错 |
| caller | exec.agent 的 session/header/turnBoundary；入参不是 authority | 当前 lease 关联已受理 owner；每条结果核验 tenant/owner/task/actor、范围及安装 pin |
| 跨 session | 调用前按 caller 不可变 header.cwd 筛选；provider 返回 header 再核验 | 不用 cwd 授权；首批只允许 caller.taskId。其他 task 即使同 owner/cwd 也拒绝 |
| provider | SessionQueryEngine 的 exact read 从 SessionCorpus live-preferred snapshot；否则 list/inspect 持久日志、核 header；SQLite 子类继承 exact read，FTS 关闭仍可读 | 仅同库已提交 native 日志；不查 TS、JSONL、文件、SSE 摘要或内存缓存作为回退；不需要 SQLite/索引服务 |
| 当前调用可见性 | read 工具没有 event_search 的“stepStartSeq-1”裁剪；读取当前 detached log | 冻结在 M1 前的事实，当前 step 全部新增事件排除；重放不扩大范围 |
| 全量内容 | 上游包括原 SessionEvent data/surface、stream、tool/result 等 | 本批原生 schema、public-v1 投影，无原协议/隐藏思维/内部配置；读取工具只记收据，结果不递归嵌入 |
| Session 生命周期 | 可含多 turn、seed/fork、live/persisted 等来源 | 一个 task 一个 turn；无 fork/继承/替换/续轮 API；旧 task 无日志则明确 unavailable，不回填推断正文 |

固定上游 `sessionProjections.stateOf(...,'turnBoundary')` 是 fold/state；SessionProjections 服务按原事件 eager drive 并可从日志重建，不是另一份“完整事件”provider。`operations.executeEventRead` 调用 sessionQuery.readEvent；只 event_search 读取 turnBoundary 做步骤裁剪。不要把 search 的裁剪误记成上游 read 行为。来源行号见 `evidence/source-map.md`，测试是阅读证据，未运行上游 TS 测试。

## 3. Session、正文与来源

受理事务建立 `task_session_history`，不可变绑定 `historyVersion=1 / public-v1 / current-task / pre-step`，`session_id=task.id`。同 key 幂等受理返回原 task/日志；新 task 是新 Session。attempt/接管/重连不重置日志或序号。owner 的历史指本 task 已提交且仍保留的事件；模型不能借“历史”读取另一个 task。

`session_public_event` 是本批权威正文，seq 自 0 连续单调、不复用；`fact_key` 唯一、同事实重放不追加。事件 JSON 完整形状见 event-v1 Schema。字段 `version/session_id/seq/type/time/source/content_policy/redacted/data` 全部必需。UTC time 使用同事务数据库时间；排序按 seq，时间仅展示。source 仅包含安全的来源类型和 step/invocation ID；tenant/owner 由表关联核验，不接受模型指定来源。

| type / fact_key | 同事务写入时机 | 完整 data | 核验来源 |
| --- | --- | --- | --- |
| user.message / accepted-user | 新 task 受理 | `{content}`：已受理 goal 的公开投影，保留换行/Unicode，不 trim/概括 | task.request_digest；owner 来自认证上下文；seq 必为 0 |
| step.started / step:<stepId> | 原 prepare 新建 step | `{ordinal}` | step.task/id/ordinal；重放 prepare 不追加 |
| model.response / response:<stepId> | M2 已接纳 Response v2、原调用槽持久化 | `{content,calls:[{id,type,name,arguments}]}`：完整已接纳的模型可见 content 和原 calls 顺序；arguments 保留原 JSON 字符串 | step.response_digest + 原 Response v2；无 usage/config/header/stream/隐藏 reasoning；输入拒绝的响应不伪装为已接纳 |
| session.read / read:<invocationId> | 原槽读取结果或确定错误提交 | `{seq,before,after,upper_seq,outcome,code,output_sha256,output_bytes}`；失败 digest=null/bytes=0，成功 code=null | 原调用、read binding 和原输出 digest/bytes；这是原生收据，不是展开的 tool/result |
| turn.ended / terminal | 首次权威终态写入 | `{state,code}`；终态 summary 已在 model.response 中，失败不杜撰模型响应 | task 终态/SSE 可靠事实；取消排队任务也有 user.message + 终态 |

只记录上述五种本批事实；没有从旧 checkpoint 重建历史的 importer。不同 worker 仅在正常受信状态迁移中写入。step/invocation 不相属、source 伪造、snapshot 摘要不符、未知事件版本/类型必须拒绝，不能跳过后当邻接。DB 源响应的 session/task/seq/type/source 必须逐条与请求和 binding 相符。原事件全量返回与 DB 保存的公开 JSON 做字节/对象一致性核验，绝不以 task.summary 或当前状态代替。

私有 source_digest 分别为 task.request_digest、step.request_digest、step.response_digest、invocation.input_digest；turn.ended 为其原可靠终态 event 的 `event_id/type/status/code` 四字段按该顺序构成对象后以现有 Payloads.json 编码的 SHA256。session.read 另核 binding 与实际 output_digest/bytes/receipt_seq。可靠终态原事实是其来源，不用可能后来核对变化的 task 当前状态重解释原事件。source_digest 不进入公开 JSON。NEEDS_RECONCILIATION 仍保留且不清理，后续核对不能重写原 turn.ended 或其 fact_key。

### public-v1：捕获时固定的公开投影

仅复制表中白名单字段；禁止对象整行序列化，禁止从 checkpoint.requestBody、模型配置、异常、HTTP headers、环境或文件填充 data。模型响应仅现有 OpenAiProtocol 的已接纳 content/calls，不读取 reasoning_content。arguments 已过本工具严格输入验证，仅含四个字段；其字符串本身不归一化。

对 user/model 的 content 应用一次固定策略：先按字面替换受信 runtime 提供的当前凭据、签名秘密和私有配置敏感值（非空，最长值优先；秘密集合不入库/日志）；再将按 Unicode White_Space（另含 U+FEFF）分割后含斜杠或反斜杠，或匹配 `(?i)(authorization|bearer|token|secret|password|api[_-]?key|env:)` 的整个非空 token 替换为 `[redacted]`，并替换该 token 后的下一个非空 token。空白原样保留。判定用替换敏感值后的原 token 序列，单次同时标记所有命中及其后继，不因替换后的 `[redacted]` 再触发匹配。不做模型生成式摘要。该保守策略会隐藏普通 URL、路径和部分普通词，是已接受的兼容差异。source IDs、固定 code 和合法 session_id 不做该文本转换。

任何替换设置 redacted=true；目标完整返回**捕获后的公开事件**，不声称原始模型/用户文本字节完整。calls.id 仅接受 `[A-Za-z0-9_-]{1,128}`，不允许路径、URL 或任意 printable ASCII；若与当前敏感值匹配则拒绝，不改写用于恢复的原 ID。无法取得必要敏感值、未知策略或已检测泄漏时 fail closed `SESSION_EVENT_UNSAFE`，不保存/返回原文。只使用 synthetic sentinel 验证，不引入真实 Key。新 endpoint 的响应和失败、模型工具结果、SSE、日志均不得含敏感值或宿主路径；原 DB 恢复快照仍是受限内部数据，不能公开。

## 4. M1 边界、真实 seq 和并发

M1 处理新请求之前，`SessionEventLedger.context(lease)` 在原授权锁序内取得 upper_seq=next_seq-1 和最近最多 8 个事件的 `{seq,type}`；额外给出恒定 `user_message_seq=0`。形成必需宿主 user-context：`kind=session_event_context, version=1, session_id, upper_seq, user_message_seq, recent`，最长 4096 bytes。将该 JSON 作为现有 WireMessage(user) 加入本次 checkpoint，不改变 Checkpoint v2/v3 wire。模型可直接使用这些真实引用，无需猜 seq 或访问新搜索工具。

prepare 验证该上下文与当前 task/binding/已提交日志切点一致，保存 step 的 `session_upper_seq/session_context_digest/session_binding_digest`，再同事务追加 step.started。若取 context 后有追加导致切点不符，返回安全冲突，不按另一个切点悄悄改同一请求。正常单 worker 无独立日志写者；并发对照测试要覆盖实际冲突。重放原 prepare/恢复原 checkpoint 必须识别其原 step，直接使用原 digest/upper，不刷新 context 或再次追加 step.started。不得因新的宿主上下文误判同一步为新模型请求。

调用 i 可见范围为 `[0, originalStep.upper_seq]`，即使此后有 M2、同响应前槽/并发追加或更高 worker fence，新范围也不能进入原结果。边缘邻接按此范围裁剪，输出 upper_seq 是可见上界，不返回当前日志总数。`session.read` 输出收据不含原结果正文，也不自动跟随摘要/引用，所以重复读收据不会展开上一次工具输出。

owner 元数据 API：GET `/api/tasks/{id}/session-history`，无 query/body，返回 version/session_id/content_state/upper_seq/recent/user_message_seq/as_of_sse_seq/retain_until/readable；recent 最多 8，过期或 PURGED 时 recent=[]、readable=false，元数据墓碑仍返回200，无正文。owner 单事件 API：GET `/api/tasks/{id}/session-history/{seq}?before=0&after=0`，返回与工具相同 read-output，但 `boundary=owner-snapshot`，上界在此只读事务开始时固定。工具为 `boundary=pre-step`。unknown/repeated query、非十进制无符号整数字面值、body 均拒绝；路径 task ID 沿用规范 UUID 验证。

沿用 SignedIdentity 认证和当前 actor/tenant/owner/pin 复核；先授权后给范围/错误。响应 `Cache-Control: no-store`。GET 不领取 task、不调用模型/工具、不创建读取绑定/原事件、不消费预算或历史。终态可读不等于可恢复。通过原 SSE 的新增 `session_history.changed` / `session_history.content_deleted` 类型作失效通知，信封字节格式不变且无正文；与原日志追加事务一起提交，metadata.as_of_sse_seq 是同一切点。先 GET metadata，再按原 SSE 游标订阅；断线/410 重新 GET，不空闲调用工具。

每个事实写事务最多发一条 history.changed；多条新原事件可引用同一个 reliable_event_seq。该 seq 只关联已提交通知，不能当 native seq 使用。原生事件不因 GET、SSE 回放或工具读取而更新/消费；本工具只产生正常调用自己的预算/结果/收据/可靠事件，不改变查询对象、cwd、todo 或历史消费位置。

## 5. 原槽 readOrResult 与事务

使用现有 run/evaluation → tenant_budget → task 的锁顺序，本 profile 无 evaluation run。再按现有方式锁 actor_access 和 installation（FOR SHARE，锁持有到事务结束），检查安装 epoch/version/digest 及不可变 binding；锁本 task history、step、invocation、read binding。所有写路径沿用同序；不添加反序获取 task 的 owner 查询。owner 读取可直接按 tenant bucket→task→authority 的同序取锁，2 秒上限内完成。

1. 原 lease tenant/actor/task 与已受理 owner 精确匹配；当前 fence/attempt/worker/有效租约、RUNNING、未取消、DB deadline 未过。校验当前模型仍为本地 paid=false。其他 session_id 在查目标前统一 `SESSION_EVENT_NOT_FOUND`；不查另一个 task 是否存在。
2. 核对 step 属于 task、RESPONDED、Response v2、原 response/input digest、原响应数组 index/slot/providerId/tool/input、pin version/digest/installation epoch。M2 仅为合法原槽新建 read binding：原上界、input digest、target_seq、before/after；它是 insert-only，不能由新 attempt 更新。
3. 已 SUCCEEDED：检查 read 绑定/源收据和 output digest/schema/字节数，复核当前权限/fence/flag/保留后原字节返回；不查询日志目标、不重写任何已绑定结果/预算/事件。已绑定 FAILED：复核当前授权后抛原安全 code；不重查后来的事实。
4. PENDING：上界外或缺目标返回确定缺失；否则仅用 PK `(tenant_id,task_id,seq)` 取 `[max(0,seq-before),min(upper,seq+after)]`，LIMIT 9。范围必须连续且目标存在；缺内部记录、源不一致或摘要坏为 CORRUPT。每条目标/邻接都核 tenant/task/owner/当前 actor/允许集合、版本、来源、保留、安装 pin；不依赖 SQL WHERE 或模型自行过滤作唯一授权。
5. 验证完整 target <=16384 bytes，产生至多 8 个确定性邻接摘要：user/model content 的前 256 Unicode code points、最长 1024 UTF-8 bytes；其他类型 text=""。UTF-8 前缀不切 surrogate/码点；以真实被省略文字设置 summary_truncated。每摘要整个 JSON <=1536 bytes；有界 summary 以外不截断，输出对象 <=32768 bytes，超限明确失败。
6. 在同一事务验证/记账/提交：一次 TOOL reservation 和 task/tenant tool_used，原输出及摘要、read binding 的状态/范围/结果、PENDING→SUCCEEDED、原生 session.read 收据及可靠 tool.dispatched/tool.completed/history.changed。source 保存实际 commit attempt/fence，调用生成 attempt 仍保持原值。
7. 完整窗口确定缺失/已过期/版本/正文超限等安全确定失败，同事务绑定 FAILED/code、一次 TOOL 消费、session.read 失败收据及 tool.failed；提交后在事务外抛 TaskFailure。输入/权限/取消/截止/fence 准入拒绝不查目标、不消费、不公开 target；worker 按原停止规则处理。不把失败包装成成功的空列表。
8. 最后一次 DB 条件检查当前 lease/截止/取消/终态和 pin，再提交。权限锁使撤权与结果有确定提交顺序。禁止先 RUNNING 再查询/另写成功；通用 admit/toolSucceeded/uncertain 对本工具明确 `SESSION_READ_COMMIT_REQUIRED`。按原响应槽顺序执行，后槽不能绕过未完成前槽。

事务内不做网络/LLM/文件 IO。query 整体上限 2000 ms（包含等待、读取、校验和提交前检查，且不越 task deadline），每次 SELECT 使用剩余预算的 statement timeout/MAX_EXECUTION_TIME，锁等待最多 1 秒；检查单调时间，finally 恢复连接会话设置。禁止通过外部线程 cancel 后仍让写者提交。事务回滚的超时/provider 异常不绑定假结果，安全映射 504/503；恢复仍使用原上界，不能读后来的历史。

连接在 commit 前后失败不能另记 toolFailed：用专门 commit-unconfirmed 退出当前执行栈，原 worker 标记静止；由现有 lease 接管读取同一个 binding/invocation 的提交事实。未提交则原上界重新查询，已提交则原结果。只有新 invocation 才获得它所在新 step 的上界。恢复不启动终态任务，撤权/flag off/过期时不返回旧正文。服务端 API 错误保留稳定安全 code，不回显 SQL/私有源/target 内容。

## 6. 额度、失败与保留

| 项目 | 固定上限或规则 |
| --- | --- |
| 输入 | JSON UTF-8 1024 bytes；深度 4；四个字段；seq 0..9007199254740991；before/after 0..4，省略为 0；session_id 36 ASCII bytes |
| 数值/文本 | 仅 JSON integral node；拒绝 1.0/1e0、字符串、bool、null、负数、溢出；无重复键、未知字段、trailing tokens、无效 UTF-8、未配对 surrogate；不做 numeric coercion |
| 日志 | 单事件完整序列化 <=16384 bytes；seq 实际 0..127，输入可接受更大 safe integer 后有界 not-found；最多 128 事件，正常最多 126 留终态/清理余量；可靠 SSE 另限 256，留 2 条收尾 |
| 查询/输出 | PK 最多 9 行、输入侧大 seq 先上界比较避免加减溢出；每侧 4；摘要 256 code points/1024 text bytes、1536 JSON bytes；输出 32768 bytes；无分页/后台任务/全文扫描 |
| 运行正文 | requestBody <=65536、checkpoint <=131072、已接纳 Response <=16384、summary <=8192 bytes；calls<=4，单 arguments<=1024、ID 为1..128 ASCII字母/数字/下划线/连字符；模型响应超限保留真实 usage/计费失败，不假称模型未调用 |
| 存储 | 8 MiB/task 独立 reservation `SESSION_HISTORY_STORAGE`；tenant history_storage_limit 默认64 MiB；累计 DB 正文 <=7 MiB、元数据 <=64 KiB，其余为收尾余量；任务/调用/step/dispatch/log/输出/上下文/清理收据全部副本实计，不仅算新表 |
| 次数/时间 | task deadline<=180s；最多8 model requests、20 tools、8 attempts、128 native events、256 SSE；查询2s、锁1s；预留不足新 task 429，不占旧 profile 普通 storage_held |

正文计数包含所有实际 UTF-8 副本；元数据沿既有 todo 的保守口径：每行32字节、每个非正文字段8字节开销加其 UTF-8 标量值长度（NULL 计1，UTC时间采用固定微秒文本），包括固定墓碑、模型/工具 pin、binding、保留摘要及 cleanup_receipt。正常变更元数据上限61440，终态/清理才可用65536。每次写入按旧/新字节差在原事务更新 body_bytes/metadata_bytes，不能让每次读取都扫描所有正文重新算账。读取日志只取9行（至多144 KiB原事件）及对应来源的定点PK记录，scratch总上限1 MiB。

owner API 未认证沿用 401；跨 tenant/owner/task 和不存在 session/seq 统一 404 `SESSION_EVENT_NOT_FOUND`，不带 session ID、范围、计数或存在性区别。已授权自身：legacy 无日志为 409 `SESSION_HISTORY_UNAVAILABLE`；过 retain_until 或 PURGED 为 410 `SESSION_HISTORY_EXPIRED`；未知绑定/事件版本为409 `SESSION_HISTORY_VERSION_UNSUPPORTED`；内容/来源损坏409 `SESSION_HISTORY_CORRUPT`；输入400 `TOOL_INPUT_INVALID`；正文/输出/存储/次数上限429 `SESSION_HISTORY_LIMIT`；查询超时504 `SESSION_QUERY_TIMEOUT`；provider故障503 `SESSION_QUERY_UNAVAILABLE`。特性关闭仅在工具执行/重放返回403 `SESSION_READ_DISABLED`，不关闭 owner 历史 API；actor/installation 撤权沿用现有安全拒绝。原 FENCE_REJECTED/CANCEL_REQUESTED/DEADLINE_EXCEEDED 不变。

retain_until=受理DB时间+604800s，所有本批正文副本不晚于该时间停止向模型/owner公开；无正文元数据墓碑不等于正文保留延期。超过期限但未知/在途事实未核清时继续占原有界容量，不能恢复输出或凭 TTL 释放资源。候选在 LIMIT 前排除非真正终态、任意历史 attempt 不静止、UNKNOWN/未结 reservation/dispatch/invocation，防不安全首项饿死其他可清理项；唯一例外是本 task 自己仍为 RESERVED、即将在本清理事务结算的 SESSION_HISTORY_STORAGE reservation，该已知存储预留不能反过来阻止自身清理。其他 kind 的未结占用及任何 UNKNOWN 均不豁免；再在原锁序事务内重检。

同一 DB 清理事务：标记 task history PURGED、清空所有原日志 event_snapshot（保留原 digest/bytes/seq/type/source）、所有 session read 输出与输入、task.goal/request_snapshot/summary、step.request/response、invocation.input/output、dispatch 中可能含正文的副本，保留原摘要/字节/来源和有界 cleanup_receipt；step.request_cleanup_state 用新 `SESSION_PURGED`，旧 digest 不重新计算成墓碑摘要。history 清理和原 reservation 唯一释放在同一事务，不减旧 model/tool 使用计数，不覆盖 UNKNOWN。清理出 `session_history.content_deleted` 可靠事件，不能向 native 日志写新正文。幂等重复清理只读原收据。

清理后元数据继续计费：按上面的口径精确计算留下的 metadata_bytes（<=65536），令 held_bytes=metadata_bytes，原 SESSION_HISTORY_STORAGE reservation 从 RESERVED 一次变 SETTLED、actual=held_bytes；tenant.history_storage_held 只扣 `8388608-held_bytes`。重复清理不再次扣减。保留墓碑期间不释放这部分占用，本批不新增墓碑删除 API；新 profile 的总元数据因此仍受64 MiB桶限制，旧 profile/其他租户不受影响。这里“唯一释放”指正文及未用余量，不声称墓碑占用已经物理删除。

flag off 时：停止广告/新受理、新模型/工具/成功工具重放；允许 owner 已有历史读取（仍须当前 actor/安装/pin/保留），保留真实 DB 事实并继续清理。重新启用不会把终态或 PURGED task 变 RUNNING；原幂等 key 清理后明确410，不新建 Session。V19 升级不回填旧任务；回退理解 V19 的制品+flag off，禁止 down migration。

## 7. 后端下一动作与完成条件

先将本包作为唯一契约入库/沿用本提交，创建或继续本项面向实际 master 的草稿 PR；只由产品经理核占用后交原后端实施。第一切片是 V19 + Schema/catalog 校验 + 受理日志/owner读取 + 原模型工具调用路径，必须可构建；两小时检查点给准确 SHA/PR 或已试动作/解锁条件/负责人。不要修改冻结基线或先搭通用 Session 服务。

| 验证编号 | 必须用同一准确源码/JAR验证的事实 |
| --- | --- |
| H01 | 私有 MySQL、真实 control/双worker、本地显式 paid=false 模型，经真实模型工具调用读 user.message/早先 model.response、前后摘要、正确来源/顺序；完成任务；保存 request/response/ledger 原证据 |
| H02 | 省略/显式当前 ID 等价；模型使用宿主 context 的 seq；SSE seq 不能直接当 native seq；边缘邻接为空；owner metadata/GET/SSE 重连不调模型/工具 |
| H03 | M1/M2/读取提交前/提交后强制退出重启，原槽同输出 digest/字节、同上界、一次工具消费和事件；新 step 新调用见新事实 |
| H04 | 原上界外并发追加及当前工具/同响应多槽均不可见；读 session.read 不含嵌套 output；故障注入 commit-unconfirmed 两侧均恢复唯一事实 |
| H05 | 两tenant、同tenant不同owner、同owner其他task、伪造session/source、错误step/slot/input/pin、旧fence、并发worker与邻接越界，全部拒绝且无泄露/旧结果覆盖 |
| H06 | actor/安装撤权与提交竞争、取消/截止/终态/flag off；成功重放先复核权限；owner终态历史可读且不唤醒执行；flag off保留/清理仍真实 |
| H07 | strict JSON 全边界：未知字段、重复key、1.0/1e0、超safe integer、null/bool/字符串、负数/4/5、Unicode、无效UTF-8/surrogate、额外query、missing/purged/unknown versions |
| H08 | 16KiB目标、8邻接/总字节、32KiB输出、摘要截断标记、稀疏大seq/PK EXPLAIN、2秒超时/锁、provider失败、全部副本额度/元数据/终态预留，均N-1/N/N+1；无静默目标截断/未授权fallback |
| H09 | known credential/签名秘密/私有配置/path/URL synthetic sentinel、隐藏reasoning/stream/provider异常不得出现在公开日志/结果/摘要/SSE/失败/诊断；脱敏标志和全部公开目标字节正确 |
| H10 | V18→V19真实升级，V1–V18及旧task/model/tool/checkpoint冻结字节不改；准确旧V18 JAR的control/worker严格拒绝19；flag off回退不丢历史 |
| H11 | 保留截止立即不可读；安全/不安全候选交错，所有正文副本清理、receipt/唯一释放、重试崩溃/双worker、UNKNOWN不释放；其他租户/旧profile无新额度阻断 |
| H12 | 按实际触及差异回归已交付workspace/working_directory、ask_user_question、todo、MCP和旧任务；不重跑旧完整矩阵，不修改旧失败记录 |

交付 SHA/tree/JAR SHA256、实际命令/退出码/测试数量、原始协议/账本/失败、清理收据和未覆盖项。原独审与原 QA 锁同一完整 SHA/JAR，分别交回；产品经理业务验收并按既有授权合并。GitHub CI 不运行、不恢复、不重跑、不等待；架构探针不代替 H01–H12，不宣称默认完整 verify 通过。无生产、真实 Key、部署、共享服务或外部通知操作。
