# V23 lineage 字符串长度充分条件（r20）

基于准确 `0eb15e32d49519480834cbd4d61a0136fb8272e5` 及核收报告
`xiao127-0eb15e3-decision.md`（SHA256
`8f36c5c0ab949c7f44b13c72fb06d4e4347be5796a4d0eb735af52a02b9ccd7c`）。
报告冻结输入 `pm-r19-input-verification.json` 为143082 bytes / SHA256
`49e8fe5781a6db497a90b8fc3c1e51cc5737a6e538bfe7c3a64006099ec8bd11`。

`SessionLineageMetadataBatch` 按原8列集合/类型/行数逐次读取；每列分派仍付1。
String先付7，再读UTF16长度：大于两倍码点上限拒绝，不超过码点上限直接成立；
其余先付UTF16长度再执行原codePointCount。7包含getter/局部存储/比较/分支和返回或fallback交接；
helper及类型分派属于原每列1，不新增缓存/字段/API/SQL/schema/pin。
同形五字符串原179扫描→35 guard，构造187→43。短于7字符可增账，
宽字符串仍扫描并多付7。这个局部差值不能预测finish或完整wire成本。

原RETAINED_BYTES=3440及1024 facade/table/list余量不减；在supplier之前预留，
rows=null之后恰一次退款。新增基本整数和借用引用只在同步栈，无新字符串/数组/集合/节点。
原Set.copyOf/keySet、外部Supplier/Map别名和通用meter/TSM/JVM/native/socket不在此次封口范围。
原scalar仍拒绝孤立代理项；不签发raw/权限证明，不删两次values之间的动态graph_bytes读取。
费用与对象范围见同目录两份 `lineage-short-bound-*-r20.csv`。

必要验证选原4方法+新8方法；新测试无候选开关或静默跳过，含43/42/44阈值、
首槽/退款、128 UTF16扫描预付、64补充码点边界、NULL/错类型/错列/多行、loader Error、
两次动态values及与JDK codePointCount一致的241内部样本。241不是JUnit方法数。
按变更执行格式/静态/定向verify，不重跑67/139旧矩阵。

真实second与独立同query response结果由本轮准确SHA/tree/JAR和各批清理证据单独绑定。
本文件描述实现，不能代替真实业务通过。256 ResultSet/2秒/1MiB/65536均不改变；
九节点、同response三trace、完整A12、网络R1R2/ACK UNKNOWN及新fence仍需原验收，
不将这些定向验证当A03或正式独审QA。
