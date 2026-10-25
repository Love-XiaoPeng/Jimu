本片为 `JdbcLedger.todoAccess` 完整资格编排 Service 的 B2 部分交付，不等 B2／B0–B5 完成。基线 `62dcfd268f5a45c2c0820e7bfaf41818a541a9cc`／tree `df674a54b9be836cc2672a63235124e9a239e986`，同原分支 `agent/agent/7aeccb14d7a9`／草稿 PR22；最终准确 SHA/tree 与 parent 由 manifest、Git 原 commit／bundle 和逐 blob 索引绑定。

## 业务、持久化与真实接线

新增 `kernel/todo/port/TodoEligibility`、`service/TodoEligibilityService` 和 `service/impl/TodoEligibilityServiceImpl`。Service 完整执行严格 `capabilities().equals(List.of("todo_write"))`→原 tools/pin→安装行共享锁→五项 OR→末尾一次 todoProjection。没有目录 HashSet、非 todo 守卫、flag、模型、content_state、新 catch 或事务。

五项比较保留原 pin.version/digest 接收者求值，再取 descriptor／安装字段参数的顺序；两次 descriptor 独立，不缓存 pin 字段。NULL pin 在安装查询后、descriptor 前 NPE；NULL version/digest 接收者仍先完成原 descriptor 参数求值。安装 String cast／Number.longValue 仅到达对应比较才执行；NULL、非 Number、负数、BigDecimal.longValue 语义不变。全部成功才原 todoProjection，本片不迁其内部。

`MapperTodoEligibility` 构造仅保存 Ledger/task，既有 caps、tools、todos.descriptor 和 projection 回调按原位置执行。`Row` 只保存原 map，version/digest/epoch 各自使用原 s/n。适配器空行仍404/NOT_FOUND，非空取 first，不改 selectOne／size==1，也不预解码整行。

迁移一个旧 JDBC 调用点至 `todo.mapper.TodoEligibilityMapper.lockInstallation`／同名 XML：旧 `SELECT * FROM installation WHERE tenant_id=? AND plugin_id='todo_write' FOR SHARE`，新 `SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE`。唯一 SQL 改变是固定 todo_write 字面量变第二 bind；第一 bind 仍原位置 String tenant，原完整投影、FOR SHARE、首行、同 DS/UoW/query 保持。无 enabled、排序、LIMIT、JOIN、预读、批量、额外 SQL／锁／事务／CAS／双写。星号例外只新增准确 Mapper-ID 并核原 SQL，不放宽其他查询。SQL 仍执行，实删 SQL0；静态库存169／动态扫描29／bind565，不能据此关闭 installation 整表。

原 baseAccess 先 epoch，四模块独立谓词／资格、question 专属 else 和 MCP 不改。getTodos 的 UUID→tx→visible→access→原联表/view；owner、幂等受理、claim、prepare/fenced、responded 以及其他 access/projection 位置和次数均保持。claim 的 flag→历史 model→projection revision→attempt 上限；fenced 的 actor→flag→projection PURGED→历史 model 等相邻业务本片不迁。access try／scope.require／afterCompletion 去重／REQUIRES_NEW 后记、特殊拒绝结算及事务外 outcome 不改。whole-scope/fence、tenant完整键、锁序、nullable／显式 query、累计实计、queryView 起点／绝对两秒和调用者末尾 deadline 保持；未改 socket/network／事务设置。

源代码逐文件核验：579 份其他既有生产 Java 和418份既有资源／冻结原字节不变，新增1份Mapper XML；schema22/V1–V22、相邻Bean/bootstrap/POM及已接收Service不变。35表＋receipt全部继续OPEN。

## 准确制品与轮次

旧 JAR `c0cab24abd464b219d3d2a2bb6e6d74727a63dc0d27d1c0c9cb680afe428928d`，44,539,769字节／1,570生产entry。

新受测 JAR `b2c9944b3710c5a06f57f0d70c983d0da56a5a569041a8792d6a02b7e87cdcf6`，44547264字节／1578生产entry。旧新全部entry读取：1562既有entry原字节同、8新增（7 class＋1 XML）；Ledger及7嵌套class变化，后者独立按class格式核仅LineNumberTable。最终target全部生产entry与原受测JAR匹配。

首轮 verify 在 testCompile 失败／退出1：新增测试Fixture的 descriptor() 同名成员遮蔽外层二参数静态工厂，javac拒绝；没有执行单测／IT／SpotBugs，没有package，新产品未归属此轮。完整原日志、失败源码、compiler状态和当时可捕获class先封存；残留或部分编译件不冒称首轮测试执行通过。

先完成首轮40项seal后，仅把测试夹具工厂调用改为类名限定并格式化，不改生产实现／SQL。第二轮定向完整 verify：29 Service单测＋2 Row惰性值测试＋2追踪＋1架构，共34项，以及1真实IT通过，verify0／SpotBugs bugs/errors0。原执行source/class、编译input/output、JUnit/Maven、Python／驱动原件及产品每轮分别封存。首轮编译失败不是产品运行失败，仍保留原失败，不写首次verify成功。

既有 TodoContractTest 主要覆盖冻结工具契约，TodoLedgerScenario 覆盖执行／fence边界；这些来源复用不表示本轮重验。新增单测只补本片严格caps重复／混合拒绝、原tools/锁/成员求值／描述符短路、NULL及原异常、投影末尾和行字段惰性cast/Number语义。DB主键不可表达的重复安装行、异常字段类型和NULL pin等仍只有单测／静态首行代码核对，没有冒充真实DB多行或缺pin。

准备阶段只读命令曾误猜 DirectoryAccess／MapperExecutionSteps 文件路径；完整stderr没有逐项单独封存，现存终端回执／说明不能替缺失原件。历史失败缺件保持。主错cause/suppressed/next SQLException/SQLState/vendor、rollback、事后证明及SQL证据落盘错由驱动分别标记，次错不遮主错。

## 有界真实验证及限制

私有 paid=false MySQL8.0.46、Linux JDK21，只调用原 TodoExperiment.prepare，不跑其完整normal/recovery/worker矩阵，没有provider或工具执行。一个真实受理的QUEUED todo任务作冻结源，先克隆旧新私有库，再各19项：getTodos owner／错owner、幂等重取、prepare/fenced、responded成功及flag已知拒绝；direct正常、caps重复／混合、tools hash／缺快照、安装缺行／禁用／版本／摘要／epoch、projection缺行／摘要，以及public安装缺行前置拒绝。

安装和projection缺行均实际DELETE，发生在各样本外层事务内：direct todoAccess 的安装缺行到原404/NOT_FOUND；同样DELETE的公开getTodos先在epoch到403/CAPABILITY_REVOKED，没有到本片404。禁用安装direct仍成功，不增加enabled拒绝。版本夹具使用合法0.2.0，projection只删行或改合法长度摘要，未制造违反CHECK的版本行。无新增SQL故障注入／自然08S01／失效连接／UNKNOWN恢复、重放或原槽重试。

19对完成结果、原SQL顺序及完整actor／installation锁绑定核对；上述固定plugin→第二bind差异单列后比较，其余SQL顺序同。其他绑定若涉及生成step／dispatch等ID，保留逐条原值与相等标记，不泛化全部绑定或wire相同。原成功证明按各自task ID、todo binding原串摘要及可靠事件列自洽后只比已证task/projection字段；Map.of原串和摘要／长度各自保留。

两笔responded已知拒绝在内层outcome返回错误后／外层rollback前，另四条SELECT证明原dispatch RESPONDED／TODO_FEATURE_DISABLED、MODEL SETTLED实计17、可靠model.rejected。response是fixture JSON，不是provider响应／工具read，也不是持久拒绝提交或独立afterCompletion后记。

38笔tenant-a二十表捕获事实外层恢复，包含task／task_todo_projection；不是整库或其他tenant快照、持久提交／FAILED-read／独立后记。完整清理证明私有容器、临时根、fixture停止及子进程退出。HTTP仅新侧setup owner／错owner／错tenant／缺task四项，不是旧新HTTP原wire比较；旧新owner driver序列化输出原串另存。注册测试worker记录用于手动claim，不启动worker循环。

## 运行与下一片

真实run `01a173ca-c0c5-73d5-85e4-19d2dadc90ac`；只回读PM唯一规则 `01a173cb-8834-73cf-92b9-856bea715785`，同run/parent、completed/failed/cancelled、once、两小时16:01:20.948446Z→18:01:20.946758Z、last_error=null。不重复创建，不恢复旧规则，不预写本run平台终态。

本片完成后下一具体候选是 `JdbcLedger.todoProjection` 完整版本／binding资格及真实读取链：原one完整键→record_version/todo_version→binding摘要→codec版本→todos.binding原串相等；本轮未迁内部。60保持in_progress／PR22草稿，B2／B0–B5未完，最终61/62仍backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read、独立后记、其他真实缺行、自然08S01／709ms／冷路径及全部历史失败缺件继续OPEN。无CI／合master／部署／真实Key计费／共享服务数据／daemon代理FRP操作，63/64/66/70不重接，72固定旧head QA不追新head，T48及新功能暂停。
