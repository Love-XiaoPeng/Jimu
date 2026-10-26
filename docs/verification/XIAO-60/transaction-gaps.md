本批从 `26f1c23be0c155c9b9c69126e66f9dbf394bc946` / tree `b42da2043ae73bb946db9bf25b2b3f5d36f66145` 接续原PR22。只接115已受审配置拆分并补G01–G03具名验证，不重复迁移或执行旧整矩阵。

## 115独立接收

116独审及117必要QA均已按自身范围done，无源码阻断。准确 `e78ad60ebc2006c003c466a7cd5ba190dbd1b279` / tree `537199bf84264f55c6743c8afe99a50ce08b1bb6` 通过 `cherry-pick -x` 单独接为 `c00656b988c107bae5ed77da1b391ae3cdb1a44b` / tree `a238ea7387d84b3a4f3f3f2db6df49fae780b004`，已推送PR22并读回一致。8个受审blob完全一致；20工厂完整注解、签名、正文token及1103个其余生产/POM blob核同26f1c23。保留原Bean条件/生命周期、Flyway→validate→同DS六参Ledger→配置→check时序。

A重新构建并锁定自己的受测原JAR：44,985,806字节，SHA256 `86e8a4c7af968b04a8c8800561f15cec0401c8e7cda675c3dfebe8df0723eca5`。它不是B或117的原JAR；四配置class原字节差异原因不作推断。后续只改验证/文档，生产源码与本原品保持绑定。最终源码SHA/tree、逐文件/class摘要、原JAR精确字节恢复配方以交付manifest为准。

## 三项验证

`TransactionGapsScenario` 复用原core、ScopedQueryScenario及session checkpoint/response合法夹具，单一公开入口流程，不为每方法新造driver。所有账本setup明确为合成：paid=false、本地模型配置、真实submit/claim/prepare/dispatch/modelResponded/finish/quiescent API，协议响应为测试构造，未执行worker循环、Host、外部工具进程或provider。G02/G03确实执行账本内原生session_event_read；日志中的no tool/provider与summary的noProviderOrToolExecution仅指外部fixture工具/provider调用数为零。没有改变生产时钟/网络/超时或添加生产hook。

G01在真实同DS事务内执行core prepare，确认原最后commitFence及业务返回、同连接可见step，再由测试同步器在原beforeCommit之前有界等待原绝对deadline自然到期。原beforeCommit实际抛SESSION_QUERY_TIMEOUT，原socket截止与rollback竞态产生08007/vendor0（ACK未知）和08003/vendor0清理错误；Spring只将commit原错保留在日志，最终标准异常图是timeout包装rollback错误。测试用只读Logback捕获完整原错和清理栈，不改框架或把日志保留说成返回异常图保留。独立连接逐全行核35张可变私表（含worker_process）before=after；只读flyway_schema_history不纳入本轮DML恢复计数。没有收到成功rollback ACK，不重放UNKNOWN。本路径在run4已通过；run4随后source response夹具v1被原v2资格拒绝，整IT仍exit1，不能改记整轮成功。

最终run8独立1IT exit0（130.5秒），按同一原JAR摘要复用run4实际G01记录，G02/G03在run8执行。执行source/class、原始全行快照、SQL/绑定/事务trace及原异常栈随附，不把run4整轮改为通过。

G02由公开scopedOrResult完整执行原生session_event_read并提交SUCCEEDED invocation、原invocation_session_scope_query收据和event，绑定原live attempt/fence/slot。计量实录268 reads／265379 bytes／5653 operations；原TOOL reservation SETTLED/actual=1、tools_used=1。相同request重放返回逐字相同output，不新增执行、receipt、event或工具消费。全行before/after都保留；重放原本会更新task revision/updated_at并重测storage metadata，比较只排除明确列出的这些原计量字段，不称全库零写入或资源零计费。

G03缺失seq=127自然返回SESSION_EVENT_NOT_FOUND：原事务先提交FAILED invocation/收据、TOOL结算及第二次工具消费，再由公开API在事务外抛404。独立连接读到持久行；同槽重复仍404且未新增消费。之后关闭原session_search installation，另两次自然403路径各先rollback，再由原afterCompletion用独立REQUIRES_NEW连接写一条失效后记并commit，连接对分别47→48、49→50。重复标识保留第一次invalidated_at，FAILED收据不变、tools_used仍2。404提交与403回滚后记是不同原路径，未把二者合并解释。没有外层fixture事务；本轮观察到的持久行随独占私库删除，不能移记为生产持久化或外部并发验收。

run8清理实录containerRemoved/rootRemoved/allChildrenExited/fixtureStopped均true，failures为空。G01只证35可变表原写入恢复，G02/G03只证独立连接可见提交、去重及原后记顺序；两类证据分别保存。

## 命令及失败

```powershell
.\mvnw.cmd -B -ntp verify '-Dtest=DurableAssemblyTest,PersistenceAssemblyTest,QueryViewAssemblyTest,PackageBoundariesTest' -DskipITs
.\docs\verification\XIAO-115\verify-relocation.ps1 -Base 26f1c23be0c155c9b9c69126e66f9dbf394bc946
.\mvnw.cmd -B -ntp test '-Dtest=SessionQueryTransactionTest,PersistencePathTrackingTest'
.\mvnw.cmd -B -ntp spotless:check checkstyle:check
$env:JIMU_IT_JAVA='/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java'
.\mvnw.cmd -B -ntp failsafe:integration-test failsafe:verify '-Dit.test=TransactionGapsIT' '-Djimu.gaps.round=run-8' '-Djimu.gaps.reuse-g01=target/failsafe-reports/transaction-gaps/run-4'
```

16项装配检查/SpotBugs0及10项事务/路径追踪通过，合计26定向＋最终1IT；前一verify显式skipITs，不称它执行了私库IT。最终受测原JAR独立IT命令、退出码及逐轮执行source/class封存另交原日志。最终spotless/checkstyle exit0。路径图已按接入后的bootstrap来源刷新。

run1的worker ID超列长、run2仅看顶层栈的误期、run3标准异常图仍无commit原错的真实框架边界、run4合成Response版本误配及compile5测试表达式括号错误均保留；compile5未产出新class，其归档class是先前输出，不作为失败源码的执行件。其余compile3/4为测试补诊断和quiescent来源准备，未另执行私库。run5首个scope连接准入自然超时；run6增加独立SELECT 1连接预热后仍在首次scope准入超时。run7增加独立普通core合法setup后完成source/caller准备，但首个scoped read在finish后的metadata计费触及原2秒截止，未获得成功提交凭证。未知ACK调用没有重放。run8仅给测试子JVM增加 `-XX:TieredStopAtLevel=1`，降低首次分层编译开销；没有增加/刷新业务deadline或改动生产JAR。每次私库失败先封存后改，清理回执独立保存；没有把未到达G02/G03的前置失败计为这两项验证，也不由此宣称冷启动SLA通过。

## 关闭清单与剩余

当前逐表覆盖层为 `evidence/transaction-gaps-closure.json.gz`，继承旧覆盖层的35管理表＋外部只读receipt共36行。G01–G03改为开发已验证、待独立差异验收；实际业务迁移、必要基础设施、开发新具名验证、最终独立验收分别标记，历史全OPEN底稿原字节保留。附件manifest逐项认证，evidence.zip.partNN按manifest顺序拼接为ZIP；随附verify-transaction-gaps.py可用原26f1c23 JAR和交付目录作两个参数离线复核索引、精确JAR还原、class及proof关系，不执行Java/DB，也不替代独审QA。

库存仍9静态/22动态/0静态bind，SQL消除0；不为归零改原基础设施API。G04已单独接115；G05由独立B120实施，须其独审/必要QA通过后由唯一A安全串行接入，本批未重复治理。G06仍由PM锁完整稳定候选后激活61/62。B0–B5及总体未宣称验收，60继续in_progress，PR22/23仍draft，master保持bcb63c5；代码卡最终master推送读回前不done。无CI、部署、付费、共享服务或T48操作。
