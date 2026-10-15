# P2 持久任务内核

事项 XIAO-35 / XIAO-37，承接 ADR-0004。P1 的只读 `/readyz` 装配仍可独立运行；设置 `jimu.durable.enabled=true` 才启用本文的持久内核。本实现仅面向单机受控验收，真实模型、公开网页报告、MCP、任意 workspace 工具和高风险动作没有启用。

## 运行与依赖

一个控制进程、两个独立 Java worker 共用专有 MySQL 8.0.46/InnoDB 和 POSIX 本机存储。控制进程运行 Flyway 迁移，worker 只 validate/check；V1 是任务账本，V2 是本机 worker 的 PID 与 Linux `/proc` 原始启动 tick 收据，V3 单独记录每个 attempt 的执行静止确认。V3 不从历史状态推断静止，旧 attempt 默认仍未知，须由原 worker 的 finally 或可信进程退出收据确认。运行账号只需本库 SELECT/INSERT/UPDATE/DELETE，迁移另用 DDL 身份。缺库、迁移不匹配或存储缺失不能领取；控制受理不依赖某一个 worker 在线。

沿用唯一 Spring AI 2.0.1 BOM，新增 client-chat、Spring JDBC、MySQL Connector/J 和 Flyway。client-chat 要求 networknt 3.0.1，SchemaContract 随之升级；保留 P1 的 Jackson 2 边界入口和原始权威 Schema 字节，Spring MVC 使用 Boot 管理的 Jackson 3。真实解析版本由 effective-pom/dependency-tree 留证。

`DurableExecutionIT` 在默认 `verify` 中必跑 `tools/p2_verify.py`，建立独立随机容器、HTTP fixture、ext4 临时根和 JVM 子进程，在 finally 收集日志、账本、计数和清理结果。Linux 需要 Docker/Python3/POSIX 存储；Windows 使用 WSL Ubuntu。WSL 没有 Java21 时仅在本次临时根下载并解包固定 Ubuntu JRE，不修改系统包或 alternatives。必需资源缺失直接失败，无可选 profile 或 silent skip。

```sh
./mvnw -B -ntp verify
# Windows: .\mvnw.cmd -B -ntp verify

# 仅用于快速定位第一条纵切，不代替完整验收
python3 tools/p2_verify.py target/jimu-0.1.0-SNAPSHOT.jar target/p2-slice --slice
```

完整报告在 `target/failsafe-reports/p2-evidence/`；包含独立 PID、task/attempt/step/invocation/effect/dispatch 关联、可靠事件、产物摘要、预算和 fixture 计数。JAR 复制到本次 ext4 根运行，避免 WSL 跨文件系统读 JAR 的额外开销。测试不接入共享数据库、存储或女娲资源；密码和签名密钥只写本次权限 0600 的配置，收尾删除，不进入报告。

手动运行时用独立权限配置文件，三个 JVM 分别指向自己的文件：

```sh
java -jar target/jimu-0.1.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:/private/control.properties
```

必需配置：`jimu.role=control|worker`、`jimu.durable.enabled=true`、`jimu.db.url/user/password`、`jimu.storage-root`、`jimu.identity.key`（至少32字节的 Base64 密钥）、`jimu.identity.subjects`（`subject:tenant:actor`，分号分隔）、`jimu.host-id`（同机控制与 worker 共用的 UUID）。control 另需 `jimu.db.migration-user/migration-password`；worker 另需 `jimu.provider-origin` 和 `jimu.fixture.credentials`（服务端 `tenant:credential` 映射）。`jimu.model-stream=true` 启用受控 provider SSE；同任务恢复时模式必须一致。JDBC 连接时区使用 UTC（测试 URL 固定 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`）。`jimu.test-mode`、`jimu.test.boundary/barrier` 只用于测试装配的文件屏障，不是 HTTP 接口。

## 身份与 HTTP 契约

本机测试 JWT 使用 HS256，校验签名、`iss=jimu-fixture`、`aud=jimu-api` 和整数 exp；sub 只能映射到服务端配置的 tenant/actor。请求体未知字段、假 tenant/actor、非整数预算和空能力均拒绝。没有把 body、模型参数、URL 或工具 callId 当身份。每次实际访问读取当前 actor/installation；没有跨租户发现、连接或对话缓存。

| 接口 | 行为 |
| --- | --- |
| POST `/api/tasks` | 必需 Idempotency-Key（1–128可见ASCII），body `{goal,capabilityIds?,resourceIds?,budget?}`。goal 1–8192 UTF-8字节；默认唯一能力 `web_fetch`；P2 的资源绑定在宿主配置，传资源句柄拒绝403。提交 task+预留+event 后才202，Location 指向查询。tenant/actor/key 相同且规范请求相同返回原任务，摘要不同409；配额429 |
| GET `/api/capabilities` | 仅当前 actor、tenant 的已启用安装；P2 的 capability key 为 `web_fetch`，对应固定目录插件 `jimu.web.fetch@0.2.0` |
| GET `/api/tasks/{id}` | 只读当前 owner 可见快照，未知/他人任务统一404；撤权403；产物只含 ID/类型/大小/hash/expiry |
| POST `/api/tasks/{id}/cancel` | 运行中202表示已提交 CANCEL_REQUESTED；无在途工作的排队任务可以直接200 CANCELLED；已终态200原快照，重复请求不增事件/重复释放 |
| GET `/api/tasks/{id}/events` | SSE envelope v1，eventId=`taskId:seq`；Last-Event-ID 严格校验。未来游标400，7天外游标410；回放不执行 Agent。每批/每次发送重验权限，撤权关闭连接 |
| GET `/api/artifacts/{id}/download` | owner授权代理下载，校验不可变快照hash/size，Content-Type/长度/ETag/安全文件名；未知/他人404，已过期410；不返回宿主路径或长期下载URL |

错误体为 `{code,message,traceId}`，不回显 SQL、cause、参数或秘密。API 仅安装在 control；worker 只开放 readiness。

可收紧预算为 `deadlineSeconds<=180`、`maxModelRequests<=8`、`maxToolInvocations<=20`，均正整数。没有配置价格/货币，传 `maxCostMicros` 明确400，所有费用质量保持 UNKNOWN，不虚构价格或零成本。tenant 默认队列16、运行2、模型128、工具320、token524288、存储16MiB；任务 token 上限65536，产物1MiB。配额是当前配置桶的累计上限，没有隐含按日重置。安装版本/Schema摘要在准入时再次核对。

## 持久边界和恢复

公开稳定契约集中在 `kernel.task.api`，避免为尚无独立职责的模块建立空实现；JDBC 适配器实现受理/查询/调度/调用账本用例，Spring 类型只在适配器。外部操作前后分别短事务，统一锁 budget→task，再检查当前 attempt/worker/fence/DB时间/状态。提交使用 revision/fence/lease 的条件更新且要求命中1行；租约30秒、10秒续租，过期租约不能复活。

只有一个 ToolCallingAdvisor。ChatModel 显式返回 ToolCallingChatOptions，DefaultToolCallingManager 关闭 resolver fallback，仅转换宿主绑定的 callback；模型本身只做受控 HTTP。JDK 的连接重试、全方法重试关闭，总尝试数设为1，避免 GET 在 EOF 时发生隐藏派发。框架附加上限20次，跨重启的权威限制来自账本。

| 边界 | 恢复依据 |
| --- | --- |
| M0 PREPARED | 规范消息/能力epoch/模型模式/摘要已保存，未派发可继续 |
| M1 RUNNING | 每次新派发单独预留；缺完整响应的旧派发保留 UNKNOWN，最多再派发一次同一步，不退已派发调用次数 |
| M2 RESPONDED | 完整响应及固定顺序/callId槽同事务落库，才允许工具准入；重启返回该响应，不再次请求本轮模型 |
| T0 PENDING→RUNNING | 持久稳定 invocation/effectIntentId、输入摘要、版本/Schema、当前epoch和预留；未存意图不执行 |
| T1 SUCCEEDED | 校验 canonical 输出Schema/大小/hash并提交后才返回框架；恢复同一槽直接读结果，插件计数不增加；损坏记录停止，不能重调工具掩盖 |
| M3 后继检查点/终态 | 用响应和每槽结果确定性重建后继历史；终态核对无未决 invocation、产物已发布和未先受理取消 |

call 和 stream 都走同一 advisor。受控 provider SSE 在有界 HTTP 交换内完整聚合，要求 finish_reason 和 `[DONE]`，完成后才提交响应并向 advisor 提供完整结果。没有把 delta 作为恢复事实，也没有提供逐 token 用户展示。多工具响应逐槽串行准入，部分成功只恢复成功槽，剩余槽单独处理。

token 预留使用受控模型请求检查点 UTF-8字节数+4096 的保守上界（含固定协议/Schema和2048输出上限），同时受 tenant/task 上限约束；provider 给出有效 usage 后结算，否则保留 HELD_UNKNOWN，actual 为 NULL。模型/工具次数在派发时消费，不因失败/取消退款。工具结果、状态及可靠事件同事务提交；event seq 由锁内 task 分配，fact 唯一键防重复。

## 取消、进程收据和未知效果

取消与完成锁同一任务、按提交顺序收敛；取消先提交后不准入新模型、工具或发布。已准入结果允许记录真实事实，但不能令 task 成功。

V2 注册同机 worker 的随机 instanceId、PID、Linux `/proc` 原始启动 tick 和 host UUID。控制角色只检查本 host 的已注册进程；启动 tick 不符或读取失败保持未知，不标记停止、不发信号。相同启动 tick 的内核 Z/X 状态证明执行已退出，无需外部父进程先 reap；`/proc` 记录不存在且 ProcessHandle 也不存在才认定进程已消失。取消超过3秒仍未退出执行的专有 worker 由 supervisor 按准确 PID 发出强停信号，下次读取内核事实后才确认停止；没有进程名扫描或等待兄弟 JVM 的 onExit。恢复扫描可以据此立即使旧租约失效。该实现依赖同机进程可见性，不支持跨主机 supervisor。

RUNNING 工具缺成功事实时，即使进程已死也不能推断效果没发生：保留 OUTCOME_UNKNOWN、task.NEEDS_RECONCILIATION，禁止自动重试。网络响应丢失的受控假服务同样如此。宿主将稳定 effectIntentId 作为关联头传至受控服务；测试按该 ID 查询真实效果计数，此头不承诺服务端去重。V3 的 attempt.quiescent 仅由同步执行已经退出的 finally 或 supervisor 的可信退出确认写入；过期旧 fence 也可确认自身 attempt 静止，不能改写任务事实。所有历史 attempt 都确认静止后，终态才幂等释放运行槽；安全取消也等待所有历史写者静止，未知工具/费用预留仍单独占用。deadline、权限失败等可以先记录 FAILED，但运行槽及存储保护保守持有。没有以 EXPIRED、STOPPED、无 ACTIVE attempt、TTL、interrupt 或取消202作为停止证明。P2 提供待核对事实，人工核对后的业务裁决接口尚未开放。

## 事件与产物

一个 control 最多32个 SSE 订阅，每 actor/task 最多2个；每批64个固定小 envelope（无模型正文/凭据，低于256KiB）。读者慢/超时只结束订阅，客户端按最后 seq 重连；单连接25秒后正常轮换，不取消任务。终态连接先排空全部已提交事件再结束。DB 是回放依据，进程通知和内存不承担可靠性。

产物先登记 STAGING/uploadId，再在事务外写专有临时文件、fsync、计算摘要，以 hard-link create-if-absent 建立不可变快照并 fsync目录；核验后 VERIFIED，当前租约/权限/取消检查通过才 PUBLISHED+artifact.created。重试沿用同一 artifact/upload/hash，授权下载再次验证对象，公共消息不含路径。

未发布对象可由 uploadId 追踪。任务已 FAILED/CANCELLED、全部历史 attempt 已可信确认静止、至少1小时后，维护器标记 ABANDONED，在事务外删除该 upload 的对象，再幂等释放存储预留；删除失败保留可重试状态。发布对象7天到期后也须等待全部历史写者静止才 EXPIRED 并回收，避免旧写者晚落盘形成退款孤儿。未知效果/未知写者不会被此清理器自动删除。

R5/R6 专项入口：`python3 tools/p2_verify.py target/jimu-0.1.0-SNAPSHOT.jar target/writer-stop-evidence --writer-stops`（Linux/WSL）。同一场景必需纳入默认 verify：A0/A1 老化、deadline/lease 竞争、撤权失败、历史旧写者跨成功恢复，以及不 poll/wait/reap 的僵尸取消。夹具保存各阶段 PID/start tick/host、task/attempt/fence、预留、对象数和发布事实。新增窗口使 IT 有界上限调整为24分钟、CI为30分钟；缺资源或断言失败仍非零退出。

最终验收命令、固定提交、实际断言数量和限制见 XIAO-37 的交付记录；本文说明行为，不替代独立审查、测试或产品验收。
