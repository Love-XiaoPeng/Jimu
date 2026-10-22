# XIAO-34 积木 P1 架构交接

日期：2026-10-15；交付岗位：架构师；接收实现岗位：后端开发（唯一仓库写入者）。

本附件交付三项可入库的ADR结论，解除“等待命名/依赖/包边界结论”的前置；不代表GitHub访问恢复、代码已实现或P1通过。后端可先完成首批文档与骨架，按ADR取得实际依赖/门禁证据后再声明验证通过。

建议由后端将三份ADR纳入 `docs/adr/`，索引到新的积木工程规范；若仓库已占用该编号则顺延编号并同步索引，保留内容与来源。没有检出或修改目标仓库，没有PR/固定Git提交。本交付不修改原始附件、固定上游证据或任何已发布契约。

| 文件 | 结论 |
| --- | --- |
| `0001-jimu-naming.md` | 根包/groupId为 `io.github.lovexiaopeng.jimu`，artifactId为 `jimu`；首期插件用jimu命名，模型调用名不变；显式绑定原权威输出Schema，不改其旧URN/摘要 |
| `0002-dependency-baseline.md` | 主选候选JDK21/Maven3.9.9/Boot4.1.1/AI2.0.1；一个AI运行链，P1兼容证据与P2/E1恢复证据分别收集；按证据决定是否整套切换Alibaba备选 |
| `0003-package-boundaries.md` | 单制品、真实职责包与ArchUnit、控制/worker独立角色；契约/内核/适配器/插件/装配分离，不预建空模块 |

## 引用的已验收基线

输入均通过认证的 `multica attachment download` 取得。以下SHA256为本轮实际计算；五份核心输入与修订对照已公布摘要逐项一致。

| 基线 | 附件ID | 本轮SHA256 |
| --- | --- | --- |
| 主方案0.3 | `01a13b9c-25a1-78ae-9e88-e625e1927f8f` | `2b8e53c700aab0d96a6f06351ee9f924a43c8d5af4b5f62837b20e6f92e6c014` |
| 工程规范0.2 | `01a13b9c-25ca-71f6-a51c-fe43d521b5c3` | `36553f58e0b129903f04389c852f75560cf4879bbe9fbdac0e2759cb3f8298b7` |
| 迁移契约稿1.1 | `01a13b9c-25ee-7928-942d-683f58caf52d` | `2111bc4a0be86a3cc69420fb9b9d08adc0bc5b609686d859b38830edce17c0f7` |
| 工具清单1.1 | `01a13b9c-26fd-7c0c-b526-07c7d115a82d` | `30b6904aad8d85dac3e789434e434c449575383cb23e254329a84930436f5b02` |
| web.fetch输出Schema0.2.0 | `01a13b9c-28f6-71b3-99cc-39c899b5ba4d` | `08a144489ed9ea37b5b33a5ffdc815237f31d4f229b76ba1d5e465b9e13f9ff8` |
| 修订对照0.3 | `01a13b9c-2a15-7dc4-b593-6cbd365ec2cf` | `ca708c3693ffd385cbedc5b5bb4a619c5bbd72e3a1e39031bc4f528974278ea5` |

采纳的是产品已接受并经独立文档复核的主方案§3/§4/§5/§9、工程规范§1–§3/§5/§7–§9、迁移稿§6及清单T15/T23/T25/T71/T72。旧0.1草稿和冲突输出不重新成为可安装契约。

## 验证范围与剩余工作

本轮检查原始输入摘要、JSON字段与首期工具身份，并对ADR文件做静态交付核验；详细实际结果见 `verification.txt`。未进行Java编译、依赖解析、真实validator/DTO测试、worker/DB/存储集成、恢复、安全或性能实验；未改共享网络/FRP/部署，也未调用真实收费模型/搜索。

远端连通性及权限仍是独立前置。后端已回传WSL执行身份拒绝及GitHub连接失败的只读证据，产品经理已升级最小环境动作，事项当前仍为blocked；本交付只解除ADR前置。取得仓库后先核实原内容和已发布状态，若与“旧命名尚未部署”的输入不符，再由架构补充兼容决定。整个P1仍需后端PR/固定提交、实际门禁、独立审查与测试以及产品验收。

## 2026-10-22 implementation index

- [ADR-0004: durable execution](0004-p2-durable-execution.md)
- [ADR-0005: atomic edit and native search](0005-workspace-edit-native-search.md)
- [ADR-0006: task image inputs and model media](0006-task-image-input.md)

The dated handoff above remains historical; current P4 configuration and evidence are in ../implementation-p4-runtime.md.
