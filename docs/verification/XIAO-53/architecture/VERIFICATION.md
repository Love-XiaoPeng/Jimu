# 架构验证记录

2026-10-23；Windows PowerShell / Node v24.21.0 / Temurin JDK21.0.12.1。验证仅针对设计证据和契约形状；无产品JAR、数据库升级或运行验收结论。

| 实际命令 | 退出码 | 结果及范围 |
| --- | --- | --- |
| `git fetch origin master` / `git rev-parse HEAD 'HEAD^{tree}' origin/master` | 0 | HEAD/master均1cce67035753731989633cb4420e7d661b894321，tree9f93ddfebaa67e001ec8b77c8a47d4d3cd27be03；初始worktree干净 |
| `node docs/verification/XIAO-53/architecture/capture-evidence.cjs` | 0 | 重新下载43个固定上游blob，与完整Git树核Git blob/摘要；旧41份证据相符；53份Jimu基线源码/原Schema/迁移摘要 |
| `node docs/verification/XIAO-53/architecture/make-contracts.cjs` | 0 | 两工具manifest/input/output/description、版本Schema、catalog/binding和合成示例生成 |
| `node docs/verification/XIAO-53/architecture/verify-artifacts.cjs` | 初次1、修正后0 | 初次source-map定位模式写成function page(，实际为泛型page<；只修正索引生成器，保留本记录。最终43个上游/53个基线字节、2工具pin、catalog/binding、read输入原字节、638byte输出示例与完整事件摘要核验通过 |
| `./docs/verification/XIAO-53/architecture/verify.ps1` | 0 | 内含离线Maven runtime classpath解析、javac编译本探针及生产SchemaContract、Java运行；58项Schema/严格JSON解析形状/旧v1拒绝v2及旧例继续有效；原输出在evidence |
| `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --cached --check` | 初次2、修正后0 | source-map生成器多写一个EOF空行；修正生成器并重生成，不改原上游字节 |
| `node docs/verification/XIAO-53/architecture/package.cjs --verify` | 0 | 142份交接文件逐项字节数/SHA256通过，包含必要旧证据、MIT许可和基线源码 |

Schema探针明确不证明public-content-literal-v1匹配/排序、精确时间语义、运行时surfaces拒绝、计账或鉴权实现。合法surfaces在wire Schema中保留，运行时拒绝是后端义务；不以Schema接受形状宣称产品支持surface。未执行上游TS测试，读取其断言仅作事实证据。

执行中 `gh auth status` 退出1（本机没有gh命令），随后使用本地Git credential helper已授权的GitHub API读取验证当前账号Love-XiaoPeng、仓库master和push权限；凭据只在内存，无秘密写入文件或输出。首个PowerShell未加引号的 `HEAD^{tree}` 被shell改写而失败，随后使用引号准确回读；没有改动源码。

交付前复核统一了search示例命中与后续read示例的同一seq0正文，并加入逐对象一致性断言；重新生成后原58项Schema探针、摘要及包检查通过。owner POST的2秒截止明确包含授权/body读取/解析，DB继承剩余预算。两次普通Git push分别遇连接重置/443连接失败，改用已授权GitHub Git Database API传送Git对象，核远端tree/commit与本地完全一致后才建分支/草稿PR；没有改动全局网络、代理或daemon设置。

未执行产品构建/JAR、私有MySQL升级/索引计划、真实control/双worker/paid=false模型、匹配与过滤边界、授权/撤权/提交/恢复竞争、容量/超时、保留清理及业务回归。S01–S12为后端自测和原独审/原QA后续完成条件，不记为通过。原XIAO-52 R1/QA-52-01已关闭事实来自同SHA独审/QA与产品验收，非本轮重新运行。

GitHub CI未运行、启用、恢复、重跑或等待。旧Windows verify 262项1failure/15errors、ADR README冻结长度3446/实际3819及历史POSIX失败保持原事实；不重跑旧完整矩阵，不修改基线追认。无生产、真实Key、付费调用、部署、共享服务/daemon/代理/FRP或外部发送操作。
