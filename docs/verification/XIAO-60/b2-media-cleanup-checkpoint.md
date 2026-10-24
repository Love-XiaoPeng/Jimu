## B2 媒体保留／清理部分检查点

从源码 `68767c03e04afddd9e9883cd887d104d6fb3e36f`、tree `75d68184794e3257b4903f2886b035dfe2f81471` 接续。准确旧JAR SHA256 `6c3c1575948ef47e51cdf027e0acb9cf0f0cf9aca8504119ad8f9d3625a51897`；本批源码/tree/受测JAR及实际结果由交付manifest锁定。B2未完成，35张表与只读receipt仍全部OPEN；本批开发自测不代替独审、QA、业务验收或合并。

### 实际调用与删除口径

`LedgerMaintenance → ExecutionLedger.cleanupArtifacts → JdbcLedger.cleanupArtifacts → cleanupImages → MediaRetentionServiceImpl.cleanup → MediaRetentions / MapperMediaRetentions → MediaRetentionMapper / ImageObjectReadMapper / 既有预算与reservation Mapper → SecureImageStore`。

Service持有保留/终态/writer静默/UNKNOWN规则、两阶段事务与存储边界、检查点完整性与仅移除requestBody规则、actual优先的分类字节释放和故障重试。类型化Candidate/LockedTask/Checkpoint/Hold隔离行Map/JDBC/MyBatis。`SnapshotObjectCodec`是snapshot公开API，用原Payloads ObjectNode编解码；Service在解码前检查原始digest。不调整业务字段、编码、冻结限制或协议。

删除原方法13次JDBC调用，其中12次静态字面量调用与1次动态预算更新调用；动态调用包含两个固定SQL正文，UNKNOWN查询有两个调用位置，因此共有14个字面量正文出现位置、13个不同正文。替换为10个新参数化XML statement、2个复用XML statement和1条真实MP读，不能将不同口径混计。静态库存由364降为352、动态24降为23、静态字面量绑定1153降为1129。对应原SQL/参数/路径/锁与事务见 `evidence/b2-media-cleanup-paths.json`。

对象读取用`ImageObjectReadMapper.selectMaps`，显式投影原`image_id`一列，LambdaWrapper包含tenant_id/task_id/DELETING条件。不伪造复合主键或by-id；为独立LedgerMapperSessions工厂保留namespace XML，并扩展同DS/无by-id装配断言。reservation锁读显式保留原11列；复用`ArtifactRetentionMapper.releaseStorage`与`ReservationMapper.releaseQueueSlot`的原更新正文，调用方保留原规则，不增加状态/类别/任务谓词或双写。所有Mapper/MP入口显式携带同级query。

### 事务、锁、存储与失败

| 阶段 | 保留的实际边界 |
| --- | --- |
| 无存储／候选 | imageStore为null直接返回，不触碰records或数据库。事务外扫描跨tenant的version3、RETAINED、三种终态、updated_at严格早于7天的task，LIMIT32不变；这是内部维护，没有新增公共授权入口。 |
| 标记事务 | 同DS/UoW：budget→原locked(scope有序task)/task→writer探针→HELD_UNKNOWN检查→RETAINED对象标DELETING。保留cleanup状态、writer、终态的短路顺序；不从Future取消或任务终态推断writer退出。task自身仍RETAINED，允许失败恢复。 |
| 存储删除 | 第一事务提交后，以实际MP读取持久化image_id，只调用原SecureImageStore.removeUpload(tenant,imageId)，不扫描未知活跃上传。外部删除期间不持数据库事务。异常原样传播并终止批次；未释放账目与DELETING对象仍可重试。 |
| 最终事务 | 新UoW：budget→原scope/task。若已DELETED先返回，避免重复释放；否则重新验证writer/终态/HELD_UNKNOWN，不满足抛原IMAGE_UNAVAILABLE。step按tenant/task/RETAINED FOR UPDATE；先核原request_digest，再只删除requestBody并保存retained_request_digest，原request/body/config/history审计字段不改。 |
| 原子释放／完成 | reservation按tenant/task、MEDIA_INPUT/MEDIA_STORAGE/MEDIA_CHECKPOINT及RESERVED/SETTLED原谓词FOR UPDATE；actual非null优先（包括0），仅null回退amount。checkpoint扣media_checkpoint_held，其余扣storage_held并标RELEASED；同事务清NULL raw_base64、DELETING对象标DELETED、task标DELETED。晚期失败回滚本事务全部数据库变更，先前标记和物理删除保持可恢复事实，重试不重放provider或效果。 |

复用原query/UoW/SqlSession/DataSource与绝对deadline，包括原scope失效后记/特殊拒绝提交行为；没有新增evaluation锁、权限/fence规则、保留期复核或CAS。原媒体锁前缀修复不改。单行写保留完整tenant/实体键；集合写保留原tenant/task有界身份。精确列投影不表示所有SQL元数据和网络往返完全不变。

### 必要验证与剩余

本批选择`MediaRetentionServiceTest,ArtifactRetentionServiceTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest`与`B2MediaRetentionIT`。同一`MediaRetentionScenario`运行准确旧/新JAR，实际入口为`imageStorage`＋`cleanupArtifacts`；两租户免费read_image→受控loopback provider→实际报告/媒体/检查点供夹具。停止生产夹具的control/worker后，在唯一私有库上构造生命周期/字节账目状态，不新增产品DDL、触发器或故障接口。

覆盖新鲜/运行/UNKNOWN、未证明退出writer、HELD_UNKNOWN保留，三终态清理、actual为0/非0/null三种占用、digest解码前拒绝、存储失败与重试、首事务标记失败和最终task写失败引起的检查点/输入/全部账目回滚、已缺文件恢复、删除后的终态拒绝与deadline拒绝/恢复、查询实计无缓存、预算锁双连接等待与两个真实清理者争同一结算，以及其他tenant媒体/budget和本task fence/revision/event/media摘要不变。

双清理者先都提交标记，到外部删除屏障后采集MySQL无活动事务的静止点，再放行结算竞争。夹具仅串行化实际SecureImageStore物理unlink以固定结算竞争，不关闭全部文件list/delete或维护锁排列。合成生命周期/账本状态不构成真实付费或视觉评分验收；历史付费视觉拒绝继续有效。

首轮verify共21项单测/契约，20项通过、1项包边界失败：新增codec被放到snapshot内部port，artifact模块跨模块引用不允许。已迁入snapshot公开api，并删除未入制品的旧目标class；不放宽门禁。该轮在打包前失败，没有新受测JAR，也未启动集成；保留原始失败日志和该轮JUnit。

第二轮完整定向verify退出0、BUILD SUCCESS：21项单测/契约、1项定向集成通过，SpotBugs 0。旧/新JAR的13组业务结果一致；相同夹具回收路径均为23 reads/79324 bytes/295 operations，无候选保留路径均为8 reads/0 bytes/0 operations。此对照只限受测路径，不表示全部SQL/网络往返均不变。最终清理回执确认所有子进程退出、provider停止、唯一私有MySQL容器和临时目录移除，failures为空。全部target/classes与JAR逐项比对，不含测试驱动或旧snapshot内部port class；准确源码/tree/JAR、原始JUnit/SQL/锁等待与清理由本批manifest锁定。

下一实际切片为已授权B2的`cleanupMcp`保留/清理Service、类型化端口、真实Mapper及对应旧SQL删除，随后其余模块/runtime scope/receipt与清理剩余。完整scope/维护锁排列、非空付费账本异常组合、runtime scope/receipt、精确CLI装配例外和真实付费视觉等仍OPEN。旧1205原异常SQLState为40001，旧摘要HY000不准确，未发现采集层转换依据；两份历史原记录不改写，不重新制作旧完整证据。

回退仅指整体回到准确旧源码/JAR并保持唯一写入，不执行部署、数据重置或混版并写。无成员信息/权限阻塞；XIAO-60继续in_progress、PR22草稿。B3–B5、初级及61/62不激活；不运行/等待CI，不部署、不接真实Key/外部计费/共享数据，不改共享服务、daemon、代理或FRP，新功能仍暂停。
