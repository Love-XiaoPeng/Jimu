# 积木 Jimu

万物皆插件，像搭积木一样通过插件组合能力。

积木是以 Java 从零建设的云端 AI 执行层底座。稳定内核承载身份、授权、任务状态、调度、预算和事件契约；模型、工具、技能等能力通过插件接入。现名为“积木”，历史方案名称为“饕餮”。

本仓库承接 XIAO-34 / P1 和 XIAO-35 / XIAO-37 的 P2 实现，已纳入 XIAO-33 文档阶段验收的方案、工程规范、工具迁移清单、权威输出 Schema 和独立复核报告，详见 [来源索引](docs/baseline/README.md)。

- [工程规范 0.2](docs/baseline/XIAO-33-工程规范-v0.2.md)
- [总体方案 0.3](docs/baseline/XIAO-33-可行性与总体方案-v0.3.md)
- [积木实施决定与命名映射](docs/architecture.md)
- [三份架构 ADR](docs/adr/README.md)
- [P2 持久执行决定](docs/adr/0004-p2-durable-execution.md)与[架构交接/资源证据](docs/verification/XIAO-36/architecture-handoff.md)
- [P2 运行配置、API、恢复及限制](docs/implementation-p2.md)
- [XIAO-40 模型核心阶段配置、迁移、预算及限制](docs/implementation-p3-core.md)
- [正常业务受信准入与 DeepSeek 请求策略](docs/normal-business-admission.md)
- [XIAO-40 完整输入门禁与计费证明边界](docs/implementation-p3-input-bound.md)
- [XIAO-40 工作区工具、报告发布与下载装配](docs/implementation-p3-reports.md)
- [P4 edit / glob / grep 实施契约](docs/implementation-p4-workspace.md)与[运行配置及验证](docs/implementation-p4-runtime.md)
- [MCP 资源只读 profile、配置与定向验证](docs/implementation-p4-mcp.md)
- [XIAO-40 关闭评测计划、阶段和额度](docs/implementation-p3-evaluation-plans.md)
- [协作约定](CONTRIBUTING.md)
- [Agent 约定](AGENTS.md)

P1 采用单 Maven 制品与模块化包边界，控制/API 和独立 Java worker 为不同启动角色。三份 accepted ADR 已明确根包/groupId 为 `io.github.lovexiaopeng.jimu`、artifactId 为 `jimu`；JDK 21 / Maven 3.9.9 / Boot 4.1.1 / Spring AI 2.0.1 已取得本地骨架构建证据，等待独立复核。旧文档示例按 ADR 映射，原始来源保持不变。

已提供 Java 21 单制品、固定 Maven 3.9.9 Wrapper、内置插件及自动门禁。完整命令为 `./mvnw -B -ntp verify`（Windows：`.\mvnw.cmd -B -ntp verify`），会检查格式、静态、契约与包边界，打包并启动独立 control/worker JVM、真实 MySQL 和受控 HTTP fixture。需要 JDK21、Docker、Python3 和 Linux POSIX 存储；Windows 通过 WSL Ubuntu 运行持久集成。缺必需资源失败，无需真实模型密钥或浏览器。

默认 P1 装配仅开放 `/readyz`，配置见 [P1 实现说明](docs/implementation-p1.md)。启用 `jimu.durable.enabled=true` 后，control 开放持久任务受理/查询/取消、SSE 回放和授权产物下载；worker 运行唯一 ToolCallingAdvisor 循环和真实插件网关。P3 真实模型与公开网页报告已完成验收交付；真实 `web_search` provider 仍未配置，第三方插件和生产隔离未验收。P4 增加原子 edit 与可选 Linux 原生 glob/grep，当前独立分支待同 SHA 审查、QA 和业务验收；缺 native bundle 时默认不广告、不执行搜索。

按 2026-10-21 成员决定，不运行、启用、重跑或等待 GitHub CI。必要本地 verify、准确源码的独立审查及业务验收继续；历史 CI 取消、失败与未完成记录保持原事实。本批使用定向 P4 验证入口，不重跑旧 P2/P3 全矩阵或付费评测。

机制与基础工具参考 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 固定提交 `571902028a75c64c1d767f4ae5fd099485f0ddf0`。P4 字面编辑、搜索规则及 fixture 的移植保留其 MIT 许可；原生传递许可见 [bundle 说明](workers/search/README.md)。积木独立于女娲 AI，不复用其代码、工具、配置、数据或服务。
