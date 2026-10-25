B2 部分检查点：todoProjection 完整版本／binding资格

本片从 `9b0de578faa44915003a906c9563642d1f3f3579`（tree `668e7c441fb84740817a1bcb925324516c4cd7fa`）接续原分支 `agent/agent/7aeccb14d7a9` 和草稿PR22。最终准确提交／tree在附件manifest及交付评论登记。本报告所在提交与manifest的改动blob逐项绑定，非B2／B0–B5完整交付。

最终受测JAR：`70a1518edcde457853c78acabe631e621625c0bf8aadf880e9403f49add5de9c`，44,556,547字节／1,586生产entry。中间制品run2 `1575d6b287cd7f584e6d33053bfc10ffde92d7d7926493dded6e16f9f1b6d1c1`／44,555,541字节，仅用于run2／run3；不能冒称最终制品跑过该中间版全部12组。

## 实现和原合同

`TodoProjections<R>`、`TodoProjectionService`／impl实际承载完整查询后资格：record版本→todo版本（OR短路）→snapshot首次读取／hash／digest→snapshot第二次读取／原Payloads.read→两个独立path/asInt版本→原contract binding接收者／snapshot第三次读取／equals。原409错误码、Number.longValue／String cast、NULL与异常优先、无catch／无新增事务保持。codec回调只做原hash／read／path.asInt，业务判断在Service。泛型返回原类型行，未裁剪、重写binding或增加revision／PURGED资格。

`MapperTodoProjections`构造不取task值／会话。查询先按原位置依次String cast tenant_id、id，再用现有 `TodoProjectionReadMapper.selectMaps` 的真实MP QueryWrapper两键读取。`select("*")`覆盖全部21列，不使用窄实体默认列集。空行原404／NOT_FOUND，非空首行。原SQL `SELECT * FROM task_todo_projection WHERE tenant_id=? AND task_id=?` 仅变为 `SELECT * FROM task_todo_projection WHERE (tenant_id = ? AND task_id = ?)`；两个bind及原顺序不变，没有锁、筛选、排序、LIMIT或额外查询。精确Mapper-ID及原SQL登记在本片path证据，未放宽XML星号门禁，没有改Mapper、XML、相邻Bean／bootstrap／POM。

中间MP默认HashMap保留了值但改变迭代顺序，run2原ROW_PROOF暴露该差异。run3封存后，在适配器用V17／schema22原21列顺序及原LinkedCaseInsensitiveMap还原原行表示，所有值含NULL直接保留原对象，不提前Number／String解码；未知列也不丢弃。Service／port原始class与中间制品相同。最终run4额外检查同侧原JDBC完整Map值、列序和大写键访问均相同；原proof查询在生产测量之外单列，未冒充生产SQL。

只替换Ledger的todoProjection方法及新增Service字段；其他Ledger方法和调用点源码保持。todoAccess末尾、幂等existing在access后的第二次projection／PURGED、claim的历史model后revision、fenced的actor／flag后PURGED、MapperModelWorkflows.todoRevision以及executeTodo的projection／invocation锁次序均未合并。baseAccess、scope403 catch／afterCompletion、REQUIRES_NEW后记、特殊拒绝结算、同DS/UoW／query实计／两秒绝对截止保持，未调socket、network或事务设置。

迁1个旧JDBC点，SQL仍执行，SQL消除0；库存168静态／29动态／563占位bind。584份其他既有生产Java及419份既有资源／冻结文件原字节保持，schema22／V1–V22不变，无新增资源。最终JAR较准确旧JAR 1,570既有entry原字节同、8新增；Ledger及7既有嵌套class变化，后者逐class仅去LineNumberTable后所有其他字节相同。中间→最终仅适配器主class行为及其嵌套class行号变化，完整比较见analysis和独立class解析原件。

## 四轮命令及原失败

1. run1完整定向verify（TodoProjectionServiceTest、TodoProjectionPortTest、PersistencePathTrackingTest、PackageBoundariesTest；B2TodoProjectionIT）：24项执行、1失败，verify1。端口测试误期望Jackson JSON null的path/asInt抛错，实际原语义是0。未打包、未跑IT／SpotBugs，旧target JAR不归本轮。先封存71项，再只修该断言。
2. run2同范围verify：14 Service＋7 port＋2追踪＋1架构全部通过，SpotBugs bugs/errors0；IT／verify1。旧新各12 RESULT／12恢复均已完成，比较器随后误将两侧幂等新任务生成ID直接比相等。封存113项后修比较器：每个projection绑定对本侧原任务ID核验，原SQL／原串不改。准确封存12对随后离线核过，不是重跑Java/MySQL。
3. run3 `test-compile surefire:test failsafe:integration-test failsafe:verify`：2追踪＋1真实IT过、命令0，复用run2 JAR，只复验幂等重取1组／两侧各1 RESULT与恢复，未重跑24项／完整verify／SpotBugs，跳过不变HTTP检查。96项封存后才修适配器原行顺序差异。
4. run4完整定向 `verify -Dtest=TodoProjectionPortTest,PersistencePathTrackingTest,PackageBoundariesTest -Dit.test=B2TodoProjectionIT -Djimu.todo.projection.evidence=b2-todo-projection-run4 -Djimu.todo.projection.phase=owner-todos,idempotent-retrieval,prepare-fenced,direct-valid,direct-missing,purged-row`：8 port＋2追踪＋1架构及1真实IT过、命令0，SpotBugs bugs/errors0，最终JAR。旧新六组各6 RESULT／6恢复。未重跑未变的14 Service单测，其生产class与run2原字节同。

每轮实际执行source/class、compiler输入／产出回执、完整Maven stdout/stderr、每轮测试结果与实际driver原件均已封存；下一轮前封存完成。错误日志保留主错／cause、suppressed、next SQLException／SQLState／vendor遍历，rollback／cleanup／证据落盘错误单独记录。run1／run2原失败未覆盖。源码审计助手曾漏计上片新增XML，AssertionError及首版脚本保留，修为实际419后通过；不改变产品。准备阶段路径／CLI／rg只读错误完整stderr未逐条独立封存，仍是缺件，不重建日志。基线远端助手曾复用上片文件名覆盖其松散“latest”回执；上片交付包中原回执保持，本轮实际基线回执另存并明示，后续均用本片文件名。

## 真实证据范围

run2中间制品12对，run3中间同JAR复验1对，run4最终制品6对，分轮／分制品报告；合计19组SQL记录／38笔tenant-a二十表捕获事实外层恢复，三份完整清理。每条原SQL、完整actor与installation锁bind、projection完整tenant/task bind与次数保留；幂等新任务绑定逐侧对本侧原task证明，不泛化所有bind相同。重复projection次数见逐轮sql-comparison。

实际DELETE：run2准确direct及public getTodos均到projection404；run4最终direct再次证404。未删安装，也未把端口空行称真实DB缺行。真实摘要坏、正确摘要后codec版本／todoVersion、错误JSON及合法版本不同原串拒绝属于中间run2；最终对应Service及Payloads类原字节同，codec视图仅行号变化，但没有在最终JAR重跑该四类负样本。SQL版本／NULL／非常规Number／类型及多行主键不可表达边界按单测／源码合同分列，没有违反CHECK或制造真实多行。

原成功证明22份逐个保留原串、长度、摘要及可靠事件列；完整原行证明8份，其中最终4份额外断言原列序及case-insensitive键读取。owner两轮各自旧新同字节只指driver对真实Ledger getTodos结果序列化。HTTP四项仅run2新侧setup，非最终配对HTTP／SSE wire证明。原Map.of／生成ID／时间串各自保存，只比较已证JSON字段。

仅开发自测与本地证据核验，非独审QA／总验收。真实任务为私有paid=false；没有worker循环、provider／成功工具执行、最终responded拒绝新验收、SQL故障／自然08S01／失效连接／UNKNOWN恢复。外层恢复只覆盖tenant-a二十表，不能称整库、持久提交、FAILED-read或独立后记；不恢复重放UNKNOWN。其他既有拒绝结算路径只沿用未变源码／字节及原已核收证据，未重复六组response。

60保持in_progress、PR22草稿；B2／B0–B5未完，61/62 backlog。下一具体候选为JdbcLedger.todoStorage的现有待办存储预算资格及原SQL真实调用链，本轮未迁。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read、独立后记、其他真实缺行、自然08S01／709ms／冷路径锁排列及所有历史失败／缺件继续OPEN。不CI／合master／部署、不改共享服务／daemon／代理FRP、不真实Key计费，不重接63/64/66/70或让72追新head；T48及新功能暂停。
