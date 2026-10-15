# P1 骨架实施边界

本说明将三份 accepted ADR 落到当前实际代码；历史基线与 ADR 文件原字节保留。项目尚需独立审查、测试及产品验收。

## 包和依赖

单一 `io.github.lovexiaopeng.jimu:jimu` Boot 制品，Java 21（无 preview）、Maven Wrapper 3.9.9。Wrapper 为 Apache only-script 3.3.4，分发 ZIP 的 SHA256 固定为 `4ec3f26fb1a692473aea0235c300bd20f0f9fe741947c82c1234cefd76ac3a3c`，已与官方 SHA512 对照。

| 部件 | 采用值 / 证据范围 |
| --- | --- |
| Boot / AI BOM | 4.1.1 / 2.0.1；实际导入并解析 `spring-ai-model`，AI 类型只在适配层 |
| JSON | Boot/AI 的 Jackson 3.1.7 与 validator 使用的 Jackson 2.21.7 同时存在于各自命名空间；以 Boot 提供的两个 BOM 属性做针对性安全补丁覆盖，继续执行收敛检查 |
| Schema | networknt 1.5.9，明确采用 2020-12、启用 format assertions；实际测试 prefixItems/items、URI 格式及文档内引用。P1 仅允许同文件 `#` 引用，拒绝外部或其他文件引用 |
| 格式 / 导入 | Spotless 2.43.0 / Google Java Format 1.24.0；Checkstyle Maven 3.6.0 检查通配符导入 |
| 静态 / 依赖 | SpotBugs Maven 4.9.3.0，高置信正确性规则；Enforcer 3.5.0，版本/收敛/禁止混入 Alibaba 或旧 AI 链 |
| 测试 | Surefire/Failsafe 3.5.3；JUnit Jupiter 6.0.3（Boot BOM 管理，沿用 Jupiter API）；ArchUnit 1.3.0 扫描实际新根包生产类 |

`plugin.api` 只有 JDK 类型和不可变契约。`kernel.plugin.application` 构造不可变发现集合，不静默覆盖重复身份或能力名；这不是持久化安装/升级服务。`kernel.readiness` 经自己的端口探测资源，仅通过 `kernel.plugin.api` 访问插件目录。`api` 仅暴露 readiness DTO；`bootstrap` 只做角色配置与固定实现装配。ArchUnit 约束叶子契约、跨模块公开 API、插件/内核/Controller 方向、线程/进程准入及顶层包循环。

R4 补充：ArchUnit 对实际生产字节码的方法调用分别禁止 `System.exit(int)`、`Runtime.exit(int)`、`Runtime.halt(int)`。P1 没有获准的 JVM 退出调用点，因此本规则覆盖全部生产包，bootstrap/runtime 不自动获得退出豁免；既有 Thread/ProcessBuilder 类型规则保留。将来确需例外须按 ADR 精确批准调用位置。本门禁不等于对反射、native 或不可信 JAR 的运行时沙箱。

Spring AI 的 ToolDefinition 由适配层从已校验 descriptor 和输入 Schema 生成，实际测试通过。P1 没有配置 ChatModel、ToolCallback 或工具循环，未请求真实模型；P2 才接入唯一 ToolCallingAdvisor 循环及宿主网关，不能绕过租约、身份、权限和预算直接把样例发布成可调用工具。

## Jackson 安全补丁依据

2026-10-15 对实际坐标查询 OSV：Boot 默认管理的 jackson-databind 2.21.5/3.1.5 命中 GHSA-cxp5-3px4-pw24、GHSA-gx83-3vf8-gh7j、GHSA-q4xh-88c3-wmh7、GHSA-wjgm-6hv5-3cvf、GHSA-wv8q-qhhj-9h54。公告分别涉及对象身份引用、类型 ID 缓存、默认多态类型、XML 时间类型和 Path 反序列化；当前 P1 不使用这些类型化输入路径，但不沿用已知受影响版本。

公告所列本分支完整修复版本为 2.21.7/3.1.7，使用 Boot 的 `jackson-2-bom.version` 和 `jackson-bom.version` 精确覆盖对应 BOM，保留 Boot 4.1.1 / AI 2.0.1 主选、不叠其他编排链、不关闭 convergence、不逐个强制传递依赖版本。重新收集完整 verify、依赖树与公告查询作为此补丁例外的兼容证据；后续升级 Boot 应复核并移除不再必要的覆盖。公告详情可在 `https://osv.dev/vulnerability/<公告ID>` 查阅。

## 插件和 Schema

仅注册随制品审阅的 `jimu.web.fetch@0.2.0` / `web_fetch` 样例，不动态加载 manifest 中的 class，不接受租户 JAR。唯一维护清单为 `src/main/resources/plugins/builtin/webfetch/plugin.yaml`；目录 descriptor 是校验后的派生视图，不另维护一套 JSON manifest。

输出 Schema 在 generate-resources 阶段直接复制原权威文件，源码中不维护第二份输出定义。启动准入和来源完整性测试核对摘要；旧 `$id`、`$comment` 与四字段原样保留。校验使用 JSON 边界类型，公共插件契约不暴露 JSON/Spring 类型。

样例 provider 仅允许运维配置的一个 HTTP(S) origin，无凭据、禁重定向，最多收集 1 MiB + 1 字节以识别截断，仅输出前 1 MiB；前后检查取消，剩余 deadline 约束整个请求（包含慢响应体），超限或超时取消订阅/请求。readiness 同样限制完整请求为 2 秒、响应体为 4096 字节，超过上限返回 DOWN。非 2xx 是可观察内容。`configured-origin-only` 是 P1 fixture 的窄权限，并不等于已完成 `public-web-through-proxy`、HTML 清洗、SSRF/重绑定隔离、canonical 总字节预算或 Harness 模型投影；任意网页业务仍未启用。取消目前在边界前后检查，不宣称已实现 P2 的进程树排空或实时取消协议。

## 启动与必需资源

设置 `JAVA_HOME` 指向 JDK 21，完整 verify 自动生成 JAR，并在本机临时端口启动 fixture、worker 和 control。构建不需要 Docker、外部数据库、存储服务或付费密钥，测试不会借用其他项目身份或服务。

手动启动示例（替换端口为你自己的受控 fixture/worker）：

```sh
java -jar target/jimu-0.1.0-SNAPSHOT.jar --jimu.role=worker --jimu.provider-origin=http://127.0.0.1:9000 --server.port=8081
java -jar target/jimu-0.1.0-SNAPSHOT.jar --jimu.role=control --jimu.worker-origin=http://127.0.0.1:8081 --server.port=8080
```

默认绑定 `127.0.0.1`；这只是 P1 本机启动验证，无共享部署。角色必须显式为 worker/control，缺对应 origin 配置则启动失败。`GET /readyz` 在本角色清单/Schema 准入及所需资源探测成功后返回 200/UP；worker 的 provider 或 control 的真实 worker 不可用时返回 503/DOWN。control 要求收到真实 worker 角色和非空插件目录的 UP 响应，不以 TCP 连通冒充 readiness。

当前没有 task/invocation/artifact 账本或数据写入 API，因此没有启用数据库与持久存储能力，也未创建空迁移脚本或虚构 DB/存储集成通过。P2 落地相应持久职责时须独立绑定资源、固定驱动与迁移版本，并把真实 DB/存储门禁加入默认 verify；现有 worker 集成不能替代届时的验证。

## 完整门禁和证据

默认 `./mvnw -B -ntp verify` 执行 Enforcer、Spotless、导入检查、编译、Surefire（真实生产类架构扫描、插件/Schema/来源契约）、Boot 打包、Failsafe 真实独立进程集成、SpotBugs 以及集成结果核验。Surefire/Failsafe 缺测试会失败。集成不使用 skip/profile；缺 Java、JAR、Schema 或角色必需配置直接失败，资源下线不能得到 UP。

集成测试创建自己的 HTTP fixture，不复用已有服务；所有子 JVM 在 finally 中停止并确认退出，fixture 同步停止。进程日志和 PID/退出清理记录保存在 `target/failsafe-reports/runtime-evidence/`。不存在跨 turn 遗留服务。

CI 同样执行默认 verify，Actions 固定提交、仅 contents:read；上传测试报告、进程记录、SpotBugs、effective POM 和依赖树。负向用例在独立副本调用同一 verify；原始退出码及预期失败点保存在 `target/negative-gates/`。实际本轮结果见根目录 VERIFICATION.md；上述配置不是运行结果的替代。

历史来源中 CRLF 不做规范化。Git 空白检查在 `.gitattributes`、本地命令及 CI 均显式使用 `blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol`；原 CRLF 与真实行尾空格的正反证据通过 `node tools/verify-whitespace.mjs` 重现，见 CONTRIBUTING。
