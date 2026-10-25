本片仅迁 `JdbcLedger.questionInput` 的完整版本／binding／request 摘要资格，parent `a6248f67c769f6a61e5f8dfdca242f2cebac6f0d`／tree `3573fe5cc98151fe4b5c27206505f542ae06d197`，沿原 A 分支与草稿 PR22。准确提交 SHA/tree、受测原 JAR 与执行源由交付 manifest、Git 原对象和 bundle 绑定。本片是 B2 部分检查点，非 B2 完成、独审 QA 或总验收。

受测原 JAR `a53263c652a8e843398aed8c48428a94ca4d977c14443bbf3638386d51334af4`，44,590,550 字节／1,621 生产 entry。直接读取准确旧 37eedf2f 与新原 JAR 全部 1,617／1,621 entry：1,609 原字节同／4 新增 class；Ledger 外七个既有嵌套 class 独立解析，仅 LineNumberTable 变化。无新增资源、Mapper 或 SQL。

## 完整规则与惰性接线

纯 Java `QuestionInputService`／impl 与类型化 `QuestionInput<P,R>` 端口承载四项 OR、拒绝及历史 codec 返回编排；`LedgerQuestionInput` 构造只保存 ledger／q／task 引用。内核不依赖 adapters、MyBatis 或具体插件。没有新 guard、flag、PURGED 判断、catch、fallback、事务、缓存或提前校验。

原顺序保持 record_version Number.longValue → question_version Number.longValue → q.binding_digest 接收者 → task.question_binding_digest 参数 → equals → 第一次 request_snapshot → 原 Payloads.hash → request_digest → equals。前项拒绝不读后项；Number 截断、NULL／String cast 错误仍原传播。binding 接收者 NULL 时仍先求 task 参数，其异常可先于原 NPE。

四项通过后才调用原 tools(task) 一次，复用缺失→摘要→codec/schema→pin 键集合／task epoch 的既有链，再 get 固定 ask_user_question pin → version → 独立第二次 request_snapshot → 原 questions.input(version,raw)。不缓存第一次 raw，不合并 access 的 tools 调用。pin 缺失仍在 version 成员到达时 NPE，尚未第二读 raw。codec 返回原 Input 实例，RuntimeException／Error／TaskFailure 不统一翻译；历史版本处理及 catch 范围未迁移。

四处调用方及其前后代码词法不变：非空 listQuestions 的固定 view 到 questions 位置；answer 幂等原 TaskFailure-only 到 IDEMPOTENCY_CONFLICT；新 answer 原 AdmissionResult catch 与事务外拒绝；questionMessages 的 answer 摘要、LateQuestionReply 参数及后续 answer JSON 读取顺序。未迁 visible/access/storage、QuestionContracts、openQuestion/answer 编排、retention 或其他模块。

保护 613 份其他生产 Java／423 份既有资源及冻结文件；schema22/V1–V22、Bean/bootstrap/POM、已迁 Service、todoProjection 完整 21 列／case／NULL、数据库时间和原锁序不变。原方法无 SQL，本片 JDBC 迁移 0／SQL 消除 0，库存静态 163／动态 29／bind 549。无新增 getter/setter、SQL 执行 API、bind 类型或 SQL 文本差异；有界真实链中原 SQL 序列与完整 actor／installation 锁绑定另证，生成 ID 绑定各存本侧原件，不泛化全部 bind 相同。

## 本轮执行与封存

先核准确 parent 的 UserQuestionContractTest、UserQuestionIT／user_question_verify.py，既有 retention、answer 幂等、late reply 与 baseAccess 覆盖。这些仅作未改历史来源，不当当前新 head 全矩阵验收；未重跑完整问答／模型／response／session 或 CI。

本轮一次完整定向 verify：7 Service＋5 port＋2 追踪＋1 架构＋1 真实 IT 全过、BUILD SUCCESS／命令 0，SpotBugs bugs/errors 均 0，无 skip。Service／port 补构造惰性、四 OR／首错、Number／NULL／cast、binding 接收者与参数先后、hash→digest、两次可变化 raw、tools/pin→version→第二 raw→codec、原返回实例及每一到达点 RuntimeException／Error 传播。另小量实际历史 codec 接线验证 0.1.0／0.2.0、未知版本／坏 schema／NULL，不重开旧 codec 全矩阵。

实际命令为 Windows Maven Wrapper `verify -Dtest=QuestionInputServiceTest,QuestionInputPortTest,PersistencePathTrackingTest,PackageBoundariesTest -Dit.test=B2QuestionInputIT -Djimu.question.input.evidence=b2-question-input-run1`，先 spotless:apply 再捕获／review 索引。102 项逐轮原件已封存：执行 source／driver／助手、编译 maven-status／class、原 Maven stdout/stderr／exit、JUnit／SpotBugs、实际旧新 JAR 绑定及清理。单测的 NULL／类型／变化回调不冒充数据库行。

本片没有测试、产品或夹具执行失败。准备阶段首次 issue get 超时后只重读；技能文件／辅助文件错误相对路径、一次 wakeup get 少 issue 参数的 CLI 用法错误均由只读准备纠正。部分准备工具 stderr 仅保留原工具回执，未独立完整封存；既有历史缺件继续 OPEN。不会把准备错误写成产品成功或关闭历史失败。

## 有界真实链及限制

私有 MySQL8、paid=false，新侧 HTTP 仅受理合法 0.2.0 question 任务；旧新试验前复制同一库。各试验在本侧外层事务内 registerWorker／claim 构造 Lease、prepare／dispatch／fixture modelResponded 得到关联 attempt／step／invocation。随后显式 SQL 构造已存 timed CONTINUED 问题及 pending 成功 invocation／settled TOOL reservation，原 task pin=0.2.0、原 binding.mode=timed、q.mode=timed、request timeout=120，FK/CHECK 合法且 binding／request 摘要匹配。

这些是明确的已存 SQL／模型响应夹具，不是 openQuestion、工具、provider 或真实问答事件产生证明。未调用 worker 循环、provider、新工具或付费入口。没有套用上一片 blocking 行对 timed binding；没有新 DELETE、非法版本行、业务 SQL 故障、自然08S01、UNKNOWN 恢复或冷路径证明。

六对实际结果：非空 owner listQuestions 真正到本片；request 摘要坏在本片 SNAPSHOT_CORRUPT；正确摘要的 `{}` 在原 codec TOOL_INPUT_INVALID；新 answer 接收为 USER_MESSAGE；同 key／command 幂等重取原 receipt；已有 PENDING answer 的 questionMessages 生成一份 late reply。四种成功样本覆盖四处实际接线；负样本只声称列表首拒绝，没有将其称 answer catch 的新运行覆盖。

新 answer／幂等／late 消费均使用原公开 Ledger 或既有 package 入口；late-messages 是 questionMessages 直接调用，未声称 pendingQuestionMessages 的额外 fenced 公共链也新跑。answer 的本轮内层结果／事件／receipt 随本侧证明保存，外层随即回滚，不称持久提交、FAILED-read 或独立后记。

32 份原 Lease／变更后夹具／成功证明分别保留原串、SHA256 与 UTF8 长度；包括 12 份实际夹具证明和 8 份成功结果证明。列表字段、question/request/binding 摘要、关联 task/invocation、answerId 与本侧 receipt 的关系、late reply questions／answers 分别核验。原生成 ID、时间、Map.of 序列化各存，只比较已证字段，不称 wire 字节全同。

12 次外层恢复仅核 tenant-a 二十一表的实际捕获 before/after，worker_process 未纳入，不称整库恢复。私有实例与所有子进程清理另有完整回执。没有改变 queryView／累计实计／全程两秒或调用者末尾 deadline，没有修改 socket/network／事务设置；夹具只延用原 180 秒任务窗口并外层恢复。

## 剩余及下一具体片

本片完成不等 B2／B0–B5 完成。下一具体候选 `JdbcLedger.questionVisible` 完整 identity／owner 三键可见资格及原 locked→access 链，需 PM 核准确源码、覆盖与写入空闲后续派；本轮未混迁。60 保持 in_progress／PR22 草稿，61/62 backlog。

35 表＋receipt、core 最后 beforeCommit、scope 完整成功工具 read、持久 FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径及全部历史失败缺件 OPEN；63/64/66/70 不重接，72 固定旧 head。不 CI、合 master、部署、真实 Key 计费、共享服务数据或 daemon／代理／FRP 重置；T48 和新功能暂停。

仅回读唯一当前规则 `01a174cc-c94b-7206-ac2c-6a57f662ca60`，绑定本轮准确 run `01a174cc-0cef-7d0b-84d2-e34ec7b681ee` 的 completed/failed/cancelled、once 及两小时。未重复创建或恢复旧规则。
