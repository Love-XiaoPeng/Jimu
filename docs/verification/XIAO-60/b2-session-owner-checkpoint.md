交产品经理：B2 metadataView/recent 与 JdbcLedger.sessionHistory 部分检查点，仍仅开发自测。准确源码／tree／远端回读见 manifest；沿原分支 agent/agent/7aeccb14d7a9 和草稿 PR22。本片完整 Service／类型化惰性投影接入真实 owner 历史入口，recent 的完整性规则进入 Service、原 MP 查询进入 adapter；普通查询已是 MP，旧 SQL 删除 0，库存仍 182 静态／26 动态扫描项／587 binds，不关闭整表。

metadata 保留 history(task,false)、upper 算术、PRESENT→retained 短路、原 LinkedHashMap 字段顺序与 Integer/Long/boolean/NULL、UTC 微秒、原 scope require 位置、Payloads.json→owner contract。recent 不重建 List/Map/值、不为空范围加短路，保留 seq/type、tenant/task、BETWEEN Math.max(0,upper-7) AND upper、ASC、LIMIT8、计量作用域及原 Math.min(8,upper+1) 守卫。负 upper／溢出、惰性异常和短路另有单测，不冒充实际产品合法历史。

sessionHistory 保留 query 为空时建立绝对两秒 queryView 的原位置，Service 的事务端口仍调用原 sessionOwnerRead；公共 owner 锁体及其他 JdbcLedger 端点只做逐字节／最小替换核验、未改业务。原 taskId→bucket→scope 锁→tenant/id/owner task FOR UPDATE→404→身份→access→metadata 顺序保持，不合并重复资格/history。13个关键 caller／并行独占文件、413份原资源／冻结输入及 schema22/V1–V22 保持，源码核对证据另附。

受测新 JAR SHA256 `58d42f5467c10fd80490a21baf4242b774bbf39dba7fd05ad3b1018f0077290d`，44,420,868 字节、1466 个编译／资源 entry。旧基线固定 dc1d44dbf9715b0b70fd3bf7293f08d4c83eb816ac2d299d2a60b5023f9f18f0，44,403,849 字节、1,451 entry。两轮使用同一份不可变新 JAR；末轮 Maven 重新打包文件未用于试验，不混为同一 ZIP 制品。最终编译输出全部 entry 与受测 JAR 逐字节匹配，所有非 class 资源与旧 JAR 相同。附件11个分片按 manifest 顺序拼接，完整摘要／entry 索引可重核。

第二轮定向 verify 退出0：93项单测／契约＋1项真实集成（本轮只执行 scope）；SpotBugs bugs/errors 均0。首轮原生完成组与第二轮 scope 完成组旧／新各10笔 RESULT、每驱动10笔捕获19张 tenant事实表全列外层回滚恢复。原生受测 PreparedStatement execute 的61条 SQL 文本／绑定／同序一致；scope 对应数量见 analysis.json，限定为该观测面，不泛化全部物理 SQL／往返。所有非空返回原串均独立摘要后逐字节相等，并对 JSON 值同序对照；仅据本轮样本，不关闭既有 Map.of 跨 JVM 字节差异。

实际链覆盖新 JAR 公开 GET owner 成功、跨 tenant／其他 owner／不存在404；旧／新实际 ledger query-null入口的同类拒绝、格式404、8条窗口、缺行409、单次 recent SELECT 故障503及共享 MapperSessionContexts.bind.recent。SQL故障各只注入一次，保留生产主异常／完整cause与回滚阶段，回滚不遮盖主错。原生 expired 为不可读空recent投影；scope expired 在原 access/require 中先410 SESSION_HISTORY_EXPIRED，未到 metadata 投影，顺序不改。scope仅为空来源scope，没有来源worker／callerworker／模型provider运行或重放。

首轮 verify 退出1原样保留：夹具误期待 scope expired owner 返回投影，旧／新均在原资格位置410；各完成5笔 RESULT、另含expired共6笔恢复后断言失败，后续4项未执行，不计完整组通过。第二轮只修 scope 预期与返回值判定，使用新私有冻结事实副本补齐该组；未重跑首轮已完成原生组、未改生产或两秒/socket/network设置。另保留最初 package 编译因 adapter 访问 private mapperSession 失败，无新受测JAR；修 package可见性后9项初步单测及打包通过。三份完整私有容器清理回执成功，所有子进程前台收回。每轮原执行source/class／supervisor、Maven编译回执／日志及seal先封存再接续。

所有试验调用在外层事务参与下运行；proof SELECT 在计时调用外。窗口中9条 INSERT SELECT克隆仅为 seq/type 投影夹具，不声称冻结event正文／摘要合法证明；retain_until/next_seq夹具UPDATE均随各笔外层事务回滚。没有试验持久提交或provider重放，不称整库回滚、scope完整成功工具read、持久FAILED-read、独立afterCompletion失效后记或全任务通过。没有新增截止内证明查询，也未通过预热／移动原 queryView 构造改变计时。撤权失效后记、完整来源／锁排列、owner其他端点全矩阵均未新增验收。

成员新增并行派工已纳入互斥范围：XIAO-63 中级B独占 DurableAgent invoke/invokeSlot 网关；XIAO-64 初级A独占 readiness/plugin及两类RuntimeConfiguration接线。本片未触碰这些文件、未重复派工；最后安全检查点的实际可用SHA／集成状态见 parallel.json。仍由中级A唯一集成PR22，可用切片分别验入，不等待两片相互完成、不动daemon／模型／并发配置。

完成本次 owner 历史投影迁移与必要差异验证。下一具体 B2 候选为 JdbcSessionRecords.metadata/scalarBytes/account 的固定点计量及条件账目完整 Service／类型化端口，保留所有调用位置、重复次数和条件算术，由产品经理按队列接续；不会借本片合并account或改原metadata固定点。当前无新增成员／外部授权阻塞。

60保持in_progress、PR22草稿。B2其余、35表＋只读receipt、core最后beforeCommit、scope完整成功read、持久FAILED-read、独立后记、冷锁／完整锁排列、response产品验证及全部历史失败／缺件（含原v1原槽、503、后继派发、真实account08S01、709ms及冷初始化剩余区间）继续OPEN。本片未重跑旧response六组或冷初始化矩阵，不运行或等待CI、部署、接真实Key／计费／共享服务数据或操作daemon／代理／FRP／重置。复用准确run的既有三终态＋两小时规则，未建重复规则。
