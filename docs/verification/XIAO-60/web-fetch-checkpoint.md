## Web 观察、取消诊断与保存凭证完整检查点

基线 `4929a7f7bb380d70d90682e550db2eabfeee2d64`。本批将 `observeWebFetch`、其专属取消分支和 `preserveWebFetchResult` 的完整业务编排移入 `WebFetchService/Impl`，由 `MapperWebFetchRecords` 提供惰性行视图、单项证明和真实参数化 Mapper 操作；原公开 Ledger 接线。

`WebFetchReceipts`、Host/provider、报告 Service/适配器与 81/82 文件不变。独审89/QA90继续绑定原报告检查点；本批开发证据不替代它们或整体61/62验收。

### 原语义与 SQL

- 保留原 `tx`（非 `leaseTx`），同 DS/UoW、特殊持久拒绝和事务外重抛；构造无预读。取消判断在事务内、在 fence 前，普通观察/保存仍 `fenced(lease,false)`。
- 原 `fenced(false)` 不执行 admission 的 actor_access 检查，撤销访问后允许已接纳效果的观察/保存；本批不擅自强化为拒绝。两项权限撤销样本绑定该原行为。
- 普通观察保留 tool/version/state 三资格的 Object 等值与短路，以及 phase/failure 事件。NULL observation 在事务内首先抛 NPE，NULL phase/failure 的原拼接不改。
- 取消保持 task 锁→原 attempt/worker/fence 六参数联接→恰一条绑定→fact 幂等→四事件次序；不新增访问权限、ACTIVE、当前 fence 或 live 检查，不执行或结算。`queryContext()` 返回原 `query` 字段本身，无新上下文。
- 保存保持 RUNNING→step 三键普通读取→原 digest→event code 首行/输出/摘要三证→双 NULL 短路→原 UPDATE→事件。step_id 以 Object 原值到达 Mapper，无提前 String 转型；忽略原更新计数。不标 SUCCEEDED、不结算、不补 CAS。
- Task/Invocation/Step 包装保留原 Map 引用，每个 getter 才调用对应 `.get`；没有预取成员或复制原 Map。digest 仍收到这三个原 Map。

7 旧静态 JDBC 点、25 bind 逐条对照见 `evidence/b2-web-fetch-removed-sql.json`。两个 invocation 读取和 event code 复用未变语句；专属 task 锁、Object step 读取、诊断联接及输出 UPDATE 使用新增 Mapper。SELECT * 展开为 schema22 原全列序，类型/NULL 未裁剪；SQL 消除0。真实 JDBC setter/getter/API 差异随原调用日志与 `transcript-audit.json` 交付，不能把 Mapper 调用视作 JDBC API 完全相同。

库存已由 `capture-paths.cjs` 实计 **110 静态 / 29 动态 / 362 静态 bind**，较基线减少7/0/25。35表+外部 receipt 均仍 OPEN。剩余包含 Ledger85、ImageRecords11、ExecutionScopes6、SessionScopes3、McpRecords2、SessionQueryTransaction2、ExecutionRecords1；不能以一表零字面量关表。

### 验证边界

10 项 Service 测试覆盖构造零预读、NULL首错、Object等值/短路、普通事件、取消绑定/幂等/四事件、保存顺序/首行三证/双NULL以及原异常实例传播。1项 Mapper 测试核全部7 SQL/25绑定及完整投影，复用原凭证1项、架构/负例/库存/Mapper门禁9项，共21项通过；SpotBugs bugs/errors=0。

私有 MySQL 旧新对照复用现有 Experiment、paid=false配置、WebFetchReceiptsTest wire/pin/input 及原取消证明参数，只执行公开 Ledger 观察与保存。普通分支通过真实 claim 建立 RUNNING/ACTIVE/live 完整租约；合成合法已存请求/响应/调用，不启动 Host/provider/工具/真实网页或 worker循环。取消样本人为推进当前 fence、过期 lease、停原 attempt并撤权限，单独验证原绑定的诊断资格；不冒称可执行租约或真实恢复流程。

每个公开调用保留前后行、SQL、绑定、setter/getter类型、连接标识、异常链。普通分支的 fence 检查及新事件追加会递增 revision；即使后续拒绝或重放，fence 变化仍在已加入的外层事务中可见，最终统一回滚。对照逐项核 revision 增量（成功 fence 一次，加实际新事件数），其他拒绝/重放业务事实保持。外层事务 rollback 对比私有全部表（含 worker_process），不声称逐调用独立回滚或持久提交。原失败和清理记录随附件，不改写为成功。

原配置缺少 Java21、非法 step 外键夹具、权限误期、外层 revision 误期均保留。只有夹具/断言/配置修正，生产实现自首轮定向单测/静态检查后未变。末轮只执行本批 IT，不重跑旧完整矩阵。

最终 `B2WebFetchIT` 1项通过，旧新各29次公开调用、各52项实际参数关系审计通过；两版调用的原 SQL 形状（仅完整列展开）、绑定位置、Statement/PreparedStatement 数量和决策一致。保存后仍 RUNNING，输出/摘要/receipt一致，TOOL/预算不结算；相同结果重放、冲突拒绝、原权限/fence和旧attempt取消幂等均通过。外层私有全表（含 worker_process）恢复，控制进程/夹具/容器全部清理，原记录见交付包。

本轮共比对549个动态到达的绑定位置。实际 setter 为旧 setString491/setLong45/setNull7/setObject6，新 setString491/setLong51/setNull7；六个诊断 fence 从 Spring setObject(Long) 改为 MyBatis setLong，值及原六键关系一致。getter调用也由原通用 getObject/混合读取改为 MyBatis元数据类型读取，完整明细保留于原逐调用日志；不把这个API差异写成零差异。完整投影另与既有 `b2-removed-sql.json` 冻结列独立对照：invocation27/task63/step22列一致，Mapper末轮定向1项通过。

下个完整批次建议由PM沿现有队列选择图像 reserve→success/timeout 与专属 JdbcImageRecords 闭环；本轮未启动、未预扣。60继续 in_progress，PR22 draft，master不合入；81/82继续 in_review。不运行或等待CI、部署、付费或共享服务；T48暂停。
