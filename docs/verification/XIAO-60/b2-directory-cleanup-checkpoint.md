## B2 工作目录保留／清理部分检查点

从源码 `4b9679a38024842a8f508d74d11e46177842a921`、tree `9863fcc38e1d78114a4bcc48b02d959b93c5f98d` 接续。准确旧JAR SHA256 `7a36857e38676feb9f4016e8dca7c3a46378c0afe4533db38b648a62e3c52777`；本批准确源码/tree/同版受测JAR由交付manifest锁定。B2未完成，35张表＋只读receipt仍全部OPEN；本批不表示B3目录业务批次完成，开发自测不替代独审/QA、业务验收和合并。

### 实际调用和删除

`LedgerMaintenance → ExecutionLedger.cleanupArtifacts → JdbcLedger.cleanupArtifacts → cleanupDirectories → DirectoryRetentionServiceImpl.cleanup → DirectoryRetentions / Workspace → MapperDirectoryRetentions / WorkspaceDirectoryRetentions → DirectoryRetentionMapper / 五个真实MP Mapper / 原SecureWorkspaceStore`。

Service持有终态、到期、writer、UNKNOWN、活动调用/dispatch短路规则，正文hash/UTF8字节元数据与精确tombstone、PURGING/generation/receipt恢复、三段短事务和事务外文件清单/删除编排。类型化Candidate/Projection/Body/Content/RetainedBody/LockedTask/Workspace隔离Map/JDBC/MyBatis和具体存储类型。适配器只映射原事实及具名操作；Payloads及SecureWorkspaceStore清单、POSIX验证和物理删除实现不改。工作区适配器通过supplier读取原可后配置的store，scoped ledger仍共享原配置。

删除20次旧静态JDBC调用：cleanupDirectories与安全探针18次、共享directoryProjection与directoryStorage聚合2次。替换为16个参数化XML statement和5条实际MP读路径；`now` XML用于本用例原数据库时钟调用，其他模块的共享questionNow字面量仍保留，不另计删除。静态库存345→325、动态23不变；绑定库存1114→1066。原SQL、参数、键、锁序、事务及替换见 `evidence/b2-directory-cleanup-paths.json`。不重计前批迁移。

共享task_directory投影显式保留原19列并继续全部版本/绑定校验；四类正文集合只读取原逻辑实际消费的正文与键，不再SELECT *。MP Lambda Wrapper均带tenant_id/task_id；step/invocation/model_dispatch使用rowId映射SQL id，避免MP隐式识别单列主键。五个Mapper无by-id方法；复合键更新仍使用原tenant+id（path为tenant+invocation_id）完整主键。未添加额外状态/任务谓词、双写、DDL、协议或限制。共享DirectoryStorageLimit复用原2097152上限与65536 headroom，其他目录调用者也使用同一原规则。

### 事务、文件系统与恢复边界

| 阶段 | 保留的实际边界 |
| --- | --- |
| 候选 | 事务外有界32条跨tenant扫描，原到期/三终态/native/writer/UNKNOWN/活动调用/模型条件与排序。候选自行带tenant/task，非新增公共维护授权入口。 |
| 第一事务 | 原同DS/UoW，tenant budget→原locked(scope来源task排序)/task→完整directory投影→PURGED/到期→终态→writer→reservation→invocation→model短路顺序。已有PURGING复用原generation/receipt；否则按原顺序保留各正文hash/UTF8长度，写原tombstone/NULL/retained digest，原存储校验后置PURGING与generation。 |
| 清单和第二事务 | planPurge在事务外。再以原budget/task顺序锁定，核PURGING/同generation/安全，若其他清理已有receipt则复用，否则原容量校验后按filesystem_receipt IS NULL持久化。 |
| 物理删除 | 事务外使用持久receipt调用原purge；原异常传播，保留PURGING/generation/receipt供后续重试，不重新执行provider或效果。未将整个清理纳入长事务，也未改变文件系统锁。 |
| 第三事务 | 同锁序；已PURGED幂等返回，否则核PURGING/generation/receipt/安全→标PURGED→原幂等directory-purged事件及task序列/revision→DIRECTORY_STORAGE/RESERVED完整hold更新恰一行→原8388608 budget释放。任何失败回滚该阶段，已完成的前段及文件事实仍可恢复。 |

显式query、实际ResultSet计量、绝对deadline和beforeCommit沿用；原权限/fence、scope失效后记/特殊拒绝提交及媒体锁顺序通过既有锁/事件/UoW保留。内容投影收窄会改变计量，不声称全部SQL/元数据查询/网络往返不变。

### 差异验证与保留限制

选择 `DirectoryRetentionServiceTest,WorkingDirectoryContractTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest` 和 `B2DirectoryRetentionIT`。同一DirectoryRetentionScenario运行准确旧/新JAR，实际产品入口cleanupArtifacts。私有MySQL8及真实control/双worker→本地模型→read/write/cd/read/edit生成文件、路径绑定、正文和账目，另一tenant同样实际生成文件；停止生产者后仅修改唯一私有夹具生命周期与故障状态。

驱动核未到期/运行/NEEDS_RECONCILIATION、HELD_UNKNOWN/usage UNKNOWN、未证明退出writer、活动invocation/dispatched model保留；三终态清理及重复调用；正文tombstone、完整原metadata hash/UTF8字节、NULL路径、generation/receipt、事件/账目只释放一次。三个阶段的SQL45000故障分别核整体第一阶段回滚、第二阶段无receipt且文件保留、最终阶段文件已删但PURGING/账目保留后可恢复；私有测试actor在持久receipt后新增文件，核原存储inventory拒绝与同receipt恢复；晚期绝对deadline核最后事件/结算回滚后恢复。两条独立产品清理连接同时等待原budget前缀，采集真实performance_schema锁等待/innodb事务，再有界释放并核一次结算。其他tenant文件与事实、原invocation输出摘要及task fence保留；revision沿原event每次递增一次。

前期失败保留：Checkstyle拒绝3个通配符import；首次54项单测中独立装配断言发现MP隐式id主键（1失败），已用rowId映射并加入生成SQL/无by-id断言；工具调用误用未安装的mvn，随后使用仓库Wrapper。首轮完整定向verify的57项单测通过，但实际任务生成夹具漏了写前read观察，以FS_NOT_OBSERVED失败，尚未进入旧/新驱动；仅修夹具，准确失败JAR、日志/JUnit/SQL表快照/驱动及清理保留。

第二轮57项单测通过，两个实际任务已成功生成，随后测试误引用不存在的legacy_task属性而中止，仍未进入旧/新驱动；已删除该误引用。第三轮57项单测通过，准确旧JAR已完成前7组（含三事务故障及物理清单拒绝恢复），晚期deadline夹具按state列计数误包含step行，提前在第一阶段截止，最终PURGING断言失败；改为测试DataSource在最终INSERT INTO event实际执行后限制同一query绝对deadline，避免依赖旧/新投影形状，仍要求最终事务的event/revision/账目整体回滚。各轮准确制品、原源码及编译驱动、日志/JUnit/表快照/SQL和清理同样保留。最终验证数值、制品摘要及计量由本批manifest和原始日志锁定，未运行项目历史全矩阵。旧1205原异常40001与旧HY000摘要差异说明沿用：摘要不准确，未发现采集转换依据，两份历史原记录保留。

第四轮完整定向verify退出0、BUILD SUCCESS，57项单测/契约＋1项定向集成通过，SpotBugs 0 bugs/0 errors。准确受测JAR SHA256 `fb1c21ea341a93555deda83d56cd14270e7f200c9740b063d6e120dc35fc9a4b`，43806813 bytes；992个target/classes文件与JAR逐项一致，测试驱动不在产品制品。准确旧/新10组结果一致；受测清理均56 reads，旧124311 bytes/1811 operations，新111836 bytes/1492 operations；未到期保留均8 reads/0 bytes/0 operations。只限受测显式query实计，不表示全部SQL/元数据查询/网络往返不变。两个方向均采集了两个独立产品事务等待原budget锁的实际证据；事件和账目仅释放一次，无provider/效果重放。四轮私有MySQL、control/worker子进程、模型夹具和目录清理回执均全true、failures为空。前三轮失败不改写为通过；失败制品可按本批manifest精确重建。

完整scope/维护/文件list-delete锁排列、runtime scope/receipt、精确CLI装配、非空付费账本异常及真实付费视觉仍OPEN；此处物理删除恢复/双连接等待仅证明受测排列。下一实际切片为已授权B2的 `cleanupQuestions` Service/类型化端口/Mapper及旧SQL删除，之后其余模块/runtime scope/receipt/清理；无成员信息或权限阻塞。回退指整体准确旧源码/JAR，不混版并写；没有部署或重置业务数据。

XIAO-60保持in_progress、PR22草稿；B3–B5、初级及61/62不激活。不运行/等待CI，不部署、不接真实Key/外部计费/共享数据，不改共享服务/daemon/代理/FRP，新功能继续暂停。
