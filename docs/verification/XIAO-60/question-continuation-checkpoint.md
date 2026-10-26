本片仅迁JdbcLedger.continueQuestion，parent `975933b48d5809f5cbc14fa883e28bb97b1f82ea`／tree `28d81c64d74e1cff0d39488b8ec7ce940b92ffa4`。原A唯一集成写入，原分支agent/agent/7aeccb14d7a9／PR22草稿。准确新SHA/tree由manifest与Git原对象绑定。仅B2部分检查点，开发自测，非独审QA或总验收。

受测原JAR SHA256 `29d39520cd4f411c2746d9c191599657f33e348ec5cd08dfaf3cb131a505df5e`，44,626,116字节／1,655生产entry。准确旧88dfec6d与新原JAR全部1649／1655entry直接读取：1643既有原字节同／6新增；五既有嵌套class仅LineNumberTable，另存独立class属性解析回执。其余641生产Java／427既有资源、所有已迁Service、相邻Bean/bootstrap/POM、冻结文件与schema22/V1–V22保持。

## 完整Service与惰性端口

纯Java接口／Service／impl、泛型行/pin和类型化单项port。构造仅保存原Ledger/task/q和ToolResult、Resume单项委托引用，不取字段、会话、pin、时钟或环境。Service依次task.tenant_id→q.invocation_id→task.id三个String cast→原列表查询；空列表原404/NOT_FOUND，任意非空只getFirst，不严格size1、不预解码所有行。找到首行后才原tools(task)完整链→ask_user_question pin→version→provider_id String cast→原questions.pending。pin NULL先version NPE，未到provider/codec；NULL task/q与非法类型仍在原到达处失败，不增guard/catch/fallback/事务。

pending返回串原实例交既有questionToolResult(task,q,output)，此处完整复用其tools/pin/version/output校验/hash/两UPDATE/tool.completed；pending内output校验与ToolResult内再次校验、两次tools不合并。成功后第二次task.tenant_id→首次q.id→原Number cast/longValue revision→条件UPDATE，仅恰1继续；0/负/>1仍409/QUESTION_REVISION_CONFLICT。随后独立第二次q.id→既有questionResume；其成功后独立第三次q.id→原question-continued:拼接→原event。所有RuntimeException/Error/TaskFailure原实例传播，首错阻断后续；不补偿先前ToolResult写入/事件、不重试，原caller决定回滚。

原task/q引用通过单项Consumer桥接原私有ToolResult与Resume，无Ledger↔Service递归或整项continue回调，无Map复制/修改。源码直连/构造反射核引用，真实JSON只证明已存值。NULL/类型/多行/变化回调/负数/>1仅合成，不冒称schema真实可表达。

原QuestionContracts.pending完整复用：AnswerBatch(null,true,callId,catalog.pendingNotice)→原Payloads.json→内部output校验→原串；未升级pin、改通知或JSON协议。必要单测确认原0.1.0的pending遭原schema IllegalArgumentException拒绝，0.2.0输出与原codec相同、原String实例传入单项回调。UserQuestionContractTest/UserQuestionIT/user_question_verify.py及准确parent QuestionToolResultScenario的due-continue为已核历史来源，不当本head新验收，不重跑旧完整codec矩阵。

## 单列MP与原条件UPDATE

先核既有QuestionInvocationReadMapper，复用其真正MP selectMaps，QueryWrapper显式只选provider_id，未改既有实体/默认投影/其他Mapper门禁：

```sql
SELECT provider_id FROM invocation WHERE (tenant_id = ? AND id = ? AND task_id = ?)
UPDATE user_question SET phase='CONTINUED',revision=revision+1 WHERE tenant_id=? AND id=? AND phase='OPEN' AND revision=?
```

首查询与旧SQL仅WHERE括号/空格差异，无别名/列集变化。第一组三String为task tenant/q invocation/task id；第二组String,String,long为再次tenant/q id/Number.longValue。各组全参数后才原queryContext及同DS/UoW会话，不增加SELECT/COUNT/锁/排序/LIMIT/过滤或事务。UPDATE仅原phase和数据库revision+1，不增task_id等谓词或updated_at/answer/delivery赋值。真实三查询String与UPDATE两String均setString；revision旧setObject→新setLong。查询旧executeQuery/getObject(1)→新execute/getResultSet/getString("provider_id")，非空同String read_1、空行无getter；UPDATE旧executeUpdate→新execute/getUpdateCount。NULL wire未新验。sql-comparison与原日志逐项保存，不称全部JDBC API相同；普通Statement不在采集数组。

迁2旧JDBC点，SQL仍两条/消除0；落地库存154静态／29动态／520bind，invocation/user_question等表仍OPEN。answer和maintainQuestions完整方法及其他Ledger方法词法未改：answer的due条件→本片→原q重读→late answer位置、catch范围不动；维护仍候选→逐项tx/bucket/locked/access与原catch→原due OPEN锁行顺序循环本片→settleStoppedWriters。维护本轮只核准确源码和历史覆盖，未新运行maintainQuestions或daemon。

## 逐轮验证及原失败

run1封存64项；run2封存35项；run3封存98项，每次下一执行前封存实际source/class/编译回执和完整日志。run1：5Service、4port通过，新增历史pending单测误期0.1.0成功而原schema正确拒绝；另2追踪/1架构通过，verify1，未到IT/package/SpotBugs。run2：只修该测试预期但漏assertTrue静态导入，testCompile失败/verify1；未执行单测/IT/package/SpotBugs，封存中的test class属于先前编译件，不当本轮成功编译。run3：仅以既有assertEquals表达空列表断言，产品未改；修正单测1＋追踪2＋架构1＋真实IT1通过/verify0/BUILD SUCCESS，SpotBugs bugs/errors0。run1已过9个业务单测未重跑；两轮未打新包，不把旧target JAR归为本片受测品，run3才生成并实测本片原JAR。

三个有界旧新私有paid=false样本，两driver0：

- direct调用Map仅把invocation_id换准确不存在UUID：真实三键空查，404/NOT_FOUND，仅该一SQL，无tools/后续SQL；未改存储FK、非DELETE或公开输入。
- direct合法OPEN revision2问题配调用快照revision1：原pending→ToolResult两count1和tool.completed已发生，本片phase count0/QUESTION_REVISION_CONFLICT；原拒绝前证明存下SUCCEEDED invocation/output摘要、SETTLED TOOL reservation/actual1及唯一新tool.completed，无Resume/continued，再原外层rollback恢复。不是公开任意revision输入。
- 公开timed到期answer：完整owner/actor/installation/q锁链→provider三键→原pending→ToolResult两UPDATE/tool.completed→phase count1→Resume/task.resumable→question.continued→原q重读/late answer→USER_MESSAGE receipt。phase曾CONTINUED，最终问题ANSWERED，按SQL顺序与原事件分证。

12份原夹具/成功/拒绝证明、6份setup Lease、6笔tenant-a21表外层捕获事实恢复及1清理。完整prepared SQL顺序/三加三bind/计数、原output串/SHA256/UTF8长度与本侧已存output_snapshot/digest匹配。生成ID/时间/Map.of仅比已证字段与本侧关系。主错cause/suppressed/nextSQLException/SQLState/vendor与rollback/cleanup/落盘次错分存机制保留。两次测试准备失败完整保留；准备只读路径/PowerShell探测stderr缺件仍OPEN，不包装为已完整封存。

HTTP仅新侧setup，registerWorker/claim及原prepare/dispatch/modelResponded构造已存fixture响应/intent，未执行provider或工具。显式FK/CHECK合法timed0.2.0 OPEN问题/WAITING_USER invocation/TOOL RESERVED/step，与pin/binding/mode/request/时间窗相容，非openOrResult/工具/provider/真实问答事件生成。task WAITING_USER，setup attempt PAUSED、fence+1并清活动字段；Lease指向PAUSED attempt，非有效执行租约。私有合法时间设置单列，未调产品query/socket/network/事务配置。worker_process未纳入恢复范围，不称整库/持久提交。无worker循环/新工具/付费/DELETE/SQL故障/自然08S01/UNKNOWN恢复。

## 剩余与门禁

run `01a17570-65c2-71a4-b470-04301fc2ef73`，原唯一PM规则 `01a17571-1ca7-7059-a8fd-33e124b31ac4`绑定本run completed/failed/cancelled＋7200秒，旧规则不恢复。准确SHA/tree、同受测原JAR分片、实际源码/class/编译/原失败/清理及离线核验器随包。

下一具体候选为仍未迁的questionNow：单条原SELECT CURRENT_TIMESTAMP(6)→LocalDateTime→UTC Instant及原两个caller到达顺序，待PM按准确源码核定，不在本片实施。其他answer/openOrResult/maintainQuestions编排仍待后续独立切片。B2/B0–B5未完，60保持in_progress/PR22草稿；B2完整才核B3，61/62仍backlog。35表＋receipt、core最后beforeCommit、scope成功工具read、持久FAILED-read/独立后记、其他真实缺行、自然08S01/709ms/冷路径与全部历史失败缺件OPEN。

保护tenant/whole-scope/fence/actor/installation/原锁序/同DS/UoW、queryView起点/累计实计/绝对两秒/caller末尾deadline；baseAccess四独立资格/question专属else/MCP、access原catch/403后记/REQUIRES_NEW、特殊拒绝结算/事务外outcome、commitFence13处/session完成边界保持。todoProjection完整21列/case/NULL、冻结值类型/字段序/字节摘要/数据库时间及schema22保持。63/64/66/70不重接，72固定旧head不追新head；无CI/合master/部署/真实Key付费/共享维护/daemon代理FRP重置，T48及新功能暂停。仅开发自测，不称独审QA/总验收/成功工具read/FAILED-read/独立后记。
