交产品经理：本片为首次 translator 冷初始化分段诊断交付，response 产品验证继续 OPEN。生产源码未改，不依据不足改异常、拒绝、结算或截止规则；SQL 删除 0，沿用库存 182／26／587，413 份资源／冻结输入按准确基线逐份匹配。沿原分支 agent/agent/7aeccb14d7a9／草稿 PR22，源码 SHA／tree 及远端回执见交付 manifest。

复用准确旧产品 c1bf9870dff1b7f5a30e9f7d49c2e953da6d650f92e270fd9627ccbe3176522d（44,391,230 字节、1,440 entry），新产品 dc1d44dbf9715b0b70fd3bf7293f08d4c83eb816ac2d299d2a60b5023f9f18f0（44,403,849 字节、1,451 entry）。本轮 verify 打包文件未用于产品对照，不交作新受测产品。

按准确库 class 原字节及 javap 核到：MyBatis supplier 创建 SQLErrorCodeSQLExceptionTranslator(DataSource)；setDataSource 立即取 SingletonSupplier；SQLErrorCodesFactory.getInstance 首次惰性构造，创建 bean factory／XML reader，加载默认 sql-error-codes.xml、探测根路径 override，再 getBeansOfType，之后才 resolveErrorCodes／数据库 metadata。class 原件、反编译、观测 class 和实际调用阶段均保留。构造／<clinit> 仅插入静态内存标记，不传未初始化 this、不改原帧／返回或异常；构造异常仍由原调用者观察。方法包装保留原 Throwable 对象，完整 cause／suppressed／SQLState／vendor code 单存。

自然 run1 退出 1。旧侧 2,009,380µs 生产 EXCEPTION：responded 追加事件的 SessionHistoryStorageMapper.body 在语句准备的原截止检查触发 SESSION_QUERY_TIMEOUT，MyBatisSystemException 包装。该侧首次 account UPDATE 已成功；不是历史末尾 account 08S01。随后外层回滚 SQLState 08007／UNKNOWN，无事实恢复证明，不重进或重放。新侧 1,976,603µs 正常返回并完成一次捕获事实外层回滚，仅为该样本。进入 modelResponded 前旧／新已消耗 423,513／264,120µs，保留原 queryView 构造和两秒时钟起点，没有把构造移出计时。

run2 退出 1：夹具准备的 apt-get JRE 下载超过 120 秒，尚未创建容器或进入产品调用。原执行归档／TimeoutExpired／无容器清理回执保留；不计完整容器清理成功。run3 显式使用现成 Java 21 工具链，未改生产或 timeout 设置，进行尚未执行的首 account 合成对照。run1 使用自动取得的 Ubuntu JRE 21.0.12.1；run3 使用现成工具链，各轮准确版本在 assertions.json。两轮不作为相同 JVM 环境的性能对比。

run3 控制合同退出 0，但旧／新生产均 EXCEPTION。只在首个 account UPDATE 执行前抛一次通用 SQLException 08S01，未执行该 UPDATE、未损坏连接，翻译为 DataAccessResourceFailureException；与历史 Connector/J CommunicationsException／RecoverableDataAccessException、失效连接及末尾 account 位置不同。各一笔捕获 16 张 tenant 事实表及 tenant/evaluation 预算全列相等的外层回滚，不能称持久提交、整库回滚或独立 afterCompletion 后记。RESULT success 仅 trial 标签。

| 本轮合成对照阶段（含嵌套，µs） | 旧 | 新 |
|---|---:|---:|
| initExceptionTranslator | 685,517 | 554,256 |
| supplier | 685,474 | 554,234 |
| translator(DataSource) 构造 | 652,687 | 528,415 |
| 错误码工厂构造 | 625,238 | 507,820 |
| bean factory 构造 | 27,044 | 25,786 |
| XML reader 构造 | 28,376 | 19,409 |
| loadBeanDefinitions(Resource) | 245,857 | 205,167 |
| DOM loadDocument | 124,352 | 128,088 |
| 注册 bean definitions | 78,136 | 63,990 |
| getBeansOfType | 158,642 | 114,297 |
| resolveErrorCodes | 6,140 | 4,219 |
| 读取数据库 metadata | 400 | 478 |

旧／新 SQL 故障至 driver 捕获分别 696877／562789µs；该区间新 datasource 获取均为 0。首次 bean factory 定义转换返回至其 <clinit> 入口另有 120242／110431µs。TRANSFORM 标记只证明观测转换时间，不能将该区间全部归为类装载；父类定义／验证／初始化、调度及其他未标记动作尚未拆分。表中耗时互相包含，不相加，不扣除观测开销。

每轮先封存执行 source/class/agent/compiler/index 及原日志，再开始下一轮；三份 seal 保留，未改历史原件。两个已执行组均用 worker 原始转换字符串逐字节进入 driver，fixture 样本仅 JSON 值一致、原字节不同。两组各旧／新副本在试验前从同批冻结事实建立，未知事务不恢复、不对原槽重试，不增加 provider 调用。两个完整私有容器清理成功，另列一份无容器准备清理。

本地定向 verify 仅检查 6 项诊断单测＋1 项已执行证据合同 IT、原静态门禁与 SpotBugs；IT 不发起数据库试验或把自然失败转成通过。仅开发自测，非独审／QA。准确 gate 回执与完整失败轮另附。

完成 supplier／translator／工厂构造／XML DOM／bean 构造／metadata 的实际阶段分段。本轮自然 account 08S01 未复现；历史 708,713µs 原因、真实失效连接首次 translator 路径、类定义后的未分段区间及组合／冷路径继续 OPEN。没有确证迁移回归或需修的生产缺陷，未改生产。下一具体证据切片可针对上述类定义→<clinit> 剩余区间及真实 account 异常类／连接状态的原始差异；不反复运行等待故障，后续由产品经理按队列接续 metadataView/recent／JdbcLedger.sessionHistory。

60 in_progress、PR22 草稿。B2／35表＋只读 receipt、core 最后 beforeCommit、scope 完整成功 read、持久 FAILED-read、独立后记、冷锁／完整锁排列及全部历史失败／缺件保持 OPEN。没有新增外部或成员授权阻塞。未运行或等待 CI、部署、接真实 key／计费／共享数据服务或操作 daemon／代理／FRP／重置。
