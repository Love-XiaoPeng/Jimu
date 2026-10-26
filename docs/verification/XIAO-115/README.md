# Durable bootstrap 职责拆分

基线：`77e48a1a1d03b3849786c3d2edadb1aec9bb3ad8`，tree `436a4e4a1ebd5eacbe950191f7e977a03914eda2`。独立支线 `agent/b/XIAO-115-bootstrap`；仅交 B5 配置拆分，不写 PR22/master，不宣称 XIAO-58/60 总体验收完成。

## 配置归属与依赖

| 配置 | 工厂 | 保留在一起的依据 |
| --- | --- | --- |
| `DurableLedgerConfiguration` | modelProfiles、ledger、taskService、artifactService、artifactStore、imageStore、authenticator | Ledger 的模型配置与初始化、存储和身份边界，以及对外发布的任务/产物 Service 装配；普通存储仍为 Primary，imageStore 仍按名字可选注入 |
| `DurableRuntimeConfiguration` | boundaryProbe、durableAgent、mcpSettings、mcpRuntime、workerLoop、publicWebReader、operationThreads、nativeSearchRuntime | worker 组装 Fetch/Workspace/Image 能力；操作计数、native 可用性和 MCP settings 也由 control/Ledger 消费，不能将整类限制为 worker |
| `DurableEndpointConfiguration` | sessionSearchBodyFilter、durableReadiness、eventFeed | HTTP 请求体过滤、资源就绪与事件订阅的接入及关闭所有权 |
| `DurableControlConfiguration` | maintenance、supervisor | control 独占的维护与监督调度生命周期 |

根 `DurableConfiguration` 保留原类名、durable 条件和 `proxyBeanMethods=false`，用显式 `@Import` 支持直接注册入口。四个职责配置各自也保留 durable 条件，组件扫描到子配置时仍能正确关闭。扫描与导入并存由 Spring 按同一配置类去重，不再在根保留第二套工厂。

Ledger 工厂仍完整执行 control migrate → 全角色 validate → 原六参同 DS 构造 → 原顺序配置/敏感值收集 → check。没有抽出新的配置绑定对象、提前读取属性或拆开这段初始化。全部参数依赖、Qualifier/Optional、返回类型、Bean 名字、Primary 与各工厂条件不变；保留依赖先创建、消费者后创建的关系，不为互不依赖 Bean 增加人为排序。`LedgerPersistenceConfiguration` 的 DS/manager/UoW/MapperScan/拦截器及持久化、Runtime、Host/provider 执行正文完全冻结。

## 本地验证及范围

环境：Windows 11，Temurin 21.0.12.1，Maven Wrapper 3.9.9。最终命令：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=DurableAssemblyTest,PersistenceAssemblyTest,QueryViewAssemblyTest,PackageBoundariesTest' -DskipITs verify
.\docs\verification\XIAO-115\verify-relocation.ps1
git diff --check
```

最终定向 verify **exit 0：16 项、0 failure/error/skip，SpotBugs bugs/errors 均 0**；Enforcer、Spotless、Checkstyle、编译和打包通过。Failsafe 因显式 `-DskipITs` 跳过，不称完整 verify/真实 IT 通过。源码核对 exit 0（20 工厂/1106 冻结 blob）；本批 diff 空白检查 exit 0。准确交付 SHA/tree、变更 blob、最终 class/JAR 摘要与日志在本事项交付 manifest/附件，不把新总 tree 的变化当作旧模块重验。

`DurableAssemblyTest` 使用真实 Spring 配置解析和工厂方法注入，覆盖 durable=false/缺失；control/worker × images 开/关四个图；import+scan 去重；唯一 DS/manager/UoW 与同源 SqlSession；Primary 和 image 可选依赖；worker/control 条件；filter 的两个 URL/async；20 工厂的显式与推断生命周期；身份 split/正则先拒绝、尾分号及缺 key；test-mode 注入门禁；native/images/storage 必需属性首错；模型 fixture 门禁；Ledger role→control 迁移参数首错及 worker validate 前不读迁移凭据。

图测试用真实六参 Ledger 构造替换数据库初始化工厂、用拒绝任何访问的代理替换两个文件存储边界；读取并断言生产 init/destroy 元数据后，仅在测试里抑制三个调度器的 start。其余实际工厂全部解析并创建，关闭由容器执行。没有启动 worker、维护调度器、provider 请求或 native 工具；不以模拟装配代替真实私库/文件系统验收。既有 PersistenceAssembly/QueryViewAssembly/架构规则覆盖原持久化接线、配置传播和模块边界。

`verify-relocation.ps1` 从准确基线读取原文件，保留字符串/字符字面量逐 token 比较全部 20 个工厂的注解、签名和正文，逐工厂输出摘要与新归属；另批量比对其余 **1106 个生产/POM blob**。这证明未改变敏感值收集、Flyway/配置/check 顺序、workspace/MCP/image/native/provider 分支或属性读取表达式；它属于源码核对，不冒充这些路径的真实环境执行。

## 原失败与修正

首轮定向 test：15 项，0 failure/1 error，exit 1。图测试夹具先注册 MyBatis 自动配置、后扫描用户 DS 配置，导致其条件提前评估，测试供应的 Ledger 构造报 `NoSuchBeanDefinitionException: SqlSessionTemplate`。修正为先注册/扫描用户配置、后注册自动配置，模拟 Boot 的真实装配顺序；没有修改产品补偿或增加 DS。原 Maven 日志、Surefire 报告和受测测试 class 随交付证据保留。原失败后的 source 快照已明确标为 after-run，包含随后新增的 Ledger 首错测试，不冒称原执行源。

第二轮只重验新增测试：6 项全通过，exit 0；随后最终定向 verify 重验受影响组合。首次拆分辅助命令因把方法正文内的 mcpSettings 调用识别成工厂而停止；改为只识别签名后重新生成，未进入编译的中间文件不作为产品证据。

未跑旧完整业务/worker 矩阵、真实 Flyway/MySQL、实际 SecureImageStore/Workspace/native self-test，也未运行/启用/等待 GitHub CI。相关执行正文与持久化装配 blob 未变；由唯一集成 A/独审/QA 按本批实际差异决定必要的有界补缺。总体重构、独审/QA 与最终 master 合入仍待后续验收；T48 暂停。
