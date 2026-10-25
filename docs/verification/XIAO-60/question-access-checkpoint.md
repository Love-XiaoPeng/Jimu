本片仅迁 `JdbcLedger.questionAccess` 完整问题资格编排，parent `695c05789ba11128df3617758a613eca1181881d`／tree `b7426ceedf9026b6a86af42e6129a34bd62a962d`，沿原分支 `agent/agent/7aeccb14d7a9` 和草稿 PR22。准确交付源码 SHA/tree、受测原 JAR 由 manifest／原 Git commit/tree/bundle 绑定。本片是 B2 部分检查点，不是 B2 完成、独审 QA 或总验收。

同版受测原 JAR SHA256 `9894c1afbe92a4376c5bd936fc9ae4f810e32eaf3240a397c17926f60b7d6915`，44,581,149字节／1,611生产entry。相对旧a0272aa5的1,605 entry，1,597原字节同／6新增；Ledger外七个既有嵌套class独立解析仅LineNumberTable变化。第二轮重新打包候选仅ZIP元数据不同、全部生产entry相同，未替代实际两轮受测原JAR。

## 完整资格与惰性边界

`QuestionEligibilityService`／impl 完整承载 tools/pin→contract→安装读取→四项OR→PURGED→binding 三项OR；类型化 `QuestionEligibility<P,C>` 保持原 pin 和已取得 contract 对象，不把 adapters 类型带入 kernel。适配器只提供原成员／codec／持久化回调。Service 不依赖 MyBatis、跨聚合私有端口或 Ledger，不递归；没有新增 caps/flag/身份/模型检查、catch、翻译、fallback 或事务。

构造仅保存 ledger/task 引用，行包装仅保存原 map，不读取／解码任何字段。pin 由原 `tools(task).pins().get("ask_user_question")` 一次取得；原 tools 快照／摘要／schema／pin集合／task epoch 校验原样复用。首次 version 到达后才原 `questions.contract(version)`；NULL pin 的成员 NPE、未知／NULL version 的原 PLUGIN_PIN_MISMATCH 均先于安装读取。

四项比较保留 version→安装version、digest→安装digest、再次digest→已取得contract的descriptor.outputSha256、pin epoch→安装Number.longValue。接收者先取、参数后取、最后equals，前项不等不读后项；无预构造descriptor、字段缓存、constant.equals替换、范围检查或统一NULL校验。成功路径version共三次（含首次contract和最后binding）、digest两次、epoch一次；原异常和Error传播。

PURGED检查仍在四项之后、binding之前，原410/QUESTION_CONTENT_EXPIRED保持。binding只读一次保存局部；NULL直接409/SNAPSHOT_CORRUPT，不读hash/digest/version。摘要仍原hash→task digest→equals；通过后才第三次pin.version→原questions.binding内部contract/mode/序列化→原串equals。QuestionContracts和blocking/timed历史绑定内部完全未迁动，不加codec或JSON等价比较。

## 原完整行与真实MP读取

复用既有plugin域 `InstallationMapper.selectMaps`，显式 `QueryWrapper.select("*")`，不采用实体默认六列投影、不改现有Mapper/XML/entity。完整六列为tenant_id/plugin_id/enabled/epoch/plugin_version/plugin_digest，native值类型／NULL不解码，空行404/NOT_FOUND、非空首行；不selectOne、不要求size1，多个结果仅合成首行合同。

```sql
-- 原JDBC
SELECT * FROM installation WHERE tenant_id=? AND plugin_id='ask_user_question'
-- 本片实际MP
SELECT * FROM installation WHERE (tenant_id = ? AND plugin_id = ?)
```

原tenant String一bind＋固定plugin字面量变为tenant String、固定ask_user_question String两bind，另有WHERE括号／空格变化。无FOR SHARE/FOR UPDATE、enabled谓词、排序/LIMIT/额外SQL/锁。tenant String cast在原位置到达，然后原queryContext和同DS/UoW；构造不预读。真实加载MappedStatement的单测核完整SQL和参数，既有星号门禁未放宽，未增加共享Mapper例外。第二轮driver仅包装原getter返回，不另发SQL／增加字段getter：旧六次getObject，新四String列getString、enabled getBoolean、epoch getLong，六列String／Boolean／Long原值一致（含disabled false）。MyBatis execute/getResultSet/getUpdateCount(-1)与原executeQuery差异保留，不称全部JDBC调用相同。

唯一直接接线仍baseAccess原question回调。epoch→session/directory/todo/question四独立资格／question专属else／MCP保持；所有其他Ledger方法词法不变。questionVisible原identity→owner三键→locked→access，listQuestions原bucket→questionVisible→questionNow→联表，幂等重取、claim/fenced、prepare/responded、answer等位置均未改。access catch／scope去重及REQUIRES_NEW后记、特殊结算／事务外outcome、共享commitFence13处与session完成边界、queryView起点／累计实计／绝对两秒及末尾deadline完全未动，不新增beforeCommit钩子。

其他604生产Java／422既有资源及冻结文件逐字节保持，schema22/V1–V22不变；相邻Bean/bootstrap/POM、所有已迁Service、todoProjection21列／case／NULL／字段序／摘要保持。本片迁1旧JDBC点，SQL仍执行／消除0；库存静态164／动态29／bind551，未关闭整表。

## 已有覆盖与本片补充

先核准确parent的UserQuestionContractTest（两版本input/output、严格codec、blocking/timed及摘要合同）、UserQuestionIT／user_question_verify.py的历史真实问答／retention场景和TaskBaseAccessServiceTest四独立模块／question else覆盖。本轮不重跑完整问答／worker／response六组矩阵；这些仅为原源码覆盖来源，不当当前head新验收。

本片15 Service单测补完整18步次序、重复成员、四OR逐项短路、NULL接收者仍求参数、PURGED、binding只读一次／原串及hash→digest→最终version/binding、每个回调RuntimeException／TaskFailure／Error传播；8 port合同补构造／row不读、原pin成员、tenant cast先于session、空行／首行、惰性String/Number/NULL、两个历史模式及真实MP SELECT*两键无锁。pin变化／NULL／多行／非常规Number仅合成单测，不冒充真实数据库。

实际定向verify、逐轮原日志／失败（如有）、编译回执、执行source/class、JUnit及制品对应关系见manifest与analysis.json；每轮下一次执行前封存。主错的cause/suppressed/next SQLException/SQLState/vendor、rollback和证据落盘次错分别采集，次错不遮主错。没有机械SQL注入或等待自然故障。

首轮103项封存：15Service＋8port＋2追踪＋1架构过、SpotBugs bugs/errors0；旧新各10个业务结果和恢复均完成，driver exit0，但比较器仅采getObject，漏掉新侧typed getter，IT／verify1。封存后只修driver采集和助手phase，第二轮91项封存：复用准确首轮原JAR，仅direct有效／disabled两对；2追踪＋1架构＋1IT过，verify0／SpotBugs0，未重跑未变23项单测。

另一次离线重核误期幂等重取只查1次；原existing和replay各access，实际两侧均2次。保留初错脚本／AssertionError后，仅修比较器count并逐条核每次query，未改产品或重跑Java／MySQL。最终助手与离线执行快照匹配；每轮仍匹配各自实际driver、助手、编译件，未将最终助手冒充当轮原件。修正后的纯比较器独立核首轮10对及第二轮2对原日志；首轮新侧完整getter值未捕获，不以第二轮两对泛化10组完整行验收。

## 有界真实链及事实边界

私有MySQL8.0.46／paid=false，HTTP仅新侧合法0.2.0 question任务受理准备，旧新试验前复制同一数据库。没有worker注册／循环、provider请求、新工具执行、真实问答／回答或retention清理。任务保持QUEUED，无真实user_question行；公开listQuestions实际items为空，验证的是完整owner/access链，不冒称已存成功问题工具read。

必要旧新样本分列：direct有效；公开owner listQuestions；真实submit已有幂等重取；真实task PURGED→410；binding摘要坏→409；binding原串追加空格并先保持正确SHA2摘要→最终原串409；direct安装disabled仍通过、public先epoch403/CAPABILITY_REVOKED；实际DELETE安装行direct到404/NOT_FOUND、public先epoch403，不能称公开抵达本片404。没有制造非法版本／NULL行或删除attempt。

每个样本的变更后原task／安装完整行、tools／binding原串摘要，以及实际SQL顺序／完整tenant/actor/installation绑定分别保留。公开来源读取前仍原安装FOR SHARE；本片读取保持原无锁。四个公开提前epoch拒绝的两侧样本本片SQL为0；幂等重取两侧各2次，其余到达时每侧1次。绑定差异只为固定plugin新增第二String bind，不泛化SQL全串同。

结果JSON原串／SHA256／UTF-8长度各存；跨侧只比较已证JSON字段，保留Map.of原序，不称HTTP wire同。幂等View只比taskId/state/revision三字段，完整原toString另留；原数据库时间和完整任务记录各自保存，未改socket/network／事务／deadline参数取通过。两轮共24份原证明／24笔tenant-a二十表捕获事实在各自外层rollback后恢复、两份完整cleanup；不称整库、持久提交、FAILED-read、独立后记或独审QA。UNKNOWN未发生，不恢复重放。

准备阶段错误路径及PowerShell展开／错误cwd的只读探测回执保留披露；能独立落盘的stdout/stderr附原件，工具回执中未独立封存的完整只读stderr仍属缺件，未冒称齐全。封存脚本在首轮前已纠正为本片精确driver／编译件，不用前片助手替代。原产品源码和执行证据不因审计脚本修正而替换。

## 剩余与下一片

下一具体候选为 `JdbcLedger.questionStorage` 完整问题存储预算资格：原contains谓词→单条相关OCTET_LENGTH汇总→原Long/NULL/基数→signed long预算判断，需先核真实调用后沿原A主线接续；本轮未混迁。B2／B0–B5未完，60保持in_progress／PR草稿，最终61/62 backlog。

35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径及全部历史失败／stderr缺件OPEN。63/64/66/70不重接，72固定旧head；不CI／合master／部署／真实Key计费／共享数据服务／daemon代理FRP重置，T48及新功能暂停。

只回读本轮已有规则 `01a17488-fc9c-76ea-950d-4abf1dff2a16`，绑定准确run `01a17488-84bf-7bab-a8a5-6f129c844682` 的唯一completed/failed/cancelled、once及7200秒；未重复创建／恢复规则。
