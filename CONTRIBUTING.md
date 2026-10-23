# 参与积木开发

以 [工程规范 0.2](docs/baseline/XIAO-33-工程规范-v0.2.md) 为规则基线，来源及适用范围见 [基线索引](docs/baseline/README.md)。骨架实际启用范围和资源约定见 [P1 实现说明](docs/implementation-p1.md)。

XIAO-58整体重构的 [工程规范补充](docs/engineering.md)、[架构方案](docs/refactoring/XIAO-58/README.md) 和 [ADR-0015](docs/adr/0015-business-services-and-mybatis-plus.md) 当前为待核定提案。核定后按其实际业务Service/impl、Controller边界、MP及验证矩阵实施；原始规范/来源不改写。方案交付不表示代码迁移或业务验收通过。

## 实施顺序

1. 核实 `Love-XiaoPeng/Jimu` 的远端、已有内容和权限；保留历史、可见性及他人改动。
2. 在独立任务分支提交已验收方案、规范、Schema 与协作规则，尽早开可审阅 PR，base 为 `master`。
3. 将已收到的三份 [架构 ADR](docs/adr/README.md) 统一入库，按其包名/插件命名映射、候选依赖及包边界实现骨架。
4. 构建单 Maven 制品与两个启动角色，只建立真实职责需要的包；提供依赖收敛、最小契约/插件、Schema/DTO 与 readiness 证据。
5. 本地运行必要 verify，演示违规依赖、格式、重复插件 ID/坏 Schema、缺必需资源的负向失败，修复后复验，再交独立审查和测试。按 2026-10-21 成员决定，不运行、启用、重跑或等待 GitHub CI；保留其历史真实记录。

## 构建与来源

骨架实现后使用固定 Maven Wrapper 和 JDK 21，候选 Boot/AI 版本以实际构建、effective POM 和 dependency tree 证明兼容；不混用两条 BOM 或编排链。

Linux 本地执行 `./mvnw -B -ntp verify`，Windows 执行 `.\mvnw.cmd -B -ntp verify`。门禁绑定格式、静态、包依赖、单元/契约、打包及当前阶段必需的真实 worker 集成；缺必需资源失败。按本批风险选择受影响测试及集成入口，P4 命令见运行说明，不因停用 CI 重跑旧完整矩阵。修改 Java 后先运行 `./mvnw -B -ntp spotless:apply` 修复格式。不得用跳过测试/静态检查的快速命令替代验收。

可选的负向证明命令为 `node tools/verify-negative-gates.mjs`（Node 22+），逐个在 `target/negative-gates/` 的独立副本中注入违规并调用完整 verify，保存原始日志与退出码；不修改工作树。Node 不是应用或默认 verify 的运行依赖。单项复验可在命令后传入用例名。

提交后本地执行 `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff origin/master...HEAD --check`。先 fetch 确保基线最新；检查尚未提交的内容时把比较范围换为 `--cached`。规则显式允许原始 CRLF，但继续拒绝行尾空格、尾部空行和 tab 前空格；`.gitattributes` 固定相同规则，来源目录 `-text` 继续保留原字节。`node tools/verify-whitespace.mjs` 在隔离 Git 副本中验证原始 CRLF 退出 0、注入行尾空格退出 2，证据写入 `target/whitespace-evidence/results.json`。

CRLF 脚本在系统临时目录创建唯一的 `jimu-whitespace-*` Git 副本，成功或异常均在 `finally` 核实路径并删除本次副本；清理失败会使脚本非零退出。只清理当前调用创建的目录，不扫描或删除历史副本。Windows 上 Git 对象可能只读，因此不再把临时仓库留给 Maven clean。`results.json` 保留正反例、异常及清理结果；下一次 clean 会删除 target 下的证据，需要保留时先复制到工程外。可按 `mvnw.cmd -B -ntp clean verify` → `node tools/verify-whitespace.mjs` → `mvnw.cmd -B -ntp clean verify` 检查生命周期。

Manifest 只维护 plugin.yaml，JSON 是派生视图。输出 Schema 保持一份权威源，打包副本须核验摘要。历史 taotie 标识、Schema URN 和上游名称原样保存，映射由架构决定。迁移先固定 SHA、核对许可及行为，再实现和验证；已发布的破坏兼容变更须新版本及迁移说明。

## 交付

分支围绕单个事项，保留用户 Git 身份，提交说明具体变化；PR 标题包含事项编号，说明问题、最终变化、固定提交和实际验证/限制。不强推或擅自合并、部署；文档验收不表示产品或运行验收通过。
