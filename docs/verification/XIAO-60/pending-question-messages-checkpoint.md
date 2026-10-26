本片仅迁 `JdbcLedger.pendingQuestionMessages` 完整公开入口。准确parent `b115ec3933108b99f654c65c767b87b8f85c6ee4`／tree `acfb26ac7cb07c6c2a0aca700737f72b85a87170`；新SHA/tree由交付manifest及Git原对象固定。原分支 `agent/agent/7aeccb14d7a9`／PR22 draft；仅B2部分检查点，60继续in_progress。

受测原JAR来自run2，SHA256 `3e82a4ec6e55789b5fecebff6299c7114ab699e4969271a0a1b19f6e05a7c96f`／44,681,659字节／1,695生产entry，四次driver执行清单均指向该原品。旧品为parent实际受测maintenance run3原JAR `b3495f3b602d4808effa1efce48042166b7dbb5b47c724afaa44914e5e078462`／44,677,596字节／1,691 entry。直接读全部旧新entry：1,685既有原字节同、4新增；5个既有Ledger嵌套class仅Code.LineNumberTable变化。675其他生产Java／433既有资源原字节同。schema22/V1–V22、Controller/UserQuestionLedger、DurableAgent/Host、leaseTx/fenced、QuestionMessages及其Mapper/codec/Input、questionContinuation、prepare消费、Bean/bootstrap/POM与全部其他Ledger方法冻结。

纯Java接口/Service/impl拥有原leaseTx→一次完整fenced(同Lease,true)→一次完整questionMessages(同返回task Map)→Map.values→stream→toList→原leaseTx返回。专属类型化port只委托三个单项操作，transaction执行Service提供的窄回调，values/stream/toList均由Service在回调内执行。构造仅保存Ledger引用，无Lease成员/Map字段/会话/flag/时钟预读，无复制、缓存、guard、catch、fallback、重试、新事务或补偿。NULL Lease仍首先进入原leaseTx及directoryTransaction；NULL messages Map在values原到达处NPE。TaskFailure/RuntimeException/Error保留原实例与阻断边界。

返回严格保留values().stream().toList()：本次Map values encounter order、重复原值及合成NULL、不可修改List；不遍历keys/entries、不排序去重、不重编码，也不承诺另一进程Map.of键序。fenced失败不调Messages；成功才把同task引用交给既迁Service。Messages内部完整questionTask、pending JOIN、逐行digest/Input/answer codec和encode复用一次。新入口无questionNow或新SQL。底层原数据库时间/锁/commitLiveFence仍执行，不能将整个公开入口称无写/0SQL。

UserQuestionLedger原签名/返回形状及DurableAgent replay资格、原调用位置、按role/content去重、WireMessage/history构造不变。返回不等消息消费；仅新model prepare中的原子消费实现按冻结源码分证，本片测量不调用prepare-consume/worker/provider，不更新delivery、写消息事件、模型预留或调用工具。leaseTx AdmissionResult内外双层、directoryTransaction及EVALUATION_MATERIAL_MISMATCH特殊结算/tx外原实例抛出均原源码冻结，无新增故障注入或跨域重跑。

原0个旧JDBC点，本片迁0／SQL消除0，实盘库存仍138静态／29动态／459bind，35表＋receipt保持OPEN，没有新增Mapper/XML或扩大投影门禁。其他已迁Service不混迁。

执行run1在Checkstyle阶段因测试4处通配符import失败，未到产品编译、单测、package、IT或SpotBugs，27项原日志/源码封存；失败轮旧target class不归属受测产品。修正显式import，并在任何driver执行前修正助手kind字段名与INSERT占位数；未执行首版助手及原源码保留。首版封存器缺父目录失败及准备Python一行引号调用失败亦留原回执，随后建立专属封存目录，无产品执行被重试。

run2定向verify退出0/BUILD SUCCESS：5Service＋1port＋2追踪＋1架构＋1真实IT共10测试通过、无skip，SpotBugs bugs/errors0；两种样本各旧新driver0，共4个前台driver调用。18份生产class／3份执行driver class与实际编译件/受测原JAR及Git匹配，143项封存。没有重跑整Messages、问答、retention、response、模型或session矩阵。测试与产品本轮没有正式执行失败或第二次真实重跑。

两对私有paid=false公开pendingQuestionMessages：合法timed0.2.0已存两条ANSWERED/PENDING，ACTIVE attempt＋RUNNING task/current_attempt/fence/worker/lease_until真实核对后通过原fenced，每侧14SQL；合法web_fetch非question独立ACTIVE前置，每侧12SQL，返回空List且无pending JOIN。每对各在独立私有实例/克隆库中执行，非PAUSED Lease。每次原evaluation/receipt存在性/scope→bucket→capabilities/task锁→模型拒绝检查/scope→actor/installation→commitLiveFence链实际采集。锁行live/within_deadline真实getter均1，不省略原fence写；非空fence位于SQL序号12、JOIN13，非question fence11且此后不查pending。

两条消息按本侧accepted_seq=1/2返回（特意与ordinal顺序相反），callId为read_2/read_1；原UTF8消息各259字节、原字符串/hash及完整List封存，并核回原问题/答案/provider。JOIN完整34列、两行/每侧，case/列顺序/类型/NULL逐列与本侧夹具核过，NULL数字typed getter0/wasNull还原。原SELECT实串/两String bind、getter/API/元数据与底层其余SQL/计量/会话均逐侧保留。该Mapper在parent已迁，本片旧新实际API形状相同：PreparedStatement.execute/getResultSet/标签typed getter；fence原execute/getUpdateCount及setters保持。普通Statement与prepared均采，测量产品实际全prepared；不能把早期JDBC迁移差异算成本片变化，SQL故障映射未新验。

每次仅原commitLiveFence一笔写，task revision+1及数据库updated_at变化；问题所有字段和PENDING delivery、invocation、reservation、attempt、budget及event前后不变。无独立questionNow查询，数据库时钟由原锁谓词/live/within_deadline及原fence更新时间体现，未冻结或重放时钟。时间/生成ID/attempt及Map.of仅核各侧已证关系；相同已存问题ID/文本产生的两条返回原串本次旧新相等，非对跨样本生成值的承诺。

双call响应/intent/invocation、timed pin/binding、pending output/TOOL SETTLED及显式答案/requestDigest/receipt/时间字段均为schema相容的setup事实，非Host工具/provider或自然问答事件生成。记录了setup初始prepare/dispatch/modelResponded与显式SQL，未调用外部模型/工具；没有把setup初始prepare当成本片prepare消费验证。每次入口真实ACTIVE；registerWorker/claim仅setup、不启worker循环。HTTP仅新侧任务setup，非HTTP/Host认证验收。显式存储时间字段保留原值，不将其冒称自然事件生成时间链。

4笔tenant-a21表外层前后事实恢复、4份ACTIVE证明、4份fixture/after证明及两个容器清理回执核过；worker_process未纳入该21表事实恢复范围（容器整体已清理）。所有样本加入显式外层rollback，生产leaseTx边界源码与实测持久提交分列。主错cause/suppressed/next SQLException/SQLState/vendor、rollback/cleanup/落盘错误通道保留；本轮无产品UNKNOWN/SQL故障、DELETE/非法schema行、sleep或网络/时间配置调整。

离线控制核验器首版误把bucket当成第一SQL、误期3个权限锁而实际2个共享锁＋question安装MP读取、误把pending output当无message字段的两键对象；首版脚本/stdio/exit保留，按原日志修正后通过，只离线复算，不改产品或执行证据、不重跑Java/DB。包内核验器可逐项验证ZIP哈希/Git原对象、执行源码/class/原JAR，并依准确旧品逐字节还原本次受测原JAR，非重新打包。

仅开发自测及离线复算，非独审QA/总验收/持久提交/整库恢复/FAILED-read/独立afterCompletion或REQUIRES_NEW后记/scope完整成功工具read。tenant/whole-scope/fence/权限、同DS/UoW、queryView起点/累计实计/绝对两秒/末尾deadline、baseAccess四资格/question专属else/MCP、access原catch、scope403去重、commitFence13处/session边界冻结；本轮公开Ledger为原nullable query=NULL，不冒称queryView新验收。

B2继续。下一准确窄片为questionContinuation的完整leaseTx→fenced→question_continuation_step_id非NULL判断公开包装（自身0旧JDBC），本片未迁。61/62backlog；63/64/66/70不重接，72固定旧head；T48/新功能暂停。35表＋receipt、core最后beforeCommit、scope成功工具read、持久FAILED-read/独立后记、其他真实缺行、自然08S01/709ms/冷路径/历史准备缺件均OPEN。不CI/合master/部署/付费/共享数据服务/daemon代理FRP维护。
本任务依最新完成条件：必要审查与验证通过后，由项目既定集成负责人合入原约定目标交付分支、推送并核实远端包含代码后再标记完成。本片仍为B2部分开发检查点，PR22未合并，不能以附件/本地提交或请求代替集成完成。唯一结果接收人为本任务产品经理，继续沿原项目分支/权限；项目专属安排仅记录于本报告/任务，不写通用指引或角色提示词。
