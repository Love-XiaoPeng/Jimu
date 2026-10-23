# XIAO-54：session_event_trace 契约候选

2026-10-23。基线 master `3e5f77549562691634c0a166d8b944ecdcdf1bb8` / tree `93d353f8efac07c34676bc3bffb1790e048fc75d`。ADR-0013、这里的完整 contracts 和 V21 草案共同组成可实施候选。**产品经理须先裁定“仅追溯实际 read 收据来源、不支持上游 surface 替换”的业务差异，再启动唯一后端。** 不把该差异隐藏为实现细节或宣称 T46 完全兼容。

## 1. 原码事实及兼容差异

固定上游 `571902028a75c64c1d767f4ae5fd099485f0ddf0`。本批重新下载并验证48个 Git blob；覆盖工具 index/input/operations/presentation/workspace-access/service-boundary、真实 sessionQuery/corpus/tracing、sessionProjections、turnBoundary、surface fold、message projections、provider/工具测试和 MIT。证据与定位在 evidence；未运行上游 TS 测试。

| 语义 | 固定上游原码 | 本候选 Jimu |
| --- | --- | --- |
| 输入 | seq 必需；session_id 可选；无 depth/cursor/limit | 只这两个字段；严格有界校验；无关系写入参数 |
| 替换 | foldSurface 的 shadowedSeqs 建立即 replacedBy；沿立即替换者形成 replacementChain；replacedEventSeqs 只直接节点 | 没有合法生产者，不实现 surface/replacement；replacement_policy=unsupported；省略上游三个替换字段，不能用空列表伪称支持 |
| 来源 | 显式 event.sourceEventSeqs；直接来源保留生产者顺序；derivedEventSeqs 扫描后续事件引用，seq升序；不递归来源 | 仅成功模型 read 收据实际返回的事件；宿主捕获并按seq升序，正反向都是直接关系；不是上游 surface 来源 |
| 约束 | source必须向后指向更早事件、无重复；非surface不能携带；assistant/message不能携带；replace必须覆盖shadowed节点 | Jimu source_event_seqs 是新 v3 自有协议，明确允许 session.read；其他四类和失败read必须[]，不能映射为上游wire |
| 目标/排序 | 目标是轻记录，surface由全日志fold得到；缺目标先报not-found；来源保持显式列表顺序 | 有界公开摘要+原event摘要；不造surface/title；两列表升序；目标不在冻结范围统一404；完整扫描前缀后才给列表 |
| 边界 | executeEventTrace未应用turnBoundary；search才以lastStepStartSeq-1截断 | 比上游更严：所有工具共同沿原M1前切点；本step响应/调用/同响应其他槽/后来追加都不可见 |
| provider | traceEvent经corpus.load取完整live优先日志；冷路径list+inspect；fold验证整个观察；无trace分页/结果cap，工具未设置searchTimeout | 唯一同库公开日志；128行/2MiB硬上限及2秒整体截止；无分页，无文件/SSE/内部快照fallback |
| 输出 | TEXT_OUTPUT为string；六行关系文本，含surface及替换 | 明确版本JSON；仅下述字段；不声称上游字符串兼容 |

会话根/父/子/祖先/后代属于 T48，不是 T46；本批不存、不推断或输出这些字段。SQL source_step_id/source_invocation_id 是公开事实来源证明，不自动构成事件因果边。模型说自己引用了某seq、参数传seq、同step或在同请求里出现都不是关系授权或持久事实。

## 2. 准入、版本及模块

新增 `jimu.session-event-trace.enabled=false`。仅显式 `local-session-event-trace-v3`：能力列表严格有序 `[session_event_read,session_event_search,session_event_trace]`，三个flag全开，不支持trace-only、旧任务加装或混入其他能力。沿现有trusted-local-v1、test-mode、loopback、非fixture、配置原文paid=false、whole-request UTF-8 proof；无BUSINESS/evaluation/scope/resources/images。180秒、8模型请求、20工具、8 attempts、4 calls/response、response16KiB、request64KiB、checkpoint128KiB、summary8KiB保持。

一个task就是一个Session/turn，session_id=taskId；attempt/重启/接管不创建会话。binding/history/event/context/owner metadata显式版本3，content_policy仍public-v1；Checkpoint v2、Response v2、ToolSnapshot v1保持原wire。新read@0.3.0只扩为可读取event3输出和新关系捕获；新search@0.2.0只扩history3输出/装配，字面匹配/排序/过滤/片段沿ADR-0012，无surface；新trace为jimu.session.event_trace@0.1.0。旧v1/v2资源、迁移、Schema、安装pin及快照原字节不变。

contracts是后端新增资源的权威来源，应复制到session/v3并核catalog各摘要，不能重新生成覆盖session/v1或v2。当前安装表每tenant/capability只有一个版本：切换read/search安装会按当前pin拒绝旧版本任务，不能为兼容绕过安装或假称多个版本同时在装；同库验证用隔离tenant及显式版本切换。flags在准入/幂等受理、发现、M1/M2、执行和成功重放复核；owner保留历史和安全清理不依赖flag，但依然复核当前授权及全部三工具pin。

沿 `plugin.api.SessionEventTracePort` 与 `kernel.task.api.SessionEventLedger` 窄接口；宿主闭包绑定immutable lease/step/原槽，插件不接触Identity/SQL或静态当前用户。复用 JdbcLedger/JdbcSessionRecords、SessionQueryDeadline 和唯一 ToolCallingAdvisor。无新技术、服务、部署、第二循环或通用图引擎。

## 3. 受信关系捕获

v3五种原生日志仍为 user.message/step.started/model.response/session.read/turn.ended。新增必需 `source_event_seqs`，来源策略仅read-receipt-sources-v1。用户/模型文本、seq参数、source标识都不能直接提供这个列表。

成功模型read的同一事务中，使用**该次实际已逐行验证且构造进输出**的目标和邻接摘要，按seq升序保存 `[{seq,event_sha256}]` 到原 invocation_session_read 的 read_sources_snapshot（<=1024 UTF-8字节）及digest/bytes。其seq集合等于该次实际window_start..window_end，含target，最多9；不以请求before/after猜不存在的节点，不抓最新窗口。每seq<=原read.upper_seq且<新receipt.seq，同tenant/task且digest等于实际返回行。receipt.source_event_seqs等于此列表的seq投影，事件摘要覆盖新字段。

M2为新v3 read原槽存 relation_version=1、来源正文/digest/bytes暂NULL；成功read一次变成非空快照，失败read只存[]的摘要与字节2，不建立任何成功来源边。失败输入/授权拒绝不产生原生receipt，沿旧提交规则。user/step/model/terminal 的来源列表必须[]，不能根据模型请求、文本或原始checkpoint填边。

关系capture与原输出、read binding终态、TOOL一次消费、可靠工具事件、原生read receipt及history.changed在原事务内提交；任一容量或权限/fence检查失败整体回滚。owner GET read不建invocation/receipt，因而不捕获新边；search/trace也不新增原生收据或伪装session.read。这个限制避免查询越查关系越增长或递归复制先前输出。

验证关系先完成事件本身的Schema/摘要/来源证明，再平面核对read binding的relation_version、快照摘要/字节、原response槽及原input、read窗口/outcome/receipt seq/输出摘要和三工具pin；快照每seq的当前不可变event_digest须相等。原read输出仅可作有界摘要/结构证明，不递归遍历内嵌旧read目标、工具正文或来源。缓存证明按step/invocation有界去重，不能递归调用verify展开链。旧v1/v2 read binding relation_version=0/全NULL；不得回填或把旧read.data.seq重新解释为已捕获来源。

## 4. trace输入/输出与完整性

UTF-8输入<=1024字节、JSON深度<=4，仅seq/session_id，拒绝重复key、未知字段、trailing tokens、无效UTF-8/未配对surrogate、null/数组/bool/字符串数值。trace.seq词法只允许 `0|[1-9][0-9]*`，数值0..9007199254740991；拒绝-0、1.0、1e0、负数及溢出；Schema integer不能代替token检查。session_id若给出为规范小写UUID；省略为当前task，显式其他ID在目标读取前统一404，不探测存在性。旧read/search按各自已发布版本解析，不能用新trace词法规则改写旧版本行为。

模型只有当前task；owner path只授权一个自己的保留task。没有受信step切点拒绝，不用latest上界补齐。seq>upper或在已授权范围缺target统一404，不返回“相邻”替代值；大seq只比较，绝不循环到输入seq。目标存在后验证整个冻结前缀[0,upper]，行必须0起连续；任一缺行/坏来源/版本/摘要/关系、重复边、自环、前向引用或不在同task均409/429失败，不静默跳过。

输出字段精确由 trace output Schema冻结：version=1、session_id、history_version=3、content_policy=public-v1、boundary、upper_seq、relation_policy=read-receipt-sources-v1、replacement_policy=unsupported、target、source_event_seqs、derived_event_seqs。禁止临时添加 root/parent/child/surface/chain/count/cursor/诊断字段。

target为真实seq/type/time/source/redacted/event_sha256及已存公开data.content的确定性前256码点摘要，text<=1024文本UTF-8字节、整个摘要<=2048字节，summary_truncated明确标记；结构事件无content则text=""，不展开data或工具输出。event_sha256为完整已存事件原字节摘要，供独立read核对。source_event_seqs是目标自身已捕获来源的seq升序列表；derived_event_seqs为冻结范围内实际成功read receipts中含target.seq的seq升序列表。前者<=9、后者<=20，绝不包含传递关系。源/反向结果和计数推导只来自已验证授权行，不能先查私有记录再删字段。

没有来源/反向引用如实[]。关系不分页、不capped、不部分裁剪：完整范围核验后全部返回，超出契约上限失败；只允许target文本摘要按上面规则截短。本候选不输出complete=true，因为它不证明上游替换图或任意因果完整性。unsupported明确意味着没有该能力，不能把上游replacedBy=null/chain=[]当成功结果。

## 5. 查询、原槽与竞争

按原锁序tenant_budget→task→actor_access/installation→history→step/invocation/binding，授权共享锁持有到提交。immutable lease的tenant/actor/task来自受信上下文；检查当前actor允许范围、owner、保留、安装version/digest/epoch、task binding、当前lease/fence、取消/DBdeadline/非终态。先授权再目标/版本/范围/计数，不能泄漏其他task。每候选、每边两端、summary以及后续read独立重新授权/核来源，返回seq不授予read权限。

M1前冻结原step.upper_seq与原context/binding；所有同响应read/search/trace槽只见同一前缀。M2原response index/providerId/name/input/pin验证后为trace插入一次 invocation_session_trace，原input_digest、binding_digest、step_id、upper_seq、target_seq不可变。新trace参数无自由文本，model.response-v3只按白名单投影这两个字段；read/search参数公私投影继续沿已发布策略，不能暴露原query或隐藏字段。

traceOrResult按原槽顺序执行，PENDING在单事务内扫描/证明/构造/Schema核验，然后invocation成功输出、binding结果、TOOL reservation结算/计数、canonical intent/result可靠收据和最后commit fence一起提交。不先RUNNING、不走通用admit/toolSucceeded/uncertain；这些旁路对trace明确SESSION_TRACE_COMMIT_REQUIRED。trace不追加原生日志、不发虚假history.changed、不移动被查task游标或业务状态。

绑定scan_digest=SHA256(Payloads.json(按seq升序的[{seq,event_sha256}]))，覆盖所有实际验证候选而非仅相关行；event原字节摘要已覆盖source_event_seqs，flat来源证明再绑定read_sources摘要到relations_digest（按receipt seq升序的[{seq,read_sources_sha256}])。同一原invocation的输入/切点/关系事实/输出绑定不可变。SUCCEEDED重放先检查当前权限/pin/flag/保留/原槽/收据及原输出digest/bytes/Schema，然后原字节返回，不能因后来新增read关系重新扫描或改列表；FAILED重放原安全code且只消费一次。

自身target缺失、版本/来源损坏、硬限额等确定失败可按旧read/search一次失败绑定，output_bytes=0；输入/授权/fence/flag/取消/截止拒绝不消费、不查内容。provider/连接/锁/计算超时回滚，不绑定空结果或伪确定失败；commit确认不明沿SessionCommitUnconfirmed退出，原写者静止后接管同槽读事实，不能另通用toolFailed覆盖。真实模型usage即使后续拒绝也结算，不接纳被拒正文。

## 6. owner API与错误

新增 POST `/api/tasks/{taskId}/session-history/trace`，SignedIdentity、Content-Type application/json、body为同trace输入，无querystring。认证后建立绝对2000ms截止，先授权path再读取/解析body；body<=1024流式计数，授权、body读取、连接/握手、SQL、证明和计算共用剩余截止，复用共享取消timer和非阻塞读取，不能另获2秒或遗留迟到请求线程。path是唯一目标，显式body.session_id必须相等。

owner在一个当前授权只读快照固定upper，boundary=owner-snapshot；保留终态v3可查询，无invocation/预算/可靠事件/状态变化，不唤醒终态。v1/v2的本人历史返回409 SESSION_TRACE_UNAVAILABLE，其他owner/tenant/task仍统一404。flag off保留owner的已有v3 read/search/trace及精确清理，但当前actor/pin/保留仍核。GET metadata/既有read按task存储版本分派，所有历史响应Cache-Control:no-store，不新增前端。

错误沿安全TaskFailure：401未认证；404 SESSION_EVENT_NOT_FOUND（目标无权/其他session/自身范围无目标）；409 SESSION_TRACE_UNAVAILABLE（自身旧历史）、SESSION_HISTORY_VERSION_UNSUPPORTED、SESSION_HISTORY_CORRUPT；410 SESSION_HISTORY_EXPIRED；400 TOOL_INPUT_INVALID；403 SESSION_TRACE_DISABLED；429 SESSION_HISTORY_LIMIT；504 SESSION_QUERY_TIMEOUT；503 SESSION_QUERY_UNAVAILABLE。原FENCE_REJECTED/CANCEL_REQUESTED/DEADLINE_EXCEEDED/撤权code保持。无SQL/provider异常正文、路径、凭据、私有输入、关系总数或是否存在的诊断。

## 7. 硬界限、存储与V21

| 资源 | 界限及行为 |
| --- | --- |
| 候选 | PK(tenant_id,task_id,seq)，仅[0,upper] ORDER BY seq LIMIT128；<=128行/2,097,152正文bytes；实际行数=upper+1且0起连续，当前history.next_seq>=upper+1且<=128（不能要求冻结前缀等于后来追加后的next_seq）；无OFFSET/按大seq遍历/跨task扫描 |
| 关系 | <=20成功read receipts、每个<=9来源、总<=180边；单次direct depth1；最大9来源+20反向=29引用、含target最多30结果节点；不递归，不建立替换链 |
| 证明 | <=1024次定点来源记录读取；事件+证明正文总<=8,388,608bytes；scratch总<=8,388,608bytes（含解析树、缓存、输出、临时序列化）；不能无限JOIN扇出或缓存原始输出 |
| 计算 | <=1,048,576次显式逻辑操作，覆盖候选/边核对、摘要码点与序列化扫描；结构解析受字节/深度门禁与整体截止约束；无用户代码/比较器/正则；预算每循环检查 |
| 返回 | 关系列表完整或失败；目标摘要<=256码点/1024文本bytes/2048对象bytes；整体<=32768bytes；不输出全事件正文 |
| 关系快照 | 每read_sources_snapshot<=1024bytes，最多20份；正文不是元数据；模型/owner查询输出只按真实生命周期存必要副本 |
| 时间 | 总2000ms，连接获取/认证/事务及全部SQL/计算/提交前共享；锁<=1s，DBdeadline取更早；沿R1物理连接和取消，无迟到写者 |
| 配额 | 8MiB/task reservation，全部正文<=7MiB，元数据<=64KiB（正常预留收尾<=61440），tenant默认64MiB；新增列/表/收据参与实际固定点，不能用固定常数代替真实计数 |

全前缀核验、每边端点按已核行定点索引，避免N层递归；PK LIMIT/EXPLAIN/rows examined由后端在真实MySQL验证。128不是可跳过授权/来源证明的借口。边/数量越界明确失败，不把扇出尾部截掉冒充完整；环因严格source<receipt被拒绝，依然测试坏库自环/环/未来引用。

V21仅把V20命名CHECK的history/event版本集合扩为1/2/3，新read关系列和trace表见migration-contract.sql；不新增事件类型，不改V1–V20/旧快照/冻结字节。不回填旧task、不修改旧binding或自动pin；read关系列旧行relation_version0/全NULL。新binary schema gate严格21，原V20 control/worker在readiness前拒绝21。回退用理解21且flag off制品，不降schema，不删历史。需同步application_grants生成器但不执行生产迁移/授权。

受理时间+604800秒到期立即停止公开。安全终态、所有历史写者静止、无UNKNOWN/其他未结占用（自身待结history storage除外）后，同事务清空所有既有正文+read_sources_snapshot；trace无重复输出列，原invocation.output_snapshot/response/checkpoint/model请求/summary/公开event/context中的副本都实计并清理。关系seq不保留到普通metadata/cleanup_receipt；只留每份原digest/bytes及必要原槽/收据墓碑，保留元数据继续计费。read sources摘要/bytes保留，content_state=PURGED；新trace墓碑同理。原reservation结算自身宽度参与metadata=held=actual固定点，tenant只释放reserved-retained一次，双清理/崩溃回滚/UNKNOWN边界保持。flag off不能停止清理或重新公开过期数据。

## 8. 后端接入及交付条件

首切片：V21和新v3 Schema/catalog/装配、三工具pin、受理/M1切点、read成功关系原子捕获、owner只读trace/provider；再接M2 trace原槽、traceOrResult、重放及全部副本清理。沿此架构分支/草稿PR同一head接续。JdbcSessionRecords的task/version/contract/create/response/bindReads/verify/metadata/purge、JdbcLedger的准入/权限/安装/原槽/read/search/commit旁路/schema gate、DurableAgent的profile/toolDefinitions/context/invokeSlot、SessionSearchBodyFilter及owner Controller均需显式v3分派；不能只广告第三个工具。read/search旧路径按各自版本保留，不顺带重构。

原后端稳定后，原独审和原QA锁同一完整源码/tree/JAR各完成一次交回，修复按差异复验。必要验证：

| 编号 | 实际验证目标 |
| --- | --- |
| T01 | 私有MySQL+真实control/双worker+显式paid=false HTTP模型：真实search/已知seq→read成功捕获来源→下一step trace查到read receipt→独立read核正文/digest→重启/重连/原槽接管→继续完成；原API/SSE/协议账本 |
| T02 | read目标与两侧实际返回9节点来源、端点裁剪、失败read无边、无关系真实[]、source与derived升序；结构事件摘要为空；多个read直接反向，不给传递链/同step/文本猜测因果 |
| T03 | 同response多read/search/trace、本step响应/当前调用/前槽新read receipt/并发追加不可见；下一step可见；原trace成功后再添新关系，恢复只回原字节/一次消费/收据 |
| T04 | M1/M2/提交前后重启、提交确认丢失两侧、fence/双worker、取消/截止/终态；撤权与提交竞争、flag off模型拒绝/owner历史及清理继续 |
| T05 | 跨tenant/owner/同owner其他task、body/path冲突、伪造event/source/read来源快照/摘要/原槽/pin、缺行/重复边/自环/环/未来引用；拒绝无正文/存在性/计数泄漏 |
| T06 | 1024输入/N±1、安全整数/词法-0/浮点/指数、重复key/未知字段/非法UTF-8/surrogate、超慢body；继承严格输入及R1授权+连接/事务同截止，不遗留迟到写者 |
| T07 | 128候选/2MiB、180边/9源/20反向、1024证明/8MiB读入和scratch/操作预算、32KiB输出必要边界；大/稀疏seq、损坏/扇出/provider故障/锁/连接失败真实有界，无部分成功，EXPLAIN证据 |
| T08 | 合成凭据/路径/隐藏reasoning不进入捕获投影、target摘要、关系、错误/SSE/诊断；旧工具输出不递归，read receipt只公开sources和原有限收据 |
| T09 | 真实V20→21、旧制品拒绝、V1–20/旧profile及冻结字节不变；新旧安装切换拒绝与恢复，旧v1/v2 trace不可用；read/search新版本语义及旧版本差异回归 |
| T10 | 7天夹具停止公开、来源快照/公开关系及所有输出副本实计并清理；metadata=held=actual/tenant精确差额/唯一释放、双清理/事务回滚、UNKNOWN/活写者不释放，QA-52-01固定点 |
| T11 | 按实际触及差异回归旧read/search、directory/workspace、MCP、todo、问答及旧task；不因取消CI重跑旧完整矩阵 |

保留准确SHA/tree/JAR SHA256、实际命令/退出码、原始协议/账本/失败/清理收据和未覆盖项。架构Schema校验不等于运行验收；matcher边界、时间夹具、未穷举竞争/全部容量/DNS/TLS/清理事务内崩溃按真实覆盖表述。不运行/启用/恢复/重跑/等待GitHub CI；保留历史Windows verify262项1failure/15errors、ADR索引3446/3819及旧POSIX失败。不接生产/真实Key/付费模型或搜索，不改部署、共享服务、daemon、代理或FRP；未发送飞书/外部汇报。
