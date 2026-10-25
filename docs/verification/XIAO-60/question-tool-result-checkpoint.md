本片仅迁JdbcLedger.questionToolResult，parent `1ecbe193bc11473385daaad670f8d5ea2c1d1f35`／tree `32db2afba8346b5d6251cfd893bb75ecfc45aa95`。原A唯一集成写入，原分支agent/agent/7aeccb14d7a9／PR22草稿。准确新SHA/tree由manifest及Git原对象绑定。仅B2部分检查点，开发自测，不是独审QA或总验收。

受测原JAR SHA256 `88dfec6d662e9c73f92ba91e73e0c7c8caf338f5769cd230858b3703a4dc89d0`，44,618,523字节／1,649生产entry。准确旧d2cab9be与新原JAR全部1643／1649生产entry直接读取：1637既有原字节同／6新增；5既有嵌套class独立解析仅LineNumberTable变化。其余636生产Java／426既有资源及已迁Service、Bean/bootstrap/POM、冻结文件、schema22/V1–V22保持。

## 完整资格与惰性次序

纯Java接口／Service／impl和独立类型化单项port。Service先task.tenant_id→q.invocation_id两个String局部且各读一次，随后一次原tools(task)→固定ask_user_question pin→version→原questions.output。codec通过后才output原参→一次Payloads.hash(output)→原tenant局部→此时task.id→原invocation局部→第一次UPDATE和恰1判断→复用两局部第二次UPDATE和恰1判断→原tool:invocation拼接／event。端口构造只保存原Ledger/task/q引用，不读取字段、会话、pin、codec、环境或时钟。

NULL task/q、String cast和成员首错在原到达位置；不从q.task_id或状态推导，不缓存全行。两个局部可NULL，原task.id/hash首错保持；NULL invocation的tool:null仅合成可达端口测试，不冒称真实NULL UPDATE命中。output实参进入方法前已求值。没有新questionTask/flag/身份/模型/PURGED/binding或状态前读、guard/catch/fallback/retry/局部事务。

原历史QuestionContracts.output不改：先UTF8 bytes（>16384原TOOL_OUTPUT_TOO_LARGE），再contract(version).out()取得接收者，再原Payloads.read/JsonNode及schema。unknown/NULL版本和坏JSON首错仍PLUGIN_PIN_MISMATCH；超字节先于unknown版本，NULL output先原bytes NPE。必要单测补0.1.0/0.2.0有效output、unknown/NULL版本与坏JSON、超字节相遇、正确版本坏JSON/schema、NULL output；既有UserQuestionContractTest矩阵准确来源只引用，不重跑为当前验收。hash和SQL不在codec之前，codec结果不替代原串，不trim/重序列化/升级版本。

各UPDATE仅恰1继续；0、负数、>1分别原409/INVOCATION_STATE或RESERVATION_STATE。第一失败不执行reservation/event，第二失败不补偿首写。原SQL/RuntimeException/Error/TaskFailure实例传播；event异常不吞错，事务仍归caller。event桥接直接使用存下的同一task Map引用，构造反射引用核证及源码直连；运行JSON只证明已存值，不证明对象身份。无新增Map复制/修改、预算计算、seq或事件payload。

## 原SQL与两处接线

核现有invocation/reservation/question Mapper后新增精确QuestionToolResultMapper/XML，两条除?参数化替换外实际实串相同：

```sql
UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND task_id=? AND id=? AND state='WAITING_USER'
UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=? AND category='TOOL' AND state='RESERVED'
```

第一条五String绑定output/digest/tenant/task/invocation，第二条两String绑定原tenant/invocation。每条全实参后才取原同DS/UoW会话及显式/nullable queryContext。第一WHERE不添plugin/step/attempt/slot/fence，第二不添task_id/dispatch/amount；只原赋值和数据库CURRENT_TIMESTAMP(6)，不Java时钟。无前SELECT/JOIN/锁/LIMIT/批量/双写/额外CAS或第二事务。

真实两侧七项非NULLString均setString，原executeUpdate→新MP execute/getUpdateCount另列。NULL/类型及负/>1仅单测合成，不称本片NULL wire类型实际已验或全部JDBC API相同。普通Statement未采集。本片迁2旧JDBC点，SQL仍两条/消除0，库存156静态/29动态/526bind，invocation/reservation表OPEN。

answer与continueQuestion完整方法、其他Ledger方法词法不变。answer仍原可见/q锁/幂等/过期terminal/flag/revision/input/canonicalAnswers/storage和ANSWERED UPDATE后，仅foreground的原AnswerBatch→Payloads.json→本片→resume→question.answered/receipt；原catch范围不扩大，幂等/late不新增调用。continueQuestion仍provider三键查询→原tools/pin→pending（其内部output校验）→本片（原tools/output校验再次发生）→原OPEN/revision UPDATE→resume→question.continued；不合并重复tools/codec，不迁这些caller业务。openOrResult不直接调用本片，本轮未执行工具。

## 验证、实际夹具与恢复

准确parent下UserQuestionContractTest、QuestionResumeScenario、UserQuestionIT/user_question_verify.py及结算覆盖已读；旧head事实仅引用。一次完整定向verify0/BUILD SUCCESS：5Service＋4port＋2追踪＋1架构＋1真实IT通过，无skip，SpotBugs bugs/errors0。103项封存实际source/class/编译回执/完整日志，两driver0，四对完成，无本片正式产品/测试/夹具意外失败。所有原主错cause/suppressed/nextSQLException/SQLState/vendor打印及rollback/cleanup/落盘次错分存机制保留。封存后离线分析器首次误期第一结果有raw，实际首个负样本raw为NULL，原脚本/TypeError独立保留；仅改分析器选择首个有raw结果，随后纯离线重算四对通过，未改产品/执行助手/原证据、未重跑Java或数据库。准备只读路径/PowerShell命令错误及未独立封存完整stderr不包装为正式测试失败；历史缺件仍OPEN。

私有paid=false旧新四对：

- direct原invocation参数换准确不存在UUID：codec通过，第一count0原INVOCATION_STATE，仅一SQL，无reservation/event，原存量invocation/reservation/task/event保持；不是实际DELETE或公开output输入。
- direct原invocation存在、reservation预设合法SETTLED：第一count1、第二count0原RESERVATION_STATE；独立拒绝证明存下invocation已SUCCEEDED和原output/digest，task/event和reservation未变，然后原外层rollback恢复，不补偿首写。
- 公开foreground answer：完整owner可见/actor/installation/q锁与资格，原ANSWERED UPDATE→本片invocation count1/reservation count1→tool.completed→resume/task.resumable→question.answered/TOOL_RESULT receipt。
- 公开timed到期answer入口触发continue：原pending输出后本片两count1→tool.completed→原OPEN/revision条件更新至CONTINUED→resume/task.resumable→question.continued，再原late answer/USER_MESSAGE receipt。没有把续问pending output说成用户直接提交任意tool output。

完整prepared SQL顺序、五加两绑定/计数、原tool.completed fact/seq/type/status及后续事件位置核对。原output串/SHA256/UTF8字节长度分存，与本侧invocation的已存output_snapshot/output_digest匹配；生成ID/时间/Map.of只比已证字段与本侧关系。16份原夹具/成功/拒绝证明、8份setup Lease、8笔tenant-a21表外层捕获事实恢复和一清理。worker_process未纳入，不称整库或持久提交。

HTTP仅新侧受理准备；registerWorker/claim构造Lease，原prepared/dispatch/modelResponded写入响应/intent fixture，无provider/worker循环/新工具。显式FK/CHECK合法timed0.2.0 OPEN问题/WAITING_USER invocation/TOOL RESERVED/step，与pin/binding/mode/request相容；公开答案沿原schema形成output。task WAITING_USER，SQL设置PAUSED attempt、fence+1并清活动lease字段；Lease指向本侧setup PAUSED attempt，不冒称有效执行租约。原task/问题时间窗设置单列，不调产品query/socket/network/事务设置。direct第二拒绝只预设合法SETTLED reservation状态，证明条件计数，不声称由真实工具结算。

无DELETE/非法schema行/SQL故障/自然08S01/UNKNOWN恢复，未重跑整问答/retention/response六组/模型/session矩阵。仅开发自测及可离线重算证据，非独审QA/总验收/整库/持久提交/成功工具read/FAILED-read/独立后记。

## 剩余与门禁

run `01a17559-8118-7a10-a262-58128784ec5b`，唯一PM规则 `01a1755a-0a60-73de-ab30-7ca43ecd6a89` 绑定本run completed/failed/cancelled＋7200秒，旧规则不恢复。准确源码Git对象、同受测原JAR分片、实际source/class/编译/日志/清理及离线核验器随包。

下一具体候选按本源码为continueQuestion完整provider读取→历史pending→既有ToolResult→OPEN/revision条件更新→Resume→continued事件资格；待PM核准确源码续派，本轮不混迁。B2/B0–B5未完，60继续in_progress/PR22草稿；B2完整才核B3，61/62仍backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read/独立后记、其他真实缺行、自然08S01/709ms/冷路径与全部历史失败缺件OPEN。

原tenant/whole-scope/fence/权限/锁序/同DS/UoW、queryView起点/累计/绝对两秒/caller末尾deadline、baseAccess四独立资格/question专属else/MCP、access原catch/403后记、特殊拒绝结算、commitFence13处/session完成边界保持。todoProjection完整21列/case/NULL、冻结值类型/字段序/字节摘要/数据库时间保护。63/64/66/70不重接，72固定旧head不追新head。无CI/合master/部署/真实Key付费/共享服务维护/daemon代理FRP重置，T48及新功能暂停。
