# ADR-0016：owner 显式继续关系与冻结范围追溯

2026-10-28；XIAO-125 / XIAO-126 / G22 T48。状态：按产品经理已批准的 P1–P3 实施；代码验证、独审、QA 和业务验收分别记录，不以本 ADR 代替。

起始 master 为 `74c79014d7411028596fa40330d0ad009adaa22a`。批准来源是 XIAO-125 评论 `01a17c90-6fad-79be-91f6-363a2386efbf` 及架构实施契约。固定参考上游为 `deepseek-ai/deepseek-harness@571902028a75c64c1d767f4ae5fd099485f0ddf0`。

认证 owner 在新任务受理时可以声明一个历史会话为本次工作的继续来源。parent 是原受理事务中的真实声明；不表示 fork、事件复制、隐藏上下文继承或原任务恢复。未声明 parent 的新 v5 会话为 ROOT，不给旧 v1–v4 回填 root。

显式历史列表至多 8 个来源。来源须为同 tenant/owner、同 v5 profile、终态静止且当前仍有权的会话；每个来源自己的依赖都必须已在本次列表中。宿主核对闭包，不查询缺失依赖、不自动扩大授权。查询图只有受理时冻结的来源和 caller，共至多 9 节点、8 边、深度 8。`coverage=admission-scope` 和 `complete_within_scope=true` 只描述该冻结范围，后来子节点不进入旧图。

V23 保存两类事实：`task_session_lineage` 的 genesis 和平铺 graph；`invocation_session_lineage_trace` 的原槽输入、图、扫描和提交证明。平铺 graph 引用单个 genesis 字节及摘要，不嵌套来源 graph 或工具结果。genesis 绑定原 request、scope authority、工具契约和 parent genesis 摘要。读取时仍核原受理事实、各来源图和全部父边，损坏不得降级为空图或新 root。

新 `local-session-trace-v5` 使用 read 0.5.0、search 0.2.0、trace 0.1.0。默认关闭，显式本地 paid=false；复用唯一模型循环和 M1/M2 原槽。M1 捕获 context/cuts 和 graph 摘要，M2 固定 provider/slot/input/pin/target/context。PENDING 执行与消费/结果/receipt 同事务；成功重放核当前权限和原证明后返回原字节，不重复消费。

全部衍生正文继续受 whole-scope 当前授权及最短保留期约束。任一来源失效则封闭整个 caller 正文；flag 关闭禁止新受理、模型执行及成功重放，但保留 owner 只读、撤权和清理。清理按已有静止/UNKNOWN 规则和元数据固定点运行，两新表正文须参与 NULL 清除和精确释放。

业务规则位于 Service/impl，持久化适配器通过真实 MP 实体/BaseMapper 和参数化 XML 访问同一 DataSource。TaskAdmissionService 通过受理 Records 的原子写入端口连接 session 规则，避免 task/session 相互依赖；Controller 只适配身份、HTTP 和严格有界输入。旧资源和冻结证据保持原字节。

代价是只接受闭包完整的 v5 来源，且单版本 installation 切换会使旧 pin 失权；这不是多版本并存或全上游兼容。限额拒绝不返回裁剪成功。默认 64MiB tenant 不保证容纳完整 9 任务拓扑，拓扑测试显式私有扩大配额，默认真实 429 仍需单独验证。

配置、入口和验证命令见 `../implementation-session-lineage-v5.md`。历史 ADR 索引和冻结 source hash 不重写，本批证据单独保存。
