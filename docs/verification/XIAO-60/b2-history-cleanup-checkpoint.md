# B2 cleanupSessionHistory 部分检查点

接续 `09567eb9433962517e780895c95376201fb3df1c`，tree `74ed10bd793b1fff6319b28b1b34c90207b5241e`。受测旧 JAR SHA256 `9da6affa7aa45fd284abcf0c5bc14052248962e1d19053346b8a7624816b2bb0`。本批新源码/tree/同版 JAR 以交付 manifest 和远端回读为准；开发自测不代替独审、QA 或业务验收。

## 完成范围

实际链为 `LedgerMaintenance → ExecutionLedger.cleanupArtifacts → JdbcLedger.cleanupSessionHistory → SessionHistoryRetentionServiceImpl → SessionHistoryRetentions → MapperSessionHistoryRetentions → MP/XML`。到期候选、锁内资格与绑定检查、正文 SHA256/UTF8 长度保留、scope 正文 NULL、事件与计账五轮固定点已归入内核 Service。12种正文/identity集合使用实际 BaseMapper/LambdaQueryWrapper；锁、条件清理、精确 NULL 和账目使用38条参数化 XML，包含16张表的冻结 V22 元数据列投影。未新增 DDL、协议、限额或业务功能。

`JdbcSessionRecords.purge/release` 旧实现已删除。`validateSessionScopes` 原失效维护事务改复用同一 `Service.purge`，保持它的调度、当前权限、失效判定和原 SessionQueryTransaction；不将这些剩余 scope 业务记作已迁移。活跃业务仍调用的 `history/metadata/columns` 及 `sessionCleanupSafe` 旧SQL保留，未重复计删除。删除为17处旧静态 JDBC 调用和1处动态正文扫描；静态库存282，动态库存23、静态绑定963。动态库存包含新的 MP selectMaps 内部转发，净数不代表旧扫描仍存在。

候选16条上限、三终态与 writer/UNKNOWN 等 anti-join、排序和 tenant/完整集合键不变。每候选原 core UoW：budget → 原 scope 排序任务锁/所属 task → 两次 history FOR UPDATE 与绑定核验。保留原短路与拒绝顺序。scope 清理依次 NULL query/read refs、step context、target ID/正文、authority；其余会话正文按原顺序改空串/NULL，原 request digest 和版本/pin/出处摘要不改写。

正文清理及 receipt → 删除事件 → 含实际所有列的元数据计账 → history metadata/held 与 reservation SETTLED/actual → 完整事后计量固定点 → `8388608 - 实际metadata` 释放。同32字节/行、8字节/标量、NULL 1字节、UTC微秒文本与65536关闭限额，最多5轮。原 reservation category 谓词与未核行数行为保留，没有套用 todo 的零行拒绝。无 provider、效果或文件系统重放；单一写入和原同 DS/UoW 不变。

## 截止与覆盖边界

**原 core UoW 最后 beforeCommit 截止检查缺口持续 OPEN**。event 后、metadata 下一查询前超时整笔回滚；最后 budget 退款 SQL 后才截止，旧/新均可已提交而下一查询超时。原绝对 deadline 验收要求仍有效，后续单列修复并补受影响验证。scope 维护仍使用原有最后截止检查的 SessionQueryTransaction，不把两种事务边界混为一谈。

采用同一 Java 驱动对准确旧/新 JAR，在真实私有 MySQL、control/双 worker 和 loopback 模型生成的会话事实上对照 read、search、带非空 scope 的 prior-session search/read。私有夹具只调整本次任务的生命周期/故障状态；无真实 Key 或外部计费。验证候选与锁内重验、保留/终态/writer/UNKNOWN、正文摘要与 NULL、元数据配额拒绝、七处 SQL 故障整体回滚/恢复、两种截止边界、双连接 budget 实际锁等待与单次账目/事件。scope 另核已失效维护入口的共用清理。显式实计按原始记录交付，投影和元数据发现变化不能表述为所有 SQL、往返或计量不变。

首次单测前置 Checkstyle 因通配 import 拒绝，修正后27项单测/契约通过；原日志保留，首次没有生成新 JAR 或启动私有库。完整定向 `verify -Dtest=SessionHistoryRetentionServiceTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest,PackageBoundariesTest -Dit.test=B2HistoryRetentionIT`：27项单测/契约通过、SpotBugs bugs/errors均0；read及search旧/新各9组结果通过，scope尚未完成。整轮接近原夹具15分钟上限时，主动SIGTERM准确自有Python子进程，经finally清理成功，整轮退出1，**完整verify失败**；保留准确JAR、原驱动及编译类、SQL、JUnit、日志及三份清理回执，不把已过read/search记作独立JUnit通过。

仅调整测试夹具为30分钟上限和显式profile选择，生产源码/JAR不变。用 `spotless:apply test-compile -DskipTests` 编译后，只对scope执行同版JAR `failsafe:integration-test failsafe:verify -Dit.test=B2HistoryRetentionIT -Djimu.history.profile=scope`：1项集成通过、退出0、BUILD SUCCESS（6分19秒），含10组旧/新结果与真实已失效scope维护共用清理。read/search已通过的实际对照不重跑。累计27项单测/契约＋1项最终scope定向集成通过，不把失败整轮重写成功。原始SQL分别提取read18/search18/scope19份receipt（包含故障回滚尝试），旧/新原numeric/string行标识、顺序、全部字段、摘要及字节逐份一致；不将这些SQL尝试数当成提交次数。

| 已测成功清理路径 | 旧 reads / bytes / operations | 新 reads / bytes / operations |
| --- | --- | --- |
| read v1 | 141 / 196029 / 5023 | 141 / 184903 / 4758 |
| search v2 | 141 / 280672 / 6554 | 141 / 265188 / 6188 |
| prior-session scope v4 | 144 / 323651 / 6981 | 144 / 306094 / 6575 |

受测JAR SHA256为 `376f5974f36527bf1b0443fc91fd38e62770843a508254346a7d1d32d3839b29`，43928558 bytes；1092个当前编译文件逐项匹配JAR，无测试驱动混入。最终四份私有实例清理回执及原失败四份清理回执均确认children退出、container/root移除、fixture停止且failures为空；不修改任何共享服务。

公开control定时维护入口另以同版JAR核验，不增加JUnit计数。第一次原v1夹具缺少 `invocation_session_trace` UPDATE授权而拒绝，原异常及私有资源清理成功回执保留；新私有夹具补齐仓库既有应用授权后复验通过：原正文清除、精确账目释放、单次删除事件、reservation实际值及重复维护一致。没有放宽生产授权或修改共享数据。计量仅限受测路径，正文投影收窄导致原/新差异按实际保留；不表述为全部SQL或网络往返不变。

## 剩余与接续

下一具体 B2 切片为 `validateSessionScopes` 调度/当前资格/失效处理的 Service、类型化端口、真实 Mapper 链及对应旧 SQL；随后其余模块、runtime scope/receipt 和维护剩余。当前无外部执行或成员授权阻塞。

B2未完成，35张表及只读 receipt 全部 OPEN。精确 CLI 装配、runtime scope/receipt、完整 scope/维护/文件锁排列、实际 v3 trace 清理组合、非空付费账本异常及真实付费视觉仍未关闭；保留合成资格/paid-run 覆盖限制、历史失败和原付费拒绝。原1205异常 SQLState=40001，旧 HY000 摘要不准确，未发现采集层转换依据；两份历史原件均保留，本批不重制历史全制品。

不计为后续会话业务批次完成；B3–B5、初级和61/62不激活。60保持 in_progress，PR22保持草稿。最终独审/QA/业务验收/合并待完整重构交付。不运行或等待 GitHub CI，不部署，不接真实Key/外部计费/共享服务或数据，不操作 daemon/代理/FRP。
