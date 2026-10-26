本片仅迁 `JdbcLedger.authorizeAnswer`，准确parent `1c1d7ba7485437fadb810b0cfd15c70efa4908e6`／tree `a85521b17dfa2e9ed535c617a6fea775c3e38c17`，沿原分支 `agent/agent/7aeccb14d7a9`／PR22草稿。新SHA/tree见交付manifest及Git原对象；本报告随源码提交，不自指未生成的提交SHA。仅B2部分检查点，60仍in_progress，非B2完成或独审QA／总验收。

同版受测原JAR SHA256 `b29314a5390bc3bad2ed0bc11a2873df95674dd5bf94207b392252aca1eb510a`／44,644,098字节／1,671生产entry。准确旧原品SHA256 `e06fece541a6675d2438066e5cf8c4bbcd3affcdbfde743820e88cf144f30555`／44,638,997字节／1,667entry。直接读全部旧新entry：1,659既有原字节同／4新增；七个既有JdbcLedger嵌套class解析后仅Code.LineNumberTable不同，其余常量池／代码／属性不变。656其他生产Java／430既有资源／冻结文件及schema22/V1–V22原字节保持。新增生产Java四个，无新Mapper/XML；Ledger仅本方法接线及Service字段改变，其他Ledger方法、全部已迁Service、Controller/UserQuestionLedger、Bean/bootstrap/POM及持久化装配均不变。

## 完整存在性资格与惰性首错

纯Java `QuestionAuthorization` 单项port／Service接口／impl拥有原完整tx内顺序：原tx/UoW→第一次identity.tenant→bucket→一次完整questionVisible(identity,taskId)→第二次identity.tenant→原taskId→原requestId→真实MP列表读取→仅isEmpty判断。任意非空成功；空才404/QUESTION_NOT_FOUND。端口构造只存Ledger／identity／taskId／requestId／窄事务与Visible引用，不读tenant／actor／query／会话／task／时钟或环境。NULL identity在进入原tx后首tenant到达才NPE。Visible单项完整复用identity／owner存在行／完整locked／access，返回Map原本不消费，新的void端口也不读其任何字段，不合并权限和问题存在性查询、不增q锁。

两次tenant不缓存；三String实参全部到达后才queryContext／同DS/UoW会话。原NULL和String传递，不trim／UUID解析／非空检查／IS NULL，不从Visible task推导键或额外读actor。Service只调List.isEmpty，不getFirst／遍历／size1要求／解码id。合成非空列表的元素访问直接poison，size可为1/2/3而不读取，覆盖任意未消费元素（含NULL）的范围；这些多行／NULL／poison边界不可由真实PK/NOT NULL表达，未造非法schema行。

原公开void返回及tx回调null保留。Service和端口不加catch／翻译／fallback／retry／新事务／提交／校验，RuntimeException/Error/TaskFailure按原实例传播并阻断后续。原tx含特殊拒绝结算／事务外outcome边界原样复用。本方法不加questionTask／questionsEnabled／caps／pin／版本／PURGED／时钟／可答资格；既有access内部拒绝不绕过。无问题UPDATE／答案／receipt／event／结算新增。

## 真实MP单列读取与JDBC差异

先核并复用既有 `QuestionBodyReadEntity`／`QuestionBodyReadMapper` 和QuestionVisibility的实际MP selectMaps模式。受审QueryWrapper显式select(id)，固定tenant_id／task_id／id全部eq，不因NULL省略条件；query参数仍原nullable／显式query基础装配，不改共享Mapper门禁。

```sql
-- 原
SELECT id FROM user_question WHERE tenant_id=? AND task_id=? AND id=?
-- MP实际
SELECT id FROM user_question WHERE (tenant_id = ? AND task_id = ? AND id = ?)
```

只差MP括号／空格，原完整三键／普通无锁／单列投影保持。无SELECT*／全实体／selectById／selectOne／COUNT／EXISTS／LIMIT／JOIN／排序／额外phase／answer／owner／flag／binding／fence／时间谓词。三非NULL String仍setString，顺序tenant/task/request。原PreparedStatement.executeQuery→新PreparedStatement.execute/getResultSet；存在行原getObject(1)→新getString(id)，实际返回均String；原列标签id、Map列表装载不改Service非空判断。真实空结果不读取id。NULL参数完整WHERE由真实加载MP的合成绑定测试覆盖，NULL wire未新增真实验收；实际SQL错误翻译未故障注入新验，不称全部JDBC调用相同。

同DS/UoW每侧单连接、原bucket/identity/owner/task锁/access完整actor与installation链及全bind按原日志核对；每次公开调用9条SQL，存在查询在最后，一次，三bind精确。本片无新增时钟SQL／写SQL；锁行里已有数据库时间表达式仍原样，不能称该方法新时钟读取。queryView装配、cacheEnabled=false／LocalCacheScope.STATEMENT、原累计meter／绝对两秒及caller末尾deadline、连接/事务/socket/network/时区设置均冻结；本轮真实公开普通路径，不扩queryView矩阵、不称scope成功工具read。

迁1旧JDBC点，SQL仍一条／消除0；库存由152静态／29动态／518bind实计为151／29／515。35表＋receipt仍OPEN，不预报关表或收益。

## 定向验证与实际证据边界

准确parent的UserQuestionContractTest／UserQuestionIT／user_question_verify.py已读：历史cross-task-question在另一个可见任务使用request原QUESTION_NOT_FOUND；QuestionVisibilityScenario的authorize-missing-question以及owner/identity拒绝来源仅作历史，不冒充本head新验收。旧整codec／问答／retention／response／模型／session矩阵不重跑。

唯一run1：4Service＋3port＋2Controller顺序＋2追踪＋1架构单测过，无skip；1真实IT过，verify0／BUILD SUCCESS／SpotBugs bugs/errors0、两driver0。无本片正式产品／测试／夹具意外失败或修复重跑。构造惰性与NULL identity tx内首错、tenant两次变化回调、精确全部顺序、tx-null/void、非空不消费行、空结果精确拒绝与每处RuntimeException/Error/TaskFailure同实例/后续阻断；真实加载MP单列SQL及三String/全NULL参数不漏谓词、基础缓存配置均覆盖。端口测试单项Visible不消费原返回Map。单测不替代实际SQL。

Controller/UserQuestionLedger原字节冻结。新增有界合成spy/poison request证明authentication→authorizeAnswer→key格式→body读取／32768上限→strictJSON→answer：认证失败不authorize；授权缺问题优先于坏key/poison body；授权通过后坏key不读body，坏JSON／32769字节不answer；合法命令才调原answer。单测的202是stub Submission映射，不是真实HTTP认证或答案提交。原answer另起自己的原tx/bucket/Visible/q锁/幂等/Clock等，未合并两事务或复用授权查询作锁行。

必要两对新head旧新私有paid=false公开Ledger样本：authorize-present合法owner+同tenant/task/request成功void；authorize-missing同合法owner/task仅把requestId参数改为准确不存在的ffffffff-ffff-ffff-ffff-ffffffffffff，真实单列空查后404/QUESTION_NOT_FOUND。不是DELETE／FK修改／Visible先拒绝；资格成功不等可答或答案持久提交。driver中result=void是证明标签，并非公开返回String或HTTP响应。

复用准确parent最终合法双call夹具：HTTP仅新侧setup受理任务；显式原modelResponded存双call响应／匹配两个intent，合法timed0.2.0问题／WAITING_USER与SUCCEEDED invocation／TOOL RESERVED及SETTLED、原形状答案／receipt／摘要、binding/pin/request/时间窗相容。该响应、问题与已存答案非openOrResult／真实工具／provider／真实问答事件生成。registerWorker/claim仅构造setup Lease，测量前attempt PAUSED／清活动字段，非有效执行租约。没有worker循环／provider调用／新工具／真实Key付费。

8份原fixture/成功/拒绝证明、4份setup Lease、4笔tenant-a21表外层捕获事实恢复及一次私有实例清理均保留。每对每侧证明SQL前后全部本侧已捕获业务事实不变；每个trial外层rollback后所捕获21表事实相等，worker_process未纳入，不称整库／持久提交。原串／SHA256／UTF8字节长度分存；生成ID／当前时间／Map.of只比本侧关系，不强求跨样本完整字节相同。原SQLException cause/suppressed/next/state/vendor、rollback／cleanup／落盘次错采集机制保留，但本轮无故障注入或自然08S01，不声称已新验错误映射。

每轮实际source/class／编译回执／完整日志和源/class/JAR匹配封存，校核器只离线重算，不重跑Java或DB。最初wakeup只读CLI超时后有界读取成功，原stderr保留；此平台读取失败不冒充产品/测试失败。其他准备转录未补造独立stderr，历史缺件仍OPEN。

## 完成/剩余

完成本片完整Service／惰性端口／原公开接线及实际MP单列三键查询、必要差异与有界自测。下一具体候选是仍原JDBC的 `answer` 完整新答/幂等/过期/foreground或late受理与原事务外拒绝：包括q三键FOR UPDATE、previous四键、续问后重读、accepted_seq和ANSWERED条件UPDATE；由PM核准确源码续派，本片未混迁。answer/openOrResult/maintenance及其余B2仍未完成，B2完整才核B3。

本run `01a175e7-d060-735d-b446-703d6d59563c`／已有唯一PM规则 `01a175e9-068c-7b6e-95a9-e601e323f13f` 精确三终态＋两小时；不新建或恢复旧规则。60保持in_progress／PR22 draft，B0–B5完整稳定前61/62 backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径及历史失败/准备stderr缺件OPEN。保护全部已迁Service、baseAccess四独立资格/question专属else/MCP、access原catch、scope403 afterCompletion去重/REQUIRES_NEW后记、特殊结算／事务外outcome、commitFence13处/session边界、todoProjection21列/case/NULL／冻结类型/字段序/摘要/DB时间/schema22。无CI／合master／部署／共享服务维护／daemon代理FRP重置；63/64/66/70不重接，72固定旧head，T48及新功能暂停。
