# XIAO-51 架构交付包

入口：`implementation-contract.md`，必要决定：`ADR-0010-task-logical-directory.md`，可执行验收：契约§7/D01–D12，数据结构：`migration-contract.sql`，工具与owner API：`contracts/`。

基线源码 `5d90fab841559e7349701c8284642525bc00da9d`，tree `e6bbcac442abdc6d279e422dd76894c242485ab5`；固定上游 `571902028a75c64c1d767f4ae5fd099485f0ddf0`。实际来源、许可、摘要与行号见 `evidence/{jimu-sources,upstream-sources,frozen-migrations}.json`、`evidence/source-map.md` 和 THIRD_PARTY_NOTICES.md。

最小范围为默认关闭、本地显式paid=false的4能力profile：working_directory/read/write/edit。cwd在DB同事务提交，路径在原槽实际执行时冻结；目录只表达现存文件祖先及根。空目录、cwd搜索和其他profile混用明确不在首批。旧profile不重新解释，目录额度独立。详见契约，不能仅依本页实现。

本包是架构文档与验证材料，供产品经理核对后交原后端唯一实施；没有更改 `src/`、运行迁移、创建产品PR、合并或部署。正文包交付在XIAO-51评论附件，产品代码PR由后端接续创建，base master。Git HTTPS直连本次失败，GitHub公共API已另行确认同一master；本地提交另因未配置Git作者失败，没有猜测或改写Git身份，材料尚未提交/推送。两项失败证据保留，不阻塞后端从附件入库实施。

复验架构材料（在同一基线仓库根目录，Node22+/JDK21/已缓存Maven运行依赖）：

```powershell
node docs/verification/XIAO-51/architecture/verify-package.cjs
& ./docs/verification/XIAO-51/architecture/verify.ps1
```

`make-contracts.cjs` 是维护源，可重新生成Schema、唯一manifest和catalog；`fetch-upstream.cjs` 只读下载固定上游并验证Git blob。重生成/重跑可能改变日志及依赖身份，不能覆盖本次证据后仍引用原包摘要。重新记录包须使用 `verify-package.cjs --record` 并重新计算外层ZIP摘要。

实际结果与未验证项见 VERIFICATION.md。架构探针不等于产品验证；不运行、恢复、重跑或等待GitHub CI。原冻结失败与未知账不改，当前仅通过任务评论/附件交付，没有飞书外发。
