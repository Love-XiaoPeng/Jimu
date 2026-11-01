R31 实施已交付原 feature 分支；一次真实业务验证仍在第二受理触发操作上限，未形成稳定候选。按裁定停止本轮进一步预算优化，等待原 PM 基于本版证据决定后续。

## 版本与范围

- 代码提交 `b695e74a5e65417523fc187f069eecc3a598d228`，tree `6a7d43a93036894668794fff608fe73f2461f2f2`，父提交 `9b6bfe58952907f911860351d19c702c930819bf`。保留原 jimu-v5、feature/XIAO-125-session-lineage、draft PR24。
- 最终完整 JAR 45465373 B，SHA256 `399fa6e6532d70bf0f07981ed079e400ae5abcc52eedd0651ce0d918a2ab9f02`。业务 runner 绑定该提交、同一 JAR，运行前 tracked diff 为空。源码/类/全部内部类/mapper XML 与 JAR 条目逐字节绑定见 manifest；不声称构建可重现。
- 本次9个生产文件、3个测试文件；随后只追加本文档。没有改公开 API、schema/migration、费用单位或四项预算。
- 冻结 decision-input 的既有34885 B / `7bc347fdb9eb65811ab81c54a2473e4f9840a779ba82cff3c0d5abdf998d3882` 证据沿用；本轮未另行取回或重验其文件。

## 实现与 owner/动作账

|边界|实现与分配前计量|实际释放/失败行为|
|---|---|---|
|成对输入|port 增加 Input(Fact,Stored)，默认仍 fact+load；service 的已有节点统一使用；新 caller 保持 fullFact、生成/插入及 fresh createdReadback|不按节点键在 Work 另存输入，不新增 codec|
|admission|借用本 Work、query、连接、锁及 stagingCaller 绑定的原 SourceFact；沿用其 Authority/Scope/History|原 staging owner 连续保留；未有依据时只在新 SQL 前回旧路径|
|普通 require|eligibility.require 全部完成且 holdScope 后，同步暂借原 Validated 引用；来源 Authority 从原 authorities 按 scope raw/digest 取得|不复制 sources Map，不提前发布 scope；相关写和嵌套/foreign 借用 poison；finally 清引用|
|闭包/依据包装|调用方创建 verify lambda 前先 prepareLineageScope：4 ops、512 B；withLineageScope 及逐来源锁/键检查先付；LineageBasis 先保留512 B|拒绝后不分配闭包/包装；正常退出清别名再退 owner；失败保留至 complete|
|尺寸/原始输入|nodeInputSizes 取三个真实 OCTET_LENGTH；一行三列、非负及各原上限；先付26动作，保留 sourceFactsBytes+8192，再按实际三份长度保留 sourceFactsBytes+8192+24*(request+genesis+graph)|尺寸拒绝不执行 raw SQL；真实 JDBC/Map、编码、树、Fact/Stored及service局部容器并存期间持续计费|
|raw SQL|完整tenant/task主键 JOIN、14列；原 QueryResults 逐行/列计量，实际物理连接、原表/原列/label/MySQL type/UTF8检查；size表达式单独核形状/label/类型|新 SQL 开始后缺/重复/异键/metadata/预算/SQL/Runtime/Error均首错失败，无旧路径补读洗错|
|逐份证明|request/genesis/graph 每次 SELECT 清本次 identity 命中再独立严格UTF8/长度/hash；service继续schema/pin/standalone/完整node-map关系及双codec校验|相同 String 身份不跨 SELECT 复用raw proof；不借 graph 内引用替代 standalone|
|输入生命周期|清映射行和容器后，Fact/Stored及rawViews仍归外层withProof；输入已发出后的相关source/target/actor/installation/unknown写直接比较原引用，按tenant/id/actor字符串长度先付|写前 poison且保留owner；不因Map删除退款；新 graph owner先建立再释放临时输入；withProof结束断链|
|Error清理|withProof新增Runtime/Error poison；QueryResults元数据构造Error会记录原Work失败并关闭cursor|原Error仍抛出，关闭失败挂suppressed，禁止上层吞错后提交|

原权限/live/terminalAccess/expiry/fence、完整tools比较、history/eventHistory双读、四raw并存/第五回退、metadata14列/两动态values、267596+8192连续bootstrap、Settings及REQUIRES_NEW/resume等保持原路径和费用。保留256 ResultSet、绝对2秒、1MiB、65536。

## 必要验证及准确覆盖

本轮按增量版本验证，没有把所有成功矩阵重跑到最终 JAR。前缀/poison/accounted三份完整JAR保留，manifest列出它们与最终JAR的实际类条目差异。

|制品/步骤|命令与结果|限制|
|---|---|---|
|prefix `d06af73d…`|native `mvnw.cmd -B -ntp spotless:apply verify -Dtest=SessionLineageNodeBorrowTest -DskipITs`：6方法通过，编译/格式/checkstyle/SpotBugs通过|首轮fixture未进入lineage模式的失败原报告保留；修fixture后通过|
|prefix SQL|`run_r31_sql.py graph-once`：admission graph、普通require、重复SELECT同String身份3项通过|真实MyBatis/Connector/private MySQL；不是完整业务验收|
|prefix新列反例|`run_r31_sql.py negatives`：22项中18通过、4失败|真实SQL返回后注入映射行；metadata走实际SELECT。charset/type表达式先被原来源检查拒绝，不能声称独立覆盖后续charset/type分支|
|poison `7cfac7c8…`|修service失败未poison Work缺陷；`run_r31_repair.py repair-four`：仅重跑原失败4项，全通过|genesis/graph同长篡改及两digest；旧4失败保留，不算成原版通过|
|accounted `b4e9cc42…`|相关写比较按字符长度预付；仅重验 `exportedAdmissionInputsKeepSourceOwnerAcrossEveryDependentWriteRefusal`，verify通过；`run_r31_repair.py boundaries` 6项通过|size label/type/shape、raw读取前内存拒绝、重新hash后的genesis/graph pin错误；没有重跑旧矩阵|
|final `399fa6e6…`|最终 `mvnw.cmd -B -ntp spotless:apply verify -Dtest=SessionLineageNodeBorrowTest#verifyClosureHasOwnerBeforeItCanBeAllocated -DskipITs` 通过，SpotBugs 0问题|只重验新增闭包分配前拒绝。第一次用未计量上下文失败，保留；改用带QueryResources的计量上下文后拒绝、回滚、末值65536均通过|
|final业务|`sql_batch_r31.py second wsl genuine-two-r31-second-event-history-admission-only-observe`：约定的一次尝试，结果见下|最终JAR及fixture类hash有记录；不是CI，不重跑response/witness旧suite|

SQL metadata实际TEXT/TEXT/MEDIUMTEXT成功路径通过；foreign query/Work、完成/poison、REQUIRES_NEW、嵌套、写/发布拒绝由新单测覆盖。没有声称新增了独立的所有物理连接故障矩阵。原成功9方法/11SQL/旧6项/R30二十反例仅按原版本、原覆盖沿用。

## 新阶段账与一次业务验证

定向一节点 graph 的完整G：R30 `11213 ops / 3 reads / 2755 B`，prefix R31 `9330 / 2 / 1862`；真实消失1次读取、893 B、1883 ops。普通require完整G `9277 / 2 / 1862`，独立证明没有重读authority raw。重复SELECT同String身份的两个原始输入仍各自独立编码/hash。详细P/G、metadata及SQL见 cases.json、JFR和mysql-statements.jsonl。

最终业务每个 query 单独起算，未合并预算，计时起点没有后移；只以必要首受理和合法finish建立第二受理种子。

|query|已返回/被拒预付|reads / B|scratch峰值/末值|结果；退出时剩余微秒|
|---|---|---|---|---|
|submit-0|54681 ops；无拒绝|84 / 22369|510483 / 65536|COMMITTED；71874|
|finish-0|55186 ops；无拒绝|76 / 22540|456292 / 65536|COMMITTED、合法SUCCEEDED/quiescent；1143768|
|submit-1|65498已返回；下一64未返回，请求累计65562|64 / 18603|344747 / 65536|ROLLED_BACK；1391166|

第二受理首错 `SESSION_HISTORY_LIMIT`：`TaskAdmissionMapper.lockAcceptedView` 的 `request_digest` 列，在 `SessionQueryContext$Result.columnMeasured:430` 预付64时拒绝；由 `SessionAcceptedTaskRows.load:83` 到达。计数器65562包含未返回的64，不能写成65562已完成，也不能拿26当完整缺口。

最终第二受理完整G `26800 ops / 4 reads / 4899 B`，P `16608 / 15 / 5513`；B到拒绝 `22154 / 45 / 8191`。对照R30 G `28694 / 5 / 5792`，重复投影/单独Stored读取实际减少，但整段业务仍未提交，不能用局部差值推算尾部足够。不同finish种子/不同query的总量不作为同输入净收益。

第二前后22表逐表快照全部相等；事务明确ROLLED_BACK。私有MySQL容器、WSL私有classpath根目录、Java子进程均已结束/移除，无清理错误；runner59.40秒，原始日志/首错栈/每callsite requested/returned CSV及cleanup.json均归档。脚本exit0表示成功保存检查点，不代表第二受理成功。

未执行提交后独立second-eventHistory；第二剩余view列/metadata/账户终算/current live-fence/seal/beforeCommit/commit和后续独立query费用仍未知。一次尝试后未继续修改生产或追加业务重试；由原PM按R31作下一裁定。

## 保留门与交付

125/126继续in_progress，127done；PR24仍draft，未合并master。A12、九节点一笔真实2秒、M2同response三trace/全出口、独立DB新fence冻结槽唯一消费、网络R1/R2与ACK UNKNOWN、A01–A16原门仍未被本轮替代；typed保持test-only、ACK不挂A14。无CI运行/等待、部署、真实Key、新付费、生产/共享DB、网络/daemon变更或强推。原备份、pycache和失败证据保持。

交付附件含最终完整JAR、manifest、源码/编译类包、必要验证证据。后续普通追加提交仅保存本文档；准确交付HEAD/tree/parent、普通push及远端独立读回另见delivery.json。代码回退使用普通追加提交恢复旧读取，不reset/clean。
