## Todo替换、成功重放与owner读取增量

基线为 `fbf8ff65c619a68c33851fc18ada13d157098252` / tree `01f2150775e67b20eb40f21eb2907ba6788fec4a`，沿PR22原分支交付；本批不表示B2或全仓重构完成。

`JdbcLedger.replaceOrResult/getTodos` 现委托 `TodoPersistenceService` / `service/impl/TodoPersistenceServiceImpl`。Service拥有原资格、完整Response calls扫描、pin/版本、期望revision、来源证明、预算/事件/计数/CAS、成功重放与owner字段构造规则。`TodoRecords` 的命名字段视图按原位置惰性访问原Map，codec/List/call保持原引用及重复成员读取。`MapperTodoRecords` 仅做单项I/O和codec适配；七条原业务SQL接入现有五个表属Mapper（Task、TenantBudget、Reservation、TodoInvocationRead、TodoProjectionRead），无新增Bean或schema。

原 `leaseTx → ConnectionCallback` 同连接会话机制保留在窄基础设施方法 `todoSession`：Duration.toSeconds强转int、remaining<1、普通Statement/getInt保存、Math.min(2,remaining)设置、原try/finally恢复和资源关闭。该读取仍实计1点/0bind，不扣除。`todoResultSource` 仅由private改包可见以供适配调用，方法体和既有Service内部未改。其他215个Ledger方法按准确两版逐方法核对（该方法仅显式归一访问修饰符），目录事务及既迁业务不变。

实际迁7旧业务JDBC点/32bind，SQL消除0。重捕剩余 **27静态 / 29动态 / 60bind**，未以字面量归零关闭35表或外部receipt：

| 文件 | 静态点 | bind | 剩余完整范围 |
| --- | ---: | ---: | --- |
| JdbcLedger | 15 | 31 | 评测文件绑定2/6、评测文件读取1/2、图像报告来源4/12、materialBody2/6、requireScope1/2、sessionOwner1/3、schema check2/0、directoryTransaction及todoSession各1/0 |
| JdbcExecutionScopes | 6 | 4 | receipt/绑定run/环境资格 |
| JdbcSessionScopes | 3 | 22 | create调用链 |
| SessionQueryTransaction | 2 | 0 | 原会话基础设施 |
| JdbcExecutionRecords | 1 | 3 | 问答checkpoint投影准备 |

后续建议按完整评测报告材料用例接续：evaluationFile/bindEvaluationFile/materialBody/imageReportContext及其专属资格与读取；实际范围、授权和事务边界需由PM按最新合同确定。sessionOwner/Scope/create/基础设施等仍单独登记，不能顺带预扣。

## 本次验证与原始证据

首轮 `spotless:apply test` 运行TodoPersistenceServiceTest、TodoWorkflowMapperTest、TodoContractTest共55项，通过。补充完整calls晚错误及PURGED不解码后，以 `spotless:apply verify -Dtest=TodoPersistenceServiceTest,TodoWorkflowMapperTest,TodoContractTest,PersistencePathTrackingTest,PackageBoundariesTest,RefactoringNegativeGatesTest,B2MapperContractsTest -Dit.test=B2TodoWorkflowIT` 执行66项定向检查及1项IT。最终退出码、静态结果和同版原JAR绑定见交付manifest与完整日志。

IT复用已有隔离Experiment/账本录制器，真实私有MySQL8.0.46、paid=false，两侧均由公开submit/claim取得真实RUNNING/ACTIVE/live租约及完整授权，然后使用合法显式response/intent/invocation（按记录时projection revision绑定）。不启动worker/provider或真实工具。每侧14公开调用、290SQL（39 Statement / 251 PreparedStatement）、634绑定，20条本侧参数关系，七业务SQL均到达；静态32bind另经真实Mapper装配核过。覆盖首次计费/事件/revision CAS、成功重放及历史成功重放无重复收费、第二revision、UNWRITTEN/PRESENT非空/空/ABSENT owner视图、隐藏owner、权限及fence拒绝。PURGED及首错/引用/计数短路由定向测试核，不冒称数据库保留清理验收。

每次公开调用至多一个观测连接。会话prior/after均为50，原Statement读取、设置2和恢复prior顺序实核；自然INVOCATION_MISMATCH/ACCESS_REVOKED/FENCE_REJECTED后也恢复。旧新SQL序列经冻结全列展开归一后相同，绑定值按本侧生成ID/seq/hash/revision关系验证，不跨侧硬比随机ID/时间。

真实JDBC API变化保留：setObject18→0、setLong30→42、setInt0→6，setString574及setNull12不变；execute184→204、executeQuery96→86、executeUpdate10→0；getObject2131→1815、getString836→1042、getLong361→427、getInt300→344、getBoolean36不变。原getter值、类型、NULL与重载均留原记录，不能称JDBC调用完全相同。

两侧私有36表含worker_process逐表FULLBEFORE/FULLAFTER相等，四项cleanup=true、failures空，fixture请求全空。这里只证明外层rollback实际恢复及清理，未验逐调用持久提交、UNKNOWN、独立afterCompletion、真实工具/文件系统或付费路径。

初次生成session适配语法导致Spotless失败；修正后首次编译因直接访问Ledger私有db失败，随后恢复为Ledger内窄基础设施方法，未扩大db可见性。原源码/class及完整日志均在failures封存。冻结方法审计首次把todoResultSource访问修饰符改变误判为内部改变，保留原失败，再显式归一唯一修饰符并核相同方法体。没有把失败、开发检查或PM离线核证移记为独审/QA。精确源码SHA/tree、同版原JAR字节、源码blob/class绑定、分片摘要和原始记录随本轮交付。
