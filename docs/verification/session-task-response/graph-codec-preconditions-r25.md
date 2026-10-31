# graph-v2 接入前置冲突检查点

XIAO-126，2026-10-30。父源码 `90cdaee044486b1fcb06d0543866a15ab4702753`。
本检查点只保存 test-only 紧凑 graph schema 与实际契约反例；没有激活新生产写路径，
不是双 codec 完成、稳定候选或业务验收。原 typed output 候选仍为 test-only。

## 两个实际约束落点

1. `src/main/resources/db/migration/V23__owner_declared_session_lineage.sql` 的
   `lineage_record_v1 CHECK(record_version=1)` 明确禁止建议的行版本2。
   `MapperSessionLineageRecords.insert` 将 `Stored.version` 原样写入该列；
   `SessionLineageServiceImpl.raw` 也只接受1。因此不能仅改新写Graph/Stored为2。
   本轮禁止V24、改V23 checksum或新增迁移，未删除/放松CHECK。
   这是DDL与源码检查证据，本轮没有启动MySQL证明数据库拒绝。
2. 当前唯一持久化契约链为 task history binding 的 `catalogDigest` →
   `session/v5/catalog-v5.json` → `schemas.graph.schema.json`。
   原catalog SHA256为 `c5e41af8f9ff746ade078feaecd5fdad245188829c65e595dc4c0c49dc846a29`，
   原graph schema SHA256为 `e6c4f774c95a0a73c0a1e9e4f0528750adcc2f46b09e2851a6a6689f166a8f8b`。
   原schema严格要求graph version1及每node的 `genesis_snapshot`。
   `task-binding-v5.schema.json` 对catalogDigest为const；`SessionContracts` 构造器
   还独立检查catalog原字节hash等于binding模板；`SessionHistoryProofServiceImpl`
   将数据库binding及其digest与当前精确binding比较。向现有catalog添加新codec项
   （即使保留所有v1项）也改变其摘要，不能只改模板/全局配置让旧任务继续匹配。

现有行没有独立codec/schema pin列，`SessionLineage.Stored.version` 当前表示结构版本，
并没有独立持久化codec判别。JSON新增version2可以标识内容，但不能自动成为原binding
pin链中已批准的schema2。本检查点没有以新增全局hash常量绕过原任务pin。

## 保存的候选和反例

`src/test/resources/session/graph-reference-v2.schema.json` 是独立ID/version2的形状候选，
只含原caller/authority和session_id/genesis_sha256有序引用所需字段。
它不进入main资源或完整JAR，不修改任何v1 schema、catalog、binding或迁移。
Schema只证明键、类型、版本和数量；顺序/精确集合、逐份fresh standalone raw长度/hash/schema、
真实tenant/权限/Work/token/锁/live/fence、owner和全费用尚须在获裁定后的生产双codec中实现。
不以本候选的成功形状验证宣称已完成那些证明。

本轮 `mvnw.cmd -B -ntp spotless:apply` exit0；
`mvnw.cmd -B -ntp verify -Dtest=SessionLineageGraphCodecPreconditionTest -DskipITs`
的三个定向方法通过，0失败/错误/跳过。最终门禁与制品收据随任务附件保存，
没有将编译的522份测试源码计成522项测试。

`SessionLineageGraphCodecPreconditionTest` 验证：新形状接受引用、拒绝v1/嵌入字段/空nodes；
现生产v1读者拒绝v2及重贴1的紧凑raw；原binding→catalog→schema链核同，新增v2 pin后
得到的新catalog摘要被当前binding schema拒绝。没有使用伪造的生产行或放宽校验。

## 交回PM的具体裁定

在保持本轮无DB迁移与旧binding不变时，需要明确批准：结构 `record_version=1`
与graph JSON codec `version=2` 分离，以及**新增独立于冻结v5 catalog的不可变codec pin落点**
及其如何绑定每份新raw、选择读者和接受混合来源；这属于现有实现中不存在的落点，
不能假称是沿已有pin字段接入。若坚持行版本2，则须先改变本轮禁止迁移的边界。
两种选择均未擅自激活。不是断言紧凑图算法无解，也不创建研究/计量/127任务。

按本轮“若源码前置不能成立，交具体合同冲突和已保存代码”的停点返回。
没有充分前置，故两项180秒业务场景均不执行；没有重跑旧六项、旧矩阵、A03、九节点或CI。
外部响应、原230字节ACK、旧raw出口、resume清理及所有既有计量/权限/释放逻辑未改。
125/126按当前合同保持in_progress，等待原PM在本事项内裁定；完整业务验收缺项继续保留。
