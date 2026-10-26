本片只迁 `JdbcLedger.maintainQuestions`，准确parent `acda47bde3ce4dbaa13ab5e8a6e147c3821d3741`／tree `15aca806a880aaf9d51b316d2f0568782ee67420`，沿原分支及PR22 draft。新SHA/tree由交付manifest及Git原对象固定。仅B2部分检查点，60保持in_progress。

受测原JAR来自run3：SHA256 `b3495f3b602d4808effa1efce48042166b7dbb5b47c724afaa44914e5e078462`，44,677,596字节／1,691生产entry；run3/run4执行清单均指向此原品。run4另行打包文件没有被执行，不充作受测原品。旧JAR SHA256 `fe9125c3577e6476f3c6ff2fa4c81fd318f1ea08a987b3fccb506155b8150457`，44,670,599字节／1,685 entry。直接读全部旧新entry，1,679既有原字节同，6新增；5个既有Ledger嵌套class仅Code.LineNumberTable变化，归一化后其余字节相同。670其他生产Java／432既有资源原字节同。schema22、已迁Service、Bean/bootstrap/POM、Controller、cleanupArtifacts调度链及所有其他Ledger方法不改。

## 完整编排及惰性边界

纯Java接口/Service/impl拥有全局扫描、逐candidate事务、资格、问题循环和末尾新锁/结算；专属类型化端口只承接单项操作。构造仅保存Ledger及事务/close/Continue引用，不读flag/Map/会话/环境/时钟。首次flag作为扫描Boolean实参到达，随后才查询会话；扫描在逐项tx之外。候选按JDBC原列表逐项执行原tx，空扫描不入tx，没有整批事务、并发、补扫、重试或新增异常隔离。

逐项tx→首次candidate tenant/bucket→第二tenant/id/完整locked。非WAITING_USER/RESUME_READY立即null返回。取消→Number.longValue deadline→此candidate当前位置重读flag→原access；仅access的TaskFailure被catch，使用原code，NULL code继续原后段。其他RuntimeException/Error原实例传播。rejection才close→terminal（仅CANCELLED映射CANCELLED，其他FAILED）→null，不走due或尾settle。

无rejection才以原task tenant/id读due列表。每行完整Continue接收同原task/q引用，保留数据库实际行序，无SQL ORDER BY或Java排序；前行改库不刷新task。零/多行全部完成后独立重读task tenant/id→新locked→settleStoppedWriters(新锁结果)→null。任何原未捕获异常阻断后续问题/候选；前项事务不被改成整批回滚。原tx、access、locked、close、terminal、Continue/ToolResult/Resume/event、settle助手内部均冻结。

cleanupArtifacts唯一入口及maintainQuestions→cleanupQuestions→cleanupTodos→validateSessionScopes→cleanupSessionHistory→cleanupDirectories→cleanupMcp→cleanupImages→artifactRetention顺序不变。本片不启动daemon或整cleanupArtifacts。

## 两SQL及真实JDBC

QuestionMaintenanceMapper/XML保留原实串：全局两LEFT JOIN/完整tenant联接/OR/EXISTS/JSON路径/NULL三值逻辑/CURRENT_TIMESTAMP(6)/ORDER BY deadline,id/LIMIT64扫描，仅1Boolean；due完整SELECT*、tenant/task/OPEN/foreground_due_at及FOR UPDATE，仅2String。没有tenant过滤扫描、锁候选、Java计时/分页/批量/额外谓词。只对准确due SQL增加窄投影断言，不扩大共享投影门禁。加载SQL及nullable/显式query配置合成验证，LIMIT64由原SQL核证，没有堆64个数据库夹具或声称实测65行截断。

实际扫描2列，due33列；case/列序/每列值类型/NULL与本侧原夹具逐列核对。旧executeQuery/getObject索引→新PreparedStatement.execute/getResultSet/标签类型化getter；扫描Boolean从setObject→setBoolean，due两String仍setString。setter/execute/getter/metadata原记录及API差异见jdbc-api-proof.json。普通Statement与prepared均采，本轮测量中的实际产品SQL均prepared；无Statement漏记为0SQL。SQL文本序列旧新逐侧核对，但不称全部JDBC调用相同。NULL wire及SQL错误映射没有机械注入新验收。

迁2旧JDBC，SQL消除0，落地库存138静态／29动态／459bind，35表＋receipt仍OPEN。tenant/whole-scope/fence/权限、原锁序/同DS/UoW、queryView起点/实计/绝对截止/caller末尾检查源码冻结；本轮直接维护调用query为原NULL路径，不冒称新增queryView完整成功工具read验收。

## 原执行轮次

run1：首次静态盘点和verify产品编译均在适配器直接调用private tx处失败，未到测试/package/IT/SpotBugs。原源码、部分/旧target class、编译清单及完整日志封存；失败编译target不归属受测产品。改为现有切片的窄事务函数引用，tx内部不改。

run2：产品编译通过，testCompile在assertThrows条件Class类型推断处失败，未到单测/package/IT/SpotBugs。封存后只显式声明测试异常类型。

run3：9Service＋2port＋2追踪＋1架构通过；两driver均0，due继续成功，cancel问题CLOSED但task仍WAITING_USER/code=NULL，因为PAUSED attempt quiescent=false使原terminal助手提前返回。助手误期CANCELLED任务，比较assert失败，IT/verify1。SpotBugs bugs/errors0。此轮原proof/失败完整保留，不称达到终态或产品行为回归。

run4：只加强显式夹具quiescent=TRUE（私有试验从未启动执行worker/native writer，非真实进程观测回执），产品不改，复用run3原JAR。2追踪＋1架构＋1真实IT通过，无skip，verify0/BUILD SUCCESS、SpotBugs bugs/errors0，两driver0。不重跑run3已过11业务/端口测试。每轮source/class/编译/完整日志在后续改动或执行前封存；原主错/rollback/cleanup/落盘错误分存。封存器第一版误包含旧opening助手，run1未执行任何助手；随后追加正确maintenance原助手并保留旧封存，不冒称旧助手已执行。

## 私有paid=false直接维护实证

run3/run4各旧新两次直接package maintainQuestions，共四对/八调用。每次扫描实际仅1合法候选；两列投影、Boolean bind、排序及LIMIT实串核过。run3每侧due32SQL/cancel17SQL；run4每侧due37SQL/cancel36SQL。run4 due：WAITING_USER/OPEN rev2→CONTINUED rev3／RESUME_READY，ToolResult→Resume→question.continued，并在所有due完成后新locked/完整settle，running预算减1、question_tasks_held不变。run4 cancel：先取消短路，close问题→原terminal CANCELLED，未查due、未Continue、未走本方法尾部新锁/settle；terminal内部原锁/结算按实际完整记录，不将其误称本片尾settle；running及question_tasks_held各减1。

due完整tenant/actor/installation锁链和同DS/UoW连接、事件及预算变化、投影原值类型/NULL、原输入/output/hash/UTF8长度封存；cancel在取消首拒绝后按原短路不执行access的actor/installation查询。数据库时间由本侧SQL due设置、锁谓词及事件/更新行原值核证，本轮未另加独立当前时刻查询。各侧独立生成ID/时间/Map.of不强求字节相等，不冻结/重放数据库时钟。

双call/intent/step响应、timed0.2.0问题/WAITING_USER invocation/TOOL RESERVED/pin-binding及显式due/cancel/quiescent均为合法setup事实，非Host工具/provider/真实问答事件生成。setup registerWorker/claim不启动worker循环；PAUSED非有效执行租约，本方法无Lease。新问题并非本片生成。HTTP仅新侧任务setup，非HTTP/Host入口验收。

生产源码证明扫描在逐candidate tx之外；真实样本为了恢复均有显式外层夹具事务，扫描/内层tx共同加入该外层，不能称本次实测逐candidate独立持久提交。8笔tenant-a21表外层前后事实恢复核过，worker_process未纳入；两个私有容器/子进程清理回执封存。不是整库恢复、持久拒绝提交或独立afterCompletion/REQUIRES_NEW后记证明。

## 剩余与交付边界

本片开发自测及离线复算，不是独审QA/总验收。B2继续；61/62仍backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read/独立后记、其他真实缺行、自然08S01/709ms/冷路径及历史准备stderr缺件仍OPEN。没有CI/合并/部署/新工具/provider/付费/DELETE/非法schema行/故障注入/共享服务维护/网络时间配置变更。

准确源码尚有pendingQuestionMessages的完整leaseTx→fenced→questionMessages→values.stream.toList公开编排，及questionContinuation的leaseTx/fenced/标志读取包装留在Ledger；其内层Messages已迁，尚不可将整个B2关表。建议PM先核定pendingQuestionMessages完整入口资格下一窄片（自身0旧JDBC），本轮不混迁、不开新任务。原TODO受理、其余模块与历史缺件仍按库存逐片续派。
