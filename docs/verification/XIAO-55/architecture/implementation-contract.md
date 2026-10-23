# XIAO-55：session_search 最小实施契约候选

2026-10-23；架构交付，等待产品经理接受 ADR-0014 的范围后实施。基于 master `d6d2d28a8f4fb3c2f05ed2bd410b1d5f65a16fe1` / tree `af0e11992efdc00d3e8bc6d656d9aeda30159a90`。本目录契约、manifest、catalog 为规范候选，不是已运行产品；全部历史文档/基线/Schema/V1–V21保持原字节。

## 1. 固定上游与业务差异

固定 `deepseek-ai/deepseek-harness@571902028a75c64c1d767f4ae5fd099485f0ddf0`，MIT。完整逐文件 blob/长度/SHA256、相关测试和行号见 evidence。不得仅凭名称推断：

| 原码事实 | 本批候选与差异 |
| --- | --- |
| index.ts 注册 session_search，query 必需；其余12字段在 input.ts，工具无公开 limit/cursor；默认100结果/30秒 | 字段名完整保留；4结果/2秒；不擅加 limit/page/cursor |
| operations.executeSessionSearch 取得 agent/caller/header.cwd，无 cwd 拒绝；追加 cwd filter，accept 排除 caller；parent ID先授权，root以null参与parent OR | Jimu无受信workspace lineage授权，改为 owner认证后宿主产生的显式scope；parent/root/availability/surface字段合法传入后明确unsupported，包括 include_root_sessions=false；不空结果/默认root伪装支持 |
| collectPages 反复provider页，授权后满cap再遇第cap+1个才capped；拒绝循环cursor；只有被接纳结果计数 | 本批完整扫描有界授权集合后才报无匹配/完整；代表会话>4则capped=true；无总命中数、无公开cursor；超扫描预算失败，不伪装裁剪 |
| SQLite _querySessions：ROW_NUMBER PARTITION BY session_id，match_count DESC/document_length ASC/time DESC/seq DESC选最强；全局match_count DESC/document_length ASC/time DESC/session_id ASC/seq DESC | 同样每会话一代表及五级全局排序结构；match_count取非重叠字面次数，length取规范化码点数；UUID按ASCII升序，不用locale |
| query.ts 将query引号转义为一段FTS5 phrase；schema使用unicode61；highlight标记数量参与match_count，snippet默认240码点；不是BM25 | 复用public-content-literal-v1的区分大小写字面匹配/ECMAScript空白折叠，不做Unicode归一化、分词、FTS语法或正则；240码点片段及显式左右裁剪，非FTS兼容 |
| corpus.listSessions 合并live/persisted且live优先；SQLite在每次search前_reconcile观察持久列表及全部live，重试源变动一次；页limit不能限制完整来源观察 | 本批没有logical live/persisted事实；仅固定0–8个terminal v4独立来源，PK候选表，无全租户列表和索引服务，不声称上游页大小是扫描上限 |
| workspaceAccess.callerOf读取turnBoundary；session_search不使用其step上界，只排除caller；event_search当前会话才用lastStepStartSeq-1 | 本批来源切点固定于准入，不刷新；caller read限M1之前；来源终结后禁止追加，attempt/接管仍是同Session |
| presentation文本包含title/createdAt/parent/availability/bestMatch(seq/type/surface/time/snippet)；titles再读且核授权 | 本批JSON返回真实session_id/created_at/upper_seq/best_match及公开digest；不伪造title/parent/availability/surface。精确输出见Schema |

`session_search`不是现有T45改名，也不包含T48 session_trace。旧五种公开事件继续捕获；仅user.message/model.response已存data.content参与匹配，不检索参数、source IDs、read收据、工具输出、快照、路径、隐藏reasoning。所有内容沿public-v1捕获时投影，不从私有原始参数补文本。

## 2. 新profile、宿主受理与scope权限

`jimu.session-search.enabled=false`；新profile `local-session-search-v4`，且read flag须启用。仅 `[session_event_read,session_search]`，工具版本read0.4.0/search0.1.0。新的history/event/context/binding-v4，不修改read-only v1、search/read v2、trace v3。固定本地显式paid=false、180秒、8模型请求、20工具调用、8attempts、每response最多4槽；不混入其他工具/资源/images。安装仍按capability单版本及epoch精确pin，不自动升级、不绕过旧pin；切换read安装版本会依既有规则使旧任务失权，此行为须在验证/交付中公开。

新增专用 `POST /api/session-search/tasks`（避免更改旧/api/tasks受理Schema），Authorization用现有Authenticator，Idempotency-Key沿旧规则。body为contracts/admission-input.schema.json：必需goal、history_session_ids，列表0–8唯一规范UUID；无需模型提交tenant/actor/capabilities/profile/upper/pins/expiry。空列表仅产生可独立作为以后来源的v4 Session，查询返回真实空，不代表无限scope。受理请求<=16384 UTF8 bytes、goal<=8192 UTF8 bytes，其他限制沿契约严格解析；认证至受理提交整体2秒，body非阻塞限时读取，不沿旧6MiB无界等待路径。

宿主发布窄Task API用例 `submitSessionSearch(Identity,key,goal,List<UUID>)`，在原任务准入同一事务产生task/tools/model/budget/history/scope；controller不得构造可信authority。Idempotency digest包含规范请求（含scope排序），同key同请求回原task且复核当前权限；异请求409 IDEMPOTENCY_CONFLICT。不先建task再异步授权，不允许工具/模型创建scope。固定预算由profile决定。

锁序沿tenant_budget→全部task按规范UUID ASCII升序（caller及至多8目标）→当前actor及按name排序的installation→history→scope/targets/原槽。单租户budget锁保持既有串行保护，但不能依赖它跳过最后权限核验；撤权代码也须遵循相容锁序。受理生成caller UUID后、插入前定点锁全部目标，不反向锁另一租户。每个申请目标必须同时满足：同tenant、owner等于当前认证actor、当前actor具备read和session_search授权、v4合法binding/pins、scope_count=0、历史PRESENT、保留至少剩余180秒、真实SUCCEEDED/FAILED/CANCELLED且全部写者静止、无UNKNOWN/未结调用或其他未结占用（source自身SESSION_HISTORY_STORAGE预留除外）。来源read pins与新caller相同安装epoch/version/digest，source自身scope空；不接纳NEEDS_RECONCILIATION。不满足任何一项整体404 SESSION_SCOPE_UNAVAILABLE，不返回缺失ID/存在性/数量/版本；无部分成功、过滤后悄悄缩scope。

scope由宿主从已核行捕获：caller/tenant/owner、caller原binding/tools摘要、authority_version=1、authority_digest、candidate_count、canonical targets（source_id/history_version=4/binding_digest/pins_digest/upper_seq/retain_until/created_at），按source_id排序；上下界均非模型输入。scope外键绑定caller task且校验其immutable tools/history摘要；scope自身摘要由step/原invocation引用，不向旧tools Schema添加字段。caller的retain_until受理时固定为min(自身+604800秒、全部source.retain_until)。候选upper固定为该源terminal public log最后seq，0–127；同一候选集合贯穿整个task，不刷新发现后来的task/事件。

新增 `DELETE /api/tasks/{callerId}/session-search-scope`：认证owner且当前任务身份可见，授权后即使flag off仍可撤销；请求不允许querystring或body。owner无权/不存在统一404；幂等204无body；ACTIVE→REVOKED、revoked_at写一次，不允许恢复/扩大/修改targets。revocation事务与所有read/commit用相同锁序，线性化后不再公开衍生内容，运行caller通过既有fence/取消/失败收尾停止接纳新输出；不直接改被查询任务状态。此接口只撤当前caller授权，不删除来源。

## 3. 模型输入、输出与独立read

session_search保留query及上游可选字段完整Schema。总输入4096 UTF8 bytes/深度4；query原始及规范化<=256码点/1024 UTF8 bytes，拒绝NUL/纯空白；session_ids/parent_session_ids 1–8且unique，event_types至多5唯一，availability至多2唯一，event_surfaces至多3唯一。数组空/重复/超限和未知enum拒绝。未知key/重复key/尾随值/无效UTF8/孤立surrogate拒绝；seq只接受JSON词法`0|[1-9][0-9]*`且<=9007199254740991，拒绝-0/负数/浮点/指数/字符串/boolean。时间沿v2严格Gregorian带秒和时区格式、最多6位小数、微秒精度比较，含端点，原始反向范围400，不因先裁剪而掩盖。Schema验证后另做原始字节、数值词法、日期、array和Unicode检查。

parent_session_ids/include_root_sessions/availability/event_surfaces只定义语法，任何合法提供值均400 SESSION_SEARCH_FILTER_UNSUPPORTED；错误不访问来源。其他过滤：各项AND，列表内OR；created_at取source task受理DB时间；event_*取来源公开日志。session_ids必须全部属于原scope，caller/任何scope外ID一律404 SESSION_SCOPE_UNAVAILABLE，不检查外部是否存在；省略即完整原scope。先核完整原scope当前权限，过滤不能绕开已撤权源。seq交集为空是真实空，但其他候选依然遵循完整性/来源核验，过滤不得掩盖坏来源。

全局输出是contracts中JSON对象：caller_session_id、scope_sha256、boundary=scoped-admission、content_policy、match_policy、ordering、returned_count、capped、items。每item只含source session_id/created_at/history_version/upper_seq/best_match；best_match真实seq/type/time/source/redacted/event_sha256/snippet/裁剪标记。不输出未经授权候选数、扫描计数、总命中数、scope原列表、标题或虚构字段。items最多4，每会话恰一最强事件；完成全部授权扫描后才设置capped（匹配会话>4）。按非重叠匹配次数降序、规范文本码点长度升序、事件time降序、session_id ASCII升序、seq降序；会话内去掉session_id维度。同source相同time取seq较大。片段以首命中前80码点为起点，至多240码点、JSON字符串<=1536bytes、hit<=2048bytes；输出<=16384bytes，超字节预算失败而非默默删item。

read@0.4.0 input仍为seq/session_id/before/after（保留旧输入原字节），但输出明确新版本。省略session_id读caller pre-step范围；显式caller同样；显式source必须原scope内、独立当前授权及source pin/保留复核，用scope固定upper，read boundary=scoped-admission。返回源完整event-v4和至多8条邻接摘要，不截断target。输出中的session_id是真正源ID。后续read不依赖有无搜索命中，不使用搜索结果作authority。模型必须在下一response取得前槽的返回值；已接纳同response后槽参数不能动态改写。

event-v4保持五类type，model.response calls只许新两工具公开参数投影。session.read新data包含target_session_id；新顶层source_event_refs保存实际成功返回的<=9个{session_id,seq,event_sha256}，本地和跨源均用相同形状；非read为[]，失败read为[]。不使用v3 source_event_seqs或V21 relation_version=1；v4 read单独原槽表捕获refs，原V21 read binding保留给v1–v3。只读receipt不内嵌output，禁止递归展开。v4不提供trace，也不把refs推断为模型因果或surface关系。

## 4. 当前权限、冻结上下文与一次提交

immutable caller authority来自lease tenant/actor/task及已存scope，不接受模型替换。M1前step记录scope_digest及固定sources snapshot，caller的原pre-step upper仍沿旧规则；context-v4只含真实source ID/upper/expiry和caller最近有限引用，不包含query或源正文。所有同response槽使用同份范围；来源terminal之后追加是协议破坏，不能被当新事实接纳。两个真实来源依次创建并终结，第三caller以其IDs受理即可完成闭环，不伪造历史。

M2校验原response index/providerId/name/raw input/pins，insert-only绑定每search/read invocation：原input digest、binding/scope/step-cut digest、normalized_query（search）、target/窗口（read）。query及scope列表/refs/context均是BODY。原输入、scope、candidate cuts、实际扫描seq/digest及原输出绑定原槽；每会话选代表前，每个候选事件连同原source_task/step/response/attempt/fence/可靠事件需独立验证，跨源证明以源task作为ancestry根，不能传caller冒充source。

查询在原单事务内执行，所有source/current actor/installation/scope锁覆盖到最终commit fence检查；每个候选、hit、公开metadata/计数、目标read再次受当前scope约束。成功输出Schema/digest/bytes、PENDING→SUCCEEDED、一次TOOL消费、可靠intent/result及绑定同事务；不先RUNNING、不走通用admit/toolSucceeded/uncertain旁路，新capability在旁路明确SESSION_SCOPE_COMMIT_REQUIRED。search不追加原生session.read，read才在caller追加真实read receipt；来源不写状态、cursor或业务事件，不唤醒来源。

成功重放先核当前authority/完整scope/全部source保留/pin/fence/flag、原step/slot/receipt及输出digest/Schema，再返回原字节；不重新发现/扫描或替换代表。PENDING重跑只用原已存scope/cuts；撤权/expiry后不返回过去输出。授权/flag/fence/取消/截止拒绝不消费；安全确定失败可一次绑定code但不能保存原输入或候选详情为错误。provider损坏/故障/超时回滚，无空结果/fallback；commit确认不明走SessionCommitUnconfirmed，原写者静止后同槽恢复，不另记失败覆盖成功。真实模型usage照常结算，被拒绝响应正文不接纳。

## 5. owner入口、撤权与副本公开边界

owner查询仅 `POST /api/tasks/{callerId}/session-history/session-search`，同session_search body；path是caller，body无caller/session单值路径。复用Authenticator和非阻塞BodyFilter，认证后建立整体2000ms绝对截止，先授权caller和scope再读body，Content-Type application/json/无querystring/流式4096bytes，授权+body+所有连接/握手/锁/计算共用截止。owner使用相同固定scope/source cuts，boundary仍scoped-admission，不发现其他历史；终态caller可查其仍有效scope，零工具预算/零状态变化，Cache-Control:no-store。

独立owner读取来源可继续用已有 `/api/tasks/{sourceId}/session-history/{seq}`，按source自身当前授权/pin复核；caller scope不替owner授予额外权限。若owner要以caller scope核查，模型新read用例覆盖，不把旧owner接口悄改为跨scope参数。新GET `/api/tasks/{callerId}/session-history`按v4新Schema返回公开metadata，不返回原scope列表给无权主体。

任何来源过期、清理、pin/actor失权或scope显式撤销：所有caller衍生BODY立即不可公开。必须对task GET summary、历史metadata/events/read/search、SSE订阅与每次投递、模型请求构造/接受、checkpoint恢复和工具原字节重放设置统一v4 scope gate；不得只守搜索controller。普通task GET在scope失效时summary=null/artifacts=[]并给安全code，状态/取消仍可用；session历史统一404 SESSION_SCOPE_UNAVAILABLE；不返回部分合法hit、旧摘要/请求。SSE原协议无正文但仍逐次复核并停止订阅，不生成含候选ID的错误。私有持久快照只供恢复/清理，普通download没有v4制品；本profile禁止新增产物。

授权与输出的线性化点是持锁最后检查/commit或owner只读事务完成；之后撤权不追溯已授权发出的响应。源到期以DB时间及固定最早上界判断，提交前再查剩余截止，不能由缓存重放拖延。显式撤销不可逆；撤销/当前源失效时为scope写一次invalidated_at（若scope当时尚有效），作为停止新模型接纳和提前安全清理依据。若仍有活写者/UNKNOWN，正文仍存且继续计费但停止一切公开；不由TTL假定写者已死。终态source不需要被唤醒或更改来完成这些检查。

HTTP安全错误：401未认证；404 SESSION_SCOPE_UNAVAILABLE（任何scope/target无权或不可用，不包含ID/数量）；400 TOOL_INPUT_INVALID/SESSION_SEARCH_FILTER_UNSUPPORTED；409 IDEMPOTENCY_CONFLICT或本人caller历史损坏SESSION_HISTORY_CORRUPT/版本不支持；410只用于已授权caller自身到期且不揭示源；403 SESSION_SEARCH_DISABLED用于新准入/模型；429 SESSION_HISTORY_LIMIT；504 SESSION_QUERY_TIMEOUT；503 SESSION_QUERY_UNAVAILABLE。原fence/取消/截止code保持。SQL/provider异常不得原样返回/日志原query/路径/来源列表。

## 6. 全查询硬界限、持久化与清理

| 资源 | 整体硬上限及失败行为 |
| --- | --- |
| scope/发现 | 0–8目标，候选只读scope_target(tenant,caller,source)PK范围LIMIT9检测坏库越界；申请至多8次task定点PK，不扫owner/租户索引，不OFFSET；总scope snapshot<=4096bytes |
| 原生候选 | 每source0..upper共<=128行，每行<=16KiB；全查询<=1024行及4,194,304事件正文bytes（不是8乘每task2MiB）；先计量再解析，超限失败；连续0起、count=upper+1、next_seq<=128且>=upper+1；无匹配也完整验证 |
| 证明/内存 | 累计事件+所有来源证明读取<=16,777,216bytes，来源定点读取<=4096，scratch<=16,777,216bytes（解析树/缓存/序列化/排名/输出全部计入）；至多8排名节点，平面refs<=9；无无限JOIN/递归旧outputs |
| 计算/输出 | 全query共享16,777,216码点比较及2,097,152其他逻辑操作；每1024单位查截止，query prefix构造也计入；至多4个代表结果/16384bytes，完整read<=32768bytes/9行；超限不可假报capped或空 |
| 整体截止 | 每owner调用或模型tool整体2000ms，连接取得/握手/认证/SQL锁/证明/计算/提交共用，更早task deadline优先；锁<=1秒，物理连接及取消沿R1，不给每source或SQL另2秒，无迟到写者 |
| 原循环/正文 | 180秒/8model/20tool/8attempt/4槽；每task8MiB reservation、所有正文7MiB、metadata64KiB（正常<=61440留收尾）、tenant默认64MiB；模型request64KiB、response16KiB、checkpoint128KiB、summary8KiB、event16KiB和128events原上界保持 |
| 新持久副本 | scope canonical targets<=4096、每step context<=4096、query normalized<=4096、每read refs<=2048，最多8steps/20tools；所有副本实计，不能因每列上限小而免计原response/checkpoint/model-request/publiccalls/goal/summary/output |

source quota计source自己的存储；caller quota额外计它复制的全部正文/元数据，不把来源空间转移或重复释放。scope/targets/step切点/新绑定加入真实TABLES/BODIES和清理receipt，target ID/时间/upper/refs作为scope BODY清理，不能偷偷留在普通metadata；只留必要owner/caller墓碑和摘要/字节。实际target FK仅用于存活来源核验，purge可置NULL保留有限ordinal/digest墓碑；后端不得用FK保留敏感列表。新2048bytes refs上限容纳9个跨会话UUID+seq+digest，不重新解释v3原1024bytes来源快照。

为使终态caller的权限失效也可被清理发现，现有维护循环增加有界scope validation：按scope_validation索引的next_validation_at/tenant/caller做keyset，单轮至多8个scope、每个至多8个目标PK，共用2秒；成功校验将next_validation_at推进不超过60秒，失效单向写invalidated_at。公开入口始终即时校验，60秒是后台发现节奏而非公开宽限；批次超时回滚/下轮从持久游标公平接续，不热循环、不阻断其他安全清理候选。不得遍历全租户或在维护线程执行模型/来源工具。

源到期和caller最早retain_until均立即停止公开；caller可因scope撤销/invalidated_at提前进入安全清理候选。源/caller清理独立，无反向等待、无刷新scope、无延长七天；源删除先发生时caller gate通过源历史PRESENT/保留检查拒绝全部副本。清理只在实际终态/写者静止/无UNKNOWN或其他未结占用（自身待结history storage除外）时事务清空全部旧BODY+新增scope/target/cut/query/refs，PENDING绑定允许PURGED且仍无结果/来源/receipt，不伪造失败或执行。保留digest/bytes/tombstone真实计费；reservation自身结算字段宽度参与固定点，metadata=held=reservation.actual，tenant精确释放reserved-retained一次，双control/重试不重复释放。普通终态尚未到期的独立来源不得因另一个caller撤scope被删除。

flag off分别冻结：新准入/新M1/M2/tool/成功模型重放拒绝；已有owner有效scope历史可查；撤销和安全清理继续；失权/到期历史绝不因flag off/re-enable重现。新任务无scope能力不申请新存储桶，不阻断旧profile/其他tenant。保留REVIEW-54-01的PENDING/PURGED、R1授权+body+连接总截止、QA-52-01计账固定点。

## 7. 迁移与后端接入

V22候选DDL见migration-contract.sql；仅放行history/event v4、新scope/target/step-cut/query原槽表。所有旧行保持，V1–V21、旧profile/catalog/snapshot/ADR索引字节不改，不回填旧历史。新binary严格schema22；旧V21 control/worker就绪前拒绝22。理解22且flag off制品为回退，不降schema/生产checksum repair。候选DDL须由后端用真实SHOW CREATE TABLE核命名CHECK，迁移字段/索引/实际约束与本契约不一致须先交回，不直接删未知CHECK。只同步application_grants生成器，不执行共享/生产授权。

最小接入点：新增公开窄SessionSearchScopeLedger和plugin.api.SessionSearchPort，不让plugin依赖Jdbc；JdbcLedger负责宿主受理/授权及同库事务；JdbcSessionRecords按v4分派捕获/verify/account/purge并保存scope context；DurableAgent沿原循环、M1/M2与invokeSlot明确新profile及两个版本；TaskController专用新用例、SessionHistoryController/BodyFilter和task GET/SSE/恢复统一v4 gate；ModelProfiles、pin/catalog、bootstrap flag及schema gate显式装配。旧路径不改语义，不为本任务引入服务/多版本安装/团队执行。

产品经理接受差异后，原后端沿本架构提交接续唯一分支/草稿PR。首可构建切片：V22+v4资源/安装pin+专用受理/owner定点查询/全部BODY清理入口；然后原槽模型闭环和恢复，不先交一个绕过scope的模型工具。两小时检查点交首个可构建SHA/PR或证据化阻塞。本轮只向产品经理交回，不启动后端。

## 8. 准确制品验证完成条件

| 编号 | 原后端自测及同一完整SHA/tree/JAR原独审/QA必要验证 |
| --- | --- |
| C01 | 私有MySQL/真实control双worker/显式paid=false：创建两个真实v4空scope来源并终结→第三caller显式scope→query每会话代表→下一step按真实session_id/seq/digest独立read→重启/SSE重连/原槽接管继续完成；保留原协议/账本 |
| C02 | 同源多个匹配选最强、跨源五级并列、时间/seq/UUID顺序、4/5匹配会话capped、片段截断、无匹配全扫描；当前caller排除；过滤AND/OR/含端点/交集空；unsupported四类包括false明确拒绝 |
| C03 | scope申请同owner但未授权/跨tenant/owner/不存在/旧v1–v3/活跃/有scope来源/未知占用/伪造上下界/pins；输入ID不授权，错误无存在性/数量/正文；零scope不全域发现；安装切换旧任务撤权不回避 |
| C04 | 固定admission candidates/cuts，后来会话/事件不进入；同response多search/read、caller M1切点、原槽成功后不同query/扩大scope/新目标拒绝；恢复原字节/一次消费/可靠receipt，无来源状态写入 |
| C05 | 当前actor/安装/pin/scope撤销与提交各必要竞争，owner查询/新step/已成功重放/summary/SSE/checkpoint模型请求所有入口拒绝衍生副本；独立逐目标read再授权，撤scope不删除或唤醒source |
| C06 | 严格HTTP/模型JSON：4096输入、query码点/UTF8 N±1、重复key/未知字段/-0/浮点/指数/safeinteger/非法UTF8/surrogate/数组/时间；超慢body+授权锁共用2秒，owner及新准入均无迟到写者 |
| C07 | 8/9候选、每source128/累计1024行、全查询4MiB事件/16MiB证明scratch/4096来源读取/比较操作/4输出与字节必要边界；恶意扇出/巨大稀疏seq/损坏来源/provider错误/连接锁超时明确失败，EXPLAIN/rows examined有界；内部预算探针不冒充端到端容量 |
| C08 | synthetic凭据/路径/隐藏reasoning/原query不经内容/片段/metadata/计数/read/SSE/错误泄漏；公开参数投影与原槽私有输入区分；read refs跨session不误接v3关系，工具结果不递归 |
| C09 | V21→22/旧制品拒绝/全旧字节与checksum不变；新旧profile/pins按版本分派，flag off五路径；read0.4完整公开event核查，新source空scope可单独读，旧read/search/trace按触及差异回归 |
| C10 | 来源最早expiry立刻拒绝caller summary/model-request/成功重放等全部副本；source先清及caller先清两顺序；scope撤销安全终止后提前清理；PENDING未执行取消/截止/前槽失败不造receipt且可PURGED；全部BODY归零、metadata=held=actual、精确单次释放、双control、后续候选继续；UNKNOWN/真实活写者不释放 |
| C11 | task级8MiB/tenant64MiB全部新表/列/墓碑实计、reservation自身固定点；受影响directory/workspace/MCP/todo/问答/旧task定向回归；不重跑旧完整矩阵 |

保留实际命令/退出码/未覆盖项、准确SHA/tree/JAR摘要、原始协议/账本/失败/清理收据。架构Schema probe不代表迁移/产品测试；开发自测不替代独审/QA/业务验收。私有时间夹具不冒充实等七天，未穷举全部容量N±1/授权排列/DNS/TLS/清理事务内崩溃如实保留。历史Windows verify262项1failure/15errors、ADR索引冻结3446/实际3819及POSIX失败保持。不运行/启用/恢复/重跑/等待GitHub CI，不接生产/真实Key/付费服务，不部署/改共享服务、daemon、代理、FRP，不发送外部汇报。
