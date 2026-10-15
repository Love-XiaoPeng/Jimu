# 参与积木开发

以 [工程规范 0.2](docs/baseline/XIAO-33-工程规范-v0.2.md) 为规则基线，来源及适用范围见 [基线索引](docs/baseline/README.md)。当前首批提交为文档基线，运行能力以实际验证记录为准。

## 实施顺序

1. 核实 `Love-XiaoPeng/Jimu` 的远端、已有内容和权限；保留历史、可见性及他人改动。
2. 在独立任务分支提交已验收方案、规范、Schema 与协作规则，尽早开可审阅 PR，base 为 `master`。
3. 将已收到的三份 [架构 ADR](docs/adr/README.md) 统一入库，按其包名/插件命名映射、候选依赖及包边界实现骨架。
4. 构建单 Maven 制品与两个启动角色，只建立真实职责需要的包；提供依赖收敛、最小契约/插件、Schema/DTO 与 readiness 证据。
5. 本地及 CI 采用同一完整 verify，演示违规依赖、格式、重复插件 ID/坏 Schema、缺必需资源的负向失败，修复后复验，再交独立审查和测试。

## 构建与来源

骨架实现后使用固定 Maven Wrapper 和 JDK 21，候选 Boot/AI 版本以实际构建、effective POM 和 dependency tree 证明兼容；不混用两条 BOM 或编排链。

Linux/CI 执行 `./mvnw -B -ntp verify`，Windows 执行 `.\mvnw.cmd -B -ntp verify`。完整门禁绑定格式、静态、包依赖、单元/契约、打包及当前阶段必需的真实 worker 集成；缺必需资源失败。当前包未提供这些工具，未完成完整验证。

Manifest 只维护 plugin.yaml，JSON 是派生视图。输出 Schema 保持一份权威源，打包副本须核验摘要。历史 taotie 标识、Schema URN 和上游名称原样保存，映射由架构决定。迁移先固定 SHA、核对许可及行为，再实现和验证；已发布的破坏兼容变更须新版本及迁移说明。

## 交付

分支围绕单个事项，保留用户 Git 身份，提交说明具体变化；PR 标题包含事项编号，说明问题、最终变化、固定提交和实际验证/限制。不强推或擅自合并、部署；文档验收不表示产品或运行验收通过。
