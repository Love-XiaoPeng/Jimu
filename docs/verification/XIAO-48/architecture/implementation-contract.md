# XIAO-48 最小实施契约 v1

此文件与ADR-0007、`contracts/`共同约束唯一后端实现。新增内容由后端入库，历史baseline与tools/evaluation/frozen保持原字节。交接基线为实际master `a04bc49a047827b292c7929312ea8d1ae9e31afc` / tree `74f134b2dbaa0c984256129fab2c41a9fb70a15d`。本包没有业务Java实现或运行验收。

## 1. 工具/API与调用入口

| 固定上游编号 | 模型名 / capability | plugin ID@version | JSON-RPC方法 |
| --- | --- | --- | --- |
| G02/T02 | list_mcp_resource_templates | jimu.mcp.list-resource-templates@0.1.0 | resources/templates/list |
| G02/T03 | list_mcp_resources | jimu.mcp.list-resources@0.1.0 | resources/list |
| G02/T04 | read_mcp_resource | jimu.mcp.read-resource@0.1.0 | resources/read |

list输入只有必填server、可选cursor；read输入只有必填server、uri。`additionalProperties=false`；不接收tenant、task、endpoint、credential、hostPath、command。server为1–64字符ASCII别名；uri为1–2048字节ASCII且符合后述URI策略；cursor为22字符base64url。显式null和空cursor拒绝。Schema的maxLength只是第一层，运行时仍按UTF-8字节数验证。

相对上游保留工具名、server显式选择、可选分页、read的URI参数及三条真实资源方法。云端差异明确为：更严格的输入/URI范围；上游逐页cursor改为有界全量采集后的宿主不可变分页；任意JSON输出改为版本化有界投影；binary原始数据不提供程序通道；不继承上游自动重连、server instructions或动态工具。它们是本批受控profile的兼容边界，不改写固定上游事实。

沿用原 `/api/tasks` POST/GET/cancel/SSE和错误DTO `{code,message,traceId}`；本批不新增HTTP工具执行端点。POST能力白名单增加三名字，上限7不扩大。含任一MCP能力时必须为三者的非空子集，resourceIds仍必须为空，不混合任何其他能力；不改变旧请求默认web_fetch行为。宿主在提交事务中解析当前tenant+owner获准的server，最多4个；零个拒绝，超过4个要求运维缩小配置，不静默选取。MCP关闭时三项不出现在capabilities且提交返回503 MCP_NOT_CONFIGURED。

提交及幂等重试继续原身份/权限/预算/installation校验。第一次受理原子存入完整MCP绑定、digest和原tools/model快照；同key相同请求返回原task，不能换成今天的服务器。列举可见别名只用宿主绑定构造普通说明文本，所有描述为数据。`McpResourcePort`的最小公开职责为listTemplates(server,cursor)、listResources(server,cursor)、read(server,uri)，typed input/output只使用JDK类型；tenant/owner/task/fence/cancellation由宿主闭包绑定，插件不能自己传入。

`DurableAgent.definitions/invoke`显式识别MCP，不把它送workspace；输入Schema验证→原ledger.admit→MCP准入→协议runtime→输出Schema/字节校验→原子结果提交。已SUCCEEDED缓存也必须先检查当前MCP绑定/撤权，不能在返回缓存之前绕过检查。只用已有ToolCallingAdvisor，禁用SDK隐式工具执行、重试、第二循环。MCP-only最终只调用finish(summary)，不触发通用fixture分支的自动artifact stage/publish。summary含本次使用的server/URI/sha256和截断说明，仍是非可信内容，不能为后续工具授予权限。

原BUSINESS报告policy1/2、旧评测scope、模型profile/paid开关均不扩大。本批受控任务采用已有可信本地测试装配、paid=false模型替身，真实协议资源服务；生产默认关闭。后端不能通过清空原业务策略、重绑旧账或改fixture允许生产放行来做通过记录。

## 2. 宿主服务器与能力快照

`server-profile-v1.schema.json`规定profile。`mcp_server`是运维/fixture身份拥有的白名单；应用账户只读。每行绑定tenant、server、hostId、唯一loopback endpoint、秘密ref+版本、allowedActors、允许的URI/模板、协议版本、serverInfo、完整serverCapabilities及本地schemaDigest。秘密实值不入库、不在日志/CLI参数/任务JSON/模型结果出现。profile序列化不超过8192字节；完整任务绑定不超过65536字节。示例只表示专有fixture配置，不能直接安装为生产授权。

`profile_digest = Payloads.hash(按既有Payloads规则序列化的完整类型化profile原文)`。保存原始canonical字符串后对原文哈希，不通过解析重排来验证旧快照。epoch为单调正整数；运维每次启停、URI/actor/secret/地址/版本/Schema变更都递增epoch，不能用相同epoch替换内容。数据库tuple、profile中的tenant/server/epoch与摘要必须一致；对象map的key必须等于profile.server。宿主配置按tenant+server+credentialVersion解析secret。当前hostId必须等于profile.hostId。

`McpTaskBinding(v1)`按server保存完整profile和其digest，作为独立task字段；原 `ToolSnapshot(schemaVersion=1)`仍仅存原工具pin。`schemaDigest`是本包 `schema-index.json`原字节的SHA256，索引列9个静态manifest/输入/输出文件的实际SHA256；catalog同时记录它。后端入库时保持这些文件原字节；有必要改动就更新新契约及证据，不在实现中偷换摘要。

每次模型/工具新准入、恢复、首次列表/读取、翻页、缓存返回检查：当前tenant+owner+task、原installation epoch及pin、现有run/scope/budget/fence/deadline/cancel、task绑定原文hash、当前mcp_server.enabled、epoch、完整profile digest、allowedActors、host-id、schemaDigest。缺绑定返回503、原文损坏409；另租户/未授权server一律403 MCP_SERVER_NOT_AUTHORIZED，不泄漏是否存在。撤权403 ACCESS_REVOKED/CAPABILITY_REVOKED沿既有事实；server撤权统一403 MCP_SERVER_NOT_AUTHORIZED。地址/secret/version变更不重绑旧任务，返回409 MCP_SNAPSHOT_MISMATCH。

调用前握手必须匹配绑定的protocolVersion=2025-06-18、serverInfo.name/version和完整capabilities；resources能力必需。服务重启但相同审核版本/能力可新握手；相同版本的数据变化用每次内容hash表达，不声称固定数据内容。能力变化/非预期版本失败，不自动发现并安装新能力。首次真实请求的服务端文件/实现制品SHA另进验证收据。

## 3. URI、分页与输出

资源URI只通过JSON-RPC params传递，绝不dereference。第一批仅支持宿主明确批准的custom scheme，例如jimu-fixture；http、https、file、ftp、data、javascript、jar等一律拒绝。只接受ASCII层级URI `scheme://authority/path`，无userinfo、端口、query、fragment、反斜线、百分号、空白或控制字符；path分段只允许 `[A-Za-z0-9_-]+`，禁止空段、`.`和`..`。不默默解码/归一化后放宽允许集合。

合法URI还必须逐字命中allowedUris，或逐字命中allowedUriPrefixes之一并且后续为上述合法path分段；prefix必须以`/`结尾、有完整scheme/authority边界。前缀不是regex。read不需要先列举，允许模型按已列模板展开，但展开结果仍须满足当前宿主URI规则；模板本身不是读授权。allowedTemplates只允许字面量安全URI加单个简单`{name}`占位符，name的展开为一个合法path段；本批不承诺RFC6570全部运算符。Schema允许表达的配置比实际安全profile宽，运行时必须拒绝不满足本段的配置。

上游列表中的URI先做相同URI/权限校验，仅投影获准项；模板必须逐字命中allowedTemplates且其展开范围受同一URI策略约束。不向模型泄漏被滤掉条目的描述或数量。保留name/uri或uriTemplate、可选title/description/mimeType/size；丢弃未知_meta/annotation等可执行或无关字段。已获准项若缺必需字段、超字段限额、重复URI/模板或不合Schema，整次失败，不截取坏数据作成功。服务返回的read.contents[*].uri必须逐字等于请求URI；其他URI直接失败，不能随返回内容扩大范围。

首次无cursor列举在同一次逻辑invocation中把上游所有页面采集成有界快照：每服务方法最多10页、每原始页最多100条、总最多1000条；每页cursor只接受服务刚返回的opaque字符串（最多1024 UTF-8字节），不解析、不允许模型直接输入、不跨请求generation使用。首次重复cursor、无进展重复页面、超过10页仍有nextCursor均失败。任一页失败不返回前缀成功；取消不发布部分列表。

完整授权投影保持上游顺序，按最多100条且最终JSON≤65536字节切为宿主页；单条放不进一页失败。总快照含所有页、cursor索引和元数据的计费表示≤1048576字节。以128-bit SecureRandom生成本地opaque cursor；cursor查找表仅存token的SHA256、rootInvocation、page、query digest和expiry。不可变页JSON保留nextCursor原token，以便重启后逐字返回相同页；token不承担授权，数据库访问仍按原私有账本权限。只给模型本地token。`cursor`重放不重新访问MCP，只读同一不可变快照。检查tenant/owner/task/current授权、toolpin、method/server、原快照hash和expiry；不同task/tenant/method/server、篡改或过期统一409 MCP_CURSOR_INVALID。重复同token返回相同页原字节，重启恢复同样成立；源服务器后来修改资源不改旧页。

列表输出带server、serverVersion、serverSnapshotDigest、schemaDigest、snapshotId、snapshotSha256、trust、page/returned/total/nextCursor。snapshotId为root invocation UUID；snapshotSha256是完整授权投影按原顺序的canonical数据摘要，不是当前页摘要，和root存储的原始serialized pages校验hash分别保存，避免自引用hash。翻页不更换来源身份。

投影中可选title/description/mimeType/size缺失时省略属性，不能输出Schema不接受的null。公开plugin.api DTO保持JDK类型；需要的null省略映射放在宿主序列化层。投影hash明确使用既有Payloads的typed DTO canonical序列化：`List<Resource>`或`List<ResourceTemplate>`，只含经过允许字段投影的原序数据，不含page、cursor或摘要字段；实际保存的原序列化字节是校验权威。不要另用会改变字段排序/空值规则的mapper重算旧快照。

内部持久body结构固定为 `list-snapshot-v1.schema.json`：tenant/owner/task/rootInvocation、server/method、bindingDigest、queryDigest、projectionDigest、按顺序的完整页JSON字符串和tokenHash→目标page映射；pages字符串解析后再逐一按对应工具output Schema校验，page从0开始，cursor目标从1开始。queryDigest为typed `Query(method,server,serverSnapshotDigest,schemaDigest,pluginVersion,pluginDigest,installationEpoch)`经Payloads序列化的hash，不含当前cursor。body_digest为最终内部body原字节hash；projectionDigest仍只散列完整授权items，无摘要自引用。字节限额和query/游标位置/内容的交叉一致性由宿主检查，单个JSON Schema不能代替这些检查。

read响应最多16个contents；严格UTF-8 JSON，无重复key、depth≤32；text编码为UTF-8计算真实bytes和sha256；blob使用严格标准base64解码后计算，不能按base64字符数冒充字节数。整次decoded contents≤262144字节；无效编码、缺text/blob、同时出现两者、总量超限失败。对text给总计最多32768 UTF-8字节的预览，在完整码点边界裁切；`textTruncated`准确标记，保留所有内容的完整bytes/hash。binary仅kind/uri/mimeType/bytes/hash，text=null且不送入图片或程序调用通道，不保存base64。mimeType缺省为text/plain或application/octet-stream，不把服务声明当可信嗅探结果。

read的snapshotSha256为有序完整content身份列表`(uri,mimeType,kind,bytes,sha256)`的canonical hash，snapshotId为本次invocation；text预览不构成完整内容副本。没有服务器revision时，这组hash是等效内容版本标识。完整read内容只在有界解析过程中存在；仅预览和摘要进入原invocation output。全部模型输出≤65536字节且标记trust=untrusted-mcp-content-v1。任何文本（包括资源描述、服务名版本）都只能进入tool回复/普通数据，不能成为system指令、权限、报告来源或media。

## 4. 协议、限额和失败

连接只到绑定的literal endpoint，独立NO_PROXY，Redirect.NEVER，HTTP/1.1，不用系统代理、DNS、自动重试或HTTP下载回退。每个资源操作initialize请求发送clientInfo jimu/0.1.0、capabilities={}、protocolVersion2025-06-18；响应合法且精确匹配快照后发送notifications/initialized，再发资源方法。所有POST都带Content-Type application/json及Accept application/json,text/event-stream，后续带MCP-Protocol-Version。通知正常接受必须202且无body；request正常成功200，一条匹配id的JSON-RPC2.0 result/error（两者不可同时有）。初始化不能取消；任务已取消则终止连接，不发送资源操作。

首批sessionless；收到Mcp-Session-Id拒绝profile；不保存或自动重建服务器会话。JSON/SSE均严格UTF-8、同一byte/time/depth界限；SSE支持CRLF/LF、注释、多data行、空行dispatch，按event:message解析JSON-RPC，累计不得超过响应预算。最终id必须匹配该请求，重复/不同id、EOF前无最终result、非法JSON、未结束最终event均失败。服务反向request统一返回JSON-RPC -32601并终止本次操作，不调用模型/工具；回复计入wire预算。资源list_changed/updated通知使当前未提交采集失败 MCP_SNAPSHOT_MISMATCH，不修改已提交历史快照。其余有限日志/进度通知丢弃但计字节和事件数，上限16；超限失败。不打开GET通知流/订阅、不导入server instructions、不进行tools/list或tools/call。

单逻辑invocation总30秒，且不超过任务剩余deadline；connect最多1秒、取消轮询/流读取片段最多100毫秒；以monotonic剩余时长防墙钟回退。每HTTP响应raw body≤1048576字节，含SSE事件；每逻辑调用累计raw body≤1048576字节，含初始化和所有分页；超限读取立即关闭，不能仅限制Content-Length。初始Schema/能力JSON≤8192字节。每task普通HTTP发送最多64次，独立cleanup通知最多20次且每invocation最多一次，总最多84；在已有fenced事务中先记账再发出，失败/崩溃也不退普通发送次数。初始化、initialized、分页、反向error都计普通发送；每个模型cursor调用仍占原TOOL额度，即使无网络。沿用原maxToolInvocations≤20，不新增隐藏循环预算。

错误沿用TaskFailure和现有任务终态，无成功外壳包错误。下表status为同步TaskFailure/HTTP映射；后台失败通过原task.code和event表达，GET任务仍按原查询语义返回200。

| code | status | 可观察语义 |
| --- | --- | --- |
| TOOL_INPUT_INVALID / INVALID_CAPABILITIES | 400 | 坏输入或越出本批能力集合；无MCP I/O |
| MCP_NOT_CONFIGURED | 503 | 默认关闭或无可用配置；不降级 |
| MCP_SERVER_NOT_AUTHORIZED / MCP_URI_NOT_AUTHORIZED | 403 | 未获准server/owner/URI；无资源I/O |
| MCP_CURSOR_INVALID | 409 | cursor作用域、摘要、过期不匹配；无MCP I/O |
| MCP_SNAPSHOT_MISMATCH | 409 | 当前profile、server能力或schema与原绑定不同 |
| MCP_PROFILE_UNSUPPORTED | 502 | 非选定协议版本/sessionless profile，不尝试其他传输 |
| MCP_RESOURCE_NOT_FOUND | 404 | MCP error.code=-32002，脱敏错误 |
| MCP_PROTOCOL_INVALID | 502 | 坏JSON/RPC/SSE/id/编码/结构，不作为部分成功 |
| MCP_UPSTREAM_ERROR | 502 | 其他标准RPC error或非预期HTTP状态；不透传正文 |
| MCP_UNAVAILABLE | 503 | 服务断开/连接失败/秘密不可解析/401或403凭据失败，无自动刷新 |
| MCP_LIMIT_EXCEEDED / STORAGE_BUDGET_EXCEEDED | 429 | wire、页数、bytes、事件数或存储越限；无前缀成功 |
| DEADLINE_EXCEEDED / CANCELLED / FENCE_REJECTED | 沿用现有 | 首个取消/截止原因不被cleanup错误覆盖，旧fence不能提交 |
| SNAPSHOT_CORRUPT / EFFECT_OUTCOME_UNKNOWN | 沿用现有409 | 保留原证据和占额，不重放不明调用 |

取消触发当前HTTP future取消、关闭body reader；对已经发出的非initialize请求尽力发一次 notifications/cancelled(requestId)，最多1秒且计cleanup预算；通知失败不改首因、不重发资源请求。可证明的本进程失败且fence有效才用toolFailed；响应之后提交之前崩溃、未证明停止、失去fence等沿原UNKNOWN/对账路径，不能因“只读”改写旧恢复规则。远端独立服务不因连接关闭就被宣称已停止；它只有合成只读数据，本批不产生远端写效果/付费。已SUCCEEDED调用恢复只取原output，未完成RUNNING调用不自动重发；PENDING可首次执行。存储/费用与现有结算事实分开，不把未知用量清零。

## 5. V15最小持久数据与事务

保持V1–V14原字节，新增V15及严格schema gate，新旧二进制先排空/排除再迁移。task新增nullable `mcp_snapshot MEDIUMTEXT`、`mcp_digest CHAR(64)`，两者同为NULL或同存在；新MCP任务保存binding-v1，其余任务保持NULL，旧工具/model/checkpoint/policy版本及请求字节不变。新路径检查完整MCP能力子集且绑定存在，旧路径不能凭NULL自动增加MCP权限。

| 表 | 主键/核心内容 | 权限与索引 |
| --- | --- | --- |
| mcp_server | (tenant_id VARCHAR64,server_id VARCHAR64 binary)；enabled、epoch BIGINT、profile_snapshot TEXT、profile_digest CHAR64；tenant FK | 应用仅SELECT；运维单调epoch写入；按tenant/server定位 |
| mcp_io | (tenant_id,task_id,invocation_id,ordinal INT)；request_id VARCHAR128 nullable（通知无id）、method VARCHAR64、kind NORMAL/CLEANUP、phase DISPATCHED/RESPONDED/FAILED、request_digest CHAR64、error_code；task/invocation复合tenant FK | 应用INSERT/UPDATE/SELECT；按tenant/task/kind计数；不存Authorization或服务正文 |
| mcp_result | (tenant_id,task_id,root_invocation_id)；version1、server、method、binding_digest、projection_digest、body MEDIUMTEXT nullable、body_digest、byte_size BIGINT、expires_at、state RETAINED/DELETED；tenant复合task/invocation FK | 应用INSERT/SELECT；仅清理UPDATE body/state，不能更新已提交内容；索引(state,expires_at,tenant_id,task_id) |
| mcp_cursor | (tenant_id,task_id,token_digest CHAR64 binary)；root_invocation_id、page_index、query_digest、expires_at；复合result FK | 应用INSERT/SELECT/DELETE；任务内token唯一；按root清理索引 |

FK不能只引用裸UUID；到task的FK为(tenant_id,task_id)→task(tenant_id,id)，到invocation为(tenant_id,invocation_id或root_invocation_id)→invocation(tenant_id,id)，并在同一fenced事务验证该invocation.task_id等于本task。cursor到result使用完整三列FK。每个数据库访问带tenant和task，owner从task绑定核实。mcp_result只持久首次list的完整快照及页序列，read沿原output。首次list先reserve1MiB，category=MCP_RESULT/id=`mcp:<invocationUUID>`，复用tenant_budget.storage_held/storage_limit及原storageHeld总计，不增租户总额；最多4个root/4MiB每任务（失败或UNKNOWN占额也算）。实际逻辑计费bytes为snapshot body UTF-8字节+每cursor固定96字节的保守计费，整体不得超过预留；不声称等于InnoDB物理空间。

成功事务同时写result/cursor、原invocation SUCCEEDED/output/hash及event、TOOL结算与MCP_RESULT实际bytes结算，释放预留差额；二次提交只验证原收据，不另写一份结果。网络I/O绝不持有数据库事务。按现有run→tenant_budget→task→installation/server（按server排序）→invocation→mcp_io/result/cursor顺序加锁，准入和当前server epoch校验同事务；网络返回后再次验证当前授权/fence再提交。mcp_io的DISPATCHED先提交再发HTTP；中间崩溃保留发送占数，不能当作没发退款重放。

所有列表快照/cursor固定7天retention，expiry不被重复读取延长。仅终态且已证明quiescent任务清理；在同数据库事务中删除cursor、置result.body=NULL/DELETED，再释放MCP_RESULT占额并记录一次清理事实。无持久文件，因此无需宿主删除；残留/UNKNOWN或未证明停止保持预留，不按过期直接释放。原invocation输出/摘要和I/O次数保留历史，不重写旧账。应用grants新增后三表相应DML，mcp_server仍SELECT-only；不因此给scope/receipt/Flyway或运维元数据增加写权限。迁移不默认生成server、secret、installation或新run。

## 6. 交付与验证退出条件

后端先核master/现有分支和在途run，再将此ADR/契约与9个静态契约文件入库、创建面向实际master的关联草稿PR。首个可构建切片为受控服务initialize→三方法→schema-bound结果，经真实task/ledger/唯一循环；不是直接调用adapter测试。两小时检查点交首个可构建切片/PR进展或真实阻塞，产品经理安排唯一实施、原审查与QA，不另开同后端/子agent。

必要验证按差异选择，不重跑旧P2/P3/P4完整矩阵，不运行/启用/等待GitHub CI。要求记录完整SHA/tree、源码/构建jar SHA256、JDK/Maven和依赖树、命令/退出码/测试数、受控服务制品hash、真实HTTP原文（脱敏）、账本读回与fixture清理；原失败尝试也保留。

1. paid=false授权任务经控制+worker：模板、两页resources、允许URI文本/二进制摘要；JSON和POST SSE均覆盖，结果来源hash与fixture原字节一致。
2. 同名server跨tenant、跨owner/task、未白名单、模型endpoint/credential注入、坏URI/编码/路径穿越、未批准模板展开、raw上游cursor、篡改/跨方法/过期cursor均拒绝；计数证明没有未授权资源I/O。
3. 配置/secret/actor/installation/server撤权、epoch/能力/Schema/hash变化在首次调用、翻页和已成功缓存恢复处均拒绝；server断线不改变既有历史快照，但不允许新读取。
4. 源改动后旧页不变、同cursor重放和control/worker重启原字节相同；已成功read不重新访问，RUNNING崩溃保持UNKNOWN，PENDING首次执行；旧无MCP任务恢复仍按原快照。
5. 未知资源、RPC/HTTP故障、错id/重复key、坏/截断SSE、超页/重复cursor、stream慢滴/响应/decoded bytes/事件/wire/存储/工具预算越界、取消与deadline首因及失fence提交被拒绝。
6. literal endpoint、NO_PROXY、3xx、hostname/其他端口/路径/public地址拒绝有实际连接计数；产品worker不启动服务/子进程，fixture根/服务PID/端口关闭有实证；不修改生产出网策略或共享服务。
7. 恶意正文与instructions不进入system、tools/call、image media、workspace/report source或artifact发布；MCP-only finish确实零stage/publish；原报告/图片快照及UNKNOWN/锁/费用必要受影响回归。
8. V15应用/运维grants、旧NULL兼容、未知绑定版本拒绝、重复提交幂等、事务崩溃与存储清理不提前退款；同一准确源码/制品供独审和QA，再交产品验收。

当前架构实证只覆盖传输可行性与契约文件，不预先声称以上产品测试通过。真实外部服务器、生产TLS/OAuth、stdio、任意动态工具、完整MCP兼容、真实模型/搜索费用及生产隔离均未验收。
