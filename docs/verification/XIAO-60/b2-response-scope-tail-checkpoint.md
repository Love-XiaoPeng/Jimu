本片交产品经理：scope 三调用的阶段观测及受控异常翻译定位，产品验证继续 OPEN。生产源码未改，未变更任何 deadline、socket/network 设置、SQL、账目、锁或冻结序列化；SQL 删除 0，库存仍为静态 182／动态扫描项 26／587 binds。原分支 `agent/agent/7aeccb14d7a9`／PR22 保持草稿，60 保持 in_progress。

准确受测产品继续复用旧 JAR `c1bf9870dff1b7f5a30e9f7d49c2e953da6d650f92e270fd9627ccbe3176522d`（44,391,230 字节）和新 JAR `dc1d44dbf9715b0b70fd3bf7293f08d4c83eb816ac2d299d2a60b5023f9f18f0`（44,403,849 字节）。本轮 Maven 打包产物不作为受测产品；没有把 ZIP 时间戳不同的文件混称同一制品。生产与 413 份基线资源／冻结输入、schema22/V1–V22 保持。

仅执行两个新私有 scope 组。每组从一次冻结事实预建旧／新数据库副本，每侧进入一次；原 UNKNOWN 实例未再进入，没有恢复、原槽重试或 provider 重放。两组均保留原 search→self read→cross read 输入及单个累计 QueryResources／两秒绝对截止。worker 的 Payloads.json 原转换串被原样保存并进入 driver，fixture 样本另存，不把 JSON 值相同写成原字节相同。

新增 test-only agent 分别记录 account、MyBatis／Spring 翻译、内层 UoW、driver 捕获、外层 rollback、连接 cleanup 的阶段。原始及观测 class、执行 source/class/agent、编译命令／输出、JAR 身份在创建数据库前归档；每轮结果／日志／清理归档后才进入下一轮。阶段只在内存记录，class 与异常图在 JVM 退出时写盘，没有截止内证明 SELECT。原 cause／suppressed／next SQLException／SQLState／vendor code 分别保留。观察器本身有开销，阶段值不作无观察器性能保证。

第一轮是无合成故障的真实调用。旧侧在原 `metadataStepSessionScope` SELECT 的结果计量处触发 TaskFailure `SESSION_QUERY_TIMEOUT`，MyBatis 将其包装成 MyBatisSystemException；生产结束 2,007,729µs，不能称正常业务拒绝。翻译器入口 2,007,506µs、返回 2,007,598µs，内层 UoW 抛出 2,007,703µs、driver 捕获 2,007,709µs。该样本没有约 709ms 的 SQL 异常翻译间隙。外层 rollback 随后失败，真实 SQLState **08007**、vendor 0、Transaction resolution unknown；主错和清理异常均保留，无事实恢复证明。新侧本次 1,982,703µs 正常返回并完成一笔捕获事实回滚、5 条 RESULT；不推定稳定、冷路径或全任务通过。此轮 supervisor 退出 1，失败材料保留。

第二轮是明确的合成故障对照，目的仅是隔离首次 SQL 异常翻译。原路径到达首个 metadata account UPDATE 调用点后，夹具在执行该 UPDATE 前抛出一次带 08S01／vendor 0 及合成 SocketTimeoutException cause 的 SQLException。未执行该条 UPDATE，未人为关闭连接、制造真实 socket 故障或延长任何截止。它与历史 Connector/J CommunicationsException 的具体类和连接失效状态不同；本次翻译为 DataAccessResourceFailureException，不冒称历史 RecoverableDataAccessException。它是 history.changed 递归追加内首个 account，不能冒称精确复现历史最后一个 account 位置。

| 第二轮观测阶段（µs） | 旧 JAR | 新 JAR |
|---|---:|---:|
| account SQL 进入标记时剩余截止 | 267,470 | 328,838 |
| 合成 SQL 异常标记 | 1,733,679 | 1,672,321 |
| MyBatis translate 进入→返回 | 1,740,585→2,429,074 | 1,678,486→2,171,979 |
| translate 总耗时 | 688,489 | 493,493 |
| 其中 initExceptionTranslator | 685,039 | 491,182 |
| 其中 resolveErrorCodes | 6,455 | 4,497 |
| 其中 extractDatabaseMetaData | 536 | 342 |
| translate 返回→内层 UoW 抛出 | 133 | 96 |
| 内层 UoW 抛出→driver 捕获 | 7 | 6 |
| SQL 异常标记→driver 捕获 | 695,535 | 499,760 |
| 外层 rollback 耗时 | 15,656 | 88,900 |

两侧各一笔捕获 tenant 16 张事实表及 tenant/evaluation 预算全列回滚恢复；续期 UPDATE 一并回滚。每侧 1 条名为 `RESULT success` 的输出只是原 driver trial 标签，本轮含义为“合成故障保留且外层恢复”，两侧生产结果均为 EXCEPTION，不是响应成功。supervisor 退出 0 也只代表故障对照断言成立。内层 REQUIRED 参与外层试验事务，不能将其退出写成独立事务提交或独立后记通过。

本对照已把约半秒至七成秒的主要时间定位到首次 `initExceptionTranslator`，而非 rollback 或内层 UoW 退出。该区间没有新 datasource.getConnection 标记，metadata 读取仅约 0.5／0.3ms，不能用历史第二个 connection.close 推定新连接获取占满该间隙。init 开始到 resolveErrorCodes 进入仍有 678,569／486,671µs；准确原 class 的 javap 留档显示 supplier 构造 SQLErrorCodeSQLExceptionTranslator 及错误码工厂初始化路径，但当前标记没有进一步分解其类初始化、XML 解析、类装载／I/O 或调度时间，不将这些可能性写成已证根因。所有被观测原 class 均逐一匹配各自准确 JAR；两侧框架依赖原件相同。

历史 **708,713µs** 无这些阶段标记，不能追溯逐段重建。本轮对照只证明同类传播间隙可以主要发生在原框架惰性初始化；第一轮自然失败类型不同，真实 08S01 故障及其连接失效没有在本轮精确复现。尚无证据认定 response 迁移回归，也没有据此修改生产翻译／拒绝类型、预热查询或截止。core 最后 beforeCommit 缺口继续单列 OPEN。

定向 `mvnw.cmd -B -ntp verify -Dtest=ResponseDiagnosticObservationTest -Dit.test=ResponseScopeTailEvidenceIT` 退出 0：5 项诊断设施单测＋1 项已执行证据合同 IT，SpotBugs bugs/errors 均 0。IT 读取已执行原件，不启动数据库，不把第一轮失败或第二轮合成对照改写为产品通过。两个私有容器的完整清理均成功。所有本轮及历史失败保留，仅属开发自测，非独审／QA／业务验收。

下一具体诊断切片：以本阶段观测核真实 account 08S01／失效连接的首次 translator 初始化，并进一步拆分 init→resolve 前的冷初始化；只在新私有冻结副本做有问题指向的必要试验，仍不改变原截止或重放 UNKNOWN。metadataView/recent、JdbcLedger.sessionHistory 继续排队。B2、35 表＋只读 receipt、scope 完整成功 read、持久 FAILED-read、独立后记、冷锁／完整锁排列以及历史 v1／503／后继派发超时等均 OPEN，无新增外部／成员授权阻塞。不激活 B3–B5／初级／61/62／T48，不运行或等待 CI、不部署或访问真实 Key／计费／共享服务，不操作 daemon／代理／FRP／重置。
