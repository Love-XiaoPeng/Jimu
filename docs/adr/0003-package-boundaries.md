# ADR-0003：单制品包边界、装配和执行角色

日期：2026-10-15。状态：accepted（沿用已验收架构并明确实现边界）；范围：XIAO-34/P1。运行验证状态：未执行。

## 问题与决定

首期规模是单一 Java 应用制品与独立 Java worker，当前没有独立SDK发布、微服务容量或跨团队独立版本需求。沿用一个 Maven 项目，以 `io.github.lovexiaopeng.jimu` 下的包与 ArchUnit 约束模块；控制与worker是同一制品的不同启动角色，按基线在独立进程执行。包隔离不等于沙箱。

按实现实际创建包，不生成全量空模块或空Service/Manager。插件API在真正需要独立发布或构建隔离有证据时再抽制品；因此契约现在就不能引用宿主内部类型。

## 包职责与允许依赖

下表包名均相对于新根包；其他模块间调用只能经发布的 `api`，模块自身对外部设施的依赖在自己拥有的 `port` 表达。

| 包 | 职责 / 允许依赖 | 禁止依赖或行为 |
| --- | --- | --- |
| `plugin.api` | Manifest/Descriptor、ToolPlugin、ToolResult、不可变ExecutionContext与限权能力契约；JDK及明确轻量契约 | Spring/AI、JSON框架类型、kernel实现、具体插件、Entity/Controller；自签tenant或授权 |
| `model.api` | 类型化模型请求/响应/usage与稳定端口 | Spring AI/Alibaba类型、具体工具、Controller或持久对象 |
| `kernel.<职责>.domain` | 本领域状态/约束、JDK、明确的稳定ID类型 | Spring/SQL/持久实现/AI/具体插件/API Controller |
| `kernel.<职责>.application` | 本模块domain/port、其他模块发布api、plugin/model契约 | 其他模块内部application/domain/port实现或Service/Entity/Mapper，具体adapter/plugin |
| `kernel.<职责>.api` | 发布窄用例/命令/结果 | 用宿主内部实体或可变安全上下文充当跨模块契约 |
| `kernel.<职责>.port` | 本模块拥有的仓储、调度或其他外部设施接口 | 暴露具体第三方SDK、DAO/Entity；供其他业务模块绕过用例直接操作 |
| `api` | REST/SSE/下载、HTTP DTO与认证映射，调用kernel发布api | DB、具体工具、直接模型客户端、启动执行线程 |
| `execution` | 单一运行链的用例/步骤协调，调用kernel公开api和model/plugin契约及自身port | 具体Spring AI/Graph类型、具体插件/adapter、数据库Entity、直接工具body |
| `plugins.builtin.*` | ToolPlugin实现、工具专属DTO及窄provider端口；调用限权端口 | kernel内部、adapter实现、任意Repository/HTTP/文件/秘密访问，全局currentUser |
| `adapters.ai.springai` | 实现稳定模型/运行链端口，将Spring AI和模型协议限制在此 | 创建第二个治理/重试/工具循环，直接调用插件body或绕过网关 |
| `adapters.persistence/storage/runtime/telemetry` | 实现对应模块拥有的port，依赖对应公开契约/必要领域类型与受控库 | 替代宿主最终授权、跨租户无约束查询或反向要求domain依赖实现 |
| `bootstrap` | 角色选择、Bean装配、配置绑定与校验，可引用装配所需实现 | 任务或工具业务分支；被其他生产包反向依赖 |

`kernel.plugin` 管理安装/启停/版本等宿主用例；`plugin.api` 是可抽取的扩展契约，两者不是一个包。task、invocation、artifact、event、budget、dispatch等kernel职责只在当前阶段需要时落地。

运行循环的策略和持久边界归 `execution`；`ToolCallingAdvisor`/`ToolCallback` 等具体实现归 `adapters.ai.springai`，通过稳定端口与宿主协作。这样符合“execution不依赖具体adapter”和“只用一条循环”两条约束。回调经 `kernel.invocation.api` 的网关入口发起调用，不能从容器查找并执行具体插件。

provider端口由真实能力的拥有方定义，例如webfetch的 `WebPageReader`；具体受控HTTP实现放 `adapters.runtime`，由bootstrap注入。端口签名只含稳定契约/必要工具DTO，不暴露Spring或HTTP客户端。若端口在工具包内，adapter仅可引用该明确端口及必要DTO；不能将整个插件实现包视为公开依赖。插件不得反向import该adapter，因此不形成循环。共享能力出现后才按实际职责抽取，不能提前造万能provider层。

## 不可绕过的执行边界

所有内置/旧回调/嵌套调用统一经宿主网关检查身份、租约、安装epoch、固定版本摘要、Schema、权限和预算，再记录意图执行。插件只提交进度/结果建议；任务终态、可靠事件seq和artifact发布由宿主账本确认。HTTP DTO、领域对象、DB对象、模型投影和插件canonical输出分开。

P1可信JVM样例只来自审阅并随制品发布的实现，不接受普通租户任意class/JAR。需要Node/Python/原生命令的能力按基线进入独立受控runtime；没有隔离证据时不启用，不降级到宿主。不因本 ADR 新增共享部署、broker、Kubernetes或收费provider。

## 自动约束、验证与例外

ArchUnit至少覆盖：两个叶子契约包的外部依赖；kernel/执行层不依赖具体adapter/plugin/AI；domain无框架；跨模块只用发布api；Controller不直调工具/数据库；插件不查宿主或创建任意客户端；非准入位置不创建线程/进程或退出JVM；业务包循环。bootstrap作为装配入口可引用具体实现，但无业务，且无反向依赖。

规则扫描新根包下真实生产类，不能复制旧 `io.taotie` 规则得到零扫描。先证明合法 `WebFetchPlugin -> ToolPlugin`、provider实现与bootstrap装配能构建，再在临时副本注入 `kernel -> WebFetchPlugin`、`Controller -> 具体工具/DB` 等违规证明verify确实非零退出。调用型风险的规则须覆盖实际采用的客户端/工厂路径，不能仅禁止一个类名后宣称所有旁路已封闭。

完整默认verify绑定当前阶段必要的真实worker/DB/存储集成；双角色readiness按各自启用能力所需资源判断，缺必需资源失败。P1只证明已实现边界，P2租约恢复、P3网页业务以及后批高风险能力按原阶段验收，不预造空测试或扩大本轮范围。

单制品的代价是无法靠Java编译器天然阻止所有越界，必须依靠真实类扫描、代码审查和契约/集成验证。多模块/微服务可提供更强构建或发布隔离，但当前会增加版本和装配成本，暂不采用。必要例外须精确到类型/规则/位置、理由、验证和有效范围，不能整包豁免。真正出现独立SDK发布、依赖冲突或资源隔离需求时，再以证据替代本ADR。

本轮完成静态架构决定；没有编译Java、运行门禁或验证部署。交后端统一写入仓库，并在PR给出固定提交及真实结果。
