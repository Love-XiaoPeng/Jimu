# 架构验证收据

日期：2026-10-22；范围：架构、静态契约与已安装框架扩展点。没有产品实现/数据库迁移/业务验收结论。

## 准确对象

- GitHub API实际master和新checkout：`0e630f28e39c157788886694e2b16dc0831bb571`，tree `8850a9d11f610257d87962585221b91a6bc19948`；API回读时间2026-10-22T13:37:04Z。源码工作树为空；37份本轮相关源码/基线的Git blob与工作字节摘要在 `evidence/reviewed-jimu-sources.json`。
- Harness固定SHA `571902028a75c64c1d767f4ae5fd099485f0ddf0`。API完整tree无truncated；21份工具/service/provider/composer/projection/测试/文档/许可原始下载逐一与tree Git blob比对，均一致。`evidence/sources.json`含每份SHA256/长度；包装还重新验证Git blob及SHA256。上游包0.2.1-alpha.3；MIT Copyright 2026 DeepSeek；原许可/第三方声明保留。
- Java为Temurin21.0.12.1+1；Maven Wrapper3.9.9；本机WSL Python3.12.3/jsonschema4.10.3。runtime classpath由当前仓库POM离线解析；`probe-dependencies.json`逐JAR记录坐标和实际SHA256。包含Spring AI2.0.1、Spring7.0.9、networknt3.0.1，实际完整版本以该文件为准；不是依赖收敛或制品构建证明。
- 产品 `src` 下无html/tsx/vue/jsx/package.json，见 `source-layout.json`。源码提供既有TaskController/鉴权HTTP/SSE，最小问答接口可由后端扩展，无现成前端需修改。

## 实际命令与结果

| 命令/动作 | 退出码 | 实际证据与边界 |
| --- | --- | --- |
| `multica repo checkout https://github.com/Love-XiaoPeng/Jimu.git` | 0 | 新独立任务分支，HEAD/tree同实际master；不指定ref、不覆盖旧工作 |
| GitHub API commit/tree + raw固定SHA下载 + `git hash-object --no-filters` | 0 | master baseline、21份Git blob/SHA256核对。不是运行上游程序 |
| `mvnw.cmd -o -B -ntp dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=<本包私有临时文件>` | 0 | `dependency-classpath-log.txt`；仅离线依赖解析，未编译/打包产品 |
| WSL `python3 make-contracts.py` | 0 | 生成9份Schema、两版manifest及描述，共13份indexed契约文件；catalog pin见`contracts/catalog.json` |
| WSL `python3 verify-contracts.py` | 0 | 9份Schema元验证、13文件摘要、14正例/34反例通过；`contract-check.json/schema-cases.json` |
| `javac -encoding UTF-8 -cp <解析classpath> -d <专有classes> ContractProbe.java SuspensionProbe.java <原始SchemaContract.java>` | 0 | `probe-compile-log.txt`；使用未修改的Jimu SchemaContract源文件 |
| `java -cp <同classpath+classes> ContractProbe <本包>` | 0 | 原始networknt路径再次通过9份Schema及14正例/34反例；`jvm-contract-probe.txt` |
| `java -cp <同classpath+classes> SuspensionProbe` | 0 | 同步call与stream都传播相同RuntimeException暂停标记；每次models=1/prior=1/ask=1/later=0。没有错误tool result/下一轮模型调用 |
| `verify.ps1 -RepoRoot <Jimu checkout>` | 0 | 新交付脚本已实际完整运行一次，复现离线依赖/两种validator/框架探针；机器classpath和class不入交付ZIP |
| 两类框架 `javap -c -p` | 0 | 完整原文在`DefaultToolCallingManager.javap.txt`和`ToolCallingAdvisor.javap.txt`；callback仅捕获ToolExecutionException，advisor仅特殊处理ToolCallLimitExceededException |

Schema正反例验证字段shape/限额与pending/answer区别，不验证“某标签是不是原选项”等依赖原问题的规则。额外检查明确展示4096个emoji可通过字符Schema但超过4096 UTF-8字节，故产品必须做runtime字节校验。严格JSON重复键/原问题匹配/鉴权/状态原子性均在后端必要测试清单中，未用本探针冒称已验证。

## 失败、日志及限制

`git ls-remote origin refs/heads/master`直连GitHub失败：`fatal: unable to access 'https://github.com/Love-XiaoPeng/Jimu.git/': Failed to connect to github.com:443 after 21059 ms: Could not connect to server`。该native退出码当时未独立捕获，所在PowerShell单元被后续rg的成功覆盖成0，不能记成git成功或猜其退出码。随后用可用的官方GitHub API回读准确master/tree并取固定raw/blob证据；没有改系统代理/共享网络设置。

源码巡检中读取过不存在的旧路径（`V1__durable_execution.sql`及插件mcp旧目录），PowerShell报路径不存在；已用rg定位真实 `V1__p2_ledger.sql` 和 `src/main/resources/mcp/`。`gh`不在当前Windows PATH；本回合只交架构附件，无产品代码变更或PR创建动作，后端仍按其实施环境创建唯一草稿PR。

框架stream探针会从MessageAggregator产生两条ERROR和Suspended栈，完整原文保留在`framework-probe.txt`，随后断言PASS、进程0退出。该标记没有变成模型工具回复，也没有调用下一模型。后端须在真实产品账本证明正常暂停不写FAILED/UNKNOWN；探针不证明生产日志整洁、线程回收或数据库quiescent事实。探针JVM均前台退出，未留下本回合服务或后台工作。

未运行产品Maven verify、control/worker、MySQL、用户回答HTTP、迁移/旧binary拒绝、真实故障窗口、存储清理、独审/QA或付费模型；未启用/运行/等待GitHub CI。`implementation-contract.md` §8列出的这些本批必要真实证据由后端/独审/QA接续，不能用本架构结论替代。未改共享服务、原187持久服务、daemon、真实Key、部署、付费或外部通知。
