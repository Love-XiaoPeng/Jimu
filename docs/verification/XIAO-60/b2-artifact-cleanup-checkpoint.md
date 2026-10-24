## B2 artifact保留／清理部分检查点

从源码 `f347569353ececab341f7532719e669d56f6d12d`、tree `83dea9db6b7a29ce518d0ea09f712083e489b796` 接续；旧JAR SHA256 `1ef2696fbf351536c0865de87d2cd428369b892f840860bccb0f3193b42915a0`。本批源码/tree/同版JAR与实际命令、测试、退出码由交付manifest锁定。B2尚未完成，35张表及只读receipt全部OPEN；本批自测不替代独审/QA或业务验收。

### 实际调用与删除

`LedgerMaintenance → ExecutionLedger.cleanupArtifacts → JdbcLedger.cleanupArtifacts → ArtifactRetentionServiceImpl.cleanup → ArtifactRetentions / MapperArtifactRetentions → ArtifactRetentionMapper / ReservationReadMapper / 既有ReservationMapper`。Service持有终态、严格到期判断、writer静默门槛、存储边界、释放条件和故障重试规则。类型化Candidate/LockedTask/Hold隔离行Map、JDBC及MyBatis；既有其他模块维护仍在JdbcLedger按原顺序执行，再进入本批Service，没有扩大为其他清理用例已收口。

删除本用例6个旧SQL调用点，替换为4个新参数化XML statement、1个既有XML statement及1条真实MP读。`ReservationReadEntity`保留原11列完整投影，以LambdaWrapper的tenant_id＋id条件查询；不伪造复合主键、不用by-id。锁artifact保留原17列完整投影。复用既有`ReservationMapper.releaseQueueSlot`的精确更新正文，调用方保留原释放规则，不增加额外状态/类别/任务谓词。入口/参数/旧SQL原文与新映射见`evidence/b2-artifact-cleanup-paths.json`。旧JDBC字面量从370降为364，动态24，字面量绑定1153；不重计前批迁移。

### 事务、锁与恢复

| 阶段 | 原边界与实际链 |
| --- | --- |
| 候选 | 事务外扫描跨tenant的有界64行；三表join均保留tenant＋完整关联键。原STAGING/VERIFIED/ABANDONED一小时规则、失败/取消条件、PUBLISHED/EXPIRED到期条件和未RELEASED预留均未改。没有新公共维护授权入口。 |
| 标记 | 同DS/UoW：budget→原有scope有序task锁/task→完整tenant/id的artifact FOR UPDATE。Java严格`expiresAt.isBefore(now)`；到期只允许SUCCEEDED/FAILED/CANCELLED，未到期只允许FAILED/CANCELLED；随后才查询writer静默。即使现有writer静默也不允许RUNNING/UNKNOWN因推断而清理。标记EXPIRED或ABANDONED后提交。 |
| 删除 | 无数据库事务持锁；只向现有ArtifactStore传持久化候选的tenant/uploadId，调用真实LocalArtifactStore.removeUpload。原上传前缀清理契约不改，无新存储扫描。失败直接传播并中断批次，保留预留供重试。 |
| 释放 | 新UoW：budget→原scope/task→完整tenant/id的reservation读取。若未RELEASED，按原amount扣storage_held并标RELEASED，二者同事务原子提交；已RELEASED不再扣账。无额外artifact锁、reservation锁或新CAS。标记/删除成功而释放失败时，保留EXPIRED/ABANDONED和未释放账目，下次从原候选路径重试，包括对象已不存在的情况。 |

依旧使用原budget串行化、scope失效后记和特殊拒绝提交事务实现，不改变fence、权限或媒体锁前缀；本用例不新增支付/效果调用。所有新增Mapper均显式传query；MP参数同时含ew/query，复用当前SqlSessionTemplate、DataSource与绝对deadline。完整投影与本次受测计量对照不代表所有SQL、元数据查询及网络往返均不变。

### 定向验证及限制

本批选择`ArtifactRetentionServiceTest,ArtifactDownloadServiceTest,PackageBoundariesTest,PersistenceAssemblyTest,PersistencePathTrackingTest,RefactoringFrozenInputsTest`与`B2ArtifactRetentionIT`，按实际原始报告登记通过/失败，不重跑无变化历史全矩阵。`ArtifactRetentionScenario`由准确旧/新JAR加载，同一私有MySQL、实际产品发布的双tenant产物和ext4存储：

- 新鲜/未来产物、RUNNING/UNKNOWN及未证明退出的writer保持对象/账目；三种允许终态到期、失败/取消旧暂存按原规则释放，重复调用不再扣账。
- 存储失败保留hold并成功重试；代理同一runtime DataSource在原artifact标记写和最终reservation释放写注入SQL异常，分别验证标记回滚/不删除，以及删除已完成后budget＋reservation整体回滚和缺对象恢复。未新增DDL、触发器或产品故障注入接口。
- 显式查询上下文实计及绝对过期拒绝；相同DB输入的retained/reclaimed旧新计量对照，重复无候选读取计数无缓存。
- 独立连接持有budget锁，实际产品清理等待，由MySQL performance_schema记录真实锁等待后有界释放、正常完成。两个真实清理线程都提交标记并到达事务外存储屏障，检查数据库无活动事务，再竞争最终释放，账目只扣一次。夹具仅将实际LocalArtifactStore物理删除串行化，排除文件list/delete的非确定竞争；不能据此关闭生产存储并发删除的全部排列，原失败传播/重试契约保留。
- 对照其他tenant保留对象及完整budget不变，task fence/revision/event序列不变；清理过程没有provider重放。合成生命周期/writer状态和私有SQL故障夹具不构成真实付费、视觉或业务评分验收。

首轮verify的16项单测/契约通过，旧制品的11组产品断言完成；新制品在标记事务和存储删除后，释放事务读取reservation时报`ReservationReadMapper.selectMaps`未注册。独立`LedgerMapperSessions`通过XML加载namespace，单独声明BaseMapper不会在该工厂自动扫描；已补齐空namespace XML并将独立工厂的无by-id/同DS装配断言扩展至该Mapper。保留首轮完整日志、失败JUnit、实际受测JAR和驱动，不把中间失败写成通过；最终结果以manifest原始报告为准。

修正后第二轮完整定向verify退出0、BUILD SUCCESS：16项单测/契约、1项旧新制品定向集成通过，SpotBugs 0。11组旧新业务结果一致；在该相同输入上，回收路径均为17 reads/8633 bytes/221 operations，无候选保留路径均为7 reads/0 bytes/0 operations。该对照仅限所测路径。最终及首轮清理回执均确认子进程退出、provider停止、私有容器和临时目录移除，无清理失败。失败JAR通过最终JAR的原压缩数据范围与保留字节重建并校验原始SHA，未用重编译冒充原制品。

既有失败、付费视觉拒绝、非空付费账本异常、完整scope/维护锁排列、runtime scope/receipt及精确CLI装配例外仍OPEN。旧1205的原始SQLState为40001，旧摘要HY000不准确，未发现采集层转换依据；历史原记录不改写。本轮不重新制作或下载历史全套制品。

下一实际切片是已授权B2的媒体保留/清理`cleanupImages`完整Service/类型化端口/Mapper/存储链，再处理其余模块、runtime scope及清理剩余。无成员信息或权限阻塞。回退仅指整体回到准确旧源码/JAR并保持唯一写入；未执行部署、数据重置或混版worker并写。

XIAO-60保持in_progress，PR22草稿；B3–B5、初级及61/62不激活，不运行/等待CI，不部署、不使用真实Key/外部计费/共享数据，不改共享服务、daemon、代理或FRP；新功能继续暂停。
