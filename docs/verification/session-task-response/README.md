## v5 submit 非阻塞出口检查点

本批只接管 `/api/session-trace/tasks` 的内部返回载体。认证、原正文 filter 和同一绝对截止不变，成功仍是 202、Location、no-store 和固定 Tasks.View JSON。业务提交与传输结果分别保留；commit 后输出失败不回滚已提交业务，也不把 ACK UNKNOWN 翻译成成功。

`SessionTaskAsyncReturnValueHandler` 明确排在默认 ResponseBody/HttpEntity 处理器前，其他处理器顺序保留。它通过 WebAsyncManager 建立 DeferredResult 写周期；业务 ready 只安装 pump，JSON 全部写完并归还引用后才设置最终 null 结果。最终 null dispatch 不重装 listener。Spring 7 的 DeferredResult 内置 error 生命周期在用户 interceptor 之前运行，因此该载体另用局部 AsyncWebRequest 委托接管 error 回调，防止在途借用尚未归还就抢先 dispatch。timeout interceptor 同样停止默认结束。不直接调用 async.complete。

Controller 在创建 carrier/pump/gate 之前以 response.prepare 预留 8192 字节请求私有 scaffold；尚未绑定 query 时按原 65536 基座和 1MiB scratch 上限记住这笔预留，bind 时整体转入同一 query/response owner。consume 再先预留 128 字节单次 lease 和 12288 字节内部工作区，之后才分配 cursor、唯一 byte[1024] 和消费闭包。没有新权限缓存、查询时钟或累计操作额度。

PRODUCING、WAIT_READY、DRAINING、CLOSING、TERMINATED 和 active 借用数由短锁协调；外部 I/O 不持锁。producer 与写回调串行修改 query，timeout/error 只提出终态意图，在途归还后才记录首错、清 View/token/chunk/sink 引用和恰一次退款。原正文 filter 的定时器在 v5 submit 交出正文后继续运行到响应结束，仍使用最初的 deadline；其他 SearchRequest 保留原三参数构造与生命周期。

游标保留字段与 artifact ordinal、字符串 offset、packed escape/UTF8 剩余 byte、负域 long/divisor/padding 及 Instant 日历阶段。isReady=false 立即归还回调线程；不会重新编码或重发前缀，不 flush 重试。准备的 byte 只有实际 sink.write 成功才计 wire completed；peer 接受 17 byte 后失败仍是 requested18/completed17。

`response-accounting.csv` 按实际源码位置列出 serialize、encode、number、instant、copy、wire 的申请点；copy 只记录向 chunk 的真实存放，不虚构 hash。各访问/条件/循环/转义/hex/surrogate/数字符号/计数/补零/日期分支在游标和 pump 内显式计量；不同暂停次数额外产生真实就绪检查，编码/副本工作不会重做。原 synchronous writer 只保留既有内部 converter/测试 oracle，submit 已走新 cursor。

`response-objects.csv` 是明确列出的保守内部账模，尚不是 JVM 全对象图测量。Servlet/MVC 容器的传递对象、输入/输出/native socket 缓冲与全部公开出口仍需联合全资源核验；不以这里的 8192/12288、旧 2048 或浅尺寸证明 A12 全副本或全链 2 秒。真实 Tomcat/socket 测试覆盖短响应 JSON 和慢正文截止；受控 Servlet 测试覆盖背压、恢复和在途错误。没有宣称慢接收端网络 R1/R2 已完全闭合。
