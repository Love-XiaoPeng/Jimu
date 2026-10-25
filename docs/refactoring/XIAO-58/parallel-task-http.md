# XIAO-66：任务 HTTP 严格解析切片

准确基线为 `efba6c732f23634755402438f7d275502d8526a3`，tree 为
`a360b902d47c1b60ca773c10fb119693e1edd74d`。独立分支为
`agent/a/XIAO-66-task-http`，不包含 XIAO-64 或网关支线提交。
本机只读获取该 Git 对象后核对 tree；没有修改集成人的工作树。

基线 `TaskController.java` blob 为 `4fe66af39d0eb1bff35e698d1db0b024bb47cb10`，
与 PM 核查的 `eac8c70` 版本相同；没有相邻 Controller 行为差异。
本次只改 Controller 的提交解析/映射及 events 游标接线，新增四个
`api/task/dto` 文件、一项专属差异测试和一个准确旧源码 fixture。
其余 Controller 方法、构造器、HTTP 注解、Envelope 和 SSE 生命周期保留。
DTO 源引用检索没有其他生产调用者；旧嵌套请求 DTO 移至该目录，不保留重复解码器。

## 行为边界与对照

| 边界 | 实现与证据 |
| --- | --- |
| 认证/body | Controller 先 authenticate，再取得 InputStream；解码器仍只 readNBytes(6291457)，超过 6291456 返回 413/REQUEST_TOO_LARGE；流 IO 异常原样传播 |
| JSON | 原 Jackson2 四项设置原值迁移；保持默认未知字段拒绝；只按 JsonMappingException 的实际 path 中 images 字段分类 400，非文本匹配 |
| DTO/命令 | JSON null 传 null TaskSubmission；缺省/null budget 保持；整数/长整数类型、字段名及嵌套不变；列表顺序、重复和 null 保持，不复制/去重/增加准入校验 |
| 服务/响应 | 同一认证 identity、原 key、一次 TaskService.submit；202、Location、原 View 保持；实际准入仍在现有 Service |
| 游标 | authenticate → taskId 原校验 → tasks.get → parse；null 为 0；Pattern.quote(id) 整串匹配原正整数格式，再 Long.parseLong；溢出/非法仍 400/INVALID_CURSOR，未授权/404 优先 |
| SSE | feed.subscribe 原 authorization/id/cursor 参数、30000ms、回调/关闭代码不动；此片不涉及持久化、线程或 EventFeed 实现 |

`TaskHttpParsingDifferentialTest` 在临时目录编译准确旧 Controller，
以 Git blob SHA1 核验新增 `TaskController-efba6c7.java.txt` 原字节。
原 `TaskController-620d5e0.java.txt` fixture 与 `TaskAdmissionDifferentialTest`
保持不变，原测试亦实际执行。

新增 56 项测试比较 38 种 JSON、16 种游标、认证/IO 次序和 body 大小边界。
比较的是原始 TaskSubmission（含列表/null/数值）、认证/读取/服务调用顺序、
错误 status/code、响应 status/headers/body 及订阅参数。
读取测试覆盖 6291455/6291456/6291457/6291460 字节，并检查尾部未消费字节。
游标测试包含 Long.MAX_VALUE、溢出、前导零、符号、Unicode 数字、换行、
错误任务前缀，结合拒绝认证、非规范 UUID/大写和 owner 查询 404。
这属于直接 Controller 适配层证据，不是 MySQL/真实 HTTP/SSE 回放业务验收。

## 本地验证

环境：Windows、JDK 21.0.12.1、Wrapper Maven 3.9.9；依赖缓存离线可用。

| 命令 | 实际结果 |
| --- | --- |
| `.\mvnw.cmd -B -ntp -o spotless:apply` | exit 0；格式通过 |
| `.\mvnw.cmd -B -ntp -o test '-Dtest=TaskHttpParsingDifferentialTest,TaskAdmissionDifferentialTest,TaskAdmissionServiceTest,PackageBoundariesTest'` | exit 0；63 项，0 failure/error/skip；含新增 56、原准入差异 1、准入 Service 5、架构 1；实际编译 517 个生产源和 237 个测试源；Enforcer/Spotless/Checkstyle 通过 |
| `.\mvnw.cmd -B -ntp -o verify '-Dtest=RefactoringNegativeGatesTest' '-Dit.test=RuntimeIT#missingRequiredWorkerConfigurationFailsStartup'` | exit 0；架构负例 3 项、缺配置启动 IT 1 项，0 failure/error/skip；打包及 SpotBugs 通过（0 bug/error）；只选择无需 DB 的启动验证，不代表完整 verify 矩阵 |

未运行 DB、POSIX/旧完整矩阵、真实 Key/付费、部署或 GitHub CI。
未验收下载/取消/查询、SSE 回放/断线/撤权竞争；这些实现未改动。

## 接收与回退

由 PR22 唯一集成人接收此独立提交，先核交付 SHA/tree 与基线，审阅
Controller 的两处接线和新增解析器，再在自己的集成分支 cherry-pick。
此片无 resources、DDL、配置、接口服务或数据库数据变化；遇到相邻
Controller 改动须保留集成人行为，不能整文件覆盖。
回退使用 revert 本片提交，恢复内嵌 DTO/解析即可，无数据迁移或降库。
准确交付 SHA/tree、差异清单及接收命令由最终评论提供，避免在文件内自引用提交。

本次 JAR SHA256 为 `0f3daa9afb929cb4f209fd130a121c9b9e1b4cf2e2f7438c9b959ebc9472638d`；
它只经过上述适配层/静态/启动验证，不是稳定集成制品验收。

首个实际环境阻塞：`git fetch origin efba6c732f23634755402438f7d275502d8526a3`
exit 1，GitHub:443 连接失败；通过本机准确对象完成了源码和测试工作。
当前 checkout 的 `git var GIT_AUTHOR_IDENT` 亦失败，缺 user.name/user.email；
不擅自覆盖用户身份。远端 PR 与提交署名状态如实见最终交付评论。
