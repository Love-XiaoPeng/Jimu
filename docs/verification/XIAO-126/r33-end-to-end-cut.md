本轮已同步获批的九节点资源拒绝契约，未改生产源码。沿准确 R31 源码核查完整两节点路径后，尚不能证明一个满足全部现行约束、可在预算内闭合的完整替换切片。本报告执行当前任务允许的“不能证明可实施时，一次给具体源码/协议冲突和最小方案”停点；不重试同字节 R31，不将局部省费或资源拒绝说成业务成功。

## 依据与新核查

生产 SHA `b695e74a5e65417523fc187f069eecc3a598d228` / tree `6a7d43a93036894668794fff608fe73f2461f2f2`；进入本轮的文档 HEAD `9b0c8db917f3b30073848004c5972421df00b01d`。完整 JAR 45465373 bytes / SHA256 `399fa6e6532d70bf0f07981ed079e400ae5abcc52eedd0651ce0d918a2ab9f02`，本轮不构建新 JAR。R32 调用/费用/owner 工作表直接复用，不重证明九节点 73891 子集。

本轮新核对原 `r31-sql-second/ordinary-and-named-operations.csv` 中第二 query `1471230198` 的已收据操作。`lockAcceptedView` returned92/requested156（含未返回64）；它的 `lockAcceptedSizes` returned61。只是重读保存收据，没有启动应用、SQL、计量器或业务 probe。

这给出两项比“下一64拒绝”更明确的排除依据：

1. 保留前缀和事件后 view，仅把全部 metadata 终算假设为免费，仍至少 `65498 + 814 = 66312 > 65536`。814 是 R32 同一 query 的剩余 view 列下界，已包含那笔未返回64。因此，仅重写18表遍历/facade/固定点不能闭合，哪怕假设它们完全不收费。
2. 反过来，乐观地删除 view 两条 SQL 已付的 `92+61`，再把其余 view 读取费用全部设为0，但保留其它前缀及原18表必读列，仍至少 `65498-92-61+1893 = 67238 > 65536`。1893 只是 R32 已知非空普通 metadata 投影的列基本费/label 子集，未加内容、history/lineage、权限、live/fence、账户和提交。这个反事实仅排除“只删除/替换这两条 view SQL，其他路径保留”，不宣称已证明所有替代实现失败，也不授权删除实际 SQL。

这些算术没有借其它 query 的成本，没有把新静态数当成运行结果；1702、776、26 或64都不是完整缺口或建议的新上限。

## 具体代码与协议冲突

|边界|准确源码事实|不能采用的局部捷径|
|---|---|---|
|事件后返回|`MapperTaskAdmissions.accepted:533` 先取 event 行、写 accepted 事件，再通过 `SessionAcceptedTaskRows.load` 新读 view；`MapperCommitFence.admission` 最后绑定 SQL fence|复用 event 前 Map 会冻结已变的 revision/seq/time；直接跳过 view 还缺真实返回值与消费者权限，并且上面的乐观下界仍失败|
|已发布 scope|`JdbcSessionScopes.require:98` 已在 `owner.scope(c)` 命中时复用本 Work 的 Validated；第一次完整资格后才发布，并安装 live；`owner.authority(parse)` 已复用 facade 的树|再给此处叠 scope 缓存不能消除第一次逐份 raw 证明。只避免首次 legacy JsonNode 转换仍不处理 view、终算和末端成本，不能单独形成完整切片|
|source 与 raw|`MapperSessionLineageRecords.input:119`/`nodeInput:229` 已借原 SourceFact 或完整 Validated，并把 request/genesis/graph 成对读入；新 caller 的 `fullFact:142`、生成/插入/fresh readback 按合同保留|不能把新 caller fullFact 改成纯生成值，也不能让 graph 引用替代 standalone；新 SELECT 即使同 String 身份仍须独立验证。跨 Work 搬用本轮 source/history/graph 违反独立证明要求|
|最终账户|`SessionQueryWork.finish:1253` 先执行 dirty accounts；`SessionMetadataAccountingServiceImpl.finalPresentReserved:82` 遍历18表、四轮内存固定点，必要时 account UPDATE，并 readBack|把计账挪到另一个 query/提交后会让业务先提交未计账数据；SQL SUM/长度总数替换原标量来源与类型检查改变合同；只改 facade 遍历被上面的第一项排除|
|晚权限和提交|同一个 finish 在账户后调用 live、expiry、fence，然后 seal/beforeCommit；`SessionScopeEligibilityServiceImpl.live:161` 重新读每节点 currentState，`JdbcSessionScopes` 的 live 闭包再执行 terminalAccess|早期静态 scope 不能替代当前 actor/installation/pin/epoch、retain、quiet、upper；提前调用 fence 或删回调会改变末端资格和原事务边界|
|独立证明|`SessionJointProjectionScenario.secondEventProof` 在第二确认提交后创建新 query，重新 require，再分别读 full15 和 event8|导入第二受理的 Work 或删一个 history 读取都不等价。原夹具仍无 SQL fence 回调，不能把该只读路径当独立新 fence 冻结槽验收|

## 最小替换边界与尚缺依据

若继续保持现行硬合同，下一代码切片必须同时替换**前缀的重复结构构造/交接和尾部消费**，不能只改最终 view 或 metadata。最小边界是原 admission 的 `createLineage → require → accepted` 与 `MapperSessionMetadataAccounting.batch → finalPresent` 的同事务数据流，随后复用同一适配入口服务独立 require；保留原表/原 codec/原公开返回形状，动态列仍新读，每份新 raw 仍独立验证，所有生命周期仍归原 Work。

可采用的工程方向是让不需要 legacy `JdbcSessionScopes.Scope` 返回值的调用只消费已核结构，把真正需要的 facade 延迟至消费者；metadata 可直接在已有行生命周期内完成类型检查与标量计算，避免数组/迭代器重复遍历。两项都必须与前缀一起计算完整操作和并存 owner，不能单独以省下若干项启动业务重跑。**本轮没有得到它们足以覆盖完整尾部的成本证明，也没有声称此方向已经可实施或保证通过。** 缺的是这组具体方法替换后，从第一次 source 读到 readBack/live/fence/commit 和下一 Work 的完整动作/owner 依据，不是已批准的九节点决定。

因此本轮不落一个未经证明的部分优化，不新建研究/计量卡，不调限额、不分拆事务、不改 expected/真实人口、不扩 generated-readback 例外。没有证据支持一个必须再交用户的新业务决定；九节点批准立即生效，不再次询问。该技术缺口交原 PM 在原126核收，业务继续 in_progress，不能标稳定候选或合并。

## 交付与未验项

本轮仅新增 `current-acceptance.md` 和本文，原 R31/R32 及冻结证据保持原字节；普通追加提交可逆，无生产回退。验证仅做新增文档的 whitespace/diff、准确 Git/远端/PR 状态读回。未运行测试、构建、私库、业务 probe 或 CI；未生成新运行收据，未重新哈希或重发旧 JAR。旧成功继续按原版本及覆盖复用。

真正第二仍是 R31 的 ROLLED_BACK，后续独立 eventHistory 未执行；完整 A12、资源内九节点/M2/新 fence/真实网络和 ACK 各门保持未验。不遗留本轮应用、容器或运行任务，不改变 master、部署和共享服务。最终提交/tree及远端读回随本轮交付收据单列。
