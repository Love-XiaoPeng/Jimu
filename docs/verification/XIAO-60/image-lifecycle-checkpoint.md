## 图像登记、完整性、预留、结算、读取与超时取消检查点

基线 `26522f0fa1bf6ec373a62473d9a7898f07be7777`。七个 Ledger 入口和全部 `JdbcImageRecords` 业务已下沉 `ImagePersistenceService/Impl`，通过惰性 `ImageRecords` port 和同一 DS/UoW 的真实参数化 Mapper 接线。`JdbcImageRecords` 只为已有模型/报告/受理调用者保留兼容签名；不持有 SQL、资格判断或新事务。

保留原 `leaseTx`，常规入口 `fenced(true)`，超时取消 `fenced(false)`；登记及原 helper 不额外开事务。预留先重读已锁预算，再校验快照/输入/调用资格和 producer 不存在，随后登记对象及 MEDIA_STORAGE。成功先检查原图与结果，再要求对象 UPDATE 恰一行，原 MEDIA_STORAGE/预算更新仍忽略计数，最后调用原公开 `toolSucceeded`，保留其嵌套事务、TOOL 结算与事件。超时只记录 CANCEL_REQUESTED 和原事件，不声称解码器已经停止。

所有 Map 成员读取延迟到原访问位置，保留 String/Number/Object/NULL 首错和短路；原逻辑路径的 Object.equals 与后续 String 转型分开。输入资格复读、原 UUID 生成时点、参数读取顺序、完整性 hash/请求/工具 pin/输出证明、step 数字 slot 排序和第二项晚拒绝均保留。Service 在调用 port 前完成原 SQL 参数求值，不把任务字段的转型或读取挪到 Mapper/session 获取之后。codec、ModelImages 及同版本的 ImageRuntime/Normalizer/Inputs/Store、Host/provider、Web、报告与81/82规则保持原实现。

本批迁移 **15 旧 JDBC 点 / 56 bind**，SQL 消除0，逐条原 SQL、参数与列投影见 `evidence/b2-image-lifecycle-removed-sql.json`。SELECT * 按独立冻结 schema 列序展开，输入9列另加原 valid 计算列、对象16列、invocation27列，保留类型与 NULL。Mapper 测试读取实际加载 SQL 并与 `b2-removed-sql.json` 的独立列清单核对。库存实计 **95 静态 / 29 动态 / 306 静态 bind**，35表和外部 receipt 仍 OPEN。剩余静态点为 Ledger81、ExecutionScopes6、SessionScopes3、McpRecords2、SessionQueryTransaction2、ExecutionRecords1。

开发定向测试共44项通过：本批13项 Service 首错/次序/懒读/计数/事务委派和1项全15 SQL/56绑定检查，复用 ImageInput 3项、ImageAvailability wiring 1项/Service 17项，以及架构/负例/库存/Mapper门禁9项；SpotBugs bugs/errors=0。生产代码在首轮定向/静态检查后未变，之后只修正验证配置、测试和比较器。

`B2ImageLifecycleIT` 使用私有 MySQL 8.0.46、原 p4 图像 PNG/模型配置夹具、paid=false 和真实 claim 所建 RUNNING/ACTIVE/live 完整租约。两版均通过公开 Ledger 七入口及原公开 submit 登记路径。合成已接纳调用/step/TOOL 预留及相应计数，不执行 ImageRuntime、decoder、Host/provider、工具或 worker 循环，也不把它写成完整模型执行验收。

旧新各34次公开调用、各566条实际 SQL，核对1244个到达绑定位置；另各123项参数关系审计覆盖全部15类迁移操作。涵盖输入空值/撤销/过期/字节不符，UNKNOWN 不重做、预算、重放、权限和 fence 拒绝，两个图像的 MEDIA_STORAGE/TOOL 结算，完整对象证明，step 顺序与第二项晚拒绝，以及撤权限后的原非 admission 超时和事件幂等。每次保存前后行、SQL、参数、setter/getter类型、连接和异常；每次操作仅使用同一连接。getter/API 不是零差异：旧 setObject25 次由新 setLong 增9/setInt增16替代，数值关系均通过；完整计数保留于 transcript audit。

所有调用加入同一个外层事务。失败和重放后的 fence revision 仍可在该事务内观察，比较器只在拒绝业务事实比对时忽略 task revision/updated_at；最终 rollback 精确恢复私有全部表，包括 worker_process。不能据此声称逐调用独立回滚、逐调用持久提交或整体验收。控制进程、fixture和容器清理回执保留。

原通配 import 检查失败、漏设 Linux Java21 的 IT 失败、证据序列化器不支持任务 View 中 Instant 的失败、以及只对新版归一化既有预算全列查询造成的比较误报，均保留原日志/脚本/退出码。两版真实调用已各 exit0，比较修正先通过原记录离线复核，末轮只重跑本批 IT，不运行旧整矩阵。

本批是开发检查点，不代替91/92或整体61/62独审/QA。60保持 in_progress，PR22 draft，master不合入，81/82等代码卡继续 in_review；不运行/等待CI、不部署、不付费、不触碰共享服务，T48继续暂停。
