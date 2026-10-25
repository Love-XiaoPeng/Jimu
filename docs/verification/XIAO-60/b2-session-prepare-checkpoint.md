# B2 prepare 部分检查点

以 `b47e668ab33b6b7f8a520c44883807ec41c9dfa6` / tree `ce3345f83d92b96c397f9c2a74cf8b50012fcafe` 继续原分支与草稿 PR22；本片不是 B2 或整体重构验收。

`JdbcSessionRecords.prepare` 已接 `SessionCheckpointPreparationService` / impl、类型化惰性 checkpoint/history/scope 端口及实际 MP 持久化。普通三字段 step 更新复用 `ScopeQueryStepReadMapper.update` 与 LambdaUpdateWrapper；七列 scope context INSERT 使用 `SessionPreparedScopeMapper`，独立账本与应用装配均注册。完整 tenant/id 和 tenant/step/caller 键、显式 NULL 及数据库默认列保留。原更新数仍忽略，不加 CAS、预读、查询、事务或双写。

顺序保留：先 decode，request 131072 → requestBody 65536 的字节短路，messages 空 → 末项 role.equals → content.equals(context) 及原 NULL 异常；然后 history(task,true) → next_seq-1 / 原 content hash / binding → step UPDATE。scope 中再取末项 content → require → INSERT → body(context)，最后 body(request)。原重复 context/history/资格读取不合并。`ExecutionServiceImpl` 的 reserveMedia → insertStep → prepareProjections → preparedEvent → commitFence，及 session 后 directory/question 投影的源码保持。

实际删除 2 处旧静态 JDBC / 12 个字面绑定；库存静态182、动态扫描26、587 binds。新增扫描项是固定 statement ID 的 MP update，非外部动态 SQL，准确方法/字面值已审阅。12 个既有调用者、ExecutionServiceImpl、412 份既有资源/冻结输入、schema22/V1–V22 原字节未变。35 表和只读 receipt 全部仍 OPEN。

准确受测 JAR SHA256 `c1bf9870dff1b7f5a30e9f7d49c2e953da6d650f92e270fd9627ccbe3176522d`，44,391,230 字节；1,440 个 target/classes 文件逐字节匹配 Boot JAR，测试驱动未进入产品制品。源码 SHA/tree 与原分支/PR 即时回读见交付 manifest。

第 2 轮完整定向 verify 退出0：43 项单测/契约 + 1 项集成（native-v3/scope-v4 两组），SpotBugs bugs/errors 均0，3 份私有完整清理成功。命令：`mvnw.cmd -f Jimu/pom.xml -B -ntp verify -Dtest=SessionCheckpointPreparationServiceTest,SessionCheckpointPreparationAdapterTest,SessionHistoryStorageServiceTest,PersistenceAssemblyTest,PackageBoundariesTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest -Dit.test=B2SessionPreparationIT -Djimu.sessionPreparation.evidence=b2-session-prepare-run2`；Linux Java21 由原 reviewed JIMU_IT_JAVA 提供。仅开发自测，不替代独审/QA。

原生旧/新各10笔、scope各12笔结果按 JSON 值同序一致；真实 JdbcLedger.prepare / ExecutionService / insertStep / session projection / preparedEvent / 最终 fence，以及 checkpoint 和同 request prepare 回放均到达。step、scope INSERT、context/request body 的单次 SQL 故障，满额 body 条件拒绝，messages/role/content/NULL/双字节限额分别覆盖。故障时 step INSERT 已发生，后续受影响事实随外层事务回滚。每笔对捕获 tenant 的16张事实表及预算作全列恢复核验；每驱动1条 body 满额夹具 UPDATE 也回滚；worker 注册/claim 属外层试验。无试验提交、caller provider 调用或 UNKNOWN 重放。scope 来源任务由真实 worker 完成，caller prepared 结果不是 caller 模型派发或全任务成功。

原始 context/request 的摘要、scope context 原摘要/长度、实际 step.started event 原摘要/长度及 request source_digest 分别核验；保留生成 UUID 与 Map.of 跨 JVM 字段次序差异，只对规定结果比较 JSON 值。当前受测 SELECT 文本/顺序逐笔对照，含原重复 context/history/require；不泛化 wire 字节、物理 SQL、计量或往返相同。没有重新序列化后冒充原 stored 证明。

首轮完整 verify 失败保留：旧 JAR 实际 preparedEvent 还会追加 step.started，夹具误将返回后的 next_seq/body 视为追加前；仅修夹具为 context upper、追加后 cursor 及事件正文字节并补原事件证明，不改生产。首轮准确 JAR/驱动/日志保留并提供无损重建。末轮 verify 后仅纠正驱动一行继承旧 search 的 Javadoc；受测源码/编译件原件保留，运行谓词及生产内容未改。库存首次审阅拦下固定 MP update 新项；准确登记后通过。外部源码核验器首次未匹配 formatter 换行的服务名，原核验器保留，仅修选择器；原工具错误仅有工具返回与诊断，没有另存完整原始 stderr，不虚构原件。这两次核验器问题不记为产品测试失败。

剩余 B2 runtime/受理/模块/维护排队。下一建议具体片为 `JdbcSessionRecords.response` 的完整 Service/类型化预检及 MapperModelWorkflows 实际响应链，尚未启动。scope 完整成功 read、持久化 FAILED read、独立 afterCompletion/REQUIRES_NEW 后记、core 最后 beforeCommit、冷锁/完整锁排列，以及历史 v1 read 原槽、503 原因、后继 ModelDispatch 超时及全部旧失败继续 OPEN；本片不重跑无变化矩阵，不称整库回滚或全任务验收。

本 run `01a170cf-4de4-780f-995f-88ddcb0a17b3`，PM 已有准确三终态/两小时规则 `01a170d0-e0d6-7bd3-a592-e4e887cc7023`，不另建重复规则。60 保持 in_progress，PR 草稿。没有新增外部/成员授权阻塞；B3–B5/初级/61/62/T48 不激活，不运行/恢复/重试/等待 CI，不部署/接真实 Key/外部计费/共享服务数据，不操作 daemon/代理/FRP 或重置数据，维护切换交主控。
