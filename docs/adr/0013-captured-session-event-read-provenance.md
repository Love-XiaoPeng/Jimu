# ADR-0013：捕获式会话事件读取来源追溯

2026-10-23；XIAO-54 / G22 / T46。状态：accepted（仅接受 read-receipt-sources-v1 的有界业务范围；产品经理于 2026-10-23 裁定，评论 01a166ed-74fb-7321-9dce-1f0e5419d460）。

基线为实际 master `3e5f77549562691634c0a166d8b944ecdcdf1bb8`，tree `93d353f8efac07c34676bc3bffb1790e048fc75d`。固定上游 `571902028a75c64c1d767f4ae5fd099485f0ddf0`；原始 blob、MIT、逐文件摘要及源码定位见 `../verification/XIAO-54/architecture/evidence/`。没有改写 ADR-0011/0012 或冻结基线。

原码结论：T46 是 surface 的立即替换者、替换链、被目标替换的直接节点、目标显式引用的来源和直接派生者。它不是邻接读取、一般因果图或会话父子树；T48 才处理会话 lineage。上游 `surface.ts` 禁止非 surface 事件携带 `sourceEventSeqs`，且禁止 assistant/message 携带该字段；不能把 Jimu 的日志收据直接当作上游 surface/tool-result。上游 trace 本身也不应用 search 的 turnBoundary 截止或 30 秒 search timeout。

Jimu 的五类公开事件均无上述关系事实，不能从 seq、同 step、同模型输入、工具参数或表中的 source_step_id 推断。新增完整 surface/替换生产者会改变当前执行和模型上下文语义，超过本批有界事件追溯的最小规模。单纯返回空链则不能交付真实关联闭环。

已接受的有界决定：

1. 新增默认关闭的 `local-session-event-trace-v3`，有序组合 read@0.3.0、search@0.2.0、trace@0.1.0，三个 flag 全开，显式本地 paid=false、180 秒，沿现有唯一循环/预算/权限。不改变旧 v1/v2。
2. 仅承诺 `read-receipt-sources-v1`：新 v3 模型 read 成功提交时，由宿主在同一事务记录实际返回的目标和邻接摘要的真实 seq/digest，最多9个。新 read 收据公开其直接 source_event_seqs；其余事件和失败 read 为 []。这个关系表示“该读取收据引用了哪些实际返回事件”，不声称模型引用、推理因果、上游 surface 或替换关系。
3. v3 trace 返回目标有界摘要、其直接来源和直接引用它的成功 read 收据；两个列表都按 seq 升序。只展开一层；不跟随来源再找来源，不展开旧工具输出。replacement_policy=unsupported，省略上游替换字段和 surface，禁止用 null/空链冒充支持且无替换。没有真实关系时返回 []，旧 v1/v2 则明确不可用，不回填。
4. V21 只扩展 history/event 版本为1/2/3，为 read 原槽新增有界来源事实正文及摘要，为 trace 新增原槽绑定。关系事实、原输入/上界/扫描摘要/结果/一次工具消费/可靠收据同库原子提交；恢复先复核当前权限，返回原字节，不让后来的引用改写原结果。
5. 全范围最多128事件/2MiB、180条来源边、1024次来源定点读取、8MiB读入/8MiB scratch、1,048,576次逻辑操作、2秒整体查询；最多9来源+20反向引用，结果不分页、不裁剪关系。损坏、缺失、环、超限或 provider 故障都失败，不静默返回部分关联。
6. 捕获关系和全部副本纳入原8MiB/task、7MiB正文、64KiB元数据和7天保留/精确清理。关系 seq 本身也是保留历史信息；清理时删除来源列表正文，墓碑只保留摘要、字节和原收据标识，继续计费。R1绝对截止和QA-52-01固定点不变。

取舍：复用当前真实 read 事务可以形成可核查的授权事件关系，避免新增模型上下文压缩/编辑生产者、surface 引擎或通用图服务；代价是**这不是固定上游完整 T46 的兼容实现**。若产品要求替换链或模型 surface 来源，须另明确真实生产者及捕获时机，再改新版本契约；不得让后端猜测关系或把本候选标成完全移植。

实施入口：按 `implementation-contract.md`、`contracts/`、`migration-contract.sql` 由原后端唯一实施。surface/替换链继续记为未交付，本批验收不代表 G22/T46 完整兼容；新增生产行为须另行定范围。架构没有启动后端或新增团队。候选文档核验不代表实现、迁移、业务闭环或独立 QA 已通过。
