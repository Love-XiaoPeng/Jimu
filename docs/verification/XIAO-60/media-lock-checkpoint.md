# B0/B1 媒体锁序定向检查点

前版源码 `ff52841180083cfa64b0122279a2a96a47fcd930`、tree `bac26c8fdde14f3cb1f02adb796369a4e7dd21c5`，原受测 JAR SHA256 `2259507afa748815f3a8188c1efaeb7ad970cab6d8800164d8471aa5e65cc6ab`。本版准确源码/tree/JAR、执行结果和清理以本批独立 manifest 为准。PR #22 继续草稿，XIAO-60 继续 in_progress。

## 架构依据与修改

依据同线程架构结论 `01a16928-9698-7670-b3fa-7856964edf27` 及附件 `XIAO-60-media-lock-decision.md`。旧版两个媒体方法在校验 producer/input/result 前取得 tenant_budget，再经 fenced 申请 evaluation_budget；另一 task 的 checkpoint/claim、control submit 可先取得同一 evaluation，再等待 budget。task SKIP LOCKED、单个 ImageRuntime 串行执行不能排除此跨 task/进程等待环。

只修改 `JdbcLedger.reserveImage`、`imageSucceeded`：reserve 先 fenced(true)，再读已持锁的 budget；success 删除 fenced 之前的 bucket。首次业务锁为 resolved evaluation（若有）→tenant budget→原 scope/task→媒体记录。reserve 的再次预算读取不释放锁，不构成新的反向依赖。run 仍按原 receipt、requirement、binding/fallback 解析，未改为直接使用 task 字段。

原 leaseTx/Spring REQUIRED、READ_COMMITTED、同 DS/UoW、CAS/预留实计/差额/嵌套 toolSucceeded/event/fence 的同连接提交保留。没有新增 DDL、API、重试或 Mapper 批次；没有改变效果执行/重放、三个持久拒绝 allowlist、材料拒绝提交后抛错、query 实计/绝对截止或 durable=false。只统一两条媒体前缀，不声称全仓 owner/维护/清理/query 共用一种锁序。

`core-workflow-checkpoint.md`、`lock-order-checkpoint.md` 中媒体例外是 ff52841 历史状态。当前 attributed graph、分支政策及参数/SQL 已重捕获，不能以旧行号作本版关闭依据。

## 夹具与边界

`MediaLockScenario` 与 `xiao60_media_lock_verify.py` 用相同驱动加载准确旧/新产品 JAR。私有 pinned MySQL 8.0.46 经真实 Flyway V1–V22 启动；控制面/worker/provider 仅用 loopback。测试专用 JDBC proxy 观察实际产品 PreparedStatement 和事务，在首个 SELECT FOR UPDATE 已返回后设置屏障；不以手写锁 SQL 替代产品取锁，不向产品加入测试钩子。

免费图片先经 HTTP→真实 worker→read_image→实际 PNG 模型请求→compose_report/present 完成。合成付费 profile 经真实 HTTP 受理后，确认 `MODEL_INPUT_BOUND_UNAVAILABLE` 及 provider 零增量。**当前 paid vision 的输入计费证明仍不具备，不为复现解除拒绝，不称干净付费视觉链路可自然到达媒体成功。**

锁分支夹具先经真实 submit/claim/prepare/dispatch/responded/admit 得到免费 read_image 的有效 task/step/invocation/media/pin，再显式将测试 task 绑定合成 paid snapshot/run。这是本轮授权的已受理状态 seed，不是当前付费 provider 派发证据。保留原 fence、当前权限、期限、run 和结果校验；实际解析查询、全键 SQL/binds、私有库身份及 LOCK_DATA 在附件中。

## 验证设计与证据

两个媒体方法各与同 tenant/run 的另一 task 的 checkpoint 竞争。媒体先取得首锁和 checkpoint 先取得首锁分别重复三轮；观察 data_lock_waits/data_locks/innodb_trx 后才放行，保存连接号、事务 ID、持有/等待锁键、SQL/binds、commit/rollback 及每次结果。

旧版 deadlock_detect=ON/OFF 分别分类 1213/40001 与 1205/HY000。关闭检测仅用于私有测试；连接 lock_wait_timeout=3 秒，原产品事务/语句上限仍有效。新版断言 evaluation 等待者尚未持有 budget，不强等旧反向双持锁屏障，放行后有界完成。所有 future/executor 确认退出后才保存快照并释放夹具。

检查失败媒体事务的 task revision/event/媒体/预留/invocation/预算整体不变；reserve 一次 1MiB hold；success 结算实际规范化 PNG 字节。免费/null-run 与合成付费的无冲突预留/状态/大小/尺寸逐值比较旧/新；不同 tenant 同 run 字符串的 checkpoint 应在另一 tenant 持锁时独立完成。

普通拒绝覆盖旧 fence、坏 input/result、storage 不足、截止、撤权、安装 epoch/pin 变化、STOPPED、取消、重复 reserve 的 EFFECT_OUTCOME_UNKNOWN 及重复 success 的原 CAS 拒绝。proxy 在实际媒体 CAS、reservation 结算、storage 差额、invocation success 写后分别注入故障，检查整组回滚、无半提交，再确认一次正常结算。没有 provider 重放，不扩大异常提交 allowlist。

原始失败、准确执行计数及清理见本批 manifest/JUnit XML/日志。开发自测不替代独审/QA；没有重跑旧完整矩阵或 GitHub CI。

最终结果：旧版 12 轮 `1213/40001`、12 轮 `1205/HY000`；新版 24 轮两事务正常完成，等待 evaluation 时无已授予 budget 锁。四个制品/配置组合均通过拒绝、四处写后回滚、跨 tenant 对照和两组免费/合成付费串行账目；八组串行快照逐值相等。每组都保留实际 1MiB 预留及 73 字节规范化 PNG 实计。真实免费 provider 五次请求完成，付费拒绝零增量。

25 项单测通过、SpotBugs0 来自本批首个定向 verify；该 verify 的媒体夹具失败仍保留，不称整条命令成功。其后只修测试夹具/采集，产品 JAR 未变；最终 `failsafe:integration-test failsafe:verify -Dit.test=MediaLockIT` exit0，1 项集成、0失败/错误/skip，日志 BUILD SUCCESS。本批 package/test-compile/格式/导入检查成功；不重复已通过单测或旧完整矩阵。最终及五次失败 fixture 的私有进程、容器、provider 和目录均有清理回执。

## 交回与剩余

交回两处前缀修复与必要定向证据，由产品经理核验 B0/B1 收口，不自动激活 B2。35 张业务表＋外部只读 receipt 的业务 Mapper 切换全部 OPEN，实际迁移须继承此顺序、逐项删除旧 SQL、禁止双写并凭业务证据关闭。B2–B5、初级、独审/QA、部署及新功能未激活。

本批没有穷举材料后验持久拒绝、receipt/requirement/binding 组合、全模块恢复/维护锁排列或定位原现场未知持锁者；相关产品逻辑未变，历史证据继续保留，相关 Mapper 切换仍需补证。合成 paid-run 不证明真实收费/付费视觉授权。没有真实 Key、外部计费、共享数据操作；观察权限仅存在于已清理私有容器。

清理回执确认真实 worker/control/驱动子进程退出、provider 停止、私有容器及存储/临时目录删除。证据先复制留档再清理实例。
