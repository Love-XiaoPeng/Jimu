## B2 task.get/cancel/events 部分检查点

从 `28744449ee7b094e9ed4ca0f64933635312358f6`、tree `54cedf5f8d0b6e1d057a4c324c7d23a68f8dba87` 接续。旧 JAR SHA256 为 `148bbc7af10a34c279063d23fe588428216b04aeca6730d8ef7d1f33791e9df5`。本批准确源码、tree、受测 JAR 与原始验证结果由附件 manifest 固定；源码不嵌入自身 SHA。B2 未完成，35 表与只读 receipt 全部 OPEN，仍需后续独审、QA 和业务验收。

### 已实现及调用链

`TaskController.get/cancel → TaskServiceImpl → Tasks/JdbcLedger → TaskOperationsServiceImpl`；`DatabaseEventFeed → Tasks.events → JdbcLedger → TaskOperationsServiceImpl.events`。HTTP、SSE 和直接账本调用共用同一业务实现。`TaskOperationsRecords` 传类型化 Snapshot/Question/Row 句柄，内核不接收 JDBC、SQL、MyBatis、可变行 Map 或隐式业务 ThreadLocal。

| 用例 | 规则及实际持久调用 | 事务和锁 |
| --- | --- | --- |
| get/view | scope hint 使用真实 `TaskVisibilityMapper.selectMaps`；可见性复用全键 MP；公开产物用 `ArtifactViewMapper.selectMaps` 的 17 列投影；Service 持有 scope 脱敏和历史保留摘要规则 | 原 tx/UoW；scope 分支 budget→按 ID 排序的 scope tasks→锁 task；普通分支保留原 scope/actor 顺序 |
| cancel | Service 持有取消意图、幂等、UNKNOWN 分支、queued/question 终态决策；`TaskOperationsMapper` 条件更新、队列/run/question-task 预留释放和账目更新 | budget→visible(scope/actor)→scope/task→question 行；同 DS/UoW 内原子提交，无双写 |
| events | Service 持有当前权限→未来游标→7 日保留→独占 seq 游标及最多64项顺序；实际 `TaskOperationsMapper.oldestEvent/eventPage` | budget→scope有序task锁→visible/actor；原事务；公开 Event 和 SSE 协议未改 |
| 共享终态/问答关闭 | 原所有调用方的 `terminal/closeQuestions/view/releaseRun/releaseQuestionTask` 都转到同一 Service/Records；等待问答关闭、调用取消/失败、TOOL actual=1、终态事件与原子预留释放 | 使用调用者已有事务和锁，不新建独立写事务，不改 fence 和媒体锁前缀 |

普通读复用 MP 的 LambdaQueryWrapper；锁、状态谓词、账目和聚合使用参数化 XML。单行保留原 tenant＋完整主键；按 task 的集合变更保留原 tenant＋task＋状态/类别谓词，未擅自增加新谓词改变业务。所有新增 MyBatis 调用显式携带同级 `query`，MP wrapper 为 `ew`；SqlSessionTemplate 复用原 DataSource/Spring 事务。原 `tx` 拒绝提交 allowlist、scope afterCompletion 失效后记和绝对 deadline 装配未更改。

UNKNOWN 有活动 writer 时只记录 stop intent，不改结果或费用；已终态且 quiescent 不新增取消事实。scope get 的特例可返回脱敏视图，而 events 仍按原可见性/授权路径拒绝；当前 actor 拒绝与 scope 拒绝顺序不互换。取消无新 capability 授权步骤，避免改变原来撤销能力后仍可请求停止的契约。

### 删除及逐项边界

本批删除 23 个旧 JDBC 字面量调用点：get1、view1、cancel2、events2、closeQuestions6、terminal7、releaseRun2、releaseQuestionTask2；对应19个 XML statement 与2条实际 MP 读路径。未重计此前的 operator18、artifact3、task.submit26、Invocation25 和 model55。准确旧 SQL 原文、方法、参数、入口与表项关系见 `evidence/b2-task-read-paths.json`；库存由393降为370，动态24，字面量参数1164。

显式 event11 列和 question33 列保留旧查询的物理结果投影；artifact17列也是原完整投影。MP/MyBatis 的元数据读取与实际网络交互不据此宣称完全相同；实计结论只适用于驱动明确测量的路径。底层 scope/runtime receipt、各模块访问证明与剩余清理 SQL 仍 OPEN，不因主调用链已迁移而关闭整表。

### 必要差异验证和限制

命令、退出码、测试数量、原始失败和最终受测 JAR 摘要记录在本批 manifest/日志，不能用本文件的计划替代实际结果。定向入口为 `TaskOperationsServiceTest`、既有受理/装配/架构/冻结/库存门禁，以及 `B2TaskOperationsIT`：

- 准确旧、新制品共用 `TaskOperationsScenario` 和私有 MySQL：可见性/权限、取消队列账目、重复取消、cursor、真实 claim/fence、活动 UNKNOWN、问答任务 hold、结果实计与绝对过期上下文；结果读取阶段注入失败，检查 task、budget、reservation、event 同时回滚；真实双连接 budget 锁等待与有界释放，区分正常完成与超时/死锁。
- 准确旧、新 HTTP 控制实例对真实受理的 v4 scope：get 脱敏、actor恢复后不复活、events 拒绝、取消时状态与脱敏结果；另由独立 JVM 直接调用账本 events，排除 Controller 先调用 get 的影响，证明拒绝事务回滚后 afterCompletion 单独提交失效后记。后记仍按原方式核算 scope/history 元数据，不能据此宣称所有存储计数不变。使用空 prior 集合，不代表全部非空 scope 解析组合已覆盖。
- 两个真实 worker JVM、私有受控问答 provider：HTTP取消 OPEN 问答、TOOL预留实际结算1、幂等、fence、关闭后拒绝答复及公开 SSE 回放。不是付费效果/业务评分验收。

开发自测不代替独审/QA。非空付费账本异常组合、真实 read_image/付费视觉、完整 scope 组合与维护锁排列继续 OPEN。原失败/中止制品及证据保持原字节；旧1205的原异常 SQLState为40001，旧摘要HY000不准确，未发现采集层转换依据；不改写两份历史原记录。

本批首次 compile 被 Checkstyle 的星号 static import 拒绝，修为显式导入。首轮 verify 的22项单测及问答/scope集成通过，直接业务对照也一致，但夹具错误地比较了两个独立变更场景的事件实计：get均3165 bytes，events旧4107/新4113；原场景保留的QUESTION_STORAGE=2097152使下一场景的tenant_budget标量增加6 bytes。已改为在变更场景前比较同一已发布任务及同一预算输入，未修改产品计量或忽略真实增量。第二轮固定任务读取和旧制品实计通过，但证据驱动误用不支持Instant的持久化JSON codec，输出证据时报错；改为完整record值输出。原始失败、驱动与准确中间JAR全部留档，最终包可由XOR差异重建这些原JAR并核对摘要；不将失败写成通过。

第三轮 verify 的22项单测、普通/产物/事件差异和双worker问答集成通过，SpotBugs为0；补充的直接events观察者在旧制品上未满足“scope尚未失效”前置条件。原始SQL显示驱动计数前控制实例的周期校验已观察撤权并后记失效。夹具改为先停本次私有control、再撤权及直接events，避免多个观察者混淆回滚证据，随后恢复HTTP控制实例。固定该轮JAR，仅通过failsafe补跑这项集成；第三轮完整verify的失败日志保持原字节，合计通过数由manifest中的两阶段JUnit映射核算，不伪造合并报告或声称完整verify最终退出0。

最终 scope 单项 failsafe 退出0、BUILD SUCCESS，旧/新制品的直接失效后记及HTTP断言均通过。准确同版JAR累计22项单测/契约、3项定向集成通过，SpotBugs 0；其中2项集成取自第三轮失败verify内的通过用例，另1项来自不重新构建的最终failsafe补跑。三组清理回执均确认子进程退出、provider停止、私有容器和临时目录移除，无清理失败。

下一具体切片是已授权 B2 的 artifact 保留/清理 `cleanupArtifacts` 业务与其 Mapper/存储端口收口，再按实际依赖处理模块/runtime scope及其清理剩余。无成员信息或权限阻塞。回退仅指整体回到准确旧源码/JAR并保持唯一写入；未执行部署回退、数据重置或混版 worker 双写。

XIAO-60保持in_progress，PR22草稿；B3–B5、初级、61/62不激活，不运行或等待CI，不部署、不接真实Key/外部计费/共享数据，不改共享服务、daemon、代理或FRP；T48和新功能仍暂停。
