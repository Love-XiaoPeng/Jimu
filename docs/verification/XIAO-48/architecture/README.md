# XIAO-48 架构交接包

基于实际master `a04bc49a047827b292c7929312ea8d1ae9e31afc` / tree `74f134b2dbaa0c984256129fab2c41a9fb70a15d`。架构范围已具备实施条件；本包不包含产品实现、数据库迁移执行、部署或真实付费请求。

- `ADR-0007-mcp-readonly-same-host.md`：协议、依赖/许可、同机进程与出网边界、取舍、健康/回滚。
- `implementation-contract.md`：三工具接口、授权、任务快照、分页、URI、预算、故障/取消/恢复、V15数据及验证退出条件。
- `contracts/`：3份manifest（JSON是合法YAML）、6份工具输入/输出Schema、server/task/list snapshot 3份Schema、catalog、静态文件摘要索引和非安装用示例。
- `VERIFICATION.md`：本轮实测、命令/退出码、失败、清理和限制。
- `evidence/`：固定上游/规范原文、18份source blob清单、实际master与缓存差异、8份master变更原文、原协议收据、Schema验证及依赖收据。
- `PACKAGE-MANIFEST.json`：本包文件字节数/SHA256；不含自身。

后端先按最新实际master核准自己的现有工作，统一将ADR、实施说明和contracts入库，再在唯一实现分支尽早开XIAO-48关联草稿PR。具体类/表通过配套契约实现，保持历史baseline和冻结材料原字节。只做三项资源只读能力；按同完整SHA/构建交原审查、QA及产品验收。

首批专有受控Streamable HTTP、MCP2025-06-18、sessionless、JSON和POST SSE；不接真实外部服务/密钥，不运行或等待GitHub CI，不改变默认禁用原生搜索、paid=false和既有账本事实。文档里提出的验收项不是已通过产品测试。
