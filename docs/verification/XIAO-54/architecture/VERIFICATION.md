# 本轮架构核验

日期2026-10-23。产品实现未开始，本报告不代替独审/QA/业务验收。

| 命令/动作 | 实际结果 |
| --- | --- |
| multica repo checkout，检查branch/status | 新独立分支agent/agent/7c01ad9ef205，工作树原无改动；初始缓存为XIAO-52合并点 |
| git fetch origin master / git ls-remote固定上游 | 均连接github.com:443失败，退出1；未修改代理/共享服务 |
| GitHub API读取actual master ref与commit | 200，master为3e5f77549562691634c0a166d8b944ecdcdf1bb8 / tree93d353f8efac07c34676bc3bffb1790e048fc75d；父提交1cce670和2ba85d5 |
| API原签名payload重构Git commit→git hash-object→ff-only | 重构字节的Git SHA与master完全一致；本地已有tree及两父对象；快进成功，退出0；没有伪造/重写历史 |
| node .../capture-evidence.cjs | 退出0：重新下载并核48个固定上游blob/SHA256，核80个基线产品文件；旧43份引用原字节，仅新增5份本批原码/测试 |
| node .../make-contracts.cjs | 退出0：完整v3候选资源，仅写本包，不改产品资源 |
| ./docs/verification/XIAO-54/architecture/verify.ps1 | 退出0：离线Maven runtime classpath、JDK21编译项目实际SchemaContract并执行；52项Schema/输入/示例正反例通过 |
| node .../verify-artifacts.cjs | 校验catalog/manifest/description/Schema/binding全部pin、read/search输入字节复用、48原blob及80产品基线；输出以实际命令日志为准 |
| node .../package.cjs --verify | 逐文件摘要核验；具体文件数见PACKAGE-MANIFEST和实际输出 |

输入probe含1024/1025字节、safe integer、-0/浮点/指数词法、重复key/未知字段/trailing、非法UTF-8/surrogate、v3示例、来源容量/重复、禁止临时输出字段。它验证候选Schema和边界规则，不运行trace provider，不证明排序、关系捕获、授权事务或迁移正确。

过程中修正：PowerShell未引用带点的Maven-D参数导致一次命令解析失败，引用后成功；最初候选binding仍带旧catalog摘要，JVM示例失败，生成器改为绑定本次catalog后52项通过。没有把这些初始失败写成产品缺陷或隐藏为全程成功。

尚未执行：产品构建/本地完整verify、上游TS测试、V20→V21真实MySQL迁移及旧制品拒绝、真实control/双worker/HTTP模型闭环、独审/QA、授权/撤权竞争/故障/保留清理的T01–T11。没有运行或等待GitHub CI，未部署/使用真实Key/付费模型/更改共享服务或代理；无外部通知。历史Windows verify262项1failure/15errors、ADR索引3446/3819及旧POSIX失败保留。

架构结论：不存在可忠实移植的当前surface/替换关系事实；read-receipt-sources-v1是明确的Jimu最小候选，需要产品经理裁定后才实施。无后端或子agent被本轮启动。
