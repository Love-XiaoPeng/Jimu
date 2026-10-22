# XIAO-49 最小实施契约 v1

本文件与 ADR-0008、`contracts/catalog.json` 一起约束本批；固定 master `0e630f28e39c157788886694e2b16dc0831bb571` / tree `8850a9d11f610257d87962585221b91a6bc19948`。由原后端唯一实施，产品经理派工。可自行决定内部类拆分和 SQL 写法，不能另选状态/授权/回答或恢复语义。尚未实现的内容不能作为已通过证据。

## 1. 接入边界

入口仍为 POST `/api/tasks`，只新增允许唯一能力 `ask_user_question`。沿用原 Idempotency-Key、owner、当前安装、原模型 snapshot、原 180 秒绝对 deadline 上限、8 次模型/20 次工具上限，以及更严格的本批 8 个问答 batch 上限。resourceIds 必须空，不允许与其他能力组合；不新增用户选择模式/模型或任意接收人的字段。现有默认 web_fetch 请求及其他任务完全按旧路径。

使用独立 fixture tenant 的真实 control、两个 worker、专有数据库及本地 paid=false 模型；宿主启用开关 `jimu.questions.enabled` 默认 false，开启不创建 actor/installation、不扩大付费、部署或其他工具授权。关闭后新任务拒绝 403 `QUESTION_NOT_AUTHORIZED`；已接受而未终结的本profile任务由维护器以 `QUESTION_FEATURE_DISABLED` 失败并关闭，禁止新答案或恢复执行，已授权的历史读取/幂等receipt仍保留。不能因功能开关或无问题阻塞全局 readiness。没有问题时不发模型/工具请求或追加事件。

目录边界：HTTP 在 `api`，公开用例在 `kernel.task.api`，SQL 在 `adapters.persistence`，绑定 runtime/contract 在 `adapters.runtime.interaction`，插件在 `plugins.builtin.interaction`，窄问题/答案类型与端口在 `plugin.api`。Controller 不能依赖 JDBC、adapter 或具体插件。bootstrap 只装配。不要将 ask 送入当前“非 web_fetch 就是 workspace”的分支。问答任务最后只写 summary；不 prepareEvaluation/stage/publish，不伪造 present。

窄端口固定为 `plugin.api.UserQuestionPort.ask(questions, optionalTimeoutSeconds) -> AnswerBatch`；runtime按不可变lease/来源call/pin绑定其实现，插件不能提供身份/数据库/任意invocation。`kernel.task.api.UserQuestionLedger.openOrResult`负责第6节原子操作，公开的HTTP用例为list(identity,taskId)与answer(identity,taskId,requestId,key,command)。暂停标记留在宿主runtime/执行适配层，不成为插件输出、跨版本序列化类型或HTTP错误。

## 2. 上游事实与明确差异

| 固定源码 | 核到的事实 | 本批云端契约 |
| --- | --- | --- |
| tool-ask-user `src/index.ts:19–138` | 默认 legacy；必需 questions；id/question/header/options/multi_select；传 agent/signal；返回 answers | 0.1.0 blocking；沿用字段和答案形态；模型/HTTP 未知字段严格拒绝，添加数量/字节约束 |
| `src/timed.ts:19–210` | timed 才有 timeout，默认120；-1无限逻辑等待；范围至2147483；batch内id唯一；pending带callId/message | 0.2.0 由宿主选；上限收紧至180，与原任务 ceiling 一致；-1也不突破 task deadline |
| user-questions `src/index.ts` | 校验非空问题、exact live root、拒绝 delegated caller；scoped waterfall；迟到答案 exact每问题一次，steer为普通user消息 | 根身份映射为当前 tenant/owner/task；无子agent；POST受鉴权、按task/版本/pin匹配；由数据库 inbox 事实取代内存队列 |
| `src/timed-wait.ts`、Web `client/index.ts:208–279` | Host 无Client claim才本地计时；UI claim接管倒计时；断线释放claim | 数据库绝对 due 始终权威；连接/断线/打开或关闭面板均不暂停、延长或结算问题。没有 Client claim 协议 |
| Web `QuestionComposer.tsx:328–362` | selected返回标签，非索引；单选自定义替代选项，多选可同时带custom；显式skip返回selected空且无custom；推荐项只是草稿，仍须提交 | 精确校验标签归属/唯一；单选最多一个选择或一条custom，多选可组合；skip须真实POST。绝不自动选推荐项 |
| projection 与晚答 `answer()` | 工具pending和问题可回答分开；迟到消息 kind=answer_to_pending_question，绑定callId及原questions | 保留相同语义及原callId，另带服务器requestId；仅在同一未终结task继续；不自动新建/重开终态任务 |

原始证据共21文件，MIT原文及第三方声明一并保留；不引入 TS 包、Cordis、TUI、Node或新运行依赖。移植规则需在产品 notices 中署名 DeepSeek 并保留 MIT。本架构验证未执行上游 vitest/Web 测试，不把源码阅读称为上游运行通过。

## 3. 模型与用户答案

两套完整 manifest/input/output Schema 在 `contracts/ask_user_question-0.1.0/` 和 `-0.2.0/`。一个任务只 pin 其中一个版本；相同函数名不同时注册两版。原输出形态保持，例如多选问题可返回 `{"answers":[{"id":"features","selected":["A","B"],"custom":"C too"}]}`；custom可省略。返回id来自原问题，selected是精确标签。工具结果不接受/输出伪造的 responder、tenant、owner 或新授权。

每个batch必有1–4问题，每问题id为1–64 ASCII `[A-Za-z0-9_.-]`且batch内唯一；跨batch可重复id，服务器requestId和来源invocation不同。question≤4096 UTF-8字节、header≤128；options可省略，给出时1–8项，label≤256、description≤1024字节，非空且label精确唯一；multi_select缺省false。问题不能携带上游service的intent/detail或批准目标字段。

每个回答batch须对原问题各给一项，不多不少、不重复id；selected精确属于该问题选项、不重复。不提供options时selected只能空。单选 `selected` 最多1项且与custom互斥；多选至该问题选项总数，可伴custom。custom≤2048 UTF-8字节，提供时不可为空白；不自动trim/改写用户正文。selected空且省略custom是用户显式skip，不等于批准或pending。

问题输入、canonical answer、工具输出分别≤16384 UTF-8字节（以最终紧凑JSON实际字节计，不以字符数冒充）。Schema中的字符限制只是第一层，runtime还必须验UTF-8/总字节及跨字段规则；超限拒绝，不截断问题或答案。模型参数与HTTP body均用严格JSON解析：重复键/尾随数据/深度>16/非整数字面量timeout拒绝，不用会覆盖重复键的JsonNode读取替代。答案规范化为原问题顺序、原选项展示顺序；custom原样保留，optional缺省省略；使用本项目typed Payloads规范序列化后算digest。任何敏感宿主值不由runtime注入；用户文本仅为不可信数据，不提升到system消息或工具权限。

timed未获答案在due时保存固定输出 `{"pending":true,"callId":"<原provider callId>","message":"<catalog中的固定pendingNotice>"}`。callId不是问题查询凭证，更不能当task或owner。多个callId相同也不重绑定：来源以 `(tenant,task,step,slot,invocation)` 为准；API只用服务器UUID requestId。

## 4. 最小受鉴权 HTTP

所有路由仅 control，先经原 Authenticator 校验 Authorization 签名/issuer/audience/exp/sub映射，再在短事务复核当前 actor、tenant/task owner、task能力和安装/版本/epoch/binding摘要。worker readiness不开放回答。每次读、首次答、幂等重试及恢复都复核；禁用actor/安装返回403，已变更pin返回409，不以旧JWT或旧问题快照续权。

GET `/api/tasks/{taskId}/questions`：200 `{"version":1,"items":[...]}`，按batch ordinal升序，最多8项，无分页，响应≤524288字节。每项包含requestId、questionVersion=1、revision、来源invocationId及callId、mode、phase、questions、foregroundDueAt（可null）、answerExpiresAt、answerable；已答时还含服务器answerId/answeredBy/answeredAt/answers/deliveryState，关闭时含closeCode。questionVersion表示不可变内容版本；revision用于状态CAS。只有所属owner可读问题及答案，模型仅通过已提交工具结果或持久user消息得到答案。未知/其他tenant/owner/task统一404 `TASK_NOT_FOUND`，无问答能力的可见task返回200空items；当前撤权403优先。body到期见保留规则，返回410。

POST `/api/tasks/{taskId}/questions/{requestId}/answers`：必需原格式Idempotency-Key（1–128可见ASCII），body严格为 `{"questionVersion":1,"expectedRevision":1,"answers":[...]}`，JSON重复键、未知字段、尾随数据、非整数、强制类型转换均拒绝。HTTP读取上限32768字节，超过413 `REQUEST_TOO_LARGE`。对应Schema见 `answer-request-v1.schema.json`。

首次提交在答案及唤醒事实同事务提交后202，返回固定receipt `version,requestId,answerId,questionVersion,acceptedRevision,answeredBy,answeredAt,delivery`；delivery为 TOOL_RESULT 或 USER_MESSAGE，并不承诺执行已完成。不返回host路径或凭据。响应丢失后，同owner/task/key且同规范请求（含原requestId/版本/revision/答案摘要）重试200原receipt，不追加事件/答案/唤醒；授权仍须有效，已终态不妨碍这个只读幂等receipt，保留期过后410。

同key不同内容或换requestId：409 `IDEMPOTENCY_CONFLICT`。不同key竞争同问题仅首个有效，后者409 `QUESTION_ALREADY_ANSWERED`；旧revision409 `QUESTION_REVISION_CONFLICT`，未知questionVersion409 `QUESTION_VERSION_UNSUPPORTED`；跨task/tenant/owner requestId统一404 `QUESTION_NOT_FOUND`。答题shape/选项/字段或限额错误400 `QUESTION_ANSWER_INVALID`；当前终态/取消409 `QUESTION_CLOSED`；到达绝对answerExpiresAt为410 `QUESTION_EXPIRED`；本批容量不足429。数据库不可用503 `LEDGER_UNAVAILABLE`，错误体统一 `{code,message,traceId}`。精确先后：鉴权/可见性/当前授权 → 同key receipt → 终态/截止 → 版本/revision → 答案校验 → CAS提交。未经授权不通过错误差异探知问题。

原 SSE envelope v1 不扩正文，可靠类型新增 `question.created/question.answered/question.continued/question.closed`、`task.waiting_user/task.resumable`；fact由invocation/request/answer固定标识去重。连接或轮询不生成事件；question细节通过GET获取，沿用原SSE重验授权/Last-Event-ID规则。

## 5. V16 最小持久结构

V1–V15原迁移保持原字节；V16新增下列结构，迁移不插入任何安装/授权或改写旧snapshot。

| 位置 | 新字段/约束及访问依据 |
| --- | --- |
| task nullable列 | question_binding、question_binding_digest、waiting_question_id、question_continuation_step_id、question_cleanup_state；无本能力任务均NULL。binding(version=1)固定profile=`local-durable-questions-v1`、mode、默认timeout120、catalogDigest及本批限额；旧ToolSnapshot/model/checkpoint/Request字节不变 |
| tenant_budget | question_tasks_held非负、question_tasks_limit默认16。问答task准入时预留1个交互任务位及2MiB现有storage，终态且所有写者静止后释放任务位；等待/RESUME_READY不占queued或running。存储直到真实payload清理才释放 |
| user_question | PK(tenant_id,id=requestId)；task_id、owner_actor_id、invocation_id、ordinal、record_version=1、question_version=1、revision、binding_digest、mode、effective_timeout、phase、request_snapshot/digest、created_at、foreground_due_at、answer_expires_at、retain_until、close_code；UNIQUE(tenant,task,invocation)、UNIQUE(tenant,task,ordinal)，FK(tenant,task)与(tenant,invocation)，写入时再验invocation.task/step/slot/owner完全一致 |
| 同一行的不可变答案 | answer_id、answered_actor_id、answered_at、accepted_seq（task锁内分配）、answer_snapshot/digest、answer_key、answer_request_digest、answer_source_revision、receipt_snapshot、delivery_state(NONE/PENDING/CHECKPOINTED/DISCARDED)、delivery_step_id；未答答案字段全NULL、delivery_state=NONE，接受后禁止修改答案/key/身份。UNIQUE(tenant,task,owner,answer_key)，key使用与既有Idempotency-Key相同二进制排序规则 |
| 索引 | (tenant,task,ordinal)供GET；(phase,foreground_due_at,tenant,task)与(phase,answer_expires_at,tenant,task)供有界due扫描；(tenant,task,delivery_state,ordinal)供新checkpoint吸收；(retain_until,tenant,task)供清理。task原task_ready索引可涵盖新增state，无需全表锁或全局队列 |

`phase=OPEN/CONTINUED/ANSWERED/CLOSED`；`OPEN`不等于task RUNNING。新增task `WAITING_USER`（无lease）与 `RESUME_READY`（持久可调度且仍无lease），attempt新增`PAUSED`，invocation新增`WAITING_USER`。它们均为question协议v1，不能伪装旧RUNNING/FAILED。只有V16兼容二进制理解这些状态。

一个task同时至多一个正在阻塞调用的OPEN问题batch；waiting_question_id只指向它。该batch回答或foreground到期的同事务清空指针；CONTINUED旧问题的迟到回答不能清空另一batch的等待。每个持久问题记录都由服务器UUID标识，模型不能指定该ID，source invocation相同的重入必须返回原记录而不是换UUID。

锁顺序沿用当前真实源码：可选evaluation run锁 → tenant_budget → task → question/invocation/reservation/event；本profile没有evaluation绑定，禁止反向拿锁。外部I/O、等待用户和operation drain均不持事务。所有状态变化条件更新必须检查命中1行。

## 6. 原子执行与崩溃窗口

1. M2原事务先固定完整模型响应、原顺序slots及PENDING invocation；本profile检查累计问答调用不超过8。新的 `UserQuestionLedger.openOrResult(lease,step,call,pin,input)` 是网关的专用准入操作，复用现有授权/epoch/pin/digest/预算检查，但**不能先单独提交通用admit的RUNNING**。
2. 首次PENDING槽在同一事务消费1次工具次数、建立唯一requestId/input/expiry、invocation→WAITING_USER、task→WAITING_USER、保存waiting_question_id、attempt→PAUSED(quiescent仍false)、fence递增并清空current worker/attempt/lease，写question.created/task.waiting_user。不得清空旧step、成本、UNKNOWN、artifact锁或其他预留。提交前崩溃全部回滚，重试同PENDING；提交后崩溃按唯一来源读到原问题，不再扣次数。
3. 暂停标记在专用ask路径传播，run捕获后返回；WorkerLoop原finally先awaitDrained再按原attemptId/worker/fence写quiescent。收据可来自已失效task fence，不能用于写其他事实。所有历史写者可信静止后，幂等releaseRun，仅释放当前RUNNING预留；否则保留安全占用，不以PAUSED、超时或lease清空冒充静止。崩溃在此窗口则由原准确PID/startTicks/boot/namespace supervisor收据接续，不能因本问答绕过死亡证明。
4. 首次答：锁task/question后检查DB当前时间。若foreground due已过，先在本事务按第7步结算pending；调用方旧revision须返回409并刷新，若revision已匹配结算后的状态才按迟到路径保存。已经到期的pending/关闭事实必须提交后再返回拒绝（沿用现有AdmissionResult的post-commit rejection方式），不能抛异常回滚due，造成永远返回陈旧revision。不能让争锁顺序延长due。若未过且phase OPEN，答案、服务器身份、receipt、canonical tool输出及hash、invocation WAITING_USER→SUCCEEDED、工具预留结算、question→ANSWERED、delivery=TOOL_RESULT、task WAITING_USER→RESUME_READY及唯一事件同事务。所有phase变化令revision递增1；输出Schema/总字节在提交前验证。
5. RESUME_READY领取必须复核当前actor/安装、所有pins、binding、deadline、取消及模型/调用/存储预算，并确认历史attempt全部静止、旧run已释放且tenant有运行槽。原子重新获得运行槽、创建新attempt/fence、task→RUNNING；恢复不占普通queued额度，也不对重复领取多扣tool/model预算。固定复用task原`:run` reservation：在同事务仅RELEASED→RESERVED并增加running，要求命中1行，不能再次INSERT同id或多加running。读取已成功槽不要求再消费一次工具额度；新的模型/工具派发仍须各自有剩余额度。
6. 原M2响应恢复进同一个advisor；SUCCEEDED槽只在重验当前授权/输出digest/Schema后返回原字节，后续槽串行执行。WAITING_USER槽不可调用通用uncertain；只有本专用记录证实该等待才可恢复，普通RUNNING/UNKNOWN维持旧保守事实。多worker抢占、重复维护和旧fence更新均只能产生一次有效接续。
7. timed due：DB CURRENT_TIMESTAMP(6)≥foreground_due_at且小于task deadline，维护器同事务使OPEN→CONTINUED、invocation WAITING_USER→SUCCEEDED并保存固定pending、工具预留结算、task WAITING_USER→RESUME_READY、写唯一question.continued/task.resumable；blocking/-1没有foreground_due。没有连接也按due处理；task截止优先于pending，不能在截止后再制造执行。
8. 迟到答：只有CONTINUED且task非终态/未取消/未过deadline可接受。一次性保存答案/receipt，delivery=USER_MESSAGE、delivery_state=PENDING；原SUCCEEDED pending输出完全不改。若task恰在等待另一问题，不解除那个等待；若运行中，等下一个模型边界；不额外创建attempt或第二循环。
9. 新模型prepare的同一事务，在原持久历史末尾按answer接受顺序加入所有PENDING回答的普通user消息（kind=answer_to_pending_question、tool、callId、requestId、原questions、answers），并把这些答案标CHECKPOINTED/stepId。保留来源元数据，不插system角色。已有PREPARED/RUNNING/RESPONDED请求原字节不变、不注入重发；先完成该请求/槽，再准备新请求。重启用原request_snapshot，不能再次追加同answer。所有内容计入原token/输入/次数预算。
10. 正常模型最终文本与late-answer竞争：finish与answer锁同task。finish先提交则关闭所有未答问题，后答409；answer先提交且尚未CHECKPOINTED，则本次finish不能成功。固定处理为在短事务保存 `question_continuation_step_id=<已持久化最终模型响应的step>`，当前attempt→PAUSED、task→RESUME_READY、失效lease/fence、写唯一resumable事件，再抛同一暂停标记返回worker。finally/领取完全复用第3/5步。新attempt在启动现有唯一advisor前，按该step原request+原assistant最终响应+PENDING user回答构建后继消息；原子新prepare、标记回答CHECKPOINTED并清空continuation指针。prepare前崩溃按指针重建，之后崩溃只恢复新checkpoint，不向框架重复返回旧最终响应。此为原暂停/恢复协议，不在run内外再起第二个while/ChatClient/Agent循环；每个attempt仍只运行一个advisor。只允许真实PENDING答案触发；新的模型请求仍计预算，预算不足则确定性FAILED并保留答案/未交付原因，不假装成功。

## 7. 取消、撤权、截止及保留

绝对answerExpiresAt固定为原task deadline，创建/轮询/断线/超时/答案不延长。无foreground超时的blocking也受此界限；保持原deadlineSeconds≤180，本批不是无限长期任务。deadline扫描必须涵盖WAITING_USER和RESUME_READY，不能只扫描有lease任务。<=DB now已过期，和answer/claim锁同task；未知/锁/费用事实不退款或重置。

取消先记录原cancel_requested_at，立即关闭未答问题并禁止新回答/执行；无在途且可信静止可终结CANCELLED，有历史写者则按原停止协议收敛。尚WAITING_USER的本工具可确定标CANCELLED；截止/撤权标FAILED并保留对应code；已SUCCEEDED的pending或答案结果不改写。任何其他UNKNOWN仍导致NEEDS_RECONCILIATION。已答但尚未交付消息可记DISCARDED并保留取消/截止原因，不撤销原答案事实。

当前actor/installation撤权在GET/POST/resume即时检查；维护器也扫描等待/可恢复task使其失败，不为等待租约自动续期。安装恢复或换版本不重绑定旧task，旧问题不能借新安装恢复。答案只是信息，不能授予部署、付费、外部发送、权限变更或目标/计划批准；推荐项、skip、pending、沉默、断线均非许可。

每问答task准入预留2MiB storage，覆盖本profile新增的全部持久文本副本（task request/goal/summary、step request/response、invocation input/output、question input/answer/receipt/迟到消息）；只计持久payload字节，行/索引元数据由上述记录数上限约束。模型canonical response≤32768、summary≤8192；新step仍受原本地checkpoint≤65536和token限制。每次新持久payload须核总量≤2MiB；必要响应最大空间在模型派发前保留，不能先产生外部费用后因存储不足丢事实。超过上限明确STORAGE_BUDGET_EXCEEDED，不静默截断。

保留至task接受时间+7天；届时必须task终态、所有历史写者静止、无UNKNOWN/不明预留才清理。仅对有本profile binding的新任务移除上述原始正文副本，保留IDs、原digests、大小、版本、来源、回答身份/时间、调用/费用/状态/幂等事实及purge标记；不得改动旧V15任务数据。沿用V14的显式tombstone+retained_digest模式，非nullable正文用固定tombstone而非与原hash混淆；读取恢复须先看QUESTION_PURGED，返回410 QUESTION_CONTENT_EXPIRED，绝不把tombstone当成功结果。实际所有副本清理同事务完成后才释放本存储reservation；UNKNOWN/未静止任务保留原文及占用，记录阻碍，不自动清账。

## 8. 迁移、验证与交付边界

V16由原control迁移身份执行，应用账户只获新表SELECT/INSERT/UPDATE及必要旧表最小DML，不获DDL/授权管理。更新 `application_grants.py`、两个角色schema gate为严格V1–V16；生产默认仍不开问答。不改宿主拓扑/共享服务/daemon/代理或真实模型资源。

必要定向证据：真实旧V15库至少含无问题QUEUED任务、已RESPONDED和SUCCEEDED invocation、model/tool pin及旧请求/checkpoint；记录迁移前逐字节SHA256、升级V16后逐一一致并用新binary恢复旧任务。旧V15二进制指向同一新专有库须readiness失败且零领取/零调用。排除并发旧binary后迁移；回滚保留V16，用兼容binary关闭新准入。任何未执行的旧版本/数据库验证必须写未执行，不用架构probe替代。

后端交付同一完整SHA/tree/JAR摘要：

- 正常单选、自定义、多选与显式skip；提问后task/lease/attempt/工具/存储/运行槽快照；人等待时另一tenant和无问题旧任务可正常执行。
- blocking、timed默认120、短timeout、-1；无连接/重连不改变due；timeout先/answer先/DB已过due但维护未运行；pending不可变、晚答仅一条来源消息；终态拒绝且不复活。
- 每个GET/POST/replay/resume的跨tenant/owner/task、撤权/换pin、陈旧revision、未知version、伪造responder/默认批准、重复questionid/标签、错误选项/单选组合、UTF-8边界和所有容量失败。
- 两worker争抢、并发重复/不同答案；创建事务前后、暂停已提交未quiescent、答案已提交未唤醒、已claim未恢复、late已入checkpoint未返回、最终文本竞争晚答的故障窗口；用旧fence攻击只能失败。
- 重启control和worker后由持久事实恢复，空闲轮询不增任何模型/工具/事件；UNKNOWN/费用/锁事实不被问答重置；到期清理只释放确已移除的本批正文。
- 真实旧V15升级/旧binary拒绝、同SHA独审和QA差异验证；保存命令、退出码、准确版本、原始失败、账本、raw协议（合成非敏感数据）、进程身份及finally清理收据。

建议新增 `UserQuestionContractTest`、`UserQuestionIT` 和前台 `tools/user_question_verify.py`，沿用现有Maven/WSL专有环境，只跑本批与受影响回归；名称由开发落实后登记。不要恢复/运行/等待GitHub CI，不重跑旧完整矩阵或付费评测。本包只已验证静态契约与框架扩展点；业务退出仍需上述真实纵切。
