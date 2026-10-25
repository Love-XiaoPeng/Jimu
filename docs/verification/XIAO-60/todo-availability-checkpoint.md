本片只迁移`JdbcLedger.todoAvailable`完整发现资格，基线`4154fdd568bb9f4804e0f2d4f0de2d11278c1ed6`／tree`1228c528801690da636497380862b8d400d65b84`。准确交付源码/tree见同版manifest及Git提交，沿原分支`agent/agent/7aeccb14d7a9`／PR22草稿；仅开发自测，非独审QA或B2总验收。

准确受测JAR：`c33c4e5d5856885bcb0bad3a27900f4e2ff3c9576b18338736a0f766f48532f6`，44536606字节／1566生产entry。旧原JAR为`b771316eadd317749cae38319036f96904c6a67c8b12299d6eac2f3a2b4a6d27`，44,528,016字节／1,559entry；不以重拼制品替代实际原JAR。

Service完整承载flag在try外→selected(identity.tenant)→requireTodo→两列安装查询→空行原404/NOT_FOUND转false／否则首行→第一次descriptor/version→成功才第二次descriptor/digest→businessPolicy→scopeRequirement→evaluationRunId三个独立NULL判断。类型化端口分别惰性返回presence，不把AND编排留在适配器。requireTodo内部原business→evaluation→scope不改，尾部仍business→scope→evaluation，再次调用不缓存。仅catch原try内TaskFailure；flag错误、SQL/NPE/ClassCastException等原异常传播。两次descriptor、接收者/参数求值、字段String cast及短路顺序保持；端口构造只保存引用，不取值/会话/模型。

一个旧JDBC点迁现有InstallationMapper.selectMaps真实MP，仅plugin_version/plugin_digest、完整tenant和固定todo_write键。实际旧SQL为`SELECT plugin_version,plugin_digest FROM installation WHERE tenant_id=? AND plugin_id='todo_write'`；新SQL为`SELECT plugin_version,plugin_digest FROM installation WHERE (tenant_id = ? AND plugin_id = ?)`，bind1为原tenant，bind2为todo_write。无enabled、锁、排序、LIMIT、JOIN、distinct、全实体预解码或额外查询。非空原首行而非selectOne严格行数。SQL仍执行，SQL实删0。

真实公开链保持TaskController认证→TaskService→Tasks→Ledger.capabilities→TaskCapabilitiesService原tx/identity/enabled清单排序/逐候选stream；仅todo_write抵达原filter调用本Service。direct disabled安装原可匹配true，公开enabled清单仍独立过滤。其他gate、重复selected、todoAccess/Projection/执行存储维护、directory/session内部、完整capabilities编排、scope后记/特殊拒绝结算及所有原access调用点不动。catalog保持nullable query，无新增session queryView；其他入口起点/累计实计/绝对两秒/末尾deadline、同DS/UoW和锁保持，无新事务/SQL/CAS/双写/拒绝或socket/network设置变化。

其余571份既有生产Java及418份资源/冻结输入逐字节同，新增4份生产Java；Ledger去除本片方法/字段后其余内容同。相邻Bean/bootstrap/POM/schema22/V1–V22不变。库存171/29/567→170静态/29动态扫描/566binds，迁1JDBC点、SQL消除0、36表/receipt账均OPEN。两原JAR逐生产entry核1551既有entry同字节、7新增；Ledger及7嵌套class变化，后者独立解析仅Code.LineNumberTable不同。target/classes逐entry匹配准确新JAR。

本轮一次定向verify：45本片单测（原顺序/flag外try跳读/首行空行多行/NULL类型/两descriptor及接收者参数/五项短路/独立尾策略与空run串/原TaskFailure边界和其他异常传播）、2追踪合同、1架构，共48项过；1真实IT过、verify退出0，SpotBugs bugs/errors0。未重跑所有模型/availability/模块、directory或owner/幂等/response六组、旧大矩阵、冷路径或等自然故障。

实际命令：JDK21下`.\mvnw.cmd -B -ntp verify -Dtest=TodoAvailabilityServiceTest,PersistencePathTrackingTest,PackageBoundariesTest -Dit.test=B2TodoAvailabilityIT -Djimu.todo.availability.evidence=b2-todo-availability-run1`；WSL IT使用固定Java21，原Maven日志/退出码/编译输入封存。之前只运行spotless:apply和原路径追踪生成器；后续仅离线分析/制品逐字节核验，不重跑Java/MySQL。

旧新各13项完成RESULT及完整原SQL记录：直接成功、禁用+NULL Identity、tenant-b、disabled安装direct true、公开清单排除disabled、实际DELETE缺安装、版本/摘要不匹配及公开摘要过滤、requireTodo内部拒绝、查询后尾scope拒绝、catalog身份拒绝、正常catalog。两列查询仅固定plugin字面量→第二bind及括号变化，完整tenant/plugin一致；其他SQL与记录的全bind值逐条相同，不泛化JDBC线路字节/往返相同。direct disabled及内部模型拒绝0SQL；catalog身份拒绝先于清单，公开disabled不触发todo查询。

私有paid=false真实模型selected缓存后，内部拒绝样本临时env paid原串not-false，仍是paid=false模型；不冒称尾策略。另一个尾scope样本临时PropertySource只对原scope key计数：requireTodo内部第一次读NULL，尾部第二次读非法JSON，原解析TaskFailure被todoAvailable原catch转false；旧新分别保留TAIL_SCOPE_READ 1/2，且各已执行一条完整两列安装查询。未改ModelProfiles。business/run尾部变化、非常规NULL/类型/重复行等仍仅单测，真实模型内部不可到达的状态不冒称真实DB覆盖。

旧新/api/capabilities各5项共10个HTTP响应：正常tenant-a/b、expired401、disabled安装、摘要错误。8个200原串/摘要/长度/Content-Type相同；2个401仅比status/code，原串各自保存。driver Boolean/catalog输出亦各存原RESULT/摘要/长度，不冒称worker/provider。task表为空，无新任务/worker/provider/工具执行，无真实Key/计费。

26笔tenant-a十九表捕获事实外层恢复、1份完整清理（所有子进程退出、容器及私有root移除、fixture停止、failures空）。tenant-b只核输出/绑定，非其快照；不称整库/任务回滚/持久提交/FAILED-read/独立后记。前快照原在内存、before/after原串于事后证据落盘。真实DELETE仅证明本片安装缺行；多行/不可表达值另列单测。无新增SQL故障，无自然08S01/失效连接或UNKNOWN恢复/重放证明。

每轮执行source/class、编译回执、JUnit/SQL/HTTP原日志及清理先封存再分析。主错cause/suppressed/next SQLException/SQLState/vendor有则按原链记录，rollback/cleanup/证据落盘错分角色，不遮主错。本轮无产品/测试失败。准备期只读错误路径、过早读取尚未生成日志和wakeup get少issue参数的原工具终端错误完整stderr未另存；说明不替原件，不计测试。所有历史失败/缺件继续OPEN。

本片完成，B2/B0–B5未完。下一具体候选`imagesAvailable`完整发现资格：先从准确head核原FileFailure catch、flags/模型/descriptor/安装及公开image filter顺序，再按后续合同实施；本轮未混迁。60保持in_progress、PR22草稿；B2完整才核B3，B0–B5稳定才锁准确源码制品/影响矩阵激活最终61/62（现backlog）。T48/新功能暂停。

35表+receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read、独立后记、其他真实缺行、自然08S01/709ms/冷路径锁排列与全部历史缺口OPEN；63/64/66/70不重接，72固定旧head不追新head。未CI/合master/部署/真实Key计费/共享数据服务/daemon代理FRP重置。

准确run`01a1737b-e51a-70d6-a6e9-eb7406388f1d`；已核唯一规则`01a1737c-9cab-75a0-bda9-0acec4fd02a2`的filter_task_id、once、task.completed/failed/cancelled、7200秒、on_timeout=wake及派发parent一致，截止2026-10-25T16:35:08.841914Z。不重复创建或恢复旧规则。远端同版开发回读见manifest，非PM独立回读。
