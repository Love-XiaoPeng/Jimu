# 写入依赖的实体来源上界

本批输入为886a71f，冻结pm-r21-input-verification.json为295228字节，
SHA256 3dace923093b5bd88c2171115b7043e6961d3c7dfc31bc6d08cd2a1ca57ca491。
按XIAO-127核收的source-bound.patch落实；原Fields/key/observed/supplied及关系注册保持。

keys在Fields构造后先预付8项，再读取final first/second计算局部来源上界：
second非空为3，否则first非空为2，否则为1。仅param1实体仍取3，保留中间空位。
新增8项包含owner判断、两字段读取、两NULL比较、两选择及局部int写入；
每次boundedKey调用前另预付2项，包含新增owner判断及int参数交接，短路不退款。
boundedKey除循环上界外沿原key值/类型/空串/别名冲突算法，每次新读真实Map/entity值。
删除的仅是尾部不存在MetaObject来源的Fields.value调用（原每项先付1后返回NULL）。
没有缓存参数值、当前权限或跨事务证明，没有新增堆对象/字段/数组/token。

完整执行K次键抽取、总别名数A、来源上界C时，删除(3-C)A项，新增8+2K项。
提前冲突或缺tenant按实际前缀计，不外推净收益。

| 参数形态 | 原before | 本版before |
| --- | ---: | ---: |
| 普通tenant/taskId Map | 38 | 30 |
| et实体 | 45 | 47 |
| 仅param1实体 | 52 | 64 |
| et/param1同一实体 | 45 | 47 |
| et/param1不同实体 | 55 | 67 |

普通成功DML完整before+observed从38+12=50变为30+12=42；observed仍12。
实体路径增费照计，不能从上版超额3项推断整条second链缺口或本版通过。
公共Fields上界草案会使observed增账，不在生产实施。

原4096 extraction owner仍在参数访问/分配之前全额预留，
sourceCount局部int及调用参数在此owner内，不延长Fields/参数/MetaObject生命周期，
before/observed的原finally在断链后恰一次退款；不缩减额度。
动作及别名生命周期见同目录source-bound-accounting-r22.csv和source-bound-ownership-r22.csv。
外部别名/通用meter/TSM/JVM/native/socket的完整A12缺项不由局部账闭合。

定向验证采用原19项与新增9项（28项）：全部实体来源/中间空洞/同对象去重、
真实ParamMap缺别名、各显式别名/坏类型/wrapper、动态来源、五类操作/回调轨迹，
N-1/N/N+1、原4096 owner分配前拒、首错/cause/SQLState/suppressed/Error，
实际SpringWork配模拟JDBC单次回滚。事务Fixture仅供编译，不执行其旧15方法。
普通before精确N=30；29拒绝，30/31动作完成后主动回滚，不声称提交通过。
剩余7项时新inspect8在Fields构造后、tenant读取前拒绝。
INSTALLATION原task候选冲突及关系observed三键/缺键保守失效仍保留，不扩SCALAR。

实际命令、退出码、准确源码/tree/JAR、独立second及response全成本探针由外部交付证据绑定。
本文件不声明稳定候选或完整A12，不替代九节点、M2全出口、新fence、网络R1R2/ACK验收。
256累计ResultSet/绝对2秒/1MiB scratch/65536操作及raw来源/锁/live/fence不放宽。
无SQL/V23/schema/pin/raw-digest/wire变更；回退仅撤销本独立增量，无数据迁移。
