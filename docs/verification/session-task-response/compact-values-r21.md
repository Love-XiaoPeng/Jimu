# metadata values紧凑前缀

输入为53c9cb0及冻结pm-r20-input-verification.json，按XIAO-127已核收的compact-values.patch生产落实。
Object[C]按schema顺序保存有键值的紧凑前缀，缺nullable列不进入，显式NULL计入。
逐列get/NULL-containsKey、原类型、SQL长度私有wrapper及最终recognized==Map.size均保持；
仅检查全部成功后发布primitive valueCount。公开value仍从当前Map读取，未缓存动态权限、SQL或raw证明。

删除boolean[C]及hasNext的presence读取/稀疏while；每次hasNext仍valid并先付1比较边界，
next仍调用hasNext并先付2读取/前移，不复用外部hasNext结果。
数组初始化2+2C降为2+C，每列3stores及3recognition原账保持，末端4增为5含valueCount写入。
正常k轮完整消费局部节省(1+2k)*C-1；重复hasNext、坏行路径独立按实际执行计。
TASK_ATTEMPT构造108→99、同形完整两轮296→247是报告局部基线，不能推算整链余量。

projection.retainedBytes及slotsOwner=256+16C仍全部在supplier前保留，不因少数组缩额。
原current/slots/source/cursor在advance/fail/close断链后恰一次退款；valueCount不持有对象。
无新增堆对象、节点、token或跨query缓存；原外部别名及通用meter/TSM/JVM/native/socket完整A12缺项不由此闭合。
完整动作与别名账见同目录compact-values-accounting-r21.csv和compact-values-ownership-r21.csv。

定向选择lineage原4+8、cursor11及compact新增10，共33方法；保留旧矩阵范围，不重跑67/139。
包含15schema+taskSized、NULL/缺列/wrapper紧凑索引、两轮/跨行新值、失效facade、Long27仍2、
操作与scratch N−1/N/N+1、supplier前拒、独立owner/原Error、首槽/cause/suppressed。
实际SpringWork加scripted JDBC的409先错和429先错验证0commit/1rollback与清链退款。
原未知键校验已在类型后；复合坏行可先类型拒绝，不声称恢复历史错误顺序。

本文件不预报验证或业务成功；实际命令/日志、准确SHA/tree/JAR、两次独立有界私库探针由交付证据绑定。
256累计ResultSet、绝对2秒、1MiB scratch及65536操作不变；Slot/raw来源、锁、live/fence不删。
九节点、全出口M2、完整A12、网络R1R2/ACK UNKNOWN/独立新DB fence仍按原缺项保留。
无SQL/schema/pin/wire变更，无迁移；回退仅撤销本独立提交并恢复反射字段名单。
