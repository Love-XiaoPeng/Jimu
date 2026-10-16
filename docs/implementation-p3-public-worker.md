# XIAO-40 公共 reader 的真实 worker 装配阶段

以 R6 已验收提交 `c4ebd1ff383124a56b79736a24e7674d5620429e` 为基线。本阶段接通公共抓取与唯一 ToolCallingAdvisor 循环；文件工具和报告发布事务尚未装配，不能视为完整 P3 或报告生产验收。

## 执行与配置

`DurableAgent` 经 `FetchRuntime` 选择已审阅的抓取策略，不再无条件在调用内部创建 fixture reader。公共装配选择 `jimu.web.fetch@0.3.0`；P2 明确测试装配仍使用 `0.2.0`。任务受理及响应调用继续使用 V5 不可变版本快照，版本不匹配在模型 HTTP 之前拒绝，没有修改 V1–V5 或基线附件。

持久 worker 的公共 reader 与 `WorkerLoop` 注入同一个 `OperationThreads`。取消后，真实操作线程退出才可确认 quiescence；未知抓取效果保留 `HELD_UNKNOWN` 工具预留，运行并发按已有停机收据规则释放。成功调用的恢复仍返回同槽持久结果，不重新抓取或增加工具次数。

公共模式默认选择公共 manifest，但 `jimu.web.public-dispatch-enabled` 缺省为 `false`，在模型请求前以 `PUBLIC_WEB_NOT_CONFIGURED` 拒绝。运营者域名配置为 `jimu.web.allowed-domains`；URL 和所有 DNS 答案仍受已审阅生产政策限制。普通任务参数不能改变模式、许可域名、传输、凭据或开关。`jimu.web.mode=fixture` 仅允许 `jimu.test-mode=true`，不能用作生产 fallback。非持久 P1 readiness 装配保留原身份。

本轮未启用生产公共出口或真实付费模型/搜索。模型配置及其价格准入保持原规则。没有新增平台权限、凭据或价格依赖。

## 防止误发布

公共模式在模型循环结束后以 `REPORT_NOT_PUBLISHED` 拒绝完成任务，不执行旧版的“最后成功 fetch + 模型摘要”产物路径。因此本阶段抓取成功的替身任务最终是 FAILED，产物数为零。这是尚未接入 present 的明确限制，不能报告为完整报告端到端成功。后续必须由实际 present 自身 invocation 和精确文件收据生产报告，再由原子发布事务及 required-report 检查替换此阻断。

工作区 `bind`/`resume`、持久原观察和强制 CAS 基础保持 R6 原样；本阶段没有声称文件工具网关、报告生产者或报告授权下载已完成。下一段接续 read/write/present 和报告事务，不等待本段独立验收才开始编写。

## 实际验证入口与范围

默认完整 `./mvnw -B -ntp verify` 新增必跑 `PublicWorkerIT`，不替换或裁剪现有完整 P2。专项命令为 `./mvnw -B -ntp -Dit.test=PublicWorkerIT failsafe:integration-test failsafe:verify`（先完成 package）。

专项通过真实 control/worker JVM、真实 MySQL 和本地 TLS listener 执行：

- 默认关闭时模型/TLS 请求数均为零。
- 同一实际 worker、唯一模型循环中，模型替身 → 公共 reader → TLS 正文 → 自身 invocation 成功落账 → 后继模型请求。
- 原 SNI 和匿名请求，无网页 Authorization/Cookie；loopback URL 在工具准入前被生产政策拒绝。
- T1 持久成功后杀旧 JVM，后继 JVM恢复调用缓存，TLS 次数和工具账本计数不增加。
- 实际 TLS 慢响应期间取消，未知工具预留保留，操作线程收敛后才释放运行预留；追踪日志记录 active=1、传输退出和 shutdown active=0。
- 跨租户及同租户其他 owner 不能读取任务；公共模式不制造旧摘要产物。

`LocalTlsWorkerMain` 和信任证书/地址/端口替换均只在测试源码，未进入产品 JAR。它在生产 URL/DNS 政策检查之后，使用包内 transport seam 把已检查逻辑目标映射到本地 TLS，仍执行真实证书主机名验证和传输代码。这是本地替身装配证据，不是公共 DNS、公共 socket、真实模型推理或真实搜索证据。

原始 XML、SQL、请求计数、进程退出和清理收据由每次默认门禁生成；最终交付附件记录准确提交与结果。历史自然 readiness 超时及旧 CI SQL 失败的精确原因仍未确认，本轮通过不解释它们。
