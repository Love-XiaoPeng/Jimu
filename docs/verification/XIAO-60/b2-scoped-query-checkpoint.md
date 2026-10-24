# B2 scopedOrResult / scopedQueryOrResult 部分检查点

接续 `33f31e860fe86d8dd6e94cb6c8ced4674b403bcd`，tree `66425474fa7a8387139a3b2070974f62b95a4497`，准确旧JAR SHA256 `9a51a3751e6a9b0e3752d90ce9aa389f99c10ea6f42b2c881894db7f21b96b99`。本批准确源码/tree和远端回读由manifest固定；仍属开发自测，不替代独审、QA或业务验收。

## 完成

实际链：`JdbcLedger.scopedOrResult → SessionScopeQueryServiceImpl → SessionScopeQueries → MapperSessionScopeQueries → MP selectMaps / SessionScopeQueryMapper`。原适配器私有规则正文删除；Service拥有工具/原槽/上下文/归一化/authority证明、PENDING与终态分支、receipt/reservation/attempt/output/native证明、限额、已知拒绝与结算顺序。事务端口在原同DS/UoW内执行，不复制一条写链；原queryView显式实计与绝对deadline不变。

七种普通读取用真实MyBatis-Plus LambdaQueryWrapper和具名列投影：step、step_scope、已提交query、event receipt、reservation、attempt、native public event。tenant及原完整关系键不收窄。锁query及五种原CAS/计数/账本更新用参数化XML；对应五条UPDATE逐一替换参数后与原语句相同，不增加行数拒绝。原方法删除13处静态JDBC调用；当前静态252、动态库存22、字面量绑定881。共享originalDirectorySlot、scope当前资格、扫描/读取、metadata/native证明仍使用的SQL未计删除。35表＋只读receipt全部OPEN。

保持原fence/whole-scope及当前pin资格、原锁序和短路顺序；同一Service结果类型把已知SESSION_EVENT_NOT_FOUND携过事务提交后再抛出，其他异常退出原事务。原调用计数→TOOL预留/结算→invocation终态→intent/result事件→query回执→native session.read→最终fence/当前scope/截止/metadata顺序保持。旧实现的缺失读取实际因scanRows三元表达式拆箱null而NPE回滚；新类型化可空字段消除该缺陷，失败回执与单次TOOL消耗得以原子提交，不能声称此路径旧新结果不变。回放必须先核完整证明，不能再次消耗工具次数或重放provider/效果。数据访问/事务确认异常仍映射SessionCommitUnconfirmed；原SessionQueryTransaction工作末尾及beforeCommit截止检查独立保留，afterCompletion失效后记机制不变。

## 验证与实际边界

命令：

```text
JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java
./mvnw.cmd -o -B -ntp verify -Dtest=SessionScopeQueryServiceTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest,PackageBoundariesTest -Dit.test=B2ScopedQueryIT
```

35项单测/契约：查询规则28、装配3、路径2、冻结输入1、包边界1。实际集成使用私有MySQL8.0.46、loopback paid=false模型、原生v4来源和caller。在worker原PRIOR_SESSION_COMMIT_BEFORE外部屏障证明无数据库事务占用后，同一Java驱动分别从准确旧/新JAR加载真实原槽。每个实际PENDING槽验证query写故障回滚、最后beforeCommit截止回滚、commit前/后确认丢失、精确字节回放、单次工具消耗、receipt/input证明篡改拒绝及stale fence拒绝；四槽包含来源搜索、自身读取、无命中搜索和来源读取，另有搜索＋已知缺失读取。私有夹具篡改仅限本次创建的证明列，拒绝后恢复原值；不改变产品逻辑或操作共享数据。

测试结论以最终原始日志、JUnit和manifest登记；成功补跑不得改写首次完整verify。稀疏投影会改变实计字节/operations，实际向量仅限受测路径，不声称全部SQL/网络往返不变。最后截止验证只证明本次原scope事务，不关闭core UoW缺口，也不外推全部锁排列。

首次完整定向verify：35项单测/契约通过，SpotBugs bugs/errors均0；集成旧JAR驱动退出1，完整verify退出1。驱动日志与worker导出日志同名，清理时驱动输出被覆盖，不能追称已保留首次驱动错误栈或断言首次原因；保留其原始SQL、失败JUnit、被覆盖后的原件及成功清理回执。第二轮固定同版JAR补跑仍退出1，修正日志名称后捕获旧JAR的SESSION_QUERY_TIMEOUT：在预定query写故障尚未命中前已触发原两秒绝对deadline，驱动并非预期的提交未确认结果。

第三轮仅改测试：只有SESSION_QUERY_TIMEOUT、SQL故障仍未命中且全部持久事实精确不变时，才允许最多两次重试原槽；每次观察均落日志，不放宽产品限额或截止。SQL故障、最后beforeCommit、commit前/后确认丢失仍分别要求命中且核回滚/已提交边界。驱动输出改为实时写入独立文件，避免同名覆盖。生产源码/JAR不变，补跑结果单独登记，不改写首次完整verify。

第三轮固定同版JAR补跑退出1：旧版四槽完整通过，缺失读取实测在query CAS之前因旧JdbcLedger的scanRows表达式拆箱null而NPE，整笔回滚，invocation/query保持PENDING且无receipt/工具消耗；原始错误栈保留。这是旧产品缺陷，不归因于夹具。第四轮仅改测试，旧版明确核此NPE及全部持久事实不变，新版则核FAILED回执/单次消耗及其故障、截止、提交确认丢失、回放与证明路径；七组共同结果逐项相同，第八组分别核旧缺陷与新修正，不伪造全等。

第四轮同版补跑退出1：旧版四槽及缺失读取NPE边界已核，新版首槽在CAS故障钩子前返回提交未确认。根因是独立账本仅通过XML注册Mapper，七个新增MP读取Mapper缺少空namespace注册资源，动态selectMaps调用未装配。补齐七份注册XML并扩展现有PersistenceAssemblyTest覆盖七种selectMaps/delete及禁止伪造selectById；原同DS与缓存配置不变。此修复改变生产资源，因此第五轮重新构建JAR并执行完整定向verify，不沿用此前JAR的验证结论。

前四轮准确受测中间JAR SHA256 `bf13305e224db688ede8341ff577bbbeb43ed2dfd2cda08d3c95ad2155514ba0`，44020419 bytes；1156个编译文件逐项匹配，无测试驱动混入。其原始制品保留在证据包，不冒充最终受测JAR。

第五轮重建受测JAR SHA256 `b0e50866ce48b7af343f02bce0dce20f72b2bb5b74ec2ff68c62d47ab595044f`，44023933 bytes；1163个编译文件逐项匹配，七份独立MP注册XML在JAR内逐项核验，无测试驱动混入。源码补丁为本批最终实现的权威重建输入；初始生成辅助脚本保留用于追溯，不替代修正后的源码补丁。

第五轮完整定向verify仍退出1：35项单测/契约通过、SpotBugs bugs/errors均0，集成在夹具来源任务受理时返回504 SESSION_QUERY_TIMEOUT，尚未进入本批查询槽。保留原超时、失败JUnit及成功清理回执。第六轮仅改Python夹具受理：最多三次相同请求/幂等键调用，仅对原504 SESSION_QUERY_TIMEOUT重试，每次核至多一个原任务，成功时核原请求只有唯一taskId；不假定超时必回滚，不放宽产品截止，不重发provider。固定第五轮同版JAR仅补failsafe，结果单独登记。

第六轮同版补跑退出1：旧版四槽结算/回放已执行，最后receipt篡改断言失败。原始SQL显示临时写入CORRUPT_PROOF后，独立事务将metadata_bytes从23337重计到23349并COMMIT；查询自身按预期拒绝。不能把整个事实快照称为无变化，也不能把此独立提交称为查询提交。第七轮只改夹具为等ASCII字节长度的receipt type篡改，仍要求原SESSION_HISTORY_CORRUPT拒绝并恢复原type；不通过改忽略字段或放宽事实断言来消除差异。此受测场景不覆盖异步维护与任意外部破坏的所有排列。

初期Checkstyle通配导入、一次编译名称遮蔽、路径库存工具对无SQL方法名的误分类及一次辅助脚本cwd失败，均保留观察记录和已有原始日志；修复未放宽门禁。历史原始制品未重制，历史失败及原付费拒绝不改写。

第七轮固定同版JAR的定向failsafe补跑1项集成通过、退出0；与第五轮35项单测/契约及SpotBugs0分开登记，最后完整verify仍为第五轮失败。七组共同结果相同，第八组为旧缺失读取NPE回滚/新版FAILED回执提交的明确修正。旧/新使用不同caller任务，响应字节只在各自同一槽的回放内精确相等，不声称跨caller响应字节相等。七轮清理回执均核allChildrenExited/containerRemoved/rootRemoved/fixtureStopped=true且failures为空。

下表为每槽最后一次精确终态回放的显式资源向量，不是整个场景、网络往返或全部SQL；缺失读取旧版无终态回放向量，不伪记为0。

|路径/原槽|旧reads/bytes/operations|新reads/bytes/operations|
|---|---|---|
|multislot/slot-0|101/96658/1978|101/94839/1945|
|multislot/slot-1|152/151909/2566|152/149882/2533|
|multislot/slot-2|103/99985/2084|103/98166/2051|
|multislot/slot-3|154/155798/2672|154/153771/2639|
|missing/slot-0|101/92233/1870|101/90672/1837|
|missing/slot-1|旧NPE，无终态回放|151/142599/2420|

## 剩余

下一具体B2切片为`sessionContext`上下文获取的Service/类型化端口/真实MP、Mapper及对应旧SQL删除，再接其余runtime scope/receipt、受理写入及模块/维护。无外部执行或成员授权阻塞。

按成员本轮最新并发切换安排，只收尾当前最小切片和必要验证，不在本轮接新片。后续由产品经理在主控恢复原队列后安排原中级；独立边界清楚的切片可由产品经理另排中级B。下一片完成条件是上述完整真实调用链、对应旧SQL删除和必要差异证据，继续保留现有授权/事务/截止/冻结约束，不提前关闭B2或整表。当前不自行修改运行时、共享服务或主控已同步的角色/owner配置。

core UoW最后beforeCommit截止检查缺口仍OPEN；历史最后退款后可提交和中途整体回滚事实分别保留。完整scope/维护/文件锁排列、精确CLI装配、实际v3 trace清理、非空付费账本异常及真实付费视觉仍OPEN，不把本批当作后续会话业务完成。

B2未完成，60保持in_progress、PR22草稿；B3–B5、初级及61/62未激活。schema22/V1–V22及冻结字节保持；不运行/恢复/重试/等待CI，不部署、不接真实Key/外部计费/共享数据或服务，不操作daemon/代理/FRP或重置共享数据；T48及新功能继续暂停，不重跑无变化历史全矩阵。
