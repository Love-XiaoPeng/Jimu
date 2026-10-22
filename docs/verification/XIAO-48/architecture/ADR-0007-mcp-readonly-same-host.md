# ADR-0007：同机 MCP 资源只读接入

状态：架构决定，可按配套契约实施；不是产品验收、部署或真实外部接入批准。
日期：2026-10-22；事项：XIAO-48。由后端随实现入库到 `docs/adr/0007-mcp-readonly-same-host.md`。

## 基线与问题

GitHub API 实际 master 为 `a04bc49a047827b292c7929312ea8d1ae9e31afc`，tree 为 `74f134b2dbaa0c984256129fab2c41a9fb70a15d`。本次 Multica checkout 的缓存仍为 `fdb38138f60b92a556b276e6c3ef96d287a8d0f6` / tree `10b993f03fb45a5e334739c5b6227aa60ccdd80d`。API compare 确认 master 领先 3 个提交，8 个变更文件全部下载并核对 Git blob；其中 Java 变化只有 NativeRunner 的制品校验收紧。执行、任务、授权、模型和数据库接口与所检查缓存一致。后端必须以实际 master 新建/更新自己的唯一实现分支，不把本地缓存当最新 master，也不覆盖已有工作。证据见 `evidence/master-baseline.json`、`master-compare.json` 和 `jimu-master__*`。

现有唯一执行链为 `DurableAgent` → `ExecutionLedger.admit` → 受控 runtime → 结果提交。工具 pin 为 `ToolSnapshot(schemaVersion=1)`；checkpoint 为模型绑定的 v2/v3；库版本 V1–V14。`TaskController` 当前仅接受固定能力名，拒绝非空 resourceIds；`DurableAgent` 当前把所有非 web_fetch 工具送进 workspace；`JdbcLedger` 的 pin 重建也枚举 workspace。这些位置必须增加明确 MCP 分支，不能注册一个回调后绕过账本，或伪装成 workspace 工具。

固定 Harness 提交 `571902028a75c64c1d767f4ae5fd099485f0ddf0` 的真实 G02/T02/T03/T04 是模板列举、资源列举和 URI 读取。`tools.ts` 把 server/cursor/uri 转为 MCP 方法；`connection.ts:367` 起以调用者 signal 和 timeout 调用同一连接 generation；`render.ts` 将 binary 替换为文字说明。原始 JSON 仍可供程序调用。它不是普通 URL 下载接口，也不等于 MCP 动态工具接入。

## 决定

采用 **MCP 2025-06-18 的 Streamable HTTP，同机、预配置、资源专用 profile**。服务由受控验证驱动独立启动；产品 worker 只作 HTTP 客户端，不启动/安装服务、不执行命令。生产默认 `jimu.mcp.enabled=false`，无 profile 时不广告三能力、拒绝 MCP 任务。启用只有本机专有受控验证环境的意义；本轮不部署共享或生产服务。

客户端用现有 JDK21 `java.net.http.HttpClient` 和已在制品中的 Jackson；不引入 Node MCP SDK、Spring AI MCP 自动工具注册或另一执行循环。现有依赖继续固定 Boot4.1.1、Spring AI2.0.1、Jackson2 BOM2.21.7/Jackson3 BOM3.1.7、networknt3.0.1；实际 resolved 版本仍由后端的依赖树收据确认。本架构探针为 Temurin21.0.12.1。JDK HttpServer 只用于测试探针，不成为生产服务。

首批 profile 要求 sessionless；客户端接受 POST 返回 `application/json` 和 `text/event-stream` 两种形式，这是该协议的 MUST，不能仅支持 JSON 后称为 Streamable HTTP。GET 通知流不主动打开，受控服务对 GET 返回405。收到 `Mcp-Session-Id` 时拒绝本 profile，记 `MCP_PROFILE_UNSUPPORTED`；不降级、不重新发起原资源请求。后续接入需要持久会话、OAuth、stdio 或任意外部服务时再变更契约与独立验证，不能将本批称为任意 MCP 服务器兼容。

每个逻辑初次列举/读取重新 initialize → initialized → resource method；初始化只通告 `{capabilities:{}}`，不通告 roots、sampling、elicitation、工具执行或资源订阅。握手的 protocolVersion、serverInfo、完整 capabilities 与宿主审核快照一致后才读资源。服务器 instructions、_meta、描述和正文都是外部数据，不能进入 system 权限层。服务可宣告其他能力，但客户端不枚举 tools、不执行 tools/call、不执行服务发来的反向请求。

三份静态 manifest/Schema 的身份、输入、输出和摘要见 `contracts/catalog.json`。保留原模型工具名，不修改冻结的工具清单或旧 Schema。插件只依赖 `plugin.api` 的窄 `McpResourcePort`，传输放在 `adapters.runtime.mcp`，授权和持久事实通过 kernel 的 ledger API，装配留在 bootstrap。

## 传输和部署边界

只有宿主 profile 可指定 `http://127.0.0.1:<明确端口>/mcp`，不可使用 hostname、localhost、IPv6、userinfo、query、fragment、代理或重定向。实际数值端口还必须在1–65535。完整 URI、当前 host-id 和 profile 摘要必须完全匹配；模型只传 server 别名和资源 URI。客户端构造独立 NO_PROXY selector，`Redirect.NEVER`；拒绝3xx后不读取 Location。此适配器不复用/放宽 PublicWebPolicy、模型端点或生产 HTTPS 出网策略。

服务只绑定127.0.0.1，校验 Host 与非空 Origin，拒绝不受信 Origin；每租户/服务器使用独立秘密引用及版本，Authorization值仅在宿主凭据解析器中取得。客户端不跟随401挑战去发现认证服务器，不自动刷新/换凭据。资源 URI 是 JSON-RPC params.uri，永远不成为客户端 HTTP 请求地址或宿主文件路径。

受控服务应在测试驱动创建的专有目录下读取合成 fixture 文件，经真实 JSON-RPC 生命周期和方法分派返回；不得以产品内存 map 代替协议调用。服务无生产密钥、业务文件、Docker socket或其他服务连接；验证驱动记录服务PID、监听地址和专有根并在 finally 清理。worker 没有 shell/启动参数入口。本架构探针在 Windows 进程内启动两个专有 HTTP listener并关闭；它证明传输连接约束，不证明 Linux网络 namespace、任意外部服务隔离或生产防火墙。后端/QA仍需实际 product JVM 的目的地址/代理/跳转计数和服务进程边界证据。

启用 readiness：控制和worker均检查 V15、打包Schema摘要、完整profile/host-id/秘密引用；对其已配置专有服务作有界 initialize+initialized，校验capability快照，不读取业务资源。单服务总2秒、最多4服务；失败拒绝本轮启用/领取，不能退回不鉴权。profile配置缺失和秘密不可用不能被识别为普通未配置任务继续。禁用配置不连网络。新 profile 注册和授权由明确的测试驱动/运维身份写入，migration 不为任何租户默认启用。

回滚先关闭新准入并取消/排空本批任务、停止测试专有服务，保留 V15 和账本。旧二进制因严格schema gate拒绝V15，不能直接回退后领取；仅用仍支持V15且MCP禁用的制品运行既有任务。不得改旧snapshot、UNKNOWN、费用、锁或原持久服务；不删除业务事实作为回滚。

## 必要持久扩展与兼容

新增独立 `McpTaskBinding(version=1)` 和 `McpListSnapshot(version=1)`，不用升级原 ToolSnapshot、model snapshot或checkpoint；原JSON仍保持原字节。V15只新增task的nullable MCP绑定字段和四张MCP表；旧任务两列为NULL，按原路径恢复，不能回填今天的服务器。MCP任务绑定不能为NULL，且只有专门的MCP任务路径创建。细节、锁顺序和限额见配套契约。

MCP批次仅接受三资源能力的非空子集，resourceIds仍为空；服务别名由当前tenant/owner的宿主授权推导。禁止与write、present、read_image等混合。保持 BUSINESS report policy v1/v2 的旧规则，不利用新工具扩充旧评测scope或付费授权。受控纵切使用独立fixture租户、paid=false的既有本地模型替身；不修改已交付项目实例和账本。MCP-only任务只完成summary，不进入旧fixture路径的自动stage/publish，不给MCP内容报告发布权。

暂不把MCP内容写入工作区、转成图片或作为compose_report来源，因此不新增报告source类型，也不改变现有不可变产物下载链。输出含server、服务器版本、server快照摘要、资源URI和内容sha256，明确来源和截断。binary只返回类型/字节数/摘要，不把base64或图片media送入模型。

## 取舍依据

| 方案 | 当前代价/边界 | 结论 |
| --- | --- | --- |
| 受控 Streamable HTTP + JDK | 需实现窄JSON-RPC/SSE边界；不新增运行依赖、子进程或进程回收协议 | 采用；严格限定sessionless资源profile并验证真实协议 |
| stdio +受控子进程 | 需新增启动制品白名单、完整环境清洗、进程树/FD/namespace/死亡证明及恢复；现有原生服务默认禁用 | 本批不引入；不是因性能排除 |
| 完整Java MCP SDK/自动装配 | 依赖闭包、版本协商、自动工具/重试/会话行为均需另核；本批不需要全部能力 | 不为三只读方法扩大依赖和执行面 |

## 协议与许可证据

`evidence/sources.json` 记录18个原始文件的固定提交、Git blob和SHA256；Harness12份，MCP规范6份。规范文件固定在 `80041ab0a139a8f4fb3adbc99f7ae9113ea59671` 的 `2025-06-18` 目录，不依赖会漂移的网页最新版本。完整上游许可保留在包内。Harness第一方为MIT（Copyright2026DeepSeek）；上游Node client为2.2.0，仅作行为证据，未引入本制品。规范仓库正在MIT/Apache-2.0过渡，非规范文档另有CC-BY-4.0说明，不能把整个仓库一概写成MIT。本批独立Java实现不复制其SDK，运行时新增依赖数为0；后端仍提交实际SBOM差异及许可收据。

探针的真实原文、失败历史和限制见 `VERIFICATION.md`。架构接受不代替后端实现、同完整SHA独审、QA和产品业务验收。
