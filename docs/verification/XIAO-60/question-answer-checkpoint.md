本片仅迁 `JdbcLedger.answer` 完整受理，parent `d41681e821dc8908cf374b2e5afa702dcc02b64c`／tree `7803aa8441a8afba3926d5b55140f49b2dd210c7`，沿原分支 `agent/agent/7aeccb14d7a9`／PR22 draft。准确新SHA/tree见交付manifest及Git原对象。本报告随源码提交，不自指未生成SHA。仅B2部分检查点，60仍in_progress，不等B2完成／独审QA／总验收。

受测原JAR SHA256 `61bdd9b446b2d67313a69da989db38d735847701e802679435967d6f25b62e22`／44,658,679字节／1,679生产entry。准确旧原JAR `b29314a5390bc3bad2ed0bc11a2873df95674dd5bf94207b392252aca1eb510a`／44,644,098字节／1,671entry。直接读全部旧新entry：1,663既有原字节同／8新增（7class＋1XML）；七个既有Ledger嵌套class仅Code.LineNumberTable变化，常量池／代码／其余属性一致。660其他生产Java／430既有资源／冻结文件／schema22原字节同；新增5生产Java＋1XML。Ledger只改answer接线及新Service字段；Controller/UserQuestionLedger、其他Ledger方法、已迁Service、Bean/bootstrap/POM及持久化装配不改。

## 完整受理与原事务边界

纯Java接口／Service／impl及泛型单项port拥有原tx→bucket首次tenant→完整Visible→三键FOR UPDATE列表→空QUESTION_NOT_FOUND/非空首行→四键previous列表。端口构造仅存引用，不取identity成员、command成员、task/q、query/session、时间或环境。所有SQL实参先求值，再同DS/UoW原nullable/显式queryContext会话；无预解码/Map复制/修改。保留String cast、Number.longValue、time内部重复get、identity/command/receipt编码重复读取及到达首错。窄事务port原样委托tx，内层Outcome只承接受理value/rejection，原Ledger AdmissionResult及tx特殊拒绝/事务外outcome/后记边界冻结；Service在tx返回后才抛rejection原实例或返回Submission。

previous非空取首行；只有原input.questions→canonical→digest的TaskFailure被新409/IDEMPOTENCY_CONFLICT替换；try外digest比较及原receipt读取不扩大catch。重放无需时钟／版本／revision／flag／状态／写入。新分支只一次原Clock；expiry含等号，先close→完整view→非terminal才terminal→返回410拒绝结果，tx后抛。原view terminal→cancel→CLOSED短路，再feature flag，完整view附带artifact等副作用不简化。due非NULL→不早于due→OPEN才Continuation，成功后两键普通重读q，空仍404/NOT_FOUND，非空首行；随后answer_id、命令version、revision依次返回拒绝，保留已发生续问写入。

canonical try及storage try只catch原TaskFailure并以原实例入拒绝；JSON/digest/id/seq/update与ToolResult/Resume/event异常不被并入catch。answer原JSON→digest→id→foreground→DB seq→原Map.of receipt。receipt保持原实参顺序与long+1溢出/NULL语义；storage使用answer UTF8 bytes＋第一次receipt JSON UTF8 bytes＋16384。UPDATE绑定仍第二次receipt JSON、重复command.expectedRevision/tenant/q.revision，不缓存合并；数据库revision+1/MAX+1不Java预算。仅updateCount==1成功，0/负/>1均原冲突、无补偿/重试。foreground才原AnswerBatch→编码→完整ToolResult→Resume；late不调二者；最后原question.answered→Submission(false,同receipt)。

## 五个真实SQL与JDBC差异

QuestionAnswerMapper/XML五条SQL原实串完整保留：三键user_question全列FOR UPDATE；tenant/task/owner/answer_key全列previous；tenant/id全列续问重读；tenant/task的COALESCE(MAX(accepted_seq),0)+1；原完整ANSWERED条件UPDATE。无新增谓词/锁/JOIN/COUNT/EXISTS/LIMIT/排序/二查/事务。三处SELECT*仅对这三个准确Mapper ID放行，未扩大共享投影门禁。3+4+2+2+14 bind；14类型String/String/Timestamp/int/String/String/String/String/int/String/String/String/String/long。原WHERE和NULL等值语义保持；实际样本均非NULL键，NULL wire/SQL错误映射未新验，加载SQL含NULL仅合成。

全列33个原列名/case/顺序/值类型/NULL逐列从本次真实ResultSet重算：22条全投影读取（含空previous）／14个非空行，空结果无getter或metadata到达范围分列。旧SELECT executeQuery/getObject(索引)→新execute/getResultSet/列标签typed getter；可空数字新getInt返回0再wasNull还原NULL，其余实际类型相符。seq旧getInt(1)/wasNull→新getInt(列标签)，实际Integer2新侧未调用wasNull（其IntegerTypeHandler仅0时检查），NULL范围另作合成；原nullableSingleResult空/多行Spring异常、一行NULL与Service int拆箱NPE仅合成。UPDATE旧executeUpdate→新execute/getUpdateCount；Timestamp/seq/sourceRevision/revision旧setObject→新setTimestamp/setInt/setInt/setLong，其余String仍setString。实际Timestamp按本次Clock对应receipt Instant及微秒精度核过；原Clock本parent已迁Mapper，普通Statement与prepared均采集，本轮实际均prepared，不误记旧时钟0SQL。

迁5旧JDBC点，SQL消除0；库存151/29/515→146静态/29动态/490bind，表仍OPEN。queryContext、同DS/UoW、全局cacheEnabled=false/STATEMENT、原累计meter/绝对两秒/末尾deadline、连接/时间/事务设置均冻结；本轮未新增queryView运行验收。

## 两轮原执行与封存

run1：Checkstyle/产品编译通过；测试编译两处lambda引用随后重新赋值局部变量失败，verify1，未到单测/package/IT/SpotBugs。47项封存含实际生产class与编译回执；测试class仅以原compiler receipt归属，不声称本轮测试编译成功，也不将旧target JAR当本轮产品。封存后仅测试增加final引用，不改产品。

run2：定向verify0/BUILD SUCCESS，10Service＋3port＋2追踪＋1架构＋1真实IT均通过，skip0，SpotBugs bugs/errors0，旧新driver均0。108项封存；受测原JAR仅本轮package生成，后续未重跑Java/数据库。完整源码/源码XML/执行helper、编译回执、实际class、日志、主错cause/suppressed/SQLState/vendor结构及清理回执随包。构造惰性/NULL identity tx内首错、空/首行、重放catch、等号expiry、due重读、所有拒绝/短路、receipt两编码、14bind、合成count0/-1/2、原Map引用/成员重复变化以及五路径每到达RuntimeException/Error原实例阻断均覆盖；不可表达边界不造schema非法行。

三对私有paid=false旧新样本／五对公开Ledger调用：

1. foreground成功33SQL，OPEN/revision2→ANSWERED/revision3，CHECKPOINTED/TOOL_RESULT/accepted_seq2；ToolResult两UPDATE→tool.completed→Resume/task.resumable→question.answered。相同key/command replay10SQL，原receipt回放、无Clock/写入。
2. due-stale33SQL，合法OPEN/revision2且due已到/expiry未到，ToolResult→CONTINUED/revision3→Resume→continued→重读q→409/QUESTION_REVISION_CONFLICT，拒绝前本侧首写可见；同外层事务再revision3晚到成功20SQL，ANSWERED/revision4、USER_MESSAGE/PENDING，仅question.answered，不再ToolResult/Resume。
3. expired38SQL，合法显式到期/非terminal，closeQuestions→原terminal FAILED/DEADLINE_EXCEEDED→tx外410/QUESTION_EXPIRED；本侧CLOSED/taskFAILED及事件可见，未声称无外层事务时持久拒绝提交。

两侧SQL文本/顺序/完整tenant/actor/installation及owner锁链/单连接、各SQLbind/getter/API、每次实际Clock、输入/答案/output/receipt原串/摘要/UTF8长度、canonical请求digest、seq/revision和事件fact/seq关系离线重算通过。生成ID/时间/Map.of只比本侧已证关系；未强求独立时刻或Map.of跨进程原串等值。12份原fixture/after证明＋10份调用中间证明、6 setup Lease、6笔tenant-a21表外层事实恢复、1清理均核过，worker_process未纳入。显式合法timed0.2.0双call响应/两intent/WAITING_USER invocation/TOOL RESERVED/已存答案receipt夹具，不由openOrResult/工具/provider生成；setup PAUSED attempt/清活动字段不是有效执行租约。HTTP只新侧setup，生产Controller按冻结源码，不称本head HTTP认证/答案提交新验收。

## 原准备与离线失败、剩余

准备脚本两次regex格式匹配失败、生成helper单引号SQL导致SyntaxError均保留；首个准备失败只有工具回执，独立stderr缺件仍OPEN。上线前源码检查纠正生成器误替换receipt键，未编译/执行该状态。平台早期rule查询本地检查与实际rule就绪分列，不称产品失败。

离线projection分析器首版假定空previous也有metadata而KeyError，修为只核实际到达getter的全列后；第二版未按冻结Payloads.SORT_PROPERTIES_ALPHABETICALLY重算QuestionAnswerRequest而摘要断言失败，修为原字母字段序后通过。两版原脚本/stdout/stderr保留；只重算已封存原日志，无产品/执行助手修改或Java/DB重跑。完整可复现离线核验器随附，可选准确旧JAR直接核全部旧新entry和七嵌套仅行号。

本片不迁digest/receipt/event内部、authorize/Controller、openOrResult/维护/既迁Service。下一具体候选为准确源码的openOrResult完整lease admission/六个直接旧JDBC点，须PM先核源码再独立续派；本轮不混迁。B2未完成，B3不激活；B0–B5完整稳定前61/62仍backlog。35表＋receipt/core最后beforeCommit/scope成功工具read/持久FAILED-read/独立后记/其他真实缺行/自然08S01/709ms/冷路径和历史缺件OPEN。无新工具/worker循环/provider/真实Key付费/DELETE/SQL故障/UNKNOWN恢复/CI/合master/部署/共享服务数据/daemon代理FRP重置；T48/新功能暂停。
