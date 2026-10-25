本片仅迁 `JdbcLedger.todoCommitFence` 的共享提交栅栏资格。原分支 `agent/agent/7aeccb14d7a9`、草稿 PR22，parent `dd2c92b5bbd1cc95de7538655adbef1f7b72d505`；最终源码 SHA/tree 由同次交付 manifest 与原 Git commit/tree/bundle 精确绑定。本片不是 B2 完成或独审 QA／总验收。

## 实现与冻结范围

`kernel.execution` 的 `CommitFenceService`／impl 完整承载原 `updateCount != 1` →409/FENCE_REJECTED；`CommitFence` 是只执行单次更新并返回 int 的惰性端口。`MapperCommitFence` 构造不读 lease／会话／时钟；更新时按原顺序逐项读取 tenant→taskId→attemptId→worker→fence(long)，之后沿同 DS/UoW／原 queryContext 调用真实 `CommitFenceMapper.commitFence`。NULL lease 仍在 tenant() 成员到达时 NPE。无 todo guard、catch、fallback、重试或第二事务。

准确 XML 只有原一条 UPDATE，执行实串与原 SQL 相同，绑定顺序不变：

```sql
UPDATE task SET updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND current_attempt_id=? AND worker_id=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6) AND deadline>CURRENT_TIMESTAMP(6) AND cancel_requested_at IS NULL AND state='RUNNING'
```

保持只写 updated_at、三处数据库 CURRENT_TIMESTAMP(6)、两个严格 >、cancel NULL 和 RUNNING。原 JdbcTemplate `executeUpdate` 迁为 MyBatis `execute/getUpdateCount`，实际日志分别记录计数；String/long 的值与 Java 类型保持，setter 调用细节按原日志单列（原 long setObject，新 setLong），不称全部 JDBC 调用相同。相同连接 URL／驱动设置，不改 useAffectedRows。测试设置明显不同的旧 updated_at，因此实际命中一行且时间变化；没有验证同微秒无变化更新，不泛化 matched/affected 同义。

其他593生产Java／420既有资源逐字节冻结，新增1XML；所有其他 Ledger 方法去掉本片字段／方法后词法相同。相邻 Bean/bootstrap/POM、schema22/V1–V22、已迁值类型／NULL／字段顺序／todoProjection21列／case／摘要不变。

13处原位置和条件不改：directoryReady、freezeDirectory、directory结果提交、executeTodo成功重取／新结果；ExecutionRecords.prepared仅directory或session；ModelWorkflows.dispatched及InvocationWorkflows.admit/success仅directory；SessionReads/Traces/Searches/ScopeQueries各原回调。session原fence→account／deadline→account／requireScope→remaining→account及既有catch/outcome、scope去重/REQUIRES_NEW后记完全保留。不是新 beforeCommit，未关闭core最后beforeCommit旧缺口。

本片迁1旧JDBC点；SQL仍执行，消除0；剩余静态166／动态29／bind556，不关闭整表。

## 逐轮执行与同版制品

run1：4Service＋3port＋2追踪＋1架构共10项，1项 port SQL合同测试失败：误把基线内部 method ID `m333` 当 Java符号搜索，expected not null。封存56记录后仅改测试定位为准确原SQL前缀。verify1，未到打包／IT／SpotBugs；其余9项过。原源码／编译件／JUnit完整AssertionFailedError及Maven日志保留。

run2：同一完整定向verify，4Service＋3port＋2追踪＋1架构＋1真实IT全部过；命令0、BUILD SUCCESS、SpotBugs bugs/errors0，封存98记录。只执行必要差异验证，不重跑旧模块矩阵。

受测JAR SHA256 `7df174b0cf718a89f0c2c8ec7a76477966f5e75e818943dbde41f61123e7a971`，44566413字节／1598生产entry。与准确旧402d431d制品全部1592生产entry独立比：1584原字节同、6新增；既有变化仅Ledger主class及七个嵌套class，后七者通过解析只剔除LineNumberTable后字节同。执行driver／编译件、逐轮源码／生产class及最终JAR关联另核，未用后打包候选替代受测JAR。

准备原件保留：helper首次py_compile引号SyntaxError；首次追踪review误把名为update的真实Mapper调用识别为动态SQL而拒绝；随后一次重命名误改adapter接口实现，capture javac失败；修正后追踪166/29/556且无未审动态项。以上均在首轮Maven前，非产品运行失败。Python Windows入口不存在、若干只读路径／通配符和离线摘要打印引号错误亦已披露；有完整独立stderr者附原件，工具回执中出现但未单独封存的只读stderr不冒称齐全。离线分析首版误写String setter有差异，按原件改为仅long的setObject→setLong，String不变；首版标签原件保留。MySQL启动2003就绪探测原stderr完整保留，非业务SQL故障或自然08S01。

## 有界真实验证

私有paid=false、无worker循环／provider／真实工具。新侧HTTP仅用于准备实际受理的directory任务，完整冻结数据库在旧新试验前复制。旧402d／新7df174b0各执行3组：

- direct-success：一次UPDATE、完整五键bind，实际count1；updated_at由2020旧值变为实际数据库时间，并位于同侧DB clock前后观测间。
- direct-old-fence：原long fence减1的合法Lease，真实UPDATE count0、409/FENCE_REJECTED，updated_at保持旧值；这是direct方法，不冒称公开入口抵达末尾。
- directory-ready：非todo能力 working_directory,read,write,edit 的真实`ledger.directoryReady`；原task/budget锁及projection读取→UNINITIALIZED/READY条件更新→末尾共享栅栏count1，返回void，workspace_state READY。证明只在外层rollback前。

3对原SQL序列相同；每笔栅栏的完整tenant/task/attempt/worker/fence绑定各自与本侧Lease核对。attempt ID及数据库时间各侧不同，原串／SHA／长度分别保留，不泛化全部bind或wire相同。既有actor/installation绑定不改；原锁序由日志与源码分别核。

6笔tenant-a二十表捕获事实在各自外层rollback后相同；worker_process不在这20表捕获范围，不称整库。唯一私有fixture清理receipt证明所有子进程退出／fixture停止／容器和根目录删除，failures空。没有UNKNOWN或回滚失败，没有恢复重放。预期FENCE_REJECTED的完整主错链保留，SQL异常递归cause/suppressed/next/SQLState/vendor采集代码保留但本轮未注入业务SQL故障。

负数／>1更新数及NULL成员仅合成单测；真实主键不可表达多行。原lease/deadline/cancel/state门禁既有覆盖如working_directory_verify.py的旧fence／deadline及final-fence场景、CoreSettlementScenario旧fence／expired恢复、session探针及Service短路测试只作原覆盖／源码引用，未当本轮新head验收；未扩大全拒绝矩阵或自然故障／冷路径。

## 剩余与边界

下一具体建议片为 `JdbcLedger.todoResultSource` 原结果源资格：原OR成员懒读取→task_attempt完整tenant/task/attempt/fence查询与严格size1→非PURGED原output摘要，保留executeTodo成功重取及getTodos调用；本轮没有混迁。

B2／B0–B5未完；60保持in_progress／PR草稿，最终61/62 backlog，63/64/66/70不重接，72固定旧head。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径及所有历史失败／缺件OPEN。本轮不是持久提交／整库／FAILED-read／独立后记／成功工具read或独立QA。没有CI／合master／部署／真实Key计费／共享数据服务／daemon代理FRP重置，T48及新功能暂停。

只回读既有规则`01a1744d-e8d0-7328-9dde-7f70753c0e0d`，准确run`01a1744d-2929-7cc1-a268-d2fb4493e54f`，唯一completed/failed/cancelled、once＋7200秒；未创建或恢复规则。
