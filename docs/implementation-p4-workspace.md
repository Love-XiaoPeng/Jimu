# P4 首批 edit / glob / grep 实施契约

2026-10-22；XIAO-42 → XIAO-41；架构交接，待产品经理验收。本文及配套 JSON 是待实现契约，不是已注册工具或产品验收。建议后端将本文件纳入 `docs/implementation-p4-workspace.md`，将第 3 节决定摘录为 ADR-0005；若编号已被占用则顺延。

## 1. 已核实的起点与本轮决定

实际 `HEAD=origin/master=f6a97168281990fd036cc18bf0ddef8045d90a6d`，tree `993eca14bcbf436951bbfdc17b63fcc01c00c120`，工作树干净。GitHub PR9 的 API 回读为 `closed / merged=true`，merge SHA 同上，合并时间 `2026-10-21T14:04:50Z`；Multica PR 缓存仍报 open，属于元数据不一致，不能据此重复合并。配套证据有回读值。

沿用总体方案 0.3 §10 / F01-b / G14 / G15、工程规范 0.2、工具清单及迁移契约 1.1、ADR-0001–0004。原始基线不变。P3 已有真实模型/公开网页报告的验收与交付，不沿用 README 中“真实模型、公开网页报告未验收”的过期当前态；真实 web_search provider 仍未准入。历史文档的“当时未执行”是历史事实，不改写。

采用以下最小增量：

1. edit 在现有工作区事务内完成字面匹配、CAS、内容和操作收据原子提交；不在插件中拼接 read→write。
2. 搜索从同一 tenant/task 的逻辑文件状态取得一次不可变快照，投影为临时普通文件树，再在空根、无网络的 Linux namespace 内运行固定版本 rg。禁止把状态根、产物根或任意宿主路径挂给子进程。
3. rg 结果完整采集后才算搜索成功；小页面进入模型结果，余下页面由同工具的可选 cursor 读取同一不可变结果。无需新增模型工具或下载 API。
4. 复用唯一 ToolCallingAdvisor、网关、账本、OperationThreads、WorkerLoop 和 LocalWorkerSupervisor。只扩展既有停止条件以涵盖真实原生进程，不建立第二套重试/恢复循环。

本次只交附件，未修改产品仓库、创建产品 PR、合并、部署或接触生产。后端将契约与实现一次入库其独立 PR，base master、关联 XIAO-41。这里不因文档交付要求完整 verify；不运行、启用、重跑或等待 GitHub CI。实现仍须必要本地门禁、准确 SHA 的独立审查和 QA。

## 2. 固定上游对照：完整输入、输出与错误

固定 Harness `571902028a75c64c1d767f4ae5fd099485f0ddf0`，不是浮动 master。工具清单 1.1 保留完整参数；本轮另外读取实际拆分后的实现：`packages/fs/tool-fs/src/{index,edit,error,mutation-result}.ts`、`packages/fs/fs-local/src/{index,fsio}.ts`、`packages/fs/fs-observation-policy/src/index.ts`、`packages/fs/tool-fs-search/src/{index,glob,grep,search-core}.ts`，以及根 LICENSE、package.json 和 pnpm-lock.yaml。

| 项 | 上游事实 | Jimu 本批契约与差异 |
| --- | --- | --- |
| T22 输入 | 必需 file_path/old_string/new_string，string；可选 replace_all，boolean，默认 false。空白路径、空 old、原始 old==new 在 parse 阶段拒绝。new 允许空，表示删除 | 保留这些字段/类型/默认值；所有对象 additionalProperties=false；不接受 tenant/task/operationId/版本/CAS 开关。上游在 confining FS 组合中可广告 sandbox_permissions/justification，本云端不提供越界升级，两个字段均拒绝 |
| T22 匹配 | 非重叠字面匹配；不是正则；0→FS_EDIT_NOT_FOUND，多于1且非 all→FS_AMBIGUOUS_EDIT。readForEdit 拒绝含 NUL 或非法 UTF-8 的旧内容 | 同一行为。拒绝输入未配对代理项；旧内容含 NUL→FS_NOT_TEXT。old/new 内 NUL 仍按上游字面字符串处理，可能生成后续不可 edit 的含 NUL 文件；grep 保留 rg 的二进制规则，不以 -a 改写 |
| T22 换行 | 原文、old、new 的 CRLF→LF；孤立 CR 保留。原文前4096个 UTF-16 code units 中，CRLF 数量大于其余 LF 数量则写回 CRLF，否则 LF；BOM 由 TextDecoder 默认忽略 | 同样处理，含 UTF-8 BOM 的旧内容匹配前去掉开头 BOM，成功后不重新插入。raw old!=new 但规范化后相同，沿上游允许完成一次版本递增，不暗改成 no-op。不做 NFC/NFKC、大小写、缩进或模糊匹配 |
| T22 输出/观察 | canonical 为 path/before/after；version 只进 observed 事件。render 是成功句，presentationMeta 为 contextual diff。无观察策略时允许 unconditional；挂策略时必须观察，absent→FS_NOT_FOUND | 本批采用现有 Jimu JSON 结果风格：filePath/version/sha256/size，Schema 见附件。不输出前后全文或宿主绝对路径，也不承诺上游 diff 卡。始终强制观察/CAS；不存在观察不能创建文件。明确属于新云端插件 v0.1.0 的输出差异 |
| T26 输入 | pattern 必需 string 且 trim 后非空；path 可选 string 且非空白。无 / 的 glob 按任意深度 basename 匹配；只列文件 | 保留。path 只允许与 FS 相同的逻辑相对路径；缺省或显式 `.` 为任务根，`./x`、`..`、绝对路径等按下文拒绝。新增可选 cursor，读取原结果，不重新搜索 |
| T26 输出/呈现 | canonical root/paths 是完整列表；--sort=modified（升序）；100 个 inline，超额采样由部署显式选择。排除6种 VCS。无文件 render 为 No files found；超额可 best-effort 保存格式化完整列表 | 新 canonical 为 root/paths/seen/returned/truncated/nextCursor；默认首屏保留上游 top-level round-robin 采样（100条），后续按原顺序补齐未展示条目。完整结果不再塞进模型或返回不可读的本地 spill 路径；保存失败明确失败，不伪称存在完整引用 |
| T27 输入 | pattern 非空，空白是合法 regex；path 可选文件/目录；include 是单个正 glob，禁止空白、开头 !、brace 之外的逗号；`*.{js,jsx}` 可用 | 同一判断；新增可选 cursor；保留 rg Rust regex 默认引擎，不切换 Java regex / PCRE2 / 多行 / fixed-string |
| T27 输出/呈现 | canonical matches，每项 path/lineNumber/line；--json 按匹配行解析，不按冒号切割；一行多个 submatches 仍计一条。忽略 begin/end/context/summary；缺关键字段失败；非 UTF-8 lines.bytes 用占位文本。inline250，line预览2000 UTF-8 bytes后加标记；完整格式化 spill 也使用行预览 | 新 canonical 增加 seen/returned/truncated/nextCursor、每项 lineTruncated。保持行号1起、去一个尾随 CRLF/LF、输出顺序。预览截断保持 UTF-8 边界；完整原行通过 read 读取（仍受 read 自身限制）。合法工作区内容为 UTF-8，仍应保留 bytes 分支的受控 fixture 验证 |
| 搜索执行/错误 | --no-config；固定 argv，stdin ignore；0成功、1成功无匹配、其余 SEARCH_*；stderr 检出 regex parse error / error parsing glob→SEARCH_INVALID_PATTERN；杀死/坏 JSON/不可访问→SEARCH_FAILED；raw 超20,000,000→SEARCH_RAW_OUTPUT_OVERFLOW；取消/超时→SEARCH_ABORTED | 保留原生匹配/过滤、原错误 detail；宿主取消/期限仍以 CANCEL_REQUESTED/DEADLINE_EXCEEDED 为首因，不能转普通成功文本继续模型。额外云端路径/隔离/游标/存储限额错误见第6节 |

上游 grep **不**加 --hidden/--no-ignore；.gitignore 是否生效保持 rg 的 require-git 语义，不能假设任何目录的 .gitignore 都生效。glob 包含隐藏/被忽略文件并排除 `.git/.svn/.hg/.bzr/.jj/.sl`。不要把两者的过滤统一成一个方便实现的规则。

固定的业务 argv（每个参数都是一个 argv 元素，不做 shell quoting）：

```text
glob: /rg --no-config --files --glob=<pattern> --sort=modified --no-ignore --hidden
      --glob=!**/.git --glob=!**/.git/** --glob=!**/.svn --glob=!**/.svn/**
      --glob=!**/.hg --glob=!**/.hg/** --glob=!**/.bzr --glob=!**/.bzr/**
      --glob=!**/.jj --glob=!**/.jj/** --glob=!**/.sl --glob=!**/.sl/** -- <logical-path-or-.>
grep: /rg --no-config --json --regexp=<pattern> [--glob=<include>] -- <logical-path-or-.>
```

`--` 后路径及 `--regexp=` / `--glob=` 的值不能展开成第二个参数。禁止 shell、PATH 查找、--pre、--pre-glob、--follow、--pcre2、自带配置文件和模型可选 argv。根缺省显式传 `.` 是云端差异，避免 stdin/当前目录的隐含选择。

许可：第一方 MIT / Copyright 2026 DeepSeek；复制或移植实质代码、fixture 时随源/制品保留根 LICENSE。上游 lock 固定 `@vscode/ripgrep(-linux-x64)@1.18.0`；不是“系统随便一个 rg”。本次校验包 SHA512 与 lock 一致，实际二进制为 rg15.0.0，静态链接、含 PCRE2 10.45（本批不启用 PCRE2 模式）。npm MIT 不能代替静态依赖许可：实施 PR 必须随实际二进制核完整 ripgrep/Rust/PCRE2 的 notices/SBOM；bubblewrap 为 LGPL-2.0-or-later，使用带 Ubuntu 修补的包须保留包 copyright、适用源代码获取方式与实际动态依赖许可。不得把全部传递闭包写成仅 MIT 已审完。

## 3. ADR-0005 的必要决定：文件快照 + FD 挂载 + 原生 namespace

### 3.1 为什么需要该边界

`SecureWorkspaceStore` 当前把整个 tenant/task 的逻辑文件与观察/操作收据放在一个原子 JSON 状态中；模型路径不会变成宿主 OS 路径。因此直接给 rg 当前目录既搜不到逻辑文件，也会扩大宿主读取范围。只做 Path.normalize/realpath 检查、或只使用 rg 默认“不跟随符号链接”，均不能保护显式传入的符号链接或目录改名竞态。

采用同机 Linux/POSIX 的一次只读投影，不新增服务、broker 或生产部署。Java 仍负责任务和工具；原生工具只承担搜索。备选是 Java regex（丢失 rg 语义，排除）、直接宿主 rg（越界风险，排除）、为这两个工具建立 Docker 常驻服务（引入 daemon/服务和生命周期，当前无必要）。

### 3.2 取得并投影同一工作区

1. 网关完成现行身份、工具 pin/epoch、租约、取消、预算校验并记录 invocation/effectId。租户、任务、操作身份、root 和 native limits 都由宿主传入；模型只能指定逻辑搜索条件。
2. 在现有分段锁 + FileChannel.lock + SecureDirectoryStream 的一次事务内读取该 scope 的文件、版本、hash、字节数、修改时间，形成不可变 snapshot digest。**不推进 observed**，glob/grep 结果不能替代 read 给 edit 授权。释放锁后搜索快照；不持 DB 事务等待 IO。
3. 仅投影当前 files，不投影历史 receipt、bindings、JSON 状态、锁、其他任务、产物或 Key。UTF-8 原字节保留，不在搜索时做换行/BOM规范化。路径继续复用 logicalPath；只创建普通文件/目录，禁止 symlink/hardlink/FIFO/socket/device。
4. 私有临时根0700、文件0600，用句柄相对打开和 CREATE_NEW/NOFOLLOW。目录创建后重新用 NOFOLLOW 的目录句柄打开；父目录始终为宿主独占，非模型可写。每份投影有宿主生成的随机 ID 与 scope/digest 清单。
5. 当前合法逻辑键可能同时出现 `a` 和 `a/b`，无法一一投影成普通树；必须在启动前返回 SEARCH_WORKSPACE_UNREPRESENTABLE，不漏掉其中一项或改名。旧文件不迁移/重写；此类命名冲突是明确剩余限制。

### 3.3 修改时间与格式兼容

现有 `FileValue(version,content,sha256,size)` 没有 mtime，不能把“本次 export 创建时间”当原修改时间排序，也不能比较每个文件各自的 version 来代表全局先后。

只对**从创建时 pins 已含本批工具的新任务**使用 workspace format3：FileValue/操作结果增加 `modifiedAt`（UTC epoch纳秒整数或等价可精确持久表示）；成功写/edit 时用注入 Clock，且大于该工作区已保存的最大 modifiedAt 至少1ns。最终值和内容/版本/操作结果同次 fsync+rename 提交，重放沿用原值。export 用该值设置真实 POSIX mtime，保留 rg --sort=modified。相同 mtime 按 rg 的实际 tie 行为，不承诺未声明的字典序。mtime 不是报告生成时间、数据库 DATETIME 或租约时间。

format2 的旧任务继续原 codec/原行为，不补伪造的 mtime，不改原状态字节；format1继续 FS_BINDING_UNPROVEN。新 worker 按 task pins 决定允许格式并校验实际头；旧 worker 遇 format3 拒绝。新任务初始化3必须发生在第一次 read/write/compose 之前；不能先用2写入，再在第一次 glob 时临时转换。现行 read/write/present 输入输出和插件 pin 不变，这些端口在 format3 额外保存的 mtime 不进入旧公开 DTO。

### 3.4 真实执行边界和 Java 与 FD 的连接

固定部署包包含 rg、bubblewrap、一个**窄 POSIX launcher**；不把实验 Python 脚本变成生产 runtime。JDK21 ProcessBuilder 没有受支持的 pass_fds，不能用反射取 JDK 私有 fd、预览 FFM 或把路径版 ProcessBuilder 冒称句柄传递。

后端实现小型 C/POSIX launcher：只接宿主签发的搜索规格，不提供任意命令执行。它以 openat/O_NOFOLLOW 从 `/` 逐层打开私有投影；fstat 核对 Java 传来的预期 dev/ino，固定该目录 FD。固定 bundle 的 rg 也以已核验 FD 打开。只把这两个 FD、独立 startup/status/gate pipes 交给 bwrap；不继承工作区父目录、配置、socket、DB 或秘密 FD。root 被换成 symlink 或其他 inode 则拒绝；已固定后再 rename，搜索仍指向原 inode。

原生边界使用已实测的固定选项：

```text
bwrap --unshare-all --die-with-parent --new-session --cap-drop ALL
      --clearenv --setenv LC_ALL C.UTF-8
      --ro-bind-fd <rg-fd> /rg --ro-bind-fd <projection-fd> /workspace
      --proc /proc --chdir /workspace
      --info-fd <dedicated-status-fd> --block-fd <dedicated-gate-fd>
      -- /rg --no-config <fixed-tool-argv>
```

不挂宿主 `/`、`/etc`、`/home`、`/mnt`、库目录或宿主 `/proc`；rg 是固定静态二进制。`--proc /proc` 是**新的 PID namespace 的 procfs**，rg --sort=modified 真实需要 `/proc/self/exe`。启动 FD 必须为独立管道：本轮实验把 --info-fd 设为 stdout 或 --block-fd 设为 stdin，造成子进程134退出；不能沿用这个实验错误。bwrap 消费后关闭相关 FD，rg stdin 接 /dev/null。负向测试要覆盖 `/proc/self/fd/<old-root-fd>/../other-task`，防 FD 泄漏绕过挂载边界。

launcher 启动即设置 parent-death SIGKILL 并复验父 PID，bwrap --die-with-parent 传播到 namespace；不使用 --as-pid-1，保留 reaper。用户 namespace、mount/PID/net 隔离或 FD 挂载能力缺失，搜索 readiness/准入失败，不降级宿主执行。bundle 在 worker 启动时验证版本、摘要、非 setuid 和一个无数据 namespace 自检；不在调用时下载安装软件。

本方案沿用现有私有根的威胁边界：不防同 OS 账户的管理进程、root 或卷管理员主动篡改；这些主体不得并发修改活动根。新 namespace 阻止 rg 访问宿主任意文件；固定经过审核的 rg 不等于给任意用户程序提供通用安全执行环境。

## 4. edit 的原子协议

保持 FilePort 旧接口，新增窄 `plugin.api.EditPort`，签名 `FilePort.Written edit(String filePath, String oldString, String newString, boolean replaceAll)`；由 SecureWorkspaceStore 的同一绑定能力实现，EditPlugin 只做 DTO/端口调用。scope/effectId/取消绑定方法与现有 bind/resume 一致，不出现在模型输入。

顺序如下，不能在插件外分别调用 read 和 write：

1. 解析类型、UTF-8、长度/总 JSON 字节；replace_all缺省false纳入规范参数摘要。路径采用原 logicalPath。
2. 在同一原生 workspace lock 下查原操作；kind=EDIT，摘要覆盖路径、原始 old/new、default后all及算法版本。已提交同 ID/摘要直接返回原 FileValue 收据；kind/参数不同 FS_OPERATION_MISMATCH。
3. 首次持久 EDIT intent 绑定原 binding 中的 expected。缺观察 FS_NOT_OBSERVED；观察为0 FS_NOT_FOUND。intent 持久后，任何恢复都不能采用后来 read 改过的全局 observed。
4. 先检查 current.version == expected 且存在，再匹配。变化/消失先报 FS_STALE_VERSION，不能先对新内容报零/多匹配。做第2节规定的 BOM/换行、字面替换；每次命中后从命中位置+old长度继续，`aaa` 替换 `aa` 只有一次，`aaaa` 则两次。
5. 校验结果 UTF-8、16MiB 单文件/总当前文件、128MiB状态和100条原收据限制。内容、新版本、hash/size、modifiedAt、observed和结果收据同次提交；提交前检查取消。失败不写文件或推进观察。
6. 相同操作恢复不改后来内容，不再递增版本/mtime。原 expected 已被另一 JVM 改变则冲突；最多一个 CAS 成功。操作意图未能证明时保留 FS_BINDING_UNPROVEN，不推断“没写”。

当前运行链在 T1 前崩溃的 RUNNING 调用会进入 OUTCOME_UNKNOWN / NEEDS_RECONCILIATION；本批不改变这条全局政策。SecureWorkspaceStore.resume 只为受信核对提供幂等证据，不因此把未知 edit 自动再派发。账本 SUCCEEDED 的恢复直接读 canonical 输出，绝不再次调用端口；收据损坏是完整性失败，不能重放掩盖。

## 5. 搜索分页、资源和停止收敛

### 5.1 公共契约与结果持久化

新增 `jimu.workspace.glob@0.1.0`、`jimu.workspace.grep@0.1.0` 和薄插件；端口使用 JDK 类型的 `SearchPort`，由 native adapter 实现，不向插件暴露 Path/Process/FD。SearchPort 接原逻辑参数及 optional cursor，返回附件的类型化 Page。

cursor 缺省才创建搜索。cursor 是宿主128-bit随机 ID（base64url字符，长度22–128），服务端记录其 tenant/task、源 invocation、工具名、规范查询摘要、插件 pin、snapshot digest、结果摘要和下一未读位置；不编码宿主路径，不仅凭 UUID 授权。带 cursor 时还必须传相同原查询字段（default后的 path 计入摘要）；读取同一 immutable result，不重新运行 rg，不接纳另一个任务的引用。权限/epoch/deadline仍每次准入重验；读取页也消耗现有一次工具调用额度。重复 cursor 幂等返回同页。

glob 首屏按上游 sampleAcrossTopLevel（group相对搜索root，保持各组原顺序，round-robin取100后按组展示）；后续游标按原排序补齐未展示索引。持久保存这一排列，任意一项只展示一次；64KiB分页字节上限如果先触发，只推进实际展示的条目。grep按原rg输出顺序，每页最多250匹配行。结果页限额包含序列化 JSON、标记与cursor。nextCursor=null表示条目已读完；truncated还可能仅因行预览截断而为true，需查看lineTruncated。seen是完整搜索的匹配行/路径数，不是已返回数或regex子匹配数。

结果对象完整采集/解析后保存为私有不可变文件，fsync+create-if-absent，并记录摘要/长度。采集/NDJSON解析时累计raw与canonical字节，超上限立即收进程；不能先构造无界List或完整巨行再检查。cursor页由同一安全存储句柄读取。它不是 PUBLISHED 报告，不向 `/api/artifacts` 伪造产物，也不把对象路径作为 spill locator。这样满足完整余项可读，避免改变 read@0.1.0 或另建下载权限链。搜索时不更新 read 观察，后续 edit 仍需 read。

### 5.2 起始限额（宿主可收紧，模型不可放大）

| 对象 | 限额与动作 |
| --- | --- |
| edit 参数 | old/new各最多16,384 UTF-8 bytes；canonical args总计≤65,535 UTF-8 bytes，匹配现有 invocation.input_snapshot TEXT 的实际边界；空new允许。不能先写数据库再发现TEXT溢出 |
| 搜索参数 | pattern≤8192 UTF-8 bytes，include≤4096；拒绝NUL/未配对代理项；path复用512个Java UTF-16 code units及原路径规则；cursor为22–128个base64url字符 |
| workspace/export | 当前文件16MiB、原完整状态128MiB、原binding/receipt各100不放大；一份export≤16MiB正文、4096个逻辑文件、8192个目录、512层路径；元数据与manifest另限8MiB，所以临时投影总预算24MiB/worker，超过→SEARCH_WORKSPACE_LIMIT |
| native raw stdout | 完整20,000,000 bytes；第N+1字节即停止、杀收进程并报SEARCH_RAW_OUTPUT_OVERFLOW，绝不把头部当完整结果 |
| native stderr/status | stderr保留64KiB tail并脱敏；startup/status单条≤4096 bytes、最多16条；不将原文/宿主路径暴露给用户或日志；计数溢出→SEARCH_FAILED |
| 结果保存 | 每个搜索完整canonical≤4MiB，单task全部搜索结果/游标索引≤8MiB、最多20个root；达到→SEARCH_RESULT_LIMIT，明确失败，不返回不可读取的nextCursor |
| 页面 | glob≤100项 / grep≤250行，序列化≤65,536 bytes；单行预览2000 UTF-8 bytes+明确标记；所有页和计数基于一次完整原生结果 |
| 运行 | search总deadline=min(task剩余,30s)，涵盖快照/export/排队/native/保存/清理；CPU软/硬10s/11s、地址空间512MiB、nofile64；这些rlimit在launcher exec前设置。rg固定argv和关闭preprocessor，不允许模型创建子任务。rlimit不冒称整棵进程树RSS或通用恶意代码隔离 |
| 并发/保留 | 复用当前worker一次一个attempt；每worker最多1个native search，无第二队列；新invocation仍受8模型/20工具/180s原任务上限。result保留最多7天且依赖task/调用保留；回收须有静止/无活动页读取证明，不能仅TTL删活动文件 |

在原 reservation/STORAGE 与 tenant storage_held 机制内为每个root先保留4MiB，成功转实际字节并退确定未用部分；与产物共用现有存储总上限，不提升默认16MiB tenant上限。原始20MB采集是有界内存，不持久raw spill；export临时资源单独限24MiB，停止/删除前不归还该worker槽。结果删除成功后才释放对应存储占用，失败保留待核对，不影响任何历史货币/UNKNOWN。实现要用专门category/稳定reservation键区分搜索结果，不能让原artifact清理逻辑误释放它。

### 5.3 进程收据与现有 supervisor

OperationThreads 只证明 Runnable 退出，LocalWorkerSupervisor 目前只证明 Java worker PID/启动tick退出；二者**目前没有原生树收齐的实现证据**。必须补最小原生收据并扩展相同 supervisor：

1. 准入短事务创建 PREPARED 搜索记录及存储预留。launcher先等待宿主握手；在启动bwrap前把自身PID/startTicks/bootId写进专有操作目录的durable launch receipt。任何子进程创建均在这之后。若worker在DB记录PID前死，现有supervisor从这份scope绑定收据读取，不能凭“DB无PID”假定未启动。
2. bwrap用独立info-fd上报namespace init的宿主PID；launcher查询startTicks/pidns，再上报Java。Java在原lease下保存launcher/bwrap/init三组身份及snapshot绑定，checkpoint后才释放独立gate，允许rg开始。取消/期限先到则不释放gate并收进程。status和业务stdout分离。
3. 父死信号覆盖worker→launcher，bwrap --die-with-parent覆盖launcher→namespace；原生helper保留子reaper、转发信号并waitpid，不能exec后遗失所有者。禁止仅用ProcessHandle.descendants()某一时刻的枚举作为树已空证明。
4. deadline/取消/overflow先停止新的工作，向**本次**launcher/bwrap/init发送TERM，最多3秒后KILL（不能为清理开启新业务deadline）。按PID+startTicks+bootId核对；进程身份不可读不是已停止。确认namespace init已退出/僵尸或原PID已被不同startTicks替换，且launcher/bwrap退出、stdout/stderr drainers都已结束，才记STOPPED、归还native运行槽和宣告attempt quiescent。
5. 扩展同一个LocalWorkerSupervisor的250ms协调和JdbcLedger.writersQuiescent/processStopped条件：Java worker退出不再足以把有native receipt的attempt标静止。旧attempt无native receipt保持原逻辑；未证明的launch窗口保守SEARCH_PROCESS_UNPROVEN/待核对，禁止换worker重跑或删export。append-only停止观察绑定原tenant/task/attempt/effect/worker，可仿照现有web取消诊断接收迟到事实，但不能凭旧fence提交结果、发布产物或释放未知费用。
6. 只有native确实退出、页/结果已验证且当前fence允许，才工具成功/T1；取消先受理不能任务成功。取消/失败同时保留真实已完成edit事实，不声称回滚。已SUCCEEDED搜索恢复返回原页/游标，不重新run rg；未确认调用沿原unknown政策停在核对，不新增自动只读重试。

## 6. manifest、Schema、数据迁移与回退

配套 `XIAO-42-p4-contract-v0.1.0.json` 给出三个完整input/output JSON Schema，属于新草案权威源；后端分别放入 `resources/plugins/builtin/{edit,glob,grep}/`，从这份交接生成一次后以仓库包内Schema为唯一源，不能长期维护两份。新URN使用jimu，不改web_fetch旧URN/摘要。manifest仍只维护plugin.yaml，生成catalog JSON；新三工具version0.1.0、hostApi `>=0.1.0 <0.2.0`，entry仍为审阅的trusted-jvm薄插件，实际native只能经宿主端口。新增EditPort/SearchPort是可选加法，不修改FilePort已有抽象方法或旧DTO。

新插件权限沿用`filesystem: [host-task-workspace]`；edit sideEffect=write，glob/grep=read，secretRefs/dependencies为空，timeoutMs30000；edit outputBytes1048576，search65536。search healthCheck为native-search-readiness，edit为workspace-readiness。新增能力需显式installation启用；默认搜索执行开关false，缺bundle/namespace/FD安全边界时不广告也不执行。不能只加载Schema就声称readiness通过。

对外HTTP任务形状、认证、事件、下载保持原契约。TaskController允许的名字新增三工具，数量上限5→7，支持`web_fetch/read/compose_report/present/edit/glob/grep`；这只是可声明集合，身份/installation/pin/epoch仍最终裁决。默认capabilityIds仍仅web_fetch，web_search仍disabled。不修改已有BusinessPolicies.Policy v1的四工具生产准入、付费开关或原评测名单；本批用隔离tenant、paid=false受控provider和现有test-mode public-worker装配验收。未来生产要接纳新增能力，另以受信新policy授权，不把本批离线许可解释成生产/付费许可。

数据库从已发布V1–V10向前新增V11（占用则用下一号），不改旧脚本。最小持久记录：

| 记录 | 字段/约束 | 实际访问模式 |
| --- | --- | --- |
| native_search_run | tenant/task/root invocation/effect/attempt/worker/host；launchNonce、phase(PREPARED/STARTED/STOPPED)、各PID/startTicks/bootId/pidns、snapshotDigest、queryDigest、tool/version/digest、resultKey/hash/size、created/stopped/expires、recordVersion1 | PK(tenant,rootInvocation)，UNIQUE(tenant,effect)；组合FK回原task/invocation/attempt。按(host,phase,id)协调，只扫未停止；按(tenant,task)核对/清理 |
| search_cursor | tenant/task/cursor随机ID、rootInvocation、nextIndex、page permutation/其摘要引用、queryDigest/toolPin、expires、recordVersion1 | PK(tenant,cursor)，UNIQUE(tenant,rootInvocation,nextIndex)，FK(tenant,rootInvocation)；每页的nextCursor只分配一次，重复读取得到同一后继；按tenant/task/root鉴权读，不做跨租户全局UUID查；按tenant/expires/root清理 |
| reservation | 原表新增SEARCH_STORAGE分类和稳定search:<root>键，金额字段不变；实际字节记actual | 同一run→tenant→task的现行锁顺序，短事务预留/结算，UNIQUE稳定ID防重扣；无独立存储配额循环 |

若backend将cursor索引作为immutable result manifest内部信息，search_cursor可合并到首表的有界manifest引用，但必须提供同等授权、位置完整性及重复页证据；不因此引入通用对象服务。schema迁移不插入生产installation、policy、tenant/run或恢复历史记录。原tools_snapshot schemaVersion1可表达新名称/pin，无需改旧任务快照；运行payload2和projection1保持，不将新schema字段强塞旧输出。

安全码沿既有FileFailure/TaskFailure风格；工具错误与REST错误分层：

| 条件 | code/收敛 |
| --- | --- |
| 参数Schema、空old/equal raw old/new、非法include、长度/UTF8/NUL搜索参数 | TOOL_INPUT_INVALID（边界映射）；不admit、不启动、不写 |
| 不允许的逻辑路径 | FS_PATH_DENIED；不返回实际路径 |
| edit缺观察/absent/stale/零或多匹配/旧内容NUL | FS_NOT_OBSERVED / FS_NOT_FOUND / FS_STALE_VERSION / FS_EDIT_NOT_FOUND / FS_AMBIGUOUS_EDIT / FS_NOT_TEXT；原字符串/文件不回显 |
| 绑定/参数/版本或状态完整性失败 | FS_BINDING_UNPROVEN / FS_OPERATION_MISMATCH / PLUGIN_PIN_MISMATCH / SNAPSHOT_CORRUPT；不自动回退或重放 |
| 缺native装配/namespace失败 | SEARCH_NOT_CONFIGURED / SEARCH_ISOLATION_UNAVAILABLE；readiness或准入503；不宿主执行 |
| 投影不可表达/投影超限 | SEARCH_WORKSPACE_UNREPRESENTABLE / SEARCH_WORKSPACE_LIMIT；不启动 |
| native非法regex/glob、运行/解析失败、输出超限 | SEARCH_INVALID_PATTERN / SEARCH_FAILED / SEARCH_RAW_OUTPUT_OVERFLOW；已启动要先收进程；无部分成功 |
| 完整结果/聚合限额、存储预算 | SEARCH_RESULT_LIMIT / STORAGE_BUDGET_EXCEEDED；拒绝不可读游标，已写对象保留可核对清理 |
| cursor非法、过期、不属于当前tenant/task或查询/pin不符 | SEARCH_CURSOR_INVALID；不泄漏引用是否属于其他租户，不重新搜索 |
| 取消/期限/fence | CANCEL_REQUESTED / DEADLINE_EXCEEDED / FENCE_REJECTED；保留SEARCH_ABORTED detail及首因；停止信号退出唯一链，不让模型自行试shell |
| 已启动而停止未证明 | SEARCH_PROCESS_UNPROVEN；保留在途资源与NEEDS_RECONCILIATION，不标CANCELLED/清理成功 |

只读确定失败在新增搜索分支正确toolFailed；未知是否写入的edit、存储错误或丢失收据仍沿uncertain。不要把DurableAgent当前对任意TaskFailure的uncertain映射原封应用到所有可证明已退出的搜索错误，也不要捕获CANCEL/FENCE当普通失败继续循环。

回退：关闭新准入/installation并递增epoch，先停收全部专有native及worker，再回到只运行旧pins的worker。保留V11、format3、结果/收据及unknown；旧制品不能执行format3任务，也不能读不懂后当空工作区重建。不得执行down migration、改旧pin、删除账本、释放UNKNOWN或为回退复活旧评测。

## 7. 实际源码位置与最小改动清单

路径均相对仓库，行号基于本轮准确master，后端提交后需在PR更新定位。

| 现有位置 | 最小动作 |
| --- | --- |
| `adapters/storage/SecureWorkspaceStore.java:53,114,119,193,286,332,375,421`（完整前缀为src/main/java/io/github/lovexiaopeng/jimu/） | EditPort事务、format2/3选择与mtime、同锁快照；复用NOFOLLOW、CAS、binding/operation与fsync，不改旧格式字节 |
| `plugin/api/FilePort.java`、新增EditPort/SearchPort | 保持旧接口/DTO；仅加窄新能力和Page类型，无Spring/JSON/Path/Process类型 |
| `plugins/builtin/workspace/{ReadPlugin,WritePlugin,PresentPlugin}.java`相邻位置 | 新增3个薄插件；不在插件创建进程或查账本 |
| `adapters/runtime/contract/WorkspaceContracts.java:17`、`WorkspaceRuntime.java:69,113,124` | 名单/manifest/Schema/薄装配；搜索使用单独绑定端口，不误走现有file bind；原read/write/present/compose输出保持 |
| `adapters/runtime/search/`（新增）与`workers/search/`（新增） | 受治理native adapter、launcher、bundle/许可清单、startup协议；只这一处获准创建搜索进程 |
| `adapters/ai/springai/DurableAgent.java:220,469` | 新工具描述/Schema/端口入口、结果页恢复、确定失败映射；保留SUCCEEDED直接返回与唯一manager/advisor |
| `adapters/persistence/JdbcLedger.java:663,1842,2140,2303,2645,2656,2673` | 新准入/原生与存储收据、当前fence、原有锁序及quiescent条件；requireComposition/旧policy/旧unknown规则不弱化 |
| `adapters/runtime/worker/{OperationThreads,WorkerLoop,LocalWorkerSupervisor}.java` | 复用run实际退出跟踪；相同supervisor延伸核对native init及launcher身份，不另建轮询线程 |
| `bootstrap/DurableConfiguration.java:99,142`、`adapters/runtime/health/LedgerProbe.java` | 注入search adapter/operations；默认关闭、完整readiness；无业务逻辑移入bootstrap |
| `api/TaskController.java:72`、resources/plugins/builtin、db/migration/V11… | 输入集合/上限扩展、新三组manifest和Schema、前向迁移，提供真实catalog diff；旧快照/冻结材料原样保留 |
| `README.md`、相关新实施文档 | 删除过期当前态断言，写明P3已交付及web_search未配置、P4本批待验收；加入现行不运行/启用/等待GitHub CI政策，历史基线不改 |
| contract/architecture/integration测试及`tools/`定向driver | 下面矩阵的真断言，更新危险调用门禁为精确adapter/launcher位置；不得整包放行 |

## 8. 可执行的 F01-b / 成功产物验证矩阵

以下是实施交付的必跑计划，**本轮未实现/未运行这些产品测试**。后端新增`WorkspaceEditScenario`、`NativeSearchScenario`、`P4WorkspaceWorkerIT`及定向driver（可沿现有WorkspaceScenario/WorkspaceIT/tools/workspace_verify.py组织，命名可按现约定调整）。测试缺Linux POSIX、真实native bundle或独立MySQL必须失败，不能silent skip。fixture只代替provider协议，搜索和文件、工作进程、数据库和下载都必须真实运行。

| 编号 | 布置/触发（driver应可单独选case） | 独立断言与必须保存的证据 |
| --- | --- | --- |
| E01 | read现有→edit唯一字面；old含regex元字符/$/反斜线；new为空；aaa/aa、aaaa/aa | 精确字节/hash、v+1；不执行regex或替换变量；零/多匹配不写，all替换数量按非重叠；失败前后磁盘摘要一致 |
| E02 | 中文/emoji/组合字符、跨行、CRLF/LF混合、BOM、孤立CR、空文件、空old、equal raw、规范化后相等、NUL旧内容/新内容、非法代理项 | 与第2节oracle对照，不以实现自己返回值作预期；新NUL产生后edit拒绝，原文件仍可read；边界code、文件及状态真实字节 |
| E03 | 未read、read缺失、read后另JVM改变；两JVM同expected争写；ABA | FS_NOT_OBSERVED/NOT_FOUND/STALE优先；仅一个成功，真实版本/hash/mtime及两PID退出收据 |
| E04 | bind rename前/后、intent rename前/后、内容提交后/T1前分别kill；恢复同op/改参数/另一read推进观察 | 不新造binding、不采用新expected、不重复写；同操作原收据不覆盖后写；无法证明则unknown；保持R6格式1/2拒绝/兼容证据，不重跑完整旧矩阵 |
| S01 | 真实rg glob深度/brace/leading dash/hidden/ignored/6种VCS/mtime顺序/不同root、101+跨顶层文件 | argv真实记录、确认为固定二进制；首屏采样与页余项无重/漏；每条返回路径在同任务read可打开；相邻mtime顺序按实际rg核对 |
| S02 | grep regex、Unicode、空白pattern、include brace/非法include、no-match、非法regex/glob、CRLF行、同一行多submatches | 与直接隔离rg输出golden对照；拒绝Java仅支持/rg不支持的lookbehind；0/1/2退出语义和lineNumber/line精确，hidden/.gitignore差异成立 |
| S03 | 源state根/投影祖先/最终目录或文件替换为symlink；已open后rename；显式symlink到host/sibling；/proc/self/fd逃逸；prefix冲突 | 真实host/sibling唯一sentinel永不出stdout/返回结果；固定FD仍原inode或明确拒绝；不凭输出过滤断言隔离，保存进程实际argv、namespace/FD身份与负向退出码 |
| S04 | 不同tenant/同tenant不同task的path/cursor/operationId；epoch撤销、旧worker/fence结果迟到 | HTTP不可见404沿旧规则；工具拒绝且实际未读、未启动/未发布；结果页每次重验，不共享source snapshot/缓存 |
| S05 | cursor翻页期间源文件变化、重启control/worker、重复cursor、篡改/过期cursor、少量/多量结果 | 所有页仍同snapshotDigest及原完整结果；不再次启动rg；全覆盖无重漏；query/tool pin绑定；读取原文件仍需新read观察 |
| S06 | 参数、stdout、stderr、页字节、line预览、4/8MiB结果、16MiB工作区边界N-1/N/N+1；空文件/全空树 | 不解析半截NDJSON，不把overflow当no-match；截断标记、seen/returned/nextCursor正确；不超实际字节/保留配额，失败已启动资源被收齐 |
| L01 | cancel在snapshot/export/启动gate/regex运行/保存/T1窗口；deadline；stdout洪泛/管道阻塞 | 首因稳定；TERM→3s→KILL、真实init/launcher/bwrap退出，drainer结束后才quiescent/运行槽释放；没有下一模型请求或任务成功 |
| L02 | 强杀Java worker、launcher、bwrap；control重启；重复stop；启动PID记录前崩溃；PID重用/不可读proc | 同一supervisor回收原native树；init身份真实消失/僵尸，无法证明则保持待核对；没有orphan活进程、未提前释放或TTL删活动目录 |
| V01 | 新任务pin三工具、旧任务旧pin、format2/3、旧worker遇3、坏Schema/重复工具、缺bundle/namespace/异常启动 | 旧任务原canonical/pin不变；禁用后不广告、不执行，旧worker拒绝新格式；无假readiness或回退宿主；生成catalog差异与Schema摘要 |
| B01 | 同一持久task：受控web_fetch+read缺失→compose初稿→glob→grep→read→edit→grep→重新compose→present→授权下载 | 可将工具槽串行打包进≤8模型步骤/20工具。修订后的明确文本进入最终不可变Markdown，下载hash/size等于present及存储快照；跨tenant/task下载404；重复恢复无二次编辑/发布 |
| B02 | compose后edit报告，直接present；改引用/生成时间；取消与present竞争 | 原requireComposition拒绝不匹配版本/hash，旧报告/生成时间/六栏目/证据规则不变；重新compose有效输出才发布；published旧快照下载字节不受后来edit影响 |

B01的重compose是必要原产品约束，不是绕过：`requireComposition`只承认已成功compose的精确file version/hash；直接放宽它来让edit后的报告发布会破坏原来源链。可先edit工作文稿，再用原compose生成最终报告，或如表中修订报告后按原typed draft重compose；必须断言编辑结果实际体现在最终产物中。

后端提供driver后，建议针对性入口（下列类/脚本为待新增，不声称当前可运行）：

```text
./mvnw -B -ntp -Dtest=WorkspaceEditContractTest,NativeSearchContractTest,WorkspaceToolContractTest,PackageBoundariesTest -Dit.test=WorkspaceIT,P4WorkspaceWorkerIT verify
python3 tools/p4_workspace_verify.py --case E03 --evidence <this-run-evidence>
python3 tools/p4_workspace_verify.py --case L02 --evidence <this-run-evidence>
python3 tools/p4_workspace_verify.py --case B01 --evidence <this-run-evidence>
```

调用者保存准确源码SHA/tree、环境及bundle摘要、完整实际命令/退出码、测试数/失败/未执行项、故障窗口、PID/ticks/pidns、文件/结果摘要与独立负向断言；不引用CI成功替代。必要本地verify按受影响范围执行，不为取消CI重跑旧P2/P3全矩阵或真实付费评测。若定向命令未绑定必需检查，调整命令/门禁并如实说明，不用skip标志制造通过。

## 9. 本轮实际小实验与资源证据

运行入口为附件`xiao42-native-probe.py`，实际执行：`wsl -- python3 <workdir>/xiao42-native-probe.py`，最终退出0。Ubuntu24.04.5 / WSL2 kernel `6.18.54.1-microsoft-standard-WSL2`。这不是Jimu产品native adapter实现；Python仅用于此次探针。

经Windows HTTPS下载固定npm包及固定Ubuntu deb（WSL直接npm HTTPS首次失败，原失败保留）；只在`/var/tmp/xiao42-native-*`本次唯一目录解包执行，未apt install、未改全局PATH/代理、未启动服务、无数据库/模型/搜索provider/真实网页调用。

| 物项 | 固定身份/实际结果 |
| --- | --- |
| @vscode/ripgrep-linux-x64@1.18.0 | tar SHA512 `mQ3bVrUpnD2vs7QT0vX90Lt0cnUq467uFtEktIdsJJmW296RoSULRGqWgzG1AKxyBpNDD6l4ZO4qKf6SgyC23Q==`，逐字匹配Harness lock |
| rg二进制 | SHA256 `193906679498de4d939345b937fa24e0e69a03c244bd70c859f5e41232713f21`；rg15.0.0 rev3a612f88b8；ldd显示statically linked |
| bubblewrap | Ubuntu `0.9.0-1ubuntu0.3`；deb SHA512 `16cad315aa5a302ee8573c8ccb7e6adcdf5bd10dd29adeed0f7f061f39f7b52159d4020b2db1b1dde88fa7f7a3f54522d83037266c956a346882e234247c790b`，匹配apt元数据 |
| 实测边界 | 真实UTF8 grep、glob hidden/ignored成功；默认grep隐藏/忽略与git上下文对照；host/sibling/显式symlink/FD逃逸读取退出2；非法regex2、no-match1；打开FD后源目录rename并替换仍只见旧内容 |
| 生命周期 | 真实rg已输出match并阻塞stdout；kill外层bwrap返回-9；info-fd报告的namespace init `1839097/startTicks15464762` 从R变ABSENT；最终父/子及drainer收齐；详见原始JSON |
| 失败历史 | 首次WSL网络reset；无/proc时glob排序失败；初版ignore fixture漏建.git；把startup FD占用stdin/stdout造成134；/proc读取遇退出竞态ESRCH。保留原记录，不冒称所有尝试通过 |
| 清理 | 每次探针finally删除该次精确临时根；成功/失败均有removed_exact_root=true。取证后删除两个下载归档，精确路径/PID及下载物的清理回读见`XIAO-42-cleanup.json`；交付包不含二进制，不遗留常驻运行或已安装软件 |

实验未证明：产品Java/C桥接、非root运行身份、正式rlimit、数据库停止/游标收据、产品故障恢复及B01。上述都由矩阵在后端准确SHA验证；不得把本实验替身叫作产品已可用。已证明的可行边界足以采用该最小方案，不需申请新服务器、生产权限或付费。

## 10. 后端下一动作与结束条件

产品经理验收本架构交接后，安排原后端一个独立实现任务，先核实master及现有分支；先入库契约/ADR、实现format3与edit事务及native launcher握手，尽早开关联XIAO-41的PR；再接分页/网关/停止收据及B01。不要同时派第二后端修改这些文件。

结束条件为：三个工具真实注册/授权执行，原生边界和分页引用可用；E/S/L/V/B必需矩阵在同一完整SHA由开发自测、原审查和QA取得独立证据；原P3/pin/unknown/费用/租约/报告约束保持。由产品经理作业务验收、按既有授权接续合并，核对actual master/tree。架构交付及平台completed均不替代这一步。

资源仅为现有同机Linux/POSIX、专有临时MySQL和受控provider、固定native bundle及构建launcher所需C工具链。若目标测试身份无法建立namespace，交精确命令/errno给产品经理处理现有测试资源，不允许弱化隔离或偷偷改共享主机配置。187保持f6a9716、paid=false、原两项持久服务；原tenant/run、18次派发、112656微元内部计价、两个HELD_UNKNOWN、scope=0/B锁及旧报告/失败/快照全部保留，不对账、不清账、不恢复旧备份、不新增生产派发。
