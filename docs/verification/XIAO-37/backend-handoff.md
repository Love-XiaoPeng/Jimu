# XIAO-37 P2 实现交接

本交付承接 XIAO-35、ADR-0004 与 XIAO-36 架构交接。PR 为 https://github.com/Love-XiaoPeng/Jimu/pull/3，base 为 master，起点为 `be5d8f1a7d687581c434f73c2548ed6925946bac`。不合并 PR，不把后端自测视为独立测试或产品验收。

运行方式、配置、API、预算口径、迁移及恢复行为见 `docs/implementation-p2.md`。本轮运行代码在 `41cd6de6e187391d8bebbea25dcb6132c568e6b4` 固定；测试入口在 `94a94ec65790fb0401a834a32842b786eeeb8c26` 固定。最终交付提交在这之后只增加本记录，运行代码与测试入口不变。

## 首轮审查修复映射

| XIAO-38 问题 | 修复与回归证据 |
| --- | --- |
| 隐式 HTTP 重派 | 启动时关闭 JDK 连接/全方法重试，总尝试数固定 1；成功工具在 T1 恢复时计数不增加；响应丢失保留 OUTCOME_UNKNOWN。`fake service effect occurred once despite lost response`、`queryable service reconciles stable effect intent`、`unknown outcome remains single effect after worker restart` 记录真实计数与稳定 effectIntentId |
| 插件固定版本/摘要未绑定实现 | 网关将实际 descriptor 的版本/摘要与 invocation 和当前 installation 同时比较，缺失安装拒绝。覆盖版本漂移、摘要漂移、预先错误版本、崩溃恢复后的摘要变更和已绑定 callback 遇安装删除，均在效果前停止 |
| 取消过早释放槽 | CANCEL_REQUESTED 不由另一 worker 按过期租约直接终结；模型发送前复查准入。V2 以本机 PID、Linux `/proc` 原始启动 tick 和 host UUID 记录进程，supervisor 确认准确实例退出后收敛。回归暂停 control、使租约到期、启动竞争 worker，验证旧 worker 仍活时保持取消意图与运行预留，放行旧 dispatcher 后 HTTP 请求数为 0；另有 A1 非协作写者停止与对象清理 |
| 终态 SSE 多批截断 | 终态先排空已提交事件，再关闭订阅；115 条事件跨 64 条批次完整有序回放，Last-Event-ID 续传及 control/MySQL 重启后回放均不重启 Agent |

## 默认验证与原始证据

2026-10-15 22:56（Asia/Shanghai），在 `94a94ec65790fb0401a834a32842b786eeeb8c26` 上执行 `.\mvnw.cmd -B -ntp verify`，退出 **0**，耗时 **11分23秒**。JUnit **32项、0失败/错误/跳过**：P1 单元/契约29项与独立进程2项保留，P2必需集成1项内执行 **391条通过断言**。格式、Checkstyle、Enforcer、包边界、来源/Schema及 SpotBugs 均通过，后者为0 bug/0 error。上表四项修复和下表当前范围均已由本次默认入口实际运行通过。

验证 JAR SHA-256：`3fdd38eab650aeff440f71601737d6aa82b083249b64a783c0cb69081b4658bb`。构建 JDK21、Maven Wrapper3.9.9；运行 JRE为OpenJDK21.0.12.1，私有deb SHA-256 `951bb9b9337f1af8568bdf8d8ae7a6d4ff837838c4987f5b5d077ef397fba375`。MySQL实际8.0.46，存储实际ext4。`cleanup.json` 的 containerRemoved/rootRemoved/allChildrenExited/fixtureStopped 全为true，failures为空；P1独立进程也已收齐。

`help:effective-pom` 与 `dependency:tree` 证据命令退出0；实际解析 Boot4.1.1、Spring AI2.0.1、Spring JDBC7.0.9、MySQL Connector/J9.7.0、Flyway12.4.0、networknt3.0.1、Jackson2.21.7与3.1.7。最终交付head仅补本记录，运行代码、测试、POM与CI配置与上述被测提交一致。CI使用相同默认verify入口，新head的外部CI状态以PR为准，不把本地通过写成CI已通过。

默认入口始终为 `./mvnw -B -ntp verify`，Windows 为 `.\mvnw.cmd -B -ntp verify`。`DurableExecutionIT` 内的 Python 断言不是额外的 JUnit 测试数量，不将快速纵切或定位验证冒充完整验证。

新增完整矩阵后，P2子进程总超时为14分钟，CI job为18分钟；每个场景的边界/计数断言保持原要求。测试客户端显式直连 loopback，不继承桌面 HTTP 代理；WSL 本轮环境的默认代理未绕过 `127.0.0.1`，前一次连接超时已经由隔离客户端修正，不能当作恢复断言通过。

默认矩阵覆盖：持久幂等受理和跨 control 重启；M0/M1/M1_RESPONSE/M2/T0_BEFORE/T0/T1/T1_BEFORE/A0/A1/A2/M3/DONE；多工具部分成功；call/stream 完整响应与部分 SSE 中断；取消/成功顺序和旧 dispatcher；两个 JVM 的同租户争抢及两租户并行隔离；身份伪造、所有者/能力/凭据/资源隔离和撤销；队列/运行/模型/工具预算及 unknown usage；可靠事件回放；产物摘要、篡改、到期、重复发布和中断清理；错误迁移/检查点版本、数据库/存储/provider 缺失；迟到旧写者和不可重试未知效果。

原始证据随事项交付附件提供：完整 Maven 输出、JUnit XML、SpotBugs、effective POM/依赖树、每个子 JVM 日志、`assertions.json`、`fixture-counts.json`、各账本 TSV、`cleanup.json` 和 SHA-256 清单。账本关联 tenant/task/attempt/worker/fence/step/dispatch/invocation/effect/事件序号及产物摘要；只有受控 fixture 数据。密钥、数据库密码、运行配置、JDK 解包和数据库数据不进入附件。

## 要求、实现与可执行覆盖矩阵

各行均由默认 `verify` 内的 `tools/p2_verify.py` 执行。实际结果统一填写在上方验证记录，不能以代码存在替代运行证据。

| 要求 | 实现位置 | 可执行验证/证据键 | 范围与限制 |
| --- | --- | --- | --- |
| E1 持久受理、幂等、无内存假成功 | `api/TaskController`、`adapters/persistence/JdbcLedger.accept` | `idempotency survives control restart`、6并发同key、摘要409、MySQL重启后查询 | tenant/actor/key 唯一；数据库失联503 |
| E1 中性检查点与完整响应 | `DurableAgent.Session.call`、`JdbcLedger.prepare/dispatchModel/modelResponded` | M0/M1/M1_RESPONSE/M2；`partial model delta cannot create invocation`、`SSE without DONE is not a response` | call/stream 同一 advisor；不提供 token 展示 |
| E1 成功工具不重放、多槽部分恢复 | `DurableAgent.Session.invoke/manager`、`JdbcLedger.admit/toolSucceeded` | T0_BEFORE/T1/multi-T1；每槽计数及输入/输出摘要；`successful tool not replayed by second worker` | 固定内置 web.fetch；不启用任意工具解析 |
| E1 固定插件与短事务 | `JdbcLedger.admit/fenced`、`DurableAgent.invoke`、`BoundaryProbe` | pin版本/摘要/缺失安装/恢复漂移；各外部屏障 `information_schema.innodb_trx=0` | 比较实际 descriptor、持久 invocation、现行 installation |
| E1 未知效果与只读核对 | `JdbcLedger.uncertain/claim`、`ConfiguredOriginReader`、`LocalArtifactStore` | T0/T1_BEFORE、丢响应计数1、按effectIntentId查询、重启后仍1；不可覆盖产物恢复 | 受控服务查询与本机不可变文件；没有通用外部写重试或人工裁决 API |
| E3 可信身份与租户/owner隔离 | `SignedIdentity`、`TaskController`、`JdbcLedger.identity/owner/access` | 伪造/过期401、自报身份400、跨owner查询/取消/事件/下载404；两租户并行 | 生产身份未接入；身份映射由服务端配置 |
| E3 能力/凭据/资源与撤销 | `JdbcLedger.epoch/access`、`DurableAgent`、`DatabaseEventFeed` | foreign-origin准入拒绝、foreign-tenant-resource不泄密、未授权工具拒绝；旧callback、SSE、产物撤权；tenant-b发现不受tenant-a撤销污染 | 无额外能力/结果缓存，检查点不跨任务复用；受控连接凭据按tenant绑定 |
| E3 原子预算及unknown usage | `JdbcLedger.accept/claim/dispatchModel/admit/stage/reserve` | 队列202/202/429、两槽三任务、模型/工具N后下一次拒绝、token到限零HTTP、存储到限零产物、unknown预留、终态对账 | 当前桶累计配额；未配置货币时maxCostMicros明确400，不声称真实计费验收 |
| E4 租约、fence与迟到提交 | `JdbcLedger.fenced/renew/claim`、`WorkerLoop`、`LocalWorkerSupervisor` | 两worker竞争、fence递增、旧写者FENCE_REJECTED、成功事实不被覆盖、确认退出后释放占用 | 同机Linux进程可见性；不把跨主机停止视为已实现 |
| E4 取消、完成、deadline与停机 | `JdbcLedger.cancel/stop/processStopped`、模型发送前checkpoint | T0_BEFORE/T1取消、重复取消、成功后取消；M1存活旧worker/过期租约/控制暂停；A1强停；过期deadline零派发 | HTTP202只表示意图；未知效果保持待核对 |
| E4 SSE断流、回放、撤权与上限 | `DatabaseEventFeed`、`JdbcLedger.events`、`TaskController.events` | 115条多批有序回放、实时断连及control SIGKILL后续传、DB重启、坏/未来/过期游标、撤权断开、actor/task第3个429、全局第33个429、断连回收 | 32订阅、批64、有界字段；停止读取的客户端用于配额测试，不冒充生产网络压力测试 |
| E4 产物完整性、活动写者与清理 | `LocalArtifactStore`、`JdbcLedger.stage/verified/publish/cleanupArtifacts`、`LedgerMaintenance` | A0/A1/A2/M3/DONE、单对象/单事件、hash下载、篡改/符号链接503、过期410；活动老化upload不删、确认停止后清理并退款 | POSIX专用根；不开放任意宿主文件路径 |
| E4 缺资源、迁移/负载版本不兼容 | `LedgerProbe`、`JdbcLedger.check/step`、`WorkerLoop` | 缺库/缺存储/未来迁移启动拒绝且匹配原因；DB失联503；provider/存储失联拒绝领取；record_version=99停止且零工具 | 无可选跳过或共享资源回退；清理由finally及进程收据留证 |

## 范围与复验

- 使用专用 MySQL 8.0.46/InnoDB 镜像 `mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`，随机隔离容器、WSL ext4 根、一个 control 和两个独立 worker；故障场景使用额外的替代实例，全部在本轮前台收齐。
- V1 任务账本保持首次提交的已发布字节，V2 增加本机进程收据；运行身份仅 DML，迁移身份另配。没有接入或修改共享服务、数据库、FRP 或女娲资源。
- 唯一执行循环为 Spring AI 2.0.1 ToolCallingAdvisor；受控 HTTP 模型只证明协议和持久恢复。真实模型、P3 公开网页报告、MCP、任意 workspace 工具和高风险动作未验收。
- supervisor 和不可变产物实现依赖同机 Linux/POSIX、进程可见性与专用存储；不承诺分布式进程停止。未知效果提供稳定 ID、查询证据与待核对状态，人工业务裁决 API 未开放。
- token 预留为受控请求的保守估算；usage 缺失保持 UNKNOWN，未配置价格时不虚构货币成本。SSE 提供完整响应后的可靠事件，不提供逐 token 展示。
- XIAO-38 / XIAO-39 应在同一最终 head 上复核四项修复及完整 E1/E3/E4；尤其复验取消竞争、隐式重派、插件 pin 和多批回放。独立审查、测试及产品验收结束前不合并。
