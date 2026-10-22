# XIAO-48 架构验证收据

日期2026-10-22，UTC；执行人为架构师。本轮只生成架构/契约/探针附件，Jimu checkout保持clean，没有修改业务源码、提交、推送、PR、迁移或部署。后端将架构与实现统一入库并创建草稿PR；本轮无代码PR。

## 准确基线

- 实际GitHub master：`a04bc49a047827b292c7929312ea8d1ae9e31afc`；tree `74f134b2dbaa0c984256129fab2c41a9fb70a15d`，PR12已merged。
- Multica新checkout缓存：`fdb38138f60b92a556b276e6c3ef96d287a8d0f6`；tree `10b993f03fb45a5e334739c5b6227aa60ccdd80d`；分支`agent/agent/4347c867b2e4`。未强行重置或覆盖。
- GitHub API compare：actual master领先3提交、8变更文件；8文件逐个按raw固定提交下载并与API Git blob核对。执行/授权/快照代码不在变更清单内，Java唯一变化为NativeRunner制品收据校验。`master-baseline.json`/`master-compare.json`保留事实；缓存不是actual master。
- Harness固定SHA：`571902028a75c64c1d767f4ae5fd099485f0ddf0`，G02/T02/T03/T04与工具清单1.1一致；原tools.ts、render.ts、连接、传输、测试与许可已审阅，未执行Harness。
- MCP规范来源固定SHA：`80041ab0a139a8f4fb3adbc99f7ae9113ea59671`，选用其中2025-06-18的schema、transport、lifecycle、cancel、resources及完整LICENSE。共18个固定source blob校验成功；不是规范最新分支的动态引用。

## 实际命令与结果

命令相对本交接包的上一级目录执行，脚本不需要真实服务凭据。以下为独立架构探针，不是Jimu构建或业务验证。

| 命令 | 退出码/结果 | 证据与含义 |
| --- | --- | --- |
| `multica repo checkout https://github.com/Love-XiaoPeng/Jimu.git` | 0 | 上述缓存基线，未覆盖本地工作 |
| `./architecture/fetch-evidence.ps1` | 0 | 18 source blobs及8 master changed blobs一致；只读取公开GitHub |
| `node ./architecture/make-contracts.cjs` | 0 | 9份Schema、3份manifest和catalog；生成器只写附件目录 |
| `java ./architecture/ProtocolProbe.java ./architecture/evidence/protocol-probe.txt` | 0 | 17项断言；真实JDK本地HTTP线路，不是产品网关 |
| `wsl.exe -e python3 .../architecture/verify-contracts.py` | 0 | 9份Schema元验证、9个静态契约文件摘要、14个正/负例检查；路径仅为本机运行参数，不是交付链接 |
| `./architecture/verify-jvm.ps1` | 0 | 原样Jimu SchemaContract编译，9 Schema初始化、7正例、5负例符合预期 |
| `git status --short` / `git rev-parse HEAD HEAD^{tree}` | 0 | checkout干净，仍是所列缓存SHA/tree |

协议探针环境：Windows11，TemurinJava21.0.12.1，单个前台Java源码启动；两个独有127.0.0.1临时listener，一个MCP fixture、一个重定向目标计数器。17断言包括initialize/resources capability、initialized=202、模板、资源page1/page2、read、-32002、POST SSE、302不follow、其他主机/端口/路径/公共地址在探针配置层被拒、slow请求到达/取消/future不返回成功、cancel通知、default proxy计数0/redirect目标计数0、handler线程结束和literal loopback绑定。原wire保留在protocol-probe.txt；不记录Authorization实值。测试用disposable token是探针代码常量，与任何真实秘密无关。

这条探针的JSON请求/响应与SSE是预设字面量，未实现通用RPC/SSE parser；它只验证拟用JDK传输行为和协议线路可达。完整parser、预算/流式取消、授权与恢复必须由后端/QA在产品路径验证。不能把17断言当成产品安全或MCP全兼容结论。

JVM Schema探针直接编译checkout原样 `adapters/runtime/contract/SchemaContract.java`，使用cache中的networknt3.0.1、Jackson2.21.7/3.1.7、annotations2.21、slf4j2.0.18、itu1.14.0、SnakeYAML2.6；具体JAR SHA256及被编译源SHA256见jvm-probe-dependencies.json。SLF4J无provider警告是隔离probe未装日志实现，测试未依赖日志；没有修改产品logging。此小classpath不是Maven完整resolved tree、依赖收敛或SBOM验收。

Python环境WSL Python3.12.3/jsonschema4.10.3。schema-index SHA256（也是catalog.schemaDigest）：`4ff8e6e0c9ef697c02517426a948479f5976806c9f4249c954a69fa579b826db`。该索引仅列3×manifest/input/output的9个静态工具文件，三个宿主持久Schema另由版本1及package manifest绑定。

## 失败与清理

`attempt-history.txt`逐项保留首次unsupported CLI flag、两条Git直连失败、缺gh命令、取消future判断假设失败、Python URI format checker未生效的发现和修正。首轮协议原文为protocol-probe-attempt-1.txt。没有删除或重写项目旧CI、失败、锁、费用、UNKNOWN或快照。

协议探针finally关闭HttpClient、恢复**本探针JVM内**的默认ProxySelector、stop两个listener、shutdown并等待独有handler线程退出，前台Java退出0；没有更改OS/共享代理，没有遗留本轮服务。没有启动multica/daemon的后台副本或停止daemon。下载与编译文件均在本轮附件目录；zip排除probe-classes编译缓存。没有访问项目187持久服务、生产配置、模型/搜索Key、收费API或真实MCP服务器。原仓库无改动。

## 未覆盖与接续

未运行Jimu Maven verify、真实control/worker/MySQL纵切、生产网络namespace/防火墙、真实凭据撤销、full SSE parser、task/cursor跨租户撤权恢复、资源/存储预算、V15迁移和cleanup。这些是后端首个切片及原独审/QA的交付项，详见implementation-contract第6节。未运行/启用/重跑/等待GitHub CI，也没有恢复旧完整矩阵或付费评测。

本次交付没有架构阻塞；Git缓存与远端差异已明确并由API全差异取证，后端仍须在实施前核自己checkout的实际master和既有改动。若其Git写入链路也不可用，按真实网络/权限证据上报产品经理，不拿缓存分支宣称基线最新。
