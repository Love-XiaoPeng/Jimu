# XIAO-36：P2 架构交接与资源证据

日期：2026-10-15。结论：**可进入后端实现，无需新增核心目标、权限或共享资源决策**。决定见 [ADR-0004](../../adr/0004-p2-durable-execution.md)。这里把实际取得的证据与后端待实现、待执行的验收分开；不是 P2 完成报告。

## 当前基线与契约差异

- 远端 `refs/heads/master` 与检出 HEAD 均为 `5439b517de6c8755810168a928b63a5bfd5d1948`，包含 P1 PR #1；检出时工作树干净。本轮独立分支由 Multica 创建，PR base 为 master。
- 已读父事项 XIAO-35 的完整范围/验收，以及 XIAO-34 的产品验收和独立复验结论；父项和本项当前没有评论线程。开始时 family runs 只有本架构任务，没有运行中的后端/测试。本项只增加文档，不实现后端纵切。
- 现有 `RuntimeConfiguration` 装配 web.fetch、Schema/目录和基于 HTTP 的角色 readiness；`ToolDefinitionMapper` 只构造工具定义。没有 task API、ChatModel/ToolCallback/ToolCallingAdvisor、DB/迁移或存储；P2 应在这些真实职责上增加实现，不能把现有 readiness 当作恢复实验。
- `ExecutionContext(invocationId,deadline,cancellation)` 保持叶子插件契约；tenant/actor/fence/epoch 留在不可由插件构造的宿主上下文，限权 provider 由宿主绑定。`ToolResult(output)` 仍是建议，不能决定 task/event/artifact 终态。不为鉴权把数据库实体或安全框架类型塞入插件 API。
- 保留 `jimu.web.fetch@0.2.0`、调用名 `web_fetch`、权威输出 Schema URN 与摘要 `08a144489ed9ea37b5b33a5ffdc815237f31d4f229b76ba1d5e465b9e13f9ff8`。其四字段 `httpStatus/sourceUrl/content/truncated` 不变；P1 的1MiB受控输出限制不冒充 P3 全量抓取行为。
- 新增契约为任务/取消/事件/产物 API、宿主调用上下文、账本/预算/存储端口及 V1 迁移，具体请求/响应/失败/索引/状态见 ADR。P1 没有持久数据或旧任务客户端，初始迁移无需搬运业务数据。历史基线和 ADR-0001–0003 原字节保持。

## Spring AI E1 扩展点证据

从 Maven Central 实际取得以下 **2.0.1** sources JAR，并成功解析 `spring-ai-client-chat:2.0.1` 的传递依赖（`resolve-chat.log`，退出0）。源码路径均相对于对应 JAR 的根，行号来自本轮下载文件；不是 AI1.x/Alibaba 文档推断。

| 制品 | sources JAR SHA256 |
| --- | --- |
| spring-ai-client-chat 2.0.1 | `fca5c4e9c4f449f9ebcec3ff59e1edf93b8f35653954f1a584c7019e63e55b4a` |
| spring-ai-model 2.0.1 | `6d26fb85d2264ba78ba7a97b51b052f899830718e9f2f38b56cb6cba202e7072` |

来源 URL 为 `https://repo.maven.apache.org/maven2/org/springframework/ai/<artifact>/2.0.1/<artifact>-2.0.1-sources.jar`。依赖仍使用项目唯一 AI BOM，不修改 POM 或叠加框架。

| 必要能力 | 代码位置与含义 |
| --- | --- |
| 每轮请求前/完整响应后拦截 | `org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java:123` 的 adviseCall；158调用 doBeforeCall、167调用内层链、169调用 doAfterCall，之后才在182调用 manager。246/250为 protected hook，可在事务完成前阻止继续执行 |
| 唯一循环与可恢复消息 | 同文件196/211以 manager 结果构造后继历史；222为 doGetNextInstructionsForToolCall，241为初始化钩子。内层 CallAdvisor 可以对当前已存响应返回恢复值；`advisor/api/CallAdvisorChain.java:54` 提供 copy(after) |
| 工具意图/逐槽结果 | `org/springframework/ai/model/tool/ToolCallingManager.java:41` 的 executeToolCalls 接口；advisor builder在520接收自定义 manager。`DefaultToolCallingManager.java:237` 起逐工具槽调用，328调用 ToolCallback，337构造与 provider callId 对应的 ToolResponseMessage；可用宿主 manager/网关包装，不需要改框架源码 |
| 未知/治理错误停止 | default manager在330只捕获 ToolExecutionException 交异常处理器；advisor在184只特别处理次数超限。宿主停止信号须穿透，不转成模型可重试的普通工具文本 |
| 流式完整响应边界 | advisor的279–400处理流聚合/递归，425/437为流式前后钩子，462为后继历史。token delta不是可恢复响应；P2须单独验证聚合完整后落库和取消时资源关闭 |

这证明主选具有所需扩展位置，**尚未证明本项目恢复实现正确**。不触发 ADR-0002 的切换条件。后端必须用一个 ToolCallingAdvisor、关闭 ChatModel 内部工具执行，通过宿主网关委托真实插件。框架次数限制不替代持久预算，也不要依赖全局 callback resolver 找到未授权 Bean。

当前恢复路径：恢复当前 model step 请求检查点→已有完整响应则从库返回→manager 对逐槽成功 invocation 返回记录结果→仅对明确未派发的槽准入→恢复/生成下一步骤。禁止从初始任务再跑整个 Agent。恢复消息须保存原始 tool_calls/callId、canonical结果及投影版本，不能只保存最后一句 assistant 文本。

## 本机资源和隔离

本轮只用 Ubuntu WSL2；没有连接共享数据库/Redis/对象存储等服务，没有读取其凭据，没有修改 FRP。Docker daemon 的现存服务保留。创建的 MySQL 测试容器、HTTP fixture、三个 Java 子进程都在同一前台探针内收集并清理；不留跨回合后台工作。

| 资源 | 选定方式 | 适用边界 |
| --- | --- | --- |
| DB | 新建专有 MySQL **8.0.46** 容器，镜像固定 `mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`，InnoDB，`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`；只映射127.0.0.1随机端口；专有 ext4 数据目录、随机测试密码 | 不用现存3306，不启动 compose 里的其他服务；本次独立协议实验采用该版本，不作为生产版本准入；P2实际迁移/JDBC连接和恢复另验 |
| 存储 | WSL原生ext4上的 `mkdtemp` 专有根，按tenant分区；fsync/摘要、create-if-absent不可变快照 | 不用 `/mnt/c` 模拟Linux原子性，不把宿主路径交用户；不宣称分布式对象存储或沙箱 |
| Java | 任务私有解包 Ubuntu JDK/JRE21.0.12.1，不改系统 Java17 或 alternatives；三个 native Linux JVM运行同一P1 JAR | Windows完成的构建产物可在WSL运行；WSL后端构建另用相同Wrapper，不用系统Maven |
| HTTP fixture | Python标准库、127.0.0.1随机端口；受控 `/v1/chat/completions` 返回无外部调用的固定OpenAI形状响应 | 当前探针仅证明HTTP资源；P2后端补工具槽/stream/中断/响应丢失脚本，真实provider未验收 |

使用已安装 Python3、Docker29.9.0 和现有镜像；JDK21下载只解包到本任务目录。Adoptium API 请求实际403，随后使用 Ubuntu已配置软件源的精确版本包解包成功；没有将网络403当作主选不兼容，也没有安装/改变共享系统包。包摘要和版本保存在 `jdk-result.json`。

资源运行结果以附件 `resource-result.json` 和三个 `p1-*.log` 为准。该探针验证：MySQL实际版本、InnoDB锁屏障与SKIP LOCKED排除已锁行、提交行跨容器重启仍存在、专有存储fsync/hash及不可覆盖、fixture固定响应、两个worker和一个control独立PID与 `/readyz`；清理后容器/专有数据根/三个JVM/fixture线程均已退出。它没有把P1进程接入任务DB，因此不能视作E1/E3/E4通过。

三个native JVM的PID为773735/773905/774091，均返回status=UP；MySQL当次loopback端口43255，fixture端口43298。独立二次检查 `independent-cleanup.json` 确认这些PID不存在、全部5个探针端口已关闭、本事项标签容器数0；`findmnt -T /tmp` 确认为ext4（`stat -f` 的ext2/ext3是文件系统家族名）。这些地址仅是已结束实验的记录，不能作为仍在线服务入口。

独立执行 Windows `mvnw.cmd -B -ntp verify` 退出0：**31项，0失败/错误/跳过**（架构1、来源1、UTF-8 17、插件契约10、真实进程集成2），SpotBugs 0问题。`p1-verify.log`、JUnit XML、进程收尾文件随附件。未改生产代码，未重复此前未变的R4/负向门禁全部用例。

## 后端第一条纵切

1. 增加 V1 迁移与 JDBC 仓储、身份映射和幂等受理；同请求跨control重启得到相同task，未提交不能202。control/worker readiness校验schema版本、DB、storage和各自必需配置；控制受理不依赖某个单一worker在线。
2. 两个worker使用同一专有库领取；实现续租/CAS、持久预算和唯一调用网关，先运行受控ChatModel→web_fetch→持久结果。固定插件/Schema摘要，调用provider只能到fixture。
3. 在“工具成功已提交、下一轮请求前”杀掉当前worker，用第二worker恢复；查DB关联、HTTP计数和provider计数，证明成功输出来自记录、插件没有第二次执行、只有新fence可提交。这是第一条可审阅纵切，不等待所有后续链路才开PR。
4. 补其余持久边界/取消/未知效果；随后接SSE和产物协议，统一同库事实/鉴权/预算。独立审查和测试锁同一head，不把下表计划写成运行通过。

## E1/E3/E4 故障注入与可观察断言（待实现）

所有 failpoint 只存在测试装配，由独立测试控制屏障；不能通过公网API任意触发。测试父进程持有准确 child PID/worker_instance_id，受控终止并在 finally 收集进程/fixture/资源；只操作专有库和目录。不得结束系统Java、Docker daemon、Multica daemon或现存服务。

| 实验/注入点 | 必需断言及原始证据 |
| --- | --- |
| E1 M0 受理事务前后、模型请求保存后/发送前 | 202只对应已提交task；同key并发和跨control重启只有一个task；摘要不同409；无PREPARED记录时fixture请求计数0 |
| E1 M1 已发送/完整响应落库前断进程或丢响应 | 没有完整响应不得执行工具；旧费用unknown不记0，新派发另预留；不能重用已经产生工具意图的步骤生成另一响应 |
| E1 M2 响应提交后/意图提交前 | 新worker从同一响应/tool槽恢复，不再次调用该轮模型；快照包含callId/模型配置/能力/usage，delta不能替代完整响应 |
| E1 T0 意图提交前、后/执行前、执行后/结果提交前 | 未提交意图不执行；RUNNING无结果不能推断未发生；可查询假服务用effectIntentId核对，无幂等且响应丢失时UNKNOWN+待核对，执行计数保持1 |
| E1 T1 结果提交后/框架拿到结果前；多工具中间槽崩溃 | 已成功插件计数不增加；恢复canonical结果和固定投影，部分批次仅处理未确认槽；禁止整Agent/整批重放 |
| E1 M3 下一轮检查点提交前后，最终响应提交前后 | 由响应+结果确定性重建历史；不会丢失或重复工具结果；跨进程按8/20持久限制停止。call和stream分别覆盖，发现只含授权工具 |
| E3 两tenant并发及同tenant不同actor；缓存预热后交换身份 | 发现/执行/查询/历史/事件/产物/资源凭据均拒越权；跨租户猜ID统一404；fixture收到的凭据/资源标记不串租户；当前检查点不跨任务混入 |
| E3 创建callback后禁用installation/撤权，再调用旧callback或下载 | epoch递增，发现不见且旧callback准入拒绝；插件/fixture计数不增加；已开SSE及时断开，代理下载重验拒绝；不能仅清发现缓存 |
| E3 在配额N−1/N/N+1并发预留，预留后崩溃/取消/重复结算 | 账本始终 committed+reserved≤limit；同reservation不重复扣/退；实际派发次数已消费，缺usage保留unknown/estimate；确认停止后的运行/未用预留回收 |
| E4 两worker同时领取、续租等于到期边界、旧callback晚到 | 同task只有一个当前fence；旧者不能续活/提交/发布；旧观察不覆盖新事实；记录每次CAS影响行数和所有权变更，fence严格递增 |
| E4 cancel先于成功、成功先于cancel、cancel后迟到产物/未知效果 | 按同库提交顺序收敛；202不等于CANCELLED；取消先提交后无新准入/发布；unknown优先待核对；终态不被迟到结果改写 |
| E4 SSE断流、通知丢失、control重启、慢消费者、过期/未来游标 | 按seq重放，无丢失/重复fact或跨租户数据；不增加attempt/模型/工具计数；旧游标410、未来400；队列超限只断订阅 |
| E4 上传中断、快照就绪/DB登记前、发布事务后掉响应 | 未发布对象有uploadId可核对/清理；PUBLISHED引用真实hash/size/producer；重复发布不新增对象或事件；旧fence/cancel不能发布 |
| E4 DB/存储/fixture缺失、错migration/payload版本 | 当前必需集成明确失败、进程readiness不合格；不silent skip，不使用内存/共享服务兜底；进程/端口/容器/临时根清理证据完整 |

unknown效果的正反例借用基线E6最小假服务机制，仅覆盖P2所需边界，不宣称全量E6、高风险工具、E5/E7/E8或P3/P4验收。每条证据关联 task/attempt/step/invocation/dispatch/effect ID、fence、event seq、artifact hash和fixture计数；公开日志脱敏，完整参数/正文不进事件。

## 启动、验证与清理命令草案

以下是后端应实现的命令契约，**仓库目前还没有这些P2配置、IT类或脚本**；需在后端PR提供真实可执行入口。不得复制不存在的测试名然后用0测试声称通过。

```sh
# WSL，使用任务私有JDK21和项目Wrapper；独立测试资源由IT创建/收尾
export JAVA_HOME="$JIMU_TEST_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp verify

# 新增的P2 IT约定名；需先实现且默认verify也必须执行
./mvnw -B -ntp -Dit.test=DurableExecutionIT,TenantBudgetIT,RecoveryArtifactSseIT verify

# 手动定位只使用本次专有配置；控制先完成迁移，两个worker仅校验
java -jar target/jimu-0.1.0-SNAPSHOT.jar --jimu.role=control \
  --spring.config.additional-location=file:"$JIMU_TEST_CONFIG/control.properties"
java -jar target/jimu-0.1.0-SNAPSHOT.jar --jimu.role=worker \
  --spring.config.additional-location=file:"$JIMU_TEST_CONFIG/worker-a.properties"
java -jar target/jimu-0.1.0-SNAPSHOT.jar --jimu.role=worker \
  --spring.config.additional-location=file:"$JIMU_TEST_CONFIG/worker-b.properties"
```

三个启动命令由测试父进程作为子进程持有，不用人工后台启动再交付。配置含专有DB URL/最小权限运行账号、storage根、fixture origin、身份测试配置、worker实例和独立loopback端口；密码不进CLI/仓库/原始日志。迁移使用单独DDL身份，运行账号只具必要表DML，不能访问其他库。DB/storage/fixture默认测试必须由本地和CI的同一IT夹具建立，资源缺失非零退出，不能通过可选profile跳过。

本轮实际复现资源探针的入口在附件：`wsl -d Ubuntu -- python3 <本任务evidence目录>/prepare_jdk.py`，然后在P1构建成功后执行同目录 `resource_probe.py`。prepare脚本只私有解包JDK，不改系统；resource脚本临时容器随机命名/端口、finally停止本次准确子PID并删除本次容器和ext4根。附件保留脚本/日志/JSON，未打包JDK、模型依赖缓存或数据库数据。

P2父进程的清理顺序：停止新准入→请求专有子进程退出→限时后只终止本次确切PID并wait→关fixture→导出日志/DB事实/对象清单→移除经标签和ID核实的本次容器→校验规范化专有根再删除。故障失败也必须进入finally；保留证据置于清理目录之外。手动清理禁止泛化 `docker system prune`、进程名kill、全机WSL关闭或扫描删除他人根目录。

后端回传固定提交、base master PR、实际依赖/迁移版本、完整verify退出码、测试数量和上述逐边界证据；独立测试未完成前不宣称框架E1正式锁定或P2验收。此后由产品经理核对架构交付并启动已安排的后端/审查/测试链，本项不直接触发这些角色。
