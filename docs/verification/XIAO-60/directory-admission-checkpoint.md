本片只将 `JdbcLedger.directoryAdmission` 迁入完整 `DirectoryAdmissionService`，基线为 `221379be1e8188d0556408a12adc1d6a8a62d251`／tree `06e473e166e4cacd775750a50dffd62d2e5f14ae`。交付源码／tree 见同版 manifest 与 Git 提交；原分支 `agent/agent/7aeccb14d7a9`、PR22 保持草稿。开发自测，不是独审QA或B2总验收。

受测制品：SHA256 `643ef0353e8b6a9fa3d9848074af85705233ed544dd317b4edeceeedc00440cf`，44519535 字节，1552 个生产 entry。本轮只有一份新 JAR、一轮 verify；没有失败后重拼或未完成样本的补跑。准确旧 JAR 为 `73c69b664fdbdd0ccee3c59e3a94d8da3506d5218ed56c5b5dae42e6013e8a45`，44,515,092 字节／1,547 entry。

Service 按原顺序执行 enabled → 一次完整 projection → 惰性 String content_state → PRESENT 判断 → 原历史 snapshot（historicalSnapshot／available）→ requireDirectory。端口构造不读 task／会话／字段；NULL、类型错误和异常原样传播，disabled不读其余内容。原projection、模型资格内部、受理／fenced／responded调用点和次数、幂等重取独立projection/PRESENT、owner及下载未改。未加catch、事务、查询、锁、Mapper/XML、CAS或拒绝；scope403后记与特殊拒绝结算边界不变。

源码审计逐字节核563份其余既有生产Java及418份资源／冻结输入，新增4份生产Java；去除唯一包装方法及Service字段后Ledger其余内容保持。POM、bootstrap、schema22/V1–V22未动。原SQL仍执行，库存172静态／29动态扫描／568binds，迁JDBC点0、SQL消除0，36项表账均OPEN。原调用／SQL／参数化条件和deadline设置未变。

旧新JAR逐生产entry核对：1539个既有entry原字节同、5新增；Ledger及7个嵌套class变化，嵌套class独立解析去除Code.LineNumberTable后其余字节完全相同。当前target/classes逐entry匹配本轮准确JAR；执行前归档的制品摘要与封存JAR一致。

验证：19项本片单测（flag优先／跳读、四种非PRESENT／NULL／类型、每阶段运行时异常和TaskFailure传播、重复调用）＋2项追踪合同＋1项架构＝22项通过；1项真实IT通过，verify退出0，SpotBugs bugs/errors均0。没有重跑不变owner／幂等矩阵、全模型／availability、response六组或冷初始化实验。

私有paid=false真实目录任务，旧新各13项RESULT／13次外层恢复：claim成功、prepare/fenced成功、responded成功及禁用拒绝、prepare禁用、disabled＋NULL task、实际DELETE projection缺行、binding摘要错误、符合原CHECK的PURGING、历史snapshot缺失／坏摘要／坏JSON及available拒绝。坏JSON原IllegalArgumentException保留，不翻译为TaskFailure。PURGING同时置NULL cwd和合法cleanup_generation，不再制造CHECK无效版本行；不执行文件系统／工具或provider。公开HTTP只用于新侧私有setup／受理，不宣称成对HTTP或worker原串。

离线核13组原SQL顺序及完整actor／installation锁与projection绑定；其余生成ID等完整bind是否相同按每样本明列，未泛化全部绑定相同。6份成功证明原串、SHA256、字节长、来源及可靠事件列保留；仅按已证明directory/task值与seq/type/status/code/artifact_id比较，原时间／生成ID／Map.of字节差异保留。两笔已知拒绝均在内层outcome之后、外层rollback之前核到dispatch RESPONDED／DIRECTORY_FEATURE_DISABLED、MODEL SETTLED实计17及model.rejected；每笔4条证明SELECT与生产调用单列。

26笔恢复只证明tenant-a二十张捕获表（含task／task_directory）的外层回滚事实相等；前快照在内存，完整before/after字符串随后原样保存。这不是整库／持久提交／FAILED-read／独立后记证明。1份完整清理回执：子进程收回、容器和私有根目录移除、fixture停止、failures空。无新增SQL故障，无自然08S01、失效连接或UNKNOWN恢复／重放。content_state NULL／非String及服务端异常的不可表达值仍仅单测，真实缺行只覆盖本片实际DELETE。

本轮产品／测试无失败。准备阶段PowerShell读取语法和wrapper路径失败未执行测试，完整stderr未另封存；原终端回执限制、空格式化日志和说明保留，不补造。坏JSON等预期生产拒绝的完整主异常／cause／suppressed／SQLState/vendor（有则记录）与rollback／证明／落盘异常分角色保存。执行source/class、编译回执、JUnit／SpotBugs、原日志、cleanup先封存后离线分析，无后续产品修改。

本片完成，B2／B0–B5未完；下一具体候选为`directoryAvailable`完整发现资格Service及惰性model／descriptor／原两列安装查询链（原查询无enabled条件），待产品经理按准确head派发，不在本片混迁。XIAO-60保持in_progress，PR草稿；最终61/62仍backlog，T48及新功能暂停。

35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read、独立后记、其他真实缺行、自然08S01／709ms／冷路径锁排列和全部历史失败／缺件继续OPEN。63/64/66/70不重接，72不追新head；未运行／启用／等待CI，未合master／部署／真实Key计费／操作共享服务数据／重置daemon、代理或FRP。

本次run `01a17345-2389-7a4f-aa7a-29ff90ebdeac`；PM唯一规则 `01a17346-4d25-7182-9a5f-e615e78faa82`，filter_task_id准确、once、completed/failed/cancelled三终态、7200秒、on_timeout=wake；未创建重复规则或恢复旧规则。远端准确head/tree与PR草稿状态的独立开发回读见manifest，不能替代PM独立核验。
