材料、请求历史与图像报告证据增量，基线 `1bfd41249eb39196e1d0bfbc552d76c559102f70`。

`evaluationFile`、`bindEvaluationFile`、`materialBody`、公开/包内 `imageReportContext` 及 `material`、`initialFile`、`checkMaterials` 的完整资格、短路、遍历和来源规则迁入 `MaterialEvidenceService/Impl`。原 Ledger 保留兼容接线；惰性 Row/JSON/Checkpoint 视图仅适配原值，九条 SQL 由四个既有表属 Mapper 执行。同 DS/UoW、公开 leaseTx/fenced(true)、包内原无新增事务、忽略计数与首行语义保留。`businessTask` 仅 private 改包内可见，正文不变；210 个其余 Ledger 方法正文核同。

实际迁移 9 旧静态 JDBC 点 / 26 bind，SQL 消除 0。重算剩余 **18 静态 / 29 动态 / 34 静态 bind**：Ledger 6、ExecutionScopes 6、SessionScopes 3、SessionQueryTransaction 2、ExecutionRecords 1。会话 Statement/保存设置恢复、scope/session/checkpoint、既迁 Service、模型/Host/Runtime/Store、POM、schema 和资源保持冻结；不按 SQL 字面量宣称表或整体完成。下一整组建议为 SessionScopes.create 的完整权限快照/目标/保留期业务，仍由 PM 排程。

必要验证为 31 项定向检查、SpotBugs 0 和最终 `B2MaterialEvidenceIT` 1 项通过。真实私有 MySQL 8.0.46 上两版各 23 次调用 / 324 SQL（30 Statement + 294 prepared）/ 648 动态绑定 / 38 项本侧参数关系，全部 9 SQL 到达。材料未绑定、首次 receipt、精确重放、成功 write 后 empty、合法/非法历史前缀、媒体完整重投影与配置/描述拒绝、非空图像来源、重复引用、缺失媒体、权限/fence 均有原调用证据。每侧真实临时私有 PNG store 读取 10 次；公开调用前核实 RUNNING/ACTIVE/live 及完整原权限资格。包内 history/image 用原外层事务，无新增事务资格。

| JDBC API | 旧 | 新 |
| --- | ---: | ---: |
| execute | 213 | 251 |
| executeQuery | 110 | 73 |
| executeUpdate | 1 | 0 |
| setString / setLong | 620 / 22 | 620 / 22 |
| setObject / setInt | 6 / 0 | 0 / 6 |
| getString / getInt / getLong / getObject / getBoolean | 1306 / 356 / 513 / 1414 / 52 | 1758 / 422 / 620 / 789 / 52 |

SQL 顺序/谓词/绑定位置保持，Mapper 的 execute 和类型 getter 有上述实差，完整 getter 类型、NULL 和每条参数关系随原证据保留，不宣称 JDBC API 完全相同。两版私有 36 表含 worker_process 外层 before/after 一致，container/root/children/fixture 四清理项 true、failures 空；provider/model/tool 请求 0。

材料资格使用本地 paid=false 任务及显式冻结材料快照关系，不代表付费评测准入。step/response/dispatch/invocation 为显式 SQL 夹具，图像为实际临时私有 store 字节，非工具生成或生产文件系统证明。重复引用轮只证明原报告读取规则；外层 rollback 不证明逐调用持久提交或独立后记。UNKNOWN 未制造/重放；旧高成本、未变矩阵沿既有证据，不重跑或改记独审/QA。

原生产编译 private 可见性、测试 API/import、时间类型/空 lease 夹具、worker 重名/宽度失败及静态空参告警均留存；修正后必要检查通过。静态空参兼容保留原 OpenAiProtocol 的模型先读顺序，有专门首错测试。最终测试 JAR 字节及源码 SHA/tree 由外置 manifest、分片与原 JAR 增量还原补丁绑定；未为取证重新打包。PR22 保持 draft，60 in_progress，未合 master；无 CI/部署/付费/共享服务操作，T48 暂停。
