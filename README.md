# 积木 Jimu

万物皆插件，像搭积木一样通过插件组合能力。

积木是以 Java 从零建设的云端 AI 执行层底座。稳定内核承载身份、授权、任务状态、调度、预算和事件契约；模型、工具、技能等能力通过插件接入。现名为“积木”，历史方案名称为“饕餮”。

本仓库承接 XIAO-34 / P1，已纳入 XIAO-33 文档阶段验收的方案、工程规范、工具迁移清单、权威输出 Schema 和独立复核报告，详见 [来源索引](docs/baseline/README.md)。

- [工程规范 0.2](docs/baseline/XIAO-33-工程规范-v0.2.md)
- [总体方案 0.3](docs/baseline/XIAO-33-可行性与总体方案-v0.3.md)
- [积木实施决定与命名映射](docs/architecture.md)
- [三份架构 ADR](docs/adr/README.md)
- [协作约定](CONTRIBUTING.md)
- [Agent 约定](AGENTS.md)

P1 采用单 Maven 制品与模块化包边界，控制/API 和独立 Java worker 为不同启动角色。三份 accepted ADR 已明确根包/groupId 为 `io.github.lovexiaopeng.jimu`、artifactId 为 `jimu`；JDK 21 / Maven 3.9.9 / Boot 4.1.1 / Spring AI 2.0.1 是待实际构建验证的候选。旧文档示例按 ADR 映射，原始来源保持不变。

当前没有 Java 骨架、Maven Wrapper、CI 或运行服务，没有构建、worker、恢复、隔离、性能或网页业务通过结论。骨架实现后完整受理命令为 `./mvnw -B -ntp verify`（Windows：`.\mvnw.cmd -B -ntp verify`）；缺当前阶段必需资源须失败，快速检查不能代替完整门禁。

机制与基础工具参考 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 固定提交 `571902028a75c64c1d767f4ae5fd099485f0ddf0`，源码与许可对照见基线。本包不含其源代码，未来迁移须保留适用许可。积木独立于女娲 AI，不复用其代码、工具、配置、数据或服务。
