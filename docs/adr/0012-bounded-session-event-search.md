# ADR-0012：有界公开会话事件检索

2026-10-23；XIAO-53 / G22 / T45。状态：accepted（实施决定；产品尚未实现或验收）。

基线 Jimu `1cce67035753731989633cb4420e7d661b894321`，tree `9f93ddfebaa67e001ec8b77c8a47d4d3cd27be03`。固定上游 `571902028a75c64c1d767f4ae5fd099485f0ddf0`；43 个源码/测试/MIT 许可 blob 的重新下载校验见本批 evidence。ADR-0011 和所有历史证据保持原字节。

现状：V19 已有每 task 至多128条、每条16 KiB的捕获时 public-v1 日志、M1前上界、原槽读取、整体2秒连接/事务截止及精确保留清理。它足以支持小规模本地检索，无需另建索引服务。现有 event-v1 的 model.response.calls 只允许 read，不能在旧 Schema 中直接加入 search；公开事件也没有上游的 surface 投影。

决定：

1. 仅新增默认关闭的 `jimu.session-event-search.enabled=false` 和显式 `local-session-event-search-v2`；固定能力顺序 `[session_event_read,session_event_search]`，两个 flag 均须启用。仅本地、显式 paid=false，沿用180秒/8模型请求/20工具/8 attempts/4槽的唯一循环。不支持 search-only 或其他能力组合。
2. 搜索为 `jimu.session.event_search@0.1.0`；组合读取为同名 T44 的 `jimu.session.event_read@0.2.0`。旧 `read@0.1.0`、旧 read-only profile、catalog、binding 和快照原字节不改。新 read 的 seq/窗口行为不变，改变的是明确的 history/event-v2 输出。安装沿原规则按 capability 名校验当前版本/digest/epoch；不自动升级安装，不绕过旧 pin，切换 read 安装版本对旧任务的撤权效果保持现行规则。
3. 一个 task 仍是一个 Session/turn；attempt、接管、重连不创建新 Session。模型只能检索当前 task。owner 通过当前鉴权可查询自己保留的 v2 task，包含终态；其他 task/tenant/owner 不因 query、过滤或 session_id 获得权限。旧 v1 task 不新增搜索授权。
4. 复用 V19 表、来源验证和清理机制，显式 V20 放行 history/event 版本2并新增 `invocation_session_search`。新历史仅写 event-v2，但仍只有原五种类型。search 结果与查询收据保存在原 invocation/新绑定/可靠工具事件中，不新增 `session.search` 原生类型，不把 search 伪装为 session.read。model.response-v2 可保存两工具的**公开参数投影**；search.query 在捕获时按原 public-v1 策略脱敏，原槽原参数仍仅在受限恢复数据中。
5. 本批匹配规则 `public-content-literal-v1`：仅 user.message/model.response 的已存 data.content，使用 ECMAScript 空白集合折叠后的、区分大小写且不做 Unicode 归一化的字面子串；无正则、通配符、SQL或FTS语法。其余三类事件无可搜索文本；过滤可选它们并得到真实空结果。不搜索 ID、时间、来源、参数、收据、错误、内部快照或工具输出，避免凭据旁路及递归检索。
6. 结果按字面非重叠匹配次数降序、归一化文档码点长度升序、事件时间降序、seq降序排序，最多16条，有界命中片段并显式 capped。无模型 cursor/limit/page；候选必须完整扫描有界范围后才能报空或 capped=false。surfaces 的三个合法上游值在本批均明确拒绝为不支持，结果不造 surface 字段。与上游 FTS5 unicode61 phrase、默认100条/30秒、文本展示的差异显式保存在合同。
7. M1前冻结上界和上下文；本 step 的模型响应、同响应其他槽及并发追加均不可见。原 invocation 的原输入/规范查询/范围/结果一次绑定，恢复复核当前权限、保留、安装/pin及 fence 后返回原字节，不重新检索扩大事实。search与read同响应都使用原step切点，按原槽顺序执行。
8. 按 PK 读取一个授权 task 的冻结范围，最多128候选/2 MiB事件正文、8 MiB含来源证明的总读取及scratch、1024次定点来源读取、8 Mi次码点匹配比较、16结果/32 KiB输出，2秒包括连接握手/锁/计算/事务。达到任何硬界限或provider故障均失败，不返回虚假完整子集；只允许明确的结果数裁剪。
9. 结果、上下文、原参数和公开投影等所有副本纳入原8 MiB/task预留、7 MiB正文/64 KiB元数据及租户64 MiB history桶。search规范查询是正文，7天停止公开并在安全终态事务内精确清理；保留摘要/墓碑继续计费，reservation结算自身宽度参与固定点，唯一释放不回归QA-52-01。整体截止继续沿用R1修复，不遗留迟到写者。
10. 新制品支持v1/v2的明确分派及schema20；旧V19制品严格拒绝20。旧V1–V19迁移、旧日志/绑定/快照/制品保持；回退用理解20的新制品关闭新flag，不降迁移、不重解释旧版本。flag off阻止v2的新受理/步骤/工具及成功重放；owner已有历史与安全清理继续按当前授权工作。

比较：沿用上游 SQLite FTS5 会新增另一运行依赖、派生索引的同步/保留/授权路径，超过128事件本批规模；MySQL全文检索会引入不同分词且不能直接继承上游语义。选择当前有界日志+字面匹配，无新技术/部署依赖，代价是明确不同于上游FTS的匹配语义、2秒内全范围验证及V20版本分派。契约不预建通用搜索服务。

权威实施入口：`../verification/XIAO-53/architecture/implementation-contract.md`、`contracts/`、`migration-contract.sql`。后端实施，原独审/QA锁同一准确源码/JAR验证S01–S12后由产品经理业务验收。本决定不声称产品测试、MySQL升级或业务闭环已经通过。
