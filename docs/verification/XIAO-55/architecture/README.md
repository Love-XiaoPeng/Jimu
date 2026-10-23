# XIAO-55 架构交接入口

本目录是待产品经理裁定的契约候选，基于准确 master `d6d2d28a8f4fb3c2f05ed2bd410b1d5f65a16fe1`。产品代码、真实迁移/模型闭环、独审和QA均未执行。

- `implementation-contract.md`：固定上游事实、业务兼容差异、授权/恢复/保留、硬界限、接入顺序及C01–C11。
- `../../../adr/0014-explicit-prior-session-search-scope.md`：proposed决定；先裁定v4独立终态来源、显式scope、字面匹配及不支持字段。
- `contracts/`：两工具manifest/完整输入输出Schema、v4事件/context/binding/owner投影、宿主受理/authority Schema、HTTP catalog及例子。不是已装配插件；read输入复用旧字节，跨会话行为和输出显式新版本。
- `migration-contract.sql`：V22候选，不执行生产迁移，V1–V21保持。
- `evidence/upstream-sources.json`、`source-map.md`：48个固定blob/MIT的重新获取核验及原码定位；local字段引用已入库的历史不可变原文件。
- `evidence/jimu-sources.json`、`actual-master.json`：119文件的准确基线摘要与实际master/tree回读；直接git fetch失败事实保留。
- `VERIFICATION.md`：架构验证的实际命令、结果和限制；`PACKAGE-MANIFEST.json`固定包内文件长度/SHA256。

再生成候选：在仓库根运行 `node docs/verification/XIAO-55/architecture/make-contracts.cjs`。验证：`& ./docs/verification/XIAO-55/architecture/verify.ps1`，再运行 `node docs/verification/XIAO-55/architecture/verify-artifacts.cjs`。仅在固定上游或基线需要重新核实时运行capture-evidence，不能用后来master重写历史证据。

后端须先收到产品经理接受范围的明确裁定，再沿本提交接续；本轮未派发实施。GitHub CI保持禁用，不运行或等待。无部署/付费/生产/共享服务变更，未发送外部汇报。
