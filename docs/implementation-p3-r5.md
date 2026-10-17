# XIAO-40 R5：未知状态后的持久停机意图

原生命周期/快照 SHA `2c942c1fc472568f878e068bdc4204a065cc67a1` 的独立审查发现：当任务先进入 `NEEDS_RECONCILIATION`，仍有非静止执行者时，后续取消被普通终态早退忽略，supervisor 因缺少取消时间而不能按宽限期停机。任务业务终态不等于执行者已退出。

`JdbcLedger.cancel` 继续先锁 tenant budget 与 task，在同一事务使用现有的全 attempt 静止判定。仅当未知任务仍存在未证实退出的写者时，登记 `cancel_requested_at` 和幂等取消事件；保留未知状态、原错误码、工具未知费用及并发预留。重复取消不改变首次时间，不延长宽限期。已经静止的未知任务及普通完成任务仍为无操作。没有新增迁移或改变状态枚举。

未知任务取消响应仍为 HTTP 200、`state=NEEDS_RECONCILIATION`，但 `cancelRequestedAt` 非空表示已持久接收停机意图，**不表示进程已停止或任务已取消**。只有 supervisor 通过既有 PID/start-ticks 进程确认与静止收据后才能释放 RUNNING 并发；未知 TOOL 预留继续 `HELD_UNKNOWN`。

默认 `CancelOrderIT` 使用独立真实 MySQL、worker T0 屏障和真实 supervisor，覆盖 cancel→unknown、unknown→cancel、三轮并发竞争、每轮八次并发重复取消、已经静止的未知任务及正常完成对照。为了稳定观察释放前的状态，测试暂停拥有 worker host-id 的 control，同时通过另一个真实、不同 host-id 的 control 发送鉴权 HTTP；恢复原 control 后观察实际进程退出及账本释放。没有增加生产测试后门。

未知事实在真实已准入调用屏障后通过 SQL 事务注入，明确不是公共 DNS 自然阻塞、公共 worker 业务装配或真实付费调用的证据。测试保留取消响应、首次时间、事件数量、停机前后 task/attempt/run/tool 状态、PID/退出码、请求计数和资源清理。原审查反例及被替代的文件层 SHA 门禁另行保留，不作为修复后的完整通过记录。

同一 PR 先前已推送工作区文件底层阶段 `d2809f356761635035b4153150937d2e6466ee95`，其行为见 `implementation-p3-workspace.md`。R5 优先闭合期间不新增公共业务装配；公共出口与真实模型/搜索付费派发保持关闭，父任务继续 in_progress。最终修复 SHA 必须重新执行完整默认 verify，不能沿用旧 SHA 或被中止的门禁。
