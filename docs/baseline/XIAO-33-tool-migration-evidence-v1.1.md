# 饕餮：DeepSeek Harness 工具迁移证据稿

版本：合稿修订1.1，2026-10-15；事项XIAO-33；后端研究v1原取证，架构师按统一主方案0.3合稿。保留源码事实/完整输入Schema及成本，修订业务首场景、阶段、ID/状态和门禁口径；不代表后端新运行验证。原研究v1作为历史证据保留。

## 1. 结论、边界与证据强度

结论为**有条件可行**。Java 可实现任务控制面、类型化插件契约、文件操作及状态工具；MCP/LSP 可协议适配；Bash、PTY、浏览器、PTC/JS、原生搜索和文档渲染应由隔离 worker 执行。Harness 的 TypeScript/Cordis 插件不能作为 JAR 原样加载；“迁移”主要是保留 Schema、行为和测试基线后重写适配层，部分保留 MIT 文本/独立执行依赖。不能把全套桌面/本地执行底座直接当作多租户服务部署。

成立条件：建立不可被插件禁用的租户与授权内核；固定版本的插件契约；版本化工作区；持久任务、事件和产物；worker 身份/租约/资源边界；原始行为与云端差异的验收。优先以Boot模块化单体＋独立worker评估，首期只开放网页报告五工具；全部72注册项仍属分批迁移总范围，尚未实施。

这是从零开发的饕餮研究，唯一工具来源是 DeepSeek Harness。当前未绑定目标代码仓库，本轮没有创建业务代码、骨架、PR、共享服务或部署。全部测试表为后续验收设计，不是已通过的运行结果。

### 1.1 固定来源与实际核验

| 项目 | 核验值 |
| --- | --- |
| 官方仓库 | https://github.com/deepseek-ai/deepseek-harness |
| 默认分支及固定提交 | master；`571902028a75c64c1d767f4ae5fd099485f0ddf0` |
| 提交时间 | 2026-10-14T13:15:15Z；同步 `0.2.1-alpha.3` 的 merge |
| root package | `0.2.1-alpha.3`；Node `^22.19.0 \|\| >=24.0.0`；pnpm `11.28.5` |
| 源码清单 | GitHub recursive tree 18,510 项，`truncated:false`；抽取 682 个文档/源码/package 文件，逐个核对 Git blob SHA-1，记录 SHA-256 |
| 工具基线 | 上游生成目录 32 个包、72 个注册项、67 个不同工具名、34 个注册来源文件；全部注册来源已下载、核对 blob |
| 已完成验证 | 静态源码/文档分析、目录解析和完整性断言、所选文件哈希验证；未安装/执行 Harness、未调用模型/真实工具、未跑上游或Java测试 |
| 证据附件 | 本Markdown；`XIAO-33-harness-tool-inventory-v1.1.json`（72项完整输入Schema、描述、固定源码链接及迁移策略）；`XIAO-33-harness-source-manifest.json`（取得的文件及双哈希） |

通过公开 GitHub API 与固定提交 raw 文件取证；本环境 Git clone/TLS 路径未成功，未使用失败的 checkout 冒充本地构建。哈希比对确认字节与该提交树一致，不能代替供应链签名或执行验证。682 是取得和哈希检查的文件数，不表示逐行审阅整个仓库。

源码引用统一使用固定提交链接。重要入口：

- [package.json](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/package.json)、[LICENSE](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/LICENSE)、[THIRD_PARTY_NOTICES](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/THIRD_PARTY_NOTICES.md)、[SAFETY.md](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/SAFETY.md)。上游明确开发预览、未安全审计、未达生产就绪；其沙箱/批准并不构成生产隔离保证。
- [tool-catalog.md](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/docs/tool-catalog.md)、[gen-tool-catalog.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/scripts/gen-tool-catalog.ts)：上游生成器启动真实插件读取 Schema，并对 `packages/*/tool-*` 做覆盖检查。本次解析其已提交生成物，没有重新启动生成器，因此运行时配置漂移还需后续验证。
- [tool-execution-pipeline.md](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/docs/tool-execution-pipeline.md)：工具调用、单调guard、批准、执行wrapper、最终冻结结果的顺序。

### 1.2 许可证与复用边界

表中每一个 G/T 项的第一方 Harness 源码适用 root **MIT（Copyright 2026 DeepSeek）**，复制/修改保留版权和许可文本；Java 独立实现不因此自动继承其第三方组件可用权。实际构建的完整依赖闭包须单独生成 SBOM 和许可证清单。

| 依赖/能力 | 已核实许可线索 | 迁移约束 |
| --- | --- | --- |
| MCP SDK/client | THIRD_PARTY_NOTICES记为MIT | 协议适配不等于复制Node SDK；Java实现另锁版本及许可证 |
| @vscode/ripgrep | notices记为MIT | 二进制/实际打包的底层组件通知另核实；不得仅凭包装包许可结案 |
| Stagehand、Playwright MCP | notices分别MIT、Apache-2.0 | 浏览器/模型/远端服务条款及Chromium依赖另核；保留对应通知 |
| LibreOffice Kit | notices记为MPL-2.0 | 文件级义务、分发方式和Native/WASM依赖闭包需审查，不把整个Office栈标MIT |
| Office Python库、vendor资产 | notices/uv.lock各自许可 | 按选定镜像实际闭包审核，未做全量法律判断 |
| 外部Claude Agent SDK | notices指向README内许可 | 不纳入首期，不能因Harness root MIT推定可任意分发 |

不迁入参考环境的凭证、用户数据或登录态。工具描述内的审批提示属于上游运行行为证据，不构成本研究环境的操作授权。

## 2. 清单口径与迁移矩阵读法

附录A逐项列72个注册项。每项以`包＋工具名`为身份，继承对应G行的协议依赖、Java/worker路径、权限资源、包级改动量、顺序、岗位与验收要求；输入必选/可选字段和固定源码单独列出。完整嵌套Schema在JSON附件，不能仅按字段表实现。

72不等于72个独立实现：Bash/Pwsh各有单次和持久版本；`interrupt_agent/list_agents/send_message`各有子Agent与团队版本，字段和授权不同。配置别名`subagent_fork`不另算一项；真实部署可配置toolName和model选择。`read_image`有附件/路由条件，很多experimental工具默认未启用。本目录是已发行能力集合，不代表默认每个Session同时暴露全部工具。

MCP远端工具、浏览器MCP/CUA等provider动态贡献的Schema不具有一个跨部署固定列表；接入时导出服务器/版本/Schema快照，接受同一授权管线。其存在不允许虚构成这里的静态72项。Office技能、office-to-pdf也不是新的模型工具名称，另见第5节。

路径标记：J=Java重写；P=协议适配；W=隔离worker；D=需单独验证、延后或显式替代。统一阶段P0–P5，网页五工具为P3，其余为P4分批并按启用条件验收；旧研究阶段保存在JSON的upstreamResearchPhase。D不取消总范围。G行工作量是**已具备共享内核后，一名熟悉Java及该依赖的工程师完成整包适配和风险测试的增量人日区间**，含表中多个工具、不可给每个T再乘一次，也不可直接累加推算交付工期。阶段总量另估共享工作。

| 包/工具 | 路径/统一阶段/整包增量人日/岗位 | 协议与真实依赖 | 权限/资源边界 | 该包各工具验收基线 |
| --- | --- | --- | --- | --- |
| G01 @deepseek-ai/dsh-plugin-manager<br>`plugin_manager` | J/管理面<br>P4后批，先验证启用条件；4–7；后端＋平台 | profile、Cordis、pnpm安装脚本<br>Requires: `ctx.tools`, `ctx.pluginManager`, `ctx.sandboxPolicy` | 只允许管理角色启停已审核版本；会话固定版本；不允许模型任意装包/运行安装脚本 | 越权安装拒绝；依赖冲突拒绝；排空升级/回滚与旧运行隔离 |
| G02 @deepseek-ai/dsh-mcp-resources<br>`list_mcp_resource_templates`, `list_mcp_resources`, `read_mcp_resource` | P/MCP<br>P4；3–5；后端 | MCP resources/list、resources/templates/list、resources/read<br>Requires: `ctx.tools`, `ctx.mcpResources` | 每租户服务器白名单和秘密引用；URI/分页/内容预算；响应为非可信外部内容 | 非法URI、分页、服务器故障/取消；跨租户资源读取拒绝 |
| G03 @deepseek-ai/dsh-experimental-browser-use-stagehand-native<br>`stagehand_act`, `stagehand_extract`, `stagehand_navigate`, `stagehand_observe`, `stagehand_screenshot`, `stagehand_tabs` | W/Node浏览器<br>P4后批，先验证启用条件；7–12；后端＋平台 | Stagehand native、Chromium/CDP；每Session浏览器，独立模型凭证<br>Requires: `ctx.browserUse`, `ctx.agents`, `ctx.tools`, `ctx.systemPrompt` | 保留六工具Schema；隔离worker、独立cookie目录/出网配额；首版只launch，禁任意attach；截图存对象存储 | act后observe验证；cookie不共享；取消排空/杀worker；截图下载鉴权 |
| G04 @deepseek-ai/dsh-tool-ask-user<br>`ask_user_question` | J/人机交互<br>P4；2–4；后端＋前端 | ctx.userQuestions；默认阻塞；timed模式可返回pending及迟到回答<br>Requires: `ctx.tools`, `ctx.userQuestions` | 持久化问题ID/所有者/deadline；暂停不占worker；区分调用终结与问题仍可回答 | 阻塞、超时、迟到答案、重连；非所有者不得回答 |
| G05 @deepseek-ai/dsh-tools<br>`run_code` | W/PTC＋J调度<br>P4后批，先验证启用条件；8–15；后端＋平台 | ctx.ptcRuntime；TypeScript/Python；子调用完整管线<br>Requires: `ctx.workingDirectory (Agent executions)`, `ctx.tools`, `ctx.ptcRuntime (execution time)`, `ctx.systemPrompt` | 不在API JVM直接eval；隔离代码worker走授权RPC；外层/子调用预算、顺序、并发与关联ID；程序不自动重放 | 嵌套拒绝不绕过；并发安全标记；已完成副作用不重放；预算超时杀worker |
| G06 @deepseek-ai/dsh-plan-mode<br>`exit_plan_mode` | J/计划状态<br>P4；1–3；后端＋前端 | plan/mode、ctx.userQuestions；批准后步边界退出<br>Requires: `ctx.tools`, `ctx.systemPrompt`, `ctx.userQuestions (execution time, opportunistic)` | 校验计划状态、审阅版本与用户身份；不是通用部署授权；批准事件与下一步状态一致 | 非计划模式拒绝；旧版批准拒绝；继续规划不退出 |
| G07 @deepseek-ai/dsh-tool-bash<br>`bash` | W/Linux单次shell<br>P4，不属网页MVP；4–7；后端＋平台 | ctx.shell/shellEnv/jobs；bash -c，每调用新进程<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.shell`, `ctx.systemPrompt`, `ctx.shellEnv`, `ctx.jobs for run_in_background and the job-backed foreground path` | 首期禁后台参数；独立worker、所属工作区、环境清理、CPU/RAM/输出/时间限额；背景提升P4再启用 | 非零退出/截断；取消杀进程树；有jobs等待超时返回同ID继续，无jobs截止杀死 |
| G08 @deepseek-ai/dsh-tool-present<br>`present` | J＋产物存储<br>P3；3–5；后端＋平台 | ctx.fs/sessionProjections；成功最终结果后才presented<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.fs`, `ctx.sessionProjections` | 实时源文件交付改为不可变快照；上传成功记录hash/version/owner再发布artifactId；按需签名URL | 最终结果失败不发布；上传失败不成功；源变化不改快照；跨租户下载拒绝 |
| G09 @deepseek-ai/dsh-tool-pwsh<br>`pwsh` | W/Windows单次shell<br>P4后批，先验证启用条件；4–7；后端＋平台 | PowerShell executor、shellEnv/jobs；每调用新进程<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.shell`, `ctx.systemPrompt`, `ctx.shellEnv`, `ctx.jobs for run_in_background and the job-backed foreground path` | 业务需要Windows时用专属worker；系统帐号/容器隔离另验证；不能复制bash sandbox保证 | 原生路径/Unicode/环境变量；取消进程树；前台截止、非零退出 |
| G10 @deepseek-ai/dsh-tool-cordis<br>`cordis_inspect_list`, `cordis_inspect_query` | D/另做诊断<br>P4后批，先验证启用条件；1–3；后端 | Cordis host/client只读inspection<br>Requires: `ctx.tools`, `ctx.cordisInspect` | TS对象不能原样迁JVM；另做Java公开诊断契约；管理面默认不向模型暴露内部/秘密 | 不存在provider/方法拒绝；诊断脱敏及权限 |
| G11 @deepseek-ai/dsh-tool-bash-persistent<br>`bash` | W/持久PTY<br>P4后批，先验证启用条件；4–7；后端＋平台 | ctx.terminals；同名bash，Agent独占PTY<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.terminals`, `an owning Agent at execution time` | 与G07择一；状态限Session、空闲回收/配额；重启丢状态明确提示 | 命令继承cwd/环境；不同Agent不共享；断开、重启、取消后检查新状态 |
| G12 @deepseek-ai/dsh-tool-pwsh-persistent<br>`pwsh` | W/Windows持久PTY<br>P4后批，先验证启用条件；4–7；后端＋平台 | ctx.terminals；同名pwsh，Windows PTY backend<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.terminals`, `an owning Agent at execution time` | 与G09择一；独立owner/帐号；不复用宿主登录shell；原生后端另测 | 持久性/owner隔离；Unicode/信号；孤儿PTY回收 |
| G13 @deepseek-ai/dsh-tool-str-replace-editor<br>`str_replace_editor` | J/文件编辑<br>P4；2–4；后端 | ctx.fs；view/create/str_replace/insert<br>Requires: `ctx.tools`, `ctx.fs` | 复用P3底层文件端口；保留命令参数/唯一匹配语义；与edit重复，首期不双重暴露 | 查看窗口/插行边界；零或多匹配拒绝；版本冲突 |
| G14 @deepseek-ai/dsh-tool-fs<br>`edit`, `read`, `read_image`, `write` | J/文件与图片<br>read/write P3；edit/read_image P4；5–8；后端 | ctx.fs/观察策略；read_image需attachments和路由图片能力<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.fs`, `ctx.systemPrompt`, `ctx.attachments (image-tool registration)`, `ctx.llm + an image-capable route (image-tool execution)` | 版本工作区；不可绕过观察/CAS；越界/符号链接由执行边界拦截；图片P4启用并限制大小/模型能力 | 读取缺失/分页/UTF8/空文件；并发版本冲突；edit零/多匹配；不支持图片路由拒绝 |
| G15 @deepseek-ai/dsh-tool-fs-search<br>`glob`, `grep` | W/rg＋J适配<br>P4，F01-b/原生worker门槛；3–5；后端＋平台 | ctx.subprocess.spawn、@vscode/ripgrep；固定argv非shell<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.subprocess`, `ctx.systemPrompt` | 先保留原生rg语义，不以Java regex冒充；搜索与FS同工作区；spill转可读artifact locator | glob采样/上限；grep正则/include/Unicode；无匹配正常；非法正则/溢出/超时/取消及清理 |
| G16 @deepseek-ai/dsh-tool-terminal<br>`terminal_close`, `terminal_list`, `terminal_open`, `terminal_read`, `terminal_send`, `terminal_signal` | W/终端控制<br>P4后批，先验证启用条件；5–9；后端＋平台 | ctx.terminals；send可接jobs；六工具opt-in<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.terminals`, `ctx.systemPrompt`, `ctx.jobs at call time for run_in_background` | 仅owner控制sessionId；不添加TUI/共享/resize；输出环/游标、信号白名单、配额 | 六操作闭环；越权sessionId拒绝；环覆盖；关闭后拒send；后台归属 |
| G17 @deepseek-ai/dsh-tool-goal<br>`create_goal`, `get_goal`, `update_goal` | J/目标状态机<br>P4；3–5；后端 | ctx.goals、授权open turn；revision/轮次<br>Requires: `ctx.tools`, `ctx.agents`, `ctx.goals`, `ctx.systemPrompt`, `a calling Agent in an authorized open turn` | CAS revision；直接人类根权限与当前轮次权限分开；goal绑定任务；保留blocked下限语义 | 创建/更新/暂停/继续/完成/阻塞权限；过期revision/跨round拒绝 |
| G18 @deepseek-ai/dsh-tool-schedule<br>`schedule_create`, `schedule_delete`, `schedule_list`, `schedule_update` | J/持久调度<br>P4；5–8；后端＋平台 | ctx.schedule/Host storage；到期恢复原Session<br>Requires: `ctx.tools`, `ctx.schedule` | 数据库租约/持久事件（使用broker时outbox）；IANA时区/cron；fireId去重、租户配额、删除竞态；不靠本机timer恢复 | 一次/固定间隔/日周/cron/DST；更新删除；重启及重复投递一个逻辑触发 |
| G19 @deepseek-ai/dsh-tool-lsp<br>`lsp` | P/LSP worker<br>P4；4–7；后端＋平台 | ctx.lsp/stdio语言服务器；缺provider为LSP_UNAVAILABLE<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.lsp`, `ctx.systemPrompt` | 工作区/版本绑定；保留位置坐标、operation与上限；进程资源和取消治理 | 各operation/Unicode坐标；provider缺失/进程死/超时/取消；外部工作区拒绝 |
| G20 @deepseek-ai/dsh-tool-ralph<br>`ralph` | D后轮次编排<br>P4后批，先验证启用条件；3–5；后端 | workflowEngine/subagents；每轮新结构化child、固定目标<br>Requires: `ctx.tools`, `ctx.workflowEngine`, `ctx.subagents`, `ctx.systemPrompt`, `a calling Agent (exec.agent parents every fresh round)` | 先G23/G30及成本轮次预算；持久round状态，停止后不再生成child | 目标不可变；maxRounds/失败/取消；新child语义/总预算 |
| G21 @deepseek-ai/dsh-tool-skill<br>`skill` | J/技能资源<br>P4；3–5；后端 | ctx.skills/agents/workingDirectory；渐进catalog<br>Requires: `ctx.workingDirectory`, `ctx.tools`, `ctx.agents`, `ctx.skills` | 技能文本保留版权改造；directory/URL/opaque资源变worker locator；文本不能授执行权限 | 不可见/禁止model调用/已移除技能拒绝；catalog替换；资源越界 |
| G22 @deepseek-ai/dsh-tool-session-query<br>`session_event_read`, `session_event_search`, `session_event_trace`, `session_search`, `session_trace` | J/事件检索<br>P4；4–7；后端 | ctx.sessionQuery；immutable caller authority；五只读工具opt-in<br>Requires: `ctx.tools`, `ctx.systemPrompt`, `ctx.sessionQuery`, `a calling Agent for workspace authority` | 每结果按调用Session授权，不信传入session_id；游标/输出/时间预算及脱敏 | read/search/trace关联；他租户Session拒绝；provider故障/超时/分页 |
| G23 @deepseek-ai/dsh-tool-subagent<br>`list_subagent_models`, `subagent` | J/子Agent调度<br>P4；6–10；后端 | ctx.subagents/llm；provider/工具名/模型选择配置<br>Requires: `ctx.tools`, `ctx.subagents`, `ctx.systemPrompt`, `ctx.llm for model discovery and selected-route validation` | 持久parent/child；返回childId不是完成；上下文与权限继承分开；深度/并发/token预算及路由白名单 | 模型发现/非法选择；spawn/fork差异；结果去重；取消/深度超限 |
| G24 @deepseek-ai/dsh-tool-subagent-control<br>`interrupt_agent`, `list_agents`, `send_message` | J/子Agent控制<br>P4；3–5；后端 | ctx.subagents；list_agents用projections/live registry<br>Requires: `ctx.tools`, `ctx.subagents`, `ctx.agents and ctx.sessionProjections (list_agents only)` | 仅可授权child树；消息幂等/inbox；中断不越租户；与G26同名工具不混装 | list scope；已结束/缺失child；send去重；interrupt与预算回收 |
| G25 @deepseek-ai/dsh-tool-jobs<br>`job_kill`, `job_list`, `job_output` | J/job＋worker<br>P4；5–8；后端＋平台 | ctx.jobs；输出/停止/列表/通知；五状态<br>Requires: `ctx.tools`, `ctx.jobs`, `ctx.systemPrompt` | 持久owner/lease/epoch、输出游标/spill、停止确认、持久事件（使用broker时outbox）去重与唤醒限额；不照搬内存注册表 | kill幂等；delta/丢失标记；等待deadline；重启/终态一次/无孤儿进程 |
| G26 @deepseek-ai/dsh-experimental-tool-agent-team<br>`interrupt_agent`, `list_agents`, `send_message`, `spawn_teammate`, `team_task_create`, `team_task_get`, `team_task_list`, `team_task_update`, `wait_agent` | D/团队系统<br>P4后批，先验证启用条件；10–18；后端＋产品 | ctx.agentTeams；九工具，默认bundle禁用<br>Requires: `ctx.tools`, `ctx.systemPrompt`, `ctx.agentTeams`, `an exact live Team member Agent` | 先明确团队场景；权限、依赖环、task CAS、write_scopes仲裁；与旧controls互斥 | lead/member权限；读写/分页/revision；依赖环/写冲突；wait事件/超时/取消 |
| G27 @deepseek-ai/dsh-tool-todo<br>`todo_write` | J/清单投影<br>P4；1–2；后端＋前端 | Session-owned todo/write；并行规则配置必选<br>Requires: `ctx.tools`, `owning Agent session` | 仅会话投影，不作为调度队列；事件幂等/最新revision；产品确定并行规则 | 完整替换、状态/并行规则；重连最新；跨Session隔离 |
| G28 @deepseek-ai/dsh-tool-working-directory<br>`working_directory` | J/工作区上下文<br>P4，P3工作目录由任务固定；1–2；后端 | workingDirectory；不改变授权根/已运行进程cwd<br>Requires: `ctx.tools`, `ctx.workingDirectory` | Session逻辑cwd限制workspaceRoot；worker用相对路径；cd不拓宽授权 | 相对/绝对/越界cd；新调用用新cwd；运行中进程保持旧cwd |
| G29 @deepseek-ai/dsh-experimental-tool-worktree<br>`create_worktree` | W/Git<br>P4后批，先验证启用条件；4–7；后端＋平台 | worktrees；固定本地commit，新branch/checkout；experimental<br>Requires: `ctx.tools`, `ctx.worktrees`, `a calling Agent` | 隔离Git worker/租户凭证ref；commit/repo/目标根、磁盘限额；保留branch/checkout语义；不自动push | 固定commit；冲突/脏目录；路径/tenant隔离；失败不切cwd |
| G30 @deepseek-ai/dsh-tool-workflow<br>`workflow` | W/JS编排＋J调度<br>P4后批，先验证启用条件；7–12；后端＋平台 | workflowEngine；JS hooks/子Agent；结果默认50k chars；可jobs后台<br>Requires: `ctx.tools`, `ctx.workflowEngine`, `ctx.systemPrompt`, `a calling Agent (exec.agent parents the script children)` | 不以Java DSL冒充兼容；隔离JS无宿主FS/network/timer；持久step/预算；后台依赖G25 | agent/pipeline/parallel的null与整段错误；非completed为error；取消dispose；不重放副作用 |
| G31 @deepseek-ai/dsh-tool-workspace-dependencies<br>`load_workspace_dependencies` | J/worker清单<br>P4；1–3；后端＋平台 | bundled Python libraries、Node/pnpm路径；不改PATH<br>Requires: `ctx.tools` | 返回镜像/库版本与能力locator；不暴露API主机路径；office环境绑定 | 清单与真实镜像一致；缺库明确不可用；locator不能跨worker误用 |
| G32 @deepseek-ai/dsh-tool-web<br>`web_fetch`, `web_search` | J/HTTP＋P搜索<br>P3；5–8；后端＋平台 | ctx.web；HTTP/Turndown；DeepSeek/Exa/Perplexity providers<br>Requires: `ctx.tools`, `ctx.web`, `ctx.systemPrompt` | Java HTTP/HTML适配或Node worker；URL/重定向/DNS/代理出网；秘密/租户计费；正文/字节预算；网页已确定为P3业务纵切 | 1–4 queries；SSRF/DNS重绑定/跨源重定向；非2xx页面保持可观察内容；Markdown/source/截断；缺provider/搜索429/取消/超时 |

## 3. 首个网页业务纵切与底层文件验收

产品已确定唯一首个业务任务：公开主题→web_search→web_fetch→read/真实观察报告目标不存在或已有版本→write强制CAS→present不可变快照→核验、登记、发布→授权下载。首期模型能力为web_search/web_fetch/read/write/present，不开放shell或额外任意代码。无有效抓取来源走FAILED/NO_EVIDENCE，不计成功报告；部分失败披露，六栏目及关键结论可追溯按主稿§1.2。

后端原文件六工具任务改为F01底层套件：P3必跑F01-a的read/write/present、FilePort版本/路径/归属/容量；edit/glob/grep的F01-b在P4对应工具启用前验证。未完成b不宣称六工具套件通过，不再同时承诺两个业务MVP。实际同工作区与原rg语义要求保留。

新报告先经同一限权文件端口记录真实不存在观察，已存在记录版本，之后原子createIfAbsent/replaceIfVersion。write完成不是业务成功；present须核验当前租约、对象hash/size/version/归属后发布。登记/上传失败无成功报告，旧worker的实际写效果仍须核对，不能仅拒DB迟到结果就声称停止。

### 3.1 从源调用链到云端链

Harness：模型tool block → 记录tool/call → pre-execute/单调guard/批准 → execute wrappers → tool.execute → FS intent/observed等领域事件 → projectContent/post-execute/normalize → finalize → 冻结tools/result → Session tool/result。run_code子调用重新进入全部管线。

建议饕餮：HTTP鉴权 → 创建task/session（绑定tenant、用户、workspace、预算、幂等键） → 持久任务/事件（需要broker时outbox通知） → 领取租约 → 模型路由/能力发现 → 解析并校验输入Schema → **内核授权/能力/预算/固定插件版本** → 创建invocationId并持久记录意图 → 文件端口或worker RPC → 结果与产物登记 → 事务提交持久事件（需要broker时outbox） → SSE按seq投影 → 终态。内核责任不因工具“内置”、插件禁用、MCP或嵌套调用而绕过。

worker请求携带经签发的tenant/task/invocation/workspace/能力/截止/epoch，模型参数不能填写或覆盖这些字段。数据库不能把外部副作用放进一个长事务；RPC之前短事务记录意图，完成后短事务CAS终态和事件。租约失效后旧worker结果拒绝覆盖新epoch，但仍须处理已产生的真实副作用。

### 3.2 六项语义与未来验收

| 工具/固定实现 | Harness输入与输出/失败语义 | Java/云端保留及差异 | 必须执行的风险验收 |
| --- | --- | --- | --- |
| read：[read.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/read.ts) | file_path，offset/limit；文件/目录读由fs路由，UTF8文本默认readLimit 2000行，大文本流式路径；读取记录fs/observed，signal传入provider | 返回相同可读窗口及截断信息；内部追加workspace/fileVersion供后续CAS；缺失观察与缺失错误区别保持源码路径；不为远端对象伪造本机绝对路径 | 缺失/目录/分页/UTF8长行、大文件、取消；读取不扩大写权限；越界及符号链接链路 |
| write：[write.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/write.ts) | file_path/content，允许空content；resolve→fs/write-intent→writeText；返回path/operation/before/after；不装观察策略为无条件原子覆盖，装策略用createIfAbsent/replaceIfVersion | 云端默认强制观察/CAS；新文件需观察不存在；拒绝未读/已变文件，不能用插件关掉。输出的额外版本/逻辑locator为显式v1适配差异 | 空文件；已存在未读拒绝；观察后被并发修改拒绝；取消前后确认实际版本；重试同operationId不重复写 |
| edit：[edit.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/edit.ts) | file_path/old_string/new_string，replace_all默认false；old非空且不同于new；唯一匹配或replace_all；edit-intent→原子editText；输出path/before/after，差异展示 | 保留字面替换，不能误写regex；授权/CAS统一FilePort，生成差异元数据；上传/展示不泄漏其他文件 | 零/多匹配、replace_all、删除为空、中文/换行；并发CAS；错误不产生伪成功差异 |
| glob：[glob.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs-search/src/glob.ts) | pattern/path→固定rg argv，经subprocess；mtime/超限采样为部署选择；有cap/可选spill；不是后台job | 固定镜像rg，argv参数隔离；同workspace；确定采样配置与版本；spill返回租户可读artifact locator | glob/排除目录/排序及采样；大量结果截断；无结果；路径参数不能注入命令或越界 |
| grep：[grep.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs-search/src/grep.ts) | pattern/path/include，rg正则和结构化匹配；raw stdout默认20,000,000 bytes、stderr 64KiB、grace 3000ms、metadata 65536 bytes；SEARCH_*表示失败 | 不用Java regex替换而默默改变语法；进程树硬停，输出UTF8边界/行预览与spill限制 | 正则合法/非法、include、无匹配、Unicode、大输出溢出、超时和取消后无残留子进程 |
| present：[index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/deliverables/tool-present/src/index.ts) | files必须现存普通文件、owning Agent Session/open turn；maxFiles默认8（描述建议≤4）；成功最终tools/result才append deliverables/presented；输出turn/files，**不复制内容**，UI打开当前源文件 | 云端先保存不可变快照再发布；artifact含tenant/task/run/version/hash/mime/size。行为变化须记录为cloud-v1；签名URL不写入长期事件 | 上传失败/最终结果error不发布；重复发布幂等；源变化不改快照；他租户读取/过期URL失败；取消清理未发布临时对象 |

FS与search同工作区是上游明确共置要求，不应让搜索得到容器A路径而read发往B。路径检查须防止`..`、绝对路径、符号链接及检查后替换竞态；只做字符串normalize不足以保证隔离。Java工作区端口与worker mount/受约束文件打开机制共同验证这一边界。

### 3.3 超时、取消、重试、断流和恢复

- [timeout-policy源码](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/guard/timeout-policy/src/index.ts)仅针对声明ToolDefinition.timeoutMs的工具派生signal，并等下游settle后映射TOOL_TIMEOUT；忽略signal会继续等待。read/write/edit等未声明该预算不自动获得通用截止。wrapper注册顺序会改变“每次重试”与“整个调用”的预算范围。
- cloud-v1对每次调用取任务剩余预算、manifest预算与租户上限的最小值。取消先持久标cancel_requested，再传signal；超过grace由worker supervisor终止进程树/容器，记录资源清理结果。不能把Java Future.cancel当作已停止外部程序。
- 写文件、浏览器操作、shell与脚本默认不自动重放。持久operationId/CAS版本可使文件写幂等；调用超时但副作用未知时标`OUTCOME_UNKNOWN`并核查实际状态，不能直接显示“失败、可放心重试”。MCP/外部接口依实际幂等支持分类。
- SSE断开只影响订阅；任务继续，客户端用Last-Event-ID/seq补读。取消是独立鉴权操作。每事件包含tenant（内部）、task/session/step/invocation/trace、seq、时间、版本；对外不发送秘密或host路径。
- 重启后按租约/epoch恢复已持久运行；可安全读重试，未知副作用待核查。状态存储和对象上传不是一个数据库事务，采用未发布对象→登记→发布与超时清理；故障注入验证“无发布记录无成功交付”。

## 4. Java收益、成本与复用策略

Java的可证实便利是record/sealed类型、编译器和模块测试对契约漂移的检查，以及Spring/JVM鉴权、事务、队列、追踪集成与团队维护一致性。它适合控制多并发任务的生命周期和资源归属；实际模型生成、浏览器/原生执行仍主要等待外部系统。语言不提高模型能力，也不自动实现租户隔离、持久调度、可靠取消或更高吞吐。

成本包括：Cordis注入/作用域/event waterfall改为Java契约；TypeScript/Python代码工具和JS工作流不能假装原样转为Java；Node生态浏览器/Office工具worker运维；JVM内存/启动开销；JSON Schema、内容块/图片/错误/展示的一致性；追踪上游alpha变化的回归成本。动态JAR带有宿主权限，只允许可信审核插件；非可信代码进程/容器隔离。MCP负责协议互操作，不负责完整权限/租约/成本/沙箱。

建议借鉴服务seam和完整执行管线，重写Java内核与纯状态工具；复用合法技能文本和可隔离原生依赖；保留Node worker的Stagehand/JS/PTC候选路径以降低首次实现风险。是否保留上游Node服务端包须在小试验中确认API和生命周期，不能由MIT或本次静态阅读认定直接可运行。

性能试验计划：同一模型/输入/限额下测每任务完成时间p50/p95、首事件延迟、工具并发吞吐、API/worker RSS、取消到资源清理时间、单位任务模型/工具费用；分别测1/10/50个并发只读任务与少量有副作用任务。记录机器/镜像/版本/失败率，至少3轮对照。当前没有性能数据，不将预期作为选型结果。JDK/Boot/AI BOM的官方兼容矩阵由架构稿提供，本稿不虚构已核实版本。

## 5. 非独立工具的能力与部署资产

### 5.1 Office技能与渲染

[skill-office README](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/skill/skill-office/README.md)包含Office路由和六个创作/操作技能，共七技能，渐进公开摘要和正文。提供Python authoring库、Node/LibreOffice Kit operation CLI资源；provider本身不执行，实际由Bash/Pwsh走工具执行边界。`cli=false`可禁执行能力。`load_workspace_dependencies`只描述库/路径，不改PATH。

迁移路径：技能正文适配为版本化资源；Python库、Node CLI、LibreOffice Kit放同一worker镜像；资源locator绑定worker镜像，禁止让模型得到API主机绝对路径再去另一容器执行。Java任务状态/产物统一，生成格式仍可用Python/Node执行，不要求将所有文档库改写Java。整包增量估4–8人日＋渲染6–10人日，前提为基础worker/产物已就绪，P4/P4后批按业务开。

[office-to-pdf README](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/document/office-to-pdf/README.md)是服务seam，不是第73个模型工具。使用LibreOffice Kit `^0.1.5`，Native/WASM按打包配置选择；缺已声明native资源时拒绝，不可假装fallback。默认并发2、converter取得后60秒超时、输入50MiB/输出100MiB、DPI192；有有界队列、授权版本、缓存、临时文件和取消/dispose。预算不能自动限制渲染引擎RSS；云端仍需独立进程/容器。验收包括队列等待/运行预算区分、损坏文件、渲染图片/PDF视觉检查、并发、取消、缓存版本、临时文件清理、跨租户读拒绝。

### 5.2 动态连接与基础管线

MCP连接、模型路由、attachments、spillStore、approval、fs observation、jobs runtime等是工具服务依赖，不单列虚构模型工具。每种provider要明确健康状态、凭证作用域、不可用错误与能力发现。自动目录包括本机默认配置时没有出现的可选控制字段；比如shell的sandbox字段和subagent的模型选择随配置改变，JSON附件不是所有部署的Schema全集。实际启用组合必须生成最终catalog，并用兼容测试审核差异。

web-fetch HTTP provider源码在[provider.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/web/web-fetch-http/src/provider.ts)与[network.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/web/web-fetch-http/src/network.ts)：同源重定向默认最多5跳，重新验证目标；跨源不自动跟随；直连DNS全部答案检查并固定连接地址，含IPv4-mapped/NAT64处理。代理路径由代理解析目标，不能声称它等价获得直连DNS保证；云端必须把同等策略落到出口代理。HTML→Markdown由Turndown/GFM等实现，Java替代需金样对照；web_fetch/search默认合作预算30秒、tool层正文上限200,000字符。不要用“Java HTTP客户端默认自动重定向”破坏原行为。

## 6. 工程规范与门禁落地建议

以下是与主稿0.3和工程规范0.2统一的**待实施规则与样例**，不是已创建模块或通过门禁。先规范/骨架，再业务纵切。框架组合已合并为候选，实际BOM仍须E1编译与依赖收敛验证，不能伪称锁定通过。

### 6.1 最小目录与依赖

工程依据为本次《工程规范0.2》和统一主方案0.3，替代旧0.1设计候选。首期 **Maven Wrapper＋单Maven项目/Boot制品、包隔离＋ArchUnit**；第三方插件API真正需要独立发布/构建隔离时再拆。下列为同一规则的文件插件落地重点，不另维护第二规范。

```text
taotie/
  pom.xml / mvnw / mvnw.cmd / .mvn/wrapper/
  src/main/java/io/taotie/
    bootstrap/                   # 启动/DI/配置装配，无业务分支
    api/                         # 鉴权HTTP/SSE和请求响应DTO
    kernel/                      # task/invocation/artifact/event/budget/dispatch
    plugin/api/                  # 叶子契约；无Spring/JPA/内核实现
    model/api/                   # 稳定模型端口；无具体AI框架类型
    execution/                   # 受治理的单一AI循环/步骤协调
    plugins/builtin/filesystem/  # read/write/edit与FilePort契约
    adapters/                    # persistence/storage/runtime/telemetry/ai
  src/main/resources/plugins/builtin/filesystem/
  src/main/resources/db/migration/
  src/test/java/io/taotie/{architecture,contract,integration}/
  src/test/resources/contract/harness/<sha>/
  workers/                       # 按需要创建隔离worker镜像/协议目录
  docs/adr/ docs/migration/ docs/verification/
  deploy/ AGENTS.md CONTRIBUTING.md
```

允许kernel的application依赖公开plugin/model契约与port、builtin插件依赖plugin.api/provider公开端口、adapters实现公开port、bootstrap组合具体实现。禁止kernel依赖具体plugins/adapters/bootstrap，插件依赖kernel内部/持久Entity/API Controller，plugin.api依赖Spring/JPA/kernel实现/具体插件。API只调用发布用例接口，不能直接execute工具。单制品编译可引用全部代码，必须用架构测试阻止越界。

plugin lifecycle/dispatch位于kernel公开契约后的实现，不预建空Service/Manager。动态JSON只出现在MCP/模型Schema等边界，用JsonNode＋Schema封装，不使task/state成为无约束Map<String,Object>。模型适配不是授权入口；worker只经版本协议与内核交互。

### 6.2 插件结构、manifest与接口样例

```text
src/main/java/io/taotie/plugins/builtin/filesystem/ReadTool.java
src/main/resources/plugins/builtin/filesystem/workspace-read/plugin.yaml
src/main/resources/plugins/builtin/filesystem/workspace-read/input.schema.json
src/main/resources/plugins/builtin/filesystem/workspace-read/output.schema.json
src/test/java/io/taotie/plugins/builtin/filesystem/ReadToolContractTest.java
```

read插件manifest的JSON注册视图样例（实际只维护plugin.yaml并生成该视图；云端契约非上游已有）：

```json
{
  "manifestVersion": 1,
  "id": "taotie.workspace.read",
  "version": "0.1.0",
  "kind": "tool",
  "hostApi": ">=0.1.0 <0.2.0",
  "entry": {"type": "trusted-jvm", "class": "io.taotie.plugins.builtin.filesystem.ReadTool"},
  "capabilities": [{"name": "read", "inputSchema": "input.schema.json", "outputSchema": "output.schema.json", "sideEffect": "read"}],
  "permissions": {"filesystem": ["workspace.read"]},
  "secretRefs": [],
  "dependencies": [],
  "limits": {"timeoutMs": 30000, "outputBytes": 262144},
  "healthCheck": {"operation": "provider-readiness", "timeoutMs": 2000},
  "errorContract": "taotie-invocation-result",
  "eventTypes": ["invocation.started", "invocation.completed", "invocation.failed", "file.observed"]
}
```

manifest字段结构沿用主稿0.3，首期以plugin.yaml为唯一维护源、JSON为派生视图；30秒与256KiB为饕餮待实测候选预算，不是Harness read默认截止/输出承诺。取消（cooperative）、幂等（read-only）、无产物及稳定错误码在插件契约中明确；baseline标注harness@5719020/cloud-v1。worker manifest还须声明固定镜像digest、CPU/memory/process/disk上限、network允许目标和termination grace；可信JVM不伪装能给每个JAR独立CPU/RAM硬限额。

候选Java公开接口片段（规范样例，未编译；类型均由plugin.api定义）：

```java
public interface ToolPlugin<I, O> {
    ToolDescriptor descriptor();
    Class<I> inputType();
    Class<O> outputType();
    ToolResult<O> execute(I input, ExecutionContext context);
}

public record ReadInput(String file_path, Integer offset, Integer limit) {}
public record ReadOutput(String path, String fileVersion, String text,
                         int offset, int totalLines, boolean truncated) {}

// context中的WorkspaceFilePort由内核按调用签发；没有租户可变setter。
public final class ReadTool implements ToolPlugin<ReadInput, ReadOutput> {
    private final ToolDescriptor descriptor;

    public ReadTool(ToolDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public Class<ReadInput> inputType() { return ReadInput.class; }

    @Override
    public Class<ReadOutput> outputType() { return ReadOutput.class; }

    // execute不创建本机File或绕过FilePort。
    @Override
    public ToolResult<ReadOutput> execute(ReadInput input, ExecutionContext context) {
        context.cancellation().throwIfCancelled();
        ReadOutput output = context.workspace().readText(
            input, context.deadline(), context.cancellation());
        context.cancellation().throwIfCancelled();
        return ToolResult.ok(output);
    }
}
```

输入Schema保持file_path必填、offset/limit可选及后续从源码提取的范围；输出按cloud-v1添加fileVersion，Schema明确记录差异。ExecutionContext含只读tenant/task/session/invocation/trace、deadline、cancellation、经裁剪的capability端口；插件不能自签授权或读取完整秘密库。异常ToolFailure包含稳定code、可展示message、retry分类、effect状态、cause内部引用；结果为类型化text/image/artifact内容块。示例展示所有ToolPlugin方法；注册器按审核manifest提供descriptor，plugin.api类型和Schema文件在骨架任务完成并编译，此片段未作为可构建代码验证。真正实现时使用具名DTO和完整Schema，示例中的snake_case保留外部Harness线格式，内部类/方法按Java命名。

生命周期：安装检查manifest/Schema/制品摘要/许可/契约版本/依赖环/重复toolName → 校验健康 → enabled可发现 → 每次调用鉴权并固定版本 → 禁用拒新调用并排空 → stop关闭资源 → 原子启用新版本；失败回滚旧制品/配置，数据库schema不可假装一键逆迁移。核心policy不是可卸载插件。默认不支持版本豁免或任意热加载；若业务确需提出ADR和测试。

### 6.3 代码规则与合理例外

| 内容 | 可执行约定/检查 | 合理边界 |
| --- | --- | --- |
| 命名/格式 | io.taotie小写包；类UpperCamel、方法lowerCamel；格式/import统一Spotless＋google-java-format；CI check，本地apply | 外部wire字段保留上游snake_case，通过DTO映射；禁止大面积重命名外部协议 |
| 类型/空值 | 核心状态sealed/enum、DTO record；Optional只用于真实可缺返回；边界字段Schema/Bean Validation，内部信任已建立不变量 | MCP原始JSON允许JsonNode，须定Schema/预算；不为内部所有值添加防御null fallback |
| 分层 | Controller只映射/鉴权/调用application；领域不依赖HTTP/JPA；持久Entity独立DTO映射；ArchUnit阻止禁依赖 | 允许infrastructure实现公开ports；不得接口名掩盖对内部实现反向引用 |
| 配置/DI | constructor注入，配置绑定并启动时校验；BOM/依赖版本集中；秘密只能ref外置 | 无本次需求不抽通用工厂；纯静态常量允许，禁止租户相关全局可变状态 |
| 错误/日志 | 不吞异常；内部cause保留；外部稳定code/message，HTTP与tool error分别投影；结构化trace/task/run/plugin/version，敏感正文/令牌不进日志 | 工具未知副作用允许OUTCOME_UNKNOWN；内部bug快速失败，不能一律“成功空结果” |
| 资源 | try-with-resources关闭流；worker request cancel→grace→hard stop；缓存/输出/队列有明确界限 | 缺取消支持必须声明并用worker终止，不能假接口支持 |
| 事务/重试 | 短DB事务＋持久事件（broker通知时outbox）；幂等键绑定tenant＋操作＋参数摘要；读/有幂等证明可重试 | 浏览器/shell/脚本不默认重试；取消不保证回滚已写文件 |
| 迁移 | schema迁移按版本顺序、不可修改已应用脚本；每PR记录兼容/回滚策略 | 只新增真实需要的迁移，不提前建未来业务表 |

统一错误：INPUT_INVALID、OUTPUT_INVALID、ACCESS_DENIED、VERSION_STALE、PLUGIN_DISABLED、DEPENDENCY_UNAVAILABLE、DEADLINE_EXCEEDED、CANCEL_REQUESTED、RESOURCE_EXHAUSTED、EFFECT_OUTCOME_UNKNOWN、NO_EVIDENCE、INTERNAL_ERROR。调用OUTCOME_UNKNOWN是状态，任务对应NEEDS_RECONCILIATION；取消请求不等于执行已停止。原候选及上游业务线索按主稿§7.2受限映射，不另维护第二层级。保留Harness原始错误线索供迁移诊断，映射表版本化；不能将非零shell退出与进程启动失败混为一种成功结果。

### 6.4 自动门禁、违规示例与执行顺序

上游package.json及[scripts/run-gates.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/scripts/run-gates.ts)已有typecheck/oxlint/Vitest、build、doc-sync、package/license检查；值得借鉴的是“生成物和代码一致”的检查，而不是把其Node全部门禁照搬Java。

| 门禁候选 | 验证对象 | 故意违规及期望 | 实施位置 |
| --- | --- | --- | --- |
| Maven compile/test/verify＋Spotless | 编译、格式、import | 加未格式化类→check失败 | root POM，Wrapper固定；本地/CI同命令 |
| ArchUnit | 目录职责/依赖方向 | kernel import plugins.ReadTool→失败；插件import BootController→失败 | architecture测试扫描真实生产包；不让空扫描假通过 |
| manifest/Schema＋注册目录契约 | 每个启用插件都有manifest、输入/输出Schema；从真实注册生成catalog | 删除manifest/重复toolName/未知capability/不兼容api→构建或启动校验失败 | builtin plugin contract suite；生成docs/contracts目录再diff |
| JUnit边界/状态测试 | 文件CAS、错误分类、状态机、预算 | 并发覆盖通过、过期epoch终结成功→测试失败 | 各模块；测风险行为，不镜像代码分支 |
| Failsafe worker集成 | 真实rg/文件/产物/进程树与存储 | 假死worker、超限输出、取消不清理→集成失败 | 验收镜像真实启动，Testcontainers或CI同等环境 |
| 依赖收敛/许可证 | 锁定BOM/制品/SBOM与许可 | 两版本依赖冲突、未知许可未核→门禁失败或人工例外 | Maven Enforcer dependencyConvergence，受审许可清单 |

ArchUnit候选规则（代码片段，骨架任务配置完整imports/version并执行）：

```java
noClasses().that().resideInAPackage("io.taotie.kernel..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "io.taotie.plugins..", "io.taotie.bootstrap..",
        "io.taotie.adapters..");
noClasses().that().resideInAPackage("io.taotie.plugin.api..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..", "jakarta.persistence..");
noClasses().that().resideInAPackage("io.taotie.plugins..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "io.taotie.bootstrap..", "io.taotie.adapters..");
```

规则须排除测试装配代码且发现真实被测类；`plugin.api→kernel`等项目包禁依赖另补规则，不能认为上面三条覆盖所有边界。通过一次合法插件、一次故意违规依赖、一次错误manifest的正反示例验证门禁本身，再恢复正常提交。

统一完整受理命令`./mvnw -B -ntp verify`（Windows为`mvnw.cmd`）；root POM绑定Spotless/静态/单元/契约/ArchUnit/Enforcer、打包和当前阶段必需的Failsafe真实DB/存储/worker集成。不得仅放在开发者自愿worker-it；缺必需资源必须失败，快速check不能替代完整门禁。P1证明真实suite失败/缺资源阻断及修复后通过，P2/P3执行已实施风险的集成和产物/恢复检查，记录测试数量与退出码。未实现功能不堆空测试，不能空suite假绿；本轮命令均未执行。

协作规则：AGENTS.md只含真实项目目录/构建/权限边界；CONTRIBUTING记录唯一构建命令、测试及PR策略。PR必须说明触发/行为变化、权限/副作用、已运行验证和未验证项；触及公开契约/权限/资源/模块方向写ADR（问题、选项、证据、决定、影响、可逆条件）。工具迁移PR更新该工具Schema快照和cloud-v1差异。许可证/门禁例外必须有owner、理由、范围、到期和复查，不无限关闭规则。当前无代码PR要求。

## 7. 统一阶段、估量与实验前提

以下采用主方案0.3的P0–P5；旧六工具P3底层/22–36人日不再套作网页首期工期，G成本仅为共享底座后的整包增量。报告质量、状态/事件、预算/保留和副作用规则按主稿。B5/B6是进入实验条件，性能阈值先基线后冻结。

| 阶段 | 前置与负责岗位 | 交付/退出条件 | 估量口径 |
| --- | --- | --- | --- |
| P0文档合稿/审阅 | 三份分件、清单与独立评审；架构合稿、产品最终业务判断 | 参考/框架/业务/工程/工具/验收一致；B1–B4文档闭合；评审阻断项明确 | 当前文档阶段，未计实现通过 |
| P1规范/骨架/门禁 | 产品审阅、真实目标绑定；后端实现、架构/测试核对、平台供环境 | 单制品角色、编译契约/样例、正反门禁、配置/独立迁移、真实依赖证据 | 约5–8人日，版本/环境准备影响较大 |
| P2最小内核/协议试验 | P1；后端/架构，测试独立证据 | E1/E3/E4基础：单一循环、账本、租约、授权、预算、SSE、产物和未知结果成立；框架正式锁定 | 与P3共享工作一起重估，避免同一治理重复计费 |
| P3网页报告真实纵切 | P2；真实模型/搜索/网页/存储合法连接及安全准入；后端/最小客户端/测试/产品 | W01–W08、F01相关底层、真实模型与搜索、授权下载、失败/恢复/取消均有证据；产品认定报告质量 | P2+P3暂估20–40人日，仅设计量级；不是旧六工具22–36人日原封套用 |
| P4全部工具逐批迁移 | P3稳定契约；后端/平台/测试按依赖，产品决定启用顺序 | 附录72项逐项完整Schema/行为/许可/cloud差异/权限/取消/回退/真实验收；延后项仍在总范围 | G包增量区间用于比较，不能乘每个T或直接求全量工期 |
| P5产品/规模扩展 | 使用/负载与明确业务证据 | 按需复杂编排、产品交互或更多资源治理；独立纵切/容量/安全证据 | 尚无规模，不给总工期或性能承诺 |

估量假设为一名熟悉Java的后端主开发，架构/平台/前端/测试按需协作；独立测试库/存储/worker可用、单一模型和搜索provider、简单客户端。未含资源采购、授权等待、全量安全审计、商业发布和上游变化；E1/P1后重估。G表是共享底座就绪后的整包增量，不与上述共享工作相加。新增规范/仓库绑定和资源等待不凭其他产品资产省略。


关键实验E1–E8和业务W01–W08/F01-a/b与主稿一一对应；固定资源/合法身份前不实施。原文件/rg/隔离/产物/JS/Office风险语义保留，按相应批次启用前验收。没有构建、部署、模型调用或测试通过声明。

## 附录A：72项统一迁移基线

| 注册项/包组 | 名称/路径/阶段 | 必需参数；可选参数摘要 | 固定注册源码 | 许可/成本/权限/验收 |
| --- | --- | --- | --- | --- |
| T01 / G01 | `plugin_manager`<br>J/管理面；P4 | 必需：`action`<br>可选：`target`, `enabled`, `runtimeVersion`, `acceptRisk`, `approvedBuilds`, `registry`, `offset`, `limit` | [packages/boot/plugin-manager/src/tools.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/boot/plugin-manager/src/tools.ts) | MIT第一方；继承G01；完整Schema见JSON中T01 |
| T02 / G02 | `list_mcp_resource_templates`<br>P/MCP；P4 | 必需：`server`<br>可选：`cursor` | [packages/mcp/mcp-resources/src/tools.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/mcp/mcp-resources/src/tools.ts) | MIT第一方；继承G02；完整Schema见JSON中T02 |
| T03 / G02 | `list_mcp_resources`<br>P/MCP；P4 | 必需：`server`<br>可选：`cursor` | [packages/mcp/mcp-resources/src/tools.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/mcp/mcp-resources/src/tools.ts) | MIT第一方；继承G02；完整Schema见JSON中T03 |
| T04 / G02 | `read_mcp_resource`<br>P/MCP；P4 | 必需：`server`, `uri`<br>可选：无 | [packages/mcp/mcp-resources/src/tools.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/mcp/mcp-resources/src/tools.ts) | MIT第一方；继承G02；完整Schema见JSON中T04 |
| T05 / G03 | `stagehand_act`<br>W/Node浏览器；P4 | 必需：`instruction`<br>可选：`pageId` | [packages/experimental/browser-use-stagehand-native/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/browser-use-stagehand-native/src/index.ts) | MIT第一方；继承G03；完整Schema见JSON中T05 |
| T06 / G03 | `stagehand_extract`<br>W/Node浏览器；P4 | 必需：`instruction`<br>可选：`pageId`, `schema` | [packages/experimental/browser-use-stagehand-native/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/browser-use-stagehand-native/src/index.ts) | MIT第一方；继承G03；完整Schema见JSON中T06 |
| T07 / G03 | `stagehand_navigate`<br>W/Node浏览器；P4 | 必需：`url`<br>可选：`pageId` | [packages/experimental/browser-use-stagehand-native/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/browser-use-stagehand-native/src/index.ts) | MIT第一方；继承G03；完整Schema见JSON中T07 |
| T08 / G03 | `stagehand_observe`<br>W/Node浏览器；P4 | 必需：`instruction`<br>可选：`pageId` | [packages/experimental/browser-use-stagehand-native/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/browser-use-stagehand-native/src/index.ts) | MIT第一方；继承G03；完整Schema见JSON中T08 |
| T09 / G03 | `stagehand_screenshot`<br>W/Node浏览器；P4 | 必需：`fullPage`<br>可选：`pageId` | [packages/experimental/browser-use-stagehand-native/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/browser-use-stagehand-native/src/index.ts) | MIT第一方；继承G03；完整Schema见JSON中T09 |
| T10 / G03 | `stagehand_tabs`<br>W/Node浏览器；P4 | 必需：无<br>可选：无 | [packages/experimental/browser-use-stagehand-native/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/browser-use-stagehand-native/src/index.ts) | MIT第一方；继承G03；完整Schema见JSON中T10 |
| T11 / G04 | `ask_user_question`<br>J/人机交互；P4 | 必需：`questions`<br>可选：无 | [packages/interaction/tool-ask-user/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/interaction/tool-ask-user/src/index.ts) | MIT第一方；继承G04；完整Schema见JSON中T11 |
| T12 / G05 | `run_code`<br>W/PTC＋J调度；P4 | 必需：`description`, `code`<br>可选：`timeoutMs`, `sandbox_permissions`, `justification` | [packages/core/tools/src/ptc.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/core/tools/src/ptc.ts) | MIT第一方；继承G05；完整Schema见JSON中T12 |
| T13 / G06 | `exit_plan_mode`<br>J/计划状态；P4 | 必需：`plan`<br>可选：无 | [packages/plan/plan-mode/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/plan/plan-mode/src/index.ts) | MIT第一方；继承G06；完整Schema见JSON中T13 |
| T14 / G07 | `bash`<br>W/Linux单次shell；P4 | 必需：`description`, `command`<br>可选：`timeoutMs`, `workdir`, `run_in_background` | [packages/shell/tool-bash/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-bash/src/index.ts) | MIT第一方；继承G07；完整Schema见JSON中T14 |
| T15 / G08 | `present`<br>J＋产物存储；P3 | 必需：`files`<br>可选：无 | [packages/deliverables/tool-present/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/deliverables/tool-present/src/index.ts) | MIT第一方；继承G08；完整Schema见JSON中T15 |
| T16 / G09 | `pwsh`<br>W/Windows单次shell；P4 | 必需：`description`, `command`<br>可选：`timeoutMs`, `workdir`, `run_in_background` | [packages/shell/tool-pwsh/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-pwsh/src/index.ts) | MIT第一方；继承G09；完整Schema见JSON中T16 |
| T17 / G10 | `cordis_inspect_list`<br>D/另做诊断；P4 | 必需：无<br>可选：无 | [packages/extensions/tool-cordis/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/extensions/tool-cordis/src/index.ts) | MIT第一方；继承G10；完整Schema见JSON中T17 |
| T18 / G10 | `cordis_inspect_query`<br>D/另做诊断；P4 | 必需：`platform`, `provider`, `method`<br>可选：`input` | [packages/extensions/tool-cordis/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/extensions/tool-cordis/src/index.ts) | MIT第一方；继承G10；完整Schema见JSON中T18 |
| T19 / G11 | `bash`<br>W/持久PTY；P4 | 必需：`command`<br>可选：无 | [packages/shell/tool-bash-persistent/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-bash-persistent/src/index.ts) | MIT第一方；继承G11；完整Schema见JSON中T19 |
| T20 / G12 | `pwsh`<br>W/Windows持久PTY；P4 | 必需：`command`<br>可选：无 | [packages/shell/tool-pwsh-persistent/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-pwsh-persistent/src/index.ts) | MIT第一方；继承G12；完整Schema见JSON中T20 |
| T21 / G13 | `str_replace_editor`<br>J/文件编辑；P4 | 必需：`command`, `path`<br>可选：`file_text`, `insert_line`, `new_str`, `old_str`, `view_range` | [packages/fs/tool-str-replace-editor/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-str-replace-editor/src/index.ts) | MIT第一方；继承G13；完整Schema见JSON中T21 |
| T22 / G14 | `edit`<br>J/文件与图片；P4 | 必需：`file_path`, `old_string`, `new_string`<br>可选：`replace_all` | [packages/fs/tool-fs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/index.ts) | MIT第一方；继承G14；完整Schema见JSON中T22 |
| T23 / G14 | `read`<br>J/文件与图片；P3 | 必需：`file_path`<br>可选：`offset`, `limit` | [packages/fs/tool-fs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/index.ts) | MIT第一方；继承G14；完整Schema见JSON中T23 |
| T24 / G14 | `read_image`<br>J/文件与图片；P4 | 必需：`file_path`<br>可选：无 | [packages/fs/tool-fs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/index.ts) | MIT第一方；继承G14；完整Schema见JSON中T24 |
| T25 / G14 | `write`<br>J/文件与图片；P3 | 必需：`file_path`, `content`<br>可选：无 | [packages/fs/tool-fs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/index.ts) | MIT第一方；继承G14；完整Schema见JSON中T25 |
| T26 / G15 | `glob`<br>W/rg＋J适配；P4 | 必需：`pattern`<br>可选：`path` | [packages/fs/tool-fs-search/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs-search/src/index.ts) | MIT第一方；继承G15；完整Schema见JSON中T26 |
| T27 / G15 | `grep`<br>W/rg＋J适配；P4 | 必需：`pattern`<br>可选：`path`, `include` | [packages/fs/tool-fs-search/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs-search/src/index.ts) | MIT第一方；继承G15；完整Schema见JSON中T27 |
| T28 / G16 | `terminal_close`<br>W/终端控制；P4 | 必需：`sessionId`<br>可选：无 | [packages/experimental/tool-terminal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-terminal/src/index.ts) | MIT第一方；继承G16；完整Schema见JSON中T28 |
| T29 / G16 | `terminal_list`<br>W/终端控制；P4 | 必需：无<br>可选：无 | [packages/experimental/tool-terminal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-terminal/src/index.ts) | MIT第一方；继承G16；完整Schema见JSON中T29 |
| T30 / G16 | `terminal_open`<br>W/终端控制；P4 | 必需：`type`<br>可选：`name`, `cwd` | [packages/experimental/tool-terminal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-terminal/src/index.ts) | MIT第一方；继承G16；完整Schema见JSON中T30 |
| T31 / G16 | `terminal_read`<br>W/终端控制；P4 | 必需：`sessionId`<br>可选：`offset`, `count` | [packages/experimental/tool-terminal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-terminal/src/index.ts) | MIT第一方；继承G16；完整Schema见JSON中T31 |
| T32 / G16 | `terminal_send`<br>W/终端控制；P4 | 必需：`sessionId`, `text`<br>可选：`submit`, `run_in_background` | [packages/experimental/tool-terminal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-terminal/src/index.ts) | MIT第一方；继承G16；完整Schema见JSON中T32 |
| T33 / G16 | `terminal_signal`<br>W/终端控制；P4 | 必需：`sessionId`, `signal`<br>可选：无 | [packages/experimental/tool-terminal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-terminal/src/index.ts) | MIT第一方；继承G16；完整Schema见JSON中T33 |
| T34 / G17 | `create_goal`<br>J/目标状态机；P4 | 必需：`objective`<br>可选：`max_goal_rounds` | [packages/goal/tool-goal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/goal/tool-goal/src/index.ts) | MIT第一方；继承G17；完整Schema见JSON中T34 |
| T35 / G17 | `get_goal`<br>J/目标状态机；P4 | 必需：无<br>可选：无 | [packages/goal/tool-goal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/goal/tool-goal/src/index.ts) | MIT第一方；继承G17；完整Schema见JSON中T35 |
| T36 / G17 | `update_goal`<br>J/目标状态机；P4 | 必需：`goal_id`, `revision`, `action`<br>可选：`objective`, `max_goal_rounds`, `blocked_reason` | [packages/goal/tool-goal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/goal/tool-goal/src/index.ts) | MIT第一方；继承G17；完整Schema见JSON中T36 |
| T37 / G18 | `schedule_create`<br>J/持久调度；P4 | 必需：`prompt`, `title`<br>可选：`after_seconds`, `every_seconds`, `daily`, `weekly`, `cron`, `at` | [packages/schedule/tool-schedule/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/schedule/tool-schedule/src/index.ts) | MIT第一方；继承G18；完整Schema见JSON中T37 |
| T38 / G18 | `schedule_delete`<br>J/持久调度；P4 | 必需：`id`<br>可选：无 | [packages/schedule/tool-schedule/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/schedule/tool-schedule/src/index.ts) | MIT第一方；继承G18；完整Schema见JSON中T38 |
| T39 / G18 | `schedule_list`<br>J/持久调度；P4 | 必需：无<br>可选：无 | [packages/schedule/tool-schedule/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/schedule/tool-schedule/src/index.ts) | MIT第一方；继承G18；完整Schema见JSON中T39 |
| T40 / G18 | `schedule_update`<br>J/持久调度；P4 | 必需：`id`<br>可选：`title`, `prompt`, `every_seconds`, `daily`, `weekly`, `cron`, `at` | [packages/schedule/tool-schedule/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/schedule/tool-schedule/src/index.ts) | MIT第一方；继承G18；完整Schema见JSON中T40 |
| T41 / G19 | `lsp`<br>P/LSP worker；P4 | 必需：`operation`, `file_path`, `line`, `character`<br>可选：无 | [packages/lsp/tool-lsp/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/lsp/tool-lsp/src/index.ts) | MIT第一方；继承G19；完整Schema见JSON中T41 |
| T42 / G20 | `ralph`<br>D后轮次编排；P4 | 必需：`objective`<br>可选：`maxRounds` | [packages/experimental/tool-ralph/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-ralph/src/index.ts) | MIT第一方；继承G20；完整Schema见JSON中T42 |
| T43 / G21 | `skill`<br>J/技能资源；P4 | 必需：`name`<br>可选：无 | [packages/skill/tool-skill/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/skill/tool-skill/src/index.ts) | MIT第一方；继承G21；完整Schema见JSON中T43 |
| T44 / G22 | `session_event_read`<br>J/事件检索；P4 | 必需：`seq`<br>可选：`session_id`, `before`, `after` | [packages/experimental/tool-session-query/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts) | MIT第一方；继承G22；完整Schema见JSON中T44 |
| T45 / G22 | `session_event_search`<br>J/事件检索；P4 | 必需：`query`<br>可选：`session_id`, `seq_from`, `seq_to`, `time_from`, `time_to`, `event_types`, `surfaces` | [packages/experimental/tool-session-query/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts) | MIT第一方；继承G22；完整Schema见JSON中T45 |
| T46 / G22 | `session_event_trace`<br>J/事件检索；P4 | 必需：`seq`<br>可选：`session_id` | [packages/experimental/tool-session-query/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts) | MIT第一方；继承G22；完整Schema见JSON中T46 |
| T47 / G22 | `session_search`<br>J/事件检索；P4 | 必需：`query`<br>可选：`session_ids`, `created_at_from`, `created_at_to`, `parent_session_ids`, `include_root_sessions`, `availability`, `event_seq_from`, `event_seq_to`, `event_time_from`, `event_time_to`, `event_types`, `event_surfaces` | [packages/experimental/tool-session-query/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts) | MIT第一方；继承G22；完整Schema见JSON中T47 |
| T48 / G22 | `session_trace`<br>J/事件检索；P4 | 必需：无<br>可选：`session_id` | [packages/experimental/tool-session-query/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts) | MIT第一方；继承G22；完整Schema见JSON中T48 |
| T49 / G23 | `list_subagent_models`<br>J/子Agent调度；P4 | 必需：无<br>可选：`provider`, `model` | [packages/subagent/tool-subagent/src/list-models.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent/src/list-models.ts) | MIT第一方；继承G23；完整Schema见JSON中T49 |
| T50 / G23 | `subagent`<br>J/子Agent调度；P4 | 必需：`description`, `prompt`<br>可选：`cwd` | [packages/subagent/tool-subagent/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent/src/index.ts) | MIT第一方；继承G23；完整Schema见JSON中T50 |
| T51 / G24 | `interrupt_agent`<br>J/子Agent控制；P4 | 必需：`agent_id`<br>可选：无 | [packages/subagent/tool-subagent-control/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent-control/src/index.ts) | MIT第一方；继承G24；完整Schema见JSON中T51 |
| T52 / G24 | `list_agents`<br>J/子Agent控制；P4 | 必需：无<br>可选：`scope` | [packages/subagent/tool-subagent-control/src/list-agents.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent-control/src/list-agents.ts) | MIT第一方；继承G24；完整Schema见JSON中T52 |
| T53 / G24 | `send_message`<br>J/子Agent控制；P4 | 必需：`agent_id`, `message`<br>可选：无 | [packages/subagent/tool-subagent-control/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent-control/src/index.ts) | MIT第一方；继承G24；完整Schema见JSON中T53 |
| T54 / G25 | `job_kill`<br>J/job＋worker；P4 | 必需：`job_id`<br>可选：`reason` | [packages/jobs/tool-jobs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/jobs/tool-jobs/src/index.ts) | MIT第一方；继承G25；完整Schema见JSON中T54 |
| T55 / G25 | `job_list`<br>J/job＋worker；P4 | 必需：无<br>可选：无 | [packages/jobs/tool-jobs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/jobs/tool-jobs/src/index.ts) | MIT第一方；继承G25；完整Schema见JSON中T55 |
| T56 / G25 | `job_output`<br>J/job＋worker；P4 | 必需：`job_id`<br>可选：`wait`, `timeout_ms` | [packages/jobs/tool-jobs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/jobs/tool-jobs/src/index.ts) | MIT第一方；继承G25；完整Schema见JSON中T56 |
| T57 / G26 | `interrupt_agent`<br>D/团队系统；P4 | 必需：`target`<br>可选：无 | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T57 |
| T58 / G26 | `list_agents`<br>D/团队系统；P4 | 必需：无<br>可选：无 | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T58 |
| T59 / G26 | `send_message`<br>D/团队系统；P4 | 必需：`target`, `message`<br>可选：无 | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T59 |
| T60 / G26 | `spawn_teammate`<br>D/团队系统；P4 | 必需：`name`, `description`, `prompt`<br>可选：`context` | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T60 |
| T61 / G26 | `team_task_create`<br>D/团队系统；P4 | 必需：`subject`, `description`<br>可选：`blocked_by`, `write_scopes` | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T61 |
| T62 / G26 | `team_task_get`<br>D/团队系统；P4 | 必需：`task_id`<br>可选：无 | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T62 |
| T63 / G26 | `team_task_list`<br>D/团队系统；P4 | 必需：无<br>可选：`status`, `owner`, `ready`, `cursor`, `limit` | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T63 |
| T64 / G26 | `team_task_update`<br>D/团队系统；P4 | 必需：`task_id`, `expected_revision`, `action`<br>可选：`subject`, `description`, `blocked_by`, `write_scopes`, `owner` | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T64 |
| T65 / G26 | `wait_agent`<br>D/团队系统；P4 | 必需：无<br>可选：`timeout_ms` | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) | MIT第一方；继承G26；完整Schema见JSON中T65 |
| T66 / G27 | `todo_write`<br>J/清单投影；P4 | 必需：`todos`<br>可选：无 | [packages/todo/tool-todo/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/todo/tool-todo/src/index.ts) | MIT第一方；继承G27；完整Schema见JSON中T66 |
| T67 / G28 | `working_directory`<br>J/工作区上下文；P4 | 必需：无<br>可选：`cd` | [packages/session/tool-working-directory/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session/tool-working-directory/src/index.ts) | MIT第一方；继承G28；完整Schema见JSON中T67 |
| T68 / G29 | `create_worktree`<br>W/Git；P4 | 必需：无<br>可选：`name`, `from` | [packages/experimental/tool-worktree/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-worktree/src/index.ts) | MIT第一方；继承G29；完整Schema见JSON中T68 |
| T69 / G30 | `workflow`<br>W/JS编排＋J调度；P4 | 必需：`script`, `meta`<br>可选：`args`, `run_in_background` | [packages/workflow/tool-workflow/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/workflow/tool-workflow/src/index.ts) | MIT第一方；继承G30；完整Schema见JSON中T69 |
| T70 / G31 | `load_workspace_dependencies`<br>J/worker清单；P4 | 必需：无<br>可选：无 | [packages/skill/tool-workspace-dependencies/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/skill/tool-workspace-dependencies/src/index.ts) | MIT第一方；继承G31；完整Schema见JSON中T70 |
| T71 / G32 | `web_fetch`<br>J/HTTP＋P搜索；P3 | 必需：`url`<br>可选：无 | [packages/web/tool-web/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/web/tool-web/src/index.ts) | MIT第一方；继承G32；完整Schema见JSON中T71 |
| T72 / G32 | `web_search`<br>J/HTTP＋P搜索；P3 | 必需：`queries`<br>可选：无 | [packages/web/tool-web/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/web/tool-web/src/index.ts) | MIT第一方；继承G32；完整Schema见JSON中T72 |

### A.1 来源文件索引（全部固定提交、MIT第一方源）

| 来源 | 包含注册/操作 | 完整路径 |
| --- | --- | --- |
| F01 | T01 | [packages/boot/plugin-manager/src/tools.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/boot/plugin-manager/src/tools.ts) |
| F02 | T02, T03, T04 | [packages/mcp/mcp-resources/src/tools.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/mcp/mcp-resources/src/tools.ts) |
| F03 | T05, T06, T07, T08, T09, T10 | [packages/experimental/browser-use-stagehand-native/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/browser-use-stagehand-native/src/index.ts) |
| F04 | T11 | [packages/interaction/tool-ask-user/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/interaction/tool-ask-user/src/index.ts) |
| F05 | T12 | [packages/core/tools/src/ptc.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/core/tools/src/ptc.ts) |
| F06 | T13 | [packages/plan/plan-mode/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/plan/plan-mode/src/index.ts) |
| F07 | T14 | [packages/shell/tool-bash/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-bash/src/index.ts) |
| F08 | T15 | [packages/deliverables/tool-present/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/deliverables/tool-present/src/index.ts) |
| F09 | T16 | [packages/shell/tool-pwsh/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-pwsh/src/index.ts) |
| F10 | T17, T18 | [packages/extensions/tool-cordis/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/extensions/tool-cordis/src/index.ts) |
| F11 | T19 | [packages/shell/tool-bash-persistent/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-bash-persistent/src/index.ts) |
| F12 | T20 | [packages/shell/tool-pwsh-persistent/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/shell/tool-pwsh-persistent/src/index.ts) |
| F13 | T21 | [packages/fs/tool-str-replace-editor/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-str-replace-editor/src/index.ts) |
| F14 | T22, T23, T24, T25 | [packages/fs/tool-fs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs/src/index.ts) |
| F15 | T26, T27 | [packages/fs/tool-fs-search/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/fs/tool-fs-search/src/index.ts) |
| F16 | T28, T29, T30, T31, T32, T33 | [packages/experimental/tool-terminal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-terminal/src/index.ts) |
| F17 | T34, T35, T36 | [packages/goal/tool-goal/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/goal/tool-goal/src/index.ts) |
| F18 | T37, T38, T39, T40 | [packages/schedule/tool-schedule/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/schedule/tool-schedule/src/index.ts) |
| F19 | T41 | [packages/lsp/tool-lsp/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/lsp/tool-lsp/src/index.ts) |
| F20 | T42 | [packages/experimental/tool-ralph/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-ralph/src/index.ts) |
| F21 | T43 | [packages/skill/tool-skill/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/skill/tool-skill/src/index.ts) |
| F22 | T44, T45, T46, T47, T48 | [packages/experimental/tool-session-query/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-session-query/src/index.ts) |
| F23 | T49 | [packages/subagent/tool-subagent/src/list-models.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent/src/list-models.ts) |
| F24 | T50 | [packages/subagent/tool-subagent/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent/src/index.ts) |
| F25 | T51, T53 | [packages/subagent/tool-subagent-control/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent-control/src/index.ts) |
| F26 | T52 | [packages/subagent/tool-subagent-control/src/list-agents.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/subagent/tool-subagent-control/src/list-agents.ts) |
| F27 | T54, T55, T56 | [packages/jobs/tool-jobs/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/jobs/tool-jobs/src/index.ts) |
| F28 | T57, T58, T59, T60, T61, T62, T63, T64, T65 | [packages/experimental/tool-agent-team/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-agent-team/src/index.ts) |
| F29 | T66 | [packages/todo/tool-todo/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/todo/tool-todo/src/index.ts) |
| F30 | T67 | [packages/session/tool-working-directory/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/session/tool-working-directory/src/index.ts) |
| F31 | T68 | [packages/experimental/tool-worktree/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/experimental/tool-worktree/src/index.ts) |
| F32 | T69 | [packages/workflow/tool-workflow/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/workflow/tool-workflow/src/index.ts) |
| F33 | T70 | [packages/skill/tool-workspace-dependencies/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/skill/tool-workspace-dependencies/src/index.ts) |
| F34 | T71, T72 | [packages/web/tool-web/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/571902028a75c64c1d767f4ae5fd099485f0ddf0/packages/web/tool-web/src/index.ts) |

## 附录B：交付验证记录

| 检查 | 实际结果 |
| --- | --- |
| 工具目录解析断言 | 72注册项、67不同名、32包；每项映射迁移路径，34来源无缺失 |
| 来源完整性 | 682个取得文件均匹配固定提交tree中的Git blob SHA-1及记录SHA-256 |
| 报告源码链接 | 52个不同来源路径均在固定tree和已取得manifest内；未引用master浮动源码 |
| 附件JSON/报告JSON示例 | JSON.parse校验成功；清单保留完整输入Schema并附迁移映射 |
| 范围/占位核验 | 报告已替换旧范围材料；无旧项目引用、虚构current_time工具或待填占位 |
| 运行验证 | 未执行上游工具/测试、未编译Java、未部署；第3/6/7节测试和命令均为未来任务 |

本材料为后端原证据的架构合稿1.1，父事项仍为in_progress，待产品组织R1–R3/B1–B4针对性复核。实现按主稿阶段另立依赖清晰的任务，本轮无产品运行验证。

## 合稿1.1权威口径

工程规范使用本次0.2，插件运行标识为invocationId，状态/错误/预算按主稿0.3；旧0.1规范及研究v1中的冲突候选不再作为实施依据。web_fetch@0.2.0仅采用本次唯一输出Schema源，0.1.0两份不兼容设计草稿被替代。完整verify必跑实际已实施阶段的必要worker集成，缺资源失败。许可、固定来源与输入Schema未改；本轮仅静态文档修订，独立审查/测试尚待复核。
