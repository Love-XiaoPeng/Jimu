## 范围与原行为

从 `560c45d5a2dd7af0a8dce94bd47383dba0afb742` / tree `f8e7bf33f17e52e39db2907353cf5648bb7aaae4` 迁移 NativeSearchLedger 九方法完整账本生命周期。`NativeSearchPersistenceService` / `service/impl/NativeSearchPersistenceServiceImpl` 拥有准备、启动、停止事实、结算、分页、静默检查、host两类扫描及存储释放的全部原资格/编排/行映射/循环。`NativeSearchRecords` 提供本聚合专属惰性行、窄事务与单项codec/I/O；`MapperNativeSearchRecords` 通过原Ledger的同DataSource/UoW执行真实参数化Mapper，构造仅存引用。

- searchQuiescent只检查原列表isEmpty；nativeSearches/expiredSearches不新增tx，保留stream映射、toList不可修改结果、原遍历顺序、nullable process_identity及Number.intValue。host/JOIN/数据库时间/ORDER/LIMIT64/NOT EXISTS逐字保留。
- prepare原leaseTx→fenced(true)→第二bucket→invocation普通三键读取→state/tool/effect短路→COUNT/SUM/COALESCE→20/4MiB/8MiB预算→worker未停止host→十一参数INSERT→held→原reserve。原proposal attempt为历史，不新增与当前attempt相等资格。
- started保留原leaseTx/fenced(true)、json(identity)参数到达处和UPDATE恰1。stopped保留原tx→bucket→locked→五键FOR UPDATE→identity原串/重复Object读取→INSERT IGNORE fact/hash→STOPPED/COALESCE数据库时间→原settleStoppedWriters(同task Map)。无新fence/admission/access检查；旧writer只能报告停止事实。
- completed的size/hash.matches/cursors.size及NULL首错在leaseTx前；内部完整fenced→四键锁→STOPPED且result_hash原Object为NULL→hash/size/json(同cursors)→原SEARCH_STORAGE RESERVED结算→held差额。保留原ignored count，不增加toolSucceeded。
- page逐候选惰性读取cursor_index，先decode/has(cursor)，再query/pin短路，最后按原字段顺序构造Result及index.path(cursor).intValue。无缓存/预解码/复制/重序列化/新排序。
- removed原tx/bucket/locked/四键run锁→STOPPED→reservation category锁；非RELEASED才按actual为NULL取amount、否则actual扣held及RELEASED；已RELEASED仍写末条storage_removed/result_size COALESCE。原忽略计数和首错保留。

`evidence/b2-native-lifecycle-removed-sql.json` 记录22个旧JDBC点、68个绑定位置及对应真实Mapper；独立冻结列oracle核原SELECT *及n.*全列序。复杂锁/聚合/精确更新用参数化XML；Invocation、Reservation、TenantBudget、WorkerProcess只追加本批具名语句。无SQL消除、双写、额外事务/锁、缓存、重试或DDL。Runtime/Runner/Processes/Results/SearchFiles、Host、MCP/图像/Web/报告/81/82和既迁业务内部冻结。

capture-paths实计 **55静态JDBC / 29动态 / 173静态bind**，本批实迁22/68、SQL消除0。剩余静态点Ledger43、ExecutionScopes6、SessionScopes3、SessionQueryTransaction2、ExecutionRecords1；35表及外部receipt仍OPEN。

## 验证、原失败与限制

21项Service惰性/首错/短路/事务与引用检查，1项实际Mapper的22SQL/68bind核对，复用5项纯NativeResults/Query契约及必要架构/通用Mapper/追踪检查。首批26项通过；第一次定向verify共36项中35项通过、库存追踪摘要陈旧1项失败。重新捕获后单独复验2项追踪检查通过，同生产源码的其余已过检查直接复用。第二轮verify包含打包、1IT和SpotBugs，exit0/BUILD SUCCESS，SpotBugs bugs/errors=0。

定向命令见附件原日志：`spotless:apply verify -Dtest=NativeSearchPersistenceServiceTest,NativeLifecycleMapperTest,NativeSearchContractTest,PackageBoundariesTest,PersistencePathTrackingTest,B2MapperContractsTest,RefactoringNegativeGatesTest -Dit.test=B2NativeLifecycleIT`；追踪更新后的必要复验只选PersistencePathTrackingTest。所有IT显式传入受审Linux Java21，不运行旧整矩阵。

真实MySQL8.0.46私有克隆库，旧版准确560c45d原JAR；新版原JAR **44,841,337字节 / SHA256 `dd0da78b2d2c2c745108fd5fcf9e68e6800ee629ec422fb4083f582d3df5c451`**。原37调用轮已通过；补充真实账本current_attempt/fence前进后的旧writer场景仅改测试夹具，生产源码/JAR不变，不将原轮仅Run字段变化冒称真实旧writer验证。最终第三轮1IT通过、Maven exit0，旧新各 **40调用 / 298SQL（30普通Statement＋268 prepared）/ 582绑定位置 / 每侧67参数关系**。实际旧writer行的attempt不同于task.current_attempt，task.fence已前进；旧writer停止/停止重放成功，旧lease结算FENCE_REJECTED。

公开submit在外层私有事务中受理glob/grep paid=false任务，registerWorker/claim仅setup；普通入口前实际核RUNNING/ACTIVE/live/current_attempt/fence/worker及完整授权链。原公开Ledger调用覆盖九方法完整链、普通与重复拒绝、撤权后停止观察、NULL身份、聚合预算、重放拒绝、分页query/pin/cursor、host边界、过期终态/非quiescent排除、RESERVED/SETTLED释放及幂等。fixture显式建立合法invocation/step/reservation、身份、过期和恢复状态；没有真实工具结果/进程死亡回执。availability=true及PID/boot/namespace身份均为合成账本前置，不代表NativeSearchRuntime/Runner启动、真实进程观察或物理删除验收。

每调用留原SQL、普通/PreparedStatement、参数值和setter/execute/getter重载/原值类型/NULL、前后业务事实与connection。独立ID、时间不强求跨侧字节相等；按本侧已证关系及冻结投影核对。setObject与setLong、executeQuery与execute/getResultSet、Map getter等真实差异保留，不称JDBC API相同。cause/suppressed/next SQLException、SQLState/vendor在实际出现处采集；未制造额外SQL故障。

外层rollback记录私有36表全部原始前后快照，含worker_process，恢复与cleanup单独核；不称逐调用持久提交、独立afterCompletion/REQUIRES_NEW后记或整体重构验收。本轮query=NULL公开Ledger；queryView累计预算/绝对deadline、历史FAILED-read及其他OPEN边界沿既有证据冻结，不新增关闭。

原生成器在转换host字段前中止，保留原脚本与部分补丁；生成的通配符import触发Checkstyle失败，改显式import；初次verify库存摘要陈旧失败，重捕并复验。各轮原日志、源码/当时class及恢复记录分存；编译尚未到达的class不冒称同版受测品。没有将助手/夹具失败写成产品缺陷。

下一候选整组为working_directory槽受理/冻结→replace→commit→owner读取（本head相关八方法合计28旧点，directoryTransaction的独立1点另计），由PM核源码再派；本批未实施。60继续in_progress、PR22 draft，最终独审/QA/总验收及master推送读回未完成。不CI/部署/付费/共享服务/真实MCP/provider/原生工具/worker循环，T48暂停。
