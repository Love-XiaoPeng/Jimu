# B2 revokeSessionSearchScope 部分检查点

接续源码 `0789306b9f48b4169de7cdc11d874a51b8acac68`，tree `aee4125b081c65c56acb3f513d27592529bac879`。准确旧 JAR SHA256 `8c7d6567d01b567322b49de19a34bec7f4328df53029c56261a4be4c3beb8f5a`。本批准确源码/tree及远端回读以交付manifest固定。开发自测不代替独审、QA、业务验收或B2完整完成。

## 实现与删除

实际链为 `JdbcLedger.revokeSessionSearchScope → SessionScopeRevocationServiceImpl → SessionScopeRevocations → MapperSessionScopeRevocations → SessionScopeRevocationMapper`。原queryView边界仍生成同一个显式query envelope；Service拥有budget/whole-scope锁、owner/current identity/input短路、撤销、终态/UNKNOWN取消判断与计账次序。端口隔离类型化owner事实和原事务/操作，内核不依赖Spring、Mapper或原始行Map。

预算锁 → 按原排序完整scope任务锁 → tenant/id/owner task FOR UPDATE；查不到或非scope优先返回404，当前身份丧失在拒绝事务外后记失效，然后才校验输入。ACTIVE条件更新仍COALESCE原撤销/失效时间、安排立即验证，保留原无额外行数拒绝的幂等语义。SUCCEEDED/FAILED/CANCELLED/NEEDS_RECONCILIATION不触发取消，其余复用已有 TaskOperationsService；最终复用原history metadata计账，不增加另一写链或重放provider/效果。

两处旧静态JDBC调用移为两条参数化XML，锁内owner投影为原schema22的全部63列，无SELECT星号或动态SQL片段。锁/CAS使用Mapper，本切片没有新增普通CRUD；已有共享MP/Mapper取消链不重复计算。原sessionSearchOwnerRead异常分类、SessionQueryTransaction工作末尾和beforeCommit截止检查、同DS/UoW、afterCompletion去重/REQUIRES_NEW及权限/fence保持。当前库存静态265、动态22、字面量绑定930；共享仍在用的SQL未计删除，35表＋只读receipt全部OPEN。

## 验证

定向命令：

```text
JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java
./mvnw.cmd -o -B -ntp verify -Dtest=SessionScopeRevocationServiceTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest,PackageBoundariesTest -Dit.test=B2ScopeRevocationIT
```

首次完整定向verify：22项单测/契约通过（撤销规则15、装配3、路径2、冻结输入1、包边界1），SpotBugs bugs/errors均0；集成在准确旧JAR的RUNNING重复撤销处失败，完整verify退出1、BUILD FAILURE。原驱动、编译类、准确同版JAR、SQL、JUnit与成功资源清理回执已保留，不能写为完整verify通过。

原始SQL证明首次及重复RUNNING取消观察都会触发已有afterCompletion失效后记，更新 `next_validation_at=CURRENT_TIMESTAMP(6)`；整行完全不变的夹具断言过宽。仅调整测试：保留每次调度时间前后原值，重复比较只排除该列，其余task/scope/正文/事件/reservation/budget完整事实仍精确核对。终态重复撤销仍比较全部事实。生产代码/JAR不变，以 `spotless:apply test-compile -DskipTests` 编译测试后执行同版JAR `failsafe:integration-test failsafe:verify -Dit.test=B2ScopeRevocationIT`；结果以补跑原始日志/manifest固定，不重跑无变化历史完整矩阵。

第二轮同版补跑亦失败、退出1：旧JAR取消event故障使外层事务回滚，但此前观察到撤销的scope会按原机制在afterCompletion独立后记失效及计账；“整个库事实必须相同”仍过宽。原始SQL含外层ROLLBACK及后记UPDATE/COMMIT，原驱动/类/JUnit/SQL/清理回执保留。第三轮只修测试：故障改为第一次命中时抛错，保证单次语句故障后可实际核对后记；对scope的invalidated_at/next_validation_at及history metadata分别验证，其他全部事实仍精确比较。要求grant仍ACTIVE、revoked_at仍NULL、task/事件/预留/预算回滚，后记invalidated_at非NULL且metadata等于真实库逐列重计，随后恢复成功；不把这种独立提交说成全部回滚。两种事务边界都是旧/新实际行为，生产实现不因夹具调整而改写。

必要真实路径使用私有MySQL8.0.46、control/双worker及loopback模型建立原生v4来源/caller和非空scope，再对准确旧/新JAR运行同一Java驱动。真实受理的queued caller用于取消/账目；状态分支只调整本次私有夹具，原始行在各用例间恢复，非生产数据重置。原始SQL、锁等待、拒绝与清理记录随本批证据交付。

范围：owner/tenant优于非法输入；当前actor丧失的拒绝与事务外失效后记；四终态含UNKNOWN、重复撤销；QUEUED原子终止/队列释放和RUNNING/WAITING_USER停止意图；撤销SQL事务整体回滚，取消event/metadata故障的外层回滚及独立失效后记/恢复；终态撤销原scope最终beforeCommit截止；两名真实budget锁等待竞争者只取消/释放一次及非空外tenant隔离。另核公开DELETE拒绝/204/重复204。计量只按本次受测路径实际值登记，不声称所有SQL/网络往返不变。

受测新JAR SHA256 `9a51a3751e6a9b0e3752d90ce9aa389f99c10ea6f42b2c881894db7f21b96b99`，43974810 bytes；1124个当前编译文件逐项匹配，无测试驱动混入。最初生成辅助脚本因自身前置断言在写入生产文件前停止，修正后再执行；没有生成失败JAR或启动私有库，原记录保留。

第三轮固定同版JAR的failsafe补跑：1项定向集成通过、退出0、BUILD SUCCESS；旧/新8组结果逐项一致，公开DELETE 204、重复204及queued取消通过。首次完整verify与第二轮补跑仍为失败，不将第三轮补跑写成完整verify通过。三个尝试均有成功清理回执，私有容器、根目录、子进程及夹具全部清理。最终终态撤销路径旧/新均为57 reads／83545 bytes／2625 operations；此计量结论仅限该受测路径，不外推到所有SQL或网络往返。

## 剩余

下一具体B2切片为 `scopedOrResult/scopedQueryOrResult` 的runtime scope查询/回放/提交及receipt核验Service、类型化端口、真实Mapper链和对应旧SQL删除，再接context、受理写入及其余模块/维护。当前无外部执行或成员授权阻塞。

core UoW最后beforeCommit截止缺口仍OPEN，历史最后退款后可提交与中途回滚事实保留；本切片使用原scope事务，不混同两种实际边界。完整scope/维护/文件锁排列、精确CLI装配、实际v3 trace清理、非空付费账本异常和真实付费视觉等覆盖限制仍OPEN，历史失败/原付费拒绝及合成资格/paid-run限制不改写。

B2未完成，60保持in_progress、PR22草稿；B3–B5、初级及61/62未激活，不计后续业务批次完成。不重制历史全制品/重跑无变化旧矩阵，不新增DDL/协议/冻结限额/功能，不运行或等待CI、不部署、不接真实Key/外部计费/共享数据，不操作daemon/代理/FRP。
