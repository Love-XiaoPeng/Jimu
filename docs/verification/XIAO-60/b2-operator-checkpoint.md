## B2 operator绑定／只读评估部分检查点

接续 `ea57cd8b4e51d7843848f3872985cdfba90ba294`，tree `c1bb35bd2a23a0b78650dc1072720aaed504f358`，旧JAR SHA256 `0563df5941485e11cb28cdd7ef850887252b511cf51b1772074001a932ef59a4`。本批准确源码/tree/同版JAR及最终验证结果由附件manifest锁定。B2未完成，35表＋只读receipt全部OPEN；开发自测不替代独审、QA、业务验收或合并。

### 入口、规则和实际持久调用

| 入口 | 实际调用链 | 涉及表及事务 |
| --- | --- | --- |
| 受信 `ExecutionScopeOperator`／原 `ExecutionScopeBinding.bindOnce` | `ExecutionScopeServiceImpl` → `ScopeBindingRecords` → `MapperScopeBindingRecords` → `ExecutionScopeMapper.selectMaps`／`OperatorMapper` | evaluation_budget → tenant_budget锁；scope唯一插入；task/dispatch/reservation账本证明和只读receipt |
| `WebFetchReconciliation.assess` | `WebResultAssessmentServiceImpl` → `WebResultRecords` → `MapperWebResultRecords` → 四个实际MP selectMaps | task/invocation/event/step，只读REPEATABLE_READ事务 |

两个纯JavaService持有规则及事务编排。绑定验证保留原先顺序：输入/canonical/binder → 锁tenant+run → receipt/requirement/实例/数据库/审批/模型结构资格 → OPEN状态 → 已绑定幂等/冲突 → tenant_budget锁 → 冻结63/500000000上限与dispatch次数 → run/task/tenant/dispatch-reservation账本核对 → 唯一scope插入及readback。已绑定重试仍必须先通过当前资格与OPEN状态；不改变首次binder/时间，不补写历史task、不创建run、不释放或重算任何预算。

`OperatorScopes`仅承载内核类型化值；原runtime值及CLI输入类保留兼容。固定canonical、计划资源和模型结构验证经窄codec端口复用，不复制冻结Schema/模型资格算法。Service读取类型化Run/Receipt/Budget/Binding；Binding的原列名readback及ledger的行快照仅用于保留既有输出字节，不用于字符串列名业务判定。原WebFetchReceipts仍负责插件pin、原工具call、内容/快照摘要和公开结果Schema证明，不新增恢复/结算能力。

只读Service保留NOT_FOUND → WEB_RESULT_PROOF_MISSING → WEB_RESULT_PROOF_INVALID顺序，查询包含tenant/task/对象完整键；不因“证明存在”将UNKNOWN改为成功。无结果导入、重试模型/工具、费用结算或事件写入端口。原DataSource被同一个Spring事务管理器与SqlSessionTemplate复用；绑定保持数据库默认隔离，评估保持只读RR，事务10秒、Mapper原默认statement 5秒。显式query在单次运维操作内传递，绝对截止不在每条SQL重置；不将operator原无计量输出扩为新业务配额协议。

### SQL删除和MP

本批删除18个旧JDBC字面量调用点：绑定14、评估4；其中scope原读取有两个调用点。不是18个不同SQL正文。原文列于 `evidence/b2-operator-removed-sql.json`；不重计前批模型55、Invocation25、task.submit26及artifact3。`JdbcExecutionScopes.verifyBinding`旧规则正文和绑定专用构造器已移除，其他运行时scope/receipt读取仍在，明确OPEN。

普通scope/task/invocation/event/step读使用真实BaseMapper与固定Lambda Wrapper。专用投影实体不声明伪单列主键，`rowId`显式映射数据库id，并开启autoResultMap以保留原列名，不能让MP自动投影别名改变证明输入。锁、聚合、联查与插入使用参数化XML；全键/tenant、NULL比较、原ORDER BY与UTC_TIMESTAMP(6)保持。Mapper禁止SELECT *、外部SQL片段、by-id和双写。

外部receipt从不写入或假设主键；table-exists/0/1/多行语义保留。原record_version允许缺列或NULL，故先读固定列元数据，再用内部boolean选择原列或NULL；没有外部动态列名。此处会比旧SELECT *增加元数据查询，不能声称全部SQL／网络往返不变。其他运行时receipt仍用旧实现，不能以本用例关闭receipt表。

独立operator装配禁用MP banner/日志实现，保留单JSON stdout；仅受信独立运维入口使用，不改变control/worker的原装配。runtime→persistence的精确CLI装配例外仍在；最终bootstrap统一治理属于后续范围，不将本批说成全仓依赖已收口。durable=false不创建这些显式运维适配器或DataSource。

### 差异验证与失败保留

必要验证入口为 `OperatorServicesTest` 与 `B2OperatorIT`，后者调用 `tools/xiao60_b2_operator_verify.py`。只运行本批相关verify，原始日志、退出码、测试数及受测JAR以manifest为准。fixture只使用私有MySQL8.0.46、受控本地provider/TLS和合成paid-run/receipt，不使用真实Key或外部计费。

```text
JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java
mvnw.cmd -B -ntp spotless:apply
mvnw.cmd -B -ntp verify "-Dtest=OperatorServicesTest,ExecutionScopesTest,WebFetchReceiptsTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest" "-Dit.test=B2OperatorIT"
```

受影响范围：实际保留web结果后崩溃、双worker恢复UNKNOWN；旧/新制品对照证明及篡改/缺失/tenant拒绝；绑定receipt缺表/0/多行/错误锚点、计数/账目拒绝、真实进程在物理commit前后退出、重复与不同binder重试、冲突及STOPPED拒绝、两个binder并发、外部可选record_version；实际run锁等待后受控释放。同DS只读RR用另一个连接提交变化后验证第二次真实MP查询仍见原快照，并验证写拒绝、显式结果实计和绝对deadline。

最终第四轮verify退出0、BUILD SUCCESS：20单测／契约＋1项定向集成通过，0失败/错误/skip，SpotBugs 0。该集成包含16项旧/新只读证明决策与13项旧/新绑定结果对照；相同微秒时间的完整readback和首次binder保持。旧/新实际run锁等待均采集到PRIMARY上的X,REC_NOT_GAP和请求/阻塞事务、线程及tenant/run键，受控释放后正常完成。只读事务中JDBC写入被驱动拒绝（SQLState S1009），Mapper与Spring同连接，结果实计为8 reads／6564 bytes／80 operations；此实计只适用于该驱动已测查询集合，不扩大成所有operator SQL/往返不变。最终所有本次子进程、容器、TLS/provider和私有目录清理成功。

第一轮verify的20单测通过，集成在新制品首次正向评估失败：MP把id投影为rowId，原证明读取id为空并抛NPE。原SQL、异常、JAR摘要及清理回执保留；修正autoResultMap后另记最终结果，不追认为首轮通过。第二、三轮分别完成旧/新16项只读评估对照，随后合成绑定夹具错误地混用公开网页逻辑URL作为模型endpoint、漏配该loopback endpoint的allowed-origins，在旧制品生成输入时被原资格校验拒绝；已改用自有loopback模型端口并补夹具白名单，不放松产品规则。原日志、中间JAR摘要和清理均保留。后续fixture把提交成功的scope时间固定为同一个已知微秒值，再比较旧/新完整readback及重试actor/时间；这不表示所有SQL实计或网络往返不变。

### 完成、剩余与下一切片

关闭的只是上述operator绑定及只读评估用例的Service／类型化端口／MP或Mapper实现切换和对应旧SQL删除。原授权、全键、锁序、唯一写入、媒体修复、其他用例fence和结算逻辑保持。35表＋只读receipt整体仍OPEN；本批并不关闭其他task读/取消/事件、runtime scope、模块和清理路径。

下一实际切片建议 `task.get/cancel/events` 的完整Service／类型化端口／Mapper调用链及对应旧SQL删除，再接已授权B2模块/清理剩余。真实read_image／付费视觉、全部scope组合、维护锁排列、非空付费账本所有异常组合未覆盖；原付费拒绝、历史失败及1205原始SQLState=40001与旧HY000摘要不一致的说明继续有效。

无成员信息或实施权限阻塞。回退仅指整体回到准确旧源码/JAR且保持唯一写入；没有执行部署回退或混版双worker写入。私有测试清理回执须覆盖本次进程、provider/TLS、容器和临时目录。XIAO-60保持in_progress，PR22草稿；B3–B5、初级、61/62不激活，不运行或等待CI，不部署、不接真实Key／外部付费／共享数据，不改共享服务/daemon/代理/FRP、不重置数据，T48与新功能继续暂停。
