# 业务值和配置治理

固定基线同总方案。机器索引 `literal-candidates.json` 覆盖所有生产Java中的大数字/语义字符串候选；它也含SQL、技术字节和测试hook，**不等于3,692处都要替换**。按下表处理业务值，剩余技术字面量在原位置说明；实施交回逐项关闭记录，不能用扫描数量下降替代行为验收。

## 1. 状态、code、身份和版本

| 实际来源（Java根包内） | 有业务含义的值 | 目标归属和兼容规则 |
| --- | --- | --- |
| `kernel/task/api/Tasks.java:59`；`JdbcLedger.java:5589,8451` | QUEUED/RUNNING/WAITING_USER/RESUME_READY/SUCCEEDED/FAILED/CANCELLED/NEEDS_RECONCILIATION | `kernel/task/domain/TaskState`，显式dbValue/wireValue。终态与可清理状态分别命名集合；保留NEEDS_RECONCILIATION仍不可自动清理的差异 |
| `JdbcLedger.java:7247,7487,7583` | invocation状态、OUTCOME_UNKNOWN；step、attempt、model_dispatch各自状态 | 分别 `InvocationState`、`StepState`、`AttemptState`、`DispatchState`，从原方法/DDL完整提取，不共享万能Status |
| `JdbcLedger.java:5504,7089,8533` | reservation category/state、KNOWN/UNKNOWN、actual/reserved等quality | `kernel/budget/domain/{ReservationCategory,ReservationState,UsageQuality,CostQuality}`，字符值原样；未知费用不当作0 |
| `JdbcSessionRecords.java:128,146`；V19–22 | PRESENT/PURGED、PENDING/SUCCEEDED/FAILED、ACTIVE/REVOKED、READ/SEARCH | `kernel/session/domain`对应状态；scope失效时间与grant_state独立，不能合成可恢复状态 |
| `JdbcLedger.java:2465,3206,3511`；V16/V17 | UNWRITTEN/PRESENT/PURGED；question phase/delivery与todo pending/in_progress/completed | 各模块枚举；不把UNWRITTEN转空清单，把未知版本降级为默认值 |
| V18、`DirectoryContracts`、V15、V11 | directory workspace/content、MCP phase/result、native search phase | 模块枚举，保留DB CHECK允许值；PENDING/PURGED组合仍合法 |
| `api/TaskErrors.java:28`、所有TaskFailure/FileFailure/ToolResult code | INVALID_REQUEST、FENCE_REJECTED、SESSION_QUERY_TIMEOUT/UNAVAILABLE、CAPABILITY_REVOKED等 | `kernel/shared/api`只放公共code，各业务API放本业务ErrorCode；HTTP映射集中api/common；code/状态码/失败顺序及安全message不改 |
| `TaskController.java:103`、`DurableAgent.java:310`、各catalog/manifest | web_fetch、session_event_read/search/trace、session_search、各workspace/MCP/todo能力；插件ID/版本/digest | 语义能力名常量/经验证descriptor；版本/digest以资源权威源读取，不能复制一套可漂移表或改变旧URN/entry类名 |
| `ModelProfiles.java:180`、`PriorSessionMatcher.java:117`、各binding | local-session-*、public-content-literal-v1、public-v1、scoped-admission、UTC/UTF8、排序字符串、snapshot版本 | 对应 `*ContractV1/V4` 常量及配置profile ID，原字节和值保持；版本是协议值，不是任意可调配置 |
| `BusinessPolicies`、`EvaluationPlans`、`ExecutionScopes` | 模型revision、价格版本、qwen-main-v1、授权阶段/区域/收费scope、材料摘要 | 保持已有受信版本配置/冻结资源；不是通用代码常量库，不把真实付费准入改成默认放行 |

枚举转换在边界显式完成，DB仍存旧字符串，JSON仍输出旧值；不使用ordinal或简单类名默认编码。内核枚举无MP/Jackson注解；适配器String字段或窄TypeHandler显式映射，未知存储值按原版本/损坏错误失败，不能fallback为UNKNOWN。SQL里的固定状态允许作为固定SQL的一部分，但Java状态集合和Mapper参数共用同一语义来源；静态检查防未登记的新业务值，不生成`${state}` SQL。

`symbol-values.json` 列出生产Java中所有独立大写语义字符串及引用位置，作为code/状态迁移核对底稿；一项值可以有多个模块语义，不能按相同拼写强合并。0/1、数组索引、UTF8位掩码、PNG/JPEG/EXIF标志、SHA-256算法结构、纳秒转换算式不机械造常量；`ImageNormalizer.java:75,180,397` 的格式标志属于技术规范，不是业务魔法值。

## 2. 冻结限额（本批保持现值）

这些值与schema/checkpoint/pin/资源证明有关，目标为模块内具名 `*LimitsV1` / `*PolicyVn` 常量，不能只改application.properties。资源Schema/manifest及历史DDL原字节保持。需要放开限额必须新契约/策略版本、兼容/计账/回退验证及父项范围决定。

| 现值和单位 | 基线位置 | 目标名/校验/冻结规则 |
| --- | --- | --- |
| 普通受理body 6,291,456 B；goal 8,192 UTF8 B；幂等key 1–128可打印ASCII | `TaskController:74,106` | `TaskHttpLimits.MAX_BODY_BYTES`、TaskAdmissionPolicy；读取N+1拒413，goal校验在Service，空images仍原错码 |
| 180 s、8模型、20工具；公开能力最多7；每response最多4槽 | `TaskController:129,160`、`DurableAgent`/`JdbcLedger`接纳路径 | `TaskExecutionLimitsV1`，budget值≥1且≤原上限；写入任务后冻结，不随启动配置改变旧task |
| session search/read全程2 s；锁等待1 s | `JdbcLedger:197,243`、`SessionSearchController:53` | `SessionQueryPolicyV1`；统一绝对deadline，不对每阶段重新给2秒；语句timeout取剩余时间，deadline不延长 |
| v4受理16,384 B、search body4,096 B；旧event search2,048 B、trace1,024 B | `SessionSearchController:55,86`、`SessionHistoryController:56,91`、BodyFilter | 各版本HttpLimits；认证/路径核权先于body消费，负向请求保持原错误次序 |
| 0–8来源、每来源最强1条、最多4代表 | `PriorSessionContracts`、`PriorSessionMatcher:109,124`、V22 | `PriorSessionLimitsV1`；不是分页或全owner授权；source种类不扩大 |
| 总事件1,024、正文4,194,304 B、证明读取4,096次/16,777,216 B、live scratch16,777,216 B | ADR-0014、V22、`SessionScopeQueryBudget:39,55`、`JdbcSessionRecords` | 全查询累计，包含未命中和前半授权；不能改成每源上限。scratch按存活内存单独验证 |
| 匹配比较16,777,216次、操作2,097,152次，每1,024步检查截止 | `PriorSessionMatcher:46,54`、`SessionScopeQueryBudget:31` | 比较/操作计数分别命名，不混同16MiB；预算耗尽原429、时间耗尽原504 |
| snippet前80/窗口240码点，序列化≤1,536 B；hit≤2,048 B；search输出16,384 B；read输出32,768 B | `PriorSessionMatcher:89,99,130`、SessionContracts | 命名注明codepoints/serialized bytes；Unicode与JSON转义实测，不能用String.length代替 |
| read前后各0–4条、直接refs≤9；公开seq0–127、receipt seq1–256 | `SessionHistoryController:149`、V19–22 | `SessionReadLimits`，不把邻接摘要hash当完整事件hash；旧收据不递归展开 |
| 会话task预留8,388,608 B，BODY≤7,340,032 B；metadata关闭≤65,536 B/开放≤61,440 B，固定点最多4轮 | `JdbcSessionRecords:250,276,326`、V19 | 固定点和cleanup headroom需逐列原标量复验；32B行头/8B字段头及各表余量属于计费协议，保持实值 |
| session/目录保留604,800 s（7d），scope取所有来源最早期限 | `JdbcSessionRecords:241`、V18/V19、V22 | `RetentionPolicyV1`；不因配置扩大已冻结retain_until；不提前释放UNKNOWN/活写者 |
| todo预留2,097,152 B、目录预留8,388,608 B；todo列表8,192 B/最多1个in_progress | V17/V18、`TodoContracts:160` | 各模块Limits；精确对齐旧constraint和清理，不与history预留混合 |
| web_fetch 30,000 ms/输出1,048,576 B、health2,000 ms | `resources/plugins/builtin/webfetch/plugin.yaml` | 使用已验证manifest值；不得只把Java常量改大而不改变插件版本/pin |
| 图片MAX_BYTES1,048,576、MAX_PIXELS4,194,304、归一化长边2048、宽高≤8192、ICC≤65,536 B | `ImageNormalizer:33,134,170,224` | 命名标注pixels/bytes；沿已有公开image contract与normalizer version，不把格式技术标志列为业务配置 |
| 评测scope最多63 requests、500,000,000费用微单位等原审批约束 | `ExecutionScopeBinding:80`、冻结plan | 不作为随意runtime限额；保留原审批/plan来源，无新增真实付费动作 |

## 3. 可调部署参数与数据库政策

配置用bootstrap `@ConfigurationProperties`（或当前严格Binder等效）、构造校验和不可变值传入内核；引入Bean Validation时通过Boot4 validation starter和受管版本，不自写一套注解框架。拒绝未知字段/负值/溢出。下表是迁移后的**推荐配置名**，本轮未启用；现有配置名继续绑定，不能让运维无声失效。时间统一Duration，接口预算仍沿秒/毫秒原字段。

| 推荐归属 / 默认 | 现来源 | 校验及是否影响旧任务 |
| --- | --- | --- |
| `jimu.worker.poll-interval=100ms` | `WorkerLoop:56` | 10ms–1s，启动冻结；仅调度，不增加同worker并发 |
| `jimu.maintenance.interval=10s` | `LedgerMaintenance:31` | 1s–60s；入口仍即时核权，后台间隔不是公开宽限 |
| `jimu.sse.poll-interval=100ms`、initial-delay50ms、lifetime25s、emitter-timeout30s | `DatabaseEventFeed:95`、`TaskController:245` | poll10ms–1s；0≤delay<lifetime<emitter-timeout≤30s；onCompletion/onTimeout/onError均只释放一次 |
| `jimu.sse.max-subscriptions=32`、per-actor-task2、batch64 | `DatabaseEventFeed:41,64` | 1≤perKey≤total≤32，batch1–64；允许收紧资源，不扩大本次容量承诺；executor不能无界 |
| `jimu.ledger.transaction-timeout=10s`、query-timeout5s | `JdbcLedger:4229` | 1s≤query≤transaction≤10s；session2s上界另行覆盖，不能靠通用配置延长 |
| lease30s / heartbeat10s | `JdbcLedger:5758,6202`、`WorkerLoop:72` | 此次提为具名WorkerLeasePolicyV1，**配置只接受30s/10s**；更改需独立策略/接管验证和全部旧worker退出，不能热切 |
| cleanup每批：artifact64、images32、scope validation8 scopes/每scope≤8 targets，validation下次60s | `JdbcLedger:1548,8656,8723`、V22 | 命名BatchPolicy，配置仅可收紧1…现上限；同一2s总预算不重置；保留公平keyset推进 |
| queue16/running2、model128/tool320、token524,288/storage16,777,216 B | V1 tenant_budget | 数据库租户政策，默认/检查不变；不是公共配置覆写，修改实际租户由既有授权控制 |
| history67,108,864 B、directory67,108,864 B、todo16,777,216 B、question tasks16 | V16–V19 tenant_budget | 不更改共享限额；私有显式容量夹具与默认64MiB的真实429分别保留 |
| 模型connectTimeoutMs/requestTimeoutMs/responseBytes/maxOutputTokens等 | `ModelProfiles.ProfileBinding:20`、ModelSnapshot/ModelInputBounds | 已经外置，归类型化模型配置；保持现有结构校验、profile digest与已接纳task快照冻结，禁止重取当前profile覆盖旧任务 |
| feature flags默认false | application.properties、DurableConfiguration | 明确默认值/依赖校验；关flag不等于清理或撤销已有scope，不放松既有公开检查 |

上述新可调范围是实施建议，不是已测容量。初级开发只可按冻结值/既有校验迁常量或配置；需要提高阈值、改保留期/费用/lease/协议时交回中级和产品经理。错误码、Schema版本、插件版本、字节限额不能通过一个通用“limits Map”绕过冻结。
