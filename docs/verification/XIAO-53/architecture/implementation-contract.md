# XIAO-53：session_event_search 最小实施契约

2026-10-23。ADR-0012是本批决定；本文件与contracts目录是交给原后端的唯一新增实施约束。源码基线、固定上游原始blob及许可见evidence。设计交付不等于产品实现或运行验收。

## 1. 范围、装配和版本分派

新增 `jimu.session-event-search.enabled=false`。仅显式新本地profile `local-session-event-search-v2`，能力列表严格且有序为 `["session_event_read","session_event_search"]`，无resources/images、BUSINESS/evaluation/scope；新受理、幂等受理重放、发现、M1/M2、每个工具与工具成功重放均检查两个flag、显式paid=false及现有本地完整请求proof。旧 `local-session-event-read-v1` 仍只要求read flag和原工具/pin，不广告search。不给旧任务新增能力，不接受search-only或混入其他工具。

继续用现有本地模型准入条件（trusted-local-v1、test-mode、127.0.0.1/[::1]、非fixture、paid配置原文false、无business/evaluation/scope、maxOutputTokens<=4096、inputLimit<=65536及test-whole-request-utf8-v1 proof），responseBytes<=16384。新错误 `SESSION_SEARCH_DISABLED`，不能放宽原 `requireSessionRead`。task<=180秒、8模型请求、20工具、8 attempts、4 calls/response。最终summary<=8192字节，仍走无workspace/无artifact完成分支；真实模型usage即使随后撤权或flag off也须结算，不能接纳被拒正文。

新安装：capability名 `session_event_search`→manifest `jimu.session.event_search@0.1.0`；组合read为同capability名的 `jimu.session.event_read@0.2.0`。description/manifest/input/output/catalog/binding均逐字节pin。read@0.1.0及其原资源不改；read@0.2.0输入原字节复用、窗口/摘要/错误/原槽算法同旧版，只允许本次history2/event2的输出Schema。owner GET根据该task已存binding选版本。当前安装表每tenant/capability只有一行：显式切换read版本会按原pin规则拒绝旧版本task，不能自动升级、同时假称两个版本都安装，或跳过当前pin以保旧历史可读；验证新旧并存用独立tenant/显式安装切换及恢复。没有新增安装授权或数据授权操作。

新task binding version2/historyVersion2，event JSON version2；contentPolicy仍public-v1，Session/turn映射与ADR-0011一致。新context/owner metadata输出version2，原字段及最近8个真实seq行为不变。原v1 binding/catalog/context/event/read输出仍按原版本解析，禁止按“最新Schema”验证旧字节。V20及新绑定是必要兼容边界，Checkpoint v2、Response v2、ToolSnapshot v1不改wire版本。

模块沿现有公开窄port：可新增 `plugin.api.SessionEventSearchPort` 与 `SessionEventLedger.searchOrResult/sessionSearch`；插件不引用Identity/Lease/SQL/宿主配置，adapter闭包绑定原lease/step/slot。复用原ledger、事务、SessionQueryDeadline及唯一ToolCallingAdvisor，不建第二循环、通用Session服务/索引器或网络provider。

## 2. 固定上游事实与明确差异

固定上游SHA见ADR；source-map给可复核行号。实际读过工具index/input/operations/presentation/workspace-access/service-boundary、sessionQuery抽象与SQLite实现/配置/测试、sessionProjections/turnBoundary、MIT许可；本轮未运行上游TS测试。

| 方面 | 固定上游事实 | 本批Jimu契约 |
| --- | --- | --- |
| 输入 | 必需query；可选session_id/seq_from/seq_to/time_from/time_to/event_types/surfaces；无模型cursor/limit | 字段不另扩；严格验证并限定下表；surfaces显式不支持 |
| 查询 | tool先trim并把JS `\s+`压成空格、拒绝空/NUL；SQLite再次归一化/保留marker转U+FFFD，以双引号包成FTS5 phrase，unicode61分词（默认大小写/音调处理由FTS决定），不是任意正则或SQL | 同一JS空白集合；区分大小写的码点字面子串，不做casefold/NFC/NFKC/分词/marker替换；标识public-content-literal-v1；这是明确兼容差异 |
| 文档 | first-party extraction只取语义正文、工具名/参数、工具结果/部分终态等；reasoning/stream/envelope/未知事件无文档；surface由foldSurface真实计算 | 只取已存公开user.message/model.response的data.content；另外三类无文档；不搜索calls/收据/输出/元数据；无surface投影，任何有效surfaces输入均400不支持 |
| 当前会话 | caller来自exec.agent；sessionProjections.stateOf(turnBoundary).lastStepStartSeq；seq_to=min(调用传值,stepStartSeq-1)，无活动边界拒绝；裁完反向范围直接空结果 | 原M1前step.upper_seq；原输入from>to拒绝；有效输入与冻结上界交集空则成功空结果；当前step/同响应各槽不可见 |
| 授权/provider | immutable header.cwd跨会话预授权，每页observed header再核；SQLite观察live-preferred/persisted corpus、派生代际和cursor；不是简单read邻接 | 模型只当前task，owner只其保留的一个v2 task；每候选逐条核来源及授权，只有同库原生日志，无文件/SSE/无授权快照fallback |
| 排序 | SQLite match_count DESC, document_length ASC, time DESC, seq DESC；match_count来自highlight标记，document_length是码点数 | 同四级键，但次数是明确的非重叠字面命中；时间用DB微秒精度；不把seq正序冒称相关度排序 |
| 分页/界限 | provider默认20/最大100页大小、limit+1/OFFSET、cursor绑定请求及generation；工具内部collectPages，默认100条、遇第101条才capped，检查重复cursor；默认协作超时30秒；未给本批这样的硬扫描/字节界限 | 扫完最多128候选后相关度top16；第17条真实匹配才capped；无公开cursor/limit/offset/分页，无完整总命中数；2秒及硬扫描/计算/字节界限；不能提前16条就说完整 |
| 展示 | 文本：session/title、seq/type/surface/time/snippet；无命中固定文本；snippet默认240码点、围绕首命中、空白折叠及省略号 | 版本JSON，实际seq/type/time/source/redacted/event_sha256/片段；无虚构title/surface；截断标志，不伪装完整原事件；用独立read核查 |

上游服务内部filters.ts的字面text过滤不等于searchEvents的FTS5，不以它替换真实provider证据。sessionProjections是随事件驱动/可重建的fold，turnBoundary来自agent/projection，不是另一份全文事件源。provider reconcile/list/inspect可能处理完整日志/语料，工具maxResults本身不是候选扫描上限。

## 3. 输入及匹配语义

输入UTF-8<=2048 bytes、JSON深度<=4，仅8字段。拒绝重复key、未知字段、trailing tokens、null/bool代替所需值、非法UTF-8/未配对surrogate、数值1.0/1e0/字符串/负数/溢出；仅integral node，seq范围0..9007199254740991。不使用Schema整数校验替代词法校验。session_id规范小写UUID，省略为当前task；显式其他ID在读取目标前统一404，不查询它是否存在。

query原值1..256 Unicode码点且<=1024 UTF-8 bytes（归一化前后都检查），拒绝NUL。空白集合精确为 U+0009..000D、0020、00A0、1680、2000..200A、2028、2029、202F、205F、3000、FEFF（ECMAScript WhiteSpace/LineTerminator，不含0085和180E）；去首尾、每连续段变一个ASCII空格。结果为空400。任意引号/星号/OR/括号都为字面内容；`.*`只命中真实两个字符。无正则执行、模糊匹配、通配符或隐式“匹配全部”。

seq_from/to分别可省略，默认0/冻结upper；提供两端且原from>to为400；输入有效但与[0,upper]不交集为空结果。time_from/to为含Z或±HH:MM的严格Gregorian日期，年0001..9999，HH<=23/MM/SS<=59、offset<=18:00；必须秒，允许1..6位小数；无闰秒/24:00/本地时区/多于6位/空白。解析为精确UTC微秒，解析后UTC年仍0001..9999；前后比较精确，不浮点截断；两端inclusive，from>to为400。相较上游可省秒/任意小数/offset到23:59及毫秒边界算法，这是显式收紧，防止DB微秒语义歧义。

event_types若提供为1..5个、不重复的原五种Jimu类型，列表内OR、与其他过滤AND。未知/上游slash命名类型400，不映射或猜测。surfaces若提供须为1..3个不重复的上游合法枚举；非法/空/重复400 TOOL_INPUT_INVALID；有效也400 SESSION_SEARCH_FILTER_UNSUPPORTED。省略表示不按surface筛选，不能推断所有事件属于current/log-only。Schema保留该上游字段并明确描述不支持，不增加include/count/sort/cursor等参数。

在已授权、冻结且来源核验通过的事件上取文本：user.message/model.response仅data.content，其余空，忽略空文档；以同空白规则归一化文档。对Unicode码点做大小写敏感的精确字面匹配；重叠命中不计入，发现命中后从其末尾继续。采用线性匹配（如KMP），query前缀表一次计算；硬上限8,388,608次码点比较，包含前缀表/所有候选匹配，达到上限失败不回部分结果。文档长度为归一化后码点数。原stored event不改，文档/排名只是本次内存值，不持久索引。

命中排序为(-occurrences,document_codepoints,-event_time_microseconds,-seq)；seq唯一保证完全顺序。结果只含前16条，returned_count=items.length，capped=(真实匹配数>16)。扫描全部授权候选才给capped=false或[]；失败不返回items或计数。不对外给总命中数、被过滤数或扫描诊断。

片段：在归一化文档码点数组中首命中位置m，起点max(0,m-80)，终点min(length,start+240)；近末尾不回移起点。snippet就是此区间无额外字符，最多240码点/960文本UTF-8 bytes，JSON字符串编码及整个hit各<=1536/2048 bytes；left_truncated=(start>0)，right_truncated=(end<length)。长query可能只部分出现在片段，完整核查用read。保留source与redacted真值，event_sha256是完整已存事件摘要；不返回内部source_digest、匹配次数、模型配置或原query。

## 4. 公开事件与只读边界

新history2全部原生event.version=2，仍是user.message/step.started/model.response/session.read/turn.ended；seq从0、至多128、与SSE命名空间分离。不导入/回填旧任务。五类的source与fact_key/source_digest规则沿用ADR-0011。

event-v2的model.response.calls允许read/search；原read参数仍仅seq/session_id/before/after且<=1024bytes。search原参数按原JSON严格解析后复制白名单参数，**仅query值**按既有public-v1公示策略（受信敏感值替换+路径/敏感token及后继隐藏）捕获公开投影，再用Payloads.json写入公开calls.arguments；原参数字节在step.response_snapshot和invocation.input_snapshot中保持。不得对整段JSON做文本替换而制造非法JSON、改写原恢复参数或把原query放进source/receipt/error。若query有替换，整个事件redacted=true；公开arguments<=2048bytes且完整event<=16384，否则M2安全拒绝并保留真实usage。content仍按原public-v1捕获；query参数即使出现在model.response也不参与搜索文本。已存事件读取不再次按新秘密集重新投影；当前必要安全校验失败须拒绝，不重写历史。

search没有原生session.search事件，也没有冒名session.read；本次调用的正常tool.dispatched/tool.completed/tool.failed可靠事件和新绑定就是收据，均不带query/输出/片段正文。session.read只由真实read追加。search本身不增加native next_seq、不发虚假的history.changed，不消费被检索事件或移动任何读取游标；原M2和后续step仍正常追加model.response/step.started。检索read收据不会展开其output_snapshot，检索不能递归提取之前search结果。

step切点完全沿用M1前context冻结和原prepare重放识别：原step.upper_seq、context_digest、binding_digest不变；同响应多槽的search/read都只见这个切点，前槽新read收据、当前search query及之后并发追加不可见。后续新step取得新的真实范围，恢复原step不刷新。没有当前受信step边界则工具拒绝，不用数据库最新upper补齐。

## 5. 原槽提交、owner API与故障

原锁序tenant_budget→task→actor_access/installation→history→step/invocation/binding；沿现有FOR SHARE授权锁持有至事务结束。每次先用immutable lease中的tenant/actor/task和当前owner/allowlist授权，再查target或给计数/版本/范围错误；每候选校验tenant/task/seq、content_state/retention、Schema版本、event_digest/bytes/time、source与原step/invocation/attempt、可靠事件以及当前允许范围/pin。过滤不能隐藏损坏来源，不能先匹配私有原数据再对摘要脱敏。

M2对原响应槽验证实际index/providerId/name/input/pin/版本后仅一次插入search binding：原input_digest、binding_digest、step_id、upper_seq、规范查询JSON及其digest。规范JSON字段按固定顺序query,session_id,seq_from,seq_to,time_from,time_to,event_types；省略session填本task，省略seq填0/原upper，省略time填null，时间归UTC六位，event_types填五种固定顺序的选择集合；与upper交集不另改原输入。surfaces有效输入因不支持在M2拒绝，不建伪成功槽。规范JSON<=2048bytes。

searchOrResult一次事务：复核flag/本地模型/原槽/step/binding/当前lease/fence/取消/DBdeadline/非终态及保留；SUCCEEDED复核原输出digest/bytes/Schema、已存收据及当前权限后返回原字节，不重新读候选；FAILED复核后返回原安全code，不重查。PENDING在冻结范围扫描、验证、匹配、排序、输出校验后，与一次TOOL reservation/消费、invocation成功、binding终态和可靠工具事件同事务提交，receipt_event_seq指本次tool.completed或tool.failed的SSE序号，与native seq分离。沿原fact_id `intent:<invocationId>` / `result:<invocationId>`；重放核receipt的tenant/task/seq、fact_id、type和安全code均与本槽一致。scan_digest绑定所有实际扫描候选按seq排序的`[{seq,event_sha256}]`（含结构/被过滤事件），空交集为`[]`；不在重放时重算。输出仅在原invocation.output_snapshot有一份，之后真实checkpoint/模型请求中的副本照实计数。

确定的版本/来源损坏、硬限额等查询失败采用与read同样的原槽一次失败/消费/可靠事件原子绑定，output_bytes=0；严格输入/授权/fence/取消/截止/flag拒绝不消费、不查内容。provider/连接/锁/计算超时回滚，不绑定假失败或空结果。commit确认不明沿SessionCommitUnconfirmed退出，由静止原写者后接管同槽读提交事实，不另toolFailed，不遗留超时返回后的写线程。通用admit/toolSucceeded/uncertain明确拒绝此工具 `SESSION_SEARCH_COMMIT_REQUIRED`，不能旁路原槽，不能越过未完成前槽。

owner新增 POST `/api/tasks/{taskId}/session-history/search`，Authorization沿SignedIdentity，Content-Type application/json，body就是同一输入Schema；path是唯一待授权task，显式body session_id须等于path。拒绝querystring、重复/未知字段、非法/超长body；认证完成后、读取task或body前建立2000ms整体截止，覆盖授权、body流式读取/解析及同次DB查询，2048byte流式计数且不能先全量缓冲。DB只继承剩余预算，慢body超时也安全退出SESSION_QUERY_TIMEOUT并回收本请求读取者，不能另获2秒或留下异步请求写者。先认证并授权path，再返回body语义错误；不能因错误差异泄漏其他task存在性。只为其已授权且仍保留的v2 task工作，包括终态；v1自身返回409 SESSION_SEARCH_UNAVAILABLE。upper在同一授权只读事务开始固定，输出boundary=owner-snapshot；不建invocation/search binding/可靠事件、不花工具/模型预算、不更新task状态/历史，不接纳跨task body ID。

owner metadata/原read/SSE沿现有入口并按task版本分派，Cache-Control:no-store；search也是no-store，不建前端。flag off不关闭owner既有v2搜索/read和清理，但仍核当前actor/安装/两工具pin/保留；v1查询仍只核v1原read pin。模型工具与owner每条结果及后续read均重新复核当前授权；search结果不是后续read的authority。GET/SSE重连不自动调用模型/工具，不恢复终态/PURGED task。

错误沿TaskFailure安全code，无SQL、路径、query、片段或provider异常正文：未认证401；不可访问/其他session统一404 SESSION_EVENT_NOT_FOUND；自身v1搜索409 SESSION_SEARCH_UNAVAILABLE；过期410 SESSION_HISTORY_EXPIRED；版本409 SESSION_HISTORY_VERSION_UNSUPPORTED；损坏409 SESSION_HISTORY_CORRUPT；一般输入400 TOOL_INPUT_INVALID；有效surfaces400 SESSION_SEARCH_FILTER_UNSUPPORTED；关闭403 SESSION_SEARCH_DISABLED；硬额度429 SESSION_HISTORY_LIMIT；整体查询超时504 SESSION_QUERY_TIMEOUT；provider503 SESSION_QUERY_UNAVAILABLE。FENCE_REJECTED/CANCEL_REQUESTED/DEADLINE_EXCEEDED及原撤权code不变。不增加泄漏存在性的计数、错误详情或诊断接口。

## 6. 扫描、全部副本与清理的硬界限

| 资源 | v2固定界限/处理 |
| --- | --- |
| 输入/规范查询 | 各2048bytes；query256码点/1024bytes；深度4；时间最多32 ASCII字符；列表5/3项 |
| 候选 | 仅一个tenant/task；PK `(tenant_id,task_id,seq)`，`seq BETWEEN 0 AND upper ORDER BY seq LIMIT 128`，最多128行/2,097,152事件正文bytes；全前缀连续且seq正确，过滤在核验之后；无OFFSET/全库/跨Session扫描 |
| 空交集 | 先授权、版本/保留和严格输入；seq与upper交集为空可直接真实空，不读候选；其他情况候选前缀完整核验，time/type无匹配不能绕过计数来源验证 |
| 来源读取/内存 | <=1024次来源定点PK记录、累计事件+证明正文读取<=8,388,608bytes、scratch总计<=8,388,608bytes，缓存按step/invocation只保留必要证明且有界；不得JOIN后扇出无限记录或递归展开output；坏库越界明确429/409 |
| 匹配/排序 | 线性码点匹配<=8,388,608次比较；候选最多128；top16，可保留最多128个有界排名记录；无正则/用户比较器 |
| 返回 | <=16 hits、单hit2048/片段JSON1536bytes、完整output32768bytes；只有结果条数可capped，片段左右截断各自标记；其他超限报错，不静默部分扫描 |
| 时间 | 2000ms整体，包含连接取得/握手、锁、所有SQL、证明、计算及提交前检查；不得越task deadline，锁<=1s，单调时钟定期检查并给每条SQL剩余预算，沿R1物理连接截止；provider故障无fallback |
| 原有正文 | event16384bytes/128条、M2 response16384、model request65536、checkpoint131072、summary8192；read单输入1024/窗口9行/原scratch1MiB，均保持；search不会扩大read窗口 |
| 持久化 | 原8MiB/task reservation、7MiB所有正文、64KiB元数据（正常<=61440含收尾预留）、tenant默认64MiB history桶；所有task/step/invocation/dispatch/event/search normalized副本精确实计 |

SQLite上游没有被本实现调用；不声称复制其FTS兼容性。候选查询用PK并保存真实EXPLAIN/rows examined证据；seq safe integer很大也只作有界比较，不能迭代到它或按时间全表扫描。定点来源证明与metadata计数也须在同一整体时间预算，不另给每条SQL2秒。实际元数据可沿当前有界TABLES读取，但新表和新增列都须计入，不能只给新表估算常数；原V19实现metadata本就按明确有界表重算，本批不要求无关改造成增量计账。

原保留截止=受理DB时间+604800秒，不延长。新normalized_query_snapshot、所有原参数/结果/context/公开calls/模型请求/summary属于正文；清理收据列每份原digest/bytes，不保存文本。安全终态、所有历史attempt静止、无UNKNOWN/其他未结reservation/dispatch/invocation（唯自身待结SESSION_HISTORY_STORAGE除外）后，同锁序一次事务清空全部旧正文列+search.normalized_query_snapshot，search.content_state=PURGED、原digest/bytes/receipt序号保留；禁止残留query在JSON元数据、错误、日志或cleanup_receipt。

将search表加入真实元数据计算与清理清单，规范查询列列入BODIES；预留其清理收据元数据容量。原存储reservation结算后重新计算metadata/held/actual宽度固定点，再一次扣tenant释放 `8388608-retained_metadata`；保留墓碑持续计费。双清理/崩溃回滚不二次释放，不能删除UNKNOWN或活写者。过期但未安全清理仍占原容量，已停止公开；flag off继续此路径，重新启用不重建Session，不恢复已清正文/终态。旧profile/其他tenant不因新search预留受阻。

## 7. V20与实施顺序

`migration-contract.sql`只描述新增V20；后端验证原V19生成CHECK的实际名称和SHOW CREATE TABLE，名称不符则安全失败，不删除未知CHECK，不改V19文件。只把history_version和event_version合法集合扩为1/2，原五类type和全部容量/一致性约束保持；新增search表及索引/FK/明确CHECK。旧行不更新、不回填、新版本不改record_version1。新binary schema gate严格20；原V19 binary在readiness前拒绝20。升级保存V1–V19 checksum/旧request/tool/model/checkpoint/binding/event原字节，回退只使用理解20并关闭新flag的制品。不要执行生产迁移/授权/部署。

后端第一切片：V20+版本Schema/catalog装配+v2受理与M1上下文+只读provider/owner API；随后接原M2槽绑定、searchOrResult与新read/工具唯一路径及清理。`JdbcSessionRecords.task/history/create/response/bindReads/verify/metadata/purge`、`JdbcLedger.sessionAccess/sessionAdmission/sessionAvailable/submit/readOrResult/通用commit旁路/schema gate`、`DurableAgent.runAttempt/toolDefinitions/context/invokeSlot`都存在read-only硬编码，逐点分派不能只改工具广告。同步application_grants生成器的新表SELECT/INSERT/UPDATE，不执行授权SQL。不要扩大旧read事件schema、给旧task切换capabilities或修改冻结ADR索引来源。

沿架构草稿PR同一head接续；产品经理核占用后原后端唯一实施，两小时内给首个可构建SHA/PR或已试动作/解锁条件/负责人。仅必要本地验证，不运行/启用/重跑/等待GitHub CI。保留历史Windows verify 262项1failure/15errors、ADR README3446/3819及POSIX失败，不改历史证据追认。

## 8. 稳定制品完成条件

| 编号 | 同一准确源码/tree/JAR的必要独立验证 |
| --- | --- |
| S01 | 私有MySQL、真实control/双worker、显式本地paid=false：真实事件→真实query命中实际seq→模型下一step用read核查→重启/重连/接管→继续search→任务完成；原API/SSE和协议账本，无fixture工具假闭环 |
| S02 | 字面大小写、空白精确集合/NUL、中文/emoji/组合字符/引号/OR/正则样文本；非重叠次数、四级并列排序、16/17命中、两侧片段裁剪及完整event digest；无命中真实完整扫描 |
| S03 | seq/time/type单项及组合AND/列表OR，微秒含端点/UTC偏移/闰年、原反向范围拒绝与上界交集空；结构类型无文本；surfaces合法但unsupported，未知/重复/空列表拒绝 |
| S04 | 同response多search/read槽、当前调用、并发追加均不越M1范围；下一step见新事实；M1/M2/提交前后强退、丢commit确认两侧、原槽原字节/一次消费/收据，后续追加不冒充原结果 |
| S05 | 跨tenant/owner/同owner其他task、body/path冲突、伪造source/event/seq/pin/binding、旧fence、重复worker；逐候选来源验证失败不以过滤跳过，不泄漏存在性/正文/计数 |
| S06 | actor/安装撤权与提交竞争、取消/截止/终态/flag组合、成功重放复核；owner终态只读、不花预算/唤醒，v1 search不可用，flag off owner历史/清理真实 |
| S07 | 原始HTTP/模型strict JSON：2048byte/256码点/1024query字节边界、重复key/未知字段/数值词法/safe integer/无效UTF-8/surrogate/时间/额外query；原read@0.1输入/输出字节不变 |
| S08 | 128候选/2MiB/8MiB proof/scratch/1024proof reads/比较次数/16结果/32KiB输出各必要N−1/N/N+1；大seq/稀疏/宽查询/无命中/恶意文本有界，EXPLAIN；真实连接/握手/SQL锁/计算截止和provider失败，无迟到写者 |
| S09 | synthetic凭据/路径/秘密/隐藏reasoning：content与search calls.query捕获脱敏，匹配/空结果计数/摘要/read/SSE/错误/诊断均不泄漏；不从原参数/输出补文本；参数公私两份来源正确 |
| S10 | 真实V19→V20升级、所有旧冻结字节/checksum不改、旧V19 control/worker拒绝20、理解20的flag-off回退；v1/v2两套pin/安装切换拒绝与恢复，owner按实际版本分派 |
| S11 | 7天截止立即不公开；全部正文含normalized query和各上下文/输出副本实计并清理；真实单/双control固定点metadata=held=reservation actual、精确租户差额/唯一收据释放/重复维护；UNKNOWN/活写者不释放，安全候选不被饿死 |
| S12 | 按触及差异回归read-only v1/T44、directory/workspace、MCP、todo、问答、旧task；不重跑旧完整矩阵；R1连接整体预算与QA-52-01清理自身元数据宽度不可回归 |

保留实际命令/退出码/断言、准确SHA/tree/JAR SHA256、原始协议/账本/失败与清理收据；开发自测不代替原独审、原QA及产品业务验收。架构Schema/示例核验不是上述运行结果。未测系统DNS/TLS全部时序、全部容量/竞争组合/清理事务内强退或实等7日不得写成已通过；按真实触及风险选择必要差异验证。无生产、真实Key、付费搜索/模型、部署/共享服务/daemon/代理/FRP变更，无飞书发送/外部汇报。
