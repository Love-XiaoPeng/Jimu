# XIAO-54 架构契约交接

实际 master `3e5f77549562691634c0a166d8b944ecdcdf1bb8` / tree `93d353f8efac07c34676bc3bffb1790e048fc75d`。固定上游 `571902028a75c64c1d767f4ae5fd099485f0ddf0`。

本包交付原码核查、ADR-0013、完整v3契约和V21草案；不包含产品实现或业务运行验证。**先由产品经理裁定read来源追溯与上游surface替换的业务差异，再启动原后端。** 本轮不直接唤醒后端。

- `implementation-contract.md`：可信关系捕获、输入/输出、兼容差异、授权/切点/原槽、owner API、全部硬界限和T01–T11完成条件。
- `../../../adr/0013-captured-session-event-read-provenance.md`：必要技术取舍，proposed状态准确保留业务差异待裁定。
- `contracts/`：read0.3.0/search0.2.0/trace0.1.0完整manifest/input/output/description及event/context/owner/binding-v3/catalog、read来源快照Schema和合成示例，作为新增资源权威来源。
- `migration-contract.sql`：仅V21增量；旧数据不回填，旧V20制品拒绝和实际CHECK名由后端在私有MySQL验证。
- `evidence/source-map.md`、`upstream-sources.json`、`jimu-sources.json`：逐文件原码、Git blob/摘要/行号和实际源码基线。
- `VERIFICATION.md`：本轮真实架构验证及未执行项；`PACKAGE-MANIFEST.json`覆盖交接文件及引用的原始证据。

推荐候选只追溯新v3成功模型read收据实际返回的seq，不能将邻接、模型输入或参数猜成因果；没有surface/替换生产者，所以明确unsupported。旧v1/v2完全保留，模型仅当前task，owner仅当前授权的保留历史。无新服务/依赖、部署或共享配置改动。

核验入口（仓库根）：`node docs/verification/XIAO-54/architecture/verify-artifacts.cjs`；`./docs/verification/XIAO-54/architecture/verify.ps1`。生成器只写本包，不能覆盖旧资源。Schema/原码核验不替代真实MySQL/双worker/业务闭环、独审或QA；GitHub CI不运行/恢复/重跑/等待。
