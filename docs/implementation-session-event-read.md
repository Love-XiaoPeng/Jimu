# 授权的原生会话事件读取

本实现接续 ADR 0011 与 `docs/verification/XIAO-52/architecture/implementation-contract.md`。一个新 task 是一个 session；attempt 接管不创建另一个 session。旧 task 没有原生日志，不回填或从 SSE/checkpoint 推断正文。

## 启用与受理

`jimu.session-event-read.enabled` 默认 `false`。control 与 worker 都必须显式启用。关闭时不广告、不受理、不执行或重放工具成功结果；已保留的 owner 历史 API 和维护清理继续有效。

只支持 `capabilityIds=["session_event_read"]`、空 resources、无 images 的独立 profile `local-session-event-read-v1`。沿用现有本地模型配置，必须显式 `paid=false`、test-mode、trusted-local-v1、loopback endpoint 和完整请求输入上界 proof；不允许内置 fixture 回退、business、evaluation 或 execution scope。保留现有更紧的 token 限制，transport response-bytes 不大于 16384。任务上限为 180 秒、8 模型请求、20 工具、8 attempts、每响应 4 calls。

安装键是 `session_event_read`，manifest ID 是 `jimu.session.event_read@0.1.0`。version/digest/epoch 与受理时 ToolSnapshot 一起固定。新的窄插件 port 只接受本次调用参数；host 闭包绑定 lease、step 和原响应 slot，不把 SQL、身份或宿主配置交给插件。

V19 新增独立 `history_storage_held/history_storage_limit` 桶及三张表。授权 SQL 生成器已包含新表的 SELECT/INSERT/UPDATE 契约；本次只在测试自己创建的私有数据库使用测试授权，没有执行共享或生产授权。回退使用理解 V19 的制品并关闭 flag；旧 V18 制品严格拒绝 schema19，不做 down migration。

## HTTP 与工具行为

请求示例：

```json
{"seq":0,"before":0,"after":4}
```

`session_id` 省略时为当前 task；显式指定当前 ID 等价。其他 task 即使同 owner 也返回 `SESSION_EVENT_NOT_FOUND`，不查询其是否存在。seq 为零起始原生序号，与正数 SSE 游标无关。参数最多 1024 UTF-8 bytes，严格拒绝重复键、未知字段、trailing token、coercion、浮点/指数数字、未配对 surrogate；seq 为非负 safe integer，每侧邻接 0..4。

每次新 M1 在 checkpoint 中携带宿主 `session_event_context`，固定原 `upper_seq`、user_message_seq=0 和最近最多八个真实引用。prepare 保存上下文和 binding 摘要；切点冲突拒绝。恢复复用原 checkpoint、step 和上界，不扩大窗口。当前步骤的模型响应、同响应前槽工具收据和之后追加的事实不进入原窗口。

owner API 沿用 SignedIdentity、tenant/actor/owner 与当前安装 pin 校验：

| GET | 返回 |
| --- | --- |
| `/api/tasks/{id}/session-history` | 保留状态、上界、最近引用、同切点 SSE seq 与保留截止；无 query/body |
| `/api/tasks/{id}/session-history/{seq}?before=0&after=0` | 完整已存公开 target 与有界摘要；`boundary=owner-snapshot` |

工具使用 `boundary=pre-step`。owner 读取不消费模型、工具、原生日志或历史预算，不恢复执行；终态历史仍可读。响应为 `Cache-Control: no-store`。未认证401；跨 owner/tenant 或缺失404；旧 task409 `SESSION_HISTORY_UNAVAILABLE`；过期410；未知版本409；来源损坏409；严格输入400；容量429；查询超时504；provider故障503。先授权，再报告输入和范围错误。

新增原 SSE 类型 `session_history.changed` 与 `session_history.content_deleted` 只作无正文失效通知，信封格式不变。客户端先 GET metadata，再按其 SSE 切点订阅；断连/410 后重新 GET。

## 公开正文、事务和存储

原生事件仅有 user.message、step.started、model.response、session.read 收据、turn.ended。各事实与原状态写入在同一数据库事务内，以唯一 fact_key 去重。目标完整返回已存 `public-v1` 事件，不声称与原始用户/模型文本相同；隐藏 reasoning、协议 headers、usage、配置、路径等不通过整对象复制进入事件。

public-v1 在捕获时先最长优先替换 runtime 已知敏感字面值，再按 Unicode White_Space 和 FEFF 分词，对敏感关键词、含正反斜杠的 token 及其后一个非空 token 同时脱敏，空白保留。任何替换设置 redacted。该保守规则也会隐藏普通 URL/路径和部分普通词。最终 summary 若需要该转换则拒绝完成，避免另一个不安全公开副本。

原槽 readOrResult 在既有 budget→task→authority 锁序内校验当前权限/fence/租约、step、原 Response v2 数组槽、输入摘要、pin 和 insert-only read binding。PK 窗口最多九行，逐条核对版本、task/seq/source、来源摘要与可靠事件。成功/确定失败、一次工具消费、结果、绑定、原生收据与可靠事件原子提交。成功重放先校验当前授权和保留，只返回原 output 字节，不重查 target。session.read 只保留输出 hash/bytes，不嵌套工具结果。

通用 admit/toolSucceeded/toolFailed/uncertain 禁止绕过 session 原槽事务。数据库提交确认不确定时抛专用 `SessionCommitUnconfirmed`，退出当前执行栈并由 lease 接管核对实际提交事实，不补写普通工具失败。已调用模型的真实已知 usage 即使响应输入被拒绝、权限变化或 flag 关闭仍结算。

每次查询从取得连接之前开始共享一个2000ms单调时钟截止。项目现有 DriverManagerDataSource 及其装饰层仅在本次 session 查询内选择截止感知的 Connector/J socket factory；其他 profile 使用原数据源。DNS 解析只在有界的8个独立只读解析槽内运行，调用方只等待剩余预算；迟到的 DNS 不得建连或执行 JDBC。TCP、greeting、认证、TLS、驱动初始化及事务共享同一截止，到期直接关闭本次查询的全部物理 socket；所有 JDBC 和提交仍在调用线程同步退出，没有可在返回后继续执行的 JDBC 写者。

语句继续使用剩余 MAX_EXECUTION_TIME、query/socket timeout，InnoDB lock wait 最多1秒，并在提交前重新核对单调时钟与 lease 条件。建连/已回滚的超时映射504；提交确认异常仍走专用不确定恢复，不映射成普通工具失败。owner 查询到期返回504，立即的连接故障仍为503。连接会话设置恢复，事务内无网络模型/文件调用。平台 DNS 若不响应中断，解析线程可占其原槽至真正退出，但它不能取得连接或成为迟到写者；本批未注入系统 DNS 停顿或所有 TLS/网络故障时序。

事件最大16KiB，输出最大32KiB；邻接摘要最多256码点/1024 UTF-8 bytes，带真实 summary_truncated 标记。无全量日志回退或分页。一个 task 预留独立8MiB、正文最多7MiB；tenant 默认历史桶64MiB。正文采用实际写入差额记账，包含 task/request/checkpoint/response/arguments/output/usage/native event 的全部副本；固定 model/tool/policy pins 归为元数据。元数据按合同的行/字段/UTF-8 标量口径计数，并为结算、终态及未来 hash/length 清理收据留出容量，预检不足时在模型响应准入前拒绝。

保留截止为受理 DB 时间加7日；过期立即停止正文披露。原维护器在 LIMIT 前排除不安全候选，并在同锁序事务重检真正终态、全部历史 writer 静止、无 UNKNOWN/其他未结占用。清理在一个事务内去掉所有正文副本，保留原 hash/length/source、metadata tombstone 和 cleanup_receipt；唯一结算存储 reservation，只释放8MiB减实际墓碑元数据，重复维护不重复扣减。UNKNOWN 和在途事实继续占原预留。

验证入口与证据边界见 `docs/verification/XIAO-52/backend/README.md`。GitHub CI 未运行、启用、重跑或等待。
