## B2 todo保留／清理部分检查点

接续源码 `f43e8a4a2a71921da44fbc98a37a617e47dd48f3`，tree `b517c5d9220787f9e8f44e9d5a114a820240b44f`，准确旧JAR SHA256 `6e29f4d33d03745f75d6c19c1a34696e41f0026ffbf364073e124a01480d4506`。本批准确源码/tree/同版JAR由manifest固定。B2未完成，35表＋只读receipt全部OPEN；本批不计B3独立todo业务完成，开发自测不替代独审/QA、业务验收或合并。

### 实现、实际路径与删除

`LedgerMaintenance → ExecutionLedger.cleanupArtifacts → JdbcLedger.cleanupArtifacts → cleanupTodos → TodoRetentionServiceImpl.cleanup → TodoRetentions → MapperTodoRetentions → TodoRetentionMapper / TodoStepReadMapper / TodoInvocationReadMapper / TodoDispatchReadMapper / TodoProjectionReadMapper`。

Service持有原候选之后的绑定校验、清理／保留／终态／writer／UNKNOWN短路、正文及hash/UTF8元数据、精确tombstone/NULL、摘要65536字节上限、事件先于严格reservation释放及固定退款的完整编排。类型化Candidate/Projection/BindingVersions/Body/Content/RetainedBody/LockedTask隔离Map/SQL；适配器只转换事实、编解码和具名持久化。原budget/locked(scope来源task排序)/task、writer和event含todoMetadata检查复用既有真实调用，同SqlSession/DS/UoW、query及权限/fence/原媒体锁序不变。

删除本用例14处旧静态JDBC调用，11个原SQL转参数化XML，加一条原DB时钟语句，共12个XML statement；step/invocation/model_dispatch三条正文集合与一条projection完整键读取实际走MP。静态库存313→299、动态23不变、字面量绑定1033→999，不重计前批。三种正文实体rowId映射SQL id；四个Mapper无伪单列主键及by-id，所有MP谓词含tenant/task。XML逐项比对原参数、谓词、锁及值，无`${}`、无双写。

本用例不再调用共享`todoProjection`旧SQL／适配器规则，而由MP读取实际消费的绑定/状态/时间字段，Service按原顺序核定；其他todo业务仍使用的共享SELECT和规则未删、不计删除数。共享`questionNow`仍供其他问题业务，迁移路径自己的同值时钟Mapper不额外计旧SQL删除。`retainQuestionBody`最后调用方迁移后删除该旧helper。原`todoMetadata`、独立todo执行与受理不在本切片关闭范围。

### 锁、事务、拒绝及保留边界

| 阶段 | 原语义与边界 |
| --- | --- |
| 候选 | 事务外跨tenant有界32条，projection非PURGED且retain_until已到期、task三终态。原反连接排除native未STOPPED、无quiescent/stopped凭证的writer、HELD_UNKNOWN/UNKNOWN使用、非TODO_STORAGE RESERVED、RUNNING/OUTCOME_UNKNOWN invocation和DISPATCHED model；按retain_until/tenant/task排序，保留防UNKNOWN占满批次的原筛选。 |
| 锁与绑定 | 每候选一个原tx/coreWork：tenant budget→原locked(scope排序来源task)/task→MP projection。原record/todo版本→binding SHA→JSON内版本→精确当前binding，拒绝码/解析异常及顺序不变。随后PURGED短路时钟、到期按DB UTC当前时间重验、三终态→writer→reservation→invocation→dispatch。 |
| 正文与摘要 | task/step/invocation正文及model_dispatch.usage_snapshot进入原task_todo_projection.retained_digests。原hash/UTF8长度、TODO_PURGED精确tombstone、nullable IF、step保留request_digest保持。编码摘要>65536 UTF8 bytes在子表写后、task/projection写前抛TODO_STORAGE_LIMIT，整笔回滚。 |
| 事件与账目 | task/projection PURGED→原todo-purged事件、task序列/revision与todoMetadata guard→tenant+task:todo-storage+RESERVED必须更新恰1行→固定减2097152 todo_storage_held。原hold SQL无category谓词。0行抛RESERVATION_STATE，必须连同先前正文、摘要、事件一起回滚；与问题清理0行允许提交的规则不同。 |
| 失败与恢复 | SQL/event、guard拒绝或事务中下一query观察到截止，整笔事务回滚并传播，下一次从原候选恢复；无文件系统/provider/外部效果调用，不重放已提交todo效果。当前权限/fence、scope失效后记、特殊拒绝提交和原结算路径均未跨模块修改。 |

**原core UoW最后beforeCommit截止检查缺口仍OPEN。** 上批问题清理的event后才截止、以及本路径最后退款SQL后才截止，原core UoW均没有最后提交前query检查，可能先提交、下一查询才超时。本批不跨模块修复；原绝对deadline验收要求不豁免。事件后、hold前截止的整笔回滚另行验证，不能概括为所有截止均回滚。实际计量按路径保留，MP投影收窄不代表全部SQL/元数据查询/网络往返不变。

### 定向验证、失败与剩余

选择`TodoRetentionServiceTest,TodoContractTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest`及`B2TodoRetentionIT`。前置61项单测/契约通过；完整定向verify与准确JAR、原始日志/JUnit/SQL/事务/锁等待和清理由本批manifest锁定。

同一TodoRetentionScenario对照准确旧/新JAR。真实私有MySQL、control/双worker→本地模型→同owner三次todo提交/四次model dispatch与另一tenant真实提交；生产者停止后仅在私有夹具上调整生命周期、注入故障，保留真实provider请求总数核对无重放。覆盖原候选、UNKNOWN/活动invocation/model、writer收据、三终态清理、正文/NULL/摘要/来源与计数/效果凭证、0行hold拒绝回滚、坏binding/hash/解析版本/精确快照拒绝、真实候选扫描后修改保留时间/状态再锁内重验、六处SQL45000失败回滚恢复、两种截止边界、实计无缓存、双独立产品连接budget前缀实际等待及一次退款/事件。元数据大小边界由Service单测覆盖；不将其外推为真实超大账本或全部native/scope/维护排列已验。

原daemon中断、先前失败、中间制品、付费视觉拒绝及旧1205原异常40001/旧HY000摘要差异说明全部保留，不重建历史全套制品或重测无变化全矩阵。本批失败若发生，准确代码/驱动/制品、原始日志/JUnit/SQL及清理按轮存档，不覆盖。

首轮完整verify失败于新夹具的锁内重验断言：用于候选扫描后变更projection的SQL钩子只匹配`SELECT p.tenant_id,p.task_id`，同时命中todo和稍后的session-history扫描，使命中次数断言不成立。原始runtime SQL保留两个扫描，驱动已限定完整`FROM task_todo_projection p`；未修改产品门禁或把旧版失败算作新版通过。准确失败JAR、源码/编译驱动、SQL、日志/JUnit与成功清理回执留档。第二轮只因这行修改的缩进Spotless失败，未构建新JAR/启动私有库，原文和日志保留后格式修正。

第三轮完整定向verify退出0、BUILD SUCCESS：61项单测/契约＋1项集成通过，SpotBugs bugs/errors均0。同版JAR SHA256 `9da6affa7aa45fd284abcf0c5bc14052248962e1d19053346b8a7624816b2bb0`，43867999 bytes；1039个编译文件与JAR逐项一致，无测试驱动混入。准确旧/新11组结果一致，真实两连接budget锁等待后一次退款/事件，无provider/效果重放。受测清理均32 reads，旧60836 bytes/1155 operations，新52974/948；保留均7 reads/0 bytes/0 operations。计量差异来自本批正文/投影收窄，仅限受测路径。首轮与最终轮均收回全部子进程、移除专用MySQL容器/私有根目录、停止受控provider，清理failures为空；格式失败轮未启动这些资源。

下一具体切片为已授权B2的`cleanupSessionHistory` Service/类型化端口/真实Mapper及旧SQL删除，再接其余模块/runtime scope/receipt与维护清理。当前无外部执行或成员授权阻塞；截止缺口后续单列修复及必要受影响验证仍待交。35表＋只读receipt全部OPEN，完整scope/维护/文件锁排列、精确CLI装配、runtime scope/receipt、非空付费账本异常及真实付费视觉仍OPEN。回退指整体准确旧源码/JAR并保持唯一写入，未执行部署、数据重置或混版并写。

60保持in_progress、PR22草稿；B3–B5、初级及61/62不激活。不运行/等待CI、不部署、不接真实Key/外部计费/共享数据，不新增DDL/协议/冻结限额/业务功能，不改共享服务/daemon/代理/FRP。
