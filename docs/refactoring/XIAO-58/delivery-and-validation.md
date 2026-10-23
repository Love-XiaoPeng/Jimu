# 分批实施、验证及交接

本文件是XIAO-60的输入，XIAO-59只交方案。产品经理核定本方案后才激活既有阶段2；XIAO-61独审和XIAO-62 QA保持等待稳定实现。没有自行创建或启动新的实现任务。

## 1. 唯一集成和冲突边界

产品经理负责范围、优先级、验收、合并及接续。现有中级后端（ID `4f506aa6-96e2-4597-a471-e87cbeb15bc8`）为唯一集成人，持有XIAO-60集成分支/PR、POM、bootstrap、事务/query adapter、Mapper/Entity基础结构、JdbcLedger/SessionRecords/Scopes、大类拆分及共享工程门禁的写入权。方案审阅期间由架构师仅写文档分支，交回后不与实现并发编辑这些文件。

每位开发同时一个执行任务，独立worktree/分支，不共享工作树或私有实例。初级切片由产品经理核空闲后派发，中级先从大类抽出目标模块并冻结输入/输出；初级不能直接切同一个JdbcLedger分支。共享 `Payloads`、服务接口、ErrorCode、状态枚举、全局配置和resources冻结目录由集成人串行修改。

| 批次 | 集成人工作和依赖 | 可给初级的独立切片 | 完成标准与交接 |
| --- | --- | --- | --- |
| B0：契约/事实锁定 | 核actual master；保存API/Schema/pins/旧JAR、35表DDL及旧账本；按路径列锁序；建立file/method/SQL追踪表 | 无实现并行；初级可整理不修改源码的API/值索引 | 旧数据/协议比较fixture与原失败已锁；master变化先分析差异而非静默重置 |
| B1：基础装配和内核分层 | 最小MP依赖；同DS SqlSessionFactory/UoW；保留有界socket；先抽Tasks/Worker/Execution/Model/Invocation/Artifact接口与实际impl；迁成本/codec | 中级锁接口后：①`api/readiness`与已有Readiness service目录整理；②插件catalog service目录整理。两片不写bootstrap，由中级装配 | 默认无DB启动/缺资源失败；Enforcer/compile/ArchUnit；旧JDBC暂仅用于尚未迁切片，所有中间版本标过渡、不能总验收 |
| B2：核心账本真实切换 | task/attempt/step/invocation/event/budget/model/worker/artifact及operator Mapper，任务受理和下载业务下沉；跨表一次事务 | 仅已抽出的 `api/task/dto,vo`严格body/游标解析局部整理，接口字节不变；中级保留TaskController及Service准入权 | 真实双workerclaim/fence/重复受理/预算与结算/产物链；同用例只能一个写入实现；移除对应旧SQL，不双写 |
| B3：独立业务切片 | 中级先抽todo、question、directory端口与独立文件；负责跨task计账、恢复、FS/事务边界 | 初级A：todo读取投影Mapper/DTO（`persistence/todo`、`api/todo`）；初级B：已冻结的目录只读VO/严格参数（`api/directory`），或用户问答列表只读映射。修改范围互斥，问答answer/CAS仍中级 | 各片真实库旧快照/损坏/撤权/精确NULL和回归；初级交准确SHA，集成人先集成再接另一片，不让多分支碰共享实体 |
| B4：复杂投影和查询切换 | 中级负责session v1–v4、scope、read证明/计量/deadline；MCP/media/native的持久化、恢复/清理；拆DurableAgent/网关 | 接口稳定后初级只处理各独立DTO/VO、局部code常量与必要断言，不能写whole-scope/事务/资源预算 | query资源不经旧JdbcTemplate；所有35表及receipt真实调用迁完；受影响授权/并发/限额/清理矩阵通过 |
| B5：删除过渡与稳定制品 | 移除旧业务JDBC、临时facade、构造new/强转旧Ledger；全仓值关闭表、明确例外门禁；按模块拆DurableConfiguration | 冻结的模块局部中文说明/文档同步可独立处理；不得修改冻结来源或替另一模块改常量 | 本地必要构建/静态与业务证据齐；一个源码SHA/tree/JAR交XIAO-61与62并行；任何缺陷回原开发，由报告者差异复验 |

批次是同一范围内集成顺序，**不是新增服务或多张并行实现工单的启动授权**。允许每批可编译检查点，最终不允许把未迁业务存储登记债务后宣称整体重构完成。开发无需重复找架构批准普通重命名/映射细节；新DDL/协议/限额/收费或权限变化须交回明确差异。

## 2. 本地门禁和自动架构检查

保留Wrapper固定版本、dependencyConvergence、Spotless/Google Java Format、Checkstyle、SpotBugs、Surefire、Failsafe。Java改动先spotless:apply；以本次模块范围执行实际门禁，缺私有MySQL/POSIX/JDK21等应失败或报告对应阻塞，不把skip当通过。当前AGENTS的verify要求与成员“不重跑旧完整矩阵”的决定结合：使用下面明确的受影响IT清单，保持原verify阶段和未执行项原事实。

| 门禁 | XIAO-60须落实的自动规则 | 负例及验收 |
| --- | --- | --- |
| Service职责 | Controller只依赖发布service接口/api值/HTTP DTO及认证端口；禁Mapper/Entity/Records/ArtifactStore/具体插件/模型客户端/ServiceImpl和线程 | 隔离副本注入Controller访问ArtifactStore或Mapper，ArchUnit失败；移除后正向通过 |
| 内核/插件 | plugin.api/model.api继续JDK叶子；kernel domain/service.impl无Spring/SQL/MyBatis/Jackson/adapter；跨模块只发布api/service | 注入kernel→Mapper、plugin→ServiceImpl、跨模块impl引用均失败；根包和业务slice无环 |
| 实际持久入口 | SQL仅Mapper/XML及列明infrastructure方法；生产业务无JdbcTemplate、NamedParameterJdbcTemplate、DataSource.getConnection、DriverManager、SqlSession手动commit/new管理器 | 业务SQL例外不得整包豁免；operator路径也要扫；ResultHandler/statement配置属指定基础设施类 |
| SQL安全 | XML禁`${}`/SELECT *；所有公共Mapper方法对应表/租户策略；复合键禁ById；Wrapper.select/where不可来自外部字符串 | AST/Mapper XML解析与真实双tenant负测一起验收，文本扫描不能自动证明所有tenant条件正确 |
| 边界完整性 | 每个表Mapper/Entity列覆盖V22 schema；每个旧公开持久方法在迁移追踪表有唯一目标Service/SQL语句/验证路径 | 自动比较35表清单、旧入口与新Mapper加载；不能只查pom是否包含MP |
| 值治理 | 已登记业务枚举/code/limits统一来源；禁止新增裸状态/code及新magic阈值，批准技术格式字节例外按位置登记 | 新增未登记状态使检查失败；不得用全仓数字regex拒绝所有技术字面量 |
| 配置/异常/日志 | 类型绑定、未知/非法值拒绝、无秘密实值、边界安全code；cause仅受限日志 | 无效配置启动失败；SQL/URL/token/prompt/全文不得进入HTTP/SSE或公开日志 |
| 冻结材料 | V1–V22、baseline、旧ADR/verification、session资源、frozen material摘要不变；新说明另加版本 | `git diff --check`/来源manifest对照；不能更新旧摘要“修复”差异 |

推荐构建命令（Linux；Windows用mvnw.cmd，`-D…`整体引号防PowerShell拆参）：

```text
./mvnw -B -ntp validate test
./mvnw -B -ntp verify '-Dit.test=PriorSessionIT,UserQuestionIT,TodoIT,WorkingDirectoryIT,McpResourceIT,ImageIT'
```

上面是基线真实存在的受影响IT入口，不包含下表全部新差异用例。core账本和模型只取下表必要定向scenario，不直接重跑旧DurableExecutionIT/ModelCoreIT完整P2/P3场景。session迁移另有 `tools/*_migration_verify.py`。实施应将本批新增Mapper/事务差异矩阵接入实际Failsafe类或逐个运行原工具，并在最终命令写明真实类名/参数。这个示意不是本轮成功记录，最终命令不得有0测试/静默缺资源。全部模块迁移影响面较广，需一次本批风险矩阵，仍不重跑无变化旧P2/P3全部场景或付费评测。

## 3. 必须在新制品重验的矩阵

以下每组要记录完整命令/退出码、源码/tree/JAR SHA256、DB VERSION/UUID/schema和迁移摘要、协议/账本/清理原始收据；敏感值和正文不得以日志暴露。测试和审查使用独立私有实例，稳定SHA后可并行，不等另一方才反馈。旧证据可以帮助选场景，不能复制成新JAR通过。

| 组 | 迁移触发与必须验证 | 现成入口/需补的最小差异验证 |
| --- | --- | --- |
| V01 依赖/装配 | Java21、Boot4.1.1/MP3.5.17、完整tree与effective POM、同DS连接；durable=false无DB；control/worker缺DB/存储失败 | Enforcer/ArchUnit/启动；本轮CompatibilityProbe仅先验，不代替实际产品Bean装配 |
| V02 schema/旧数据 | 空库V1→22、既有V22；旧v1–v4 task/step/invocation/query/body和PENDING/PURGED；新→旧schema22回退读取 | `prior_session_verify --v21-jar`及session migration工具；本批重点V22无DDL+旧快照字节/NULL/时间/列覆盖，V21升级沿原入口必要一次 |
| V03 核心事务/并发 | 双worker同候选、SKIP LOCKED/fence、重复提交/幂等、取消/到期/提交、revision/同值行数、未知结果不能重放 | 从现有DurableExecutionIT夹具提取本批定向scenario；收集连接/事务/锁图，确认run→budget→task等原锁序。原未知持锁者不能假定已解释 |
| V04 模型/费用 | 原request字节/输入证明/response usage、dispatch前后崩溃、UNKNOWN保留、一次结算、整数精度、profile/pin变化 | 既有model/input-bound/usage夹具的必要切片；paid=false合成HTTP，不真实付费、不新增凭据，不重跑旧完整模型矩阵 |
| V05 API/SSE/下载 | 所有7 Controller路径，严格JSON/额外字段/浮点/重复键/UTF8/大小；旧status/code/body/headers/location；owner先核权、猜ID；Last-Event-ID回放/重复/断线/取消/超时/撤权释放 | 现有HTTP脚本+契约测试；TaskController下沉的capability/resource/budget和download需新旧对照，不能新包Result外壳 |
| V06 插件/网关/AI | 固定manifest/Schema/digest/profile、唯一advisor循环、每response≤4槽、原invocation/step冻结、工具拒绝/取消、版本切换旧pin失权 | contract suite及paid=false HTTP；Gateway拆分必须比对请求/工具顺序和持久原槽，不能仅测mock Service |
| V07 session v1–v4 | read/search/event_trace及跨来源search→read；完整事件/邻接Unicode摘要/公开与嵌套收据/篡改拒绝；whole-scope逐入口、旧来源拒绝、0–8来源及capped | `prior_session_read_verify.py`、`prior_session_legacy_verify.py --case read --case search --case trace`；精确READ before/after commit命中、新fence重放/一次消费 |
| V08 查询累计/截止 | 2秒包括连接/认证后body/SQL/证明/commit，1秒锁；无SQL cause的504/503分类；读4096/字节16MiB/事件1024/正文4MiB/比较/ops/scratch边界N−1/N/N+1 | `prior_session_lock_verify.py`真实持锁对照；新增Mapper result计量/空结果/缓存禁用/commit截止用例。503需真实依赖拒绝证据，DataSource合成只记契约，不冒充HTTP503 |
| V09 授权竞争 | 双tenant/双actor、撤权/installation epoch变动与submit/read/commit竞争；scope revoke/来源过期后summary/history/SSE/model/checkpoint/replay全部停止正文 | 原XIAO-55工具加必要同步barrier；本批至少每种公开/模型入口一次和原事务交错一次；未穷举排列列边界，不能说所有竞争证明 |
| V10 配额/清理 | 默认64MiB真实429；history/todo/directory/image/MCP及metadata+BODY全部副本实计；PENDING/PURGED、UNKNOWN/活写者保留；反复清理释放一次、NULL精确更新 | `session_cleanup_verify.py`、现有todo/directory/media/MCP工具的必要切片；本批迁SQL须重验固定点/cleanup中断后恢复，不能沿旧release收据 |
| V11 FS/原生/MCP/问答 | read观察→write/edit CAS→present发布、路径/namespace、native停止身份和cursor；MCP list/read；question答复幂等/暂停恢复；todo revision | `prior_session_legacy_verify.py --case todo --case questions --case directory --case workspace-mcp`，及image/native受影响片段；不重新验全部工具/网络能力 |
| V12 回退/资源释放 | schema22旧JAR读取新制品未变协议数据、flags off仍whole-scope核验、stop/drain与no late write；SSE/ResultHandler/SqlSession/线程/socket/容器清理 | 私有实例录制完整切换和cleanup；连接断/commit不确定进入原错误边界，不增加自动重试 |

DDL/SQL迁移使事务、授权、配额和清理都受影响，V01–V12必需路径不能用原SHA结果豁免；网络页面投影、原生bundle重建许可证、冻结schema/上游源码的原始验收可按**字节未变**沿用，不重建无变化bundle、不重跑DNS/TLS完整矩阵。驱动/socket截止本身若改，DNS/connect/TLS需对应重验；保持实现不变时也至少重验MyBatis连接获取和超时清理。

## 4. 本次已核查与证据继承

本轮完成全仓静态清单、准确master/tree、官方来源抓取、基线/candidate effective POM与依赖树、候选原Enforcer、隔离Java21 MP装配/复合键语句注入探针。具体实际命令/退出码和限制见 `docs/verification/XIAO-59/README.md`。产品实现、DB业务、上述V01–V12、性能、独审与QA **均未由本轮执行**。

XIAO-55最终独审和QA的六caller、原槽恢复/一次消费、错误映射已接受；QA的原现场长持锁归因仍未知，其结论边界由原独审关闭及产品验收裁定。旧完整verify exit1、TCP预设503断言exit1实际504等历史失败不改；本批没有把probe/模拟DataSource当真实HTTP/MySQL验收。

交接制品至少包含：唯一源码SHA/tree、同一JAR/hash、与基线的file/入口/表/值关闭清单、完整依赖与构建/门禁命令退出码、真实原始证据、冻结摘要对比、所有私有进程清理收据、例外登记/风险/回退指引。独审/QA阻断由原报告者按准确差异复验；版本变化必须重标证据适用范围。

## 5. 风险与退出条件

高风险是事务拆分失去原子性、复合键丢tenant、计量因缓存/类型改变偏差、序列化原字节漂移、NULL不更新、deadline/socket失效、混合旧worker接管以及清理提前释放。对应上表每组验收；任一未完成不作为“后续债务”宣布总重构结束。

XIAO-59退出：方案/工程规范/ADR/来源证据入库，文档PR关联XIAO-59，交产品经理核定。XIAO-58退出：批准范围的真实代码迁完、旧业务JDBC无残留、必要本地检查/独审/QA同版无阻断、产品验收合并并核actual master/tree后才完成和恢复功能路线。没有新增部署、真实Key/付费、生产数据、共享服务/daemon/代理/FRP的授权。
