# 架构候选验证记录

2026-10-23；本记录只覆盖契约候选，产品实现/实际V22迁移/真实模型闭环/原独审与QA未执行。ADR-0014仍proposed，业务差异须先由产品经理裁定。

| 实际命令 | 退出码与结果 |
| --- | --- |
| `multica repo checkout https://github.com/Love-XiaoPeng/Jimu.git` | 0；新任务分支初始为旧master `3e5f775...`，不是当前交付基线 |
| `git -c http.connectTimeout=10 -c http.lowSpeedLimit=1 -c http.lowSpeedTime=20 fetch origin master` | 1；连接github.com:443约21秒失败；没有伪记fetch成功 |
| 本项目上一已验收checkout只读导入 `b31bcd8763de14c542a4ab2d829f54552271e6aa` Git对象；GitHub API取得signed merge payload并重建commit | 0；准确重建 `d6d2d28a8f4fb3c2f05ed2bd410b1d5f65a16fe1` 后ff-only到本任务分支，tree=`af0e11992efdc00d3e8bc6d656d9aeda30159a90`；无共享Git配置/他人工作树修改 |
| `node docs/verification/XIAO-55/architecture/capture-evidence.cjs` | 0；48个固定上游源码/测试/MIT逐文件重新下载，Git blob/bytes/SHA256和历史捕获一致；119个本地基线文件记录摘要；GitHub actual master/tree与本地相同 |
| `node docs/verification/XIAO-55/architecture/make-contracts.cjs` | 0；生成候选manifest、完整输入/输出Schema、catalog、v4 binding/event/context/owner、宿主受理/authority、HTTP catalog和例子；无产品注册 |
| `& ./docs/verification/XIAO-55/architecture/verify.ps1` | 0；Maven离线runtime classpath、JDK21编译项目实际SchemaContract及候选probe；69项检查通过，详细清单在evidence/schema-cases.json |
| `node docs/verification/XIAO-55/architecture/verify-artifacts.cjs` | 0；catalog/binding pins、read输入旧字节、全部固定来源/119基线、scope/context/result摘要、每会话一代表/当前排除样例及容量一致性通过 |

69项是候选Schema/严格JSON词法与UTF8探针，包含合法Unicode/最大safe integer、重复key/未知字段/无效UTF8/surrogate/-0/浮点/指数/4096–4097字节、query256–257码点、例子、输出缺口/旧版本拒绝及跨会话read refs。unsupported字段的合法语法接受仅用于Schema层验证，**不代表产品支持过滤**；宿主语义拒绝、日期/反向范围/授权/事务/模型/provider行为仍由后端实施和C01–C11验证。

容量样例：8目标的完整authority为3070字节（上限4096），9条UUID+seq+digest引用1297字节（v4新上限2048）。这些是序列化形状核验，不能冒充端到端容量或SQL执行证据。旧v3来源快照1024字节不改。默认租户64MiB同时承载独立source与caller的各8MiB预留，实际可接纳数仍受租户总容量限制；8来源加caller的完整边界运行需私有夹具显式较大配额或验证真实429，不调整共享/生产配额。

未运行TypeScript上游测试、MySQL V22、产品单元/集成矩阵、双worker/model业务闭环、容量/授权竞争、清理/迁移、DNS/TLS或事务内崩溃；没有新JAR，不将架构检查记为独审/QA或业务验收。后端完成后按C01–C11锁同一完整源码/tree/JAR验证。未运行/启用/恢复/重跑/等待GitHub CI；旧Windows verify262项1failure/15errors、ADR索引冻结3446/实际3819、POSIX失败继续保留。无部署、生产、真实Key、付费、共享服务/daemon/代理/FRP变更；外部汇报未发送。
