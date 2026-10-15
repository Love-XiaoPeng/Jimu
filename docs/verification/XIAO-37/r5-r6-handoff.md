# XIAO-37：R5 / R6 修复交接

本交付接续 PR #3（https://github.com/Love-XiaoPeng/Jimu/pull/3，base master）的独立复核阻塞。对照提交为 `eec3092fa3dadedfa712e0d4fc2a1dd1f91de4d6`，原 P2 范围、此前四项修复和历史证据保持；本记录补充本轮修复与验证，等待同一最终 SHA 的独立复核及产品验收，未合并。

## 根因与实现

R5：租约恢复将 attempt 置 EXPIRED、任务失败将 attempt 置 STOPPED，但这些状态不能证明同步执行已退出。旧实现又在普通终态立即释放 run，并以“没有 ACTIVE attempt”授权产物清理，因而先退款后仍可能发生真实文件写入。

`6d1d47ea52e23e84d111716ca60f3ab082a41de5` 增加 V3 `task_attempt.quiescent`。只有 WorkerLoop 的执行 finally 或已核验进程退出才能确认对应 attempt 静止；旧 fence 可确认自身执行停止，不能覆盖当前 task/产物事实。`JdbcLedger.writersQuiescent` 检查全部历史 attempt，缺收据保持未知；`terminal`、`quiescent`、`processStopped` 和 `cleanupArtifacts` 统一据此决定释放。进程退出对账先逐任务事务确认静止与结算，最后标记 worker 收据 stopped，避免中途崩溃后不再扫描。安全取消等待全部历史写者；deadline/权限失败可以先记录 FAILED，但 run、对象保护和存储计费仍保留到可信停止。

`7029c2324f618afc35ba11d517d2aac90d99fe14` 进一步禁止清理仍可领取恢复 attempt 的 RUNNING 任务的到期产物：当前写者全停不排除未来新写者。到期清理同时要求任务已 SUCCEEDED/FAILED/CANCELLED。外部存储操作仍在 DB 事务外；只有删除成功才幂等退款。未知效果/费用不会因进程退出改记为安全取消或零成本。

R6：兄弟 JVM 的 `ProcessHandle.isAlive/onExit` 会把未被父进程 reap 的 Linux Z 状态当作仍存活，原 supervisor 在 onExit 超时后跳过停止对账。现在 `LinuxProcessReceipt` 一次读取 `/proc/<pid>/stat` 的状态及启动 tick；同 host、同 tick 的 Z/X 是内核退出事实，不等待父进程 reap。PID 记录消失且 ProcessHandle 也不存在才确认消失；host/tick 不符或读取失败保守持有。发信号前先取得 JDK ProcessHandle，再核验内核 tick，由句柄的启动身份检查防止随后 PID 复用误杀。取消扫描覆盖所有历史未静止 attempt，不局限于 task 的当前 worker。

## 要求到验证的对应

以下真实资源场景均在 `tools/p2_verify.py:writer_stop_regressions`，默认 verify 必需执行；也可在 Linux/WSL 专项执行：

```sh
python3 tools/p2_verify.py target/jimu-0.1.0-SNAPSHOT.jar target/writer-stop-evidence --writer-stops
```

| 要求 | 可执行验证及保留事实 |
| --- | --- |
| R5 A0 老化 + deadline/lease + 竞争 worker + 晚写 | `r5-a0-deadline`：存活阶段 EXPIRED/quiescent=0、run/storage RESERVED、未删除/退款；放行后旧发布 FENCE_REJECTED，真实对象1且仍计费；finally 确认静止后清理为0，再过维护周期不重复退款/发布 |
| R5 A1 对照 + 真实停止 | `r5-a1-deadline`：活动对象1和配额均保持；SIGKILL 后保留 Z、不 reap，产品自己确认停止，再清理/退款；重复维护对象0、计费不变 |
| 相邻失败路径 | `r5-a0-revocation`：安装撤销引起 FAILED/CAPABILITY_REVOKED，旧写者未静止时保护不变，放行后的晚对象仍计费并最终清理 |
| 旧写者跨恢复/成功仍须保护 | `r5-historical-writer`：新 fence 成功且 finally 已退出，旧 A0 仍活；到期对象1、SETTLED、run RESERVED。旧 attempt 静止后才回收；工具1次、发布事件1条 |
| 全停但任务尚可恢复 | `r5-recoverable-expiry`：A2 崩溃、原写者确认退出后任务仍 RUNNING；到期对象保留。新 worker 恢复成功后才清理，工具与发布均1次 |
| 取消覆盖历史和当前写者 | `r5-historical-cancel`：旧 A0 与当前 A1 同时存在；两个 JVM 均保持未 reap 的 Z 后产品 CANCELLED、全部 attempt 静止、run RELEASED；清理对象0、零发布、工具1次 |
| V2 到 V3 升级不推断停止 | 专有 DB 还原 V2 schema，保留暂停中的旧 attempt；worker 拒绝未升级 schema，control 真实 Flyway 升级，原 attempt 的 quiescent 仍为0，再执行上述 A0 竞争 |
| R6 不依赖父进程 reap | `r6-unreaped-cancel`：用 `/proc` 观察 Z，先断言 API CANCELLED/run RELEASED、Popen.returncode 仍 None、模型/工具均0和唯一取消事实，再 finally/收尾 reap；记录 stopConvergenceSeconds |
| R6 身份不匹配/读取失败保守 | 真实进程分别变更 host/tick 后保留 CANCEL_REQUESTED/run RESERVED 且不被杀；恢复收据后才收敛。`LocalWorkerSupervisorTest` 6项另覆盖匹配 Z/X、tick 不符、live PID 的 stat 缺失/读取失败和存活状态 |

各阶段 `writer_facts` 保存 PID、内核状态、启动 tick、host、task/current attempt/fence、每个历史 attempt/state/quiescent、run/storage 预留、storage_held、对象数和发布事件。活动写者保护检查至少跨一个10秒维护周期，不延长清理 TTL；重复清理继续跨维护周期。

原 M1 取消竞争继续保持“存活旧执行不可因租约过期终结”、放行后 HTTP 请求0和终态后0请求。合作执行通过 finally 静止时可以保留空闲 JVM，因此原夹具收尾改为先检查全部 attempt 的可信静止和 run 释放，再收集空闲进程；非合作 JVM 必须真实退出的要求另由上述不 reap 场景直接证明。

## 验证结果与限制

最终完整验证的命令、退出码、JUnit/夹具断言数量、原始日志及清理结果随事项交付评论附件提供，以该次默认 verify 为交付依据。运行代码固定为 `7029c2324f618afc35ba11d517d2aac90d99fe14`，本交接只增加说明文档。首轮专项已退出0，44条断言，真实 MySQL 8.0.46/ext4/JRE21，A0/A1、撤权、历史写者和不 reap 取消均通过；其后增加迁移、可恢复到期与历史取消场景。开发中因补上可恢复到期约束而主动中断一次旧提交验证，该次退出1、资源 finally 清理通过，不算完整通过证据。

当前适用范围仍为同机 Linux/POSIX、受控 HTTP provider/假服务和内置 web.fetch。host/tick 不匹配是可控反例，没有宣称实际耗尽 PID 空间重现快速复用；读取失败用单元测试的受控 proc 根验证保守行为。V2 升级使用真实旧 schema 和暂停写者，不宣称运行旧版本二进制的滚动升级兼容。未知写者/效果仍需核对，不能自动重试或退款；真实千问、公开搜索、跨主机管理和 P3 不在本轮。
