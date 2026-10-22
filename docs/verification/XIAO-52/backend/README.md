# XIAO-52 后端验证

本目录记录后端实现的本地验证；架构原证据保持原字节。最终准确源码/tree、JAR SHA256、命令/退出码、用例数量及清理结果由本目录的交付记录和事项附件固定。未执行的细项不视为通过，仍交产品经理安排原独审与原 QA 锁同一 SHA/JAR 复核。

## 可重复入口

Windows 构建与新契约/包边界/静态检查：

```powershell
.\mvnw.cmd -B -ntp spotless:apply package spotbugs:check '-Dtest=SessionContractsTest,PackageBoundariesTest'
```

Linux/WSL 使用 Java21、Docker 和固定 MySQL8.0.46 镜像。以下脚本在前台创建私有数据库、本地 paid=false HTTP 模型、真实 control/worker，收集原始协议/账本与 cleanup.json；finally 收集子进程、删除私有容器和临时 ext4 根。模型不使用真实 key 或外部付费服务。

```text
python3 tools/session_event_verify.py --jar <jar> --evidence <directory> --case all
python3 tools/session_event_migration_verify.py --jar <jar> --v18-jar <exact-v18-jar> --evidence <directory>
python3 tools/session_event_regression_verify.py --jar <jar> --evidence <directory>
```

`JIMU_IT_JAVA` 可指向 Linux Java21。迁移入口要求从 `270a5d15611f5d7e59bbbd1d96c94d07567fc858` 源码构建的真实 V18 制品，不能用新制品伪造旧 schema。所有实验按 JAR hash 固定；失败尝试保留，不覆盖为成功。

## 覆盖对应

| 合同 | 本地测试入口与断言 |
| --- | --- |
| H01–H02 | normal：双 worker、三次真实 session 工具调用、早先完整 model.response、context 真实引用、窗口裁剪、收据不嵌套、终态、owner/跨主体、control 重启、无 artifact；读取不增加消费/事件 |
| H03–H04 | recovery：M1/M2/读取提交前后 SIGKILL，真实新 worker 接管，同上界/digest/字节/一次工具及收据；JDBC commit 前回滚与 commit 后丢确认两侧注入；同响应多槽看不到前槽新收据 |
| H05–H06 | replay helper：变更 target 后仍回原字节、fence/actor/pin、actor/installation 撤权、flag off、通用写入口拒绝；authorization：M2 后取消/截止/flag off 保留 usage、无工具消费；owner终态可读 |
| H07 | SessionContractsTest 严格 JSON/UTF-8/surrogate/safe integer；failures 真实 M2 拒绝及 owner query；diagnostics 数据库版本/来源/摘要损坏 |
| H08 | 原生事件16KiB、输出32KiB N-1/N/N+1 契约测试；真实 Unicode 摘要截断；PK EXPLAIN；真实行锁超时；独立汇总全部正文副本与元数据等于计数；无后续事件的 checkpoint 读取在 revision 99→100 时立即补记元数据字节 |
| H09 | redaction：已知合成签名/DB秘密、私有配置、token/path、reasoning sentinel；公开目标/summary/SSE 不泄露，redacted 真实；unsafe summary fail closed |
| H10 | migration：真实 V18→V19、旧 request/model/tool/checkpoint/invocation 原字节及 V1–V18 checksum 保持、旧 task 无回填、旧 V18 control/worker 在 readiness 前拒绝19、flag off旧任务接续 |
| H11 | retention：过期立即410、17项 UNKNOWN/非静止 writer 不安全前缀后安全任务可清理、全部正文副本/墓碑/唯一释放；重复维护不重复释放 |
| H12 | regression：复用已交付的 directory normal、workspace/POSIX和MCP目标回归、todo normal、question normal；不运行旧完整矩阵 |

## 结果解释

默认完整 Windows `verify` 已执行，存在历史 SourceIntegrityTest ADR README 长度3446/3819差异，以及 `/proc`、POSIX workspace 在 Windows 不支持的既有失败。本任务不修改冻结基线来抹除失败，不声称默认完整 verify 通过。新契约、包边界、SpotBugs 和真实 Linux 业务实验分别报告。

细项限制必须随最终交付明确：本地 JDBC 丢确认注入不等于真实 TCP 中断的每一种时序；未穷举2秒内每条语句、64KiB元数据/7MiB正文/128事件/256可靠事件的全部组合边界；未覆盖每种 schema字段损坏、权限撤销与 commit 的所有交错；未以真实时钟等待7日，也不运行真实付费模型/生产部署。owner/provider 故障和保留竞争的实际覆盖以原断言文件为准，不由此表扩大。
