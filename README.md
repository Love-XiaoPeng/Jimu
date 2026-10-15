# 积木 Jimu

万物皆插件，像搭积木一样通过插件组合能力。

积木是以 Java 从零建设的云端 AI 执行层底座。稳定内核承载身份、授权、任务状态、调度、预算和事件契约；模型、工具、技能等能力通过插件接入。现名为“积木”，历史方案名称为“饕餮”。

本仓库承接 XIAO-34 / P1，已纳入 XIAO-33 文档阶段验收的方案、工程规范、工具迁移清单、权威输出 Schema 和独立复核报告，详见 [来源索引](docs/baseline/README.md)。

- [工程规范 0.2](docs/baseline/XIAO-33-工程规范-v0.2.md)
- [总体方案 0.3](docs/baseline/XIAO-33-可行性与总体方案-v0.3.md)
- [积木实施决定与命名映射](docs/architecture.md)
- [三份架构 ADR](docs/adr/README.md)
- [P2 持久执行决定](docs/adr/0004-p2-durable-execution.md)与[架构交接/资源证据](docs/verification/XIAO-36/architecture-handoff.md)
- [协作约定](CONTRIBUTING.md)
- [Agent 约定](AGENTS.md)

P1 采用单 Maven 制品与模块化包边界，控制/API 和独立 Java worker 为不同启动角色。三份 accepted ADR 已明确根包/groupId 为 `io.github.lovexiaopeng.jimu`、artifactId 为 `jimu`；JDK 21 / Maven 3.9.9 / Boot 4.1.1 / Spring AI 2.0.1 已取得本地骨架构建证据，等待独立复核。旧文档示例按 ADR 映射，原始来源保持不变。

已提供 Java 21 单制品骨架、固定 Maven 3.9.9 Wrapper、内置插件样例及自动门禁。完整命令为 `./mvnw -B -ntp verify`（Windows：`.\mvnw.cmd -B -ntp verify`），会编译、检查契约与包边界、打包并启动真正独立的 worker/control 进程完成集成验证，缺必需资源会失败。安装 JDK 21 并设置 `JAVA_HOME` 即可；测试使用临时本机 HTTP fixture，无需模型密钥或浏览器。

当前运行入口仅为 `/readyz`。启动角色、必需配置、依赖版本、验证范围与限制见 [P1 实现说明](docs/implementation-p1.md)，实际结果见 [验证记录](VERIFICATION.md)。样例未开放工具调用 API；任务持久化、租约恢复、模型工具循环、网页业务、任意第三方插件与运行隔离尚未实现，不能据此宣称 P2/P3 或生产安全通过。

机制与基础工具参考 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 固定提交 `571902028a75c64c1d767f4ae5fd099485f0ddf0`，源码与许可对照见基线。本包不含其源代码，未来迁移须保留适用许可。积木独立于女娲 AI，不复用其代码、工具、配置、数据或服务。
