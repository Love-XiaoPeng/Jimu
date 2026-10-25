# B2 response 验证缺口诊断检查点

基于 `689d904355bb006de966e91fdcbfe431b0755205` / tree `118fb57eefc486e212d6ecad712a7071b9b72c52`。本片修复夹具取证缺口，没有修改生产实现或截止；**response 验证不关闭，仅开发自测**。准确诊断源码、制品及原件索引见本轮 manifest。

## 准确制品与修改

继续使用旧 JAR `c1bf9870dff1b7f5a30e9f7d49c2e953da6d650f92e270fd9627ccbe3176522d`（44,391,230 字节）和新 JAR `dc1d44dbf9715b0b70fd3bf7293f08d4c83eb816ac2d299d2a60b5023f9f18f0`（44,403,849 字节）。没有以重新打包替换受测 JAR。门禁另产 `7fa8abc36510d203a610ee76323c4eca794ab40d056e4f7a523007678201972c`，44,403,849 字节；仅打包产物，未作为新的受测产品，其 1,451 个生产编译／资源 entry 与上述新 JAR 逐项相同。

新增私有 `SessionResponseDiagnosticScenario`，独立保存生产正常返回／业务拒绝／主异常、外层事务异常及 connection rollback／setAutoCommit／close 的异常；异常含完整 cause／suppressed 栈和 JDBC next-exception 的 SQLState／vendor code。证据写入失败有 stdout／stderr 保底，追加取证错误不覆盖主异常。正常返回标记直接在真实 `modelResponded` 返回处取样，取证不在产品截止内新增证明 SELECT，不修改任何 socket／network／事务超时。

两个窄实验各先将同一批冻结事实复制到两个新的私有数据库，保留原表级 runtime grants，然后分别只进入旧／新副本一次。UNKNOWN 副本没有恢复、重放或原槽重试；新侧使用在任何试验前已创建、尚未执行的副本。未重跑六组矩阵。原受理、worker、ledger／Service／Mapper 链保持，时钟替换仍仅为既有私有测试设施。

## 实际结果

| 组／侧 | 生产结果及耗时 | 外层事务 | 事实恢复证明 |
| --- | --- | --- | --- |
| trace／旧 | 正常返回，1,929,976 μs | 回滚返回，结束于 1,951,512 μs | 1 笔捕获 tenant 16 表及预算全列恢复 |
| trace／新 | 正常返回，1,687,778 μs | 回滚返回，结束于 1,710,887 μs | 同上 1 笔 |
| scope 原三调用／旧 | 原始异常，2,745,532 μs 才到达 catch | 回滚异常；没有恢复证明 | 0 笔，不计通过 |
| scope 原三调用／新 | 正常返回，1,951,366 μs | 回滚返回，结束于 1,973,718 μs | 同上 1 笔 |

trace 旧／新各 3 条 RESULT 按 JSON 值同序一致，各 181 条受测 PreparedStatement 文本／顺序一致；原事件字符串各自先核 SHA256／字节长度。原历史 trace 回滚失败没有在这次窄暖路径复现，不能据此确定历史原因为何或关闭冷路径。

scope 保留 search→self read→跨会话 read 原三调用和单一累计 query context／绝对 2 秒截止。旧侧最后 network timeout 原设置为 1 ms，采样于 1,999,671 μs；随后 metadata `UPDATE task_session_history SET metadata_bytes=?,held_bytes=IF(content_state='PURGED',?,held_bytes) WHERE tenant_id=? AND task_id=?` 在 2,036,819 μs 结束采样（35,866 μs）。完整生产主异常为 `RecoverableDataAccessException` → `CommunicationsException` → `SocketTimeoutException`，SQLState `08S01`、vendor 0，路径为 `MapperModelWorkflows.responded → event → sessionFact → sessionAppend → account`。随后外层 `rollback` 另报 `TransactionSystemException` / connection closed，SQLState `08003`、vendor 0；cleanup 的 setAutoCommit 异常单独保存。原异常不再被回滚异常遮蔽。

旧侧在受测 SQL 已超时后至 catch 之间仍有未逐段归因的耗时；不能把末条 SQL 采样当成生产结束。旧／新受测语句共同前缀 269 项，包含旧侧失败的该 UPDATE；新侧继续至 304 项、末尾 outcome SELECT 后正常返回。这里的样本数不是全量物理 SQL、QueryResources reads／bytes／operations 或往返计数。旧侧失败发生在 response 迁移前的准确 JAR，不能据此认定 response 迁移回归；本轮新侧单次成功也不能证明组合／冷路径稳定或修复历史失败。没有为通过试验删减 account／资格查询或放宽生产规则。

两个私有容器均完整清理，进程已前台收回。成功试验的续期 UPDATE 和预热另笔回滚；成功绑定／结算／可靠事件只证明外层事务内写入，随后对捕获事实回滚。旧侧 UNKNOWN 不声称恢复，不作持久提交、独立 afterCompletion 后记、FAILED-read、全任务或整库回滚证明。

## Response 来源

scope 使用测试专用 `ResponseSourceAgent` 只在 worker 原 `Payloads.json(response)` 返回后复制该 String，并在调用 ledger 前暂停。保留原始／观察版 Session class 和 agent；原始 class 已逐字节匹配准确新 JAR。没有重序列化后冒称原串。worker 原串 612 字节，SHA256 `f10ccc508052990f30f71229c5af440fa7bf4f6aa31e29d59257f1be0364e93c`；fixture 样本 SHA256 `01d7381b2c31aa13a5b7ce48cb6e66dae5d502499e7f7b9441f0d676fd54b87f`。两者 JSON 值相等、原字节不同；样本及 worker 原串分别保留。两侧驱动将这份 worker 原串原样交真实 ledger，内部 Call 顺序来自同一原串解析。

来源 agent 仅用于本次 scope worker 的转换取证，actual ledger 驱动没有 agent。trace 仍使用原 fixture 样本经 `Payloads.json(Response)`，不扩称该组也取得了 worker 原串。

## 验证与原件限制

完整定向门禁：`mvnw.cmd -B -ntp verify -Dtest=ResponseDiagnosticObservationTest -Dit.test=ResponseDiagnosticEvidenceIT`，退出 0。3 项诊断设施单测（SQL 链／suppressed、证据落盘失败仍保主错、原转换后唯一探针），1 项已执行证据合同集成检查；SpotBugs bugs／errors 均 0。**该集成读取两次实际实验的原件，不再启动数据库，也不把 scope 实际脚本退出 1 变成产品通过。** 两次实际实验独立执行：trace 脚本退出 0；scope 脚本退出 1，两份完整清理回执。

首次诊断编译有局部变量重名失败，未运行数据库或产生新受测 JAR；仅有诊断摘记，完整初次 compiler 原日志已被后续 compile.json 覆盖，不能虚构原件。trace run1 原始 driver 日志、完整 outcome／timing／rollback／事实保留，但执行 class 和完整源码未在 run2 编译覆盖前归档，不以重建件冒充原件。scope run2 的执行源码、编译 class、agent、编译回执和 supervisor 已在后续格式／门禁前准确归档。最终源码仅格式／设施单测及证据合同补充，均另可核对。

生产 Java、413 份资源／冻结输入及 schema22／V1–V22不变；旧 SQL 实删 0，库存仍 182 静态／26 动态扫描项／587 binds。所有历史失败继续留档。本片不关闭 B2、35 表＋只读 receipt、core 最后 beforeCommit、scope 完整成功 read、持久化 FAILED-read、独立后记、冷锁／完整锁排列或历史 v1／503／后继派发超时。下一具体工作仍为 scope 累计截止末尾 account／异常翻译／回滚阶段分账，历史 trace 回滚根因继续 OPEN；metadataView／recent 与 owner 历史投影排队，交产品经理排定。
