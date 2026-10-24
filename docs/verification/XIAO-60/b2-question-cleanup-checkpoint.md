## B2 问题保留／清理部分检查点

接续源码 `d946f6761e578425caa30c1a3c1c0bfbf5741192`、tree `f2b90e8505d94d51f9205fbc7e95b62e4457aed4`，准确旧JAR SHA256 `fb1c21ea341a93555deda83d56cd14270e7f200c9740b063d6e120dc35fc9a4b`。本批准确源码/tree/受测JAR由交付manifest锁定。B2未完成，35表＋只读receipt全部OPEN；开发自测不替代独审/QA、业务验收或合并。

### 实际实现与SQL删除

`LedgerMaintenance → ExecutionLedger.cleanupArtifacts → JdbcLedger.cleanupArtifacts → cleanupQuestions → QuestionRetentionServiceImpl.cleanup → QuestionRetentions → MapperQuestionRetentions → QuestionRetentionMapper / QuestionStepReadMapper / QuestionInvocationReadMapper / QuestionBodyReadMapper`。原budget/locked(scope有序来源task)/task、writer、View、event和同DS/UoW复用既有真实调用链。

Service持有保留状态、原View终态与writer/UNKNOWN拒绝顺序、正文摘要/UTF8字节、精确tombstone及NULL规则、逐问题独立metadata、task metadata、条件释放账目及最后幂等事件。类型化Candidate/Body/Content/RetainedBody/LockedTask隔离Map/JDBC/MyBatis；Mapper适配器仅转换原事实和原Payloads metadata结构，不承载清理编排。原View投影及其artifact读取继续调用既有TaskOperationsService，不通过简化state列表移除原查询或改变其终态定义。

删除本方法12处旧静态JDBC调用，替换为9个参数化XML statement与3条实际MP正文集合读取。静态库存325→313、动态23不变，字面量绑定1066→1033，不重计前批。MP均用tenant/task全集合谓词；显式列保留实际消费的正文和键，rowId映射SQL id，三个Mapper无by-id，未声明伪单列主键。原tenant+id schema完整主键更新、原重复tombstone参数、NULL IF、hold状态与budget常数逐项比对一致，无双写。共享retainQuestionBody仅供未迁移cleanupTodos保留。入口/SQL/参数/锁/事务对照见 `evidence/b2-question-cleanup-paths.json`。

### 保留的边界

| 阶段 | 原语义与事务 |
| --- | --- |
| 候选 | 事务外跨tenant有界32条；task.question_cleanup_state=RETAINED、三终态、task.created_at不晚于数据库当前时间减七天，created_at/id排序。七天条件原本仅在候选SQL，不额外在锁内重验，也不替换为user_question.retain_until。 |
| 锁与拒绝 | 单个候选一个原tx/coreWork：tenant budget→原locked(scope来源task排序)/task；RETAINED→原View.terminal→writer→reservation HELD_UNKNOWN或usage UNKNOWN→invocation OUTCOME_UNKNOWN，保持短路与额外View读取。View.terminal含NEEDS_RECONCILIATION，候选只选三终态；锁后状态变化分支仅按原谓词保留，不扩大运行覆盖。没有新增RUNNING invocation或DISPATCHED model拒绝。 |
| 正文与摘要 | task/step/invocation正文保留到task metadata；每条user_question的request/answer/receipt分别保留到自身metadata。旧hash与UTF8字节、QUESTION_PURGED精确tombstone、原NULL、step retained_request_digest和原question request/answer摘要不改。model_dispatch/usage不属于此清理，保持原事实。 |
| 账目与事件 | 正文及task PURGED后，以tenant+task:question-storage+RESERVED更新hold，原SQL没有category条件；恰1行才减原2097152 storage_held，0行仍提交清理与事件，不新增RESERVATION_STATE拒绝，不按actual/amount另算，不再扣question_tasks_held。最后原question-purged幂等事件、task序列/revision与全部变更同事务。 |
| 失败与恢复 | 无文件系统、provider、外部效果调用。事务中SQL/event失败，或退款后、event前由下一SQL观察到deadline失败，正文/metadata/hold/budget/event一起回滚并中断批次，下一次从原候选重试，无provider/效果重放。最终event SQL执行后才截止的边界另见下文，不能声称所有截止均回滚。 |

沿用同SqlSession/DataSource/UoW、显式query/真实结果计量/绝对deadline、原权限/fence、scope失效后记/特殊拒绝提交及媒体锁序。正文投影收窄按实际记录计量变化，不声称全部SQL、元数据查询或网络往返不变。无新增DDL/协议/限额/业务功能。

### 定向验证、失败和剩余

选择 `QuestionRetentionServiceTest,UserQuestionContractTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest` 与 `B2QuestionRetentionIT`。同一QuestionRetentionScenario运行准确旧/新JAR，入口为实际cleanupArtifacts；真实私有MySQL、control及双worker→本地模型→两条实际owner问题/回答/receipt，另一tenant完成回答。生产者停止后只调整本夹具任务生命周期和注入故障，无真实Key/付费或共享数据操作。

驱动覆盖候选保留期/非终态、UNKNOWN hold/usage/invocation、writer收据、三终态、逐正文hash/UTF8元数据及原NULL、已RELEASED/SETTLED hold导致0行更新、question retain_until与原task年龄条件、四处真实SQL45000拒绝的整体回滚恢复、退款后event前截止回滚、最后event后截止的原提交边界、实计无缓存、两个独立产品连接在budget前缀真实锁等待后一次释放/事件；保留另一tenant事实、model_dispatch全行、原question/invocation摘要、task fence与独立question容量。原响应/权限门禁不通过纯探针关闭，完整scope/维护/文件锁排列不在本批结论内。

前置18项定向单测/契约通过。首条verify在测试编译阶段因新驱动遗留旧类私有normalize方法引用而失败，已修正为本类引用；该轮没有生成新JAR或启动私有库，不能计作集成通过或伪造失败制品。原源码和编译日志/退出码保留。最终验证数值、准确受测制品及清理由本批manifest与原始日志固定。原daemon中断、历史失败、付费视觉拒绝和旧1205原异常40001/旧HY000摘要差异说明继续保留；不重建历史证据、不重跑无变化全矩阵。

第二轮verify的旧版对照在“event后截止必须回滚”的新夹具断言处失败：实际runtime SQL明确记录最后INSERT后COMMIT，随后下一查询抛SESSION_QUERY_TIMEOUT。原cleanupQuestions使用core tx/SpringLedgerUnitOfWork，不是带beforeCommit截止检查的SessionQueryTransaction；这条原边界此次不跨模块改变。驱动新增退款执行后event前截止的真实整体回滚，并对最后event后截止断言已提交且后续幂等恢复，旧/新都须符合。提交前最后截止检查缺口保持OPEN，不能以集成通过关闭或称deadline失败必然无提交。第二轮准确JAR、源码/编译驱动、SQL、失败日志/JUnit及成功清理回执保留。第三轮仅驱动日志行Spotless格式失败，尚未构建JAR或启动私有库，保留原文与日志后修正。

第四轮完整定向verify退出0、BUILD SUCCESS：18项单测/契约＋1项真实集成通过，SpotBugs bugs/errors均0。同版JAR SHA256 `6e29f4d33d03745f75d6c19c1a34696e41f0026ffbf364073e124a01480d4506`，43834592 bytes；1013个编译文件与JAR逐项一致，无测试驱动混入。准确旧/新10组结果一致，真实两连接在budget前缀锁等待后一次释放/事件，无provider/效果重放。受测清理均21 reads，旧22433 bytes/293 operations，新16704/144；保留均7 reads/0 bytes/0 operations。该差异来自原整行读取改为只读消费列，不外推至其他路径或全部网络往返。第二轮和最终轮均收回所有子进程、删除专用MySQL容器和私有根目录、停止受控provider，清理failures为空；编译/格式失败两轮未启动这些资源。

下一实际切片为已授权B2的 `cleanupTodos` Service/类型化端口/真实Mapper与对应旧SQL删除，再接其余模块/runtime scope/receipt及清理；当前无成员信息或权限阻塞。35表＋只读receipt全部OPEN，精确CLI装配、完整scope/维护/文件锁排列、非空付费账本异常和真实付费视觉仍OPEN。回退仅指整体准确旧源码/JAR并保持唯一写入，未部署、重置数据或混版并写。

XIAO-60保持in_progress、PR22草稿；B3–B5、初级及61/62不激活。不运行/等待CI、不部署、不接真实Key/外部计费/共享数据，不改共享服务/daemon/代理/FRP，新功能继续暂停。
