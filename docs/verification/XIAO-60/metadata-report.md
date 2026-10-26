# 目录/todo 元数据计费与报告来源增量

准确基线 `f83f909cb95a07b65d56c51a0aca0d07da97645c`。本批迁移两个完整 metadata 规则和 `reportContext` 两重载；沿 PR22 交付。既有 session metadata、报告发布/材料来源内部、event、事务、Host/provider、bootstrap/POM 和 schema22/V1–V22 保持冻结。

`TaskMetadataAccountingServiceImpl` 拥有两套独立的排除集、目录9表/todo8表的固定遍历顺序及全部计费规则。每行32、每个非正文列8，NULL1，否则原 value.toString→TodoContracts.bytes；仍先完成全部扫描，才按 open61440/closing65536 的 `>` 拒绝。没有排序、拷贝原行、预读、提前拒绝、聚合近似或新增事务。事件尾部 directory→todo、closing 判定及幂等早返未动。

10个表的现有 Mapper 各承接一项封闭 `metadataRows`，共用7表并未消除任何执行。语句仍是原完整 `SELECT *`，以精确 statement ID/SQL 的门禁例外保留原列顺序、NULL 和新增列可见性，结果使用 `LinkedCaseInsensitiveMap`。端口构造只存引用，每次到达才读取 task tenant/id，端口不算整批资格或阈值。

`ReportContextServiceImpl` 拥有 public leaseTx→fenced(true)→原 publicReport/report_time 短路、内部两次 tools 访问和逐行 pin 资格。复用原 publicReport/codec 资格调用，不改既迁消费者。InvocationMapper 的普通/组合两个固定语句保留五列、两参数、SUCCEEDED/web_fetch、普通0.3.0谓词及 ordinal 排序。组合分支原 NULL/version/row version/digest 短路、空来源不提前验 pin、Source 原串及 stream.toList、末尾 report_time 均保留。两个原内部消费者仍调用 Ledger Map 重载。

四个旧 JDBC rows/one 转发 helper 已无生产、测试及反射字符串调用者，本批删除；没有改动同连接 session 变量、schema/receipt 元数据 scalar 或恢复 API。

## 实际余项

静态追踪实计 **9静态 / 22动态 / 0静态bind**。本批完成3个原动态业务点：metadata 两个循环形成原17次扫描序列，报告2种有限形状；落地12个固定 Mapper 语句。SQL消除0，未把共用语句数量记成业务消除。移去3业务点与4无调用转发点后，动态库存从29到22；类型化内核 port 访问不在原 adapter SQL 扫描器的检测口径内，仍由准确调用图登记。

| 实际分类 | 剩余及关闭边界 |
| --- | --- |
| 9静态基础设施 | directoryTransaction/todoSession 两同连接 Statement.executeQuery/getInt 保存锁等待；Ledger.check 两 Integer schema22 校验；执行scope receiptTable/serverUuid/schema 三 scalar；SessionQueryTransaction 双变量读取及固定 SET。全部0bind，原返回、NULL/基数、finally/afterCompletion 与恢复保持。 |
| 22动态 | 8个受信数字超时SET/恢复；5个固定MP/Mapper入口；9个内存 rows/query 值访问误报。该清单中无剩余 raw 动态业务 SQL，不等于B2全部验收或全仓业务都已出adapter。 |
| 35表＋外部receipt | `metadata-report-remainder.json.gz` 逐表链接真实Mapper、基础设施例外及准确调用链。全部仍OPEN，外部receipt仍只读且非本仓库DDL；已有同版适用证据可复用，表名出现不等事务、并发、清理或业务验收关闭。 |
| B0/B1 | 基线、MP装配、同DS/UoW、Service和Controller切片已交；最终统一追踪、精确例外及完整稳定候选尚待收口。 |
| B2/原B3/B4 | todo/question/directory/session/MCP/media/native及报告/材料业务多轮增量已有真实实现。本批补齐两个metadata规则和报告来源；不因旧阶段标签重做既迁实现/旧矩阵。跨表恢复/锁序/并发/历史缺件仍按既有准确证据逐项收口。 |
| B5 | Ledger仍有134个公开入口，构造/ServiceImpl/强转词法匹配147处，是审查底稿而非147缺陷。旧 facade、构造内装配、具体query-view/DSTM强转、DurableConfiguration拆分及全仓配置/冻结值治理尚未完成。删除4死helper不关闭B5。 |

建议下一完整批次先核 **持久层 queryView 及同DS装配边界**：`JdbcLedger` 构造链、`queryView(long,QueryResources)`/`queryView(Lease,boolean)`、`sessions.copyConfiguration`/`mcp.copyConfiguration` 与 `JdbcCoreRecords.execution()` 的实际所有者和调用者；类型化共享同DS事务依赖，消除 `transactions.getTransactionManager()` 的具体管理器强转，明确旧测试构造与产品装配边界。关闭条件是原 query资源累计、绝对两秒/lease截止、所有flag/store/availability配置复制、特殊拒绝及afterCompletion不变，产品和独立夹具同版必要验证；不能只改类名、删除facade或另造万能Context。此处是后续完整范围建议，未在本批实施或预扣。bootstrap配置拆分、全仓值关闭表及最终61/62仍另需核定，未知冷路径不自动无限阻断。

## 验证与原品

最终命令、测试数量、原失败、受测JAR摘要及有界私库结果以随附认证索引和原日志为准。私库仅本批目录/todo事件与报告来源整组对照，不访问真实provider或工具；合成来源、ACTIVE租约和外层全表rollback不冒称物理文件系统、真实进程回执、付费准入或逐调用持久提交。开发验证不移记独审/QA。

本地共56项不同定向检查通过（原verify中55项及新增NULL/Unicode精确容量边界；metadata类最终10项），SpotBugs0。受测原JAR为44,981,550字节，SHA256 `60ae50e17ab9603c0383a6c21bfbf0482605e78ba85511a8e4f51f5f0cbafc24`；后续私库复验使用同一原JAR，没有重打包。目录/todo open事件和quiescent终态closing事件、幂等早返，及报告普通/组合空与非空来源、普通分支不额外验pin、组合version/digest首拒绝、缺时间/权限/fence均有实际调用。每侧21调用/342SQL（31 Statement、311 prepared）/661绑定/84项参数关系。新侧另有20份、10种固定表扫描的实际Mapper结果对原JDBC结果比较，逐行核原Map值、NULL、值类型和列序；这些额外只读投影核验位于测量调用外，未混入每侧公开调用SQL数。

完整verify第一轮因测试worker标识拼接超原id长度而失败，保留该轮55项本地通过和SpotBugs0，不能记整轮verify成功。第二轮夹具在quiescent已自动终结取消后再调stop，收到原FENCE_REJECTED；修为直接观测原quiescent关闭入口。第三轮旧新Java全部调用、投影核对及恢复均完成，但Python把调用数误算23、误期directoryReady写事件；修正后原记录离线复算通过。原source/class/log、SQLState/主次错、恢复和cleanup完整保留，后续通过不抹去前三轮失败。合成quiescent只报告本测试账本写者静止，不冒称实际进程死亡或物理文件回执。原session计费和query deadline/计量设施冻结，复用既有适用证据，不重跑旧session/worker整矩阵。

`audit-metadata-report.cjs` 绑定312个未变 Ledger/SessionRecords 方法正文及1077个未变生产/POM blob，另绑定本批源/Mapper/class/JAR。原审计脚本把新port调用误预计入旧adapter扫描器的24动态口径，实捕22后修正；原exit1保留，不是产品故障。完整原JAR通过准确f83f909原件＋原字节patch恢复，并独立校验完整包和仅分片目录。60保持in_progress、PR22 draft；master交付前代码卡不done，T48暂停。
