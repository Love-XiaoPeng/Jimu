# Readiness 与插件目录独立切片（XIAO-64）

基线：`efba6c732f23634755402438f7d275502d8526a3`，tree `a360b902d47c1b60ca773c10fb119693e1edd74d`。本片是 B1 的实际实现迁移，不代表 XIAO-58 总重构完成。

## 代码与行为

- `kernel/readiness/application/ReadinessService` 移到 `kernel/readiness/service/impl/ReadinessServiceImpl`，继续实现既有 `Readiness`；保留插件数大于零且 probe 就绪才 UP、空目录短路探测的规则。
- `kernel/plugin/application/PluginRegistry` 移到 `kernel/plugin/service/impl/PluginCatalogServiceImpl`，继续实现既有 `PluginCatalog`；重复 identity/capability 的拒绝顺序、异常消息、发现顺序与 `List.copyOf` 不变。
- `api/ReadinessController` 移到 `api/readiness/controller`，局部响应 record 移到 `api/readiness/vo/ReadinessResponse`；保持 `/readyz`、200/503、role/status/plugins 字段及状态值。
- `bootstrap/RuntimeConfiguration` 仅改两实现的 import/new 接线；既有发布接口、ResourceProbe、Snapshot、ToolDescriptor 均未改动，无新增转发接口或旧 application 兼容壳。
- 跟随更新 `WebFetchPluginContractTest` 的 import/构造；补发现顺序、输入列表修改隔离、返回列表不可变断言。新增 `ReadinessTest` 覆盖空目录 DOWN/503/角色/数量及不探测资源。其余资源失效/恢复、角色和缺配置失败复用 `RuntimeIT`。

## 验证

环境：Windows PowerShell、Temurin JDK 21.0.12.1、固定 Maven Wrapper 3.9.9。仅私有临时 HTTP fixture 和测试 JVM，不需要数据库。

格式命令：`.\mvnw.cmd -B -ntp spotless:apply`，exit 0。

本片准确验证入口：

```text
.\mvnw.cmd -B -ntp verify '-Dtest=ReadinessTest,WebFetchPluginContractTest,PackageBoundariesTest,RefactoringNegativeGatesTest' '-Dit.test=RuntimeIT'
```

结果：exit 0，BUILD SUCCESS（2026-10-25 14:02:19 +08:00）。Surefire 16 项（ReadinessTest 1、WebFetchPluginContractTest 11、PackageBoundariesTest 1、RefactoringNegativeGatesTest 3），Failsafe RuntimeIT 3 项，均 0 failures/errors/skipped。Enforcer、Spotless、Checkstyle、编译、打包、SpotBugs（0 bug/0 error）均通过；初次编译实际覆盖 514 个生产与 237 个测试源文件。

RuntimeIT 验证默认非 durable 的无 DB 双角色启动、HTTP readiness 随资源失效/恢复和 worker 停止变化、超时诊断、缺少 provider-origin 启动失败。四个私有 JVM 均留下 `alive=false` 清理收据。测试制品 SHA256：`74f36c4bd6f49c5c56540a9e96ad19c2110fb31c09379c704bc667cb5ef38d1b`。原始两轮日志、JUnit XML、RuntimeIT 收据和 SpotBugs XML 随本次事项评论附件提供，不修改旧 verification。

首轮筛选使用 `-Dtest=ReadinessTest,*ContractTest,io.github.lovexiaopeng.jimu.architecture.*Test`，误纳入无改动 POSIX 存储测试：142 项，0 failures、4 errors、0 skipped，exit 1。首错 `WorkspaceEditContractTest.oldFormatPreservesCodecAndPinsRejectWrongFormat -> store:26 -> Files.setPosixFilePermissions` 抛 `UnsupportedOperationException`，其余三个错误同因。未改测试或放宽门禁；随后精确选择本片清单，不将首轮失败改写成成功。无变化 POSIX 存储矩阵未在 Linux 重验，亦不作为本片通过证据。

源码扫描 `rg -n 'kernel\.(readiness|plugin)\.application|PluginRegistry|new ReadinessService\(' src/main src/test` 无命中（exit 1）；旧实现已删除，无双实现。`git diff --check` exit 0。POM、全局门禁、持久化/session/事务/查询、冻结资源与历史证据均未修改。

## 集成与限制

本片独立分支 `agent/xiao-64-readiness` 从指定 SHA 开始。两次 `multica repo checkout ... --ref efba6c...` 均因 daemon 请求 `context deadline exceeded` 退出 1；通过本任务目录中的独立 Git 仓库定向 fetch 指定 SHA 继续，无需进入其他人的工作树或改共享服务。后续一次冗余 fetch 连接 GitHub 超时，不影响已成功取得并核对的基线。

交中级 A 在 PR22 当前支线 cherry-pick 本片完整提交；仅 `RuntimeConfiguration` 两实现的接线可能与集成后续改动相邻，冲突时保持本文规定的 import/new，其他 Bean 采用 A 当前版本。与 DurableAgent/宿主网关切片没有文件重叠，二者之间无强制先后；禁止把本片直接合并 master 或 PR22。集成后由 A 核对同版编译及必要验证，再按 PM 流程独审/QA/验收。

回退为撤销本片提交，恢复原实现位置和接线；无 DDL/协议/数据迁移。未运行 GitHub CI、部署、真实 Key/付费、MySQL 或全仓 V01–V12，不对这些范围作通过结论。
