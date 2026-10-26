本批从 `77e48a1a1d03b3849786c3d2edadb1aec9bb3ad8` / tree `436a4e4a1ebd5eacbe950191f7e977a03914eda2` 接续原 PR22，关闭图像、MCP、执行scope三个兼容门面及真实调用者接线。整体重构仍未验收。

## 调用者与删除依据

| 原门面 | 实际调用与现在的直接路径 |
| --- | --- |
| JdbcImageRecords | MapperTaskAdmissions.insertImages → imageService.insert；MapperMaterialEvidenceRecords 的 snapshot/step/object → imageService，逐次使用本视图 imageRecords.bind 原Map。公开七入口本来就直连同一个Service，未重迁业务。原 raw/reserve/succeeded 等门面方法无独立调用者。 |
| JdbcMcpRecords | MapperTaskAdmissions 的 enabled/bind、MapperTaskCapabilities.enabled、MapperTaskBaseAccess.binding 分别直连原 mcpRecords 或 mcpService；binding 在原位置执行一次 records.bind(row)。公开设置与 queryView 配置捕获/应用直连原 mcpRecords，仍传同一settings引用。静态 unauthorized/corrupt 工厂无真实调用者，随层删除；Service原错误构造不变。 |
| JdbcExecutionScopes | Ledger候选/完整fenced、MapperTaskAdmissions及既有scope夹具的 targetRun 直连原 scopeConsumption。needed/requirement/businessAnchor/require/matches 的门面方法已无实际调用；准入/资格早已直连消费Service及原 MapperExecutionScopeConsumption 的窄wrap/unwrap，保留此codec边界。 |

准确基底及新源码的生产、测试、反射类型名和字段名检索与原调用图均纳入审计。三个类删除，未新增等价转发类。只提升原Service/Mapper绑定字段为包内可见以服务同包真实调用者，仍由每个Ledger/派生query视图各自装配；没有暴露新的公共API或共享可变全局状态。

构造零业务预读；bind仍只是持原Map的窄类型包装，不复制、枚举、缓存或提前取字段。材料Row先按原位置unwrap，再一次bind，再进Service，保持NULL/字段首错、重复访问、原异常实例与参数顺序。配置引用捕获和应用时点、公开三构造/配置API保持。所有Service/impl、Mapper SQL/API/投影、事务/query基础设施及bootstrap原blob冻结；115独占配置拆分，本批未编辑其生产或测试文件。

`tools/xiao60_question_visible_verify.py` 只删除基底已有的最后一个多余LF；本批与master到本分支的 `git diff --check` 均通过。历史原失败不改写。

## 验证与证据

必要本地门禁沿verify生命周期：

```powershell
$env:JIMU_IT_JAVA='/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java'
.\mvnw.cmd -B -ntp verify '-Dtest=FacadeCallersTest,QueryViewAssemblyTest,ImagePersistenceServiceTest,McpPersistenceServiceTest,ExecutionScopeConsumptionServiceTest,ExecutionScopeConsumptionMapperTest,MaterialEvidenceServiceTest,PackageBoundariesTest,PersistencePathTrackingTest' '-Dit.test=FacadeCallersIT'
```

90项定向检查涵盖原图像/MCP/scope业务及材料适配、query配置引用与独立所有权、Mapper/架构门禁。新增3项仅核改线风险：Service到达前不碰Map、重复访问实读原Map、NULL字段失败和异常实例原样传播；没有按实现逐行复制测试。

有界私库差异复用MaterialEvidenceScenario的合法图像/材料夹具：6个受影响来源调用（成功、重放、内部、重复引用、旧fence、自然撤权），加mediaHistory内已有的3个请求投影/配置/Schema检查，实际每侧9调用。必要合法准入/登记、lease/图像结算与原step/dispatch证据只用于setup；不执行Host工具/provider，不重跑旧23调用材料或图像/MCP整矩阵。两侧运行同一份归档测试class，分别加载准确77e48a1与本批原JAR；生产Service/SQL冻结证据与已有独审/QA按适用范围复用。逐调用SQL、bind、原字段、异常链、同连接session恢复及全私表外层恢复/进程清理分存；不冒称逐调用持久提交、真实worker运行或新独审QA。

最终准确SHA/tree/JAR、命令退出码、SpotBugs、IT、原失败（若有）及实际SQL计数以随附认证manifest、receipt和原日志为准。开发门禁与原JAR绑定，不用后打包制品替代。

首轮90定向与SpotBugs0通过，私库旧新生产调用、session恢复和36表外层恢复/清理均完成，但核验器误期6调用、漏计mediaHistory内3调用，完整verify仍exit1。先封存本轮源码/class/原始日志，再修正为实际9调用并核各返回来源/字段摘要及完整参数关系；离线重算每侧9调用/145 SQL/326bind/220关系通过。随后同一原JAR独立定向IT单独复验，结果分存，不把完整verify改记成功。

最终 `failsafe:integration-test failsafe:verify -Dit.test=FacadeCallersIT` exit0，1IT通过，每侧实际9调用/145 SQL/326bind/220参数和结果关系，同连接session恢复、36表含worker_process外层恢复及完整清理通过。受测原JAR44,980,734字节，SHA256 `a9ff82854a6dec8bc6b8809b2f798e0cf7bc21daf50bad4261c3a977176913a2`；第二轮未重新打包。三个已删除门面class在原JAR中均不存在。

API审计首次误把压缩追踪图未收录的三个公开构造当作新API而失败；原失败日志保留。修正后逐文件仅允许明确接线替换、另核三个原构造，公共方法集合不变，所有受影响文件全正文匹配原文加授权替换。443方法正文及1099生产/POM blob冻结、15方法仅接线替换通过。只读命令曾有PowerShell花括号路径语法和不存在的文档名诊断，不计构建/业务失败，未声称每份只读stderr另行封存。

## 有依据的关闭清单

`evidence/facade-closure.json.gz` 为当前逐项覆盖层：36行逐表列真实Mapper语句、适用原增量文档及其Git提交/摘要、明确剩余验证ID。历史 `b2-table-status.csv` 和旧检查点的全OPEN是当时事实，保留原字节；不能继续当作当前36张表均未迁移。

| 范围 | 当前源码及证据结论 |
| --- | --- |
| 原生2表；图像2表；MCP4表；问答1表；todo1表；目录/路径2表；artifact、worker各1表；execution_scope及外部receipt各1表 | 实际业务Service/impl、惰性port和真实Mapper已交；引用各自生命周期、计费、清理及资格增量。无本批新增表级缺口，复用未变证据，不宣称物理文件/进程证明。receipt只读及0/1/多行语义保持，不由本仓库供应。 |
| 核心task/attempt/step/invocation/event/model_dispatch、actor_access/installation、tenant/evaluation预算及reservation | 业务迁移已交；G01为core最终beforeCommit截止/回滚的具体缺证，G02/G03仅绑定相关session结算/事件/计费路径。不是整表未实现。 |
| session历史/公开事件/原生read/search/trace收据、scope/target/step/query收据共9表 | 业务迁移已交；G02完整成功scoped工具read结算，G03持久FAILED-read及独立afterCompletion后记/去重仍缺明确证据，直接native read或外层rollback不能替代。 |

剩余 **9静态/22动态/0静态bind，SQL消除0**。9静态及8动态是必要基础设施：原同连接session变量保存/设置/恢复、schema22/外部receipt元数据scalar；另5固定Mapper入口和9内存值访问误报。各API及执行正文不为计数归零改动，详细清单复用原精确位置/分类并绑定冻结blob。

| 阶段 | 已交与实际剩余 |
| --- | --- |
| B0 | 基线、冻结资源/协议、file/method/SQL/表追踪已交，本批刷新图并补逐表覆盖层；最终稳定候选统一核对仍需PM收口。 |
| B1 | 同DS/manager/UoW、真实MP、核心Service/impl、显式query及独立视图装配已交；G01最终beforeCommit尾部缺证。 |
| B2 | 35管理表＋只读receipt真实迁移已交，必要基础设施明确例外；G01/G02/G03及最终同版独审QA尚待收口，不能凭计数宣称B2验收。 |
| B3 | todo/问答/目录完整用例和HTTP局部切片已交，复用准确原证据；无已知本批新增实现缺口，等稳定候选验收。 |
| B4 | session/media/MCP/native及网关拆分已交；仅具体G02/G03缺证继续追踪，不重做已交规则。 |
| B5 | query根装配/Core层、三个兼容门面已关闭。115配置拆分仍在实施；全仓业务值/配置归属关闭表仍待真实源码治理。公共Ledger构造、SessionRecords/Scopes中的实际codec及基础设施职责不能按名字删除。 |

G01–G03严重度为高风险验证缺口，责任A补最小路径、原报告者差异复验；没有已证实产品缺陷。最小下一完整验证组：一个真实最终beforeCommit边界、一个合法scope工具read到原槽结算、一个无外层试验事务的自然拒绝后独立连接读取FAILED/note/去重。不得重放UNKNOWN、跑旧整矩阵或访问真实provider。

G04由115中级B独占，独审/必要QA接受准确补丁后A串行接入。G05由A按PM后续完整合同核当前 `magic-values.md` 归属和实际值；例如NativeResults 4MiB/64KiB与SessionSearchMatcher比较预算仍需具名冻结值治理，协议/图片格式技术值不能机械改配置。G06由PM在G01–G05收口后锁完整候选，激活61/62；本批不预报整体完成。

历史自然08S01/约709ms、未知持锁者/完整锁排列、v1/503/后继dispatch失败保留为诊断边界。未确证本次回归，不因未复现而扩为36表迁移阻断，也不伪称已通过。下一具体工作以上述具名缺证及值治理为依据。

60保持in_progress、PR22 draft；master未合并，代码卡最终master推送读回前不done。无CI、部署、付费、共享服务或T48操作。
