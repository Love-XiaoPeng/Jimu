# 积木 P1 实施说明

日期：2026-10-15。适用 XIAO-34；三份架构师 accepted ADR 的实现索引，不代表运行验证通过。

| 决定 | 权威文档 |
| --- | --- |
| 命名与插件/Schema映射 | [ADR-0001](adr/0001-jimu-naming.md) |
| 候选依赖与兼容/恢复证据 | [ADR-0002](adr/0002-dependency-baseline.md) |
| 单制品包边界与执行角色 | [ADR-0003](adr/0003-package-boundaries.md) |

原始方案和工程规范保持原字节，其中 `io.taotie` 示例在新代码中映射为 `io.github.lovexiaopeng.jimu`，Maven groupId 同根包，artifactId 为 `jimu`。首期规划插件 ID 为 `jimu.web.fetch`、`jimu.web.search`、`jimu.workspace.read`、`jimu.workspace.write`、`jimu.artifact.present`；模型调用名仍为 web_fetch/web_search/read/write/present。P1 仅实现必要契约和样例，不能将首期规划表当成五个业务工具已完成。

`jimu.web.fetch@0.2.0` 显式绑定 [原权威输出 Schema](baseline/XIAO-33-web-fetch-output-v0.2.0.schema.json)，保留 `urn:taotie:plugin:web-fetch:0.2.0:output`、原摘要和四字段定义；不新增第二套品牌 Schema。发现已发布旧身份时保留它并由架构补充兼容决定，不能直接覆盖。

依赖候选为 JDK21 / Maven3.9.9 / Boot4.1.1 / Spring AI2.0.1，一条工具循环。单 Maven 制品采用真实职责包，通过 ArchUnit 约束；控制和 worker 为独立进程角色。实际兼容性、依赖收敛和当前阶段必需的真实集成在骨架验收前补证；恢复与网页业务分别按 P2/E1、P3 验收。

三份 ADR 来自附件 `01a13dea-6390-784e-8cab-52e565d627b2`，保持原字节，来源摘要见 `adr/sources.json`。原交接索引和 verification.txt 是架构师当时的静态记录；当前创建通道诊断见根目录 VERIFICATION.md。

## 2026-10-23 全仓重构实施依据

XIAO-55 / PR #20 已由产品经理验收合并；XIAO-58重构基线为master `620d5e0e5bfe7b5da530f10997936466159ba34f` / tree `ef29f391b8355a7d693a48cefdcc379e35c32929`。ADR-0014及实现说明中当时“待验收”的文字是历史阶段状态，当前合并/限制以本次核定基线及事项验收回执为准，不重写历史快照。

[XIAO-59全仓方案](refactoring/XIAO-58/README.md)、[工程规范补充](engineering.md)、[ADR-0015](adr/0015-business-services-and-mybatis-plus.md)记录业务Service接口/impl、Controller职责、35表MP真实迁移、业务值治理、分批/回退与必要验证。XIAO-58产品经理已于2026-10-23核定并激活XIAO-60，实施范围见其正文；当前B0/B1为过渡检查点，不能当作重构已完成。T48及其他新功能继续暂停，既有不运行或等待GitHub CI的决定保持。
