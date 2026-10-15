# XIAO-40 第一阶段：模型配置、快照、协议与预算

这是供独立审查的核心阶段，不是 P3 运行验收。继续使用 Spring AI 2.0.1 的唯一 ToolCallingAdvisor 循环，没有新增 SDK、BOM 或共享服务。公开网页 reader、五工具报告与 present 生产者修正属于接续阶段；当前非测试装配的网页执行明确返回 `PUBLIC_WEB_NOT_CONFIGURED`。搜索提供无网络、无凭据的 `DisabledSearch` 及 queries 契约，尚未安装/授权 `web_search`。

## 模型配置

配置只来自运营者。任务 HTTP 请求不接受 endpoint、profile、model、credential 或 evaluation run。以下是无钥本地兼容服务的配置结构，地址和型号必须由运营者提供；替身不证明真实本地推理。

```yaml
jimu:
  model:
    default-profile-ref: local-v1
    paid-dispatch-enabled: false
    allowed-origins:
      - ${JIMU_LOCAL_MODEL_ORIGIN}
    profiles:
      local-v1:
        version: 2
        profile: local-v1
        config-version: 1
        adapter: openai-chat-v1
        base-url: ${JIMU_LOCAL_MODEL_BASE_URL}
        model: ${JIMU_LOCAL_MODEL_ID}
        revision: ${JIMU_LOCAL_MODEL_REVISION}
        auth-mode: none
        credential-version: 0
        transport-policy: trusted-local-v1
        connect-timeout-ms: 2000
        request-timeout-ms: 20000
        response-bytes: 1048576
        stream: false
        max-output-tokens: 512
        output-limit-field: max_tokens
        output-margin-tokens: 0
        capabilities:
          call: true
          tools: true
          stream-text: false
          stream-tools: false
          usage-call: true
          usage-stream: false
          parallel-tool-field: false
          context-limit: 8192
          evidence: ${JIMU_LOCAL_CAPABILITY_EVIDENCE}
        paid: false
```

`tenant-profiles.<tenant>` 可在宿主配置绑定其他 profile；没有绑定时用 default。未知配置字段拒绝。base-url 去尾斜线后追加 `/chat/completions`，保留路径前缀；拒绝 userinfo/query/fragment、重复 completions 后缀、路径绕过及不在 allowed-origins 中的地址。cloud-https-v1 必须 HTTPS 且 paid=true；本地 HTTP 必须明确使用 trusted-local-v1 和独立许可 origin。禁止重定向，TLS 使用 JDK 默认的证书/主机名验证。每次派发独立 HTTP client，不缓存凭据或共享 Cookie。

Bearer 配置使用 `auth-mode: bearer`、`credential-ref: env:JIMU_MODEL_KEY_V1` 与 `credential-version: 1`。只读取进程环境，拒绝 CLI/普通 Spring 属性携带密钥；不提供外置明文 key 字段。none 模式禁止引用凭据，HTTP 不发送 Authorization。生产装配不读取 fixture credentials；测试装配的网页凭据与模型引用分开。启动/readyz 不触发付费生成，也不 GET 云服务根路径；仅检查配置、当前 profile 的秘密可解析性、数据库和存储。旧 profile 的秘密缺失会在该任务派发前明确阻断。

保留所有在途 profile 及秘密版本；只切换 default 新建任务。旧 profile 原地修改、缺失或撤销时报 `MODEL_CONFIG_UNAVAILABLE` / `CREDENTIAL_UNAVAILABLE`。环境变量版本名的内容不可原地轮换；发布新引用/版本，旧任务结束后再撤销旧版本。本阶段固定请求配置与运营者声明的 revision；真实服务是否承诺不可变模型权重仍待实证，不将任意 revision 字符串当成提供方保证。

普通兼容服务不发送私有参数。百炼可选非思考适配仅在 `capabilities.evidence: modelstudio-nonthinking-v1` 下接受 `provider-options.enable_thinking: false` 和 `provider-options.enable_search: false` 两项完整组合；字段直接放顶层。该字符串是显式适配声明，真实能力及平台适用性仍须独立验证。未知/开启内置搜索/保留字段覆盖均拒绝。未声明 parallel 字段支持时不发送该字段，宿主仍串行执行工具。

## 快照、迁移与恢复

control 在受理事务把完整非秘密 ModelSnapshot v2 与 SHA256 写入 task，同任务、幂等请求和 accepted 事件原子提交；尚未被领取的任务也固定模型。客户端请求摘要与模型配置分离，幂等重试按原始请求记录比较，切默认不改原任务。旧请求 Budget 未含 maxCostMicros 时按 null 读取，不使历史幂等重试冲突。

checkpoint v2 固定配置摘要、完整消息、实际无秘密 HTTP body 和 body 摘要；恢复核对原快照和当前工具投影产生的 body。M2 重用已存完整响应，后继 step 仍用原 task 配置。模型请求发送 `n=1`，输出字段、误差余量、stream usage 和能力均来自固定快照。

只新增 V4，V1–V3 原字节不变。V4 增加 task 模型快照/评测绑定/货币桶、model_dispatch 逐派发事实、evaluation_budget；扩大 step.model_version。schema gate 精确要求 V1–V4，旧/未来版本拒绝。

升级需停新受理，确认独立 worker 收敛后，由 control 迁移，再启动同版角色；不宣称支持旧新 worker 在线混跑。已完成旧 task、事件和产物继续读取；缺原始模型配置的旧未完成任务不回填当前配置，`MODEL_SNAPSHOT_MISSING` 阻止模型派发。回滚需停准入/worker、保留库和对象，禁止 V3 JAR 接管 V4，不提供破坏性 down migration。

## 协议和错误

HTTP 响应有界聚合、严格 UTF-8/JSON；call/stream 校验完整终止、工具 id 唯一、参数完整对象 JSON、授权工具、槽连续和上限。SSE 以事件聚合，支持末尾 `choices=[]` usage；没有 DONE、半段参数、length/filter/refusal 不执行工具。尚未实现实时 TTFT 测点，不能从缓冲区解析时间伪造首段延迟。

每次 dispatch 存独立配置/请求摘要、时刻、安全状态、分项 usage、提供方关联 ID、返回型号和 finish reason。输入/输出/缓存/推理用量分别可未知；total-only 不虚构分项。401/403、404、429 分别为 `MODEL_AUTH_FAILED`、`MODEL_NOT_FOUND`、`MODEL_RATE_LIMITED`；超时/网络/坏响应使用安全码，不转发原始 body/headers/秘密。请求派发后异常仍保留计次及 UNKNOWN 预留，无 SDK/应用重试。真实 paid step 最多一次派发，M1 无完整响应恢复不自动重发；fixture 保留原最多两次的恢复实验。

## 货币与筛选预算

真实付费默认关闭；实际平台、权限、适用价格及计费上界未确认前必须保持关闭。仅设置 paid=true 不会启用；还需显式 paid-dispatch-enabled、完整 price 和已存在的可信 evaluation run。评测 run 由迁移账户/可信运营流程预建，无普通租户创建/更换接口，也不会启动时重建清零。

price 包含 version、currency=CNY、input-micros-per-million、output-micros-per-million、fixed-micros、input-token-upper 和 bound-evidence。rate 为每百万 token 的整数 micro-CNY，按最坏实时价、附加费计算并向上取整。当前仅实现提供方硬性总输入/上下文上界路线，input-token-upper 必须等于固定 capability context-limit；该上界必须覆盖工具 Schema、消息与提供方额外计费，bound-evidence 同时说明总计费输出及误差保证。没有这个证明时拒绝启用，不能拿 JSON 字节估计冒充货币硬上限。模型输出最多512，线上发送上限减误差余量。P2 字节估计仅保留为无付费 fixture token 配额。

evaluation_budget 持久上限最多20次、1,000,000 micro-CNY。不同候选/worker/重启共享同一 run；派发前按 evaluation→tenant→task 锁顺序，原子计次、费用预留和 dispatch 意图。费用不超过 task/tenant/run 任一剩余额度才发送。调用失败和缺分项 usage 保留最坏预留，不释放、不记零成本。已知费用按实际向上取整结算；提供方超上界保留实际 usage/费用并 STOPPED 整个 run，阻止后续调用。计数不因失败/重启退回。可空 task budget.maxCostMicros 仅在该任务配置 CNY 预算时接受，只能收紧至1元以内；未配置仍400。

本阶段不提供真实付费评测脚本或默认平台地址，不读取 XIAO-35 凭据正文。测试的 paid=true 配置仅面向两个独立 loopback 替身，synthetic-v1 价格不是外部平台实际价。

## 验证与后续依赖

默认 `mvnw.cmd -B -ntp verify` / `./mvnw -B -ntp verify` 保留完整 P2 必需集成，另有 ModelCoreIT；缺 MySQL、Linux POSIX 存储或 JDK21 仍失败。`tools/p3_model_verify.py <jar> <evidence>` 可独立复现本地协议/快照/预算，所有资源在前台收集并 finally 清理。验证记录随固定 SHA 交付，不把替身当真实推理/真实业务证据。

集中待产品经理协调：实际平台文档和完整 base_url、地域/工作空间/快照型号权限、适用价目与完整计费输入/输出上界证据；搜索推荐单一 Exa 路线，需独立权限/凭据、明确 search 类型与结果数、禁用额外 contents/answer/research、确认适用单价和 USD/CNY 预算换算。不重复索要已提供凭据，不抓取搜索结果页代替授权。公共抓取将需 HttpClient5 可固定 DNS 与 HTML 解析依赖的兼容/许可证据，无新增付费资源申请。

后续完整 P3 仍需公共 HTTPS 抓取出口防护、真实搜索、read 观察/write CAS/present 生产者及授权 Markdown 下载、真实模型小额质量评测与独立审查测试。父任务保持 in_progress。
