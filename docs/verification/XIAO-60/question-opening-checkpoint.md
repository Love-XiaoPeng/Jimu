本片只迁 `JdbcLedger.openOrResult`，parent `3674097c485df117d30c7013b4d64612787329d6`／tree `d9510797db54efd433dc74601a5bc6f708e106ab`；原分支 `agent/agent/7aeccb14d7a9`、PR22 draft。准确新SHA/tree由交付manifest与Git原对象绑定。本报告随源码提交，不自指尚未生成SHA。仅B2部分检查点，60保持in_progress。

受测原JAR来自run1，SHA256 `fe9125c3577e6476f3c6ff2fa4c81fd318f1ea08a987b3fccb506155b8150457`／44,670,599字节／1,685生产entry；run2明确复用该原品。旧JAR `61bdd9b446b2d67313a69da989db38d735847701e802679435967d6f25b62e22`／44,658,679字节／1,679 entry。直接读全部旧新entry：1,673既有原字节同，6新增（5class＋1XML）；5个既有嵌套class只变Code.LineNumberTable，其余字节归一化后相同。665其他生产Java／431既有资源（含冻结资源和schema22）原字节同。每轮16生产class、最终3driver class及13执行源/XML/助手与Git／编译件／原JAR核对。Ledger只改本方法接线及新Service字段；HostInvocationGateway、UserQuestionLedger、Controller、其他Ledger方法、leaseTx/fenced、codec、reserve/event、已迁Service、Bean/bootstrap/POM不改。

## 完整用例与惰性边界

纯Java接口/Service/impl持有原leaseTx内全部分支；泛型类型化端口逐项承接Input/PluginPin/AnswerBatch/OpenResult/Timestamp，不在kernel引用插件、Jackson或MyBatis。端口构造只保存参数和依赖引用。原leaseTx、directoryTransaction、特殊拒绝结算及事务外outcome完整复用；没有新catch、guard、重试、事务或提交。

顺序为完整fenced(lease,true)→第二次bucket(lease.tenant)→原caps split/contains与flag短路→原输入codec→四键FOR UPDATE列表/空NOT_FOUND/非空首行。六个invocation匹配条件及pin version/digest/epoch短路均由Service执行。tools(task)完整快照、摘要、codec/schema及pin集合/epoch验证仍是原单项调用。保留task/budget/invocation引用、String cast/Number.longValue/time重复get、lease/call成员及原NULL首错，不复制/修改Map。

SUCCEEDED分支保留hash/codec/read三次独立output_snapshot读取，返回原OpenResult(false,batch)；不执行本片count/storage/写/Pause/event。原fenced的commitLiveFence仍执行，不能称整个历史入口无写SQL。非SUCCEEDED独立第二次state读取，PENDING才COUNT(*)+1 Integer单行nullable读取及int拆箱。ordinal>8、waiting_question_id、task预算再tenant预算原次序不变。storage(16384)→id→原版本timeout分支；0.1.0跳过timeout成员，非NULL保留两次读取。

原INSERT→task tools_used/waiting→tenant tool_used计数完全忽略，reserve八实参一次仍在最后invocation写之前；最后计数仅恰1通过，其他值原INVOCATION_STATE。成功才原完整Pause→question.created→task.waiting_user→OpenResult(true,null)。保留每次全部参数求值后才会话/queryContext、hash及Timestamp首错，以及原事务/权限/fence/UoW/实计/绝对截止边界；不调整连接、时间或全局配置。

## 六SQL与实际JDBC

专属QuestionOpeningMapper/XML保留原六条SQL：invocation全列四键FOR UPDATE，question两键COUNT(*)+1，原question INSERT（含IF/三timeout/数据库CURRENT_TIMESTAMP(6)），task增量，tenant增量，invocation PENDING条件更新。4+2+16+3+1+2＝28bind。先核现有QuestionInvocationReadMapper/Entity的MP模式；其窄投影不替代本片全列锁查询，未扩大共享Mapper门禁，只增加此准确SQL的受审断言。完整27列/case/顺序/值类型/NULL逐列由实际getter重算，共6次全投影读取。原列、谓词、锁、数据库微秒表达式和三timeout位置不改。

旧SELECT executeQuery/getObject索引→新PreparedStatement.execute/getResultSet及标签类型化getter；旧UPDATE/INSERT executeUpdate→新execute/getUpdateCount。全部String保持setString；ordinal从setObject→setInt，timed的effective_timeout和三due参数从setObject→setInt，retain Timestamp从setObject→setTimestamp；deadline原LocalDateTime对象仍setObject。blocking实测第9/12/13/14位均setNull，effective_timeout/foreground_due_at均SQL NULL；记录setter方法和实际NULL结果，未采setNull的第二个JDBC类型数值，不称原始wire包逐字节相同。真实样本各写计数1；0/负/>1及COUNT空/多/NULL仅合成。实际SQL异常映射未新注入验收。Statement和prepared均采集，本轮实际产品SQL全部prepared，不漏计普通Statement。

迁6旧JDBC点，SQL消除0；实计库存140静态／29动态／462bind，35表＋receipt仍OPEN，不预报表关闭或收益。

## 执行轮次与原失败

run1：产品/测试编译和7Service＋3port＋2追踪＋1架构通过；真实IT在测量入口前因助手把完整Lease交给原Payloads编码而失败，原Instant序列化限制触发Invalid payload/InvalidDefinitionException。旧新driver均exit1，未进入openOrResult；两侧已执行setup的tenant-a21表外层事实恢复。IT/verify1、BUILD FAILURE，SpotBugs bugs/errors0。107项封存含原source/class/编译/完整日志及原受测JAR。

封存后仅修助手Lease证明为原成员/字符串值，并补timeout先7后9的合成端口证明第二次读取不缓存；产品不改。run2：8Service＋2追踪＋1架构＋1真实IT共12通过，无skip，verify0/BUILD SUCCESS/SpotBugs bugs/errors0，两driver0；不重跑run1已过3port。103项封存，实际复用run1原JAR。

离线分析器首版沿前片误期7个嵌套class变化，实际5个均已归一化相同；原脚本/stdout/stderr/exit保留，改为准确5个后离线核证通过，没有改产品或原执行证据，没有重跑Java/DB。准备生成器的descriptor accessor在首次编译前按实际ToolDescriptor改为outputSha256；原生成器保留，执行source和Git对象为准。

## 私有paid=false公开Ledger实证

旧新各三次独立试验，共三对：timed0.2.0 PENDING新建34SQL；SUCCEEDED历史output15SQL；blocking0.1.0 PENDING新建34SQL。三条路径每次均重新setup/claim，在调用前核RUNNING task、ACTIVE attempt、current_attempt/fence/worker/live lease；六份完整ACTIVE证明与task/attempt原值封存。Pause后的PAUSED状态只属该次成功结果，没有复用失效旧Lease。PENDING没有预塞同id TOOL reservation；历史输出及SETTLED TOOL为显式合法已存夹具。

完整evaluation/tenant budget/task锁/actor/installation共享锁/pin读取/commitLiveFence/第二bucket→invocation锁顺序及全tenant/actor/installation绑定核过；每次同DS/UoW单连接。timed due与本次INSERT的question created_at精确差120秒；expiry等于本侧原task deadline，retain等于本侧task created_at＋604800秒。blocking mode/NULL effective_timeout/三NULL due实参和NULL due结果核过。COUNT为1，task tools_used及tenant tool_used各加1，原TOOL RESERVED，invocation WAITING_USER，Pause清活动字段/attempt PAUSED，两事件原fact_id/seq及OpenResult核过。历史结果原串/hash/codec/AnswerBatch关系核过，仍RUNNING/ACTIVE且只保留原fence revision写，不产生本片新问题或事件。

12份原fixture/after证明、6份ACTIVE Lease证明、8笔tenant-a21表外层恢复（含run1失败两笔）、2清理回执核过；worker_process未纳入事实恢复集合。原输入/output/问题串、摘要、UTF8长度和生成ID/DB时间关系逐侧存证；独立生成值、当前时刻及Map.of不声称跨样本字节全等。普通Statement/PreparedStatement执行/getter/列标签/API差异原样保留。主错cause/suppressed/next SQLException/SQLState/vendor及rollback/cleanup/证明错误分存，UNKNOWN未发生。

前置model checkpoint/dispatch/显式双call响应通过setup Ledger方法形成对应intent，历史SUCCEEDED输出和blocking pin/binding为合法显式SQL夹具；不是Host工具/provider真实问答或付费生成。只有本片测量中的新问题由公开openOrResult实际写入。registerWorker/claim只setup，无worker循环、provider、新工具、真实Key付费、DELETE、故障注入、自然08S01或维护daemon。HTTP仅新侧task setup，不称Host/HTTP认证新验收。

## 剩余与下一片

本批仅开发自测/源码及原证据离线核证，不是独审QA、总验收、整库恢复、持久提交、scope成功工具read、持久FAILED-read或独立后记。outer rollback内可见写入不当作真实持久提交；PAUSED结果不当作有效租约。NULL setNull数值类型与SQL错误映射、其他真实缺行、自然08S01/709ms/冷路径、历史准备stderr缺件等继续OPEN。

准确源码下一具体候选为 `maintainQuestions` 的全局有界候选扫描、逐task原tx/锁/身份拒绝及到期关闭/续问编排与剩余2个旧JDBC点；本轮未混迁，待PM核准确源码实际续派。B2未完成，B3及B0–B5完整稳定制品/影响矩阵仍待后续；61/62 backlog，63/64/66/70不重接，72固定旧head。无CI/merge/deploy/共享服务或daemon代理FRP维护；T48及新功能暂停。
