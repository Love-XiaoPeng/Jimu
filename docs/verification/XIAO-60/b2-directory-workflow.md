## 工作目录完整账本增量

从 `8ef44048a9fe5201fd8ab6979277d0cc20a2a19a` 接续 PR22 原分支；准确交付 SHA/tree 以附件 manifest 和推送读回为准。本批仅为 B2 增量，XIAO-60 继续 in_progress，PR22 保持 draft。

`DirectoryPersistenceService/impl` 拥有六入口以及 projection、原槽、路径冻结和成功来源校验的完整规则。`DirectoryRecords` 提供具名惰性行字段、原事务以及单项 I/O/codec；`MapperDirectoryRecords` 使用原 DataSource/UoW/query 和实际表 Mapper。兼容 Ledger 助手只委托并还原同一个 Map；没有复制或刷新原 task/invocation/evidence，也没有把整段业务回调适配器。projection 继续复用原 MP Lambda 查询。attempt 两项查询接入已有 execution/TaskAttemptMapper。

保留原 leaseTx/fenced(true)、snapshot 专属资格、ready 无新 guard/忽略更新数、freeze 事务前工具拒绝、UUID catch 范围、owner visible→access、Response v2 完整扫描/先槽后早 step、冻结 14-bind INSERT 和重读比较、成功历史重放的完整来源验证。提交依次保留资格/证据/预算/计数/reserve/event/两项恰1更新/changed-only revision CAS/完成事件/末尾 fence；READ/nochange 不增 revision 或 changed 事件。SQL 键、列序、NULL、计数与首错不变。208 个其他 Ledger 方法正文经逐方法原字节比较（仅行尾归一）不变，包含 directoryTransaction、leaseTx、todo、fenced、storage/reserve/event/commitFence 内部。

实迁 **21 静态旧 JDBC 点 / 81 bind**，SQL 消除 0。库存实计 **34 静态 / 29 动态 / 92 静态 bind**。静态余量：Ledger 22、ExecutionScopes 6、SessionScopes 3、SessionQueryTransaction 2、ExecutionRecords 1；完整逐 SQL 剩余在附件。下一完整业务组是 `replaceOrResult`→`getTodos` 的 todo 替换/成功重放/owner 读取（8 点/32 bind）；本批未迁。底层 directoryTransaction 和其余剩余不预扣，不宣称关表或总重构完成。

## 本地验证及原失败

最终命令（显式 Linux Java21 环境变量）：

```text
mvnw.cmd -B -ntp spotless:apply
node docs/verification/XIAO-60/capture-paths.cjs
mvnw.cmd -B -ntp verify -Dtest=DirectoryPersistenceServiceTest,DirectoryWorkflowMapperTest,PersistencePathTrackingTest,PackageBoundariesTest,RefactoringNegativeGatesTest,B2MapperContractsTest -Dit.test=B2DirectoryWorkflowIT
```

最终 verify exit0 / BUILD SUCCESS：24 项定向测试、1 项私库 IT，失败/错误/跳过均0；SpotBugs bugs/errors均0。定向测试覆盖构造/字段惰性、原 Map 引用、事务/异常实例/首错、完整 Response 扫描、槽赋值时机、历史成功冻结、严格来源 size1、无change结算与恰1失败阻断，以及真实加载的21 SQL/81参数位置、冻结投影和架构门禁。未重跑旧目录/工具/worker整矩阵。

依序保留原源码、class、日志与恢复记录：首次 checkstyle 的星号 import 失败；第二次事件参数 Object→String 编译失败；修正后 test-compile0。第一组16定向测试通过；首次 verify 的7项架构/通用检查通过、SpotBugs0，但 IT 的新 Python 助手缺闭合括号，在 DB 执行前失败。修正后 IT 暴露真实 Spring Bean 同名冲突：误新建 task/TaskAttemptMapper，与既有 execution/TaskAttemptMapper 冲突；已并回既有 Mapper并清除失败构建的两项陈旧 target 文件，未改 Bean/bootstrap/POM。该轮私库清理全部成功。最终重建完成上述24+1验证。独立源码核验助手首次未解压 compact symbol id 而得到0方法，原空回执留档；修正并加完整性断言后208方法核过，仅离线核证。

## 同版旧新私有 MySQL

一个整组 `DirectoryWorkflowScenario` 在旧新原 JAR 各运行一次，均 exit0。真实 RUNNING task / ACTIVE attempt / live lease 与完整授权成立；explicit 合法 response、intent、invocation 是测试 setup，Evidence 为合成数据，不代表 Host/工具产生或物理文件系统证明。没有工具、provider 请求，paid=false。

旧新各 **26 次公开调用 / 736 SQL（104 Statement + 632 prepared）/ 1473 绑定位置 / 111 项本侧参数关系**，21 SQL 点均到达。覆盖 UNINITIALIZED→READY、重复ready拒绝、READ/nochange、SET/change、冻结与提交成功重放、revision前进后的历史READ重放、同step前槽/早step拒绝、FILES路径与重复读取、owner初始/changed/ABSENT/隐藏视图，以及撤权/fence拒绝。两侧 SQL 仅将原星号展开为冻结全列后序列一致；独立ID、时间、namespace和 Map.of 顺序仅按本侧关系核，不强求跨实例同值。

原始 JDBC 差异真实保留：executeQuery 239→148、execute 482→588、executeUpdate 15→0；setString1371/setNull12不变，setObject37→0、setLong53→88、setBoolean0→2。全部 getter调用计数：getObject4116→2031、getString2506→3971、getLong842→1180、getInt622→880、getBoolean197→221；这是实际 getter/重载调用计数，不能把这些差异直接解释为投影字段值或类型变化。逐调用完整绑定、getter标签/位置/重载/返回值类型、异常cause/suppressed/next SQLException/SQLState/vendor见原记录。

每调用同连接证据已核；两版外层事务回滚后私有36表（含 worker_process）before/after完整相同，容器/临时根/子进程/fixture四项清理true、failures空。该验证仅证明外层私有夹具恢复，不冒称逐调用持久提交、独立afterCompletion后记、真实文件系统或整体验收。UNKNOWN不重放。最终 JAR 44,871,689字节，SHA256 `4e00a43b1feed4b52bd1f8db6d96d7402239792ab8733819412725d61d25c90b`；附件提供按已交8ef原JAR逐字节还原的差分、完整索引、class/source绑定及只读复核脚本。

Runtime/Host、目录文件契约、SecureWorkspaceStore、Controller、Bean/bootstrap/POM以及已交MCP/检索/图像/Web/报告/81/82冻结。未运行或等待CI，未合master、部署或使用付费/共享服务；T48继续暂停。
