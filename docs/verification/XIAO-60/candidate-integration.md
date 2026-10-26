本批从`bc21e0f029dd7638ebf13423c6c0379a051860e7`／tree `5c0c5c9d59d8ace8be352bde8fe70e98b5c04a39`接续原PR22，将通过具名独审/必要QA的120接入已有115与G01–G03，交一个完整集成候选。最终源码SHA/tree及原制品以本批manifest为准，未自行激活61/62或合master。

## 准确接收与冻结

120源码`cfa2f51824cd84c1657bf6dcdc0f5cee83d5807a`／tree `d79dfacfee47c87bb5ba2c125e07c3d965f21696`以`cherry-pick -x`接为`07956ba126aa1b40c9eb01486b12cc8783499dd1`／tree `7fae579f870bf914a041c4e32d388ebca3a1f40a`；证据`cdba4530f5f6dbd39389c27c2d63b442f773bf1b`接为`f5c4e1500fe39daa401fcfc733a699943fd3c6de`／tree `fad6ef838d8a792a5d3dc61018f5bd7a71b0aacd`。未用B整树覆盖A，两个接收提交保留来源与原作者。

审计逐Git blob核188既有生产文件、61新增政策类、1既有测试和9证据文件，共259个受审blob完全一致；其余2503个A文件在接入时不变，其中115的8受审blob及G01–G03测试/工具/历史closure全部保留。115的20工厂据原准确blob复用，不重写。最终另有本批图索引、候选closure及说明更新；这些新增差异逐项列入manifest。

原B核对器基底为26f1c23，本批不直接运行它覆盖历史输出。新审计保留其原385项冻结证据：368完整文件中只有115已受审的5个bootstrap文件属于明确来源差异，其他原SHA保持；17个完整Ledger构造/事务/query/同步块重新按原规则核SHA一致。188等价/250源码blob及模块归属复用121独立真实符号核证与122必要QA；没有为bootstrap差异放宽全包规则。SQL、资源及POM未变，native收集和document各4MiB、64KiB页、旧8388608与PriorSession16777216预算的区别保持。

## 本候选实际验证及制品

```powershell
.\mvnw.cmd -B -ntp verify '-Dtest=DurableAssemblyTest,PersistenceAssemblyTest,QueryViewAssemblyTest,PackageBoundariesTest,NativeSearchContractTest,SessionSearchContractsTest,PriorSessionContractsTest,SessionQueryTransactionTest' -DskipITs
node docs/verification/XIAO-60/capture-paths.cjs
.\mvnw.cmd -B -ntp test '-Dtest=PersistencePathTrackingTest'
```

首条exit0，重新编译814生产/438测试源，80项零失败/错误/跳过，格式、Checkstyle、依赖/包边界、打包及SpotBugs bugs/errors均0；明确skipITs，不称完整必需集成矩阵verify成功。图刷新后2项追踪检查exit0，共82项本轮检查。范围包含同DS装配/独立query配置、原事务恢复定向、native总document上限与分页/Unicode，以及两个session搜索版本的政策。没有重跑1615项、POSIX10项或旧业务矩阵。

锁定本A实际新构建原JAR：**45,049,291字节，SHA256 `daf4dc75cdf4431b3358082059e11ca963f9323ca38970c4c5e0762a63f5a96e`**。生产源码在07956ba后未变；后续只有证据、图和文档。manifest记录最终生产源码、实际Surefire执行class与全部打包class/资源摘要，逐字节绑定target/classes；该新JAR不是B、122或前次G01–G03原JAR。本轮没有启动该JAR或新增私库/外部工具/provider进程，因此没有本轮新DB恢复或清理断言。

接入仅受审同值引用，不新增规则/SQL/事务变化，无需重复私库差异。复用未变的G01–G03具名原品证据及123/124独立结果：123无阻断，124新10定向＋原86e8…JAR独立1IT通过、原品1471 class同重建件，均当前in_review待其原PM核收。G01的run4整体失败、35表恢复、框架日志首错/08007/08003及ACK未知保持，run8按同JAR摘要复用；G02原槽结算/重放及G03持久404和403独立后记分证，不改记冷启动SLA或外部并发通过。

121已经原PM按自身范围done，122已在`2026-10-26T22:51:10Z`原PM核收done，130项是其新验证；本A未代验收或重派。B原依赖超时/Windows POSIX四errors、未执行完整verify等记录原字节保留。此次首个cherry-pick因运行环境无默认committer身份而停止；沿用已有用户身份逐命令继续成功，未修改全局/共享Git配置，原日志保留。本轮测试无新增失败。

## 逐表与候选交接

最新`evidence/candidate-closure.json.gz`逐35管理表＋外部只读receipt共36行引用实际Mapper/源码及已交增量；`path-tracking.json.gz`按当前源码重新生成，静态9／动态22／静态bind0，SQL消除0。9静态及8动态为必要基础设施，另5固定Mapper入口、9内存访问候选保持原归属，不为归零改API；历史全OPEN表仅为原盘点，不能当当前全部未迁。

| 阶段 | 实际已交 | 当前交接 |
| --- | --- | --- |
| B0 | 冻结协议/资源/schema、准确图与基线 | 完整候选最终验收G06 |
| B1 | 同DS/manager/UoW、真实MP、独立query与配置 | G01独审QA结果待原PM核收，G06 |
| B2 | 35表业务Mapper＋外部receipt只读链、必要基础设施例外 | G01–G03独审QA结果待原PM核收，G06 |
| B3 | question/todo/directory完整组及HTTP片段 | G06，无本轮已证实实现缺件 |
| B4 | session/media/MCP/native与唯一执行循环边界 | G02/G03独审QA结果待原PM核收，G06 |
| B5 | query/facade治理、115配置拆分、120政策代码全部串行接入 | G04/G05代码已接；G06，代码卡仍in_review |

G01–G03已有独审及必要QA无阻断结论，待123/124原PM按具名范围核收；G04已接115；G05已接通过121/122的120；G06为本完整候选交PM锁定并实际激活原61/62最终独审QA。未发现需另派开发的具体缺陷，不扩大为无限冷路径等待；若后续有真实问题，由唯一A与原报告者最小闭环。

本次仅交差异证据，旧记录按准确SHA和附件引用，不重封未变整套。附件patch以已交86e8…原JAR为基件，能恢复本次真实原字节；manifest及离线脚本验证索引、源码/class/资源、检查数和恢复结果，离线核证不替代新的运行验收。60继续in_progress、PR22 draft、master不变，所有代码卡最终master推送读回前不done。不CI/部署/付费/共享服务，T48暂停。
