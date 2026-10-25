本片仅迁 `JdbcLedger.questionPause`，parent `a23dd158b58977dfabf77b1c5f471a991aed72d9`／tree `5e51355e3dabbae4acef5bdeba2cfcbebdd49cf8`。原A分支 `agent/agent/7aeccb14d7a9`／PR22草稿，准确新SHA/tree绑定于manifest和Git原对象。仅B2部分检查点；开发自测，非独审QA／总验收。

受测原JAR SHA256 `d2cab9be6e11d9bd295d7335ecf8125783d1f4f4b7aba41ebe10d19f72bc3837`，44,612,143字节／1,643生产entry。两轮实际执行均使用这一首轮原品；第二轮打包候选只核全部生产entry相同，不替换受测原品。直接读取准确旧df245e31与新原JAR全部1637／1643生产entry：1631既有原字节同／6新增；5既有嵌套class独立解析仅LineNumberTable变化。其余631生产Java／425既有资源、已迁Service、相邻Bean/bootstrap/POM、冻结文件及schema22/V1–V22不改。

## 完整Service与两处接线

纯Java接口／Service／impl＋类型化惰性单项port。端口仅存Ledger与Lease引用；原task参数完全未用，不读、复制、验证或修改，NULL和poison Map实际direct样本均保留。构造不解引用Lease／会话／环境／时钟。原state/continuation在方法调用前已经求值，保持普通String参数，不改成更晚回调。

Service按第一次tenant→attemptId→fence→单次attempt UPDATE→原int恰1判断，再state/continuation→第二次tenant→taskId→第二次fence→单次task UPDATE→原int恰1判断。每条全部参数后才取同DS/UoW的会话及原queryContext。没有Lease快照或缓存第一次tenant/fence，没有guard、question谓词、前置SELECT、catch、fallback、重试、局部事务、事件或额外返回。

各阶段0／负数／>1均原409/FENCE_REJECTED，仅1继续。首条失败不读第二条成员／会话；第二条失败不补偿首条，原RuntimeException/Error/TaskFailure实例传播。NULL Lease在首次tenant成员到达才NPE。真实Lease为既有record，变化回调／负数／多行计数／NULL state等仅合成。原task Map不用于推导任何参数。

openOrResult和JdbcExecutionRecords两处caller及其他Ledger方法词法未改。openOrResult仍仅新PENDING分支在invocation WAITING_USER条件更新后本片(WAITING_USER,null)，随后question.created／task.waiting_user；原SUCCEEDED重取不新增暂停。该工具入口只读准确parent源码及历史覆盖，本轮不执行新工具。Admission.pauseQuestions仍本片(RESUME_READY,step)后才由caller发question-final-resume事件；ExecutionService.finish的admission→lockAdmission→validateSummary→pendingQuestions→latestStepId→pauseQuestions→true保持，后续结算／终态分支不迁。事件未移入Service。

公开ExecutionLedger.finish是void：内部ExecutionService.finish返回true后，原wrapper抛QuestionSuspended。本轮实际捕获该原公开暂停信号，不称公开API返回true／任务SUCCEEDED／持久提交。内部true分支由未变源码与实际到达证明。

## 两条SQL、绑定与实际JDBC差异

核现有task/question Mapper后新增精确QuestionPauseMapper两方法/XML；无同一两条条件更新可直接复用。除?参数化替换外，加载后的原SQL实串相同：

```sql
UPDATE task_attempt SET state='PAUSED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND fence=? AND state='ACTIVE'
UPDATE task SET state=?,question_continuation_step_id=?,fence=fence+1,current_attempt_id=NULL,worker_id=NULL,lease_until=NULL WHERE tenant_id=? AND id=? AND fence=?
```

第一条String,String,long三bind；第二条String,String,String,String,long五bind，tenant/fence分别再次到达。第一条不加task/worker/current_attempt条件，第二条不加RUNNING/current_attempt/worker/lease等条件。没有JOIN／LIMIT／锁／额外CAS／双写／批量／第二事务。fence+1及ended_at=CURRENT_TIMESTAMP(6)保留数据库表达式，不Java计算／时钟。第二条不额外设置revision、updated_at、ready_at、waiting_question_id或deadline。

真实String仍setString；long fence原setObject(java.lang.Long)→新setLong；真实NULL continuation原setNull(JDBC type=0)→新setNull(type=1111)。执行原executeUpdate→MP execute/getUpdateCount。原SQL／值／计数一致，API和NULL JDBC类型差异分列，不称全部JDBC调用或wire相同。普通Statement未采集；负数/>1、非法类型、NULL state与溢出不制造真实schema非法行。

迁2旧JDBC点，SQL仍两条／消除0。库存落地158静态／29动态／533bind；task/task_attempt表仍OPEN，不预报关表／SQL收益。

## 验证、原失败与封存

先核准确parent下UserQuestionContractTest／UserQuestionIT、user_question_verify.py及Execution覆盖；仅引用原来源，不当本head新验收。run1：5Service＋3port＋2追踪＋1架构通过、无skip，SpotBugs bugs/errors0；两个实际driver均0，四对真实执行及夹具均完成。原IT/verify1唯一失败是离线比较器误期公开finish revision只+1；实际两侧均11→13，原fenced commitLiveFence在本片前+1、原caller event在本片后+1。不是产品／夹具执行失败。

run1的98项实际source/class／编译件／完整日志／cleanup先封存，再只修比较器公开revision期望。原失败／脚本／索引不改。纯离线重算原四对通过，不称重跑真实执行或原verify0。run2复用首轮受测原JAR，仅public-finish一对及2追踪＋1架构＋1真实IT通过，verify0/BUILD SUCCESS、SpotBugs bugs/errors0，无skip；未重跑未变8单测及前三对。各轮主错cause/suppressed/nextSQLException/SQLState/vendor打印机制、rollback／cleanup／证据落盘次错分存。

四种不同真实旧新样本（最终共5对完成，公开对第二轮重复一次）：

- direct匹配ACTIVE attempt/task fence：两条count1，attempt PAUSED及数据库ended_at，task WAITING_USER/fence+1/三个lease字段NULL；continuation真实NULL，waiting键等未赋值字段保持。无event／额外revision或时间写入。
- direct仅attempt fence错：首条count0、仅一SQL、原409，第二条未执行。错键不是DELETE；调用task为NULL。
- direct仅task fence错：首条count1且attempt已PAUSED，第二条count0原409；拒绝后的独立证明保留首写发生，随后原外层rollback恢复，无补偿／局部提交。
- 公开finish：合法非空PENDING消息，真实admission/fenced链与完整actor/installation锁，questionMessages联表及input/answer资格→原latest step→两UPDATE→caller question-final-resume:<本侧step>/task.resumable。原task revision总+2，fenced与event分列；task RESUME_READY/continuation本侧step/fence+1及lease清NULL，attempt PAUSED。原事件fact/seq/type/status、revision/数据库时间各存。

公开fixture实际调用前Lease五键与ACTIVE attempt、task fence/current_attempt/worker/lease_until匹配；没有把PAUSED setup attempt当有效finish Lease。HTTP仅新侧受理准备，registerWorker/claim构造Lease；原prepared/dispatch/modelResponded写入setup响应/调用，无provider或新工具。显式FK/CHECK合法timed0.2.0 CONTINUED问题、SUCCEEDED invocation／SETTLED TOOL与计量配套，pin/binding/mode/request/answer摘要相容。原late answer setup形成ANSWERED/PENDING且保留ACTIVE Lease；不是openOrResult／工具／provider／真实问答事件生成事实。准备阶段一条旧reservation SETTLED脚手架UPDATE匹配0，随后才显式INSERT合法SETTLED行，不声称该0行语句执行真实工具结算。

两侧所记录prepared SQL顺序／原完整bind和更新计数核对；完整tenant/actor/installation、每侧Lease五键、联表两bind及原commitLiveFence五bind各核本侧关系，第三键是revision，不是worker。生成step/attempt/event ID、时间／Map.of只比已证范围。20份原夹具/成功/拒绝证明、10份Lease证明，原串/SHA256/UTF8长度分别保存；10笔tenant-a21表外层捕获事实恢复及两轮清理。worker_process不在范围，非整库／持久提交／成功工具read／FAILED-read／独立后记。

准备期缺失只读路径及PowerShell花括号参数语法错误未改产品；可得工具错误保留，未独立封存的完整准备stderr及所有历史缺件OPEN。无真实DELETE／SQL故障／自然08S01／UNKNOWN恢复；不恢复重放UNKNOWN或原槽自动retry。

## 剩余与边界

run `01a1753d-f765-780f-a585-757bdaef84b1`，唯一PM规则 `01a1753e-8322-7ed0-b9c6-ee9d820b4303` 精确绑定completed/failed/cancelled＋7200秒，旧规则不恢复。每轮原受测JAR／实际source/class／编译回执／索引／失败与清理、纯离线比较器及独立重算器随包。

下一具体候选按当前准确源码为questionToolResult：原历史output codec→invocation条件UPDATE→TOOL reservation条件UPDATE→原tool.completed事件及原caller边界；待PM核源码续派，本轮不混迁。B2/B0–B5未完，60保持in_progress/PR22 draft；B2完整才核B3，61/62 backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径及全部历史失败缺件OPEN。

所有原tenant/whole-scope/fence/权限、锁序、同DS/UoW、queryView起点/累计/绝对两秒及caller末尾deadline、access原catch/403后记、特殊拒绝结算、共享commitFence13处和session完成边界不改。todoProjection完整21列/case/NULL、冻结值类型/字段序/字节摘要/数据库时间保持。63/64/66/70不重接，72固定旧head不追新head。无CI／合master／部署／真实Key付费／共享服务数据／daemon代理FRP重置，T48及新功能暂停。
