# XIAO-126：事务证明与剩余探针检查点

2026-10-28。保存实现和补验结果，**不是稳定候选或整体验收通过**；XIAO-125/126保持 in_progress，交产品经理接续原报告者设计差异核对。

Java/XML实现检查点为 `362ab2948c751bc7faaad1a48c129a5fb7559416`，tree `f55d36d48172fb3642b86b5419b3d1bf8143c5b3`。它保存上次失败执行遗留的32个tracked修改和8个业务新文件，没有恢复旧provider会话或覆盖备份。源码与PM备份逐文件摘要一致；本次随后只修改Python夹具和本说明。

同一JAR `jimu-0.1.0-SNAPSHOT.jar` SHA256：`6738a94c1f16146499bdf4bd78257792f19980a7df905ed558965e655e35eb66`。JAR内2,067个classes/resources与当前target/classes逐字节一致，本次没有重建或修改Java业务码。

## 实现范围

SessionQueryTransaction拥有真实事务及SessionQueryWork，嵌套借用不flush；挂起/新事务不继承证明。全部排序任务锁后发布scope/graph证明，末端逐节点当前资格和DB保留期检查、条件fence、seal及beforeCommit保持。可靠/native事件仍同步写入；最外层按dirty集合加载最终元数据并求固定点，再做live/fence。PRESENT终算与旧PURGED/无query路径分开，普通usage拒绝仍先提交后外抛。

这些是代码实现范围，不是全部安全/恢复边界均已实测通过。ACK不明不改写成确定回滚；原失败及UNKNOWN日志全部保留。

## 当前有界实测

本次在独立私有MySQL8.0.46、Java21.0.12、ext4、paid=false模型夹具执行：

```sh
JIMU_IT_JAVA=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21/bin/java \
PYTHONDONTWRITEBYTECODE=1 python3 -u tools/session_lineage_budget_verify.py \
  --jar target/jimu-0.1.0-SNAPSHOT.jar --evidence <private-evidence> --remaining-only
```

退出0，cleanup的containerRemoved/rootRemoved/allChildrenExited/fixtureStopped均true、failures=[]。

- 先核schema列默认确为67,108,864，在held=0时设置私有tenant为该默认；八个真实受理/执行任务各保留8MiB，第九个任务真实返回429 SESSION_HISTORY_LIMIT。拒绝前后tenant/task/reservation/event/history/scope/targets/lineage完整快照一致。不改held、CHECK或产品默认；原非法降额失败仍保留。
- 独立小任务 `3f54665c-1005-48ed-a2d1-b3bdd1dc9944` 在同一响应生成三个trace槽。停止control/worker后，新的JVM通过真实claim将fence从1提升到2，逐原槽返回相同output及摘要；原invocation整行、step_scope、tools_used=3、三条tool.completed及三笔SETTLED TOOL预留均不变。新attempt最终SUCCEEDED且quiescent。原槽导出摘要 `7ba5afd9caccfac668111db8f41033992cc277c7909e34d29c23afd1571d7717`。
- 恢复驱动中独立metadata重算25,522与当时账本一致；finish后历史metadata观测为25,552。本探针不冒称独立重算了finish后的最终值，也没有测试丢ACK、双worker或九节点恢复。

复用的前次证据适用于相同Java源码/JAR：135项定向检查及SpotBugs零问题，后续24项驱动相关检查通过；61个真实写点/beforeCommit失败回滚断言、普通TOOL_INPUT_INVALID拒绝的usage和审计提交、metadata重算保留。它们不覆盖尚未实测的容量/权限拒绝和真实提交/回滚ACK丢失。

旧run仍是failed，整条旧脚本仍失败。本次仅保留其中已有的有效事实：A03任务 `fff8539a-296a-4886-b48d-1cf8e8a7d1a1` SUCCEEDED，search→同响应三个trace→read及五次消费成立；九节点深8和分支排序断言成立。本次没有重跑A03或旧矩阵。

## 互斥计量与实际限制

证据按query身份、绝对deadline和线程解析原始日志；同M2的outcome继续累计，不能把它的事务行与前一累计行相加。P/G/A/L/B是互斥分组；嵌套阶段诊断不另加，全部提取记录的分组合计与reads/bytes/operations一致。逐Mapper调用清单保留在证据JSON。

旧A03/九节点受限成功路径样本：

| 路径 | 最大读取 | bytes峰值 | operations峰值 | scratch峰值 | 最小剩余µs |
|---|---:|---:|---:|---:|---:|
| prepare | 175 | 248850 | 63539 | 279524 | 857670 |
| M1 | 184 | 263582 | 64272 | 279524 | 852260 |
| M2含outcome | 198 | 340454 | 64442 | 280643 | 374484 |
| trace执行 | 175 | 213353 | 63551 | 266896 | 786281 |

各列独立取峰值，不把它们伪装成同一查询。九节点prepare最大读取行P/G/A/L/B=75/18/19/16/47；M1为75/18/37/16/38（保留18表预检）；M2为75/18/37/16/52，含outcome总198；trace执行为75/18/19/16/47。M2.total最长1,571,657µs；同context和原绝对截止未重置。

新小夹具受限路径峰值94读取、137644 bytes、4269 operations、125349 scratch；新JVM三个恢复调用分别62/63/64读取，最大行P/G/A/L/B=3/2/19/8/32。

**未关闭差异：HTTP受理没有调用restrictLineage，仍走旧4096/16MiB包络和同步重复account。** 原control-v5.log九节点受理已COMMITTED的实际峰值458读取、411968 bytes、72443 operations、1174390 scratch，不能算256/65536/1MiB验证通过。这是真实正常受理记录，不是故障注入负例。`JdbcLedger.submitSessionLineage`与`JdbcSessionScopes.require`的条件可对应此差异。本检查点不新增Java修改或再次A03，交原报告者界定和产品经理接续必要窄修复。

原正常夹具另有10条UNKNOWN事务日志，新夹具有2条，出现在control维护路径；不能凭成功任务终态消除这些记录，也不据此推断真实commit/rollback ACK注入已经验证。旧无计量sessionContext还出现reads=0的scratch记录，不纳入受限路径通过结论。

## A01–A16差异

| 项 | 当前证据与剩余范围 |
|---|---|
| A01 门禁 | 同Java的135项/SpotBugs及后续24项通过；旧完整verify失败和冻结source断言差异未关闭，未重跑旧矩阵。 |
| A02 默认关闭 | 同JAR真实隐藏trace、受理403；pin/paid/非本地全矩阵仍缺。 |
| A03 闭环 | 同JAR旧运行中E终态SUCCEEDED、五槽产物和消费成立；旧run/整脚本失败事实保留。 |
| A04 排序范围 | 九节点深8和分支DFS成立；后加F、同时间UUID等完整冻结范围实测仍缺。 |
| A05 权限闭包 | 部分服务/契约证据；跨owner/tenant、UNKNOWN、180秒和无残留真实矩阵未齐。 |
| A06 协议 | 原解析/契约测试；真实严格输入/N−1/N/N+1矩阵仍缺。 |
| A07 原子受理 | 新默认配额拒绝无残留；并发幂等和全部受理写点注入仍缺。 |
| A08 限额 | 受限prepare/M1/M2/trace九节点样本及新默认64MiB拒绝成立；HTTP受理旧包络差异明确未闭合，完整字节/操作/scratch边界未齐。 |
| A09 来源损坏 | 部分契约；MySQL逐字段篡改、cut/pin损坏及无消费矩阵未齐。 |
| A10 M1/M2/恢复 | 同响应三槽及独立小场景fresh JVM/new fence原槽重放通过；九节点恢复、丢ACK、双worker等未验。 |
| A11 撤权 | 末端live及截止已实现/有定向测试；真实撤权串行、flagoff重放、提交前到期和单调后记矩阵未齐。 |
| A12 副本封闭 | 接线存在；GET/SSE/history/model/原槽/download全路径真实矩阵未齐。 |
| A13 清理计账 | 固定点与61个回滚断言/普通usage拒绝提交成立；新表BODY NULL、PENDING/UNKNOWN、精确一次释放和重复清理实测未齐。 |
| A14 截止/R1/R2 | 原2秒累计包络、事务定向测试和当前成功样本保留；维护UNKNOWN、慢body/锁/连接、真实ACK不明未关闭。 |
| A15 迁移 | 同JAR fresh23；最终同JAR22→23和定向旧功能实际回归仍缺，早期升级证据只保持其原制品范围。 |
| A16 收口 | 私有夹具清理成立；来源不唤醒完整断言、同版独审/QA/PM业务验收和master集成未完成。 |

PR仍为草稿#24，base master。未运行/启用/等待GitHub CI，未部署或使用生产、付费、真实Key、共享DB/daemon/代理/FRP；master没有改动。
