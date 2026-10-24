## B2 MCP保留／清理部分检查点

从源码 `289670a9b53760a86ed06c48351c026f6ae7fe5a`、tree `1546c5cd48915e49c3731626efa0d2dc7e43427c` 接续。准确旧JAR SHA256 `5d8081f22498062982e58a75af44165ad2ec9fd69c2891686ec9e381a49b26ac`；本批源码/tree/受测JAR及实际验证由交付manifest锁定。B2未完成，35张表＋只读receipt仍全部OPEN；开发自测不替代独审/QA、业务验收或合并。

### 实际调用与删除

`LedgerMaintenance → ExecutionLedger.cleanupArtifacts → JdbcLedger.cleanupArtifacts → cleanupMcp → McpRetentionServiceImpl.cleanup → McpRetentions / MapperMcpRetentions → McpRetentionMapper / McpCursorMapper / 既有ArtifactRetentionMapper、ReservationMapper及event调用链`。

Service持有writer/终态/UNKNOWN短路规则、权威mcp:预留到root的原映射、实际占用优先释放、正文/游标清理、同事务幂等删除事件与异常传播。类型化Candidate/LockedTask/Hold隔离Map/JDBC/MyBatis。时间条件保留在具名SQL里，适配器绑定原锁定task的updated_at对象，不在Java中换时钟或转换类型；LockedTask只暴露有界过期hold事实及原事件端口。

删除本方法7次旧静态JDBC SQL调用，替换为4个新参数化XML statement、2个既有XML statement及1条真实MP集合删除。原`r.*`锁读改为显式原11列reservation投影；MP `McpCursorMapper.delete`使用tenant_id/task_id/root_invocation_id的原集合谓词，不伪造tenant/task/token复合主键或by-id。为独立LedgerMapperSessions提供namespace XML并扩展同DS/注册/无by-id装配断言。更新正文通过显式NULL写入原body字段；复用原storage扣账和reservation RELEASED正文，不加状态/类别/任务谓词或双写。

旧SQL静态库存352→345，动态23不变，静态字面量绑定1129→1114。入口、原SQL、参数、锁序和替换对照见 `evidence/b2-mcp-cleanup-paths.json`；不重计已交媒体、artifact或其他批次迁移。

### 事务、锁与恢复

| 阶段 | 保留的实际边界 |
| --- | --- |
| 候选 | 事务外跨tenant DISTINCT tenant/task扫描，LIMIT32。reservation/task/result的join均保留tenant及完整关联身份；MCP_RESULT且RESERVED/SETTLED、三种终态，结果过期或结果缺失且task updated_at严格早于7天。没有新增公共维护授权入口。 |
| 锁与复核 | 同DS/UoW，budget→原locked(scope有序task)/task→writer探针→三终态→任意类别HELD_UNKNOWN检查，原短路顺序不改。原完整tenant/task/category/state与LEFT JOIN/时间参数条件FOR UPDATE读取预留。 |
| 清理／释放／事件 | 同一事务内按原id.substring(4)派生root：MP删除该tenant/task/root所有游标→result body=NULL/state=DELETED→按actual非null优先（包括0）、仅null回退amount扣storage_held→reservation标RELEASED→原mcp-cleanup:root幂等事件。复用既有EventMapper/TaskMapper和event序列/可见响应规则。 |
| 失败与重试 | 无外部文件删除；结果正文与游标存储均在MySQL内。清理、账目、删除事件和task事件序列同事务原子完成；异常原样传播并中断批次，不吞异常。任一晚期写失败回滚全部事实，下次从原候选重试；已释放预留不再入选，不重放MCP、模型或效果。 |

沿用原SqlSession/DataSource/UoW与显式query/实计/绝对deadline；原权限/fence、scope失效后记、特殊拒绝提交和媒体锁序不变。没有新evaluation锁、CAS、支付资格、DDL、协议、限制或业务功能。完整投影与受测计量不表示全部SQL元数据查询或网络往返不变。

### 定向验证与限制

选择`McpRetentionServiceTest,McpContractTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest`及`B2McpRetentionIT`。同一`McpRetentionScenario`运行准确旧/新JAR，入口是实际`cleanupArtifacts`；独立私有MySQL及真实control/worker→loopback模型→私有MCP服务生成两个list根结果、一个read输出、多个游标和另一tenant的保留结果。停止control/worker后再构造唯一私有夹具的生命周期/账目状态，覆盖actual为0、非0及RESERVED/null回退；不调用共享数据或真实Key/付费。

验证原未到期/运行/UNKNOWN、未证明退出writer与HELD_UNKNOWN保留，三终态原子清理与事件只记一次，缺结果时以task更新时间判断七天回退，正文更新/reservation释放/最终event插入SQL故障的游标＋正文＋全部账目＋event序列/revision整体回滚及恢复，查询实计无缓存与清理后期绝对deadline拒绝回滚。两个独立产品账本连接都在候选读取后等待原budget锁，采集真实performance_schema锁等待和innodb事务后有界释放，核同一账目与事件仅完成一次。原invocation输出、fence/MCP摘要及其他tenant的结果/游标/budget保持；revision沿原event实现每个删除事件递增一次。

本批前四轮失败均保留，不能当作通过：第一轮旧JAR前置夹具把list＋read误判为两个缓存根，实际只有list占用结果存储，已增第二次实际list且保留read；第二轮旧JAR前置证据序列化不支持SQL LocalDateTime，已仅修测试驱动的递归编码，未改产品codec；第三轮旧JAR已通过9组结果及实际双连接等待，末尾错误要求revision不变，而原event本就递增revision，已改为每事件一次及失败整体回滚断言，并保留原编译驱动；第四轮在驱动启动前因旧barrier目录存在而失败，原目录已先归档再整体移开，第五轮使用新证据目录。四轮均18项单测/契约通过、定向集成失败；原日志、JUnit、SQL、清理及准确失败JAR按manifest留档，不重建历史制品。第四轮归档里的old.log/等待文件来自第三轮遗留，第四轮本身未运行旧/新驱动，以当轮verify.log异常为准。

最终第五轮完整定向verify退出0、BUILD SUCCESS，18项单测/契约＋1项定向集成全部通过，SpotBugs 0 bugs/0 errors。受测JAR SHA256 `7a36857e38676feb9f4016e8dca7c3a46378c0afe4533db38b648a62e3c52777`，43765402 bytes；961个target/classes文件与JAR逐项一致，测试驱动不在产品JAR。准确旧/新JAR的10组结果一致：受测清理均20 reads/6154 bytes/128 operations，未到期保留均7 reads/0 bytes/0 operations；这些是显式query实计，不是全部SQL/网络往返不变的结论。相同驱动两边均观察到两个独立产品连接在原budget前缀的实际锁等待、释放后账目及删除事件只记一次；provider/MCP请求数量不增加。五轮私有MySQL、control/worker/夹具子进程、MCP端口/目录清理回执均全true、failures为空。准确源码/tree及同版制品由manifest锁定。

完整scope/维护锁排列、runtime scope/receipt、精确CLI装配例外、非空付费账本异常组合及真实付费视觉等仍OPEN。本批不重跑历史全矩阵；历史失败、物理删除并发覆盖限制及付费视觉拒绝保留。旧1205原异常SQLState为40001，旧HY000摘要不准确，未发现采集层转换依据，两份原记录不改写。

下一实际切片为已授权B2的`cleanupDirectories`保留/清理Service、类型化端口、Mapper及工作区清理链与旧SQL删除，再接其余模块/runtime scope/receipt和清理剩余。无成员信息或权限阻塞。回退只指整体回到准确旧源码/JAR并保持唯一写入；未部署、重置业务数据或混版并写。

XIAO-60保持in_progress、PR22草稿；B3–B5、初级及61/62不激活。不运行/等待CI，不部署、不接真实Key/外部计费/共享数据，不改共享服务、daemon、代理或FRP；新功能继续暂停。
