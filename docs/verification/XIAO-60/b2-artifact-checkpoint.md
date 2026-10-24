## B2 artifact下载部分检查点

接续源码 `c5aeb1cf8fa2b383190e158e7464dda0f545e52c`、tree `bed680b477b6b199273f6fe7c1324e76d574f5a6`、JAR SHA256 `5dbaf82e4d132c15239c2fd9f5696b7ab5cedd6802474052415f5c84e78f343c`。本批准确源码/tree/同版JAR由附件manifest锁定。B2未完成，全部35表及只读receipt均OPEN，本检查点不代替独审、QA、业务验收或合并。

### 真实路径与规则

`TaskController.download → ArtifactServiceImpl.download → ArtifactAccessServiceImpl.authorize → ArtifactDownloads → MapperArtifactDownloads → ArtifactMapper.selectOwnedRetained / MapperTaskVisibility.selectMaps`。直接 `Tasks.download / JdbcLedger.download` 也复用同一个授权Service；不是保留另一套元数据实现。Spring装配通过 `ledger.artifactAccess()` 注入同一实现。durable=false仍不创建DS/SqlSession。

ArtifactAccessServiceImpl持有短事务与拒绝次序：先锁当前actor，再按tenant+artifact完整键、task联表owner和PUBLISHED/EXPIRED保留态查候选，再执行task可见性和完整当前权限复核，最后按原严格 `expiresAt < now` 拒绝410。精确相等的到期时刻仍沿旧逻辑允许，EXPIRED状态但未来到期时间也保留原行为；本次不改变保留协议。不可见/未发布仍404，撤权先于到期拒绝，未新增更早的快照/metadata错误。

ArtifactServiceImpl在授权事务返回之后才调用原ArtifactStore，以冻结tenant、snapshotKey、SHA256和长度读取；原POSIX/非符号链接/不可变内容/大小/摘要验证仍由实际存储适配器执行，不在kernel复制文件安全算法。错误仍沿TaskErrors映射，不吞异常，不在事务内读取文件。

同DS、SqlSessionTemplate与既有coreWork→tx→SpringLedgerUnitOfWork不变；保留显式query、原绝对deadline、实际ResultSet计量、关闭缓存，以及原特殊拒绝提交allowlist。此用例没有新写入、fence更新或结算；不改变其他已接纳用例的fence/原子结算和媒体锁序。

锁顺序保持：actor FOR SHARE → artifact/task联查（普通一致性读）→原sessionScopes.lock（按实际分支）→actor重验 →owner-visible task →原epoch/installation及模块权限、scope复核。没有给artifact增加排他锁，也未引入budget前缀。shared visible在actor撤权时仍执行原三列hint并登记scope失效afterCompletion；原scope共享帮手、拒绝后记和其deadline不变，不能将这批写成它们已全部迁完。

### SQL与MP切换

删除3处旧JDBC字面量：下载artifact联查1处、共享visible的owner-task查询及撤权hint各1处。原始SQL及替代在 `evidence/b2-artifact-removed-sql.json`，不重计此前模型55、Invocation25、task受理26处。

联查使用固定参数化XML，tenant/artifactId/owner条件与PUBLISHED/EXPIRED状态原样保留，显式展开原artifact的17列（含receipt_digest）。普通task可见性使用真实MP BaseMapper.selectMaps、固定Lambda Wrapper与显式tenant/task/owner三条件；完整旧task行63列由专用只读投影实体描述，撤权hint只选原tenant/id/capabilities三列。实体没有伪单列主键，不注入by-id；不用于写入，不使用saveOrUpdate。完整行是复用现有多模块授权函数的过渡需要，不是批准扩大返回字段或对外输出数据库实体。

MP参数同级显式传query和wrapper，走原结果实计/截止插件。独立SqlSession工厂依靠 `TaskVisibilityMapper.xml` 的namespace注册MP语句，与Spring扫描兼容。真实装配断言确认独立工厂的selectMaps存在、selectById不存在、使用原DS。

源码库存仍映射153个原入口，剩余411处字面量JDBC SQL、24处动态SQL、1256个字面量绑定；逐路径图、表项和SQL均随本版重采。静态库存/Mapper存在不代替运行验收，35表＋只读receipt全部OPEN。

### 定向验证与失败保留

最终命令为 `mvnw.cmd -B -ntp verify -Dtest=ArtifactDownloadServiceTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest -Dit.test=B2ArtifactIT`。固定LinuxJava21/私有MySQL8.0.46、同版JAR执行；最终退出码、单测/集成数量、SpotBugs、原始日志由manifest登记。范围为10单测及1项真实MySQL/文件/HTTP集成；单项集成内部运行准确旧/新制品，不重跑旧完整矩阵。

13条旧/新下载比较包括：真实worker发布后的200及摘要；跨tenant/owner404；EXPIRED状态但未来到期原行为；过期410；撤权优先于过期；未发布404；actor禁用/installation epoch变化403；长度、摘要、缺对象503；恢复后200。夹具只改本次私有表/临时文件，原对象字节恢复，artifact其余冻结键/版本/生产元数据保持。

直接账本驱动对两版读取实际query计量，重复查询累计恰好两倍，过期绝对deadline拒绝。两连接受控验证在actor完整键上持有排他锁，另一下载连接实际等待；从performance_schema保留等待关系，barrier有界释放后成功完成。记录连接/事务/实际SQL/预检位置；不是死锁复现或穷举维护锁排列。首个成功下载事务的原始顺序和物理连接由证据工具复核，显式投影及MP括号归一化有明确边界；所有原始SQL和实计值保留，不声称网络往返全部不变。

最终实际QUERY两版均为7 reads、4063 bytes、97 operations；重复读取均恰好累计两倍。证据复核确认17列artifact及63列task与原schema一致、选定下载事务的业务语句/谓词/锁序一致且各用单物理连接。受测JAR中的863个编译文件与target/classes逐字节一致，含新Service、Mapper和独立工厂XML注册，未打入测试驱动或旧过渡class。

首条verify：9单测及Spring HTTP的13条旧/新场景通过，但新制品直接账本/显式query驱动因独立工厂缺TaskVisibilityMapper.selectMaps而失败；SpotBugs0不能替代失败集成。补XMLnamespace注册及独立装配回归后重新verify；首轮完整日志、失败JUnit、驱动异常、原SQL、中间JAR摘要和清理回执均保留。没有将首轮失败报告改成通过。

第二轮verify的10单测、1集成及SpotBugs0通过，但随后复核实际计量发现旧版4063 bytes/97 operations与中间新版4040/96不同（均7 reads）。原因是artifact显式展开漏掉receipt_digest，其列名/NULL也计实际资源。补回原17列，集成额外比较实际meter字典，SQL证据工具按原schema逐列核对17/63列，不能只归一化整段SELECT掩盖遗漏。第二轮完整通过记录仅归属中间JAR，不作为最终制品通过依据；按修正后的完整投影再verify本批10＋1，保持每个版本真实结果。

SQL证据工具曾在集成尚未收齐时提前选择QUERY日志并以StopIteration退出；随后真实运行显露上述装配缺口。该工具调用及底层真实错误均保留，最终仅在运行完成后重汇总。初版仅归一化投影的工具与其成功输出亦保留，最终加强实际列集合及meter一致断言；一次临时Node诊断因PowerShell引号失败未修改产品，后用结构化字段核出漏列。此前所有历史失败、合成paid-run资格限制和付费视觉拒绝继承；历史1205按原JDBC观察为40001，旧HY000摘要不准确，没有采集层转换依据，两份旧记录不改写，前批证据ZIP不重做。

### 剩余与接续

下一具体切片为operator执行scope绑定/只读评估入口的业务Service、类型化端口及真实Mapper切换、对应旧SQL删除，随后B2其余task查询/取消、模块和清理路径。共享scope/目录/todo/session/media等内部SQL仍未全部迁移；本批不表示完整task/artifact表关闭。

真实read_image/付费视觉、全部scope组合、完整材料后验拒绝矩阵、维护/清理与下载全部锁排列未在本批重测。没有真实Key、provider付费或共享数据操作；fixture的本地provider不代表付费资格/业务评分验收。必要清理回执覆盖本次私有容器、进程、provider及临时目录，失败也保留。

无成员信息或权限阻塞。回退须整体使用准确旧源码/JAR，不混版双写；未执行部署回退。XIAO-60保持in_progress、PR22保持草稿；B3–B5、初级及61/62不激活。不运行/等待CI、不部署、不改共享服务/daemon/代理/FRP、不重置数据，不启动T48或新功能。
