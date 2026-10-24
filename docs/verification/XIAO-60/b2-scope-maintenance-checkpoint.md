# B2 validateSessionScopes 部分检查点

接续 `725d9fd0f0684a2b9f061737bad16c351b067f61`，tree `e0fa68cef34903ca16573f651e620947a6950f43`。准确旧 JAR SHA256 `376f5974f36527bf1b0443fc91fd38e62770843a508254346a7d1d32d3839b29`。本批准确源码/tree、受测 JAR 和远端回读以交付 manifest 为准。开发自测不代替独审、QA、业务验收或完整 B2 完成。

## 实现

实际维护链为 `LedgerMaintenance → JdbcLedger.validateSessionScopes → SessionScopeMaintenanceServiceImpl → SessionScopeMaintenanceRecords → MapperSessionScopeMaintenanceRecords → SessionScopeMapper`。候选扫描、先提交60秒调度再执行当前资格验证、原拒绝分类、失效与清理/计账决策已归 Service。每轮仍最多8条候选、同一2秒绝对截止；调度和校验各使用原 SessionQueryTransaction，拒绝后不回滚已独立提交的调度。

`JdbcSessionScopes` 当前资格、whole-scope、来源资格、锁顺序规则正文移入 `SessionScopeEligibilityServiceImpl`，通过类型化 SessionScopeRecords 获取数据。当前权限仍经原 access/identity 路径；兼容外层保留原 runtime/受理调用形状并共享真实 Service，没有请求间缓存或第二写链。核对 scope/history/authority 绑定、owner/tools/epoch、保留时间、原始 JSON 及摘要/UTF8字节、完整有序 target/ordinal、来源独立性/终态/writer/UNKNOWN 和受理180秒保留门槛。原 source 拒绝映射与损坏拒绝顺序保持。

来源普通读使用真实 MP BaseMapper/LambdaQueryWrapper，tenant+id 完整键，保持原9列投影。锁、候选、当前 scope/target 和失效写入使用11条参数化 XML，无 `${}`/`SELECT *`；保留 FORCE INDEX、排序、LIMIT及更新谓词。budget 后按去重排序的 source/caller task 加锁。失效清理复用上批共享 purge/退款/事件固定点，UNKNOWN/非静默不清理、不重放 provider 或效果。同 DS/UoW、原 afterCompletion 去重/REQUIRES_NEW 后记及异常传播不变。

本批删除15处旧静态 JDBC调用及1处动态行转发，库存静态267、动态22、字面量绑定935。仍在使用的共享 history/account/metadata SQL、scope 受理写入和 runtime query/receipt 业务未计删除；35表＋只读receipt仍全部 OPEN。本批不计为后续会话业务批次完成。

## 必要差异验证

27项单测/契约：资格12、维护8、装配3、路径2、冻结输入1、包边界1。第一轮单测因路径库存未刷新及假来源解码夹具辨识错误而失败2项；修正库存/测试夹具后27项通过。首轮未生成 JAR/启动私有库，原失败日志保留。

完整定向命令：

```text
JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java
./mvnw.cmd -o -B -ntp verify -Dtest=SessionScopeEligibilityServiceTest,SessionScopeMaintenanceServiceTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest,PackageBoundariesTest -Dit.test=B2ScopeMaintenanceIT
```

最终完整定向verify于2026-10-24 21:25:26北京时间结束，BUILD SUCCESS、退出0，27项单测/契约＋2项集成通过，SpotBugs bugs/errors均0，耗时8分25秒。两份私有资源清理回执均确认子进程退出、container/root移除、fixture停止且failures为空。私有真实 MySQL 8.0.46，原 control 与双 worker、loopback 模型生成真实 v4 非空 scope/source/caller；实际拒绝依赖来源、跨 owner 和跨 tenant，非空运行及 owner search 正常。未使用真实 Key 或外部计费。

相同 Java 驱动分别加载准确旧/新 JAR，对同一私有事实完成9组维护对照：有效scope独立调度/计账；非终态失效保留；撤销终态原子清理/单次退款事件/重复；损坏延后且预提交调度保留；UNKNOWN保留8MiB；来源当前资格丧失使whole-scope失效；event故障整笔回滚和恢复；最后beforeCommit检查；双竞争者真实budget锁等待且只结算一次及跨tenant隔离。原始 SQL、锁等待、输出和清理回执随证据交付。

scope 最后截止测试在退款 SQL 已完成后注册更早执行的 beforeCommit 故障钩子，令同一 query 截止；原 SessionQueryTransaction 随后的最终检查使正文与退款均回滚。**core UoW 最后 beforeCommit 截止缺口仍 OPEN**，不以scope的更强边界替代它：历史 event 后下一查询前超时回滚、最后退款后截止可能已提交的实际记录仍有效。

公开control另作旧/新6组get/events/cancel对照：有效scope、actor撤销后的脱敏和失效后记、actor恢复不复活、事件拒绝、取消的状态/脱敏、direct events 拒绝回滚且 afterCompletion 后记提交，不发生task/event突变。公开actor拒绝优先级保持；无provider调用。

有效非空scope受测计量为旧83 reads / 103375 bytes / 2901 operations，新83 / 103378 / 2901。真实 MP 来源投影使用 `id AS rowId`，列标签比原 `id` 多3字节，query按实际列标签计入，不放宽计量或冻结限额；仅陈述受测路径，不扩大为全部 SQL/网络往返不变。

新 JAR SHA256 `8c7d6567d01b567322b49de19a34bec7f4328df53029c56261a4be4c3beb8f5a`，43965117 bytes，1116个当前编译文件逐项匹配，无测试驱动混入。最终测试数、SpotBugs与两份私有资源清理回执由manifest/原始日志固定。

## 剩余与下一切片

下一具体 B2 切片建议 `revokeSessionSearchScope` 的owner/输入拒绝、撤销/取消/计账 Service、类型化端口和真实 Mapper 链；再接 runtime scoped query/context/receipt、scope受理写入和其余模块/维护。当前无外部执行或成员授权阻塞。

core UoW最后提交截止缺口继续单列；runtime scope/receipt、精确CLI装配、完整scope/维护/文件锁排列、实际v3 trace清理、非空付费账本异常和真实付费视觉等覆盖限制不关闭。保留历史失败、原付费拒绝及合成资格/paid-run限制。B2未完成，60保持in_progress、PR22草稿；B3–B5、初级与61/62不激活。不重跑无变化历史矩阵，不新增DDL/协议/冻结限额/功能，不运行或等待CI、不部署、不接真实Key/外部计费/共享数据，不操作daemon/代理/FRP。
