# 报告暂存与发布检查点

2026-10-26，XIAO-60。报告实施 parent 为 `8c3cee6a1cca2fb3d7c587088c856b1d35b8ec83`；准确交付 SHA/tree 和原制品摘要见本轮交付 manifest。
本次仍为 B2 部分交付，60 继续 in_progress，PR22 draft；没有合入 master，不替代完整独审、QA 或业务验收。

## 81、82分别接收

| 接收项 | 接收 commit | tree | 本轮必要集成验证 |
| --- | --- | --- | --- |
| 81 报告规则 | `d2b1603cd753c2984015e8ef0bc75d4bb53c4a7f` | `ca8793a6579d8139d1e89cd53811a35773544d69` | ReportPolicyTest / ReportValidationPolicyTest / ReportComposerTest，共26项通过 |
| 82 问答解析 | `8c3cee6a1cca2fb3d7c587088c856b1d35b8ec83` | `9b5a93d09f852ea32ba2f69d22f3430fc51ff56d` | QuestionAnswerRequestDecoderTest / UserQuestionContractTest，共10项通过 |

81 从原 `0a1a0593eeb11f4e77d0245ff98e5213e83cf4c3` cherry-pick，82 由认证附件补丁接收，SHA256 `23114bbcbe7972a90247b9ca04a1ed514e8a70f090bd5c0ca53cd1e218a4632d`。
两项各自提交、推送至 PR22 原分支并远端读回，均无冲突或内容修正。三个受审 blob 分别完全相同：

```text
81 ReportPolicy.java                      7935241356cd77bb2c3e334b500e6772e055ce1d
81 ReportValidationPolicy.java            147f6a8951010d7d6cbdb392e230f770ad849242
81 ReportValidationPolicyTest.java        9093dc1698ca3515a2d023e51bc8ebf29ee343fd
82 UserQuestionController.java            f0d3df510cdf9273f2c3d51cc2cad899e594ccac
82 QuestionAnswerRequestDecoder.java      609a9825b299d1f418d43ed09bd10c1602c05dd1
82 QuestionAnswerRequestDecoderTest.java  c5ee740c021bbce54414f180f6a339279d876993
```

复用85/86和83/84对这些准确 blob 的独审、QA，不重跑旧HTTP、Composer兼容矩阵。81/82继续 in_review，最终 master 推送读回前不提前 done。

## 完整报告用例

`ReportPublicationService` / `ReportPublicationServiceImpl` 拥有 `stageReports → presentSucceeded` 及专属 `requirePresent`、`requireComposition` 的全部资格、分支和顺序。
原公开入口接入该 Service；`MapperReportPublications` 仅保存 ledger / 原 Map 引用，行和 JSON 节点按业务到达处读取，文件和 publication List 不复制、不提前遍历。

Service 保留原单次 leaseTx / fenced(同 lease,true)、1..4文件及路径顺序去重、输入路径比较、逐文件先 composition 后字段验证、原 receipt JSON/hash/长度、首行重放、预算/预留/事件、最终数量检查。
composition 保留 tools/pin、按 ordinal 已确认行、input/output摘要和版本资格、0.2.0图片分支及原单项 Composer.matches。
发布保留按index和artifactId定位、原receipt/key/digest/state全字段校验、逐项产物发布/STORAGE结算，再调用成功/TOOL结算及事件。
忽略更新计数、重复读取、NULL首错和异常传播不变；无新增catch、事务、补偿、重试或双写。

两个共享 reportContext / imageReportContext 仅改包内可见性供单项适配调用，内部字节逻辑不变；Composer、codec、leaseTx/fenced、Host/存储协议、schema22/V1–V22、Bean/bootstrap/POM不改。
81规则通过原 ReportPolicy / Composer 接线复用；82三个文件此阶段保持受审字节。

原13个 JDBC 点/45bind迁至真实参数化Mapper：复用3条既有语句，新增10条到原 ArtifactMapper。
原SELECT *展开artifact17列、invocation27列的完整冻结顺序；条件、排序、精确NULL与表达式写保持。
逐点SQL与全部bind位置由 `b2-report-removed-sql.json` 和真实Mapper加载测试核对，未扩大投影门禁例外。
路径重算为 **117静态 / 29动态 / 387静态bind**，SQL消除0；35表和外部receipt仍全部OPEN。

## 验证与限制

本轮使用 Windows Java21 / Wrapper Maven3.9.9；真实私有 MySQL8.0.46 / Linux Java21，paid=false。
19项定向单元/契约/架构测试全部通过，SpotBugs bugs/errors=0：9项Service、1项报告Mapper、3项既有Mapper装配、2项清单、1项包边界、3项负向门禁。

```text
mvnw.cmd -B -ntp spotless:apply -Dtest=ReportPublicationServiceTest,ReportPublicationMapperTest,PersistencePathTrackingTest,B2MapperContractsTest,PackageBoundariesTest,RefactoringNegativeGatesTest -Dit.test=B2ReportPublicationIT verify
```

此首轮 verify exit1：19项测试及静态检查通过，IT在旧版夹具setup访问private reportContext(Map)处失败，尚未进入报告入口。该侧外层rollback及私有全表恢复已通过，清理完整。
封存原源码/class、原JAR摘要、原栈、失败XML和清理后，只把夹具改用原公开 `reportContext(lease)`；第二轮在setup发现合成来源不足原32 code point门槛，换用 ReportComposerTest 已验的原文。
第三轮两侧Java均exit0、各20次公开入口按预期完成、外层恢复和清理通过；Python把QUEUE/RUNNING也误纳入发布结算断言，包装IT exit1。封存后改为本用例TOOL/STORAGE范围，离线复核exit0，原错与通过记录并存。
生产源码、原制品、19项测试及静态结果在这些测试助手修正中未变。
另一次初始单测命令在显式import门禁失败，修正测试import后通过；原日志保留。

```text
mvnw.cmd -B -ntp spotless:apply test-compile failsafe:integration-test failsafe:verify -Dit.test=B2ReportPublicationIT
```

最后只重跑修正后的报告IT，exit0 / BUILD SUCCESS，1项0失败/错误/跳过；复用未变19项单元/架构和SpotBugs0，原失败不覆盖。新旧Java也均exit0，最终私有容器/root/fixture/子进程清理全部成功。
受测新原JAR为44,728,541字节，SHA256 `a799954065eae313c61aedc4179e148f425f203afb5c45419ab7709f38b822b8`。
旧版原JAR为准确699：44,698,790字节，SHA256 `b7ed81a537d1b4fe4419e700492289e583c81171076910a38f6427837c299192`。
对照复用同一已编译Scenario及两独立私有库；实际registerWorker/claim，核RUNNING/ACTIVE、current_attempt/worker/fence和live lease。
fetch/compose/present为合法显式已存SQL夹具，报告内容由原Composer在本侧数据库report_time下组装，pin/receipt/输入输出摘要匹配；不声称Host、实际网页/工具/provider或存储CAS生成。

旧新各20次公开调用覆盖两文件暂存/发布、暂存与PUBLISHED重放、空/重复/路径顺序、composition先拒绝/摘要、预算、receipt、输出结构/重复/索引、两个入口的fence和权限拒绝、发布重放资格及第二项晚拒绝。
对应SQL数量/次序/bind位置相同，显式列展开之外SQL不变；离线另逐侧核65处参数关系、预算增量、STORAGE预留数额/producer、发布键/摘要、TOOL和STORAGE结算与事件。
新建stage每侧44=2普通Statement+42prepared，stage重放和PUBLISHED重放24=2+22，成功present37=2+35；fence各6=1+5，权限各10=2+8，第二项晚拒绝19=2+17。原时钟、权限锁链及commitLiveFence没有漏计。
实际setter由部分setObject转为setLong；例如stage-new每侧原6次setObject由6次setLong替代，总绑定数相同。Mapper使用类型化getter；完整前后行的类型/NULL与本侧关系由日志保留，getter统计只涵盖单参调用，不冒称双参getObject零次。

夹具为一个外层事务。内部失败会标记外层rollback-only，后续仍在同外层执行，最终强制rollback并核全部私有表恢复（含worker_process）。
末尾第二文件拒绝刻意展示同外层第一文件已经写入、调用/TOOL尚未结算；不能把调用当场快照误称已回滚或持久提交。最终恢复由外层rollback证明，不代表独立逐调用持久事务或afterCompletion/REQUIRES_NEW后记验收。
原时钟、底层锁/资格/commitLiveFence及普通Statement计量均计入；setter/getter差异见原始SQL记录，未冻结或重放数据库时间/独立ID。

本轮不验收HTTP下载、Host/worker生成、付费模型、queryView成功工具读或整个恢复矩阵；未变证据继续沿用，历史真实缺口保持OPEN。
准确原制品以699原JAR的无损ZIP字节重建配方交付，校验器核原字节摘要、逐文件索引、生产class和Git blob；不是重打包。

## 剩余与下一批

完整剩余在 `evidence/path-tracking.json.gz` / `b2-table-status.csv`。下一可行完整批次为Web抓取观察与保存凭证用例 `observeWebFetch → observeWebCancellation → preserveWebFetchResult`（入口自身7旧JDBC/25bind），须连同专属资格核定后实施，不预扣。
当前87/88沿PM既有独审/QA，不重复接管；61/62仍等B0–B5完整稳定候选。其余历史缺行、scope成功读、持久FAILED-read/独立后记及自然故障仍如实保留。
没有CI、master合并、部署、付费、共享服务或T48动作。
