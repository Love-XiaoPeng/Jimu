# 普通DML依赖抽取消重

5c95171的独立response已完整发送230/230字节，但真实second在65520+171的Slot.verify预付仍拒绝。
本增量删除SessionDependencyWrites.relationKeys中TASK/PROGRESS语句的前置Fields创建、
tenant/task别名完整读取及supplied扫描。两次Kind比较先预付2项，再直接进入原keys。
TASK/PROGRESS仍完整执行原keys；INSTALLATION保留原前置task候选冲突检查及原keys，
未知Kind/wrapper先保守拒绝，
STEP/DISPATCH/INVOCATION/ATTEMPT/RESERVATION/BINDING仍执行原关系三键校验和真实observed证据。

此前普通DML参数被relationKeys及keys各读取一遍；前一遍的结果从未进入keys，只有后一遍是最终依赖。
不保存前一遍结果、不过滤别名、不扩大PROGRESS-SCALAR注册，不从caller猜关系。
新增的两次Kind判断全部预付2项（短路时也不退）；无新增循环、字段、授权、堆对象或token。
关系类保留原完整抽取与type分派，额外2项先付；不把普通路径节约外推到关系路径。
剩余Fields.value读取与别名比较仍按原inspect先付费，删除的调用及其预付一起消失。
字段抽取只发生在同步DML边界；原4096 extraction owner仍先预留，finally退款，不缩额。
既有Slot/raw来源、锁、独立raw/digest/schema及末端live/fence全部不变。

新增测试验证普通DML一次tenant读取、准确依赖，缺键/冲突Map和实体别名/wrapper保守失效，
并覆盖INSTALLATION带冲突task候选仍按未知依赖失效；
实际SpringWork/scripted JDBC验证操作N−1/N/N+1及首错保留、单次回滚与清链退款。
原SessionDependencyTest及SessionCheckedHistoriesTest检验关系证据及caller/source失效，
与33项metadata定向测试分别按最终制品执行，不重跑旧完整矩阵。
精确操作收益只由同形原版/新版probe计量；不拿旧155超额推断整条second链通过。
两批业务探针、版本、JAR及限制由外部交付证据绑定，本文件不声明稳定候选或完整A12。
