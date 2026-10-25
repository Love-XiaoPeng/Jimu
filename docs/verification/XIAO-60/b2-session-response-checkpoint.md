# B2 response 实现部分检查点（验证未关闭）

从 `2efa699ebe025f9e47862b13cb39e8f7fe228584` / tree `95986e77c7e453cdb1b401b8aeedb42fd351bc72` 沿原分支 `agent/agent/7aeccb14d7a9` / 草稿PR22继续。本片仅开发自测，准确源码/tree以manifest为准，不是独审、QA或B2验收。

`JdbcSessionRecords.response` 已接 `SessionResponseService` / impl、类型化惰性 `SessionResponsePreflight` 与 `StoredSessionResponsePreflight`。Service承担原容量、Call/槽位、工具资格及完整preview规则；adapter复用原codec、公开文本/sensitive、metadata、validateInput、projectedCalls及冻结序列化。该方法本身无SQL，实删0；库存182静态JDBC / 26动态扫描项 / 587字面binds，不新增查询、事务、预读、双写或关闭整表。

metadata(2048L+calls.size()*4096L)→原UTF8 16384/calls4短路→decode→version2/数量corrupt；逐项actual Call→wire id/id/name/arguments及equals→function→read/scope/search/trace短路→callId→validateInput均保留。publicText之后保持LinkedHashMap version/session_id/seq127/type/time/固定source/content_policy→projectedCalls→redacted OR→原Map.of(content,calls)→trace空source_event_seqs→scope空source_event_refs→原event合同校验。NULL、缺失、拒绝/异常及字段值/类型/原始字节/摘要不借迁移修正，外层重复validateInput不合并。

12个既有持久化调用者、ModelDispatchServiceImpl及413份基线资源（328 main/resources+85 frozen，含prepare新增XML）保持原字节，schema22/V1–V22不变。实际入口仍是JdbcLedger.modelResponded→ModelDispatchService.responded→MapperModelWorkflows→session response；lockResponse/成本结算/validatePayload、成功storeResponse/token结算/资格/槽位/bindCalls/responded/sessionFact/account，及已知拒绝结算/可靠拒绝记录后在内层事务外报错的顺序不变。同DS/UoW、显式query/实计、绝对两秒截止、metadata固定点及UNKNOWN不重放保持。

准确JAR `dc1d44dbf9715b0b70fd3bf7293f08d4c83eb816ac2d299d2a60b5023f9f18f0`，44,403,849字节；1,451个编译/资源文件逐项匹配Boot JAR，驱动不入制品。前五轮生产entry payload与本轮逐项相同，但完整ZIP字节/摘要不同，不能当成同一个受测制品。精确分片、各失败JAR无损重建与证据索引见manifest。

第6轮完整定向verify退出 **1**：37项单测/契约通过；1项集成有1项失败，SpotBugs bugs/errors均0，累计18份完整私有夹具清理成功。完成旧/新差异的组：v3-search, v3-read, v4-search, v4-self, v4-cross；未完成组：v3-trace。失败不计通过，本片验证未关闭。

实际命令：`mvnw.cmd -f Jimu/pom.xml -B -ntp verify -Dtest=SessionResponseServiceTest,SessionCallProjectionServiceTest,PackageBoundariesTest,PersistencePathTrackingTest,PersistenceAssemblyTest -Dit.test=B2SessionResponseIT -Djimu.sessionResponse.evidence=b2-session-response-run6`；reviewed Linux Java21。前五轮另指定不存在的SessionInputValidationServiceTest，没有增加计数；末轮删除该选择器。准确报告始终是manifest列出的5个类，共37项。

末轮仍有六个独立私有DB/caller：native-v3 trace/search/read、scope-v4 search/self-read/cross-read，实际HTTP provider返回单调用，驱动不删改捕获的成功响应。scope各有来源任务worker完成，不当成caller完成。完成的组各旧/新7条RESULT按JSON值同序一致；每驱动五笔success、16385字节拒绝、slot等值拒绝、单次storeResponse SQL故障、首条绑定INSERT故障。拒绝核实际token结算、无槽位、可靠拒绝事件及原code/内层事务外错误；绑定故障前核response/调用/token写入，不宣称已有先前绑定。

每个已完成试验对捕获tenant16张事实表及tenant/evaluation预算全列核对外层回滚恢复；每驱动五条夹具续期UPDATE也回滚，预检/account/normalized预热另笔回滚，不修改原两秒生产截止。末轮生产调用/预期异常后立即外层回滚，之后另连核对并输出证明；不更改任何socket/network/事务截止设置。事件及normalized原串/摘要/长度、原response来源摘要由实际执行成功INSERT的JDBC参数和更新数1核验，另核invocation对应及ordinal/slot/provider次序；这不是SELECT回读、持久提交或独立后记证明。HTTP fixture确实返回对应单调用，driver使用fixture同步记录的内部Response样本进入真实ledger/model service；未另核真实worker转换后的完整Response原串与该样本逐字节相等，不能把样本source摘要扩称该worker冻结原串证明。受测SELECT文本/顺序在完成的旧/新组逐笔核对。私有精确数据库时钟替换仅在夹具，生产时钟SQL不改。

失败组内已完成的试验与未完成部分分别见validation-summary和原日志，不能将组内部分成功汇成整组通过；回滚异常没有完整事实恢复证明。驱动未恢复/重放UNKNOWN，没有caller试验提交或provider重放，不称持久拒绝/FAILED-read、全任务、scope完整成功read、独立afterCompletion或整库回滚。Map.of跨JVM字段次序/原始摘要差异保留；先各自核原串证明，再比较JSON值，不泛化wire字节、全部物理SQL/计量或往返相同。

前五轮完整verify均退出1，原日志/JUnit/源码/编译驱动/准确制品保留：

- 第1轮夹具误读event_sha256，真实列为event_digest，旧native实际响应已返回；后仅修夹具并补来源与逐笔回滚证明。
- 第2轮native三调用旧/新五笔通过；旧scope三调用末尾account metadata_bytes读取截止及回滚连接异常。
- 第3轮独立account/normalized预热和SQL timing后，native三调用又通过；旧scope三调用append advance截止及回滚异常，末尾event INSERT采样约2008ms返回。预热未解决组合路径。
- 第4轮尚未进入scope，旧native三调用metadataInvocation读取截止及回滚连接异常。第2/3轮通过不能覆盖该失败。
- 第5轮六个真实单调用组中native search与scope search/self/cross四组旧/新五笔通过；旧native trace/read生产返回并完成后置SELECT/事件证明后外层回滚连接失败，新驱动未进入。这两组没有恢复证明，不计通过。

第6轮只修夹具取证：实际写参数/更新数捕获、生产后立即回滚和回滚后核对，仍未使所有组通过；原生trace新版外层回滚失败，末条采样为response尾部state/code读取，不能仅凭末条SQL证明正常返回或确认根因。没有更改生产代码、两秒截止、socket或拒绝规则，也不恢复UNKNOWN。未再重复整轮；剩余组实际结果以本轮原日志为准。所有截止、回滚连接异常及根因未确认限制继续OPEN。失败制品：[{"round": 1, "sha256": "311b6974667ed8cbc131c5efced5e451f6ea3c05f3f0beeacd098e44ef477d77", "bytes": 44403849}, {"round": 2, "sha256": "ed44ab0c360bd7385b409dcfb66234ae7ad7c3c06daabfd85209edb2d60230db", "bytes": 44403849}, {"round": 3, "sha256": "32134f0a4058e969b73a32f84314140d599a0e732e680cbf2de7d2beaaf555ec", "bytes": 44403849}, {"round": 4, "sha256": "20a5f0bd8a2aab88f4069723d5bcd07b04e98283383acd8526a66152c17f627e", "bytes": 44403849}, {"round": 5, "sha256": "d0a0ea9515e55e3ca1cc06e7325dea000fefbade63a305844b1107d5464522d7", "bytes": 44403849}]。

实现已交，完整响应验证未关闭。优先下一具体工作建议隔离原生trace响应结束/回滚异常并保留主异常、核清原scope三调用截止边界；不放宽生产规则。后续迁移候选JdbcSessionRecords.metadataView/recent完整Service/类型化owner历史投影和JdbcLedger.sessionHistory端点继续排队，未启动。

B2其余runtime/受理/模块/维护、35表+只读receipt、core最后beforeCommit、scope完整成功read、持久化FAILED-read、独立afterCompletion/REQUIRES_NEW后记、冷锁/完整锁排列、原生v1原槽/503/后继ModelDispatch超时及全部历史失败仍OPEN。60保持in_progress、PR草稿；准确run `01a170ef-2a99-7b11-8b3b-ada054c45c01`，已有PM三终态/两小时规则 `01a170ef-7e8f-7485-968f-f4aaef60c5a4`，未建重复规则。交产品经理按准确版本核收与安排验证，不是申请成员授权；无新增外部/成员授权阻塞。未激活B3–B5/初级/61/62/T48或新功能，未运行/恢复/重试/等待CI，未部署/接真实Key/计费/共享数据服务或操作daemon/代理/FRP/重置/维护切换。
