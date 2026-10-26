# XIAO-74：会话历史 HTTP 请求与响应适配

本片准确基线为 `d6f147df1e59a5c80db710e48408eb1130eae3a3`，tree 为
`738785bde0df7bd9efcddbbfcb6f88402bd4548d`。独立分支
`agent/xiao-74-history-http` 仅交本片，由中级A串行接入PR22。

## 实际职责与兼容边界

`SessionEventRequest` 承担 event 的 seq/before/after 严格十进制解析，
保留原正则、JS安全整数上界、邻接上界4、重复键/未知键/编码值拒绝，
不做URL解码。body或Transfer-Encoding、非法query及seq仍归为
`target=-1,before=0,after=0`，由原账本先核权再报告输入错误。
不创建新的授权查询或提前抛400；未改变实际读取事务。

`SessionHistoryHttpRequest` 仅提取重复JSON媒体类型/query/长度校验及
metadata的HTTP拒绝。search/trace仍在原authorize调用后校验，长度上界
分别为2048/1024；未知长度-1及兼容媒体类型的原行为保留。
metadata仍先调用history再校验，保留原异常类型和400安全code。

`SessionHistoryHttpResponse` 映射原始String为200、application/json、
Cache-Control: no-store；不解析、重编码或包装JSON。
Controller的URI/签名、认证、System.nanoTime起点、2秒绝对deadline、
毫秒取整、DeferredResult回调、SearchRequest及attribute key均未改动。
没有改body读取、异步filter、服务、持久化、POM、bootstrap或冻结resources。

## 开发自测与源码核对

环境：Windows，Temurin JDK21.0.12.1，Wrapper Maven3.9.9。

| 命令 | 结果 |
| --- | --- |
| `.\mvnw.cmd -B -ntp spotless:apply` | exit0；格式完成，仅本片Java文件变化 |
| `.\mvnw.cmd -B -ntp '-Dtest=SessionHistoryHttpAdapterTest,SessionHistoryViewServiceTest,SessionSearchContractsTest,SessionTraceContractsTest,PackageBoundariesTest,RefactoringNegativeGatesTest' test` | exit0；82项、0失败/错误/跳过；Enforcer/Spotless/Checkstyle通过，编译683个生产源及370个测试源 |
| `.\mvnw.cmd -B -ntp -o spotbugs:check` | exit0；0 bug、0 error |

新增25项直接Controller/请求测试覆盖非法seq、重复/未知/编码query、
最大安全整数与0、body/Transfer-Encoding拒绝，非法输入的403优先和
target=-1传递，metadata先核权，search/trace媒体类型与长度上界/超界、
相同deadline及attribute、成功/失败回调、响应原文对象/状态/header。
请求替身在非预期HTTP访问（含body读取）时失败。
原服务/协议57项继续执行，未复制旧完整矩阵或重复集成driver。

开发源码核对：三处提取的条件次序、短路、异常转换及response构造与
基线一致；Controller diff仅职责提取和接线。开发自测及核对不代表独审/QA。
真实HTTP字节写出、MySQL事务、异步filter/容器超时竞争没有在本片重验；
未运行完整verify、付费调用、部署或GitHub CI。原有失败记录不变。

## 接收与回退

交付tree与原始日志见本项最终评论及附件。当前Git未配置提交身份，
`git var GIT_AUTHOR_IDENT` 实际exit128；未擅自覆盖身份，单一提交尚未完成。
附件完整patch可在上述基线用 `git apply --index` 接收，再核 `git write-tree`。
先审准确差异，再由唯一
集成人cherry-pick；不覆盖其他分支文件，不自行修改PR22/master。
独审与必要QA须对接收后的准确源码结论另列，不能把本片自测记作其通过。
回退revert本片即可恢复内嵌HTTP适配，无schema或数据回退。
