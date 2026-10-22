# XIAO-50 架构交接包 v1

结论：可以交原后端唯一实施。产品范围、接口、状态与持久化取舍已定，不存在待用户决策的架构阻塞。本回合未启动后端/其他成员，未修改产品代码或创建实现PR。

先读 ADR-0009-task-todo-projection.md，再按 implementation-contract.md 实施。contracts/ 是可入库的 manifest、输入/输出/API/binding Schema、catalog、描述及11个示例；migration-contract.sql 是 V17 数据设计，未执行。原后端将本包放入 `docs/verification/XIAO-50/architecture/`，ADR另索引到 `docs/adr/0009-task-todo-projection.md`，运行契约资源随实现进入src/main/resources；冻结输入不改。

最小差异是 todo-only 的显式本地 paid=false profile、独立binding、原子 PENDING→SUCCEEDED 投影提交和 owner GET `/api/tasks/{taskId}/todos`。一个 task 对应一个用户 turn；attempt恢复不清空；GET＋现有SSE失效通知重连。零/一进行中、完整替换/trim/去重、空清单与未写、所有来源和边界见合同。todo清单不成为调度队列、业务验收或权限。

evidence/source-map.md 给出实际源码位置和决定依据；sources.json 是24个固定上游文件的Git blob/SHA256，jimu-verified-blobs.json 是672个实际master blob核验清单。PACKAGE-MANIFEST.json 固定本交付的所有文件摘要。VERIFICATION.md 只记录本回合真实执行及未执行项。

后端首步：核实际master/改动，入库本契约，尽早开关联master的草稿PR，交“真实受理→唯一循环todo_write→owner GET”的可构建切片。并发、故障、权限、保留与真实旧二进制升级证据按合同§6完成，再锁同SHA/JAR交原审查和QA。保留原失败及UNKNOWN旧账；不运行GitHub CI，不操作187持久服务、部署、付费资源或外部通知。
