## 运行时签发与独立history owner

长度factory最先保留原首错，以StackWalker直接caller名和同ClassLoader核验真实QueryResults，再进入原具名statement/flags/meter/read逻辑。普通Java、反射公开factory和同名异loader均在read增加前poison；没有测试豁免或kernel→adapter依赖。512字节是新增caller临时引用/帧的内部保守模型，不是所有JVM/native分配上界。

十五列history与八列eventHistory并列。新`historyBindingSize`仅在原tenant/task、同DS/query/connection和FOR UPDATE锁下对PRESENT且cleanup=NULL的binding计长；0行仍读原完整raw。raw反查十五个label、实际Statement/ResultSet、完整key/Work/token/query/connection和原锁，再核PRESENT/cleanup=NULL。cap绑定准确raw入口；写前失效，不能跨入口、query或事务重用。原Service的record/history版本、binding比较、独立reader/digest/schema、retained及末端scope.live/fence没有删除。

每次重新查size/raw，四个独立scratch key和Slot链持续持有四份raw，写入只使能力失效。第五次回原完整扫描；不覆盖旧key、不退款旧raw、不缓存next_seq/body/metadata/权限。构造/交接失败保首错；Work先清closure/Map/list/Statement/ResultSet/cap/key/别名再退款。flags-off、additional-meter、无owner和legacy保原完整扫描，额度和期限不重置。PURGED长cleanup走独立完整计量，不受binding长度包络。

`history-length-accounting.csv`列出实际请求位置，`history-length-objects.csv`列出每份owner及引用，`history-length-routing.csv`登记新SELECT只读语义；长度前查不进入依赖关系证明或DML放行表。模型仍不证明A12全部heap/native/socket或所有公开出口。新增比较、lookups、容器/链接/清理和额外ResultSet均计在原单位，未从旧日志扣减影子原型收益。

真实业务测试的SessionHistoryObservation只在test sources和显式observe模式装配。其独立stack/Map/UTF8 oracle/序列化/日志不写生产meter；耗时仍包含在原2秒绝对截止。它逐query记录history ordinal、caller、B/P组、进出meter、写间隔和实际动态raw，并单独导出22表快照；不构造authority、不改变计数/权限/业务行，也不将测试设施的副本计作生产A12完成证据。
