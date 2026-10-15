# XIAO-34 验证记录

## R4 退出调用门禁修订（2026-10-15）

审查在首批提交 `0c82e1b37db65c3a60a417350b8eb36cba55f62d` 的插件包中加入未执行的 `ForbiddenExit.stop(int status) { System.exit(status); }` 后，完整 verify 仍退出 0。该缺口已通过实际方法调用规则修复：生产类不得调用 `System.exit(int)`、`Runtime.exit(int)` 或 `Runtime.halt(int)`。P1 没有获准的退出调用点，因此覆盖全部生产包；原 Thread/ProcessBuilder 规则及其他架构约束未改。此规则不把一般 `System`/`Runtime` 类型引用一概禁止，也不等于运行时沙箱。

本轮只修改架构门禁、负向验证脚本和实施/验证说明；生产实现、依赖及权威来源未变。正常完整 `mvnw.cmd -B -ntp verify` 退出 **0**，耗时 49.170 秒，仍为 **14 tests / 0 failures / 0 errors / 0 skipped**，SpotBugs 0 问题。真实 worker/control 集成成功，3 个子 JVM 均记录 `alive=false`。

执行 `node tools/verify-negative-gates.mjs system-exit runtime-exit runtime-halt`，脚本退出 **0**；三个隔离源码副本均执行格式修正及完整 verify，各自原始退出码 **1**：

| 新增副本 | 实际失败点 |
| --- | --- |
| `system-exit` | ArchUnit 报告 `ForbiddenExit.stop(int)` 调用 `java.lang.System.exit(int)`，与审查复现一致 |
| `runtime-exit` | ArchUnit 报告同一插件位置调用 `java.lang.Runtime.exit(int)` |
| `runtime-halt` | ArchUnit 报告同一插件位置调用 `java.lang.Runtime.halt(int)` |

每个负向构建为 12 项单元/契约/架构测试中 1 项架构断言失败，发生于 Surefire，尚未执行打包后的集成阶段；违规退出方法只编译/扫描，未运行。脚本匹配具体调用目标才计通过，不用任意非零冒充预期失败。违规源码仅存在于隔离副本；正常源码的完整 verify 通过。

本轮未重跑首批 8 类负向构建；它们的历史结果在下节，测试侧独立矩阵继续。原始正常构建、三个失败构建、源码及清理记录随事项附件交付，修复是否解除 R4 待独立聚焦复验。

额外尝试的 `clean verify` 在清理历史 CRLF 证明副本的只读 Git 对象时退出 1，未进入构建门禁。递归清理操作被运行时策略拒绝；没有改审批或绕路删除，保留该日志。本轮报告的正常通过命令是上述完整 `verify`，未声称本机 `clean verify` 通过。

## P1 首批实现验证（提交 0c82e1b，2026-10-15）

PR：<https://github.com/Love-XiaoPeng/Jimu/pull/1>，base `master`，沿用任务分支，保留既有公开可见性和历史。固定实现提交见该 PR 与事项交付评论；本记录与实现一同提交。下列是本地实测，CI 与独立复核单独记录，不宣称已验收。

工具链：Temurin `21.0.12.1+1-LTS`，Maven Wrapper `3.9.9`，Boot `4.1.1`、Spring AI `2.0.1`。Jackson 安全补丁为 `2.21.7` / `3.1.7`，依据和范围见 [实施说明](docs/implementation-p1.md)。

| 实际命令 | 退出码 / 结果 |
| --- | --- |
| `./mvnw.cmd -B -ntp verify` | 0；默认完整生命周期，14 tests，0 failures/errors/skipped |
| `./mvnw.cmd -B -ntp spotless:apply verify help:effective-pom -Doutput=target/effective-pom.xml dependency:tree -DoutputFile=target/dependency-tree.txt` | 0；格式修正后完整构建，effective POM / 实际依赖树生成成功 |
| `node tools/verify-negative-gates.mjs` | 0 表示全部注入均按预期失败；各副本完整 verify 退出 1，详见下表 |
| `node tools/verify-whitespace.mjs` | 0；原始 CRLF 文件检查退出 0，加一个真实行尾空格后检查退出 2 |
| `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff origin/master...HEAD --check` | 与 CI 使用相同显式规则；来源字节不规范化 |

### 测试与断言

| Suite | 数量 | 实际检查 |
| --- | --- | --- |
| `SourceIntegrityTest` | 1 | 13 份来源长度/SHA256，以及打包输出 Schema 与权威源逐字节一致 |
| `PackageBoundariesTest` | 1 | 扫描实际生产类，叶子契约/内核/插件/Controller 依赖方向、跨内核公开 API、线程/进程准入和顶层包无环 |
| `WebFetchPluginContractTest` | 10 | HTTP 非 2xx 四字段 DTO；缺失/额外/类型/URI 拒绝；重复身份/能力拒绝；manifest 路径/入口拒绝；2020-12 格式/关键词/本地引用；调用前后取消；origin/deadline 限制；截断/禁重定向；已收到响应头后的慢响应体仍超时、readiness 返回 DOWN；Spring AI 适配类型实际可用 |
| `RuntimeIT` | 2 | 打包 JAR 作为真实 worker/control 子进程；资源正常 UP、provider 下线两角色 DOWN、恢复 UP、worker 退出 control DOWN；缺必需 origin 启动非零；finally 收集进程 |

SpotBugs：0 BugInstance、0 Error；Enforcer 工具链/收敛/禁止依赖均通过。原始 Maven、测试汇总及运行日志随事项交付，CI 使用相同默认 verify 并上传报告。

### 真实负向门禁

每项在 `target/negative-gates/` 独立副本修改，不污染源码；退出 1 且日志匹配对应失败点才计为成功，超时/错误位置不匹配不计通过。注入后未改动的主工作树完整 verify 退出 0。

| 注入 | 预期且已观察的失败点 |
| --- | --- |
| 非规范 Java 格式 | Spotless |
| Alibaba agent-framework 依赖 | Enforcer BannedDependencies |
| 内核引用具体插件 | ArchUnit |
| Controller 引用具体插件 | ArchUnit |
| 确定的 null 解引用 | SpotBugs NP_ALWAYS_NULL |
| 启动清单重复插件身份 | Duplicate plugin identity |
| 输入 Schema 改成其他 draft | Unsupported Schema dialect |
| 指定不存在的 worker Java 运行时 | Failsafe 启动进程失败，未 skip |

### 独立资源与清理交接

本阶段必需资源由测试自己建立：loopback 临时端口 HTTP fixture、独立 worker/control JVM 和 JUnit 临时目录。无需账户、付费模型、共享服务、数据库或对象存储。测试 finally 关闭 fixture 并停止/等待所有子 JVM；`target/failsafe-reports/runtime-evidence/cleanup-*.txt` 记录 PID、退出码与 `alive=false`，临时文件由 JUnit 清理，日志保留于 target。Windows 主动终止进程记录 exit=1 是清理结果，与 Maven 构建退出码分开记录。没有需要使用者继续维持的服务。

当前未启用账本/持久化/产物写入职责，因此没有 DB/存储迁移或集成通过声明；P2 启用时必须先绑定独立资源、版本与迁移并加入默认门禁。本轮仅提交可信内置样例和 readiness；模型循环、恢复/租约、任意网页业务、实时取消/隔离、安全及性能验收不在通过声明中。

依赖可获得性及构建收敛已实测；OSV 查询在当次返回的范围内发现并修复 Jackson 公告，修复坐标查询无返回公告。这不是全依赖安全审计或永久无漏洞声明。正式框架运行链仍按 ADR 等待 P2/E1 的持久边界恢复试验。

## 首批文档入库状态（历史记录）

确认目标为原有公开空仓库，使用已授权 GitHub 身份在 `master` 创建首个 README 提交，再由 Multica 受管检出建立独立任务分支。保留仓库可见性。

八份基线及三份 accepted ADR 保持原字节。两个 sources.json 为当时准备/交接记录，其中旧环境状态属于历史证据。`.gitattributes` 禁止 Git 对这些来源进行换行转换，入库检查逐项验证记录中的字节数和 SHA256。

首批文档提交不代表 Java 构建、worker 集成或 P1 验收通过。后续实现追加实际命令、退出码、测试数量、资源及限制。

## 以下为历史准备核验（保留排障来源，不代表当前状态）

日期：2026-10-15。准备成果尚未提交远端，没有 PR 或固定 Git 提交，P1 未验收。

八份基线保持原字节，其中五份核心摘要与发布值一致。三份 accepted ADR、架构索引及原静态核验记录已原样纳入 docs/adr，四份 Markdown 与架构师发布摘要一致，详见两个 sources.json。新说明引用 ADR 明确积木命名与原 Schema 映射，未修改历史方案或旧 URN。未编译 Java、解析依赖、运行完整门禁或真实 worker。

## 本轮创建通道实测

已有授权：目标不存在时创建 Love-XiaoPeng/Jimu 私有仓库并初始化 master，存在时保留原内容与可见性。ADR 前置已解除，无需再次请求创建授权。

| 探测 | 实际结果 |
| --- | --- |
| 当前可调用工具 | 无 GitHub 连接器；workspace MCP 列表为空 |
| 本机 gh / 注入令牌 | gh 不在 PATH；GH_TOKEN/GITHUB_TOKEN 未注入 |
| Multica repo 命令 | 仅 add/list/remove/checkout；add 登记 URL，不创建 GitHub 仓库；目标资源绑定也不等于创建 |
| curl 请求目标 API | GET https://api.github.com/repos/Love-XiaoPeng/Jimu，退出35、HTTP000，Schannel AcquireCredentialsHandle / SEC_E_NO_CREDENTIALS (0x8009030e)，在本机TLS初始化失败 |
| 已安装 Node 客户端请求同一 API | 匿名 GET 实际返回 HTTP404、Not Found；此客户端可达 API，匿名404不能排除私有仓库已存在 |
| 标准 Git 凭据助手 | 关闭交互后 credential fill 退出128，未取得凭据；没有输出或保存凭据、令牌、助手配置或Owner秘密 |
| 带身份创建请求 | 当前没有可用 GitHub 身份通道，未发送 POST /user/repos，未初始化分支或写入远端 |

不能把匿名404、TLS错误称作创建请求被 GitHub 拒绝，也不能把凭据助手缺凭据等同于平台未连接 GitHub。平台连接与当前任务可调用能力之间尚缺通道。

## 最小修复动作

请平台/运行环境负责人将现有已连接 GitHub 集成的仓库创建、内容和 PR 能力提供给当前任务，或提供受支持且已鉴权的 gh 执行通道。无需重新授权创建，无需把Owner令牌发到评论，也不需要改共享网络/FRP。

通道可用后先验证身份和目标：存在则保留；不存在且权限确认后创建私有仓库并初始化 master，再检出独立分支纳入文档与ADR，开 base=master 的 PR 并回传固定提交。当前仍不能验证该已连接身份的创建权限或远端 refs。
