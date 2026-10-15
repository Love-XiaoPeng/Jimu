# 已验收文档基线

来源为 Multica XIAO-33。产品经理于 2026-10-15 接受了文档阶段业务交付，事项保持 in_review 待人工最终确认；独立复核关闭的是文档问题，运行验收未执行。

项目现名积木（Jimu），原名饕餮。附件保持原字节与历史名称；其中 io.taotie、插件 ID 与 Schema URN 不构成积木最终命名决定。原文 `/api/attachments/...` 为 Multica 附件引用，不是 GitHub 相对链接。本目录包含本轮所需八份基线。

| 文件 | 原附件 ID |
| --- | --- |
| [总体方案 0.3](XIAO-33-可行性与总体方案-v0.3.md) | `01a13b9c-25a1-78ae-9e88-e625e1927f8f` |
| [工程规范 0.2](XIAO-33-工程规范-v0.2.md) | `01a13b9c-25ca-71f6-a51c-fe43d521b5c3` |
| [迁移契约 1.1](XIAO-33-tool-migration-evidence-v1.1.md) | `01a13b9c-25ee-7928-942d-683f58caf52d` |
| [工具清单 1.1](XIAO-33-harness-tool-inventory-v1.1.json) | `01a13b9c-26fd-7c0c-b526-07c7d115a82d` |
| [权威输出 Schema 0.2.0](XIAO-33-web-fetch-output-v0.2.0.schema.json) | `01a13b9c-28f6-71b3-99cc-39c899b5ba4d` |
| [修订对照 0.3](XIAO-33-合稿修订与评审对照-v0.3.md) | `01a13b9c-2a15-7dc4-b593-6cbd365ec2cf` |
| [审查复核 v2](XIAO-33-security-performance-recheck-v2.md) | `01a13ba1-cfd0-7f15-8707-c6f54c2a10d3` |
| [测试复核 v2](XIAO-33-testability-recheck-v2.md) | `01a13ba3-6586-7667-8b84-18b490f6d1c8` |

sources.json 记录原附件 ID、实际字节数和 SHA256。前五份文件的摘要与修订对照所载一致；其余记录下载后实际摘要。后续修正通过新文档/版本或 ADR 表达，保留来源快照。

三份命名/依赖/包边界 ADR 已收到，见 [积木实施说明](../architecture.md)。P1 尚需 GitHub 目标核实与入库/PR、实际构建和依赖树、契约/插件实现、双角色 readiness、真实 worker 集成和门禁正反例。文档完成不等于 P1 完成。
