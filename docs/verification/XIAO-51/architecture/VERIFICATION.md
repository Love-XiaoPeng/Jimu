# 本轮实际验证与边界

运行时间2026-10-23（Asia/Shanghai），源码基线 `5d90fab841559e7349701c8284642525bc00da9d` / tree `e6bbcac442abdc6d279e422dd76894c242485ab5`，Java21.0.12；依赖制品名及SHA256见 `evidence/probe-dependencies.json`。本轮未修改产品Java或资源。

| 实际入口 | 退出码 | 结果 |
| --- | --- | --- |
| `multica repo checkout https://github.com/Love-XiaoPeng/Jimu.git` | 0 | 专有checkout基线与任务相同，初始工作树干净 |
| `node docs/verification/XIAO-51/architecture/fetch-upstream.cjs` | 0 | 最终25个固定上游blob逐一匹配原Git tree；原源码/测试/文档/Session快照/许可留存 |
| `node docs/verification/XIAO-51/architecture/make-contracts.cjs` | 0 | 4份Schema、唯一plugin.yaml、catalog、5种owner快照示例及binding、35个Schema正反例；28个路径验收fixture另行手工对照契约，不声称运行了产品解析器 |
| `.\mvnw.cmd -o -B -ntp dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/xiao51-architecture/classpath.txt` | 0 | 仅准备本地离线probe运行classpath，非产品构建或依赖收敛门禁 |
| `& ./docs/verification/XIAO-51/architecture/verify.ps1` | 0 | 使用准确基线production SchemaContract编译4Schema，15正例/20反例通过；候选边界拒绝8个JSON/Unicode错误并接受2正例，严格UTF-8解码拒绝非法编码；实际Spring AI manager按4个交错槽的响应数组顺序执行，原RuntimeException在第2槽抛出后立即终止，不执行后续槽 |
| 脚本内 `capture-evidence.cjs` | 0 | 核对27个已读基线blob；原V1–V17共17个迁移与基线Git blob相同，记录各SHA256 |
| `git ls-remote origin refs/heads/master` | 128 | HTTPS connection reset，原失败见 `evidence/git-transport-failure.txt`；未推送、未开产品PR |
| GitHub公共commit API固定回读master | 0 | 2026-10-22T18:41:44Z仍为相同完整SHA/tree，见 `evidence/master-confirmation.json` |
| `git commit -m 'docs(XIAO-51): define task logical directory contract'` | 128 | checkout未配置作者，Author identity unknown；没有修改/猜测Git身份，无新commit，材料通过附件交付由原后端入库 |

原始probe输出、编译退出结果和Java版本在 evidence/，脚本源可复验。包完整性以 `PACKAGE-MANIFEST.json` 为准，`node .../verify-package.cjs` 校验完整文件集合、SHA256及25个上游Git blob。

限制：Schema probe不是产品严格parser，路径fixture不是解析器实现；manager probe不是完整call/stream模型循环。没有启动Jimu control/worker、MySQL、付费/远程模型、native bundle或常驻服务；没有运行DDL、真实upgrade、授权/撤权、fencing/并发/崩溃、清理或业务纵切。所有这些仍是D01–D12的实施/独审/QA要求。未执行上游Vitest，不引用上游文档内历史测试数作为本轮结果。

本轮不执行默认完整verify，也不声称其通过：任务已指明旧master的SourceIntegrityTest存在ADR README冻结3446字节、实际3819字节的既有失败；本轮保留原记录，没有改冻结原件追认。没有运行、启用、重跑或等待GitHub CI。

本轮无产品进程、DB、下载二进制或外部发送需要清理；target下只留可再生编译/classpath缓存，不是交付依赖。附件含原始文本证据及manifest，不含class、JAR、密钥或runtime私有凭据。
