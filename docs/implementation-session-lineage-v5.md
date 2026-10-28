# v5 显式继续关系与 session_trace

本批由 XIAO-126 接续 XIAO-125 的原工作树。实现范围依据 ADR-0016；当前验证结论以本批验证记录为准，接口存在不表示全部验收已通过。

配置默认 `jimu.session-trace.enabled=false`。启用 v5 还须显式开启 `jimu.session-event-read.enabled`、`jimu.session-search.enabled`，使用现有本地 paid=false 模型输入证明配置。模型执行上限仍为 180 秒、8 次模型请求、20 次工具调用、每响应 4 槽。任务工具固定为 `session_event_read@0.5.0`、`session_search@0.2.0`、`session_trace@0.1.0`，权威资源位于 `src/main/resources/session/v5/`。不自动更改共享 installation；同能力单版本 pin 的切换按既有规则使旧任务失权。

认证 owner 使用 `POST /api/session-trace/tasks` 和 Idempotency-Key 创建任务：

```json
{"goal":"继续核查","history_session_ids":["00000000-0000-0000-0000-000000000001"],"parent_session_id":"00000000-0000-0000-0000-000000000001"}
```

示例 UUID 仅表示请求形状，实际须是当前有权、终态静止且闭包完整的 v5 来源。历史列表必填，可为空，最多 8 个唯一规范 UUID；parent 可省略，有值时必须是列表成员。列表排序不影响幂等语义，parent 或范围变化返回 409。goal UTF-8 最多 8192 字节，body 最多 16384 字节；拒绝 null、重复键、额外字段和 query。

模型工具和 `POST /api/tasks/{caller}/session-history/session-trace` 使用 `{}` 或 `{"session_id":"规范 UUID"}`，最多 1024 字节。省略 target 指 caller；输入不能携带 seq、depth、cursor 或授权事实。owner 查询先核 caller 和 whole scope 再接收正文，最终观测仍使用同一 2 秒绝对截止。结果为 JSON/no-store，包含最近优先 ancestors、按 created_at/UUID 排序的 DFS descendants 和各自 depth；只报告 admission-scope。

`DELETE /api/tasks/{caller}/session-trace-scope` 无 body/query，owner 幂等撤销原 scope，返回 204。feature flag 关闭时 owner 只读和撤权仍可用；模型执行和成功重放禁止。scope 或任一来源失效后，summary、SSE、历史、模型恢复与原槽结果均须停止公开衍生正文。

V23 只扩展历史/event 版本并增加两张 lineage 表，不修改 V1–V22、不回填旧历史。理解 schema23 且 flags off 的同版制品可回退；旧 schema22 制品在 readiness 前拒绝23，不能直接降级。两新表的 INSERT/UPDATE 最小 grant 已加入本地 grant 生成器。

必要验证命令：

```text
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp test -Dtest=PackageBoundariesTest,DurableAssemblyTest,SessionLineage*Test,SessionCallBindingServiceTest,SessionScopeEligibilityServiceTest,SessionMetadataAccountingServiceTest
python3 -B -u tools/session_lineage_verify.py --jar target/jimu-0.1.0-SNAPSHOT.jar --evidence <private-evidence-dir> [--v22-jar <exact-baseline22.jar>]
```

真实夹具要求 Linux `/proc`、现有 Java21（`JIMU_IT_JAVA`）和私有 Docker MySQL8.0.46；不会安装运行时或接共享数据库。夹具前台收集 HTTP、模型 wire、SQL 证明及 cleanup 收据，结束前回收本轮子进程和唯一 label 容器。Windows 的 `/proc` 与 Unix 文件存储测试失败必须如实记录；不把跳过/缺资源算作通过。GitHub CI 不运行、不重试、不等待。

M2 的核心 UoW 在显式 query view 下使用同一 `SessionQueryTransaction`；普通无 query 路径仍使用原 UoW。实际 usage 与载荷/核权拒绝审计先提交，再在事务外读取 outcome 并抛错；该 outcome 查询沿原截止另开短事务，不把整个 `responded` 包进一笔事务。v5 模型入口也显式收紧至累计 256 次 proof 读取，不按来源或槽重置。

同响应的绑定对象只存活于 M2 持锁事务：一次逐节点当前核权、一次不可变图证明和同一 cut 可供该批槽复用；每槽目标、输入摘要、context/graph 摘要、原槽插入和正文计账仍分别执行。新事务、新响应和工具执行均重新证明。查询事务在部分 SET 失败时恢复已有修改，同 query 嵌套 REQUIRED 只由原持有者恢复连接设置。已确认回滚解包原 TaskFailure；提交或回滚确认丢失仍保留 TransactionSystemException/cause，超时映射不表示数据库结果已确定。

内部 `Session query` 日志记录阶段耗时、累计读取差值和事务 COMMITTED/ROLLED_BACK/UNKNOWN，不记录正文或 SQL。嵌套阶段为包含关系，读取数不能相加充作总量。`--a03-only` 可让真实夹具在完整任务 SUCCEEDED、最终 summary、search→同响应三个 trace→read 的原槽输出摘要和精确计费成立后结束；不运行后续撤权等用例，不代表 A01–A16 总验收通过。
