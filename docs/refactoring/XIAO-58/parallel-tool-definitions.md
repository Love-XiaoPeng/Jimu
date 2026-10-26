# XIAO-73 宿主工具定义与 pin 校验切片

输入提交 `d6f147df1e59a5c80db710e48408eb1130eae3a3`，tree `738785bde0df7bd9efcddbbfcb6f88402bd4548d`。开发分支 `agent/b/XIAO-73-tool-definitions`，独立工作树；中级 A 仍是 PR22 集成分支 `agent/agent/7aeccb14d7a9` 的唯一写入者。

## 实际改动与兼容

`DurableAgent.runAttempt → definitions(lease) → HostToolDefinitions.definitions(lease)`。新协作者承担真实 snapshot 读取、能力迭代、工具版本选择、可用性和 pin 校验、schema/description 组装及不可变结果返回；DurableAgent 只保留构造接线和方法委托。没有新增 Spring Bean、服务空壳或第二个 advisor 循环。

完整定义方法从输入提交迁出；忽略空白及方法可见性后与原方法一致。构造仍先创建原调用网关，再构造缓存的 web_fetch 定义；不提前查询 ledger、检查 provider 可用性或加载其他能力的契约。三个公开构造器及 records 不变。每次定义解析只调用一次 toolSnapshot，按 capabilities 原顺序迭代，包括重复名称；结果仍用 List.copyOf。

- MCP：配置/available 在 contract 和 pin 检查之前；缺 pin 为原 PLUGIN_PIN_MISMATCH。
- 网页：先检查 pin，再 available；缓存定义的 schema 和描述原样保留，fixture/public 模式均有对照。
- 工作区：先检查 publicMode/workspace，再按 pin 版本查 contract，再 available，最后核摘要。glob/grep/read_image 可用性失败仍优先于错误摘要，但版本错误先于 available。
- 目录、todo 及各会话分支：原缺 pin 空指针不改写成新业务异常。会话 read 仍按 prior-session/trace/search/基础能力选择 v4/v3/v2/v1；event search 仍按 trace 选择 v3/v2；不依据迭代先后选择版本。
- 问答：继续按 pin 版本读取已打包契约，保留 0.1.0/0.2.0 的输入与描述，以及未知版本/坏摘要拒绝。

未修改 runAttempt、Session、模型提交、slots、restore/wire、HostInvocationGateway、bootstrap、持久化、POM 或冻结资源。

## 开发验证

环境：Windows，Temurin JDK `21.0.12.1`，Maven Wrapper `3.9.9`。原始日志和 Surefire XML 随事项最终评论附件交付。

| 命令 | 结果 |
| --- | --- |
| `.\mvnw.cmd -B -ntp spotless:apply` | exit 0，仅改动本片 Java 格式 |
| `.\mvnw.cmd -B -ntp test '-Dtest=HostToolDefinitionsTest,HostInvocationGatewayTest,PackageBoundariesTest,RefactoringNegativeGatesTest,TodoContractTest,UserQuestionContractTest,SessionContractsTest,SessionSearchContractsTest,SessionTraceContractsTest,PriorSessionContractsTest,ModelProtocolTest,ModelInputBoundTest'` | exit 0，206 项，失败/错误/跳过均 0；含 Enforcer、Spotless、Checkstyle、实际编译及架构正反例 |
| `.\mvnw.cmd -B -ntp test spotbugs:check '-Dtest=HostToolDefinitionsTest'` | exit 0；补强后的 61 项，失败/错误/跳过均 0；SpotBugs BugInstance/Error 均 0 |

新增 61 项覆盖全部定义分支：打包契约的完整 schema/description 字符串、能力顺序与重复项、session v1–v4 和 companion 正反顺序、question 0.2.0、缺 pin/错版本/错摘要、available 首错、空能力及不可变列表。最后补强错版本与错摘要为互不混淆的独立输入，并核对错误 HTTP 状态。真实 DurableAgent.run 测试证明三个构造入口均惰性，先解析定义再读 modelSnapshot，定义拒绝时不进入模型；复用原 5 项网关测试覆盖真实 advisor 回调及暂停/提交不确定传播。

工具准备阶段曾因本机无 Python 和 PowerShell 只读变量名失败，改用 .NET 文件读写及非保留变量重做；没有 Java 测试失败。没有执行 GitHub CI、真实模型请求、付费调用、部署或共享服务操作。测试账本是内存替身，provider 未实际执行；不将这些结果记为独审、QA、数据库或业务验收。原生搜索成功广告需原有 Linux bundle，图片成功广告需可用存储，本片只验证其原拒绝顺序，不伪造这些环境。无数据库业务变化，不重跑 MySQL 或历史完整 verify 矩阵；既有历史失败和限制继续保留。

## 集成与验收

按本项冻结要求交一个可 cherry-pick 的支线提交，由 PM 安排差异独审、中级 A 串行接入 PR22；不另开包含整条 PR22 变更的 master PR，不修改或推送 A 的分支。准确交付 SHA/tree 以最终评论为准。接入时若 DurableAgent 已继续演进，仅合并本片字段/构造/definitions 委托，保留其他改动。随后在集成 SHA 复核受影响路径；独审、必要 QA、验收及最终 master 推送回读完成前，本片保持 in_review，不宣称整体重构完成。
