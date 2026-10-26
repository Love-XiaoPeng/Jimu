本批从 `d23a89efacb0bbc262205ab941be3c5ba9e13823` / tree `94c695e1076d155796dcdeafbbfa43699d210f11` 接续 PR22。只关闭持久层根装配→queryView 派生与配置传递这一完整边界；B2、B5和总重构仍未验收。

## 改动与所有权

`LedgerPersistenceDependencies` 是根装配持有的窄、不可变持久化依赖集合：原 source、明确 manager、一次解析的 UoW、schemaCheck、CoreMappers。三种有真实产品/测试调用者的公开构造保持兼容；两参仍包装原 DS，五参独立夹具仍建立原 Mapper 会话，产品六参仍使用已提供的唯一会话。queryView 直接复用同一个依赖集合与 models，去掉通过 `transactions.getTransactionManager()` 再强转具体管理器的反取关系。没有新增第二套存储、池或缓存，也没有新增事务资格校验。

每个视图仍重新构造 SessionQueryContext、SessionJdbcTemplate、SessionQueryTransaction、TransactionTemplate 及所有捕获自身 Ledger 的 Service/port/回调。共享的是原 DS/manager/UoW/schema/mappers/models，不共享 query 计量或捕获根 Ledger 的协作者。`SessionQueryTransaction`、tx/leaseTx、特殊拒绝和 beforeCommit/afterCompletion 执行正文完全冻结。

`QueryViewConfiguration` 在派生 Ledger 构造完成后捕获并应用配置，与原时点相同。包含 availability、imageStore、questions/todos/directory、workspace、sessionRead/Search/Trace/priorSearch，以及 session 的 sensitive 引用和 MCP settings 引用。没有求值 availability、check/read store、schema 或 MCP 凭据。sensitive 的公开设置仍 List.copyOf；已有视图保留当时引用，后续根配置替换只影响后续视图；同一 MCP settings 的 Environment 更新仍可见，没有变成全局共享可变配置盒或缓存。未配 sensitive 的原503、MCP默认及缺失配置拒绝保持。

精确源码/测试/反射字符串检索确认 `JdbcCoreRecords` 除自身定义和构造之外，仅有 Ledger 构造中的一个调用者；其 execution() 只返回 JdbcExecutionRecords。已删除这个过渡类并直接接真实 ExecutionRecords 实现。没有删除仍被夹具实际使用的公开 Ledger 构造。已迁 metadata/report/owner/图像/Web/MCP/Runtime/Host/provider 业务和 Mapper SQL 未改。

## 受影响验证

本地命令：

```powershell
$env:JIMU_IT_JAVA='/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java'
.\mvnw.cmd -B -ntp spotless:apply verify '-Dtest=QueryViewAssemblyTest,PersistenceAssemblyTest,SessionQueryTransactionTest,SessionDeadlineTest,SessionScopeExecutionServiceTest,SessionScopeEligibilityServiceTest,PersistencePathTrackingTest,PackageBoundariesTest' '-Dit.test=QueryViewAssemblyIT'
```

50项定向检查覆盖产品/独立构造、同DS/manager/UoW/Mapper、独立context和回调、配置逐项传播及引用更新、未配默认/失败、prior累计与无计量分支、两秒/更短lease/过期截止及嵌套restrictDeadline、whole-scope/权限资格、清理与rollback首错及afterCompletion。原绝对截止计算和计量执行正文冻结，TCP无greeting/慢认证仍由已有真实有界连接测试检查。workspace仅用无构造身份哨兵核引用，没有文件系统证明。

`OwnerDeliveryScenario` 新增一个有界 query-view 模式，复用原owner/session/SQL观察与全表恢复夹具，使用产品六参、同DS/manager/UoW/SqlSessionTemplate接线。私库旧新原JAR各做成功owner、两次累计计量、无metered、cross-owner、cross-tenant六次公开调用。记录真实SQL/绑定/结果、同连接关系、同连接两个session设置保存恢复和全私表外层rollback；没有启动worker、工具或真实provider。最终结果和准确原JAR摘要见认证索引/回执与原日志；开发验证不移记独审或QA。

最终同原JAR独立1IT通过：每侧6调用/122 SQL/100绑定/18参数关系；计量从reads/bytes/ops **2/12/4→12/6990/148→22/13968/292**连续实计，无metered分支为0/0/0。同一外层连接内自然cross-owner/tenant拒绝，两个session变量逐调用恢复，36表含worker_process外层rollback和容器/进程/临时root清理通过。该结果不等于外部并发、逐调用持久提交或物理文件系统证明。

受测原JAR **44,985,502字节**，SHA256 `ec6e63ba17ae8359a71b1064560e14d7b1d86f28449ac87f3ef9bd5102f23f52`；补夹具权限后没有重新打包。原字节patch以准确d23a89e原JAR为基，完整包和仅分片离线还原另核摘要。

首轮原verify因新测试环境未声明 `jimu.test-mode` 在ModelProfiles夹具初始化失败，50项中44通过、6初始化错误；第二轮补配置时漏回被Spotless移除的Map import而编译失败。第三轮50项及SpotBugs0通过，但私库setup遗漏原session两表的写权限，合法任务准入自然503、尚未进入计量调用，原完整verify仍exit1；补回私库夹具权限后的同原JAR定向IT结果单独保存，不能把完整verify改记成功。原日志、source/class和清理保留，后续通过不抹去。审计首轮把授权变更的私有构造误纳冻结清单而失败，明确排除两处授权构造后重新逐正文核验；这不是产品运行缺陷。

## 实际剩余及下一完整组

本批没有迁移或消除SQL，库存仍 **9静态/22动态/0静态bind，SQL消除0**。9静态均为原schema22/receipt元数据scalar及同连接会话设置基础设施；22动态分为8受信数字SET/恢复、5固定Mapper入口、9内存值访问误报。没有因去掉装配过渡类而预扣业务点。`query-view-remainder.json.gz` 链接逐点调用者、35管理表＋外部只读receipt、134公开Ledger入口及146构造/ServiceImpl/强转词法候选；词法数量不是缺陷数量。

| 阶段 | 实际交付与未关闭内容 |
| --- | --- |
| B0/B1 | 基线、MP/同DS/UoW装配及过渡门禁已交，本批明确根共享依赖和query所有权；完整稳定候选的统一追踪与验收仍待收口。 |
| B2 | 各业务Service/impl/惰性port/真实Mapper已按增量实施；35表＋receipt仍全部OPEN，需按适用原证据对账事务/锁序/并发/恢复/清理与历史缺件，不能凭JDBC计数关闭。 |
| 原B3/B4 | 已交Controller、Service、runtime/workflow切片复用准确证据；不得因历史阶段标签重做已迁模块或旧整矩阵。真实剩余按具体源码边界处理。 |
| B5 | 本批queryView同DS装配、配置传播、管理器反取强转及Core单入口过渡关闭；旧facade/构造所有者、DurableConfiguration职责拆分及全仓配置/冻结业务值治理未完成。 |

建议下一完整组为**剩余 facade 与构造所有者核对，加 DurableConfiguration 按已有 bootstrap/config 职责拆分**：先逐条追踪产品、测试及反射真实调用者，再最小接线；保持依赖引用、开关默认与校验时点、同DS/UoW、原生命周期/事务顺序，冻结已有业务Service及SQL。全仓值治理和逐表最终证据核对继续明确登记，未知冷路径不自动扩成无限阻断。

`audit-query-view.cjs` 逐正文绑定404个未变Ledger/SessionRecords/MCP方法及1102个未变生产/POM blob；准确源码、生产class和同版原JAR由随附原字节patch及索引绑定。60保持in_progress、PR22 draft，61/62待完整稳定候选，master最终推送读回前代码卡不done；无CI、部署、付费、共享服务或T48操作。
