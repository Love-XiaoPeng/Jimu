本片只将`JdbcLedger.directoryAvailable`迁入完整`DirectoryAvailabilityService`，基线为`1aac3bbb4387ba729b592c5c9f7c22a5983027fb`／tree`fde797515b59b4c911794600426ddd9c1180ba7a`。准确交付源码/tree见同版manifest与Git提交；沿原分支`agent/agent/7aeccb14d7a9`、PR22草稿。仅开发自测，不是独审QA或B2总验收。

本轮准确受测JAR：`b771316eadd317749cae38319036f96904c6a67c8b12299d6eac2f3a2b4a6d27`，44528016字节／1559生产entry；一份新JAR，一轮verify。准确旧JAR为`643ef0353e8b6a9fa3d9848074af85705233ed544dd317b4edeceeedc00440cf`，44,519,535字节／1,552entry。没有以重拼JAR替代原制品。

Service完整承载flag在try外→selected(identity.tenant)→requireDirectory→两列安装查询→空行原404/NOT_FOUND转false／否则首行→第一次descriptor/version→成功才第二次descriptor/digest。只catch原try内TaskFailure；flag的TaskFailure及其他运行时异常不吞。首行端口只惰性String cast，两次descriptor不缓存，NULL接收者仍先求原参数，短路保持。构造只保存引用，不取会话、字段或模型；Service无适配器/MyBatis依赖。

一个旧JDBC调用点迁为现有InstallationMapper.selectMaps真实MP：仅plugin_version/plugin_digest，完整tenant与固定working_directory键；原固定plugin字面值变第二个bind及WHERE括号是实际SQL差异。没有enabled条件／FOR SHARE／ORDER BY／LIMIT／JOIN／distinct／额外字段或查询。原one首行／空行合同由Service保留，未用selectOne强制单行；没有增加SQL／事务／CAS／双写／拒绝或修改socket/network/事务设置。查询仍执行，SQL消除0。

TaskController认证→TaskService→Tasks→Ledger.capabilities→既有TaskCapabilitiesService的tx→identity→enabled清单排序→stream顺序未动；仅working_directory候选到达原filter时调用本Service。原其他gate／重复selected、directoryAccess/Admission/Projection、owner／幂等／响应／scope后记及特殊拒绝结算均未改。catalog保留nullable query，无新增session queryView或两秒事务；其余入口原截止/实计/锁边界不变。

逐字节核567份其余既有生产Java、418份资源／冻结输入保持，新增4份生产Java；Ledger去除唯一包装方法和Service字段后其余内容同版。POM/bootstrap/schema22/V1–V22不变。库存由172／29／568实计为171静态／29动态扫描／567binds；迁1个JDBC点但SQL实删0，36项表账仍OPEN。旧新JAR逐entry核1544既有entry原字节同、7新增；Ledger及7嵌套class变化，后者仅Code.LineNumberTable不同；target/classes匹配该准确JAR。

一次verify：33项本片单测（flag外try、跳读、模型→查询→descriptor顺序、空行/多行首行、两次descriptor与version短路、NULL/String类型及接收者参数顺序、TaskFailure边界和其他异常传播）＋2追踪合同＋1架构共36项过，1真实IT过，退出0；SpotBugs bugs/errors均0。没有重跑不变owner／幂等／响应六组、旧availability全矩阵、所有模型、冷初始化或自然故障等待。

私有paid=false目录夹具，旧新各12项RESULT完成：直接成功、禁用加NULL Identity、tenant-b、disabled安装仍true、公开enabled清单排除disabled、实际DELETE安装缺行、版本/摘要失配、公开摘要失配、原模型require拒绝、身份拒绝先于list，以及正常公开Ledger catalog。模型拒绝复用已知原缓存快照，再临时env paid原串改not-false，使原requireDirectory拒绝，模型对象仍paid=false；无真实Key或计费。直接资格无身份锁；catalog身份拒绝保留原403并无清单SQL。

12组原SQL顺序逐条核：两列目录查询仅固定plugin字面量→bind/括号改变，完整tenant/plugin值同；其余SQL文本和记录的全部bind值相同，不扩大成JDBC线路字节证明。直接disabled/null和模型拒绝各0SQL；公开disabled清单无目录查询。直接disabled安装仍匹配true与公开enabled清单独立过滤分别实证。原RESULT串/摘要/字节长度保留：Boolean及Ledger catalog是driver序列化输出，不冒称worker/provider。

旧新公开HTTP各5项，合计10响应：正常tenant-a/b、expired、安装disabled、摘要错误。8个200响应逐原串/摘要/长度/Content-Type相同；2个401仅比status/code，原串各自保留。未新增任务或工具/provider/worker执行，HTTP只/api/capabilities与私有setup，未改变公开签名、Controller或其他gate。

24笔tenant-a十九表捕获事实外层恢复，task表为空；tenant-b仅另核输出/SQL绑定，不宣称其快照。前快照原在内存，完整before/after字符串事后落盘；不当整库／任务回滚／持久提交／FAILED-read／独立后记。1份完整清理：所有子进程退出、容器/私有根目录移除、fixture停止、failures空。多行／NULL／非常规类型等不可表达schema值仍仅单测；实际缺行仅本片真实DELETE。无新SQL注入故障、自然08S01／失效连接或UNKNOWN恢复／重放证明。

本轮产品／测试无失败；原执行source/class、编译回执、原JUnit/HTTP/SQL/主异常及清理日志先封存再分析。主错cause/suppressed/next SQLException/SQLState/vendor按有则记录，rollback／cleanup／证据落盘异常分角色、不遮主错。准备期只读路径和未知CLI帮助命令错误的完整stderr未独立封存，终端回执限制另存说明；不补造原件，不计测试。全部历史失败/缺件继续OPEN。

本片完成，B2／B0–B5未完。下一具体候选`todoAvailable`完整发现资格Service：保留原flag外try、selected/require、无enabled的两列安装首行、两次descriptor及后续businessPolicy→scopeRequirement→evaluationRunId短路，待产品经理按准确head派发，本片未混迁。60继续in_progress、PR草稿；B2完整后才核B3，B0–B5稳定后才锁制品/影响矩阵激活最终61/62（当前backlog）；T48和新功能暂停。

35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read、独立后记、其他真实缺行、自然08S01/709ms/冷路径锁排列与全部历史缺口保持OPEN。63/64/66/70不重接，72不追新head；未CI/合master/部署/真实Key计费/操作共享服务数据/重置daemon或代理FRP。

准确run`01a1735e-b22c-7931-af9a-2457e06d82d2`，唯一规则`01a1735f-d198-7c1c-91fa-ee4d32d44879`已核：准确filter_task_id、once、completed/failed/cancelled、7200秒、on_timeout=wake。未重复创建或恢复旧规则。远端开发回读记录见manifest，不替代PM独立核验。
