# XIAO-120 独立代码交付证据

本批只交全仓业务值及配置归属代码治理，等待独审、必要 QA 和 A 串行集成；不表示 XIAO-58、PR22 或 master 已验收。

## 准确版本与集成范围

- 基底：`26f1c23be0c155c9b9c69126e66f9dbf394bc946`，tree `b42da2043ae73bb946db9bf25b2b3f5d36f66145`。
- 单一源码提交：`cfa2f51824cd84c1657bf6dcdc0f5cee83d5807a`，tree `d79dfacfee47c87bb5ba2c125e07c3d965f21696`。
- 分支：`agent/b/XIAO-120-values`。后续证据提交只增加本目录，不修改源码。
- `source-blobs.json`：每个受影响生产/测试文件的基底 blob、交付 blob，以及整个 `src/main`、`src/test` 子树。
- 188 个既有生产文件作同值引用替换，新增 61 个模块政策/常量类；新增边界用例位于 1 个既有测试文件。完整分类关闭及未完成交接见 `closure.md`。

A 可在完成其 115/G01–G03 工作后，串行接入上述单一源码提交和证据提交；本分支没有叠入 115，也没有改 PR22/master。Git 身份沿用 `origin/agent/b/XIAO-115-bootstrap` 的既有提交身份，未修改全局或共享仓库配置。

## 必要验证

Windows：Temurin Java `21.0.12.1+1-LTS`，Maven Wrapper `3.9.9`。

```powershell
.\mvnw.cmd -B -ntp spotless:apply test jar:jar spring-boot:repackage spotbugs:check '-Dtest=Session*Test,PriorSessionContractsTest,NativeSearchContractTest,Task*Test,Todo*Test,Question*Test,Image*Test,Mcp*Test,PackageBoundariesTest,Report*Test,WorkingDirectoryContractTest,WorkspaceToolContractTest,WebFetchPluginContractTest'
```

最终日志 `xiao120-final-source-windows.log`：退出 0，1,615 tests、0 failure/error/skipped；包含编译、格式、Checkstyle、依赖规则、模块边界和定向业务测试，生成可执行 Spring Boot JAR；SpotBugs 0 bug/0 error。逐 suite XML、校验和及 JAR 的字节数/SHA256 见附件中的 `reports/windows` 与 `build-evidence.json`。JAR 另作为同一条交付评论的附件提供。

WSL 私有源码副本：Java 21、Maven 3.9.9，离线复用依赖缓存，未启动服务。

```bash
env JAVA_HOME=/opt/jimu-test-toolchains/neo4j-3388e05ee53c-java21 \
  /root/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn \
  -B -ntp -o -Dmaven.repo.local=/mnt/c/Users/Administrator/.m2/repository \
  spotless:apply test -Dtest=WorkspaceEditContractTest,NativeSearchContractTest
```

最终 `xiao120-final-posix.log`：退出 0，10 tests（WorkspaceEdit 4、NativeSearchContract 6）、0 failure/error/skipped，源码格式无改动。独立 XML 位于附件 `reports/posix`。Windows 构建前后 `src` 对固定源码提交无差异。

新增测试通过带大量引号的路径区分两种 4MiB 预算：2,100 个收集项仍低于收集预算，但 pages 再转义后的总 document 超限，保留 `SEARCH_RESULT_LIMIT`；2,000 项的相同路径模式可接受。原有搜索 Unicode、排名、比较预算、截止和模块规则测试直接复用。

## 源码等价与互斥边界

```text
node docs/verification/XIAO-120/verify-source-equivalence.cjs
node docs/verification/XIAO-120/verify-frozen-boundaries.cjs
```

`source-equivalence.json`：188 个修改过的既有生产文件全部通过。只去除 import/注释/格式，展开具名字面常量，并折叠脚本中明确列出的编译期转换/算式；完整 SQL 和字符串仍参与比较。`TaskExecutionLimitsV1` 仅允许三个明确新增声明。该检查不能替代语义归属的独立代码审查。

`frozen-boundaries.json`：385 项全部保持基底文本 SHA256（仅统一 CRLF/LF），包含完整 bootstrap、事务/query 基础设施、`SessionQueryDeadline`、`SessionQueryContext`、全部生产资源，以及 Ledger 的 17 个完整构造/事务/query wrapper/同步块。提交前的边界复核撤回了这些冻结正文内的同值错误码替换，冻结正文不需要 A 接收额外差异。所有变更通过 `git diff --check`。

## 各轮真实结果与限制

中间轮次仅代表各次开发工作树，未单独固定可交付 SHA；它们不能作为最终提交的验证替代。完整原日志随证据附件保留。

| 日志 | 实际结果 / 首错及处理 |
| --- | --- |
| `xiao120-round1.log` | 1,267 tests 通过。 |
| `xiao120-round2.log` | 1,591 tests 中有 4 errors；首错为 WorkspaceEditContractTest 调用 Files.setPosixFilePermissions 时，Windows provider 抛出 UnsupportedOperationException。没有改写生产行为或将此轮改记成功。 |
| `xiao120-round3.log` | Windows 适配的定向选择共 1,614 tests 通过。POSIX 4 项另转 WSL 验证。 |
| `xiao120-final-windows.log`、`xiao120-delivery-windows.log` | 加入 native 总 document 字节边界后，均为 1,615 tests，JAR 与 SpotBugs 通过。 |
| `xiao120-posix-first.log` | 首错为 Maven Central 拉取依赖 POM 超时，尚未执行测试；改用现有本机缓存离线执行，没有改依赖版本。 |
| `xiao120-exact-source-windows.log`、`xiao120-prefreeze-posix.log` | 冻结边界恢复前的 1,615 / 10 tests 通过；此后最终源码变化，因此另外运行下列最终轮次。 |
| `xiao120-final-source-windows.log`、`xiao120-final-posix.log` | 固定最终源码的 1,615 / 10 tests 通过，以上最终制品及 XML 以此为准。 |

未执行旧数据库/worker/真实模型完整矩阵、完整 `verify` 生命周期、真实付费调用、部署、共享服务或 CI；没有恢复 CI，也没有将历史取消/失败记录改为成功。当前测试使用既有模拟/本地夹具，没有真实 provider key。现有 Mapper 无主键等警告保留在日志，不等同于此次失败。

独审、必要 QA、业务验收、115 接入和最终 master 集成尚未完成，负责人及最小下一步见关闭表。交付状态为 `in_review`。
