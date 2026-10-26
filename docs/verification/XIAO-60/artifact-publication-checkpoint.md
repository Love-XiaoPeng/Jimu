# 普通产物 stage → verified → publish 检查点

2026-10-26，XIAO-60，基于 `a192da6860281743d18e782e5a66a12685000075`。
本次关闭三个入口的迁移切片，仍属 B2 部分交付；PR22 保持 draft，整体独审/QA和 master 集成尚未完成。

## 实现和兼容

`ArtifactPublicationService` / `ArtifactPublicationServiceImpl` 承接完整事务编排、报告拒绝、首行复用、摘要/大小校验、预算短路、预留与发布事件顺序。
`MapperArtifactPublications` 仅保存账本引用，惰性包装原 task/budget/artifact 行；复用原 `leaseTx`、完整 `fenced(lease,true)`、bucket、reserve 和 event。
三公开方法直接接此 Service；无额外事务、catch、补偿、重试或双写。

原 stage 4 SQL/14 bind、verified 1/5、publish 3/8 全部迁到既有 `ArtifactMapper` 的八个真实参数化语句。
两个 `SELECT * FROM artifact` 展开 schema22 原顺序全部17列；保留租户/任务/产物条件、原无排序首行和 producer 的 ordinal DESC LIMIT 1。
CAS/表达式/精确 NULL 写保留原 SQL，更新计数仍忽略：重复 verified 不改 key，缺失产物的 verified 仍记原事件；PUBLISHED 提前返回。
storageHeld 仍包含 media_checkpoint_held，预算算术、短路与两次 ID 生成顺序不变。

仅改三个入口及新增字段、专属 Service/port/adapter 和 ArtifactMapper。宿主、Controller、ReportPolicy、schema22/V1–V22、Bean/bootstrap/POM、原 leaseTx/fenced 等未修改。
路径清单按 javac 解析重算：**130 静态 / 29 动态 / 432 静态 bind**，较 parent 消除8静态/27bind；35表及外部receipt仍全部 OPEN。

## 实际验证

Windows Java21 / Wrapper Maven3.9.9；私有 Linux Java21 / MySQL8.0.46，paid=false。

```text
mvnw.cmd -B -ntp spotless:apply
mvnw.cmd -B -ntp -Dtest=ArtifactPublicationServiceTest,PersistencePathTrackingTest,B2MapperContractsTest,PackageBoundariesTest,RefactoringNegativeGatesTest -Dit.test=B2ArtifactPublicationIT verify
```

最终 verify exit0 / BUILD SUCCESS：9 Service、3 Mapper、2清单、1包边界、3负向门禁，共18单元/架构测试；真实IT 1项，failure/error/skip均0。SpotBugs bugs/errors均0。
旧版从准确 parent 私有 detached worktree 编译打包（只作对照制品，不冒充新增旧版测试）；新旧使用同一已编译 Scenario，各18次公开 Ledger 调用，共36次。

两侧都真实 registerWorker/claim，核 RUNNING task、ACTIVE attempt、current_attempt/worker/fence 和 live lease。step/已成功 invocation、pin和摘要为显式已存 SQL setup，非宿主/工具/provider生成；没有启动 worker 循环。
覆盖新建、STAGING重放、超限、hash/size不匹配、未核验发布拒绝、verified及零行重放、publish及重放、PUBLISHED stage重放、verified缺失产物计数忽略、三个入口各自的 fence 和权限拒绝。

旧新对应 SQL 次序、数量和 bind 位置相同，只有两查询的显式投影展开。新建 stage 每侧23=2普通Statement+21 prepared；verified 18=2+16；publish 20=2+18。
STAGING/PUBLISHED stage重放各14=2+12，verified重放14=2+12，publish重放13=2+11；fence拒绝各6=1+5，权限拒绝各10=2+8。包含底层资格、receipt存在性COUNT、时钟与原commitLiveFence，不称三入口无写或只执行新增Mapper。
实际 setter 从部分 setObject 改为 setLong，Mapper读取使用类型化getter；原SQL/原bind及两侧调用、完整前后字段和每侧ID/时间关系均保留。当前getter记录器统计单参getter，不把未记录的双参getObject算作零。

逐侧核17列产物、原hash/size/key、producer关系、storage_held增量、STORAGE预留、SETTLED actual=amount、三类事件仅一次及重放不改key；数据库时间和随机ID只核本侧关系。
两侧均使用一个外层夹具事务，最终 rollback 后比较该私有库全部表快照（包含worker_process）相等。内部失败会标记外层 rollback-only；这里没有逐调用持久提交或独立事务后记的验收声明。
私有容器、root目录、子进程和fixture均清理成功。预期 TaskFailure 原栈保留，不作为测试失败。

未执行HTTP下载/Host/真实工具/provider、queryView有界成功读取或持久提交矩阵；未修改的历史证据继续适用，不扩大本片结论。初次创建 baseline worktree 遇 Windows 长路径失败；仅本次命令启用 core.longpaths 后重建成功。测试执行前发现证据序列化需转换时间值，保留原助手源码/class后修正，未运行该旧助手。最终产品/IT没有失败轮次。

## 制品和接续

受测原 JAR 44,698,790 bytes，SHA256 `b7ed81a537d1b4fe4419e700492289e583c81171076910a38f6427837c299192`。
旧版原 JAR SHA256 `87466680f9f2963e1d3156041f24adbc94369fabfd7ae11e87cac68583811d47`。
原始日志、SQL/前后快照、XML、源码/class和同版制品随任务评论附件交付；源码SHA/tree以该最终评论登记为准。

剩余完整清单见 `evidence/path-tracking.json.gz` / `b2-table-status.csv`。下一可行完整用例为报告 `stageReports → presentSucceeded`（两入口自身11静态/40bind），需同时核其资格/receipt/组合验证依赖再确定关闭范围；本片未实施，也不预扣库存。
其他并行包沿PM既有队列交回，在安全检查点串行接入；本轮未重复实现或额外派工。历史真实缺口继续留档，完整稳定候选才激活61/62；不CI/合master/部署/付费/共享服务维护/T48。
