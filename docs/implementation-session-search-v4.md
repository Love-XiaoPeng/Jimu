# 显式授权的既往会话检索

XIAO-55 依据 accepted ADR-0014 和 `docs/verification/XIAO-55/architecture/implementation-contract.md` 实施。范围通过不代表产品验收；PR #20 保持草稿，事项进行中。固定上游、MIT 来源、Schema 和兼容差异沿架构交付；架构目录原来的探针及包清单是历史证据，不作为本实现的产品验证。

## 接入

默认 `jimu.session-search.enabled=false`，另须启用既有 `jimu.session-event-read.enabled`。仅支持显式本地、`paid=false` 模型配置，沿原有模型输入证明和限制。执行 profile 是 `local-session-search-v4`，工具固定为 `session_event_read@0.4.0` 和 `session_search@0.1.0`。模型仍使用唯一宿主循环，180 秒、8 次模型请求、20 次工具调用、每响应最多4槽。

新 binary 要求 schema22，理解22且关闭 flag 的 binary 是回退制品；不降库、不 repair 历史 checksum。单版本 installation 的版本/epoch/digest 切换会使旧 pin 失权，不能用于同时保留旧 profile 的运行授权。本次仅同步私有夹具和 application_grants 生成器，不修改共享安装或配置。

| owner 接口 | 行为 |
| --- | --- |
| `POST /api/session-search/tasks` | 认证及 `Idempotency-Key`；JSON `{"goal":"…","history_session_ids":["规范UUID"]}`，最多16384字节，来源0–8个唯一UUID |
| `POST /api/tasks/{caller}/session-history/session-search` | `session_search` 输入，最多4096字节；先核整个原 scope，再读 body；认证、输入、DB、计算共用2秒绝对截止 |
| `DELETE /api/tasks/{caller}/session-search-scope` | 先核 owner；无 query/body；幂等204、不可恢复，flag off 仍可撤销 |
| 既有 task GET / session-history / events | v4 分派；每次公开前复核整个 scope，失权 summary=null、artifacts=[]，状态/取消仍依既有身份规则可用 |

宿主在原准入事务内核权、排序锁定并冻结 scope、来源上界、原 pins 和最早保留期。仅接纳同 tenant/owner、当前 actor/pin 有权、已终结且静止、无 UNKNOWN、空 scope 的独立 v4 来源。旧历史、活跃和依赖历史来源本批不支持。空 scope 用于产生独立来源，查询真实返回空，不授予全 owner/tenant 权限。

工具只在冻结的来源中查公开 user.message/model.response 内容；每来源最强一条，稳定排序，最多4个代表，真实匹配会话多于4才 capped。采用 `public-content-literal-v1`，保留与 FTS5 unicode61 的差异。合法提供 `parent_session_ids`、`include_root_sessions`（包括 false）、`availability` 或 `event_surfaces` 均明确400 unsupported。其他支持字段、严格数值/UTF8/时间范围与硬界限见冻结契约和 v4 资源。

后续 read 独立复核原 scope 和当前来源，不由命中结果产生授权。caller read 限原 M1 切点；来源 read 限准入上界。原 invocation 固定输入、scope、context、cut、输出和可靠收据；成功重放核当前授权后返回原字节且不再消费工具。未知提交确认沿 `SessionCommitUnconfirmed` 恢复；provider 损坏、故障和超时回滚。

REVIEW-55-01 修复保持 read refs 的完整事件 SHA256：收据公开读取及成功重放校验原切点内最多9条直接来源事件的完整字节/digest，再分别比对完整 target 和由来源事件按原规则生成的邻接摘要。摘要复用原有256码点/1536字节限制，不将摘要 digest 冒充事件 digest；遇到旧 read 收据只核直接事件，不递归展开其输出。旧收据字节无需迁移。

同轮实测发现 `Map.of` 的 JSON 字段顺序可跨 JVM 改变。原槽 normalized snapshot 继续按已存原字节核验 digest/bytes，再按 JSON 字段值与重新核定的输入/cut 比较；不重写旧快照或输出，不将进程内字段顺序误作契约。

REVIEW-55-02：模型上下文读取复用 owner read 的安全错误映射，锁等待/查询超时返回 `SESSION_QUERY_TIMEOUT`，其余数据库/事务资源故障返回 `SESSION_QUERY_UNAVAILABLE`，不把 JDBC SQL/原因链传给 worker 日志或误记 `EXECUTION_FAILED`。仍采用同一事务、1秒锁等待和2秒绝对截止；不增加重试、不保证资源争用下所有查询成功，不改变任务既有失败收尾或已提交结果。私有锁等待采样与受控持锁探针用于区分正常锁竞争、资源拒绝及错误映射；受控复现不能证明独审已销毁实例中原持锁者的身份。

## 保留与配额

caller 保留期不晚于任一来源。任一来源失权/到期、显式撤销或已经记录的失效，立即阻断全部衍生正文入口，包括模型请求/恢复/成功重放、owner 历史和 SSE。观察到的失权在原事务完成后单独记录 invalidated_at，避免因原查询回滚而恢复授权；所有操作仍受原绝对截止约束。安全静止前保留正文并继续计费，不释放 UNKNOWN 或活写者。

新增 scope、target ID/快照、step context、normalized query 和 read refs 均按真实字节计入 BODY；墓碑及 reservation 本身计入 metadata。安全清理清空全部旧/新 BODY，以 metadata=held=reservation.actual 固定点精确释放一次。后台 scope validation 有界、公平推进；公开入口不使用其60秒发现间隔作为宽限。

默认 tenant history quota 仍为64MiB，每 task 预留8MiB；本次不扩大默认值。容量夹具明确使用独立私有配额，并另核64MiB的真实429。

## 本地验证入口

固定源码、tree、JAR 摘要、实际命令/退出码、原始协议/账本、失败和清理收据通过事项附件交付；未执行的用例不记通过，开发自测不替代独审/QA/业务验收。

- `PriorSessionContractsTest`：v4资源逐字节绑定、严格输入、代表排序和累计预算交接。
- `tools/prior_session_verify.py --jar … --v21-jar … --evidence …`：真实私有MySQL V21→22、双worker、本地HTTP模型、原槽故障、撤销及配额等实现验证。
- `tools/prior_session_read_verify.py --jar … --evidence …`：REVIEW-55-01 定向实测；零/单边/双边及边缘裁剪邻接、Unicode摘要、收据公开读取、嵌套收据不展开、篡改拒绝，精确READ invocation/step的丢确认、成功重放及新fence接管和单次消费。`--expect-bug` 仅在旧JAR上确认409缺陷，exit0不表示产品通过。
- `tools/prior_session_lock_verify.py --jar … --original-jar … --evidence …`：保持六 caller 同时排队及真实双 worker，采集私有 MySQL 的等待者/持锁者/事务/历史 SQL；随后同一真实 lease 在受控预算锁下比较原制品 JDBC 异常与新制品安全504，核验截止、零消费、释放后恢复读取及任务完成。原制品复现成功不算产品通过。
- `tools/prior_session_legacy_verify.py --jar … --evidence … --case read --case search --case trace --case todo --case questions --case directory --case workspace-mcp`：复用已有受监督夹具的必要旧能力切片；不重跑旧完整矩阵。

WSL 夹具须显式设置 `JIMU_IT_JAVA` 为已核本地 Linux Java21。只使用私有 MySQL、paid=false HTTP 服务与合成数据，前台收集并清理全部子进程。不运行、启用或等待 GitHub CI；原取消/失败/未完成记录保持原样。
