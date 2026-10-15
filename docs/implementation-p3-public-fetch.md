# P3 公共抓取阶段

以已验收的核心 `605b927800e7fe52b1c258574c538a6b6942e6bb` 为基线，先交付架构交接第 6.2 节的公共抓取适配器和可审阅插件契约。本阶段是库和插件契约交付，**不是已接通的公共网页任务，更不是 P3 运行验收**。

## 装配边界

`PublicWebReader(Set<String> allowedDomains)` 实现现有 `WebPageReader` 端口；可与 `WebFetchPlugin` 和 `BuiltinManifestLoader.loadPublic()` 返回的 `jimu.web.fetch@0.3.0` descriptor 显式组合。此阶段没有新增 HTTP 调用端点、环境配置开关、安装记录或任务模式。默认 Bootstrap、DurableAgent、任务快照和恢复仍使用原 `0.2.0` fixture，不能仅用新 descriptor 替换它并声称公共抓取已接入。

新 manifest 单独存放在 `plugins/builtin/webfetch-public/plugin.yaml`；loader 严格验证版本、网络权限、Schema 路径、入口和全部字段，不动态加载 class。复用原打包的输入/输出 Schema，四字段 `httpStatus/sourceUrl/content/truncated`、URN 和输出摘要 `08a144489ed9ea37b5b33a5ffdc815237f31d4f229b76ba1d5e465b9e13f9ff8` 不变。`0.2.0` manifest、reader 和全部权威基线/迁移原字节保留。

## 出口和内容行为

- 运营者传入精确、小写 ASCII 域名集合；空集合禁用。只允许 HTTPS/443，拒绝 userinfo、fragment、IP 字面量、通配或子域扩权。模型只有原来的 URL 参数，不能注入头或凭据。
- 每跳解析并检查全部 A/AAAA 答案；任何私网、回环、链路本地、共享/保留/文档地址或 IPv6 转换地址使整次失败。IPv6 采用保守的 `2000::/3` 子集，另排除 IETF 特殊用途、文档和 6to4 范围。
- Apache 自定义 `DnsResolver` 只返回本跳已检查的地址集，不再查询系统 DNS/反向 DNS。原 host 用于 HTTP Host、TLS SNI 和证书域名验证。匿名 TLS 上下文显式使用空 KeyManager 集合，不继承 JVM 默认客户端证书，保留系统信任校验。每跳新建连接、不复用，不自动重试/认证/带 cookie/重定向；固定直连路由，底层显式 `Socket(Proxy.NO_PROXY)`，不使用系统 HTTP/SOCKS 代理。经典 HTTP/1 客户端不处理 Alt-Svc。
- 同源手动跟随最多 5 次；每跳重新验证 URL 和 DNS。跨源即使也在域名集合内仍拒绝。
- 响应最多 64 个头，每行最多 8192；正文传输和解压各最多 2 MiB，流式计数，超出显式失败。只支持 identity/gzip；拒绝其他 Content-Encoding、歧义类型头、非 UTF-8 和非 HTML/plaintext。超界、解码失败或重定向时先 abort，避免底层 entity close 自动排空无限正文。
- 整次调用使用任务剩余 deadline 与 30 秒较小值，轮询取消并 abort 活跃请求，覆盖慢速响应；固定地址集内的回退每次创建 Socket 前也重查取消/deadline。系统 DNS 的底层阻塞不保证能被 Java 中断：调用者按 deadline 返回，迟到解析结果经过取消/deadline 检查后不能建连。DNS 线程可能晚于调用者退出，故本阶段**不宣称完成任务 quiescence/进程树排空接入**；接入既有 worker supervisor 前需补生命周期证据。
- `public-text-v1` 为确定性的纯文本子集，不宣称通用 HTML→Markdown 或 JS 渲染。jsoup 只解析已取得的有界字符串，移除脚本、样式、表单、导航、iframe 等节点，不执行脚本或加载子资源；保留文本并转义 HTML 标签字符。输出有来源及整段不可信标记，最多 200,000 个 UTF-16 单元，按完整 Unicode 边界截断，显式 `truncated` 和 `[TRUNCATED]`。另检查四字段 JSON 序列化总量 ≤1 MiB。
- 非 2xx 和空正文保留可观察状态；这些结果尚未接入报告有效证据分类。调用者不能把不可信标记或 HTTP 成功本身当成报告证据。

后继模型输入还必须受更小的 profile 输入限额约束；本阶段的 200,000 字符上限不替代模型派发前预算检查，也不扩大任何付费开关。

## 依赖与权限

| 坐标 | 实际版本来源 | 许可 | JAR SHA256 |
| --- | --- | --- | --- |
| httpclient5 | Boot 4.1.1 管理 5.6.4 | Apache-2.0 | bdef5f8841145cd76c4c605ff301bc840eea0d5292332b7b892a93316c0d8ee1 |
| httpcore5 | Boot 管理 5.4.3 | Apache-2.0 | 18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a |
| httpcore5-h2 | Boot 管理 5.4.3，客户端传递依赖；本实现使用 HTTP/1 | Apache-2.0 | c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6 |
| jsoup | 显式固定 1.19.1 | MIT | 7b850eb00f2f77f606a343b2e4effe4107f3c1faa183729d85fbe16baf606096 |

Apache JAR 均含 `META-INF/LICENSE`/`NOTICE`；jsoup 含 `META-INF/jsoup/LICENSE`。现有 SLF4J 2.0.18 由 Boot 管理，没有添加其他编排框架或升级 Boot/AI。依赖树和 Enforcer 收敛检查作为实际解析证据；发布包保留依赖内许可。

后续启用所需新增权限仅为运营者精确域名列表的匿名 HTTPS/443 出口；本阶段没有启用、申请或使用任何收费权限。真实模型、Exa 搜索账户/权限、适用价目、币种换算及完整计费输入上界仍沿原依赖记录，付费调用继续关闭。

## 默认回归与证据范围

新增 15 项 JUnit 默认被 Surefire 发现，不使用新 profile/skip；全部原 P2、模型协议和 R1–R3 回归仍在默认 `./mvnw -B -ntp verify`。本地定向命令：

```sh
./mvnw -B -ntp test -Dtest=PublicWebReaderTest,PublicPageProjectionTest,PinnedHttpsTransportTest
```

实际传输测试生成临时自签名 TLS 身份并精确信任该证书，使用回环 fixture、显式地址和随机端口直接测试包内传输层 seam；生产 `PublicWebPolicy` 不放行回环或非 443。测试证明真实 TLS 建连、原 host/SNI、证书域名拒绝、系统代理不被查询、无自动重试/重定向/认证/cookie、响应头/传输/解压上界和慢速响应取消。另有服务端请求客户端证书的对照：带测试密钥的客户端确实发送证书，而公共匿名上下文在 JVM keyStore/trustStore 配置同一测试身份时仍不发送证书。纯策略与组合测试覆盖混合 DNS、重绑定、跨源/5 跳、迟到 DNS 禁建连、Schema/manifest 和 HTML/UTF-8/JSON 上界。它们是本地替身运行证据，**没有执行外部公共网页、真实模型或真实搜索评测**。

首轮新增测试曾发现普通 Socket 仍查询 JVM ProxySelector；显式 NO_PROXY 后复测。另有一处测试把 YAML 通配符写成非法 alias 的断言问题，已修正为合法但不被允许的权限值。保留首轮失败日志，不能把它们称为已有核心阶段回归失败。最终 SHA 的完整门禁结果由本次交付附件单独给出。

## 后续必需工作

1. 显式运行模式、安装/任务版本及恢复兼容装配；公共 reader 生命周期接入 supervisor、profile 输入投影限额和无秘密出口。
2. 按固定工具矩阵实现 workspace read 观察、稳定 operationId + write CAS、防 symlink/竞态的 FilePort。
3. 六栏目报告、实际抓取有效证据及引用关联、present 自身 invocation/文件版本/摘要绑定、原子发布/结算、最终成功要求与授权下载。
4. 搜索准入确认后的单一适配及真实评测。当前指定 URL/本地 TLS 不能代替搜索和完整 P3。

本阶段不改变父任务 in_progress，也不构成 PR 合并许可。历史自然 readiness 超时和旧 CI SQL 失败的确切根因仍未确认，沿已验收基线的限制保留。
