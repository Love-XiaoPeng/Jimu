# XIAO-60：B2 worker 与执行记录首批切换

本批从 `f5fc481949333a6f8d08f4872042e1d38381fb8b` 接续原分支及草稿 PR #22。产品经理已激活 B2；这是 B2 的部分实现检查点，**B2 未完成，35 表＋只读 receipt 全部仍 OPEN**，不启动 B3–B5 或独审/QA。

## 实际调用链与唯一写入

| 入口 | 规则/事务所有者 | 本批实际 Mapper 操作 | 剩余边界 |
| --- | --- | --- | --- |
| registerWorker / processes | WorkerServiceImpl | MapperWorkerRecords → WorkerProcessMapper BaseMapper.insert；显式 host/PID/startTicks/boot/namespace 投影，最多64行 | native-search 的 host 读取和跨表维护联查仍在旧适配器，worker_process 不关闭 |
| renew | WorkerServiceImpl | TaskMapper.renewOwnedLease，tenant/task/attempt/worker/fence、未过期及原 state 谓词 | 旧 fence/过期返回 false，无重试 |
| claim | WorkerServiceImpl + 原 tx/UoW | TaskMapper 候选/精确 SKIP LOCKED；TaskAttemptMapper；ReservationMapper；TenantBudgetMapper；InvocationMapper；EventMapper | scope/当前权限/材料和 terminal 的旧模块操作仍保留；不称整个 claim 调用链已无 JDBC |
| quiescent / processStopped | WorkerServiceImpl + 每 task 原 tx/UoW | native 活动观察、全租户 attempt 收据、lease 过期及 worker 停止 Lambda 更新 | 每 task 提交后才写 stopped；旧 settleStoppedWriters/终态释放仍待切换 |
| checkpoint / prepare / finish / stop | ExecutionServiceImpl + 原 lease admission/tx | StepMapper 原 checkpoint 原字节/ordinal、prepared insert/目录绑定；完成/未决效果/报告探测、summary；EvaluationBudgetMapper.stopRun | user_question 交付与各 B3/B4 投影/terminal 仍为过渡实现；model 和 invocation 主工作流尚未切完 |
| 上述及其他既有调用者的共享原语 | 各原用例事务 | budget/task/evaluation 的原位置取锁，event fact/序号、reservation insert、writersQuiescent | 固定显式投影保留 V22 物理列序；媒体前缀修复代码顺序保持 |

`evidence/b2-removed-sql.json` 逐条登记59处原 JDBC SQL 调用、参数类型/顺序及实际 XML。另有两处进程普通 CRUD 换成 BaseMapper/Lambda，共删除61处原调用；具名 XML 为48个语句（含同 SQL 的 map/标量投影）。这不是61个完整业务用例或48张已迁表。所有已切换位置仅保留一个写入实现，无双写和任意 SQL Mapper。

`path-tracking.json.gz` 仍保留153个基线入口声明；新版本另列 XML 语句、静态可达 Mapper 方法、参数和各表剩余 JDBC。可读表项见 `evidence/b2-table-status.csv`，入口对照见 `evidence/reviewed-entries.csv`。BaseMapper 生成语句以真实 SQL 日志另证，不将静态可达等同运行覆盖。旧历史行号和词法键命中不能作为关闭依据。

## 装配和兼容

产品 Bootstrap 传入既有 SqlSessionTemplate，queryView 复用同一组 Mapper；直接构造账本的工具/测试入口同样使用 MP/XML，并在同一传入 DataSource 上装配 Spring SqlSession。没有第二 DS、池化、独立提交或新事务。一级 STATEMENT、二级禁用；显式 query 参数继续流入现有 Statement/ResultSet 计量与绝对 deadline 适配器。

Map 只在过渡持久适配器中传递原列标量，不成为权限上下文、DTO 或快照序列化。普通 worker 元数据使用独立 Entity；复合键 Mapper 未声明假的单 id、未使用 by-id。普通 task/step 插入的实体/CRUD 收口与旧大类删除仍待后续 B2/B5，不将本批视为最终结构验收。

真实 MySQL 首轮发现：`SELECT evaluation_run_id` 的唯一列为 NULL 时，MyBatis 默认将整行映射成 null，旧 JDBC 返回含 null 的 Map。配置同时开启 `callSettersOnNulls` 与 `returnInstanceForEmptyRow`，恢复原行形状。首轮失败日志及清理回执保留；最终结果以附件 manifest/JUnit 原文为准。

## 验证范围

本批专用 `B2MapperCutoverIT` 使用同版 JAR、独立 pinned MySQL 8.0.46、受控 loopback provider：核心结算8组、工作流22组、worker 双领取/过期/旧 fence/恢复/停止收据/取消/实际 attempt 写后回滚，对照准确 f5fc481 制品；真实双 worker/受理幂等/授权下载/SSE/旧新制品顺序回读。新增投影逐列对照真实 information_schema 的 V22 物理列序。

因共享锁/事件/步骤原语受影响，另跑 question/todo/directory 正常投影钩子、session read/search/trace 实读，以及 repaired f5fc481/本版的媒体锁与账目/拒绝/写后回滚。媒体对照只运行两个已经修复的制品，不重复 ff52841 的旧死锁矩阵；原失败历史继续保留。

开发自测不替代独审/QA。合成 paid-run 只覆盖账本分支，真实 paid vision 仍 `MODEL_INPUT_BOUND_UNAVAILABLE` 且 provider 零增量；没有真实 Key、外部计费、共享数据、部署或 GitHub CI。

最终定向 `verify` exit0、BUILD SUCCESS：26 项单测、4 项集成均0失败/错误/skip，SpotBugs0。命令如下（JIMU_IT_JAVA 指向已核 Linux Java21）；8 份最终私有实例清理回执均确认进程/provider退出、容器及临时目录删除。首条 verify 的26单测通过、4集成失败及整条 exit1、四份清理，另与最终记录分开保留。

```text
mvnw.cmd -B -ntp verify -Dtest=B2MapperContractsTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringNegativeGatesTest,RefactoringFrozenInputsTest,PackageBoundariesTest,ModelProfilesTest,ModelProtocolTest -Dit.test=B2MapperCutoverIT
```

本轮受测 JAR 为43,533,179 bytes，SHA256 `ce8e8966643c00ef365b1b80549f5959531f9a90840bf911a9766ecaa885fea0`。源码/tree 和逐片重建摘要以本批附件 manifest 为准，四个集成组的实际 fixture JAR 摘要一致。没有把未执行的旧完整矩阵算成通过。

## 旧1205 SQLState 勘误

前批 `media-lock-checkpoint.md`、manifest 和已交 ZIP 中的摘要将旧 lock timeout 写成 `1205/HY000`。实际 `old-timeout-trace.json`（SHA256 `fbf96157df63b70cf994bc363e897ec107d7d8a171f02de6ae9ae8bcb961f583`）记录的是 **1205/40001**，未包含 HY000。

`MediaLockScenario.ObservedSource` 在产品 PreparedStatement 的 JDBC SQLException 层、Spring 翻译之前采集 `getErrorCode/getSQLState`；outcome 再遍历 SQLException cause。40001 是本次 Connector/J 实际观测。没有原始证据证明这里曾采到服务端 HY000，不能把差异解释成已证明的服务端/驱动转换；HY000 是摘要中的不准确表述。今后该轮按“JDBC 观测 1205/40001”引用，错误码1205与1213继续分别区分锁等待超时和死锁。前批两份 ZIP/manifest 与原始异常文件保持原字节；此说明追加勘误，不覆盖两份记录。

## 继续 B2 的清单和回退

下一切片优先 ModelDispatchService 的 snapshot/dispatch/responded/failed/settleCost 全调用链（当前 JdbcModelWorkflows 仍有28处、JdbcCoreRecords 的 model/invocation 合计仍有10处字面 SQL）。之后继续 invocation/admit/success/UNKNOWN、task submit/get/cancel/events、artifact stage/verify/publish/授权下载、operator bind/assessment，并将受理/下载规则完整下沉 Service；逐项删除相应旧 SQL、补实体普通 CRUD 和表项实际证据。材料后验持久拒绝、receipt/requirement/binding 解析组合、native-active 阻止停止收据分支及维护锁排列，需按这些后续切换补证，本批不冒称穷举覆盖。

无 DDL/协议/冻结字节变化，仍精确 schema22。私有验证按先停一个制品全部 worker 再换另一个制品回读；不授权部署或混版本并发试写。可回退本批前准确 f5fc481 制品；总体最终回退契约仍按已接受 schema22 基线。失败与未覆盖项保持开放，本项 in_progress、PR 草稿。
