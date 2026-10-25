# XIAO-70：下载响应与任务事件表示

准确基线 commit `8574f7f0ffdeae16754998686008aa58262b70e1`，tree
`ccbf5193a4c4da6758068c4cdce838d6d052e2dd`；独立分支
`agent/a/XIAO-70-task-response`。本片以一个后继提交交付，不重打包66。

## 实际边界

Controller 仅把下载响应组装移至 `api/task/vo/ArtifactDownloadResponse`，
把原 Envelope 及九字段映射移至 `TaskEventResponse.from(Tasks.Event)`。
无新增业务 Service、框架装配、数据库、线程或存储读取。
原嵌套 Envelope 无外部生产/测试源引用，不保留重复兼容壳。

下载保留原 `artifacts.download(authentication.authenticate(authorization), taskId(id))`
调用表达式和一次调用；适配器取得 bytes 后，依次 parseMediaType、bytes.length、
原 Content-Disposition 表达式、nosniff、ETag、body。没有复制/重读 bytes，
没有新校验/归一化。只有精确 `text/markdown; charset=utf-8` 产生 `.md`，
其他值走原 `.txt`。原200、header和body引用不变。

事件保持 eventVersion=1，按原 eventId/taskId/seq/type/occurredAt/status/code/artifactIds
顺序从同一个事件取值；声明字段、int/long类型不变，无复制/去重/null拒绝。
`Tasks.Event` 本身的构造器原已 List.copyOf，不能产生 null 列表或含 null 元素；
本片不改该契约，测试覆盖其可构造的空/重复有序列表及其余可空字段。
SSE id/name 仍取原 eventId/type；原实现遇到 null 路由值会抛 NPE，此行为亦有对照。

submit、严格body/DTO/游标、taskId/认证/核权次序、get/cancel/capabilities、
构造器/注解/公开方法、feed参数、30000ms、IOException包装文本、complete及
onCompletion/onTimeout/onError保留。原四个解析文件blob核对无变化，66原解析
证据仅按该边界沿用；66的67项不算本片新测试。原fixture/历史材料未改。

## 差异证据

`TaskResponseDifferentialTest` 校验新增fixture的Git blob为
`12955edc6aec02df921d94c1a2f38f10722d2c5e`，在临时目录编译准确基线Controller。
使用 `-g` 保持局部变量调试信息，使JDK helpful NPE消息可直接对照。
新增17项涵盖六种有效mediaType、空/二进制/UTF-8 body、原body引用、
全部下载header、Content-Length不信任artifact.size、带空格/引号/null文件名、
空/null/已引号/weak ETag、认证/非规范ID/owner拒绝、无效media与null异常先后。

事件检查record字段顺序与类型、逐字段原值/列表引用、旧新JSON字节和
SseEmitter构建的id/name/data片段；在真实emitter装内存handler，触发complete、
completion/timeout/error关闭与IOException原cause包装。未启动网络服务器；
直接Controller/模拟feed验证不等于真实HTTP/SSE回放或MySQL验收。

## 本地命令和结果

Windows，JDK21.0.12.1，Wrapper Maven3.9.9，离线依赖缓存。

| 命令 | 结果 |
| --- | --- |
| `.\mvnw.cmd -B -ntp -o spotless:apply` | exit0；最终格式通过；仅本片Java发生格式变化 |
| `.\mvnw.cmd -B -ntp -o test '-Dtest=TaskResponseDifferentialTest,TaskAdmissionDifferentialTest,PackageBoundariesTest'` | exit0；17+1+1=19项，0失败/错误/跳过；519生产源、238测试源真实编译，Enforcer/Spotless/Checkstyle通过 |
| `.\mvnw.cmd -B -ntp -o verify '-Dtest=RefactoringNegativeGatesTest' '-Dit.test=RuntimeIT#missingRequiredWorkerConfigurationFailsStartup'` | exit0；架构负例3项、缺配置启动IT1项，0失败/错误/跳过；打包与SpotBugs通过（0 bug/error）；仅定向验证，不代表完整矩阵 |

首轮test exit1：17项中2失败/1错误。两个NPE文本差异来自临时基线未开局部变量
调试信息，一个null SSE id/name用例原实现就抛NPE；修正fixture编译参数并将null VO
和原SSE拒绝分开验证后通过，产品实现未为测试改变。原日志/XML随交付保留。
首个环境失败为完整66 bundle下载超时；CLI checkout未返回，经核daemon PID后
仅停止本次CLI PID。随后从自己66原checkout只读clone到本工作目录，准确HEAD/tree
一致，未写原工作树/其他开发者工作树；Python入口缺失后改用Node写文件。

未跑无变化POSIX/旧全矩阵、数据库、真实HTTP/SSE/撤权竞争、真实Key或付费验证；
未运行/启用/等待CI，未部署/合master/改PR22。开发自测不替代独审/QA/业务验收。

## 接收与回退

先接受66的通过版本，再由PR22唯一集成人仅cherry-pick本片新增SHA，不合整个分支。
准确SHA/parent/tree、文件diff、bundle SHA256和接收命令见外部manifest与最终评论。
离线delta bundle要求已具有准确66 commit及其可达对象；应先用66交付物恢复，
然后fetch本bundle并核对候选tree。不能把bundle前置要求当作完整独立历史。
相邻接线冲突只局部处理，不能整文件覆盖。无DDL/配置/数据变化，回退revert本片接收提交。
现有唯一三终态/两小时回调已绑定本run，未另建规则或额外通知PM。
