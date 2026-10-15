# ADR-0001：积木工程命名与插件契约映射

日期：2026-10-15。状态：accepted（架构决定，待后端统一入库）；范围：XIAO-34/P1。运行验证状态：未执行。

## 问题与依据

项目现名为积木（Jimu）。已验收主方案0.3、工程规范0.2的 `io.taotie` 明确是待项目创建时统一的示例；`taotie.web.fetch@0.2.0` 是未部署的设计样例。事项要求保留历史附件、固定上游来源与已发布契约。不能把工程更名变成修改 Harness 证据或重新定义输出 Schema。

## 决定

| 对象 | 积木采用值 / 映射 |
| --- | --- |
| 项目与工程展示名 | 积木 / Jimu；README 说明“原名饕餮”，沿用现有项目和仓库 |
| GitHub 目标 | `Love-XiaoPeng/Jimu`；目标分支 `master`，由后端核实远端后交付 |
| Java 根包、Maven groupId | `io.github.lovexiaopeng.jimu`；命名依据为当前 GitHub 所有者，去除连字符并小写，不假定持有 `jimu.io` 域名 |
| Maven artifactId | `jimu`；P1 仅一个应用制品，不提前发布插件 SDK |
| 新工程内原示例 `io.taotie.*` | 对应 `io.github.lovexiaopeng.jimu.*`，职责子包不变；测试、扫描与 ArchUnit 同步采用新根包 |
| 旧示例 `errorContract: taotie-invocation-result` | 新 manifest 使用 `jimu-invocation-result`，仅映射名称，沿用主方案§7与工程规范§6的字段、错误码和失败语义 |
| 上游证据 | Harness SHA、npm 包名、源码路径、工具名、原始 Schema 与许可保持原值 |

本轮确定首个业务纵切的名称如下。表中首期规划不表示 P1 已实现这些业务工具；P1 先实现必要契约与样例。

| 清单项 / 能力调用名 | 旧设计插件 ID | 积木插件 ID | Java 实现包后缀 |
| --- | --- | --- | --- |
| T71 / `web_fetch` | `taotie.web.fetch` | `jimu.web.fetch`，样例版本 `0.2.0` | `plugins.builtin.webfetch` |
| T72 / `web_search` | 基线未固定，本次确定 | `jimu.web.search` | `plugins.builtin.websearch` |
| T23 / `read` | `taotie.workspace.read` | `jimu.workspace.read`，已有设计版本 `0.1.0` | `plugins.builtin.filesystem` |
| T25 / `write` | 基线未固定，本次确定 | `jimu.workspace.write` | `plugins.builtin.filesystem` |
| T15 / `present` | 基线未固定，本次确定 | `jimu.artifact.present` | `plugins.builtin.present` |

其余首次实现的积木自有插件用 `jimu.<职责域>.<能力>`，不批量预建72个插件。插件是安装和版本单元，可以包含多项能力；Java 代码共包不代表共用安装 ID。后续有真实合包需求时明确安装、授权和版本影响，不把上游每个注册项机械变成独立制品。

模型可见能力名继续使用 `web_fetch`、`web_search`、`read`、`write`、`present`；不随品牌添加 `jimu_` 前缀，也不改上游参数线格式。registry 对 `(pluginId, version)` 重复、同一任务发现集合内的重复能力名及摘要不匹配均拒绝，不静默覆盖；合法不同版本可并存，在途任务固定版本/摘要。

## 权威 Schema 的特别映射

`jimu.web.fetch@0.2.0` 显式绑定原权威输出 Schema：

- 来源附件：`01a13b9c-28f6-71b3-99cc-39c899b5ba4d`。
- `$id`：`urn:taotie:plugin:web-fetch:0.2.0:output`，保留原值。
- SHA256：`08a144489ed9ea37b5b33a5ffdc815237f31d4f229b76ba1d5e465b9e13f9ff8`。
- 必需字段：`httpStatus`、`sourceUrl`、`content`、`truncated`；`additionalProperties: false`，dialect 为 JSON Schema 2020-12。

该绑定是本 ADR 明确的品牌映射，不通过替换字符串伪造一份 `urn:jimu:*` Schema。原文件含旧名称的 `$comment` 也不改。打包 `output.schema.json` 必须是该文件的字节一致派生副本，绑定表引用相同 `$id`/摘要；禁止另写一份近似输出定义或将 `$id` 前缀与新插件 ID 的字符串相等作为准入条件。插件与 Schema 的关联应来自经过校验的 manifest/显式绑定，而不是品牌字符串推导。

新 manifest 的实现入口示例为 `io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin`；仍以 `plugin.yaml` 为维护源、JSON 为派生视图，Schema 使用包内相对引用。入口判别、hostApi 范围与四字段输出语义不因本次映射改变。

## 兼容、备选与影响

采用新工程命名加明确契约映射，避免新代码长期混用两个根包，同时保留可核验的原始依据。保留全部 `taotie.*` 作新工程名会继续混淆品牌；全局替换历史资料和 Schema 会破坏摘要及来源链，因此不采用。

基线明确没有已部署旧插件，本次不实现永久双 ID 运行别名、不预建数据库更名迁移。若后端核实仓库或运行环境已有发布的旧 ID、坐标、调用方或持久引用，应先保留该契约与数据，回传具体证据，由架构补充版本/映射与回退决定；不能拿本 ADR 重写已发布身份。已发布破坏兼容变化须新版本及迁移对照。

原始附件与清单逐字保留；新工程规范通过版本化“积木命名补充/ADR引用”说明旧示例的适用映射，不修改来源附件冒充原版本。已有他人代码也须先核实再调整。

## 验证与交接

本轮已核对原 Schema 字段、清单T15/T23/T25/T71/T72及文件摘要，未运行 Java。后端在 P1 证明：新根包的真实类被架构规则扫描；manifest/descriptor/绑定一致；原输出副本摘要相同；同一 fixture 的真实 validator 与 DTO 序列化一致；缺字段、额外字段、错类型及重复 ID/能力名被拒绝。Schema 的旧 `$id` 是本 ADR 指定的合法例外，不能绕过其余准入校验。
