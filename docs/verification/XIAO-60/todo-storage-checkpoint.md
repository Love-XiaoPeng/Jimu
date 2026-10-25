# XIAO-60：todoStorage 部分检查点

本片从 `b990f4861db6df6fd71751b197246dddc07a8c26`／tree `58a985601513797755a2cd03b7999acb2ff38373` 接续，仅迁移 `JdbcLedger.todoStorage`。准确交付 SHA/tree 见附件 manifest；60 保持 in_progress、PR22 草稿，B2／B0–B5 尚未完成。

受测制品固定为 `jimu-xiao60-todostorage-run2.jar`，SHA256 `402d431da703ad2b11bfbdd4deb022af7eb61584844ae24afdc7bfe5b57cb03d`，44,561,737 字节、1,592 个生产 entry。旧版为准确 `70a1518e…` 原 JAR。逐项读取全部生产 entry：1,578 既有 entry 原字节相同、6 新增（5 class＋1 XML）；Ledger 主 class 改变，七个既有嵌套 class 经独立 classfile 解析仅 LineNumberTable 改变。当前 target 的全部生产 entry 与受测原 JAR 匹配。

`TodoStorageService`／impl 承载 contains(todo_write) 的谓词短路、可空 Long 拆箱、原左结合 `bytes + additional + 65536 > 2097152` 及原 429/TODO_STORAGE_LIMIT。混合／重复 caps 仍进入查询；非 todo 不取 tenant/task 或访问 SQL。负 additional、等于边界和 signed long 溢出不增加校验。`MapperTodoStorage` 构造只保留引用；caps 仍用原 split/cast，tenant 再 task 的 String cast 在查询点惰性发生，无 flag／模型／身份／pin／projection 预取。

`TodoStorageMapper.bytes`／XML 保留原单条相关汇总。除源码 `?` 写为两个参数化绑定外，实际 SQL 原串相同，绑定仍完整 tenant、task；OCTET_LENGTH、所有 COALESCE 层次和四个相关子查询不变。没有新增查询、JOIN、锁、筛选、事务、CAS 或计费更新。尤其 projection 子查询缺行仍使整式 SQL NULL，随后在 Service 拆箱 NPE；外层 task 缺行仍为 Spring EmptyResultDataAccessException。List<Long> 配合 Spring nullableSingleResult 保留单行可空及 IncorrectResultSizeDataAccessException 基数语义，未换成 selectOne。MP LongTypeHandler 和原 SingleColumnRowMapper 均用 JDBC getLong 保留 Long／SQL NULL 转换；非零结果时 MP 省略原 JdbcUtils 的 wasNull 调用，此 getter 实差已单测记录，不声称底层 getter 序列全同。

原四个调用位置与 additional 表达式未改：claim resume 的 snapshot→questionStorage→todoStorage；ModelDispatch.reserveModuleStorage 的 question→todo→后续预算；responded.validatePayload 的原材料校验→directory／条件 session→todo；executeTodo 的 fenced／projection／invocation／revision／工具预算→todos.output→todoStorage→原更新。additional 仍在调用前计算。prepare→dispatch 的本片汇总实际有两次，旧新次数及原 SQL 顺序一致，未合并重复调用。

源码审计逐字节冻结其余 588 份既有生产 Java、419 份既有资源（包含冻结文件）及 schema22/V1–V22；仅增加本片 XML。todoProjection 的完整 21 列／列序／case／NULL、已迁 Service、相邻 Bean/bootstrap/POM 全部保持。静态 JDBC 库存 167／dynamic 29／bind placeholders 561：迁移一个旧 JDBC 调用点，原 SQL 仍执行，SQL 实删 0，不表示关闭表或减少运行查询。

三轮原件分别封存，下一轮前保存执行 source/class、编译状态、原日志和清理证据：

- run1：Checkstyle 两处测试星号导入失败，verify 1；未编译、未执行测试、未打包。封存后仅改显式导入。
- run2：11 Service＋5 port＋2 追踪＋1 架构共 19 项通过，SpotBugs bugs/errors 0。真实 IT 的旧新各 14 个 RESULT／RESTORED 均完成、driver 退出 0；比较器误把 prepare→dispatch 的汇总次数设为 1（实际两侧 2），使 IT／verify 1。完整失败回执保留。
- 修比较器后离线重核上述原 14 对；不称 Java/MySQL 重跑。run3 只复验受影响 prepare→dispatch 一对，并运行 2 追踪＋1 架构＋1 IT；完整定向 verify 0、SpotBugs bugs/errors 0，未重跑未变 16 项本片单测。两轮 IT 显式使用同一 `402d431d…` JAR。run3 构建还产出 `111a56b31cab4b66d02ad99a83de5d0109147362db1508863c9049d946f84c36` 的 ZIP 元数据不同 JAR，其全部生产 entry 与受测 JAR 相同，但未替代固定的受测交付制品。

必要旧新私有 paid=false 样本覆盖直接等于／超 1／负数／溢出、真实 UTF-8 OCTET_LENGTH 增量 20 字节及空子集合、合成 task map 的混合重复 caps／非 todo 跳读、真实无 task 行、实际 DELETE projection 的 direct NULL→NPE 与 public getTodos 先 projection404；prepare→dispatch 成功／storage 拒绝及 responded 成功／storage 拒绝。不存在的 task 键没有执行 task DELETE，不声称删除证明；多行基数仅单测，真实 PK 下不可表达。

共 15 对完成结果及原 SQL 顺序、完整 actor/installation 锁与 projection/storage 绑定分别核对；6 份成功证明保留原串、摘要、长度和可靠事件列。2 份已知拒绝证明在内层 outcome 后、外层 rollback 前为 RESPONDED／TODO_STORAGE_LIMIT，MODEL SETTLED 实计 17，含 model.rejected；每份另列四条证明 SELECT。30 笔 tenant-a 二十表捕获事实均在夹具外层回滚后恢复，2 份私有实例清理均完整。主错 cause/suppressed/next SQLException 路径与 rollback、证明及落盘错误分开记录。

这只是开发自测；HTTP 仅新侧夹具 setup，受影响入口为实际 Ledger prepare／dispatch／responded 和 getTodos，response 是 fixture。无 worker 循环／provider 请求／工具执行、付费或共享服务操作。固定应用时钟只用于旧新夹具，不改变生产查询／网络／事务设置。原 Map.of／生成 ID／时间／namespace 字符串各自保存，只比较已证 JSON 字段；不是 HTTP wire／所有 bind 全同、整库／持久提交／FAILED-read／独立后记或成功工具 read 证明。没有新增 SQL 故障、自然 08S01、失效连接或 UNKNOWN 恢复验证，未制造 CHECK 违例。

准备封存脚本曾在错误 cwd 读取路径，原脚本及工具输出存在，但该准备错误的完整原生 stderr 未单独封存；说明不补造缺件。target-product-match 首版键名继承 Run4，原回执保留，后仅更正为准确受测制品标签并重新核对；产品／执行原件未改。全部本轮原失败和历史失败／缺件继续保留。

下一具体片建议 `todoCommitFence` 原单条受 fence 限制的更新及原错误资格，须保持原 SQL／锁／更新次数，不并入本片。B2 及后续 B3–B5 仍有剩余；35 表＋receipt、core 最后 beforeCommit、scope 完整成功工具 read、持久 FAILED-read、独立后记、其他真实缺行、自然 08S01／709ms／冷路径等历史缺口继续 OPEN。最终 61/62 backlog，63/64/66/70 不重接、72 不追新 head，T48／新功能暂停；未运行 CI、合并 master 或部署。
