# B2 Invocation 部分检查点

从 `7d721570d9109677995c109b3d53ae60e3b51193`、tree `56d098f5ca758afd6ef2d1af0ded1cd9e0b89712` 接续原集成分支和草稿 PR22。准确新源码/tree、同版 JAR 与运行结果见本批 manifest。B2 未整体完成；35 表＋只读 receipt 继续全部 OPEN，开发自测不替代独审、QA、业务验收或合并。

## 实际入口、规则与持久调用

| 实际入口 | Service／类型化端口 | Mapper／事务及锁 |
| --- | --- | --- |
| DurableAgent → JdbcLedger.admit | InvocationServiceImpl.admit → InvocationWorkflowRecords.Admission | MapperInvocationWorkflows；原 leaseTx，evaluation→tenant budget→scope/task/fence，之后重入 bucket，读取原 slot；installation 普通复合键查询使用 MP selectMaps＋固定 LambdaQueryWrapper |
| DurableAgent／MCP模块 → toolSucceeded／commitToolSuccess | InvocationServiceImpl.succeeded／commit → Success | 原 required＋directory UoW；fenced(false)，目录分支再 fenced(true)，保留目录限额 hook；读取 tenant/task/id producer，image/event/step 事实后 CAS、reservation结算、event、目录 fence、材料复核 |
| DurableAgent → toolFailed | InvocationServiceImpl.failed → InvocationRecords.Invocation | 原事务内 fence 校验；session 特殊拒绝；InvocationMapper.failRunning 的 RUNNING CAS，ReservationMapper.settleToolUnit，唯一 failure event |
| DurableAgent → uncertain | InvocationServiceImpl.uncertain → 同一 InvocationRecords | 特殊工具保护按原顺序在事务前读取；事务内 fence、RUNNING→OUTCOME_UNKNOWN、RESERVED→HELD_UNKNOWN、NEEDS_RECONCILIATION。保留预留，不结算、不重放 |
| 所有原 fenced 调用者 | 原调用者 UoW／权限／拒绝边界 | TaskMapper.commitLiveFence 保留 tenant/id/revision/current_attempt_id/fence/live lease 的原完整 CAS 谓词，失败仍 FENCE_REJECTED；没有增删事务或改变锁位置 |
| 所有原 checkMaterials 调用者 | 既有共享材料复核 hook | InvocationMapper.selectMaterialSources／selectMaterialFileHistory 保留原 tenant/task、状态／tool 和 step join／排序；更广材料业务规则仍按后续模块收口 |

Service 实际持有原槽内容／provider绑定、pin比对、成功快照重放、PENDING拒绝、工具双额度、一次状态转换／结算、普通提交的五种特殊工具拒绝优先级、材料准入的来源／目标／先读后写／present限制、image producer和web结果证明比对。端口只返回 Slot、Pin、MaterialUse、ImageProof、WebProof 等事实及具名写入，不向内核暴露数据库 Map、SQL或框架。

web receipt存在时先读step，再按原短路条件比较output、hash、最后惰性计算WebFetchReceipts证明；image先读已COMMITTED producer再解析输出。材料存在时先完成原 material/initialFile读取与校验，再解析输入；仅write分支查询successful read，不提前加载。admit先比较绑定，再freezeDirectory/material/pin，然后检查缓存／状态／额度。以上次序保持原拒绝优先级和查询实计。

成功分支在 CAS 后保留结算／成功event／fence；材料后验 EVALUATION_MATERIAL_MISMATCH 只令evaluation STOPPED，先提交成功事实和账目，再在事务外抛拒绝。其它异常仍回滚。UNKNOWN继续走原终态和恢复屏障，不新增补偿、重试、网络调用或二次写入。

Mapper复用原 SqlSessionTemplate／DriverManagerDataSource／事务管理器；每次调用显式传原query，XML无 `${}`／SELECT *。同一上下文的累计实计、绝对deadline及durable=false不依赖DB继续适用。媒体修复的evaluation前缀、完整权限／fence／tenant检查及持久拒绝白名单保持。

## 删除与表项对照

本批删除 **25处** JDBC SQL源调用，逐条原文、参数顺序和实际Mapper见 `evidence/b2-invocation-removed-sql.json`，没有重复计算此前55处模型迁移。

| 原实现 | 本批替换／删除 |
| --- | --- |
| JdbcInvocationWorkflows | 删除整个旧类；MapperInvocationWorkflows承接事实读取／具名操作，规则迁Service；slot、read history、安装pin、工具额度、RUNNING、producer、成功CAS、TOOL结算、STOPPED、image producer、web event、step |
| JdbcCoreRecords.invocation | 删除匿名实现；MapperInvocationRecords承接失败CAS、TOOL结算、UNKNOWN、HELD_UNKNOWN；execution工厂暂留 |
| JdbcLedger.rejectTodoBypass | 删除旧规则与5个字面tool查询；Service保持5次独立惰性读取和原错误顺序，Mapper将固定tool值参数化 |
| JdbcLedger.fenced／checkMaterials | 迁移共享fence CAS和2个材料事实查询；不改变调用位置或更广规则 |

涉及 invocation、installation、task、tenant_budget、reservation、evaluation_budget、event、step、task_image_object。普通installation读复用MP，不声明单列@TableId，不注入复合表by-id；锁、CAS和账目复用参数化XML。原 tenant/id 或 tenant/task/id 谓词逐条保留，集合查询按原范围保留，不以增添无依据键改变业务。

源码库存重新生成，153个原入口均可映射；剩余JDBC字面调用从465降至440，动态24，剩余字面参数绑定1356。库存只提供本版源码调用／参数／条件和实际XML链接，不等于全业务覆盖或并发穷举。所有36个表项状态OPEN；目录、session、scope、材料等传递模块仍有旧SQL／策略，不能以本批调用或探针关闭整表／全调用树。

## 验证与证据

定向 `verify` 选择 InvocationRulesTest、PersistenceAssemblyTest（含durable=false）、B2MapperContractsTest、PersistencePathTrackingTest、冻结输入和架构门禁；集成为B2InvocationIT。数量、最终exit／SpotBugs、受测JAR与清理以最终日志和manifest为准。

真实私有MySQL中，同一驱动对准确7d72157旧JAR和新JAR依次运行：6项admit／成功分支（绑定、pin、RUNNING拒绝、成功重放、重复结算拒绝、双连接success竞争）及3项工具失败／UNKNOWN／claim恢复。保留精确状态／账目／事件快照、fence和拒绝回滚断言及SQL／连接／事务原始日志；旧／新不混跑worker。实际worker覆盖普通fetch/read/write/present、双worker唯一效果、成功write后进程恢复不重放、计账和授权下载；合成paid-run覆盖W1/W2非冻结URL准入拒绝与真实已发生fetch的材料后验STOPPED／结算持久提交。

以上仅本地受控provider／TLS和合成paid-run，不是真实付费／模型质量／资格评分或付费视觉验收。不重跑无变化旧完整矩阵，不重制历史整包；原1205观测40001与旧不准确HY000摘要两份记录继续保留，没有依据推断采集层转换。

本批首轮verify：20单测通过，3项集成均失败。旧JAR的6项workflow先通过；新JAR在pin读取因 `Map.of` 不接受原普通调用的空query而NPE，实际worker终态EXECUTION_FAILED，材料非冻结URL分支在pin之前按预期拒绝但后验路径失败。原始日志、中间JAR摘要、驱动和3份完整清理回执保留。修复安装查询的参数Map以保留原可空上下文，同时增加实际MP/JDBC全列Map及读取／字节／操作计量比较、重复／空结果／累计限额和绝对截止；不新增隐式上下文或改变普通调用语义。

本批没有真实read_image／paid visual执行证据；image producer的完整tenant/task/invocation谓词由加载XML合同核定，Service的producer不匹配拒绝由单测验证。目录／scope／session维护锁的新运行穷举亦不在本批；共享fence仅替换SQL承载，原谓词、位置和分支锁序保留，后续模块实际切换继续补证。历史失败、覆盖限制和原付费视觉拒绝继续有效。

SQL事务比较工具首次按完整SQL列表等同断言失败：MyBatis写入使Connector/J的 `SELECT @@session.transaction_read_only` 元数据预检次数增加。它们不读取业务表或持有业务行锁，原始位置／次数和完整日志均保留；工具仅将这一条精确语句单列，再比较业务SQL结构／顺序，而非忽略任意SELECT。列展开、MP固定安装wrapper括号及占位值／空白归一化也有明示。五条定向事务和双连接竞争结论以最终review JSON为准，不宣称网络往返数或所有SQL全文未变。

JAR内容核对脚本首次因生成时替换字符串展开导致Python语法错误，尚未执行内容断言；原脚本和报错保留，改正后的脚本再逐文件核对，不计为制品通过或产品失败。

材料SQL回读工具先引用了历史场景文件名而未找到本轮 `material-facts.json`；修正后，“STOPPED只出现一次”的工具假定又被实际记录否定。后验成功事务先将成功／actual=1／STOPPED提交，随后Worker终态处理再次写STOPPED且不重复成功或结算。最终工具逐项保留全部STOPPED事务，只核定含成功CAS的唯一结算事务及另一个后续终态事务；两次工具失败原文和脚本均保留，不删减原SQL。

最终第二轮verify于2026-10-24 14:17:59+08:00完成：exit0／BUILD SUCCESS，20单测＋3项定向集成通过，SpotBugs bugs/errors均0。9项准确旧／新结果对照、5对业务SQL事务顺序、2连接成功竞争、同连接MP/JDBC全列结果与累计实计／截止、2个实际worker barrier、成功write进程恢复和4项合成paid材料拒绝通过。3份最终清理回执均确认所有子进程退出、fixture停止、私有容器和root移除；TLS监听亦已停止。

受测JAR为43,585,360 bytes，SHA256 `377083663030f452d849e0d8c18f8b9a68dcf796ab4afa1a48743c5acf1b5bc1`；838个编译文件逐项与JAR一致，旧Invocation适配器／匿名实现、临时生成Mapper和测试驱动未打包。源码SHA/tree／分片摘要见本批manifest，先前失败和未覆盖项继续有效。

## 剩余与回退

下一已授权实际B2切片为 task/artifact/operator 及受理／下载业务下沉和剩余真实Mapper链、对应旧SQL删除。共享材料／scope／session／directory等传递路径及35表＋receipt全业务证据仍需后续逐项收口；本批不声明整个Invocation世界或任何表关闭。B2完整后由产品经理核定B3，B3–B5、初级、61/62仍未激活。

没有成员信息／授权缺口；不新增DDL／协议／冻结限额／新功能，不部署、不接生产／真实Key／外部计费／共享数据，不改共享服务／daemon／代理／FRP，不运行或等待GitHub CI。无数据迁移时可阶段回退到7d72157与其准确原JAR（SHA256 `1b1d551cd83e572728c76b6a392efc89357d95bfddb9420d7da840493176f817`），保持唯一写入；本机二进制对照不构成部署或共享混跑授权。
