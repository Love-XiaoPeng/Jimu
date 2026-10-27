# XIAO-61 R1/R2 差异修复检查点

仅沿 XIAO-60 / 草稿 PR22 修复 61 对 `3e3fab8da3e7858a3590e973e21e956b7b573317` / tree `65fc18071df9cdca611dcbe3575e9345712f053c` 的两项阻塞。本文是开发交付，不替代 61 独审、62 QA 或产品验收；60 保持 in_progress、61 保持 blocked，未合并 master。早期检查点中的暂存职责、旧 SQL 库存和 OPEN 标记是历史记录，以本差异和实际源码为准。

生产提交 `9c31c0fd9ed5ef6cb924d7262699d39f7ea416a3` / tree `e23811046a22c8a07dbb40eed065506371c85d4a`；取证夹具修复 `6f1f3fe0eb317d465c0417aa49c1e1f8d39fbaae`，只改测试及验证脚本。最终文档提交 SHA/tree 在同轮附件 manifest 中记录，避免文档自引用。唯一交付分支仍为 `agent/agent/7aeccb14d7a9`，base master。

## R1：决定与编排回到纯 Java Service

| 原位置 | 新业务所有者 | 适配器保留内容 |
| --- | --- | --- |
| JdbcExecutionRecords 请求、summary 校验与媒体预算 | ExecutionAdmissionServiceImpl，ExecutionServiceImpl 明确调用 | 惰性事实、codec、同一 query、明确 hold/reserve/settle 变更 |
| JdbcLedger.event 普通254/保留256槽、首错及跨模块计账 | EventRecordingServiceImpl | MapperEventRecords 读取幂等/序号、推进/插入、调用既有模块存储服务 |
| JdbcLedger.fenced 混合的业务准入次序 | ExecutionAdmissionServiceImpl.lease | 原锁获取、fence 校验/CAS、事务基础设施与模块外部能力 |
| directoryContext 的请求版本/上下文/容量决定 | ExecutionAdmissionServiceImpl.request | checkpoint 解码与原上下文渲染，不持有拒绝策略 |

新增 ExecutionAdmissionPolicyV1 / ReliableEventPolicyV1 明确原值，无新限额或协议。保留原单次 UoW 和 run/budget/task 锁序，无新增 DS/事务、预取或双写。todo 原有先解码、再检查外层大小、必要时才读取 body 的短路顺序保持；directory 先计算额外字节、再读取存储量。session 脱敏先于大小检查，原错误实例/首错保持。媒体不足不写预留，成功顺序仍 hold → reservation(SETTLED/KNOWN) → settle。事件先幂等、再槽限制、推进并插入、session fact、session/directory/todo 计账，终态/删除 closing 语义不变。

保留真实连接、fence、query、窄构造等基础设施 API。Question/Todo/Session 已有存储 Service 用其公开嵌套 Facts 类型对外提供事实端口，内核跨模块不导入另一个模块内部 port。Ledger 组装随新增策略发生必要变化，不能再把旧17块整体宣称为逐字节冻结；V1–V22、外部 receipt、插件/会话资源与冻结评测源码没有变化。

## R2：完整显式投影与负例门禁

17条受管 XML 语句和7个 Wrapper 调用位置（6张表）改为 schema22 原完整列集与原列序。包括 task owner、step/attempt 会话事实，以及安装、question join、计账 metadata、scope 等位置；没有裁剪未立即消费的计账字段。schema 列清单从 V1–V22 演进重建，并与既有真实数据库列清单的重叠表逐项相等。`review-fix-projections.json` 保存准确旧候选17条原语句。WHERE、参数顺序、join、锁子句、类型/NULL 处理不变。

外部 `jimu_provisioning_receipt` 的唯一完整 wildcard 保持原 SQL；不假设供应方 schema 为九字段。PersistencePathTrackingTest 移除原受管 wildcard 固化断言；ExplicitBusinessProjectionTest 实际加载全部 Mapper，检查完整有序列、原谓词/绑定/锁、Wrapper 列及扫描门禁，并用新增普通/限定/换行 wildcard 和冒用 receipt 例外的负例验证拒绝。追踪清单按最终生产源码刷新。

## 新验证及失败保留

825生产 /442测试源重新编译。最后两个 Maven 执行合计62个不同测试成功：`verify-final.log` 首次62项为61过/1失败（格式化注释后源码追踪摘要未同步）；同步清单后 `verify-final2.log` 仅复跑 ExecutionAdmissionPolicyTest + PersistencePathTrackingTest，14项全过并完成 `verify -DskipITs`，SpotBugs bugs/errors0。其余48项源码未再修改，最后12份 Surefire XML 合计62项、零failure/error/skip。不能写成一次62项 verify 全过；Failsafe 此次明确跳过，真实私库切片单列。

覆盖：ExecutionAdmissionPolicy12、EventRecordingPolicy7、显式投影3、源码追踪2，以及既有 Question/Todo 存储、Todo 投影、架构/装配/调用者/QueryView 的38项。含 UTF8/Unicode/惰性异常、各模块请求/summary 上下界、媒体额度不足及结算顺序、事件254/256临界/重放/首错/计账次序。格式、依赖与架构检查通过。

早期编译的 TodoErrorCode 导入/包可见性错误已修正；最初 graph-run1 缺 classpath.txt，补齐后 graph-run2 成功；旧源码 worktree 初次因 Windows 长路径创建失败，命令级 core.longpaths=true 重建成功，未改共享配置。保留已有完整日志，不伪造缺失的首轮编译日志。

私库结果以附件 runtime 目录与汇总为准：只运行 question、todo、directory、session、media 五个受影响切片，loopback paid=false、固定 MySQL8.0.46、独立 ext4 私库及本地文件。未重跑旧完整矩阵。每侧实际加载17 Mapper和6 Wrapper，原 JDBC wildcard 与新显式投影同连接检查字段标签/列序、SQL类型、Java类型、NULL、值，并核实际 Mapper Map；两版 JAR 对同一份私库的完整类型化输出相等。原谓词/绑定/锁由加载门禁验证，不把空行结果称为已覆盖非空计量。

首次 question/todo 业务已过，取证输出因 LocalDateTime 不能默认 JSON 序列化失败；只修测试夹具的显示值并保留原 Java 类型。原失败及旧/新助手源码/class 都留档，未改产品 JAR。run1 在 directory 之前替换为修正助手；其已完成的问题/todo 取证另作定向补跑，不改写 run1 为全过。原异常/ACK未知及早期失败沿原版本保留，未重放 UNKNOWN。

最终结果：run1 的 directory/session/media 与 run2 的 question/todo 五组业务和两版完整投影检查均通过；run1 整体仍 exit1（两项夹具失败），run2 exit0。包含首轮失败在内的七套 fixture 均确认容器、私有目录、子进程和本地provider清理完成，无待收集的后台工作。

| 切片 | 本次实际验证 |
| --- | --- |
| question | 实际 prepare → WAITING_USER → 回答 → SUCCEEDED；原 answer receipt 幂等重放 |
| todo | 正常替换/完成及显式空列表、相同内容新事实、连续revision、双worker、重启读、隔离和无额外读写事实 |
| directory | 正常目录转换、相对路径写/编辑、原工具与模型收费、owner隔离、重启读 |
| session | 实际 prepare/finish成功，并产生真实step/attempt事实；不启动T48功能 |
| media | 成功媒体checkpoint actual=amount；合法额度耗尽返回STORAGE_BUDGET_EXCEEDED，无新增预留/调用provider |

每组另有17 XML/6 Wrapper新旧对照；空表仍检查完整元数据，非空字段/类型/NULL/值由各组真实事实互补。没有把未造出的evaluation scope非空行称为已实测。

## 同版制品、旧基线与还原

实测新原JAR为45,072,231字节，SHA256 `2deeb19e9c13f76f8e3c361e1392965b3ea60d8d9219f3b572c16c3e353f21be`。1,990应用条目含1,550生产class，全部class与本次编译目录逐字节匹配；源码/POM、测试助手、执行class与日志独立索引在附件中。

旧比较件从准确3e3fab8源码单独 `package -DskipTests` 重建，45,049,291字节 / SHA256 `8cd80d74ca364307ab1cd2b9d3207bfe8f8b0762d68957b88c9ff53bc655ce24`。其1,972个应用条目全部匹配认证下载的旧manifest。它的 ZIP 整体摘要与原交付的 `daf4dc75...` 不同，明确标作 old-rebuilt；没有下载/还原或执行旧原JAR，没有以新测试冒充旧验收。

附件 `review-fix-original-jar.zip` 加 `review-fix-artifact.py` 可从含相同83个依赖的旧基件还原本次原JAR。只复用逐个核 size/SHA256 的 ZIP_STORED 依赖载荷；其余每个目标原字节和布局均在bundle中，不重新打ZIP。已用 old-rebuilt 基件实际还原并核新原JAR大小/摘要。命令：

```text
python3 review-fix-artifact.py restore review-fix-original-jar.zip OLD_BASE.jar RESTORED.jar
```

最终证据清单声明各附件大小/摘要、准确候选 SHA/tree 和源码/JAR绑定；私库 config、凭据和临时JAR不打入证据。无 GitHub CI 启用/运行/等待，无部署、付费、共享数据变更；不启动 T48 或其他新功能。后续由原61审查者按本差异复审，再接 QA/验收。
