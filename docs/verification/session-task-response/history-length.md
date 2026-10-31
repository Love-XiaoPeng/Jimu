## 运行时签发与独立history owner

具名路由和metadata补账后的真实finish与wire仍拒绝，因此后继实际删除了eventHistory八列的重复标签分派：bind已核八列准确顺序，provider传真实ordinal，第3列才走原cap；每列新增4普通操作覆盖参数/比较/分派/cap条件。原八列分派各申请`1+label.length`及binding额外2，完整一行共110，新分派32，局部差78。原label/query/raw statement单次能力校验、size/raw ResultSet及全部当前权限/末端fence不动；没有新容器/owner或动态值缓存。不把这78从旧业务首拒中相减当成通过，原首次补账带来的净增成本另行保留；最终准确制品另跑必要链。

具名 history 调用由 `MapperSessionHistoryProofs.load` 的 acceptedEvent=false 分支直接进入包内 `JdbcSessionRecords.historyRows`。每个非空 query 在所有分支先申请12普通操作，覆盖分派、guard、字段引用、mapper及两个key查询和交接；不新增容器、闭包或owner。通用 mapperRows 删除 history 专属的 suffix 判断12及完整比较43，保原 eventHistory、args/meter/selectList。真实调用图另列于 routing CSV；metadata 表投影数不是 mapperRows 次数。原型同输入局部 history1941→1898、metadata2480→2468；searchBudget/nonlineage2468→2480的新增成本保留，不从旧失败差值推业务通过。

独立十五列 history 的每个 size/raw ResultSet 至多缓存一份 metadata view，缓存仅是包装器，不保存值/label/raw/权限。每次 getMetaData 在健康检查后申请3操作（字段查询、判断、交接）；游标创建由8改为10，先预付无条件执行的缓存字段及cursor临时引用清理；首次另预付8（loader、接口数组及元素、handler及两个捕获引用、proxy、发布）；每次 view getter 申请4（当前metadata查询、判断、真实反射调用、交接）。全部分配前完成申请，已有首错时不再申请。两份view的对象、临时数组和别名逐项纳入原slot4096清单，未扩大优化资格或复用cap。关闭在driver.close前清缓存/metadata/delegate，finally先断局部cursor，再按原顺序清Result/length/Statement/cap；清理不在sticky failure后补申请。外部view只能见已脱离driver的state，原Work清raw后才退款。此修复只影响独立history包装器，通用及eventHistory包装器未借此声称全覆盖。

长度factory最先保留原首错，以StackWalker直接caller名和同ClassLoader核验真实QueryResults，再进入原具名statement/flags/meter/read逻辑。普通Java、反射公开factory和同名异loader均在read增加前poison；没有测试豁免或kernel→adapter依赖。512字节是新增caller临时引用/帧的内部保守模型，不是所有JVM/native分配上界。

十五列history与八列eventHistory并列。新`historyBindingSize`仅在原tenant/task、同DS/query/connection和FOR UPDATE锁下对PRESENT且cleanup=NULL的binding计长；0行仍读原完整raw。raw反查十五个label、实际Statement/ResultSet、完整key/Work/token/query/connection和原锁，再核PRESENT/cleanup=NULL。cap绑定准确raw入口；写前失效，不能跨入口、query或事务重用。原Service的record/history版本、binding比较、独立reader/digest/schema、retained及末端scope.live/fence没有删除。

每次重新查size/raw，四个独立scratch key和Slot链持续持有四份raw，写入只使能力失效。第五次回原完整扫描；不覆盖旧key、不退款旧raw、不缓存next_seq/body/metadata/权限。构造/交接失败保首错；Work先清closure/Map/list/Statement/ResultSet/cap/key/别名再退款。flags-off、additional-meter、无owner和legacy保原完整扫描，额度和期限不重置。PURGED长cleanup走独立完整计量，不受binding长度包络。

`history-length-accounting.csv`列出实际请求位置，`history-length-objects.csv`列出每份owner及引用，`history-length-routing.csv`登记新SELECT只读语义；长度前查不进入依赖关系证明或DML放行表。模型仍不证明A12全部heap/native/socket或所有公开出口。新增比较、lookups、容器/链接/清理和额外ResultSet均计在原单位，未从旧日志扣减影子原型收益。

真实业务测试的SessionHistoryObservation只在test sources和显式observe模式装配。其独立stack/Map/UTF8 oracle不写生产meter，观察与复制耗时仍包含在原2秒绝对截止；观察记录在事务返回后才序列化输出。22表独立baseline先于请求创建，后续同一个query/原绝对2秒贯穿准入、终算和wire，不重设deadline。观察逐query记录history ordinal、caller、B/P组、进出meter、写间隔和实际动态raw；不构造authority、不改变计数/权限/业务行，也不将测试设施的副本计作生产A12完成证据。

新增局部删除点是history raw的逐列分派。原十五列每列都执行一次`binding_snapshot.equals(label)`，现在provider将其真实loop ordinal传给Slot，仅第5列可消费cap；bind仍核对十五列准确顺序，columnSqlUtf8仍重新核对实际label、query、raw statement及单次cap。其他十四列全部使用原独立scalar计量，动态next_seq/body/metadata及所有owner生命周期不变。新增ordinal参数/比较/分派请求4普通ops，替代原3+16的比较包络；一个真实十五列raw调用局部少225 ops，不从旧失败中直接扣减宣称业务通过。无新增对象、缓存或提前退款。移位binding列及bind后更改binding label均必须poison，catch后原首错仍回滚。
