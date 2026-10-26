## 范围与源码基线

从 `de5f32d4757679c0f8c21b3cd26938b569539b6f` / tree `5fce0115409b78eca3d3edad104359a0478dfec6` 迁移 MCP 完整持久化用例。`McpPersistenceService` / `service/impl/McpPersistenceServiceImpl` 拥有七个公开入口、`validateMcpList`、server/bind/binding 的全部原业务规则。`McpRecords` 是本聚合专属惰性行/事务/单项 codec/数据库端口，`MapperMcpRecords` 适配原同 DataSource/UoW；`JdbcMcpRecords` 仅保留配置、copyConfiguration、enabled 及原调用者兼容接线。

Service 构造只存引用。原 helper 的默认 settings 和 McpContracts 构造时点保留；配置复制仍共享同一个 settings 引用。环境和凭据只在原到达位置解析，凭据不进入快照或交付证据。原 MCP 传输、Wire、Settings、Contracts、Host、retention、图像/Web/报告/81/82 内部冻结。

## 原行为与实际持久化

- binding 保留 profile/binding 的原串、UTF8 长度、hash、canonical JSON、host/allowedActors/epoch/digest、credential 顺序及原 catch 范围。task 的 tenant/owner/digest 保留原 Object 读取，列表校验的同名字段仍按原 String 读取；没有提前转换/预解码。
- server 先经原公开 `mcpBinding` 事务返回，再取 Map、判 NULL。responded 使用原 tx，其他入口沿原 leaseTx。dispatch 普通 admission=true、cleanup=false；调用锁与资格后依次 COUNT、64/20、cleanup 单笔、数据库 MAX+1、INSERT。应答只接受 UPDATE 恰1，其他原 ignored count 仍忽略。
- 列表预留仍 bucket→fenced→调用锁/资格→COUNT>=4 短路→1MiB 预算→reserve→held。succeeded 保留 SUCCEEDED 精确原 output/hash 重放早退；snapshot NULL 绕过存储分支。非 NULL 校验/大小→reservation 锁/状态→result→逐 cursor INSERT SELECT→差额/SETTLED→原 `commitTool(..., true)`，没有替换为 toolSucceeded。
- 分页保留完整 JOIN 投影、数据库到期谓词、query/binding/body 摘要、完整快照校验、cursorPositions/page_index 和 pages 取值。typed Node 仅代理原 Jackson 单项 path/转换/遍历/原串；元数据、projection、两类资源解码、cursor 唯一性/末页 NULL 以及 IOException/IllegalArgumentException 的翻译范围全部在 Service 中。
- 原 `one` 缺行 NOT_FOUND、多行首行；scalar 保留 JDBC queryForObject 的空/多行/NULL 结果约定，Service 在原位置拆箱。所有实际调用参数求值完毕后，adapter 才取 query/Mapper/会话。

`evidence/b2-mcp-lifecycle-removed-sql.json` 逐条绑定原18点/65参数与实际 Mapper ID。Invocation、Reservation、TenantBudget Mapper 只增加本批具名语句；McpServer/Io/Result/Cursor 承接本表操作。SELECT * 按独立冻结 schema 列序展开，JOIN 为原 cursor 全列加 result 四列；锁、谓词、case、数据库时间、NULL、计数均保留。无 SQL 消除、双写、额外锁、缓存、重试或新 schema。

capture-paths 实计 **77 静态 JDBC / 29 动态 / 241 静态 bind**，本批实迁18/65、SQL消除0。剩余按文件为 Ledger65、ExecutionScopes6、SessionScopes3、SessionQueryTransaction2、ExecutionRecords1；35表及外部receipt仍 OPEN。

## 验证和证据边界

定向本地命令：

```text
.\mvnw.cmd -B -ntp spotless:apply verify -Dtest=McpPersistenceServiceTest,McpListValidationTest,McpLifecycleMapperTest,McpContractTest,PackageBoundariesTest,PersistencePathTrackingTest,B2MapperContractsTest,RefactoringNegativeGatesTest -Dit.test=B2McpLifecycleIT
```

36项单测/契约/架构/追踪检查全部通过，格式、Checkstyle、依赖门禁通过，SpotBugs bugs/errors=0。首轮 verify 因未传 Linux Java21 配置而在 IT 前置断言失败，因此不称整条 verify exit0。后续显式设置 `JIMU_IT_JAVA`，只运行必要 test-compile 和 B2McpLifecycleIT，不重跑已过无变化矩阵。

最终第六轮 B2McpLifecycleIT：1项通过、无失败/错误/跳过，Maven exit0/BUILD SUCCESS；修正模板授权夹具后重验22项受影响检查，未变14项沿用原36项记录。旧新各32次公开调用，各496条SQL（49普通Statement、447 prepared）、1,064个绑定位置；两页资源及模板成功/游标、normal/cleanup及应答/重复拒绝、重放/冲突、snapshot NULL、权限与fence均按各侧实际事实核过。离线脚本审计每侧76项原SQL参数关系及资源/模板授权列表通过，全部18站点/65个静态绑定位置另由实际加载Mapper核对。

同版受测原JAR：44,807,089字节，SHA256 `2a9aa00e153563e3da47a8cc0af49b07c6831ab8705fa15d673be4b0f158d7d5`。旧版为准确de5f32d原JAR，44,771,568字节、SHA256 `173f4ccc9009a166ec957af036a9748235986bd39bcb8e8faa2c2f49f2384dba`。第四/五轮通过的账本和schema原记录保留，但第二模板未进入profile授权列表，不称完整合法模板验收。第五轮补采集器原来遗漏的getObject(column, Class)重载和值；第六轮仅在两处夹具补齐原合法授权，生产源码/JAR未改。不将第四轮单实参getter统计冒称完整重载统计。

真实 MySQL 场景通过原公开受理获得合法 MCP task，registerWorker/claim 仅作 setup，真实核 RUNNING/ACTIVE/live、current_attempt/fence/worker 完整绑定。显式合法 step/call/pin/TOOL reservation 和两页资源/模板结果不由 Host/provider 生成。清理与应答的原 admission=false 资格另证。全表原始回滚前/后快照含 worker_process；这是外层夹具 rollback，不能称逐调用持久提交、独立后记或整个重构验收。

每次调用保存 SQL、Statement/prepared、setter/execute/getter/NULL 类型、connection、原值和前后业务事实。旧 JDBC 的 SELECT * 与新显式投影仅在比较器中按冻结列规范化；两侧原 SQL 保留。Integer/long 的 setObject 与 Mapper setInt/setLong、旧 executeQuery 与新 execute/getResultSet、索引与标签 getter 等真实差异按原日志记录，不冒称 JDBC API 相同。

本轮直接公开 Ledger 分页调用覆盖 query=NULL；queryView 的预算/deadline、历史 FAILED 读取及独立 afterCompletion 等保持 OPEN 或沿用已有冻结证据，不扩记为本轮新增验收。

保留原失败：最初生成器局部变量错误导致格式失败，修复 Mapper 路径并整合既有接口；首轮单测 lambda 捕获编译失败；首轮 IT 缺 Java21 环境；第二轮基础 Fixture 无 requests 属性；第三轮私有夹具漏 MCP 专用写授权，旧版 dispatch 正常分支收到 INSERT denied。后者原 cause/调用/全表恢复及 cleanup 完整保留，原日志未显式列 SQLState/vendor，不能追认为已采；未重放该失败来补故障证据。后续仅修私有夹具的既有最小授权，不授 mcp_server 写权。各次源码、class（编译失败时标为部分/旧 target，非受测品）、日志和清理分别封存。

后续候选整组为原生检索账本生命周期（22旧点），由 PM 核当前源码后继续派发；本批未实施。60保持 in_progress、PR22 draft；最终独审/QA/总验收及 master 推送读回未完成。不运行或等待 CI，不部署/付费/访问真实 MCP/provider/启动 worker 循环，T48暂停。
