# XIAO-120 业务值与配置归属关闭表

基底为 `26f1c23be0c155c9b9c69126e66f9dbf394bc946`，tree `b42da2043ae73bb946db9bf25b2b3f5d36f66145`。这是独立分支的 B5 实施记录，不代表 PR22/master、XIAO-115 或总重构已经验收。下面按 `docs/refactoring/XIAO-58/magic-values.md` 的全部类别核对当前生产源码；旧 3,692 个词法候选没有被当成缺陷清单或重新标为 OPEN。

Java 路径以 `src/main/java/io/github/lovexiaopeng/jimu/` 为根。**代码关闭**表示补齐实际调用的具名值；**冻结关闭**表示当前单一协议/SQL/资源校验来源有保留依据，不新造可漂移的平行来源；**受信配置关闭**表示复用当前配置与校验，不启用推荐的新配置名。表中的关闭均是本次开发核对，仍待独审和必要 QA 接收。

## 状态、错误码、能力和版本

| 原类别 | 当前实现/关闭依据 | 结果 |
| --- | --- | --- |
| task 状态、终态与可清理状态 | `kernel/task/api/TaskState`；`Tasks.View.terminal`、`TaskOperationsServiceImpl`、worker、各清理用例仍保留原来各自的有序集合。终态含 NEEDS_RECONCILIATION，可清理集合仍只有 SUCCEEDED/FAILED/CANCELLED，未扩大清理资格。 | 代码关闭 |
| invocation、step、attempt、dispatch | `InvocationState`、`StepState`、`AttemptState`、`DispatchState` 分别归其模块 API，供现有 String 边界引用；SQL 固定状态保持。MCP audit 的 RESPONDED/FAILED 独立为 `McpAuditPhase`，不混入 invocation。 | 代码及冻结关闭 |
| reservation category/state、usage/cost quality | `kernel/budget/api/{ReservationCategory,ReservationState,UsageQuality,CostQuality}`，包括 MEDIA_INPUT/CHECKPOINT、SEARCH_STORAGE、QUESTION_TASK 等实际类别。`ModelCostSettlement` 的 UNKNOWN 仍为 CostQuality，预留行的 usage quality 仍为 UsageQuality；未知费用没有变成零。 | 代码关闭 |
| session content/outcome/grant/operation | `kernel/session/api/{SessionContentState,SessionOutcome,SessionGrantState,SessionScopeOperation}`。grant、content、失效时间继续独立；数据库 REVOKED 等固定 SQL 原字节不动。 | 代码及冻结关闭 |
| todo、question | `TodoContentState`、`QuestionPhase`、`QuestionDeliveryState`、`QuestionContentState`、`QuestionCloseCode`。todo pending/in_progress/completed 仍在 `TodoContracts.input` 的唯一严格输入 switch 和受 pin 的 Schema 中；未知状态仍失败。关闭原因 CANCELLED 与任务状态分别具名。 | 代码及冻结关闭 |
| directory、MCP、native | `DirectoryContentState`、`DirectoryWorkspaceState`、`DirectoryOperation`、`NativeSearchPhase`、`McpAuditPhase`。V18 的 workspace/content 仍分开，PENDING/PURGED 组合未收紧。数据库 mutation 中的固定 phase/state 没有动态拼 SQL。 | 代码及冻结关闭 |
| TaskFailure/FileFailure/公开错误码 | 模块 `api/*ErrorCode` 和叶子 `plugin/api/*ErrorCode` 供原调用方直接引用；公共认证/请求/基础设施码在 `kernel/shared/api/CommonErrorCode`。插件不依赖 kernel。报告动态失败仍拼接原 REPORT_DRAFT_ 与具名 `ReportDraftReason`；HTTP 映射及安全 message 原样。 | 代码关闭 |
| capability ID、插件 ID、版本、digest | `plugin/api/ToolCapability` 是现有语义能力名来源，包含实际 verb-first MCP 名称；各 Contract.NAME 保持原公开字段。版本、entry class、URN、catalog、Schema、digest 仍由既有受检资源及 pin 核验承担，不复制版本/digest 注册表。冻结 bootstrap 的注册/装配正文未改。 | 代码及冻结关闭 |
| session/matcher/normalizer/projection 协议字符串 | `SessionEventSearchPolicyV1` 和 `PriorSessionPolicyV4` 分别拥有原 content/match/ordering；`ImagePolicyV1` 拥有原 normalizer/projection 字符串。ModelProfiles 的 local-session-* 是原 test-mode fixture ID；UTC、UTF8、MCP 字符集/日期格式为编码规范。 | 代码及冻结关闭 |
| BusinessPolicies/EvaluationPlans/ExecutionScopes | 复用当前不可变、受信绑定和冻结资源；`ExecutionScopeApprovalV1` 仅命名原审批 63 requests/500,000,000 微单位边界，不是运行时可调限额或付费授权。材料摘要、版本和准入顺序不变。 | 受信配置及代码关闭 |

现有 String API/Entity/JSON 没有改为隐式枚举序列化。具名类显式保存旧字符串，不使用 ordinal、简单类名编码或 UNKNOWN fallback；本批没有增加类型转换层、Service 转发层或 Bean。

## 冻结限额

| 原类别/单位 | 当前来源与处理 | 结果 |
| --- | --- | --- |
| 普通受理 6,291,456 B、goal 8,192 UTF8 B、幂等 key 1–128 ASCII | `TaskRequestDecoder.MAX_BODY_BYTES` 已具名且仍读取 N+1；复用 `TaskExecutionLimitsV1`，新增原默认费用/令牌具名值；key 的原 `[!-~]{1,128}` 是严格协议 grammar，保留。 | 既有及代码关闭 |
| deadline 180 s、8 模型、20 工具、7 能力、response 4 槽 | 复用 `TaskExecutionLimitsV1` 并去掉受理中的重复 180；`SessionHistoryPolicyV1.RESPONSE_CALLS` 是会话 4 槽，`ModelResponseLimitsV1` 保留另一普通响应的 8 槽/32KiB，不合并。 | 代码关闭 |
| session 查询 2 s、锁等待 1 s | `SessionHistoryPolicyV1.QUERY_NANOS` 用于原 HTTP 和 scope maintenance；事务/query 基础设施的绝对 deadline、驱动 socket、锁等待正文保持基底。没有每阶段重新给 2 秒。 | 代码及冻结关闭 |
| v4 受理 16,384 B、search 4,096 B、旧 search 2,048 B、trace 1,024 B | 分别由 PriorSessionPolicyV4、SessionEventSearchPolicyV1、SessionTracePolicyV3 供 Controller、严格解析和 BodyFilter 引用。认证/路径核权仍在 body 消费前。 | 代码关闭 |
| 0–8 来源、每源最强 1 条、最多 4 代表 | `PriorSessionPolicyV4.SOURCES/RETURNED_SOURCES`；每源最强仍由原 best map 和比较器实现；输入来源数仍受冻结 Schema 校验。 | 代码及冻结关闭 |
| 累计事件 1,024、正文 4,194,304 B、证明 4,096 次/16,777,216 B、scratch 16,777,216 B | `PriorSessionPolicyV4`、`SessionScopeExecutionServiceImpl`、`JdbcSessionRecords.SearchBudget` 引用同版本政策，含前半核权/未命中计量。旧搜索证明 1,024 次/8,388,608 B 与 trace 1MiB scratch 分别归旧版本政策。互斥的 `SessionQueryContext` 执行正文保持基底原文，其相同原限额由冻结关闭处理。 | 代码及冻结关闭 |
| 匹配比较、操作与截止 | 跨会话比较 **16,777,216 次**/操作 2,097,152 次归 PriorSessionPolicyV4；旧公开搜索比较 **8,388,608 次**归 SessionEventSearchPolicyV1。各自保留原 1,024 步 mask 检查，预算 429 与时间 504 次序不改。 | 代码关闭 |
| snippet 80/240 码点、JSON 1,536 B、hit 2,048 B、search/read 输出 | 两版 matcher 各自完整政策注明码点/序列化字节；旧 search 输出仍 32,768 B，v4 search 16,384 B，read 32,768 B。Unicode 及 JSON 转义算法不变。 | 代码关闭 |
| read 前后 0–4、refs≤9、公开 seq 0–127、receipt 1–256 | `SessionHistoryPolicyV1` 的输入/事件/前缀/末序号供调用方引用；refs 与 receipt 数值由原受 pin Schema 和固定 DDL 管理，没有新增可漂移平行值。非终态 126 与终态 128 的事件容量分别具名。 | 代码及冻结关闭 |
| 会话预留 8MiB、BODY≤7,340,032 B、metadata 65,536/61,440 B、四轮固定点 | Java 预留、回收差额、metadata、cleanup headroom、32 B 行头/8 B 字段头归 SessionHistoryPolicyV1。SQL 的 BODY 约束保持。四轮、表序、逐标量算式及首错完全保留。 | 代码及冻结关闭 |
| session/directory 7d、最早来源截止 | 这些持久 retain_until 的固定 SQL/已有时间计算不改，不引入新开关扩大旧任务。问答已有 Java 604,800 s 被单独具名，不与会话保留策略混用。 | 冻结及代码关闭 |
| todo 2MiB、directory 8MiB、清单 8,192 B、最多一个 in_progress | TodoPolicyV1、DirectoryPolicyV1 供受理/预留/校验/释放引用。目录正文原额外 2MiB 上限独立名为 BODY_STORAGE_BYTES，未误换成目录 8MiB 总预留。metadata 开放/关闭阈值按各模块分别归属。 | 代码关闭 |
| web_fetch 30,000 ms/1MiB、health 2,000 ms | 既有 manifest 及 BuiltinManifestLoader 的严格 expected 值负责资源真实性核验。expected 是冻结资源的校验断言，不是另一可调配置；manifest/pin/版本不改。 | 冻结关闭 |
| 图片 1MiB、4,194,304 pixels、2048 长边、8192 宽高、64KiB ICC | ImagePolicyV1 贯穿 TaskImages snapshot、ImageNormalizer、ImagePersistenceServiceImpl、ImageRuntime；归一化像素原 **1,048,576** 与原图像素 **4,194,304** 分别保留。PNG/JPEG/ICC/EXIF 标志、UTF8 位运算仍为技术字节。 | 代码及技术关闭 |
| 评测审批 | ExecutionScopeApprovalV1；审批主体字符串长度、requests 与费用单位明确。已有 plan、价格、授权版本与只读运维边界保持。 | 代码及受信配置关闭 |
| 补缺：完整 native 结果 | NativeResultPolicyV1 分别命名收集条目/总 document **4,194,304 UTF8 B**、page **65,536 UTF8 B**、glob 100/grep 250、分组采样 100、path preview 2048/line preview 2000 UTF8 B、16 随机 cursor bytes、原 20,000,000 raw stdout bytes 与 parser 字符/文档/depth 限制；同时替换读回与存储预留调用方。 | 代码关闭 |
| 补缺：MCP/报告/问答重复值 | McpResourcePolicyV1 明确区分 page/binding/read output、wire、资源解码、列表/游标/快照计量；ReportLimitsV1 复用原报告上限并区别 UTF16 文本；QuestionPolicyV1 贯穿 binding、HTTP/内容/默认超时与存储。 | 代码关闭 |

## 部署参数与数据库政策

| 原类别 | 当前处理与依据 | 结果 |
| --- | --- | --- |
| worker poll 100 ms、maintenance 10 s | WorkerLeasePolicyV1 具名原调度值，构造、单 worker 并发和调度时点不变；本轮未启用推荐的新属性。 | 代码关闭 |
| SSE poll 100 ms/delay 50 ms/lifetime 25 s/emitter 30 s | EventFeedPolicyV1 同供原 DatabaseEventFeed 和 Controller；原释放一次的 AtomicBoolean/回调正文不动。 | 代码关闭 |
| SSE 32 总/2 每 actor-task/64 batch | 同一政策贯穿有界线程池、接纳与读取；TaskOperations 原分页上限保留同值。没有引入无界 executor 或扩大容量。 | 代码关闭 |
| transaction 10 s/query 5 s，session 2 s | 冻结 transaction/query 基础设施已有窄类型、deadline 与超时政策；没有开放新配置、改事务正文或让通用超时覆盖 session。 | 冻结关闭 |
| lease 30 s/heartbeat 10 s | WorkerLeasePolicyV1 命名 heartbeat；数据库 TaskMapper 的固定 INTERVAL 30 SECOND 在 claim/renew 中原字节保持。lease 不外置、不热切。 | 代码及冻结关闭 |
| cleanup 64 artifacts/32 images/8 scopes×8 targets/60 s | 当前参数化 Mapper/XML 的固定批量、keyset 和下一验证 SQL 为权威来源；未改 SQL 或增加动态模板/配置。全 scope 的原 2 s envelope 未重置。 | 冻结关闭 |
| queue/running/model/tool/token/storage 租户政策 | V1 tenant_budget 及当前显式租户行控制容量。没有给公共运行配置添加覆写入口。 | 数据库政策关闭 |
| history/directory/todo/question 租户政策 | V16–V19 及既有容量行负责；本批未改 DB/私库夹具，也未将过去私有容量结果改写成默认 64MiB 验收。 | 数据库政策关闭 |
| 模型 timeout/response/max tokens 等 | ModelProfiles 已用严格 Binder、NoUnboundElementsBindHandler 和不可变 snapshot，当前字段和读取时点不变；受信 BusinessPolicies/plan 仍校验版本、摘要和准入。 | 受信配置关闭 |
| feature flags 默认 false、依赖关系 | 原属性名、默认值、读取时点保持；冻结装配/四职责绑定由 XIAO-115 交给 A 接入。本任务没有重做 115，也没有把推荐配置名写入 application.properties。关 flag 不被当成撤销 scope/清理授权。 | 冻结交接关闭 |

## 保留的技术/协议例外与未完成交接

`source-equivalence.json` 对全部修改过的既有生产 Java 做 token 比对：只忽略 import、注释和格式，展开具名常量，并折叠已列出的编译期算式/数值转换；完整字符串（包括 SQL）仍参与逐字节比较。它不证明新类型归属正确，须结合上表和独审判断。新增 TaskExecutionLimitsV1 的三个具名字段单独允许；其值也参与调用方展开比较。

未修改的技术/冻结字面量有明确归属：HTTP 状态码、SQL 文本/参数形状、JSON 字段名、正则 grammar、UUID/日期/时区、PNG/JPEG/EXIF/ICC 标志、UTF8/摘要位运算、纳秒换算、原生 fd/权限位、JSON-RPC 协议错误、网络协议端口，均保留在实际边界。`BoundaryProbe` 的 QUESTION_*/S_*/COMMIT_* 等为现有受控测试注入点，不能当公开业务 code。DurableAgent 的 STAGING/DONE 是该 adapter 自己的冻结 checkpoint phase；SecureWorkspaceStore 的 READ/WRITE/EDIT/PRESENT 是其持久 operation grammar，均不与同拼写任务/能力值合并。MCP 的固定 cursor 占位串用于最坏封装大小证明，不是用户凭证。

| 真正尚未完成的事项 | 具体位置/原因 | 负责人及最小下一步 |
| --- | --- | --- |
| XIAO-115/G01–G03 接入与同版重核 | `bootstrap/DurableConfiguration`、115 四职责配置、LedgerPersistenceConfiguration、事务/query 执行正文及 Ledger 构造接线本轮保持；未把另一分支代码叠入本分支。 | 唯一 A 按既有授权接入 115/G01–G03，再串行接本分支并读回准确 SHA。没有需要 B 代改的冻结文件缺件。 |
| 本批独立审查/必要 QA | 开发测试和源码等价核对不替代独审/QA，尤其须核模块语义归属、两个搜索版本的预算以及封装字节边界。 | 原流程由 PM 接收并分配；以交付 SHA/tree/JAR 为对象。 |
| 最终集成/master | 本分支交回并保持 in_review，尚未写 PR22/master。 | A 串行集成，PM 验收；最终 master 前不标 done。 |

本授权独立生产代码范围没有另留未经实施的“扫描候选任务”。上述交接项不等于独审/QA 已通过，也不恢复 T48、CI、部署、共享服务变更或真实模型调用。
