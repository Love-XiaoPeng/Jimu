# Metadata身份通道：生产接入检查点

本批落实51741ab的两个反例及已核准身份表示。实际身份取自当前线程已安装、未挂起的私有ConnectionSettings侵入链；显式batch.query只支付发现费用，不授予身份、证明或权限。没有新的Spring资源key、ThreadLocal、Map或独立节点。无当前Work时不访问Spring/JDK ThreadLocal；每次closed/failed尝试也收费，首错不可清除。

head/count及next/previous/thread由同一类monitor保护；挂起摘链，恢复原Settings，同query和异query的REQUIRES_NEW均保留独立Work/token。每次结束先断链、清holder/physical/token/failure别名，再释放4096，最后complete Work。构造前bootstrap4160，交接时bootstrap64与Settings4096并存；注册前拒绝也释放Settings及根Work1280。固定4096只持有本节点及拒绝对象，不解释任意外部Map容量。

计费保持原operation单位。发现12+3a（a为当时全部登记节点数）；已失败外来receiver向真实当前query另付8登记。健康guard24、资源读取8+(8+4r)、物理解包2+4p，合计54+3a+4r+4p。r是实际线程资源Map基数，p是实际ConnectionProxy边数。Spring7.0.9只读view构造前留64，finally断view后退款；不copy、put或移除void holder。数据源为生产装配受控实际provider，按Spring真实unwrap，不扩成任意外部DataSource证明。

安装fixture共128，相比51741ab的108增加20；挂起每次16、恢复每次12+3a。每次扫描、方法分派、字段与callback清理费用见metadata-identity-r19-accounting.csv；同目录旧CSV按各自历史版本解读。发现失败只允许扫描及失效登记，不再读holder/物理连接或执行正常业务。Error、SQLState、cause、suppressed及已有首槽保持原规则。

定向测试覆盖无Work12/次、新平台线程0→0、r=1/9/65对应61/93/317、a=2对应64、60/61/62健康查找、11/12/23/24/25/60/90及127/128/129安装边界、bootstrap及view scratch N−1/N/N+1、跨query/holder替换/嵌套挂起恢复/UNKNOWN。另验证实际代理边成本，以及短monitor竞争计入原绝对截止；线程均finally join。monitor本身没有2秒公平性或等待上界保证，测试观测不替代全链保证。

metadata局部5528，scalar仍2295；比5714少186，但仍比原raw4777多751。history/event含安装为2134/1381，各自N−1拒绝、N和N+1通过。不能据局部净减推真实SQL或响应通过；真实私库探针须另绑定准确commit/tree/JAR。

其他current/currentOwner/connection及execute同步器快照仍保留。完整A12仍缺通用计量容器/ThreadLocal基础设施、JVM/native/socket、外部facade持有脱离rows的query/Work及fallback key先new后retain等账。14列/原WHERE/时机/两values循环、动态权限live/fence、独立raw reader-digest-schema、日期/字符串及四raw驻留与第五回退均保留。256 ResultSets、2秒、1MiB、65536不变；不是稳定候选、正式独审QA、A03、九节点、M2或网络ACK验收。


## 真实拒绝后删除内部重复查找

351ff0b两批仍在首submit拒绝（second65536+1→65537，response65511+64→65575），各22表回滚；这些只说明首拒位置，不能估作总缺口。原始PHASE、OP_ACCOUNT、制品与cleanup另保留，各少于180秒，不借旧finish/wire。

随后在生产values迭代器next实际删除一次内部value(name)调用。next内部hasNext已经执行open，核Work/query/token、事务/holder/物理连接和原绝对截止；其后只有私有列List、已持有MyBatis结果Map和primitive索引读取，中间没有外部provider或应用回调。直接从rows.get(index)按固定列取值，仍付原1操作；独立公开value(name)、每次外部hasNext/next/scalar及closed/foreign入口全部保留原检查。不能把外部hasNext的旧结果缓存到下一次next。

a1/r1/p0局部单次next从123变62，只删除实际内部重复的61；完整样本5528→4674，scalar2295不变。原4777对新4674差103仅属于这个样本；生产两values循环、14列投影及全部动态值不删。新增回归同时检查next费用、closed next被catch后首错和整事务回滚；10个原metadata方法及8个生命周期方法按影响复验，19项通过。新的真实SQL与响应仍需按新commit/tree/JAR分别判断。
