# Cursor metadata 准确列集合的等价计数（r20）

准确前版7e437f4的首second和独立response仍触限；本增量不从其超额推测完整缺口。
普通SessionMetadataScalars.operation入口在首submit/finish各执行2287/3643次，付7499/10811操作；
这是该通用入口所有调用的合计，不冒充validate单站点数字。
真实18表metadata路径按SessionCursorMetadataBatch.validate先遍历所有行key，
每key付1+2*length后做projection.names.contains；之后又按固定schema逐列get/判断presence。
受控旧实现最小TASK_ATTEMPT一行测试实测239操作、keySet遍历1次；
保存红测预期0而实际1，只证明尚可删的遍历，不将其称错误业务输出。

改动删除keySet遍历及names.contains，保留原逐列getter/nullable/type/scalar/slots/presence。
schema的列名唯一（15个原投影逐个验证）。设实际Map键为K，schema键为S；
每个非null getter必有键，null仍用原containsKey区分显式NULL和缺列，
故recognized准确等于|K∩S|。比较recognized==current.size()即|K∩S|==|K|，
充分且必要证明K⊆S。所有nonnullable缺列仍在原类型循环拒绝；
nullable缺失和显式NULL保持不同presence及values输出。删nullable后加入未知键虽不改变size，
recognized仍少1，不能混过。不是凭行数或部分列授权。

新增账：初始化计数器1；每schema列presence读取/分支/至多一次increment共3；
末尾Map size getter/比较/分支及返回交接4。均先付再执行，不按实际值缓存或打折。
删除账为原每实际行键1+2*key.length的遍历/hash/比较，初始化/计数增加5+3*C。
空/稀疏行可能增账，不宣称每行节约；原操作单位、类型读取与两次values循环不变。
错误多列检测在逐列类型检查后发生，但仍抛原SESSION_HISTORY_CORRUPT并经原fail/poison/close；
已记录首错、预算拒绝、cause/suppressed与退款行为保持，未承诺复合坏行的检测顺序不变。

没有新增字段/数组/集合/String/Map/节点/cache/API/SQL/schema/pin；只有同步栈int。
原projection、slotsOwner、游标及行预留保持，不因删除keySet视图减留存或提前退款。
原所有引用清空后退款；余量/外部别名/基础设施等完整A12缺项继续保留。
scope/Work/token、owner、holder/连接、动态权限、raw reader/digest/schema和锁均不改变。

定向验证选择SessionCursorMetadataBatchTest：原8+新3方法，
覆盖原15个投影/SQL/原scalar公式、NULL/缺列/多列和nullable等长替换、
精确操作阈值与原首错、独立scratch owner、关闭失败suppressed、原行数与预留边界。
新测试都执行，没有候选开关/跳过。原lineage12已按前版通过，不在本增量重复计量或相加覆盖。
之后按准确保存新SHA/tree/JAR分别运行有界second及独立同queryresponse，
结果/限制在本轮外部证据中，不以本文件预报finish/第二准入/wire成功。
