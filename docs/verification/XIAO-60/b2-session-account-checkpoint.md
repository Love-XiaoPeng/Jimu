交产品经理：B2 `metadata/scalarBytes/account` 部分检查点，仅开发自测；准确源码、tree、远端回读与制品索引见交付 manifest。沿原分支 `agent/agent/7aeccb14d7a9`／草稿 PR22，issue 60 继续 in_progress。本片不关闭 B2 或整表。

完整计量业务进入 `SessionMetadataAccountingServiceImpl`，类型化端口 `SessionMetadataAccounting` 及 `MapperSessionMetadataAccounting` 接原 Mapper 实际链。16表原顺序、逐表／逐行读取与原 Map.values 惰性遍历保持，不重造标量。每行32，每值先原 int 表达式 `8+scalarBytes` 再累加 long；NULL=1、LocalDateTime 原 UTC 微秒字符串、其他值原 toString→UTF8，求值／异常顺序保持。cleanupHeadroom 原1024及各表768／512／256增量、全扫描后 closing65536／非closing61440拒绝、仅非closing再算 total+headroom+planned 的原 long 溢出行为均保留。

account 保留非session短路、至多4轮 metadata→metadata_bytes 首行 getFirst／原数值→相等立即返回→UPDATE；第4次更新后仍corrupt，无第5次确认。原完整tenant/task、忽略更新数及数据库 `IF(content_state='PURGED',新值,held_bytes)` 沿既有 Mapper/XML，不加CAS／预读／事务／查询。原query计量、锁序、同DS/UoW、绝对两秒截止与全部caller位置／次数不变。SQL实删0，库存182静态／26动态扫描项／587 binds；17个关键caller／并行独占文件、413份资源／冻结输入及schema22/V1–V22逐字节保持。源码核验器最初两次漏排Spotless移除的未用LocalDateTime import，原错误和仅修该排除规则的成功核验均封存，不算产品缺陷。

旧对照固定为最终owner集成受测JAR `c9fa697028f54f9c9bd51c5fa68061811fa0874af3f54752f6b3d63a593335d4`，44,425,290字节、1,467编译／资源entry。新受测JAR `6da92a48f8384a5a179b4966832337471ffc49c15388f64af0b4aee1f368672f`，44,434,527字节、1,475编译／资源entry。三轮均使用这同一不可变新JAR；各轮Maven后续打包文件仅为打包产物，不混为同一ZIP字节。最终生产class／资源与受测JAR逐entry核对，非class资源与旧JAR一致；11片拼接证据见manifest。

实际路径使用新私有MySQL8.0.46／paid=false公开受理和owner样本，冻结后克隆同批事实给准确旧／新JAR。driver核metadata/account及真实 `JdbcLedger.sessionContext`（query-null原两秒queryView构造仍在原调用计时内）和 `ledger.cancel` terminal。无model worker／provider调用。context的claim只在外层事务内执行并回滚；terminal事件／账目只到外层事务内写入。本片未验真实工具read、持久FAILED-read、独立失效后记或全任务。

定向验证分三批封存，不把它们合称一轮完整14项通过：第一轮76项单测／契约通过、IT失败；原生／scope旧新各完成8项RESULT与各9笔捕获事实恢复，第9笔为失败setup。夹具missing-counter的DELETE因原runtime无DELETE权限，42000/vendor1142在生产account之前失败；随后移除非法夹具，不改grants。实际DB缺行getFirst边界未覆盖，仅源码／单测保留。第二轮只执行剩余6项，9项重复单测／契约通过、IT失败；旧新每组完成5项RESULT／5笔恢复，terminal正常返回并完成rollback后，SQL证据中的LocalDateTime被原Payloads.json拒绝序列化，未完成该笔全列恢复证明，terminal本轮不计通过。仅test-only参数观察表示改为原class名及原ISO值，实际JDBC参数不变；证据错误另存，不能覆盖主异常。第三轮只在新私有冻结副本补terminal取证，结果以封存分析及原JUnit为准。每轮原执行source/class、supervisor、编译回执和日志先封存再接续，不覆盖失败轮。

第三轮定向verify退出0：7项单测／契约＋1项集成，零失败／错误／skip；三轮SpotBugs bugs/errors均0，六份完整私有容器清理成功。run1／run2／run3分别封存101／83／81份原件。旧新每组完成14项RESULT，分8＋5＋1三批，不包含首轮失败setup和第二轮未完成terminal恢复证明；每项计通过都对应其原批的19张捕获tenant表恢复。强制固定点两次UPDATE后相等、相等路径零UPDATE、PURGED一次UPDATE；真实context两次account UPDATE、terminal三次，旧新样本一致。

完成项包括metadata开／闭计量、planned上限与原溢出、account相等立即返回／强制固定点／PURGED held计量、容量拒绝、三处直接SQL单次故障、实际context成功及其account单次故障、terminal。直接metadata／counter／account故障为执行前一次注入HY000/vendor1644，保留原UncategorizedSQLException；context原映射SESSION_QUERY_UNAVAILABLE。它们连接仍可回滚，不代表真实08S01／失效连接或UNKNOWN恢复。容量/PURGED receipt仅为计量夹具，不作为合法清理receipt证明。

每项完整恢复证明仅指捕获tenant的19张事实表全列；不称整库回滚。setup的counter／PURGED／receipt UPDATE、claim和生产变更均在各笔外层事务恢复；调用后proof SELECT和重算位于生产返回／计时结束之后，未增加截止内证明SELECT。直接计量／account的SQL文本与bind严格同序，context／terminal仅SQL文本同序比较，原bind另存；attempt UUID及数据库时间不冒称相同。context输出按JSON值同序比较，原串摘要独存，不泛化wire字节／全部往返相同。单测另核NULL／时间／UTF8、惰性异常、全部headroom权重、非session短路、四轮和无第五次确认。

安全提交仅修 `parallel-gateway.md` 对既有测试覆盖的表述为“三项真实advisor＋两项网关直测”。63／64既有集成不重复接收；66严格body／DTO／局部SSE及70独占download／Envelope局部均未碰，66／69／70各沿原回调，不抢接收。65／67独审及68锁410067e／c9fa运行验证不随本片换版。无新增授权阻塞，不建重复接续规则；复用本准确run的唯一三终态＋两小时规则，详见manifest。

结束前已读最新正文，按其要求通过CLI取回68原证据ZIP并核SHA256 `0368581eda615f6dc1317b46f16c9f803582a12fd7820761e87906a73b0a91c5`，核读原 `qa-run1/qa-after-restart.log` 首错：原metadata/account→read.finish链首次MyBatis translator数据库名提取时，deadline DataSource建连接触发SESSION_QUERY_TIMEOUT；随后afterCompletion连接reset失败及回滚覆盖应用异常。原件封入本包，未据此推定最早SQL主错、709ms根因或网关回归，未修改生产异常／事务规则。68已交有保留失败的有界QA结果，不再称其未交回；它的首轮任务FAILED、后继同JAR成功及两个真实提交后合成确认丢失边界均沿原记录，不扩称实际未知提交通过。本片直接可回滚HY000故障不覆盖该真实失效连接／冷路径，交由原报告者依据本准确变更作必要差异复验。

完成本片Service／类型化账目迁移及必要分批差异取证。下一具体B2候选为 `JdbcLedger.sessionFact/sessionAppend` accepted/prepared/model-response/terminal公共事件编排的完整Service／类型化端口，保留可靠history.changed事件→可靠seq→append→account与固定点，由产品经理按队列另行激活。

response产品验证、自然account08S01／历史约709ms间隙、translator冷初始化余段、所有历史失败／缺件、冷锁／完整锁排列、core最后beforeCommit、scope完整成功read、持久FAILED-read、独立afterCompletion／REQUIRES_NEW后记、35表＋只读receipt、B2其余与最终61／62均继续OPEN。未运行／恢复／等待CI，不部署、不接真实Key／计费／共享数据服务，不操作daemon／代理／FRP／重置。
