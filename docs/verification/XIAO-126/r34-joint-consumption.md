R34完整联合消费切片已实现并完成必要定向验证；唯一一次原两节点业务仍失败，不能标为稳定候选。第二节点未提交，后续新Work独立full15/event8未执行。按R34约定停止本run生产优化及业务重试，交原PM接续裁定。XIAO-125/126保持in_progress，PR #24保持draft，master不合并。

## 准确源码与制品

- 生产基线：`b695e74a5e65417523fc187f069eecc3a598d228`；本轮起点（R33文档HEAD）：`c77372a31f5bb05e67e5774cb700b52e976c1f37`。
- 联合实现：`944944cda991ab1f992dc2e9b1f29e6652309748`；最终代码：`7559cf0f7492fd64ba9de76a3dc515deeb10ab46`，tree `e7b5e5acde897fbd95dda514535791cc54a55b76`。后续仅追加本报告文档提交，交付HEAD/tree另见远端收据。
- 完整`jimu-r34-final.jar`：45,478,502 B，SHA256 `1b1ae9f9fda7877ca21d73b56f74a511818be2dd445d8be8242f50d80cc64b7d`。
- `r34-manifest.json`及`r34-source-and-classes.zip`绑定18个变更源码、67个相关class（含内部类）、114个Mapper XML与完整JAR的准确字节。不是可复现构建声明。最后一次改动只影响`JdbcSessionScopes.class`，与前一完整JAR逐entry对照保存在manifest中。
- 交付仍在`feature/XIAO-125-session-lineage` / [草稿PR #24](https://github.com/Love-XiaoPeng/Jimu/pull/24)，base master `74c79014d7411028596fa40330d0ad009adaa22a`。

## 实现、动作变化与owner

`JdbcSessionScopes`拆出`requireProof/ensure`与兼容`facade`。忽略返回值的createLineage、ledger访问及内部调用用ensure；真实JSON/Map消费者继续require。首次eligibility、完整prove、stagedGraph/withLineageScope、scope/live与graph交接保留。typed digest/来源谓词消费原Validated；谓词每次按当前Work重新prove，不捕获跨Work证明。无Work仍走旧facade兼容校验；缺query/foreign/已完成及Error按真实Work首错处理。最后小提交复用本次已发现owner，保留current(query)与健康校验。

`SessionMetadataAccounting`增加默认不读数据的可选`finalTable`和默认`accountAndReadBack`，未启用时回退原batch。普通cursor、history14列、lineage8列三个FINAL入口直接验证原行、累计短标量；FINAL不再生成slots/values迭代器/兼容facade或task长度wrapper。原类型、NULL/presence、UTF8/特殊年/长度来源及history加载连接/SQL/bootstrap检查保留。history私有扫描仅入口及交接两次公开open检查，旧公开API仍逐次检查。

service按原18表顺序吸收summary，保cleanup headroom、PRESENT/PURGED不变量和最多四轮固定点。accountAndReadBack仍仅在数值变化时UPDATE并检查changed==1；每个分支均fresh balance SELECT及八项比较。原source/fullFact/nodeInput新raw、生成/插入/fresh readback例外、动态权限、event后新view、末端currentState/terminalAccess/live/expiry/fence、seal/beforeCommit和真实commit/UNKNOWN路径未删改。本次业务未到达的段只作源码保留说明。

费用依据沿R32/R33与R34合同：旧facade专属792、普通FINAL slots/iterator 851..1047、history原50/53次open变入口/交接2次均只是可消失旧动作，不是净节省或完整缺口。新实现继续预付guard12、每表route/服务fold交接32、account/readBack及比较20，三个扫描的分支/算术/识别/返回/清理逐点付费；实际明细以源码及ordinary/named CSV为准，不用静态机会量推导预算必过。

scope/graph借原scopeScratch/graphScratch，不提前退款外层withProof输入；普通行和Result workspace保持，FINAL不分配slots owner；history保持267596+8192连续bootstrap及转交/Result owner；lineage原RETAINED_BYTES不缩减。FINAL4096覆盖summary512、balance/row重叠1024、固定点/formatter2048、闭包/局部512，先接收短标量，原行/current/marker断链后close，再账户写/读回。Runtime/Error登记原首错，try/finally及suppressed保留；不以退款掩盖活引用。

## 必要验证及边界

Windows Java21原工程执行`mvnw.cmd -B -ntp spotless:apply verify -Dtest=<定向选择> -DskipITs`。完整参数、测试方法与XML properties在`r34-verification.json`、原日志/XML中：

|收据|测试数|结果与范围|
|---|---:|---|
|r34-initial-verify.log|编译失败|误用包私有owns；随后改用现有current(query)身份检查，保留失败记录|
|r34-verify.log|48|受影响scope、三个batch、task-sized、service/fixedpoint/balance；verify及SpotBugs通过|
|r34-consumption-verify.log|4|新增proof/facade、typed消费与生命周期；通过|
|r34-final-verify.log|11|history loader、FINAL/fallback、Error/关闭/foreign/stale及account故障等新增边界；通过|
|r34-owner-verify.log|3|最终owner查找改动的scope/consumer/Error必要回归；通过|

以上为增量验证，54个不同测试方法；不是全部54在最终JAR重跑。后续验证均SpotBugs 0错误/0缺陷，集成测试明确skip。FINAL数据注入等价测试不冒充真实SQL来源门；旧有效raw/依赖/REQUIRES_NEW/resume成功证据按原版本复用，未重跑旧全仓矩阵。

## 唯一一次原两节点业务

命令：`wsl -e python3 sql_batch_r34.py second wsl genuine-two-r31-second-event-history-admission-only-observe`。模式保留r31是原runner选择secondEventProof的既定条件；人口、expected、绝对2秒、65536操作/256RS/1MiB及原预算单位不变。原前台两进程、monotonic/phase flush、180秒全成本、finally清理join保留；observer test-only、transformed=1/error=none。只运行此一次，没有失败后更换投影/codec/收费点或重试。

|query/phase|已返回operations|拒绝预付|reads/bytes|scratch peak/end|事务|
|---|---:|---|---|---|---|
|1109306750 / submit-0|53,342|无|84 / 22,369|510,483 / 65,536|COMMITTED|
|1853443923 / finish-0|53,845|无|76 / 22,540|456,292 / 65,536|COMMITTED；SUCCEEDED/quiescent|
|51101315 / submit-1|65,520|下一47；请求总数65,567|64 / 19,637|344,747 / 65,536|ROLLED_BACK / BUSINESS|

65,567含被拒47，不能称为完成操作数。拒绝时statement=null、column=null；普通callsite为`SessionQueryWork$Projection.loaded:220`，栈经`SessionAcceptedTaskRows.load:78`、`MapperTaskAdmissions$Locked.accepted:547`、`TaskAdmissionServiceImpl:521`。CSV该callsite三次请求共141、两次返回共94，最后47未返回。日志中的query totals沿原计数包含拒绝请求，observer区分returned/requested。

首节点`032c564b-158e-4653-9609-e7a3e6e63c2f`受理并合法finish。第二已执行source/fullFact/nodeInput、lineage生成插入/读回、ensure prove与accepted事件相关写；在accepted投影loaded预付处拒绝。第二后续view返回、FINAL18表/account/readback、末端live/fence/seal/commit及新Work独立full15/event8均未到达，其整笔成本/耗时仍未知；不能将首受理/finish的FINAL成功代替第二尾部成功。

第二前后22张表逐表完全一致，`OWNED_FAILURE_DB_UNCHANGED true`；确认回滚，未记录为UNKNOWN或部分提交。原transaction outcome的remainingMicros分别565175、1139352、1417005，失败因操作预算，不能据此推算未知尾部耗时。runner退出0仅表示保存预算拒绝checkpoint，业务不PASS。该mode没有新wire/response验证，旧response证据仍仅绑定旧版本。

`cleanup.json`：containerRemoved=true、rootRemoved=true、childReturned=true、failures=[]；全成本61.183723982秒。所有前台工具已返回，私有容器/临时root及子进程已清理。

## 接续与未验项

两节点要求未降低；九节点maxItems9及资源超额明确拒绝沿已批准语义，不恢复无条件成功。A12、M2同response三trace/全出口、独立DB新fence冻结槽、真实网络/ACK仍未验；独立夹具无SQL fence回调不能代替新fence门。typed仍test-only。无CI运行/等待、部署、付费、真实Key、生产共享DB或master合并。原备份/pycache/UNKNOWN均保留。

原PM唯一终态once：rule `01a190a2-bb51-70c9-9f9c-9e381e987808`，准确source run `01a190a2-48eb-784d-a462-44d0cce192c5`，三终态/首次3h/on-timeout=wake，已保存收据；不重复派工或叠加once。交PM依据本轮准确失败接续裁定。回退为普通追加提交撤销Java消费切片，无表/codec/数据迁移，保留历史失败证据。
