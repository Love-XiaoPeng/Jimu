# ADR-0014：显式历史 scope 与跨会话副本保留

2026-10-23；XIAO-55 / G22 / T47。状态：accepted（最小实施范围），产品经理于 2026-10-23 裁定通过，评论 `01a16770-4cd9-75bd-90dc-06da4aafa18b`；允许实施，尚未产品验收。

基线 actual master `d6d2d28a8f4fb3c2f05ed2bd410b1d5f65a16fe1`，tree `af0e11992efdc00d3e8bc6d656d9aeda30159a90`。固定参考上游 `571902028a75c64c1d767f4ae5fd099485f0ddf0`。来源和逐行定位见 `../verification/XIAO-55/architecture/evidence/`。

上游 T47 返回每个既往会话的最强匹配事件，按同一排名跨会话排序；以 caller workspace 过滤并排除 caller。Jimu 的 task 就是 Session，既有模型权限只覆盖当前 task。上游 cwd 不是 Jimu 的安全主体；相同 owner、模型传入 ID、seq 或关系均不能产生授权。当前单版本 installation pin、五种 public 事件和七天清理规则也不能自动解释成旧历史的跨会话权限、live/persisted 或 lineage。

推荐首批决定：

1. 默认关闭的新 `local-session-search-v4`，能力固定为 `[session_event_read,session_search]`，分别为 `jimu.session.event_read@0.4.0`、`jimu.session.search@0.1.0`。不广告 event_search/trace，不改变 v1/v2/v3；paid=false、180秒和唯一执行循环保持。
2. 新专用受理接口由认证 owner 提交有界来源 ID 列表，宿主在同一受理事务内核验当前 actor、tenant、owner、来源历史和安装/pin，并产生绑定 caller task 的 immutable scope。列表是授权申请而非授权事实。scope 固定0–8个明确目标及各自上界；空 scope 用于产生独立来源会话，绝不意味着全 owner/tenant。来源只限已终结、写者静止、无 UNKNOWN、scope 为空的 v4 task。v1–v3、活跃及有历史依赖的来源本批不接纳。后续扩展须另定范围，不回填旧事实。
3. session_search 只搜索 scope 中 prior targets，过滤只能缩小，caller 永远排除。每会话选一条最强事件，跨会话稳定排序；沿已发布 `public-content-literal-v1`，不声称 SQLite FTS5 兼容。最多8候选、4代表结果、全查询1024事件/4MiB事件正文/16MiB含证明读取/4096来源读取/16MiB scratch/16Mi次比较/2秒绝对截止。无公开分页字段。
4. read@0.4.0 根据同一宿主 scope 独立复核目标；搜索结果不授予 read 权限。同响应 read 可以使用该 response 之前已有的来源 ID/seq，但不能把前槽输出注入已接纳 response。caller 当前事件仍限原 M1 切点。新 event/history/binding/context-v4 显式定义 cross-session read receipt；不把其他会话 seq 写入旧 v3 单会话关系。
5. scope 及查询结果均属正文。caller 受理时 `retain_until=min(caller受理+7天,所有来源retain_until)`，不续期、不复制延长；来源至少剩余180秒才接纳。所有公开接口、SSE、summary、历史、模型请求/恢复/成功重放在返回前检查整个原 scope 的当前权限、来源保留和 pins。任一失效则整份 caller 衍生正文停止公开，不能仅删一条 hit 后继续返回旧快照。原 invocation 绑定输入、scope/candidates/cuts/结果，成功重放只回原字节。
6. 显式撤销 scope 是 owner 的独立宿主操作，线性化后不可恢复；正常模型/工具停止接纳，真实 usage 仍结算。失效和到期不强行释放 UNKNOWN/活写者：先拒绝全部正文公开，待安全静止后清除所有副本并精确释放；普通 task 状态/取消入口保留且不含衍生正文。来源与 caller 独立清理，不互相等待，不延长来源保留。
7. V22 只新增 scope/target/step-cut/跨会话原槽表并放行显式 v4，所有表/正文/墓碑参与原8MiB/task及64MiB/tenant实际固定点计账。scope targets 是有界捕获事实，兼作候选索引；不扫全租户、无 OFFSET/provider fallback，不预建搜索服务或多版本安装体系。V1–V21和已发布 Schema/catalog/ADR索引原字节不改。

备选：沿 owner 自动发现所有 task 会扩大模型权力且缺少有界授权依据；复用旧 event_search 名称无法实现跨会话；把历史输出复制到 caller 后只检查 caller 七天会绕过来源删除/撤权；改 installation 为多版本共存涉及全局安装/旧 pin 语义，超出本批最小需求。选择显式扁平 scope 和新 v4 的代价是来源范围较窄、所有 caller 正文受整份 scope 约束。产品经理必须明确接受这些差异后才启动后端；若要求搜索旧 profile、活跃或依赖历史，先扩本契约，不静默降级。

唯一候选实施入口为 `../verification/XIAO-55/architecture/implementation-contract.md`、`contracts/`、`migration-contract.sql`。架构仅交付候选和可核原码；后端实施、准确制品独审/QA和业务验收另行完成。
