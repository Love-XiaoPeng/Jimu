本片仅抽离 `JdbcLedger.imagesAvailable`，属于 B2 部分交付，不表示 B2 或 B0–B5 完成。基线 `da45e6f31d86f90402a0dfa821ad5c8591eab333` / tree `11077b3d0c88c428312d4c1f7dfca762914c20ea`；交付准确源码 SHA/tree 由同包 manifest 和原 Git commit/bundle 绑定，原分支 `agent/agent/7aeccb14d7a9`、PR22 保持草稿。

## 完整业务与真实接线

新增纯 Java `kernel/artifact/port/ImageAvailability`、`service/ImageAvailabilityService` 和 `impl/ImageAvailabilityServiceImpl`。Service 完整执行 store 存在检查（try 外）→ store check → codec check → true，只捕获 try 内 FileFailure 返回 false。缺 store 跳过后续；存在读取抛 FileFailure、TaskFailure、IllegalStateException、NPE 和 Error 均保持原传播边界。

`LedgerImageAvailability` 构造只保存 BooleanSupplier/Runnable，原 `imageStore != null` 与 `imageStore.check()` 分别在原位置读取字段，没有缓存同一 store 引用、锁、volatile 或重试。codec 回调直接调用原 `ImageNormalizer.available()`；其 JDK SPI/reader/writer/PNG/JPEG 检查和 LocalArtifactStore 内部均不改。没有 flag、Identity、模型、descriptor 或 installation 查询迁入本 Service。

两个真实调用点原源码不变：

- TaskController 认证→TaskService→Tasks→Ledger.capabilities→TaskCapabilitiesService 原 tx→identity→enabled 安装清单 ORDER BY plugin_id→逐候选过滤；read_image 候选才调用 imagesAvailable，false 跳过 selected，true 后才 ModelImages.enabled(selected)。非 image／更早过滤／重复候选仍保持原惰性与次数。catalog 不新增 queryView。
- MapperTaskAdmissions.Locked.imageStoreAvailable 原 `ledger.imagesAvailable() && sql.maxPacket(ledger.queryContext()) >= 16777216`。新 media 路径 requireImageModel→store/codec→原 maxPacket→后续资格/installationEpoch；existing 幂等路径仍先于 media。没有修改 maxPacket SQL、阈值或服务器设置。

同 DS/UoW/query、权限／tenant 键、锁、queryView 起点与两秒 deadline、baseAccess／scope catch／独立后记／拒绝结算与其他 Service 均不变。相邻 Bean/bootstrap/POM、575 份其他既有生产 Java、418 份既有资源／冻结与 schema22/V1–V22 原字节保持。无新资源、Mapper/XML、事务、CAS、双写或拒绝。JDBC 迁移 0，SQL 实删 0，库存仍 170 静态／29 动态扫描／566 binds；35 表＋receipt 跟踪全部 OPEN。

## 准确受测制品

旧 JAR SHA256 `c33c4e5d5856885bcb0bad3a27900f4e2ff3c9576b18338736a0f766f48532f6`，44,536,606 字节／1,566 生产 entry。

新 JAR SHA256 `c0cab24abd464b219d3d2a2bb6e6d74727a63dc0d27d1c0c9cb680afe428928d`，44539769 字节／1570 生产 entry。读取两份原 JAR 全部 entry：1560 既有 entry 原字节相同、4 新增；Ledger 及 5 个嵌套 class 变化，后者按 class 格式解析仅 LineNumberTable。生产 target 与原受测 JAR 全量匹配。第二轮复用第一轮原 JAR，没有重新 package 或把新测试编译当新产品。

## 验证轮次与原失败

首轮完整选定 verify：17 本片合同单测＋1 惰性接线＋3 既有 image filter 单测＋2 追踪＋1 架构，共 24 项通过；SpotBugs bugs/errors 0。真实 IT 失败，verify 退出 1：旧新各完成 11 RESULT，最后新媒体 accepted/replay 已执行并打印原子事实，驱动随后用 Payloads.json 序列化含 Instant 的 Tasks.View，均抛 IllegalArgumentException / InvalidDefinitionException。此为测试驱动输出错误；完整 cause、原终端日志、SQL、源码、编译回执和 class 随首轮 seal 保留，不能写成首次 verify 成功。

先封存首轮后，仅改驱动输出：明确列出 View 字段，createdAt/updatedAt 保留各自原时间字符串；不改产品、依赖或编码器。第二轮只执行剩余 admission-accepted-replay，复用原 JAR；2 追踪＋1 真实 IT 通过，命令退出 0。未重跑 24 项或完整 verify，SpotBugs 仅属首轮。两轮执行 source/class、编译 input/output、原 Maven/JUnit、驱动和 Python helper 均各自先封存，下一轮后不改原件。

准备只读诊断曾猜错文件／wrapper 路径或 PowerShell 通配路径；完整 stderr 未逐次单独落盘，现存终端回执／说明不替缺失原件。报告生成助手首次把原 SQL 中的双 at 符号误当未替换模板而 AssertionError；原助手和完整 stderr 保留，仅收窄模板检测后生成，不改产品／测试／原证据。历史失败和缺件不关闭。主错及 cause/suppressed/next SQLException/SQLState/vendor 与 rollback／清理／证据落盘错误按驱动分存，本轮没有 rollback 或完整清理失败，也没有 SQL 故障注入。

## 有界真实样本与证据范围

私有 paid=false MySQL 8.0.46／Linux JDK21／本地 POSIX store 和 JDK codec；只复用 ImageExperiment.prepare，不跑旧 ImageIT 完整矩阵、worker、provider 或工具。共 12 对完成结果：direct 缺 store／正常／合成 FileFailure；catalog 正常、缺 store＋缺 retained model、store 正常＋缺 model、enabled 清单排除 image、text-only model、身份拒绝；新 media store 缺失、model 先拒绝、accepted/replay。

原 SQL／绑定／交错 timeline 共 13 组旧新（包含首轮已受理但最终序列化失败的一组），不是 13 对完成结果。store/codec 成功才一次原 SELECT @@max_allowed_packet，再原 actor／installation 锁；拒绝路径跳过 store 或 maxPacket。既有幂等重取在同一外层事务内把 store 清空和 retained model 移除后仍返回相同 View，不重复 store／packet／模型资格。查询上下文保持原 nullable；没有网络／事务／maxPacket 设置调整。未增加阈值两端真实服务器样本。

每侧每轮原 ACCEPTED_PROOF 证明 QUEUED、record_version 3、next_event_seq 1、唯一 seq1 task.accepted / fact_id accepted；原 PNG 输入字节／大小／摘要、media_snapshot 摘要一致，QUEUE RESERVED amount1，MEDIA_INPUT SETTLED actual/amount100。证明均在内层返回后／外层 rollback 前，不是持久提交，也不是工具成功或 FAILED-read。首轮两侧亦完成这些事实及 replay 后才发生测试输出异常，原记录保留。

公开 HTTP 只首轮跑 5 对：normal tenant-a／tenant-b／expired，以及 no-store 和 text-only 配置。8 个 200 原串／SHA256／长度／Content-Type 相同；2 个 401 只比 status/code，原串各自保留。真实配置的正常 store/JDK codec 与关闭 images、text-only profile 分证；测试端口抛 FileFailure 和反射修改 retained model map 是明确合成机制，不当自然文件系统／codec 故障或成员配置缺失。

两轮共 26 笔 tenant-a 二十一表捕获事实外层恢复（含 task_image_input/task_image_object），包含首轮失败样本；2 份完整清理均容器／临时根／fixture 已移除、子进程退出、failures 空。它不是整库／其他 tenant 快照／持久提交／FAILED-read／独立后记。tenant-b 仅公开输出覆盖。没有真实 DB DELETE、新 SQL 故障、自然 08S01、失效连接或 UNKNOWN 恢复／重放证明。

各自原串／摘要／长度先保留。生成 task/input/event ID、由本侧原 snapshot 独立验算的 media_digest 仅在已证明范围替换；Map.of 只按 JSON 值核本片确证字段，View 原时间保留但不跨侧相等，不泛化全部 wire 或所有历史绑定。SQL 顺序与完整记录绑定按上述限定核对。

## 运行绑定与剩余

真实本 run `01a173a5-7daf-71bc-9e2f-6a6eb877d420`；只回读 PM 已建唯一规则 `01a173a7-31b8-762a-ab14-319aeceb7c96`，同 run / parent，completed/failed/cancelled、once、两小时，15:21:39.512630Z→17:21:39.510849Z，last_error=null。不重复创建，不恢复旧规则；最终终态由平台在本 turn 退出后形成，开发不预写 completed。

本片完成，不等 B2 完成。源码下一具体候选为 `JdbcLedger.todoAccess`：原单项 caps→tools pin→SELECT* installation FOR SHARE→五项 pin 比较→todoProjection，待 PM 按准确新 head 分片接续；本轮未迁它。60 保持 in_progress／PR22 草稿，61/62 backlog。35 表＋receipt、core 最后 beforeCommit、scope 完整成功工具 read、持久 FAILED-read、独立后记、其他真实 DB 缺行、自然 08S01／709ms、冷路径排列及全部历史失败缺件仍 OPEN。没有 CI、合 master、部署、真实 Key 计费、共享服务数据操作、daemon／代理／FRP 重置；63/64/66/70 不重接，72 旧 head QA 不追新 head，T48／新功能继续暂停。
