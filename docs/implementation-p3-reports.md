# P3 工作区工具与报告发布装配

本阶段沿既有唯一 ToolCallingAdvisor 循环接入 `read`、`write`、`present`。这是后端本地 TLS/模型替身装配，不代表真实模型、搜索或完整 P3 验收。生产公共出口及付费派发仍默认关闭。

## 配置和准入

worker 可配置 `jimu.workspace-root`，由运营者预先创建专用 Linux/ext4 目录，权限必须为 `0700`；不创建共享目录、不接受租户指定根路径。未配置时，含文件能力的任务在模型 HTTP 前以 `WORKSPACE_NOT_CONFIGURED` 拒绝。文件能力仅用于 public 装配；fixture 0.2.0 及现行 P2 行为保留。

控制面不自动授予能力。运营者按现行 installation/epoch 机制安装 `read`、`write`（`jimu.workspace.*@0.1.0`）和 `present`（`jimu.artifact.present@0.1.0`），digest 为各打包输出 Schema 的 SHA256。任务显式请求 `web_fetch/read/write/present`，受理时固定各自版本、digest、epoch，执行及缓存复用前复查 pin/当前授权。现行 API 缺省仍为 `web_fetch`；仅抓取不能满足公共任务的报告成功条件。`web_search` 未准入，保持禁用适配和原平台/价目/权限依赖，未冒充五工具全部完成。

模型参数直接来自固定基线：`read(file_path, offset?, limit?)`，`write(file_path, content)`，`present(files:[{path,description?}])`。行号从 1 开始，缺省最多 2000 行，present 为 1–4 个不重复路径。宿主额外拒绝未知参数及非整数行界；模型不能传 tenant/task/operationId/CAS。保留现有协议单槽参数 16KiB 上界，因此模型 write 的实际正文上限更小于工作区/报告上限，超限在执行前拒绝，不声称模型可一次写满 1MiB。

read 返回行号内容、缺失/版本/摘要等 canonical 结果；write 返回实际 CAS 文件收据。输出 Schema 与 manifest 随新工具版本打包，输入参数与不可变 inventory 的等价性由默认契约测试核验（仅补服务端 dialect 声明）。这不是 Harness 原始输出的逐字兼容声明。present description 只作模型输入说明，不控制文件名、媒体类型或授权。

## 文件与恢复

每个新准入调用仅以账本生成的稳定 effectId 执行 `bind`，冻结当时观察。真实打开仍通过 SecureDirectoryStream 和跨进程锁，逻辑路径不拼接为 OS 文件路径；所有文件/观察/绑定/操作收据按 tenant+task 隔离。write 未观察、版本陈旧、路径越界等已确认前置拒绝记工具 FAILED 并结算已准入次数；未知存储 IO 则保留 OUTCOME_UNKNOWN/HELD_UNKNOWN。

成功调用由校验过摘要的持久输出缓存恢复，不重复执行文件操作。RUNNING/OUTCOME_UNKNOWN 沿现行账本明确拒绝自动重放；不会在恢复时再调用 bind、捕获缺失后重新绑定，或重置费用状态。基础 store 的 resume 只供具备原绑定/调用意图证明的恢复；本阶段没有扩大自动恢复准入。格式 1 / 缺意图依旧拒绝，R6 原 CAS 规则不变。

## 报告与原子发布

公共任务的最终成功必须存在实际 present 产生的已发布报告；旧的“最后成功 fetch + 模型 summary”产物路径在公共模式明确拒绝。文件须为 Markdown、UTF-8、最多 1MiB，具备非空的六个 Markdown 栏目：主题、摘要、结论、来源链接、生成时间、局限。

V6 为新任务在受理事务保存宿主报告时间 `report_time`，通过持久系统消息提供给模型；生成时间栏目须精确匹配此保留时间。实际上传意图/发布时间另由 artifact.created_at/updated_at 记录。不拿模型自报时钟代替宿主时间；历史任务不伪造回填该元数据。

证据来自本任务已成功提交、摘要一致的 public0.3.0 fetch 调用。校验先剥离 `public-text-v1` 包装，空页不因标记/来源 URL 的长度被算作正文。非 2xx、截断、短正文、登录/错误/搜索摘要提示不计有效来源；全部无效为 `FAILED/NO_EVIDENCE`。结论必须含引文、来源链接非空、所有报告 HTTP(S) 引文属于有效抓取来源，最多 8 来源；部分无效须在局限中明确包含“部分来源失败”。这是保守的确定性筛选，不能识别任意伪装错误页，亦不证明结论由引文蕴含，业务质量仍需人工/真实评测。

present 用自己的 effectId 固定原观察版本并取得每个文件的不可变快照。文件 path/version/hash/size/mediaType 的收据及自身 invocation、上传 ID 在事务内持久化后，才在事务外写入/fsync 不可变对象并重新核验。输出 Schema 验证通过后，同一当前 fence 事务原子提交所有文件发布、present SUCCEEDED、artifact.created/tool.succeeded 事件及 TOOL/STORAGE 结算。取消、撤权、deadline 和旧租约在此事务重新拒绝；未经确认的上传不对下载可见。

上传前 P0、上传后提交前 P1、原子提交后 P2 及 write 成功后 W1 为明确 test-mode 故障窗口。P0/P1 未确认调用在重启后保持未知、不得盲重放；P2/W1 成功缓存可继续新 JVM，不能双写、双计次或双发布。STAGING 对象沿既有实际静止及过期清理规则保留；不以 TTL 证明写者消失。

下载沿既有 tenant/owner/撤权/expiry/hash 校验，Markdown 返回 `text/markdown; charset=utf-8`、宿主 UUID `.md` 附件文件名及 `nosniff`；旧 text/plain 产物仍为 `.txt`，不暴露工作区/宿主路径。

## 迁移和验证

只增加 V6；基线与 V1–V5 保留原字节。V6 增加 report_time/file_receipt/receipt_digest，把原“一调用一产物”唯一键扩为“一调用每收据一产物”，支持单次 present 多文件；旧产物继续可读。严格 schema check 要求 V1–V6，离线升级仍先停受理、确认私有 worker 静止再迁移，不允许旧 JAR 接管 V6。无破坏性回滚。P2 的 V2 重建及受控 V4/V5 升级测试同步撤销隔离库的 V6，随后由真实 Flyway 重建；完整 P2 不删减。

默认新增 ReportWorkerIT，真实 control/worker JVM、MySQL、ext4 文件存储、本地 TLS 及模型替身验证实际 producer/精确收据/授权下载、多文件、缺证据/伪引用/前置拒绝、跨 task/tenant/owner、取消与已确认/未知恢复。测试 TLS 仅在 test classpath 映射已校验逻辑 URL；正文包装的 Source 与 canonical sourceUrl 一起映射回原逻辑 URL，生产网络/TLS 政策未放宽。产品 JAR 不含测试入口。默认命令仍为完整 `./mvnw -B -ntp verify`，缺必需 MySQL/POSIX/OpenSSL 明确失败。

准确 SHA 的运行数量、原始 XML、SQL、文件与清理证据随 Multica 附件交付；开发定向结果与准确 SHA 全门禁分列。历史自然 readiness 超时、旧 CI SQL 及先前瞬时全库事务失败的具体事务原因仍未确认，不与本阶段已解释的开发失败混写。
