# XIAO-53 架构交接

本包冻结G22/T45的可直接实施契约，不含产品实现或运行验收。基线：master `1cce67035753731989633cb4420e7d661b894321` / tree `9f93ddfebaa67e001ec8b77c8a47d4d3cd27be03`。

- `implementation-contract.md`：字段、上游差异、公开投影、匹配/排序/裁剪、原槽事务、owner API、额度/清理、接入点及S01–S12。
- `../../../adr/0012-bounded-session-event-search.md`：唯一新增ADR；原ADR-0011和基线字节不改。
- `contracts/`：两工具完整manifest/input/output/description、history/event/context/owner/binding-v2、catalog、规范示例；这些是新增产品资源的权威来源，后端复制并核pin，不独立重写第二份Schema。
- `migration-contract.sql`：V20设计；扩展合法history/event版本并新增search原槽绑定，升级实际CHECK名及真实旧制品拒绝由后端验证。
- `evidence/source-map.md`：固定上游原码/测试与Jimu接入点行号；`upstream-sources.json`重新核43个blob，旧41份原文件通过相对路径引用，包内一并保留原字节。
- `VERIFICATION.md`、`evidence/`：已执行的架构验证与未执行项；`PACKAGE-MANIFEST.json`是整个交接包逐项摘要，包含必要旧证据与基线源码。

新profile固定组合read@0.2.0/search@0.1.0，当前task授权，public-v1捕获时脱敏，M1前冻结，16条相关度结果，无surface推断/正则/全文索引服务。五类原生日志保持，search自己的结果/收据只在原槽和可靠事件中，不冒充read收据。旧read-only v1和所有旧冻结资源保持。

后端从本分支/草稿PR同一head接续，先交V20+契约装配+v2受理/M1/provider/owner API的可构建切片，随后原槽/读取组合与全部副本清理。由产品经理核实际占用后交原后端唯一实施；稳定源码/JAR交原独审和原QA按S01–S12验证，再由产品经理业务验收。架构设计已定，无需另开团队或子agent。GitHub CI不运行/恢复/重跑/等待。

复核入口（仓库根目录）：

```powershell
node docs/verification/XIAO-53/architecture/verify-artifacts.cjs
./docs/verification/XIAO-53/architecture/verify.ps1
node docs/verification/XIAO-53/architecture/package.cjs --verify
```

生成器仅维护本次架构产物；不能运行它覆盖原src/main/resources/session或XIAO-52证据。package检查要求完整当前仓库或交接ZIP内的同路径源文件，不包含模型凭据、运行数据库或JAR。所有示例为合成数据，不能作为业务验证证据。
