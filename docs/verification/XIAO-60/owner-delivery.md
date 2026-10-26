# Owner 资格与 prepare 原子消费增量

基线 `d76507c39cd806a734dcbfadb53a6fe6d17bbadc`。本批只下沉 `JdbcLedger.sessionOwner` 和 `JdbcExecutionRecords.lockAdmission().prepareProjections` 的 question delivery 分支；沿 PR22 集成，不改变 master，不构成 B2 或全仓验收。

`SessionOwnerServiceImpl` 保持 taskId.matches 首错、bucket→scope lock→原全投影 owner task FOR UPDATE→空404→getFirst 原 Map→仅非 session 的 identity→access。`QuestionDeliveryServiceImpl` 保持 questionTask→一次原 codec→一次原 questionMessages→entrySet 顺序→每项原 messages/anyMatch 短路→PENDING 条件更新且 count==1→全部成功才 clear。两个窄 port 只保存引用、按需委托；既有 TaskMapper、QuestionMessagesMapper 各新增一项原 SQL，六参数顺序不变。

外层 prepare 的 latest/重放/材料/预留/插入/事件/fence，以及先前 session 和 directory 投影均未修改。没有新增事务、重试、补偿或新的后台工作。`pendingQuestionMessages`、所有既迁业务内部、Runtime/Host/provider、bootstrap/POM/V1–V22 冻结。冻结核对包括两文件 270 个其他方法正文（lockAdmission 只归一化其内被授权的 prepareProjections 子体）及 1085 个既有生产/POM blob。原方法子体不按外层方法的文本差异重复算改动。

## 实际余项

捕获器实计 **9 静态／29 动态／0 静态 bind**，本批实迁 2 旧业务 JDBC／6 bind，SQL 消除 0。`owner-delivery-remainder.json.gz` 逐项给出调用者、接口 dispatch、原 SQL/表达式、JDBC API、条件、来源摘要，以及 35 应用表＋外部 receipt 的 Mapper 与剩余链。库存是静态追踪器的口径，不是动态业务已清零。

| 范围 | 实际分类及行为 |
| --- | --- |
| 9 静态 | todoSession、directoryTransaction 各一同连接 Statement.executeQuery/getInt 保存锁等待；Ledger.check 两次 JdbcTemplate Integer schema22 校验；scope receiptTable/serverUuid/schema 三个原 Integer/String scalar；SessionQueryTransaction 的双变量读取及固定 SET。全部 0 bind，保留原 strict-one/NULL、同连接及恢复时机。receipt 元数据不是业务 receipt 行读取。 |
| 29 动态：8 基础设施 | todo/目录各两次 SET，QueryStatements 一次剩余 deadline，SessionQueryTransaction 三次设置/恢复。仅受信数值拼接，继续保留原 API/绝对截止和 finally/afterCompletion。 |
| 29 动态：3 真实业务残留 | `directoryMetadata`、`todoMetadata` 的完整 metadata 计费，及 `reportContext(Map)` 完整来源资格。不是基础设施例外，尚在 Ledger。 |
| 29 动态：4 JDBC 转发 | Ledger、SessionRecords 各 rows/one；Ledger.rows 承接上述三处。两个 one 没有静态调用者，SessionRecords.rows 仅被其 one 调用，列为后续 B5 删除候选，未以此冒称无反射到达。 |
| 29 动态：5 固定 Mapper 入口 | updatePreparedStep 固定 statement；SessionFacts 的 step/attempt/terminal 三个固定 class/wrapper；WorkerProcess 停止的固定 MP update。不是待迁 raw JDBC。 |
| 29 动态：9 值访问误报 | Search/Trace/RetainedTable 的 rows() 和 Input.query()，只是内存结果/输入成员。保留原捕获记录，不为扣数改产品 API。 |

三处业务残留的确定边界：

1. `event` 原尾部依 task 类型调用 directoryMetadata/todoMetadata，closing 为 content_deleted 或 terminal。分别按固定 9／8 表的原顺序读取完整行；task 使用 id，其余 task_id，均 tenant＋task 两 bind。计费保持每行32、每个非正文列8、NULL1／原 toString UTF8，排除字段集分别保持，open61440/closing65536 的首拒绝不变。共17种固定查询形状，不是已迁点。
2. `reportContext(Lease)` 原 leaseTx/fenced/公开报告资格及 report_time 后调用 Map 重载；材料图像来源和发布文本资格也调用该重载。composed 与普通分支形成两个 SQL 形状（单次只执行一个、两 bind），原五列、SUCCEEDED/web_fetch/可选0.3.0谓词、ordinal排序及 pin version/digest 首拒绝仍待完整下沉。不能因已交报告 stage/publish 批次而漏记。

建议 PM 的下一完整批次首先核定 todo＋directory metadata 计费业务与固定 whitelist Mapper（17种形状），保留 event 次序/计账/事务；随后完整报告来源读取资格（2种形状）。这是剩余依据，不是已派工或预扣库存。

## B0–B5 按实际源码登记

| 原阶段 | 已交事实 | 仍待关闭 |
| --- | --- | --- |
| B0/B1 | 固定基线、schema22/契约/字节索引及调用图；MP真实装配、同DS/UoW、Tasks/Worker/Execution/Model/Invocation/Artifact Service接口/impl；Controller局部切片已有接入。 | 原失败/历史证据缺口保留；最终同版全仓闭环与例外门禁尚未验收。不是重新实现装配的理由。 |
| B2 | 核心 SQL/Mapper 与多轮真实私库增量已交；本批 owner 与新 prepare delivery 完整下沉。 | 上述3动态业务边界、跨表账务/锁序/并发/清理与历史验收缺项尚不能由 XML 清单关闭。 |
| 原 B3 | todo replace/replay/owner读取，question opening/messages/pending入口/answer/maintenance等，directory六入口和助手均已有Service/Mapper；本批补prepare消费。 | todo/目录 metadata 完整业务仍在Ledger；真实FS/Evidence/进程证明按原边界区分，未重跑旧完整矩阵。 |
| 原 B4 | session v1–v4/scope/history/read/trace/search、MCP、media、native与retention多个完整增量已交，Mapper/Service实际存在。 | 报告来源动态读取残留；35表＋receipt实际入口/事务/调用证据/并发与缺件仍逐项OPEN；不能仅凭无静态表SQL关闭。 |
| B5 | 已有模块接口、局部值域/限额与结构门禁。 | 旧Ledger总facade、构造内new Service/record、具体实现与事务管理器强转、无调用者JDBC helpers、DurableConfiguration模块拆分及全仓值关闭表仍未完成。本审计列134个公开Ledger方法和144处源码构造/强转/ServiceImpl匹配，是逐项审查底稿而非144个缺陷或完整风险计数。 |

值治理仍有 Ledger 内61440/65536、业务状态/错码等原字面量；已有 `TaskExecutionLimitsV1`、模块 policy 不等于全仓 magic-values 关闭。冻结协议/错误字符串、摘要、版本、计费单位与默认值，不在本批顺手替换。最终61/62等待完整稳定候选，60保持in_progress、PR22 draft。

## 验证归属与边界

开发45项不同定向测试通过（Mapper一项复执行，共46次），覆盖顺序、原引用、session身份分支、NULL与首错、重复内容/多问题、短路、空/未匹配、所有非1计数、原异常阻断、真实加载SQL/六绑定、现有history/read/问答契约、装配/架构/追踪；SpotBugs0。最终独立执行1IT通过，使用原verify受测的同一JAR，未重新打包。原verify因夹具受理403仍为失败，不记整轮verify成功。

受测原JAR SHA256 `1b4d8f3a5123ce4f12c485aeab2cc606426afe2810bcc2dc5d8f441805f8e235`。旧新各8次公开调用／141 SQL（47 Statement、94 prepared）／214绑定／21参数关系；query起点 reads2/bytes12/operations4，末值12/6990/148，两侧一致。owner原63列的标签/类型/NULL和4次全投影已离线核；每侧独立DB时间设置的ready_at不强求跨侧值相等，其余owner值一致。两个答复原串、hash、UTF8长度、body digest及重放request逐字节核过。旧侧15 executeQuery/122 execute/4 executeUpdate，新侧12/129/0；setter总数不变，getObject/typed getter的实际差异留原记录，不称JDBC API相同。

私库driver复用既有 pending question 合法夹具，公开owner读取/跨owner拒绝/累计query计量，新prepare非空消费、精确重放/checkpoint不再消费及fence拒绝。冲突通过测试专用同连接交错：pending快照后改变第二问题状态，原MySQL条件UPDATE真实返回0；非真实外部并发证明。原先已答问题、response/intent和ACTIVE进程身份均为显式私有合成setup，不是Host/工具/provider生成。全部表外层rollback恢复与cleanup是实际证明；中间写入可见不等逐调用持久提交或每调用回滚。

原生成器漏定位return导致格式化失败、首轮私库owner模型资格缺配置导致403拒绝，以及后续若有失败，均先封存原source/class/log再修。没有将失败verify改记成功，原嵌套进程stdout/stderr单存，不造缺失后段证据。无CI/部署/付费/共享服务操作，T48暂停。
