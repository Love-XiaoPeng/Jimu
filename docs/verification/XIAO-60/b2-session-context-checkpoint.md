# B2 sessionContext 部分检查点

接续源码 `eac8c702997506e931a9aaaed185d0e3d4473371`、tree `6a1e341a9c71fd8f24b43ba15ede84cb806e6f3a`，旧受测 JAR SHA256 `b0e50866ce48b7af343f02bce0dce20f72b2bb5b74ec2ff68c62d47ab595044f`。新源码/tree、最终制品摘要和远端回读由本批交付 manifest 固定。仅为开发自测检查点，不替代独审、QA及业务验收。

## 完成

`SessionEventLedger.sessionContext → JdbcLedger.queryView → SessionContextServiceImpl → SessionContexts → MapperSessionContexts`。Service拥有当前资格锁定后取上界、最近窗口完整性、scope上下文组合、验证后计账的顺序。适配器保留框架事务和协议编码；checkpoint校验也走同一 `snapshot`，旧上下文组装正文已删除。上下文JSON的键、类型、版本、scope目标次序和原codec验证不变。

最近事件使用现有 `ScopeQueryNativeReadMapper` 的真实 MP Lambda 投影，仅取 `seq,type`，保留完整tenant/task键、`BETWEEN max(0,upper-7) AND upper`、升序和 `LIMIT 8`。history锁复用已有参数化 `SessionHistoryRetentionMapper.history`；metadata读取复用其16个固定投影，保持正文排除、NULL、时间格式、预留清理余量及固定点规则，删除动态列发现／行扫描。`SessionContextMapper`承接原metadata值读取与精确账目更新。共享调用者也走这套实现，不留双写或旧SQL旁路。

本批删除5处旧静态JDBC调用（history锁、最近事件、列发现、metadata值、metadata更新）及1处动态metadata行扫描。静态库存247、动态21、字面量绑定868；具体旧SQL及链路见 `evidence/b2-session-context-paths.json`。仍在用的共享资格、受理及其他SQL未计删除；35表＋只读receipt全部OPEN。

保留原evaluation→budget→scope/task及后续资格／权限锁序、当前whole-scope／fence、拒绝顺序、同DataSource／SqlSession／UoW、scope失效后记、账目原子性及UNKNOWN恢复不重放。`SessionQueryTransaction`的工作末尾及beforeCommit截止检查原样保留；本批不修复core UoW最后beforeCommit检查缺口，不混同两种事务边界。

## 验证

第四轮完整verify：79项单测／契约通过、SpotBugs 0；整条命令仍失败。原生v1已通过，scope v4旧新上下文／metadata／故障回滚也通过，但其冷JVM锁测试实测2390ms，超过夹具2300ms上限。不能写成完整verify成功。第五轮固定同版JAR的scope单项failsafe补跑1项集成通过、退出0；原生v1沿用第四轮通过记录，不增加JUnit数量。六份清理回执均通过。最终JAR SHA256 `d8a59303978d8a52405b51e45c312e248cbddc6048f315b42c7a68e9c4ca852c`，44,040,114 bytes；1175个生产编译／资源文件与JAR逐字节匹配，无测试驱动。产品2秒截止与原2300ms断言不放宽，补跑只在持锁测量前增加同lease无竞争上下文读取。定向命令：

```text
JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java
.\mvnw.cmd -o -B -ntp verify -Dtest=SessionContextServiceTest,SessionDeadlineTest,PersistenceAssemblyTest,PackageBoundariesTest,ScopeReadNormalizationTest,SessionScopeQueryServiceTest,SessionScopeRevocationServiceTest,SessionHistoryRetentionServiceTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest -Dit.test=B2SessionContextIT -Djimu.session.context.evidence=b2-session-context-r4
.\mvnw.cmd -o -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=B2SessionContextIT -Djimu.session.context.only=scoped -Djimu.session.context.evidence=b2-session-context-r5
```

实计仅按受测路径登记：原生v1旧49 reads／33100 bytes／1222 operations，新33／22522／552，metadata同为10674 bytes；scope v4旧80／60860／1494，新64／50282／824，metadata同为12962 bytes。补跑scope在同lease无竞争读取后，锁竞争实测2047ms通过原2300ms夹具；冷JVM2390ms失败仍保留。

新Service拒绝／计账顺序、TCP连接绝对截止、真实Mapper装配、受影响共享scope／清理契约、路径清单及冻结／包边界按必要范围验证。私有MySQL 8.0.46使用同任务旧新制品，核原生v1和scope v4的上下文、同JVM原字节、独立information_schema metadata复算、旧fence／错误actor拒绝、metadata SQL故障回滚、最后更新后beforeCommit截止整笔回滚、同lease恢复和budget锁超时后恢复；provider为本地paid=false夹具，不使用真实Key或付费。

首轮Checkstyle星号导入失败、前两轮错误暂停点、第三轮夹具负metadata值违反数据库CHECK，以及静态证据工具误把动态`SELECT `片段计为静态的失败均保留。前四轮完整verify失败，不改写为成功；中间受测JAR逐一固定摘要，并用最终JAR＋无损差分保留准确原字节，不能称作同一JAR。首次13项定向test成功亦与最终verify分开记录。

## 剩余与接续

下一实际切片建议由产品经理接续原中级A：`readOrResult` 原生session_event_read查询／回放／提交／receipt完整Service、类型化端口、真实MP／Mapper和对应旧SQL删除，补本批实际受影响路径验证；之后其余runtime scope／receipt、受理写入、模块与维护。此处仅交接，不在本轮启动下一切片。

B2未完成，60保持in_progress，PR #22草稿；B3–B5、初级、61／62与T48／新功能不提前激活。core UoW最后截止缺口、原生v2／v3完整worker、实际v3 trace清理、精确CLI装配、完整scope／维护／文件锁排列、非空付费账本异常及真实付费视觉均OPEN。投影／预检引起的实计变化只按受测路径登记，不声称全部SQL或网络往返不变。外层回滚与afterCompletion独立提交分别核算，不声称整库回滚。

冷JVM锁竞争2390ms的失败继续保留；预热后的定向通过不能关闭全部冷启动／scope锁排列。当前无外部执行或成员授权阻塞。保持原分支唯一集成，不运行／恢复／重试／等待CI，不部署、不接真实Key／外部计费／共享数据或服务，不操作daemon／代理／FRP或重置数据；维护切换由主控负责。
