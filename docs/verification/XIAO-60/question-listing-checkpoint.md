本片仅迁 `JdbcLedger.listQuestions`，准确parent `e79cbc0a63e874923a7ba20f28b82239bf9479e3`／tree `82a982e7c1c00773f0a468274805e50ed3b8d65f`。沿原分支agent/agent/7aeccb14d7a9、PR22草稿，原A唯一集成写入。准确新SHA/tree与Git原对象见交付manifest；本报告随同源码提交，不在提交内容内自指SHA。仅B2部分检查点、开发自测，非独审QA／总验收。

受测原JAR SHA256 `e06fece541a6675d2438066e5cf8c4bbcd3affcdbfde743820e88cf144f30555`／44,638,997字节／1,667生产entry；后续轮次明确复用run1此原品。直接读准确旧新全部1661／1667 entry：1653既有原字节同／6新增，七个既有Ledger嵌套class独立解析仅LineNumberTable不同。651其他生产Java／429既有资源及冻结文件、schema22/V1–V22原字节不变。新生产Java五个＋XML一个；Ledger仅列表接线与新Service字段改变。Controller/UserQuestionLedger和所有其他Ledger方法完整保持，保护全部已迁Service及相邻Bean/bootstrap/POM。

## 完整列表资格Service与惰性端口

纯Java `QuestionListing` 泛型单项port、Service接口与impl承载原tx内全部编排。构造只存Ledger、identity/taskId与窄tx、Visible、Clock、Input引用；不读tenant、query、会话、flag、时钟或环境。调用顺序：原tx/UoW（含原特殊TaskFailure/AdmissionResult提交边界）→identity.tenant→bucket→原questionVisible完整identity/owner存在行/locked/access→原caps String cast/NULL/split/contains。混合/重复caps仍按原contains；非question直接原Map.of(version=1,items=List.of())，无Clock/联表/flag/最终编码限额。NULL identity在tx内首tenant读取NPE，端口不预检。

question分支即便零行亦先复用既有Clock一次，然后ArrayList、联表和逐行新LinkedHashMap；同一本次Instant用于所有行，不逐行重读或跨请求缓存。每行固定put/read顺序：requestId、questionVersion、revision、invocationId、callId、mode、phase、原Input.questions、foregroundDueAt、answerExpiresAt、answerable。String cast、Number.longValue、LocalDateTime按UTC、NULL、原time内部重复get均保留；不整行复制/预解码/DTO、不改原task/q Map。

Service逐行原位置读取flag，并拥有完整原短路：enabled → 完整view(task).terminal → cancel_requested_at → 本次now.isBefore(再次time(expiry)) → 再次phase属于OPEN/CONTINUED。flag false仍已构造前面的展示字段/Input/expiry，但不view/cancel/第二expiry/phase；terminal、cancel、expiry等号、phase分别阻断后续。view单项端口返回原完整Tasks.View，仍执行原TaskOperations.view的snapshot/artifacts/混合cap权限与异常/SQL副作用，不简化为状态枚举、不缓存一次全局view。每行固定展示phase和后续资格phase不合并，expiry两次time各保留内部get。

answer_id前置get非NULL才独立第二次String读取，并按序追加answerId/answeredBy/answeredAt/原Payloads.read(JsonNode)/deliveryState；close_code前置get非NULL才独立第二次String读取追加closeCode。无新增canonicalAnswers/摘要校验/过滤/NULL保护，坏answer JSON保持原读取位置及异常。items及item保留可变/插入顺序与NULL键。

循环后原Map.<String,Object>of(version=1,items=items)，只为限额一次原Payloads.json→UTF8 bytes.length；仅>524288抛409/QUESTION_RESPONSE_TOO_LARGE，恰边界通过，返回同result。无预估/累计/字符计数/截断/分页，不把Map.of顶层顺序称稳定跨进程字节契约。无新guard/catch/fallback/retry/事务/事件/写SQL；kernel不依赖Jackson/plugins/adapters/MyBatis。

## 原联表、投影与实际JDBC差异

question域专属参数化Mapper/XML保留完整原实串：

```sql
SELECT q.*,i.provider_id FROM user_question q JOIN invocation i ON i.tenant_id=q.tenant_id AND i.id=q.invocation_id WHERE q.tenant_id=? AND q.task_id=? ORDER BY q.ordinal
```

两个String实参仍identity.tenant()→原taskId，全部到达后才取同DS/UoW原nullable/显式query及Mapper会话，不从task行推导。q.*全33列＋provider_id共34列，完整tenant/id JOIN与tenant/task WHERE、ordinal排序保持；无额外phase/PENDING/accepted_seq/状态/锁/排序键/LIMIT/过滤或SELECT/拆实体。只为本准确Mapper语句加原星投影审计白名单，不扩大共享门禁。callSettersOnNulls=true、LinkedHashMap、cacheEnabled=false、LocalCacheScope.STATEMENT不改。

真实ResultSet产品读取捕获34列标签/case/次序与原类型/NULL：旧queryForList的PreparedStatement.executeQuery/getObject(索引)→新Mapper PreparedStatement.execute/getResultSet及getString/getInt/typed getObject(列标签)。数字nullable的getInt原始0与随后的wasNull分列后还原NULL；时间仍实际LocalDateTime，微秒与原UTC展示保持，数字展示字段仍longValue后的Long。两个非NULLString仍setString；NULL bind wire未新增真实验证。全部列及各行与本侧已存q原值/provider相核，非只比DTO字段；完整getter/API记录见full-projection-proof，不声称全部JDBC调用相同或自然驱动错误翻译已逐字验证。

采集器同时支持普通Statement与PreparedStatement。本parent已有Clock为Mapper，两侧本片实际时钟均PreparedStatement.execute/typed getObject(LocalDateTime)，一条原SELECT CURRENT_TIMESTAMP(6)，不能沿历史只采prepared漏记时钟。本轮普通路径未构造queryView矩阵；原queryView起点/累计meter/绝对两秒/末尾deadline、拦截器/连接/事务/socket/network/时区设置均未改，前片准确e79cbc0的实测只作历史引用，不冒称本片新scope成功工具read。

迁1旧JDBC点，SQL仍一条／消除0。库存实计152静态／29动态／518bind，基线153／29／520；35表＋receipt仍OPEN，不预报收益/关表。

## 定向验证、原失败与有界样本

准确parent的UserQuestionContractTest/UserQuestionIT/user_question_verify.py、QuestionClockScenario owner-list与Mapper模式已核，只引e79cbc0旧head来源，非本head新验收，不重跑历史完整codec/问答/retention/response/模型/session矩阵。新增8Service＋3port单测：tx/tenant/Visible/构造惰性、非question/零/多行、时钟一次和身份两bind到query；精确字段/Number/NULL/time首错与可变重复读取；每行flag、完整view次数及全部短路（含expiry相等）；optional字段、原Map引用、不复制/修改、原异常实例在各到达处阻断；精确UTF8 524288/524289及多字节合成编码；真实加载联表、原投影/缓存/NULLmap策略。合成NULL/类型/变化回调/极限编码与真实schema范围分列，不造非法NULL/CHECK/FK行。

run1：8Service＋3port＋2追踪＋1架构全部过；真实IT在HTTP准备普通任务时以INVALID_CAPABILITIES拒绝空capabilityIds，未启动旧新driver/未到列表；verify1，SpotBugs0。原日志/原源码/class/编译/清理94项封存。改为合法已安装web_fetch普通任务，仅受理、不执行工具。

run2：2追踪＋1架构过，IT/verify1；两侧driver1，在setup原QuestionContracts.output先拒绝混入pending=false/callId的已答output，未测列表。前次替换漏匹配格式化文本，未修到该源码；原错误/SQL空的测量区间与实际setup SQL、外层恢复、清理分别保留，89项封存。随后精确修正原答案形状（answers、其余NULL）并补观测wasNull，产品不改。没有把setup拒绝说成列表产品失败。

run3：仅2追踪＋1架构＋1真实IT过，verify0/BUILD SUCCESS/SpotBugs0、两driver0，90项封存，复用run1原JAR；未重跑11业务单测。完成owner两行和nonquestion两对，原SQL/锁/字段/类型/NULL/短路/时钟/view/清理核过。此轮第二invocation为schema合法显式clone，对应第二call未进入原响应；该限制保留，不能称完整响应-意图关系已证。

run4：为上述新发现的夹具关系缺口，只补owner-list一对，不重跑nonquestion或11业务单测；2追踪＋1架构＋1真实IT过，verify0/BUILD SUCCESS/SpotBugs bugs/errors0，无skip、两driver0，92项封存。原modelResponded接收显式双call响应并生成对应两intent，非运行provider/model/tool。原第一call作为WAITING_USER/TOOL RESERVED/OPEN无答案问题；第二call显式已存SUCCEEDED/SETTLED、原答案output/schema/hash与QUESTION_INPUT相容，问题ANSWERED/合法CHECKPOINTED及原形状TOOL_RESULT receipt/answerRequestDigest，附合成已存close_code用于展示。两q按ordinal1/2、NULL foreground与合法未来expiry/另一行due、原输入/答案codec和全部可选键核对。原响应、step、invocation三键/slot/provider关系另核；不是openOrResult/工具/provider/真实问答事件生成，也不称第二行closeCode有真实业务生成历史。新fixture/调用都复用原PAUSED setup，不称有效执行Lease。仍复用run1原JAR。

run3非question为真实受理的web_fetch任务，原tx/bucket/Visible/权限后空items，时钟/联表0，不读flag/最终编码。Controller.list认证→UserQuestionLedger.listQuestions完整接线、签名、返回形状源码原字节冻结；真实driver调用公开Ledger方法，HTTP仅新侧setup受理，不冒充测量了HTTP认证本身。owner/identity拒绝与更广codec按准确历史覆盖说明，本片不机械全组合；坏answer JSON只补port异常及Service首错合成，无新真实坏JSON样本，不把input首拒绝称answer解析错误。

共3对完成样本（两种路径，其中owner加强一对）、12份原夹具/成功证明、6份setup Lease、8笔tenant-a21表外层捕获事实恢复（含run2失败）和4次清理。worker_process未计入恢复范围；每轮captureSQL/本侧原串/摘要/长度及实际源/class/编译回执/主错cause/suppressed/nextSQLException/SQLState/vendor与rollback/cleanup/证据次错机制保留。生成ID/当前时间/Map.of只比已证字段与本侧关系，不冻结/重放时钟、不要求跨样本完整字节相等。没有持久提交、整库恢复或UNKNOWN恢复重放。

离线分析器对原封存/本侧值/完整SQL及两bind重算；34列原ResultSet投影/NULL/类型/case/order原证另验。debug比较器首版前缀替换误指本片run2→run1得0个差异，不是准确baseline证明；原脚本/输出保留，修为原e79制品准确SHA并强校七个嵌套class后通过，仅LineNumberTable。未重跑Java/DB。准备生成器曾因工作目录路径及格式化匹配ValueError失败，原独立stderr存在者保留；仅工具响应可见的路径错误以转录备注保存，独立原stderr缺件不冒充补全，继续OPEN。

## 完成/剩余与阶段门禁

本片完整列表Service/原公开接线/联表/字段/短路/限额及必要差异自测交付。下一具体候选为仍原JDBC的authorizeAnswer完整tx/bucket/Visible及tenant/task/request存在性资格（404 QUESTION_NOT_FOUND），待PM按准确源码核定续派；不在本片混迁。answer/openOrResult/maintainQuestions及B2其余编排仍待后续，B2/B0–B5未完。60保持in_progress/PR22draft；B2完整才核B3，61/62继续backlog。

run `01a175af-8e3f-7a06-afc7-bef678294e97`，已有唯一PM规则 `01a175b0-3346-70d6-921f-61123ccae91c` 绑定三终态＋两小时，未新建/恢复旧规则。受测原JAR分片、Git原对象、逐轮源码/class/编译/原失败/清理、可复现离线核验器及回执随包。没有独审QA/总验收/整库/持久提交/成功工具read/持久FAILED-read/独立后记。35表＋receipt、core最后beforeCommit、其他真实缺行、自然08S01/709ms/冷路径及全部历史失败/准备stderr缺件OPEN。

tenant/whole-scope/fence/actor/installation、原锁序/同DS/UoW、baseAccess四独立资格/question专属else/MCP、access原catch、scope403 afterCompletion去重/REQUIRES_NEW后记、特殊拒绝结算/事务外outcome、commitFence13处/session边界、todoProjection完整21列/case/NULL、冻结值类型/字段序/字节摘要/数据库时间/schema22均保持。无worker循环/新工具/provider/真实Key付费/DELETE/SQL故障/自然08S01/UNKNOWN重试；无CI/合master/部署/共享服务维护/daemon代理FRP重置。63/64/66/70不重接，72固定旧head，T48/新功能继续暂停。
