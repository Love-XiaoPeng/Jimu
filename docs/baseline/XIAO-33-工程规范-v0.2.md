# 饕餮从零开发工程规范与骨架验收草案

版本：0.2；日期：2026-10-15；事项：XIAO-33；负责岗位：架构师。

本规范配套本次《可行性与总体方案》0.3，替代工程规范草案0.1的设计口径。固定来源与从零开发边界不变；修订仅是文档，未创建骨架、运行构建/门禁或部署。目标与资源仍待明确绑定。插件输出以本次唯一Schema源为准，完整门禁包含真实必需worker集成。

## 1. 构建与模块决定

**首期使用 Maven Wrapper + 一个 Maven 项目/Boot 制品，以包明确模块边界，通过 ArchUnit 自动约束。** 控制层和 Java worker 是同一制品的不同启动角色；高风险工具另以隔离 worker/镜像运行。先避免多个空 Maven 模块与服务间事务/版本协调成本。

插件 API 首期是独立叶子包；第三方 SDK 真正需要独立发布，或构建/依赖隔离有证据时，再抽成独立 Maven 制品。包隔离由门禁约束，并不等于已经完成独立发布或物理 sandbox。契约设计保持可抽取，插件代码不因暂时单制品就引用宿主内部类型。

主选候选：JDK21 / Boot4.1.1 / Spring AI2.0.1；Maven Wrapper 固定具体 3.9.x 版本（骨架可先验证 3.9.9）。依据和 Alibaba 备选见主稿。真实 dependency tree、effective POM、插件版本/兼容性清单由骨架任务生成；本稿没有实际构建验证。compiler release、Enforcer JDK范围、wrapper版本与 CI 镜像要一致，不靠默认系统工具链。

## 2. 可直接落地的目录和职责

`io.taotie` 是建议示例包名，项目创建时统一一次；不混用多个临时根包。

```text
taotie/
  pom.xml                         # Boot parent / AI BOM / 构建与门禁
  mvnw, mvnw.cmd, .mvn/wrapper/    # 固定 Maven 与下载校验
  AGENTS.md, CONTRIBUTING.md      # 当前任务约束与共同工程规范
  .github/workflows/verify.yml    # 示例 CI 位置，按真实仓库平台调整
  src/main/java/io/taotie/
    bootstrap/                   # 启动角色、Bean装配、配置绑定，无业务分支
    api/                         # REST/SSE Controller、HTTP请求响应DTO
    kernel/
      identity/                  # 服务端 tenant/actor 与授权
      task/{api,application,domain,port}/
      plugin/{api,application,domain,port}/
      invocation/{api,application,domain,port}/
      artifact/{api,application,domain,port}/
      event/{api,application,domain,port}/
      budget/{api,application,domain,port}/
      dispatch/                  # 领取、租约、fencing、恢复协调
    plugin/api/                  # Manifest/Descriptor/ToolPlugin/ExecutionContext
    model/api/                   # 模型能力/请求/响应/usage端口
    execution/                   # 可信任务执行、单一AI循环、步骤持久化协调
    adapters/
      ai/springai/               # ToolCallback/Advisor/ChatModel与端口转换
      persistence/               # Repository端口实现、数据库对象、映射
      storage/                   # 产物存储适配
      runtime/                   # 受控HTTP/MCP/隔离进程适配
      telemetry/                 # Micrometer与安全审计导出适配
    plugins/builtin/
      webfetch/                  # 工具输入输出/执行逻辑，示例
      websearch/
      filesystem/                # 分阶段实现read/write/edit/read_image
  src/main/resources/
    application.yml              # 安全公共默认值，不含秘密
    plugins/builtin/webfetch/
      plugin.yaml
      input.schema.json
      output.schema.json
    db/migration/                # 独立的数据库版本脚本
  src/test/java/io/taotie/
    architecture/                # 依赖/循环/分层约束
    kernel/                      # 状态/权限/预算/幂等必要测试
    contract/                    # 每个插件的共享契约测试
    integration/                 # *IT，真实应用/库/worker受控依赖
  src/test/resources/
    contract/harness/<sha>/      # 已核验工具的输入/效果/错误fixture
  docs/
    architecture.md, engineering.md, plugin-contract.md
    adr/                         # 经审阅的关键决策
    migration/harness-tools.md   # 全量清单、映射、差异和进度
    verification/<task>/         # 命令、环境、结果和限制
  deploy/                        # 专用部署描述与健康/回滚说明
```

目录中的六类 kernel 子包按实现实际需要创建，骨架不生成每个目录的空 Service/Manager。每个模块的 `api` 是对其他模块发布的窄接口；`port` 是该模块依赖外部设施的接口；application 完成用例协调；domain 表达不依赖框架的状态/约束。persist/adapters 可以依赖公开 domain/port，domain 不反向依赖其实现。

## 3. 依赖方向与禁止样例

| 调用方 | 允许引用 | 禁止引用 |
| --- | --- | --- |
| `plugin.api` | JDK、明确的轻量契约类型；必要的空值注解 | Spring AI/Alibaba、Controller、Entity、Mapper、宿主 kernel实现、具体插件 |
| `model.api` | JDK、模型请求/响应契约 | Spring AI/Alibaba类型、工具具体实现、HTTP Controller |
| `kernel.*.domain` | JDK、本模块领域对象、显式共享ID值类型 | Spring/SQL、Repository实现、AI框架、具体插件、Controller |
| `kernel.*.application` | 本模块domain/port、其他模块发布api、plugin/model契约 | 其他模块内部Service/Entity/Mapper、具体插件/adapter实现 |
| `api` Controller | kernel发布api、HTTP DTO、认证映射 | DB访问、具体工具、直接模型客户端、执行线程创建 |
| `execution` | task/invocation/budget等公开API和模型/插件端口 | 绕过网关调用工具body；直接使用数据库Entity；自己创建第二个AI循环 |
| `plugins.builtin.*` | plugin公开契约、必要的工具provider端口 | kernel内部实现、共享静态当前用户、任意Repository、直接读宿主凭据 |
| `adapters.*` | 对应公开port/domain/DTO与受控第三方依赖 | 在适配器内替代平台授权，使用跨租户无约束数据查询 |
| `bootstrap` | 装配所需具体实现与配置 | 任务/工具业务逻辑；任何其他模块反向依赖bootstrap |

允许：`WebFetchPlugin implements ToolPlugin<FetchInput, FetchOutput>`，依赖受限 `WebPageReader`；`JdbcTaskRepository implements TaskRepository`；`TaskController` 仅调用 `TaskSubmission`。

禁止：`TaskController -> WebFetchPlugin.execute()`；`ToolPlugin -> JdbcTaskRepository`；`TaskDomain -> OpenAiChatModel`；`kernel.task -> plugins.builtin`；`WebFetchPlugin` 从全局容器查 `DataSource` 或设置当前 tenant。

跨模块调用采用发布接口，不能用 `public` 关键字将内部实现当公共API。JSON/AI框架类型停留在适配层；公共插件 API 用类型化输入输出和稳定结果。单 Maven 项目仍可在编译时引用一切依赖，所以必须用架构测试使违规构建失败。

### 可检查的 ArchUnit 样例

以下是骨架阶段应落地的规则示意，尚未编译。ArchUnit 的具体版本由构建兼容检查固定。

```java
ArchRule pluginApiIsLeaf = noClasses()
    .that().resideInAPackage("io.taotie.plugin.api..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..", "com.alibaba.cloud.ai..",
        "io.taotie.kernel..", "io.taotie.adapters..",
        "io.taotie.plugins..", "io.taotie.api..");

ArchRule kernelDoesNotImportImplementations = noClasses()
    .that().resideInAPackage("io.taotie.kernel..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "io.taotie.plugins..", "io.taotie.adapters..",
        "org.springframework.ai..", "com.alibaba.cloud.ai..",
        "io.taotie.bootstrap..");

ArchRule domainDoesNotImportFrameworks = noClasses()
    .that().resideInAPackage("io.taotie.kernel..domain..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..", "java.sql..", "jakarta.persistence..");
```

另检查模块之间只引用公开 `api` 包、包循环、Controller只依赖发布用例接口、禁用 `System.exit`/不受控线程和进程创建。运行进程的代码只在批准的 runtime adapter，资源限制/执行身份必须经过宿主上下文。

## 4. 代码内容约定

| 项目 | 可执行规则 / 合理例外 |
| --- | --- |
| 命名 | 根包小写，类UpperCamelCase、方法lowerCamelCase、常量UPPER_SNAKE_CASE；用职责命名 `TaskSubmission`、`LeaseRepository`，避免万能 `CommonUtil`/`BaseService` |
| 格式/导入 | Spotless + 固定 Google Java Format；禁止通配符导入；本地 `spotless:apply` 修复，CI只check；示意文档不是可直接复制的最终格式化文件 |
| 空值 | 输入必需字段在边界拒绝；业务域默认非空；可缺项用Optional查询结果或明确可空字段，不能靠返回null表示多种错误；采用一致空值注解但不承诺编译器已全面检查 |
| DTO边界 | HTTP `Request/Response`、公开 `DTO`、domain对象、DB `Row/Entity`分开；record适合不可变值；不直接序列化Entity、异常或授权上下文 |
| 泛型/JSON | 核心使用类型化ID/上下文/命令，不传播 `Map<String,Object>`；动态插件JSON留在边界并强制Schema/大小/深度；有界 `Map<String,String>` labels是合理例外，不能承载权限或秘密 |
| 配置/注入 | 构造注入、明确ConfigurationProperties并校验；没有工具ID/endpoint/密码硬编码；只在bootstrap组合Bean，不用service locator到处拿容器 |
| 并发 | 运行上下文不可变、显式传递；禁止全局 `currentUser/currentTask`；executor受宿主管理并有界；虚拟线程仍受并发/资源配额 |
| 资源 | Closeable使用try-with-resources；流/HTTP连接/文件/worker生命周期归属明确；取消与异常均回收，未完成输入不得留下文件写者 |
| 超时/取消 | deadline贯穿模型、工具、进程和提交；先检查准入再传递取消token；Thread interrupt只作协作手段，不把Future.cancel当作硬终止 |
| 重试 | 一处统一策略，deadline/总预算内、有限次数；仅宿主核验可重试的read；write/unknown及整个Agent禁止自动重放；provider SDK重试必须纳入同一预算 |
| 事务 | 短事务记录意图/状态/事件；网络、LLM、进程等待不能持有DB事务；提交条件包含tenant、版本/租约token；不凭ORM自动得到隔离 |
| 时间/标识 | Clock由注入端口提供，持久化UTC；耗时用单调时钟；不把外部工具callId作为安全令牌；UUID等标识必须伴随授权 |
| 注释 | 公共契约说明生效边界、错误、资源/取消和兼容；复杂恢复/副作用处说明依据；不逐行复述代码，不用大量JavaDoc字数代替行为测试 |

## 5. 一个遵守边界的基础工具插件样例

样例选择 Harness `web_fetch`：源自固定提交 `571902028a75c64c1d767f4ae5fd099485f0ddf0` 的 `packages/web/tool-web`。源代码工具调用由 `ctx.web` 承载；模型端参数是 `url`，timeout是部署策略而非模型参数。非2xx页面由参考工具作为可观察内容返回，不应无说明改成一般异常。

样例是 Java 设计，不代表已迁移；原render文本/HTML过滤/不可信标记/截断必须在迁移对照中验收。内部canonical DTO不要求与原TypeScript类型完全一样，但对模型/用户可见行为变化必须列明。

```text
plugins/builtin/webfetch/
  WebFetchPlugin.java             # 实现ToolPlugin，薄执行与结果转换
  FetchInput.java                 # 仅url，不接收tenant/secret/timeout
  FetchOutput.java                # 状态、内容、来源、截断信息
  WebPageReader.java              # 受控provider端口
adapters/runtime/web/
  GovernedWebPageReader.java       # 网络策略、HTTP、重定向/输出限额
resources/plugins/builtin/webfetch/
  plugin.yaml, input.schema.json, output.schema.json
test/contract/
  WebFetchPluginContractTest.java
test/integration/
  WebFetchRuntimeIT.java
```

```java
public record FetchInput(String url) {}
public record FetchOutput(int httpStatus, String sourceUrl,
                          String content, boolean truncated) {}

public interface ToolPlugin<I, O> {
  ToolDescriptor descriptor();
  Class<I> inputType();
  Class<O> outputType();
  ToolResult<O> execute(I input, ExecutionContext context);
}

public final class WebFetchPlugin implements ToolPlugin<FetchInput, FetchOutput> {
  private final WebPageReader reader;
  private final ToolDescriptor descriptor;

  public WebFetchPlugin(WebPageReader reader, ToolDescriptor descriptor) {
    this.reader = reader;
    this.descriptor = descriptor;
  }

  @Override public ToolDescriptor descriptor() { return descriptor; }
  @Override public Class<FetchInput> inputType() { return FetchInput.class; }
  @Override public Class<FetchOutput> outputType() { return FetchOutput.class; }

  @Override public ToolResult<FetchOutput> execute(FetchInput input,
                                                  ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    FetchOutput output = reader.fetch(input.url(), context);
    context.cancellation().throwIfCancelled();
    return ToolResult.ok(output);
  }
}
```

省略的类型属于待实现插件 API；`reader` 必须由宿主装配成受限provider，不接受插件创建任意HTTP客户端。统一网关在调用前后校验版本、权限、Schema和输出预算，转换边界异常/审计；body不再次实现一套授权，不直接向模型暴露未包装的回调。取消检查不能保证在远程fetch完成后撤销网络效果。

```yaml
manifestVersion: 1
id: taotie.web.fetch
version: 0.2.0
kind: tool
hostApi: ">=0.1.0 <0.2.0"
entry: {type: trusted-jvm, class: io.taotie.plugins.builtin.webfetch.WebFetchPlugin}
capabilities:
  - name: web_fetch
    inputSchema: input.schema.json
    outputSchema: output.schema.json
    sideEffect: read
permissions: {network: [public-web-through-proxy]}
secretRefs: []
dependencies: []
limits: {timeoutMs: 30000, outputBytes: 1048576}
healthCheck: {operation: provider-readiness, timeoutMs: 2000}
errorContract: taotie-invocation-result
eventTypes: [invocation.started, invocation.completed, invocation.failed]
```

这里的trusted-jvm仅适用于平台审阅并随制品发布的工具；不可由普通租户指定任意 class。若保留Node实现，换成经过准入的isolated-worker/HTTP入口，不改变工具可见调用名与治理边界。主方案0.3统一entry.type及包内Schema文件引用；引用只能落在已校验包，禁止任意路径/远程URL。旧技术0.2的入口/输出草稿被替代，不是可混装的第二规范。

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {"url": {"type": "string", "minLength": 1, "format": "uri"}},
  "required": ["url"],
  "additionalProperties": false
}
```

唯一权威输出Schema见本次附件 `XIAO-33-web-fetch-output-v0.2.0.schema.json`，适用于 `taotie.web.fetch@0.2.0`。打包 `output.schema.json` 必须从该源生成/复制并核验摘要，不能再独立维护另一份输出定义。canonical DTO四字段为httpStatus/sourceUrl/content/truncated；Harness模型文本/来源/不可信标记和安全事件是不同投影。两个互不兼容0.1.0未发布草稿被替代；已发布破坏兼容的变更必须新版本并提供映射。P1同一fixture通过真实Schema validator与DTO序列化检查，缺字段/额外字段/错类型须拒绝；本轮未编译或运行该检查。

必须区分服务端dialect和模型子集；模型不支持minLength等字段时由provider明确处理，服务端校验保留。registry读取manifest并验证descriptor对应，无重复ID/调用名、无静默覆盖；升级保留旧版本供运行中任务使用。这里的canonical结果经迁移adapter投影为Harness约定的模型文本/来源与不可信标记，不将新增字段静默直接传给模型。

## 6. 统一错误、日志、事件与产物

| 宿主安全code | 含义 | 默认恢复 |
| --- | --- | --- |
| INPUT_INVALID / OUTPUT_INVALID | Schema、类型、资源上限等边界不合格 | 改正输入或修复插件；不自动重复同调用 |
| ACCESS_DENIED / PLUGIN_DISABLED / VERSION_STALE | 无权限、禁用、安装/版本变化 | 重新读取宿主状态；不得凭模型再请求放行 |
| DEADLINE_EXCEEDED / CANCEL_REQUESTED | 本地执行预算已到或取消已提出 | 区分远端结果是否已确认；不伪称副作用撤销 |
| DEPENDENCY_UNAVAILABLE | provider缺失/网络故障等安全映射 | 只在宿主认定只读可重试时有界重试 |
| EFFECT_OUTCOME_UNKNOWN | 外部写/未知操作可能发生但未记录确认 | 停止自动重放并核对 |
| INTERNAL_ERROR | 需要traceId定位的实现错误 | 安全摘要，不暴露原始异常/内部地址 |

工具原有业务code（例如 Harness FS_NOT_OBSERVED/FS_STALE_VERSION 或 WebError）可在结构化detail的受限字段保留，映射表必须随迁移交付。任务/控制API、工具结果、HTTP远端状态不能混为同一层错误；web_fetch的页面404不自动等于invocation的DEPENDENCY_UNAVAILABLE。

内部异常保留cause供受限诊断，边界转换成稳定code、安全message、是否可重试及traceId；禁止catch后吞掉、返回success或继续执行下一项写操作。`retryable`只是提示，最终重试由宿主政策/预算决定。

日志结构统一：time/level/serviceRole/traceId/taskId/attemptId/invocationId/pluginId/version/code/duration；tenant/actor使用平台允许的审计标识。不得记录token、secretRef解密值、原始prompt/完整响应、文件正文或未脱敏URL查询参数。高基数调用ID进日志/trace，不直接作Prometheus指标标签。

插件只能提交工具进度/结果建议；任务终态、可靠seq和artifact.created由宿主确认/写入，插件不能通过自发事件越过任务账本。公开事件是安全投影，不把日志原样推SSE。产物记录含artifactId、tenant/task、producerInvocation、hash/size/mediaType/保留期；下载由宿主鉴权，工具不能返回任意宿主绝对路径作为交付。

## 7. 依赖、配置和数据迁移

- 一个Spring AI BOM管理对应依赖，所选AI链不混主版本；若切Alibaba，整套组合按主稿备选重新验证。不以动态版本、SNAPSHOT、milestone作为生产锁定版本。
- Maven Enforcer执行JDK/Maven约束、dependencyConvergence及禁用不允许制品；与Boot/BOM的已知兼容冲突按具体artifact记录，禁止全局关闭规则。CI保存effective POM与dependency tree，版本变更PR说明影响/验证/回滚。
- dev/test/prod分环境，公共默认值必须安全；秘密从目标平台合法绑定的secret设施提供，不提交 `.env` 实值、私钥或模型凭据。测试使用fixture，不调用真实收费API作为默认CI门禁。
- 迁移采用Flyway等单一选定工具，独立从V1开始；已发布脚本不可修改，修正写新版本。使用兼容新增与滚动升级，不在仍有旧worker时删字段/改终态含义。
- 数据库方言、领取语义、索引由实际访问模式确定；插件任务与artifact归属包含tenant约束。选定一种关系库后再锁驱动/版本，不预先实现所有数据库。
- isolated worker镜像/工具二进制用内容摘要或准确版本，安装来源/许可/健康检查可核对；未准入版本不得从远端自动拉取执行。

## 8. 本地与CI一致的自动门禁

选择如下工具；构建插件的准确版本由骨架PR确认支持JDK21/Boot4/所选测试环境后固定，本稿不伪造尚未运行的版本兼容结论。

| 门禁 | 所选机制 | 阻止什么 / 不声称什么 |
| --- | --- | --- |
| 格式与导入 | Spotless + Google Java Format | 格式漂移、通配符/导入规范；不替代功能审查 |
| 静态正确性 | SpotBugs Maven Plugin（固定高置信正确性规则） | 空值/资源等已配置可识别问题；不声称自动证明所有并发/授权安全 |
| 模块与危险调用 | ArchUnit/JUnit5 | 上述禁止边、循环、非准入位置直接进程/线程/退出调用 |
| 依赖与工具链 | Maven Enforcer、BOM、Wrapper | JDK/Maven不匹配、冲突依赖、不允许预览/浮动版本 |
| 单元风险测试 | Surefire / JUnit5 | 状态转换、权限交集、预算、lease/token、错误映射；不为纯DTO/静态配置堆镜像测试 |
| 插件契约 | 参数化共享suite，fixed Harness fixtures | 参数/结果/错误/取消/生命周期/权限边界；每个注册插件必须参加 |
| 集成 | Failsafe / Testcontainers + 受控HTTP/worker | 真应用、真实选定DB、恢复/租户/产物/SSE；mock结果不等于外部模型验证 |
| 制品/启动 | Boot打包、控制角色/worker角色最小启动与readiness | 装配错误、缺配置、manifest发现错误；没有生产部署声明 |

规范命令由pom绑定阶段，使**唯一受理命令**为：

```text
Windows: .\mvnw.cmd -B -ntp verify
Linux/CI: ./mvnw -B -ntp verify
格式修复: ./mvnw spotless:apply
```

`verify` 必须实际绑定check、静态检查、ArchUnit、unit/contract、Failsafe及packaging；不能写了命令而把插件只配在开发者自愿profile。统一集成测试资源前提并在CI提供；缺必需Docker/DB时明确失败/阻塞该验证，不静默skip后宣称verify通过。已有外部模型实测独立标注，不把真实API密钥放入默认PR测试。

### 骨架验收必须演示的负向门禁

| 故意注入的违规（仅测试分支/副本） | 应失败的检查 | 修复后的证明 |
| --- | --- | --- |
| kernel类import具体WebFetchPlugin | ArchUnit kernel依赖规则 | 去除反向引用后通过，保留正向接口装配 |
| Controller直接new HTTP客户端调用工具 | Controller/危险调用架构规则 | Controller只调用发布用例；网关审计可观察 |
| 未格式化或通配符import | Spotless/check | format后相同源程序通过 |
| 重复插件ID、错Schema、输入/输出超限 | registry/contract suite | 不能激活/不能执行；安全失败code且无实际外部请求 |
| 无tenant限制查artifact或猜他人task | 真实双tenant集成 | HTTP/内部网关拒绝，不只验证展示过滤 |
| worker带过期token提交终态 | 恢复/lease集成 | 旧写者拒绝，新lease继续；未重放副作用 |

负向样例不留在生产源码；保留原始退出码/日志/修复后结果作为骨架证据。清洁工程必须有真实assertion被执行，防止“0项测试”被当成门禁通过。首期不设机械全仓覆盖率承诺；关键风险路径的证据优先。

## 9. 协作、审查与ADR

- `AGENTS.md` 写角色边界、只在任务范围修改、必读工程规范、真实验证报告、凭据/环境约束与构建命令；`CONTRIBUTING.md` 解释给人的本地构建、规范、插件接入/测试和PR流程。两者引用同一工程规范，避免各自维护相互冲突的长清单。
- 工作分支围绕单个事项；提交标题建议 `type(scope): concrete change`，保留用户配置身份。PR给具体触发问题/变化、契约/迁移差异、真实验证与未验证项；锁定评审SHA，不用“功能支持”代替证据。合并、部署按具体任务授权推进。
- 审查检查：依赖方向、授权在真实执行处、插件状态/版本/Schema、deadline与资源关闭、重复/未知副作用、租户/产物访问、异常/日志秘密、数据库事务与兼容、真实测试。按问题风险分阻塞与建议，不为同一已有效证据要求反复跑无关全量检查。
- 规范例外要具体：规则、位置、理由、影响、验证与有效范围。不能整包放行或关闭全套门禁。涉及核心权限/隔离/共享资源由产品经理协调；普通代码组织/适配细节在已授权范围内处理。
- ADR只记录需要长期解释的取舍：框架/运行循环、执行隔离、数据库领取与fencing、插件API兼容。日常重命名/格式修复不强制ADR。

ADR最小格式：

```text
编号/标题/日期/状态：proposed | accepted | superseded
问题与当前规模：具体需求、约束、已核验版本
决定：采用什么，在哪些模块/运行边界生效
备选与依据：成本、能力/证据差异、切换条件
影响：依赖、接口/数据兼容、隔离与资源
验证：实际结果或待执行计划，不能混写
后续：明确前置条件/负责人岗位/替代ADR
```

Harness更新先固定新SHA、比较真实工具注册/Schema/provider行为，更新迁移矩阵及fixture，再处理Java实现；不在构建时跟随master自动变化。插件契约或事件/数据变化先改文档和适用验收；破坏兼容的变更单列迁移与回退。

## 10. 本阶段输出和后续退出条件

本次交付了规则、目录、允许/禁止依赖、工具/manifest样例、构建与负向门禁计划；没有声称骨架、插件或CI已通过。

后续骨架事项完成需具备：确认的项目仓库、统一pom/toolchain、可编译样例插件、控制/worker最小启动、实际依赖/格式/静态/单元/契约门禁及负向证明、无秘密的配置、首个迁移脚本与受控集成资源约定。骨架达标再实现最小内核与真实任务；全量工具迁移按后端清单逐批推进，不把规范完成当作产品完成。

技术事实来源和版本哈希见配套技术证据稿及官方来源证据包；本文所有示例仍需在骨架PR中用真实依赖编译、格式化和验证。

## 11. 与统一主方案0.3同步的修订

- 首个业务任务为网页检索至报告下载。真实read/不存在或版本观察→write强制CAS→present不可变快照→核验/登记/发布后才能成功；旧租约不得发布，write完成不是报告交付。文件F01-a为P3底层，edit/glob/grep的F01-b在P4相应工具启用前完成，不宣称全六工具已通过。
- 标识统一invocationId；任务NEEDS_RECONCILIATION、调用OUTCOME_UNKNOWN与安全码EFFECT_OUTCOME_UNKNOWN按主稿§7分层。NO_EVIDENCE属于报告业务失败；INPUT_INVALID、ACCESS_DENIED、VERSION_STALE等统一映射及取消/终态竞争以同库CAS和实际效果为准。
- 主稿§1.3定义deadline/grace/lease/续租、重试、传输/解压/转换/投影/产物、配额和保留/游标边界；按具体单位作N−1/N/N+1和时间竞争验证。这些是起始政策，非已测性能。
- 完整默认verify必须执行当前实施阶段必需的Failsafe真实DB/存储/worker集成；缺资源失败，不以开发者自愿worker-it或快速check代替。记录真实测试数量、失败suite/缺资源阻断及修复后通过。未实现工具不预建空测试，已启用高风险能力不可省略集成。
- 阶段统一P0–P5，详细映射/业务W01–W08/实验E1–E8见主稿；B5/B6保留后续准入，独立复核尚未通过。
- manifest只维护plugin.yaml，JSON为派生注册视图；Schema源使用JSON，版本/摘要检查不可绕过。

同一canonical输出fixture示例（测试数据，不是真实抓取）：

```json
{
  "httpStatus": 200,
  "sourceUrl": "https://example.org/report-source",
  "content": "可用文本示例",
  "truncated": false
}
```

P1将该fixture交真实validator与FetchOutput序列化对照；删除content、加入额外text或将httpStatus改为字符串的变体必须拒绝。本文未执行这些Java/Schema运行检查。
