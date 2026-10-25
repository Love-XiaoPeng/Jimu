交产品经理：按成员追加并行授权，在 owner 历史投影安全检查点 `bd8dce2e999f1723898ac76f2a82990c20fde6e0` 之后，分别接入 XIAO-63 和 XIAO-64，仍由中级A唯一集成原分支／草稿PR22。仅开发自测与集成核对，不是独审、QA或全仓重构验收。

| 切片 | 支线原SHA | 本线cherry-pick SHA |
| --- | --- | --- |
| XIAO-63 宿主工具网关 | `8257f99ad0221c02fe73292759f76f838728b763` | `b1067578da358492c0f784a84635fca8736f0759` |
| XIAO-64 readiness/plugin实现整理 | `4d0c67f30624a48c0853eca4a988eb7138b0e5ce` | `f4245d010921ce58696035ccbd83306eb22ac35a` |

两片共同基线 efba6c732f23634755402438f7d275502d8526a3，独立checkout开发、原生派工；收到各自可用SHA后顺次集成，无重复派工。网关先单独编译／单测，再接readiness。上游交付bundle及原验证ZIP另封存，未改写上游证据：63原verify缺JIMU_IT_JAVA、旧V15/V16制品未在该工作区具备；64原142项宽测试中4项未改存储测试的Windows POSIX失败仍保留。本线仅补必要受影响验证，不据此关闭全部上游限制。

DurableAgent九个原方法体，在仅显式传递lease/stepId的必要替换后token一致，七类工具分派保持；未改模型请求、快照、slots或共享接口。readiness/plugin沿原接口迁实现与局部VO，RuntimeConfiguration仅两类import/new接线。两片改动blob／删除与上游逐项相同；owner检查点之后706份持久化/session/资源与冻结输入文件不变，集成JAR内956份对应class及非class资源逐字节等于owner受测JAR。413份资源／冻结输入、schema22/V1–V22、POM及持久化业务不变；SQL实删0，库存182／26／587。

受测集成JAR SHA256 `c9fa697028f54f9c9bd51c5fa68061811fa0874af3f54752f6b3d63a593335d4`，44,425,290字节、1,467个生产编译／资源entry。两轮复用这一不可变制品，末轮重新打包文件不冒充受测JAR。四份已移动旧class在打包前定点清除，受测JAR确认不存在旧类。owner独立受测JAR仍为 `58d42f5467c10fd80490a21baf4242b774bbf39dba7fd05ad3b1018f0077290d`，44,420,868字节、1,466 entry；其93项单测／契约＋1项owner集成证据单列，未将全体owner旧新试验在集成JAR上重复执行。

集成首轮35项单测／契约及3项RuntimeIT通过；ParallelGatewayIT失败，verify退出1。真实付费关闭的私有model→DurableAgent→网关→原生session.read已提交首个原槽，夹具误期待slot键0/1，原存储为0:slot_0/1:slot_1，失败原因是该预期，生产未修。第二轮只修夹具键，重跑5项HostInvocationGatewayTest＋1项ParallelGatewayIT，verify退出0，未重复3项RuntimeIT或其余30项；两轮SpotBugs bugs/errors均0。每轮执行源码／class／supervisor／编译与原始日志先封存，两个网关容器完整清理成功，RuntimeIT的新鲜运行日志另存。

末轮实际worker仅核首槽SUCCEEDED一次、第二槽PENDING、模拟provider请求1次及owner HTTP历史包含session.read。采样task为RUNNING；原SESSION_COMMIT_AFTER暂停点之后按夹具清理，不代表第二槽、全任务或后续模型派发成功。该路径有真实首槽持久提交，区别于owner差异试验的外层回滚；不扩称scope三调用、持久FAILED-read、独立afterCompletion后记或UNKNOWN重放验证。

源码追踪门禁先两次失败于移动Controller／VO的映射，不进入Maven产品试验；补两个准确类名alias，并将实际javac生成的独立VO构造器纳入符号字典，未伪造调用边或提升为SQL入口，未映射仍fatal。153个基线入口均映射，库存不变。初次门禁完整stderr只在工具转录中，附件仅保存诊断摘记和实际stdout，不伪造缺失原件。此前owner private可见性编译失败、scope expired预期失败及本轮slot键失败均单列。

附件用owner JAR的11个4MiB分片及集成raw ZIP差量交付两份准确制品：先按manifest拼接owner JAR，再依parallel archive的integration/delta/plan.json复制原ZIP记录与新记录，不解压重压。1,965条记录中776条复用，新增payload 1,714,476字节；独立核验脚本检查两个完整JAR摘要、entry摘要、封存证据与所有附件索引，并可写出复原JAR。准确最终source/tree及远端回读以manifest为准。

完成owner切片与两条支线必要集成，下一具体B2候选仍为JdbcSessionRecords.metadata/scalarBytes/account固定点账目Service／类型化端口，交产品经理排续。60保持in_progress、PR草稿；response诊断、真实account08S01／709ms剩余区间、scope完整成功read、core最后beforeCommit、35表＋只读receipt、持久FAILED-read、独立后记、冷锁／完整锁排列及全部历史失败／缺件继续OPEN。无新增成员／外部授权阻塞；未运行或等待CI、未部署、接真实Key／计费／共享服务数据或操作daemon／代理／FRP／重置，复用本准确run已有三终态＋两小时规则。
