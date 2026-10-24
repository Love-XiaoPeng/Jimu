## B2 task.submit 部分检查点

接续准确基线 `dbcf58df28304fbc3a6d83c883da4b59f1360fbd`，tree `3c7c30938c62e2c56287a0bf2023551a6510de69`，旧 JAR SHA256 `377083663030f452d849e0d8c18f8b9a68dcf796ab4afa1a48743c5acf1b5bc1`。本批最终源码/tree/受测 JAR 由随交付 manifest 锁定。仍是开发检查点，B2未完成，不构成独审、QA、业务验收或合并。

### 已实现的真实路径

`TaskController.submit → TaskServiceImpl` 保留 HTTP 规范化/输入边界；`Tasks.submit → JdbcLedger.submit → TaskAdmissionServiceImpl → TaskAdmissionRecords → MapperTaskAdmissions → TaskLookupMapper/TaskAdmissionMapper` 执行受理。历史会话的受信 `submitSessionSearch → submitBound` 同样调用该 Service，保持外层绝对 deadline 和原事务，不额外开连接。直接账本调用和 HTTP 使用同一业务实现。

Service 持有能力组合、幂等/快照、当前配置/模型资格、业务策略、执行/存储/队列预算、评估材料与重复样本拒绝、各类预留选择及 accepted 原子编排。端口传类型化事实、具名写入和原序列化契约，不传 SQL 或数据库行 Map。共享评估绑定继续调用已交 `EvaluationBindingServiceImpl`，Invocation实现不另复制。模型/各能力的既有环境和契约证明仍交对应适配器；这不表示其全部内部规则/SQL已完成B2切换。

普通绑定查询用 MP `BaseMapper.selectMaps`、固定 Lambda wrapper 和显式 tenant/owner/idempotency 三元键；安装 pin 复用已有 MP 入口。实体不声明单列主键，也不注入 `xxById`。锁/数据库时钟/NULL更新/计账使用固定参数化 XML。受理语句24处、共享 actor/installation 权限锁2处旧 JDBC 调用已删除，共26处；不重计前批模型55处或Invocation25处。逐语句、原始SQL、参数类型/顺序和MP替代见 `evidence/b2-task-removed-sql.json`。

原 `LeaseUnitOfWork` 带 task Lease 类型，归入公开 `kernel.task.api`，方法及实现语义不变；通用 `kernel.transaction.port.LedgerUnitOfWork` 不再反向依赖 task，避免新 task Service 引入循环。成本算法调用现有 `kernel.budget.api.ModelCost`，不穿透预算domain，不放宽架构门禁。旧包class从本次制品中移除。

### 事务、键与锁序

沿原 DS、SqlSessionTemplate、TransactionTemplate、显式 query context；普通 null context 有效，显式上下文复用原绝对截止和物理结果实计；缓存关闭。无双写、新DDL或协议/冻结限额变化。

受理先读 tenant/owner/idempotency 的原 evaluation，或解析当前 execution scope/paid run；锁 evaluation parent，再锁 tenant_budget；历史来源按原 scope 排列，再核 actor、锁原幂等 task。首次受理随后按排序取得 installation 共享锁，冻结 pin、scope/model/评估绑定，写 task 及投影/预留/事件；原有媒体 evaluation-first 前缀不变。所有具名单行写限定 tenant+task/id；集合读保留原有谓词和有界 LIMIT，不误称主键查询。

重试保持原顺序：会话/目录/todo内容核权、ask初次核权→快照完整性/请求冲突→原 MCP/ask 补充核权→返回原View。普通重试仍先于新任务pin/队列资格；不会借本次迁移改变撤权重试语义。特殊作用域失效提交、Invocation材料后验拒绝/UNKNOWN及原子结算仍由原实现负责。受理拒绝整体回滚，避免持久化半个 task、预算或 accepted 事件。

MP普通绑定读固定投影 `id,evaluation_run_id`，旧版分别只读其一；实际计量字节/operation因此可能变化。Connector/J事务只读状态预检频率也可能变化。保留完整原始SQL/连接/事务和 QUERY计数，只比较明确记录的业务SQL与结果，不宣称所有SQL或网络往返不变。

### 必要差异验证与证据

最终完整生命周期命令为 `mvnw.cmd -B -ntp verify -Dtest=TaskAdmissionServiceTest,TaskAdmissionDifferentialTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,InvocationRulesTest,ModelFailurePolicyTest,RefactoringFrozenInputsTest -Dit.test=B2TaskIT#exactOldNewTaskAdmissionDecisions`，23单测＋1项普通差异集成、SpotBugs 0，BUILD SUCCESS。其重打包JAR摘要与verify-5不同，因此此前paid/worker通过不用于最终制品；固定该JAR后执行 `mvnw.cmd -B -ntp failsafe:integration-test failsafe:verify -Dit.test=B2TaskIT#syntheticPaidAdmissionAndQualification+actualDualWorkerAndSuccessfulEffectRecovery`，只补剩余两项，不再打包。`b2-task-integration-validation.json` 汇合两轮原始JUnit与三个夹具的相同JAR摘要；保留所有前次失败报告，绝不将verify-5失败报告改写成成功。最终23单测＋3项集成为这同一JAR的开发证据，命令、原始日志/退出码随manifest提交。源码库存重采后153个原入口均有映射；剩余414处字面量JDBC SQL、24处动态SQL、1265个字面量绑定，仅为静态库存，不能当逐路径验收。

本批验证范围：

- 同一驱动运行准确旧/新JAR，比较普通受理、tenant隔离、重试/快照冲突/腐坏、actor/installation撤权、满队列重试与拒绝、能力组合/功能禁用拒绝、问题task/storage/queue预留、插入后的计量拒绝回滚、绝对截止、同幂等键双连接barrier竞争。额外保留真实query读取/字节/operation实计及重复读取不缓存证明。
- 合成paid-run只补正常冻结材料与绑定/原paid重试、缺资格拒绝、合成阶段B受理、同样本双连接竞争四条受影响差异；不重跑旧21项绑定全矩阵。没有provider调用、真实Key、计费或评分验收授权。
- 两个实际worker在T1受控barrier持有不同task，按新受理完成受控provider/本地TLS/文件/发布/下载；成功write后的进程恢复验证不重复效果/CAS/结算。
- 实际SQL日志保留连接、事务边界、锁与提交/回滚，清理回执记录私有MySQL、子进程、provider/TLS及临时目录。并发证据是明确的受控交错，不穷举锁排列。

原始SQL复核选取普通受理、问题受理、插入后拒绝回滚三对事务，各自保持单物理连接及归一化后的业务语句顺序；同键竞争两连接均提交但仅一连接INSERT。预检语句逐位置另列。显式重试计量原版为5 reads/3520 bytes/93 operations，新版为5 reads/3566 bytes/94 operations，差异来自固定绑定投影多读id字段；重复读取均按实际结果累计两倍，没有缓存或重复实计。原始字段/值和统计均在日志，不将归一化结果扩展为全部SQL相同。

### 失败、覆盖限制和剩余

本批前置构建曾因显式import门禁、包内可见性失败；首条verify因新增task模块循环及MP单列主键推断失败，后续编译/源码库存采集暴露实体重命名错误；另一次verify发现跨模块访问预算domain。已按现有公开API及严格门禁修正，原始日志/失败单测报告保留；没有把中间成功编译当最终验证。

verify-4的23单测、paid与worker两项集成通过；普通差异驱动在旧制品的晚期计量拒绝处因只捕获直接TaskFailure而失败，原始MyBatisSystemException→PersistenceException→TaskFailure cause链、回滚SQL、全部清理均保留。只修测试驱动读取原始cause并额外记录外层异常，不修改产品异常映射。同时核对新幂等锁SELECT的列展开，移除从通用锁语句带入的两个计算别名，只投影原SELECT-star的物理列；普通MP绑定仍固定多投影id/evaluation_run_id。按新的准确JAR复验本批三个集成，而非挪用中间JAR的通过记录。最终结果与后续失败按交付原始日志据实记录。

verify-5的普通差异在旧制品完成16条断言后，收尾cancel因基础Experiment漏授user_question锁所需权限而失败；这是测试夹具缺项，并非新制品已通过。仅在本次独立私有MySQL补齐既有 `application_grants.py` 规定的该表INSERT/UPDATE，保存SHOW GRANTS，产品权限契约和共享环境不变。原异常、完整SQL/事务、已通过16条旧版断言及清理保留。最终只复验这一失败项，并记录实际旧/新两侧结果。

证据SQL选择器首轮只按幂等key筛选，误选了有意复用同key的tenant-b隔离事务，报候选2条；已限定tenant-a后核三对事务。首版选择器和失败记录保留，原始SQL不改写。最终额外paid/worker补验仅为锁定相同重打包JAR，未重跑历史完整矩阵。

前批所有失败、证据工具失败、合成资格限制及付费视觉拒绝保留。历史1205按原始异常记录为SQLState `40001`；旧摘要 `HY000` 不准确，没有证据表明是采集层转换；两份旧原记录不改写，也不重做整套历史制品。

35张迁移表及只读receipt均保持OPEN。task的get/cancel/capabilities、各scope/session/directory/todo/media等共享帮手尚含旧SQL，Mapper接口或本次探针不等于表已迁完。真实read_image/paid视觉、完整scope组合、全部维护锁排列未覆盖。artifact下载和operator用例仍未下沉/切换；B2剩余逐项补真实业务证据，不能凭新增Mapper关闭。

下一具体切片为 artifact下载授权/保留状态/元数据校验与存储读取下沉Service，走真实MP/Mapper并移除该用例旧SQL，然后接operator及B2剩余；无成员信息或权限阻塞。回退须整体回到准确旧源码/旧JAR，不混版双写；未实际执行部署回退。B3–B5、初级、61/62独审/QA不激活。保持不运行/等待CI、不部署、不接生产/真实Key/外部付费/共享数据，不改共享服务/daemon/代理/FRP，不重置数据；新功能暂停。
