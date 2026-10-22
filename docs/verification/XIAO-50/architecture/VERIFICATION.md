# 本回合实际核验

日期：2026-10-22；范围：架构、固定源码与可入库契约。不是产品实现或独审/QA/业务验收。

| 命令/动作 | 实际结果 |
| --- | --- |
| multica issue get / roots summary / trigger thread | 退出0；本项in_progress，仅产品经理派架构的线程，没有其他约束变更 |
| multica repo checkout Love-XiaoPeng/Jimu | 退出0，但实际checkout为旧PR13：0e630f28e39c157788886694e2b16dc0831bb571/tree8850a9d11f610257d87962585221b91a6bc19948，工作树clean |
| git ls-remote origin refs/heads/master | 退出1，GitHub443直连21秒失败；未改全局/共享代理或Git配置 |
| fetch-evidence.ps1 最终版 | 退出0；API actual master777a2e2ae47c3d5be7ac545648c0d90483124ff2/tree6bfa20648fdf7124ff8a32f4c6c90ce0cfdfae95；672个Git blob逐项一致 |
| fetch-upstream.ps1 | 退出0；固定571902028a75c64c1d767f4ae5fd099485f0ddf0的24个源码/测试/许可文件，与未截断tree的Git blob逐项一致 |
| node make-contracts.cjs | 退出0；生成4个2020-12 Schema、manifest/catalog/description、11个示例；JSON格式plugin.yaml沿用现有问答契约约定 |
| wsl python3 verify-contracts.py | 退出0；4个Schema合法，39个Schema案例（12正/27负），固定资源摘要与catalog绑定校验通过，生成46个语义fixture |
| node verify-upstream.cjs | 退出0；从固定源码提取toTodoList函数，仅移除类型注解，39个共同语义案例一致；7个新增云端界限/严格JSON案例明确不当作上游保证 |
| mvnw.cmd -o -B -ntp dependency:build-classpath -DincludeScope=runtime | 退出0，离线解析现有runtime classpath；无依赖升级，不是产品构建或verify |
| verify-jvm.ps1（javac＋java） | 均退出0；编译并运行**未修改的实际master SchemaContract.java**，4 Schema、12正27负案例一致；候选JVM严格解析/Unicode/字节/并行/计数规则46案例通过 |

原输出在 evidence/python-contract-probe.txt、upstream-body-probe.txt、probe-compile-log.txt、jvm-contract-probe.txt、dependency-classpath-log.txt。JDK为Temurin21.0.12.1；Python3.12.3/jsonschema4.10.3；JVM依赖制品SHA256见probe-dependencies.json。Schema案例与语义fixture均入包，后端可复用；候选normalizer是合同探针，不是已经装配的产品代码。

原始失败保留在attempt-history.txt：首次Git hash-object遇Windows长路径，随后用标准 `blob <length> NUL <bytes>` 的SHA1逐项验证；archive的mvnw.cmd与Git blob换行不同，固定raw文件纠正后才成功。没有将失败算作通过。没有运行上游整个包或vitest。

未执行：产品Java构建/必要定向verify，MySQL DDL/事务与双worker纵切，授权/撤权、提交响应丢失/崩溃、SSE竞争、实际清理释放、V16旧二进制升级和旧binary拒绝新版本，独立审查/QA/业务验收。这些是原后端及原审查、QA在准确SHA/JAR上的后续完成条件，见合同§6；契约探针不能代替它们。

没有部署、生产Key/付费模型/搜索、187持久服务、daemon/共享服务/代理/FRP变更，没有后台遗留进程或CI操作。唯一Git checkout保持clean；只读archive、编译缓存与离线classpath为本机工作副本，不是交付路径。交付以附件zip及其manifest为准，包中排除这些副本和绝对classpath。
