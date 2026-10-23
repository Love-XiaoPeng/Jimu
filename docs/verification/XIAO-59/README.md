# XIAO-59 架构方案核查证据

2026-10-23。源码基线 `620d5e0e5bfe7b5da530f10997936466159ba34f`，tree `ef29f391b8355a7d693a48cefdcc379e35c32929`。本目录支持 [重构提案](../../refactoring/XIAO-58/README.md)，不是重构制品验收。

## 实际核查结果

| 核查 | 实际命令/动作 | 退出码及结论 |
| --- | --- | --- |
| 工具链 | `.\mvnw.cmd -B -ntp --version` | 0；Temurin21.0.12.1、Wrapper3.9.9；Node24.21用于只读盘点 |
| 远端基线 | `git -c http.proxy=http://127.0.0.1:7890 fetch origin master`，随后 `ls-remote origin refs/heads/master` | 均0；master与核定SHA一致；代理仅限命令，不改共享配置 |
| 基线盘点 | `node docs/verification/XIAO-59/capture-inventory.cjs` | 0；1287文件、163生产Java/27685行、11持久化Java、22迁移；DDL中35个CREATE TABLE |
| 文件/值映射 | `node docs/verification/XIAO-59/plan-targets.cjs` | 0；163文件全部有处置；3692条词法候选中提取350项大写语义字符串候选 |
| 原依赖 | `.\mvnw.cmd -B -ntp help:effective-pom '-Doutput=target/xiao59-baseline-effective-pom.xml'`；`dependency:tree '-DoutputFile=docs/verification/XIAO-59/evidence/baseline-dependency-tree.txt'` | 均0；没有改产品POM |
| 候选依赖 | `.\mvnw.cmd -B -ntp -f target/xiao59-probe/pom.xml dependency:tree '-DoutputFile=candidate-dependency-tree.txt'` | 0；唯一候选差异是spring-jdbc替为MP Boot4 starter3.5.17 |
| 候选约束 | `.\mvnw.cmd -B -ntp -f target/xiao59-probe/pom.xml enforcer:enforce@toolchain-and-dependencies help:effective-pom '-Doutput=candidate-effective-pom.xml' dependency:build-classpath '-Dmdep.outputFile=candidate-classpath.txt'` | 0；Java/Maven/convergence/banned四项均通过；不是产品compile/verify |
| 装配探针 | `javac -encoding UTF-8 -cp <candidate-classpath> -d target/xiao59-probe/classes docs/verification/XIAO-59/CompatibilityProbe.java`；`java -cp <classes+candidate-classpath> audit.xiao59.CompatibilityProbe` | 均0；两个非web上下文，durable=false无DS，true注入insert/selectList且无selectById；原始输出含预期无@TableId警告 |
| 官方依据 | `node docs/verification/XIAO-59/capture-official.cjs`，任务进程环境 `JIMU_AUDIT_PROXY=http://127.0.0.1:7890` | 0；14个官方页面/版本POM/metadata，全部抓取成功，全部检索词有摘录 |
| 文档/证据保护 | `node docs/verification/XIAO-59/verify-plan.cjs`；`git diff --check`及暂存区同项 | 均0；1287基线blob摘要、163映射、22迁移/35表覆盖、相对链接、证据摘要和变更范围断言通过，不执行产品/数据库测试 |

探针仅检查依赖与条件装配形状。DataSource指向不可连接的本机端口1，Flyway/SQL初始化关闭，未调用getConnection或执行SQL；使用的TaskEntity显式把taskId映射到现有id列且没有伪造单列@TableId。没有证明MySQL语义、完整产品Bean装配、事务/授权/计量/恢复正确。探针类位于docs，不打入产品。

## 文件与可复核范围

| 文件 | 用途及限制 |
| --- | --- |
| `evidence/baseline-files.json` | 精确Git blob字节的路径/大小/SHA256；包括中文长路径，使用NUL分隔与cat-file批读取，不经Windows文件重编码 |
| `evidence/java-inventory.json`、`import-edges.json`、`largest-files.md` | 全生产Java清单、显式import和规模；不是完整AST/调用图/ArchUnit通过证据 |
| `evidence/ddl-index.json`、`persistence-methods.md` | 所有迁移DDL锚点、持久化声明锚点；需结合方法体和方案逐用例阅读 |
| `evidence/file-mapping.json` | 每个旧文件唯一处置记录；一对多目录/花括号目标由总方案Service表及持久化35表矩阵展开 |
| `evidence/literal-candidates.json`、`symbol-values.json` | 包含技术值、SQL及hook的候选底稿；不是全部缺陷，也不要求机械替换 |
| `evidence/baseline-dependency-tree.txt`、`candidate-dependency-tree.txt` | 原/候选实际完整依赖树；候选仍是隔离POM，不代表产品已迁移 |
| `evidence/candidate-pom.xml`、`*-effective-pom.xml.gz`、`resolution-summary.json` | 精确候选POM、原始effective POM压缩字节和摘要；effective POM含当时的构建路径，仅作证据，不作为可移植配置 |
| `evidence/compatibility-probe.txt.gz` | Java21探针原始输出（含原banner空格/BOM/换行，压缩保字节）；没有业务测试数量或真实SQL断言 |
| `evidence/official-sources.json` | 每个URL、UTC抓取时间、原响应SHA256/大小、有限原文摘录；网页会变，以固定版本POM/解析结果优先 |

官方主要依据：[MP安装](https://baomidou.com/en/getting-started/install/)、[Wrapper](https://baomidou.com/en/guides/wrapper/)、[注解](https://baomidou.com/en/reference/annotation/)、[批处理](https://baomidou.com/en/guides/batch-operation/)、[MyBatis-Spring事务](https://mybatis.org/spring/transactions.html)、[Spring事务](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)、[MVC异步](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)、[Spring AI工具](https://docs.spring.io/spring-ai/reference/api/tools.html)。版本特定Central URL见JSON。

复核候选时先将 `evidence/candidate-pom.xml` 复制到 `target/xiao59-probe/pom.xml`，再从仓库根目录执行上表候选命令；不要覆盖产品pom。Windows将classpath文件作为一个字符串参数传给javac/java，不能将分号按shell命令拆开。复核只读盘点不需要MySQL。

## 保留的失败和未验边界

本轮初始直接GitHub连接失败，使用既有代理的单命令访问后成功。早期盘点遇Git中文路径引号/Windows长路径失败，改NUL+cat-file后成功；一次未整体引用的PowerShell Maven `-Doutput=...xml` 被拆成生命周期参数而exit1，整体引用后exit0。这些是本轮核查工具/命令问题，不是产品缺陷，未伪记首试通过。

本轮没有产品源码/POM/DDL变更，没有产品JAR、真实MySQL/HTTP业务回归、独审或QA。没有运行、启用、重跑或等待GitHub CI，也未重跑旧完整P2/P3矩阵。XIAO-55的旧verify exit1、真实锁现场持锁者未知、旧容量/授权排列与DNS/TLS覆盖限制原样继承；新制品必须按 [V01–V12](../../refactoring/XIAO-58/delivery-and-validation.md) 取得自己的证据。
