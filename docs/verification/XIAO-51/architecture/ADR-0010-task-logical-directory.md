# ADR-0010：任务逻辑目录与调用时路径绑定

日期2026-10-23；XIAO-51；状态：架构决定，待产品经理核对后由原后端唯一实施。基线 `5d90fab841559e7349701c8284642525bc00da9d` / tree `e6bbcac442abdc6d279e422dd76894c242485ab5`。完整行为与验收以 implementation-contract.md、contracts/ 为准。

当前 SecureWorkspaceStore 把逻辑文件及观察/CAS/操作收据原子保存在format2/3状态文档；没有目录实体或宿主目录映射。持久任务已有MySQL、固定原响应槽、唯一ToolCallingAdvisor、owner认证API与SSE。改变宿主cwd不能实现任务隔离，也不能恢复原槽路径。

决定：

1. 首批新本地paid=false、默认关闭的 `local-logical-directory-v1`，恰为working_directory/read/write/edit。task映射本批Session，一个新task从`/`开始；attempt/重连不清空。不改变旧profile或支持跨task续轮。
2. 逻辑目录为文件祖先和根，空目录不支持。固定现有tenant/task namespace；对外`/a`，内部文件仍根相对`a/x`，绝不变成宿主Path。新profile写入禁止文件/目录前缀冲突。无删除/rename使存在证据单调，可信只读存储证据可在DB事务外取得；异常不回退宿主或原目录。
3. cwd权威在同库task_directory，新V18、binding/path policy v1。目录调用直接PENDING→SUCCEEDED，同事务保存原输出、目录CAS、提交来源、工具预算与可靠事件。READ/no-op不改目录revision；旧成功调用仅返回原结果。通用外部效果路径不能提交目录工具。
4. 路径在每个原槽**实际执行时**按前序已提交目录冻结到invocation_path_binding；不在M2预解析。DB前序槽屏障配合原manager串行，文件观察/CAS与恢复都用原canonical key。过期领取需历史真实写者静止；不放宽文件RUNNING的UNKNOWN规则。
5. 每个新模型请求带必需、按JSON引用的user-context；step新可空列绑定目录revision/cwd/policy摘要，旧checkpoint/body不改。owner使用GET快照+原SSE失效通知；不新建写API或第二执行循环。
6. 新profile独立有界存储预留；7天后确定终态、历史写者停止且无UNKNOWN才清理。DB先PURGING墓碑并禁恢复→精确scope文件删除/fsync→DB确认PURGED与唯一释放；不宣称跨库/FS原子事务。
7. 不需workspace format4或旧wire版本提升：存储仍是原format3文件codec；新政策只由新DB binding入口适用。需V18是因为cwd/路径绑定/来源/保留须持久化且须防旧binary执行，而非为证明升级制造版本。V1–V17及冻结原件不改，旧binary按严格schema gate拒绝18。

取舍：把cwd仅放worker内存会在重启丢失；放文件state再另写DB结果/事件会产生两个权威事实及跨存储提交窗口；以DB目录原子提交+冻结文件路径沿用现有事务与恢复基础。代价是新增一份窄ledger/port、两表和step可空上下文列，以及显式的清理阶段。为支持空目录、native搜索或长期Session建立新文件协议/会话系统超出本次需求。现有native搜索profile不受新cwd影响，本profile混入搜索明确拒绝。

无新增依赖、部署拓扑或共享服务变更。可实施性验证包含固定源码/blob/许可、production SchemaContract与实际Spring AI槽顺序探针；产品并发/崩溃/权限/迁移和业务纵切仍须按D01–D12运行，架构探针不能替代。
