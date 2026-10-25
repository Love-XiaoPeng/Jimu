本片仅迁 `JdbcLedger.questionResume`，parent `0b3a5a25517c87ad11926e2d9848d34fd2b9b6ef`／tree `89f204417618e6c0fbe87ffbe1c0a0eb902b8780`，沿原A分支与PR22草稿。准确新SHA/tree绑定于manifest及Git原对象。仅B2部分检查点，非独审QA／总验收。

受测原JAR SHA256 `df245e310ffb24c34efc894e81fde5a00fd8703f06fd96bc60e2fb9a78bb5df1`，44,607,143字节／1,637生产entry。两轮实际执行均使用这一首轮原JAR。第二轮候选仅核全部生产entry相同，不替换受测原品。直接读准确旧5663d8f3与新原JAR全部1631／1637entry：1625既有字节同／6新增；5既有嵌套class解析仅LineNumberTable变化。其他626生产Java／424既有资源及冻结文件、相邻Bean/bootstrap/POM和schema22/V1–V22不改。

## 完整Service、惰性与接线

纯Java接口／Service／impl＋类型化单项port，Service依次tenant→task→原requestId参数→一次UPDATE→原int恰1判断→fact字符串拼接→原event。端口构造只存ledger/task引用，不读成员、环境、会话或时钟；NULL task和字段String cast在原s到达处失败，所有实参后才取同DS/UoW会话与原queryContext。没有questionTask/flag/pin/状态前读、额外guard、catch、事务、fallback或重试。

只有count==1继续；0／负数／>1仍409/QUESTION_BINDING_MISMATCH，无event预读。成功后原`"question-resume:" + requestId`（合成NULL为字面null）及task.resumable/null参数，仅调用一次ledger.event(task,...)。adapter的final task引用直接传回原event，无Map复制或修改；这项实例传递由源码直连核证，运行JSON不宣称证明Java对象身份。Service各到达点原RuntimeException/Error/TaskFailure实例传播；更新异常阻断event，event异常无catch/补偿，事务归原caller。

原answer和continueQuestion完整方法及其他Ledger方法词法不变。answer仍原权限/锁/校验/Storage及问题UPDATE后，foreground独有AnswerBatch→questionToolResult→本片→question.answered/receipt；原幂等分支不新增本片。continueQuestion仍provider三键→pin/pending→questionToolResult→OPEN/revision条件UPDATE→先s(q,id)实参→本片→question.continued。没有搬动原TaskFailure/AdmissionResult边界或业务编排。

event内部fact去重、预算、seq/revision/数据库时间、状态读取与写事件原样；本SQL不直接修改revision/updated_at/ready_at/lease/deadline/continuation。所有原scope/fence/完整tenant与权限/锁序、queryView累计/绝对两秒/调用者deadline、共享commitFence13处及session完成边界保留。

## SQL、绑定与差异

先核现有task/question Mapper，无可复用同一UPDATE；新增精确QuestionResumeMapper.resume参数化XML：

```sql
UPDATE task SET state='RESUME_READY',waiting_question_id=NULL WHERE tenant_id=? AND id=? AND state='WAITING_USER' AND waiting_question_id=?
```

除?换参数表达式外，实际SQL实串相同，三String参数tenant→task→request原序；无SELECT、其他赋值、锁、JOIN、LIMIT、额外CAS/事务或双写。Service不读取task状态，无Java预比较。真实非NULL绑定三项均setString；旧executeUpdate返回计数，新execute后getUpdateCount，差异逐项记录，不称JDBC API全部相同。NULL/cast字段与NULL request仅合成，未声称真实NULL命中／NULL wire类型已验；真实PK不能多行，负数/>1仅单测。

迁1旧JDBC点，SQL仍执行／消除0。库存落地160静态／29动态／541bind；task表及整表迁移仍OPEN，不预报SQL收益。

## 验证及原失败

先核准确parent下UserQuestionContractTest、UserQuestionIT、tools/user_question_verify.py的前台答复/到期/重复受理源码，旧来源不当当前head新验收。run1：4Service＋3port＋2追踪＋1架构过，SpotBugs bugs/errors0；原IT/verify1。两侧direct匹配与错键完成，但foreground answer均先因显式夹具漏TOOL RESERVED行而原RESERVATION_STATE，未到本片，不算成功。完整主错栈与cause/SQL错误链打印机制、SQL、rollback/cleanup、compile和实际源码/class及原JAR封存后才改夹具。

只补合法显式TOOL RESERVED行/工具累计、PAUSED attempt及清除活动lease字段，并补错键拒绝后的独立状态证明；产品不变。run2复用同受测原JAR，2追踪＋1架构＋1真实IT过，verify0/BUILD SUCCESS、SpotBugs bugs/errors0，无skip；未重跑未变7单测。第二轮四对真实旧新：

- direct同等待键：恰1，WAITING_USER→RESUME_READY/等待键NULL，再原task.resumable；direct不同键：count0、原409，独立证明仍WAITING_USER/原等待键，无resumable事件，非真实DELETE或公开用户输入。
- 公开owner foreground answer：完整bucket/identity/owner/锁/access与q锁，原问题ANSWERED UPDATE→invocation SUCCEEDED/TOOL SETTLED→本片UPDATE→task.resumable→question.answered及TOOL_RESULT receipt；q为CHECKPOINTED。
- 公开owner timed到期answer（expectedRevision3）：先原questionToolResult→OPEN/revision条件UPDATE到CONTINUED→本片→task.resumable→question.continued，再原late answer受理/USER_MESSAGE receipt与PENDING。没有把到期续问说成foreground answer。

完整actor/installation锁、每侧Lease/attempt/fence关系、UPDATE三键/实际计数、事件fact/seq/type/status与revision/数据库时间分证。两侧所记录prepared SQL顺序相同；普通Statement未采集。生成attempt/step/answer ID、时间与Map.of顺序各存原串，不泛化全部bind/wire一致。原串/SHA256/UTF8长度分别留存。

HTTP仅新侧受理准备，私有paid=false MySQL8。registerWorker/claim仅构造Lease；原prepare/dispatch/modelResponded为setup响应/调用夹具，无provider/worker循环/新工具。显式FK/CHECK合法timed OPEN问题、WAITING_USER invocation、TOOL reservation和等待task，pin/binding/mode/request均0.2.0相容；非openQuestion/工具/真实问答事件生成事实。第二轮用SQL置PAUSED attempt及fence+1/清空current_attempt/worker/lease；Lease证明指向setup产生的本侧PAUSED attempt，不冒称仍有效执行租约。私有task/问题时间窗单列，不调产品query/socket/network/事务设置。

共24份原夹具/成功/拒绝证明、14份setup Lease证明；14笔tenant-a21表外层捕获事实恢复和两轮清理已核，失败样本恢复也保留。worker_process未纳入，非整库/持久提交。无真实DELETE/SQL故障/自然08S01/UNKNOWN恢复；event失败回滚传播仅合成及原源码边界，不冒称真实注入。

准备期路径/PowerShell读取错误、源码审计抢在capture完成前误读旧索引的断言错误、交付生成器引号语法错误均非产品测试失败；已保留独立可得stderr及原脚本，未独立封存的其他准备完整stderr仍OPEN。首轮真实RESERVATION_STATE失败不擦除；没有恢复重放UNKNOWN或原槽自动retry。

## 剩余与交付边界

run `01a1751e-8a36-7214-b3f8-c3a55afed0db`，当前唯一PM规则 `01a1751f-47e2-73b0-8d50-7add30ec87aa` 精确绑定completed/failed/cancelled＋7200秒，旧规则不恢复。原A唯一集成写入；准确SHA/tree、执行源码/编译件、受测原JAR分片、各轮索引/日志/失败/清理、离线核验器随包。

下一具体候选按当前源码为questionPause（两条原条件UPDATE及原caller边界），仅待PM核准确源码续派，本轮不混迁。B2/B0–B5未完，60保持in_progress/PR22 draft；B2完整才核B3，61/62仍backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read/独立后记、其他真实缺行、自然08S01/709ms/冷路径及全部历史失败缺件OPEN。63/64/66/70不重接，72固定旧head不追新head。无CI/合master/部署/真实Key付费/共享服务数据/daemon代理FRP重置，T48和新功能暂停。
