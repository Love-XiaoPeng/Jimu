# REVIEW-75-01 显式投影修复检查点

输入为已接入73的 `88e06549123b8513bfa7dfee96e10339729782a1`，tree `9772e2b554e3d25dc94dd96bea0dc232f5443136`。本检查点只修复75报告的五条查询；准确修复 SHA/tree 随事项交付评论登记。

## 生产差异

- `QuestionAnswerMapper.lockQuestion/previous/reload`、`QuestionMaintenanceMapper.due`：按 schema22 原序列出 `user_question` 全部33列。
- `QuestionOpeningMapper.invocation`：按 schema22 原序列出 `invocation` 全部27列，包含V17追加的6列。
- 直接列名保持原标签和JDBC类型，无表达式、别名、类型转换或NULL默认值。投影外SQL、参数名/顺序、tenant/全键条件、FOR UPDATE、更新和计量实现不变，DDL和冻结资源不变。
- 删除 `PersistencePathTrackingTest` 中上述五条通配符例外。重新运行现有 `capture-paths.cjs`，使源码摘要和真实Mapper清单反映当前集成分支；其中源码摘要也纳入此前68/74及73接收结果，未再修改这些生产文件。清单中的SQL变化恰为本片五条。
- 同步三个既有数据库对照driver的期望。`question_projection_sql.py` 只为五条精确旧语句展开既有schema22清单；旧侧期望保持原SQL，新侧要求显式列，其他SQL逐字比较。未重跑这些数据库driver，不将其维护记作数据库验证通过。

完整列序对照使用既有 `evidence/b2-invocation-removed-sql.json` 中的schema22列证据；V1/V16/V17源码已人工对照，未重写历史列清单。

## 必要开发验证

Windows Temurin21.0.12.1，Maven Wrapper3.9.9；原始日志/XML随本次事项附件交付。

- `.\mvnw.cmd -B -ntp spotless:apply`：exit0。
- `.\mvnw.cmd -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/xiao60/classpath.txt'` 后 `node docs/verification/XIAO-60/capture-paths.cjs`：exit0，153个基线入口、36表保持OPEN。首次capture因新工作树缺少上述classpath文件exit1，补齐后成功，未改追踪器。
- `.\mvnw.cmd -B -ntp test '-Dtest=QuestionProjectionTest,QuestionAnswerPortTest,QuestionOpeningPortTest,QuestionMaintenancePortTest,QuestionAnswerServiceTest,QuestionOpeningServiceTest,QuestionMaintenanceServiceTest,PersistencePathTrackingTest,B2MapperContractsTest,PackageBoundariesTest,RefactoringNegativeGatesTest'`：exit0，45项，失败/错误/跳过均0；含Enforcer、Spotless、Checkstyle和实际编译。
- 五个实际加载的MappedStatement与完整列序比较；原有三个Port测试保留全部13条SQL、参数顺序、NULL参数及显式/空query context断言。新增测试经真实MyBatis参数处理器验证五条查询的非空/空参数setter及次序，结果处理器读取合成JDBC CachedRowSet，验证LinkedHashMap完整键序、String/Integer/Long/Timestamp/NULL，以及一次结果的全列字节/操作计量。这不等于真实MySQL执行。
- 新测试前两轮分别因JDK CachedRowSet不支持`isClosed`、测试Statement替身缺少`getConnection`失败；仅补齐替身生命周期/单结果集元数据，未修改生产代码。失败日志/XML保留；最终上述45项全通过。
- WSL Python3解析四份改动脚本AST，并验证五条精确映射只替换投影、旧SQL和无关SQL不变：exit0。未启动服务或数据库。
- `git diff --check`：exit0。

未运行GitHub CI、真实付费请求、部署、MySQL或旧完整矩阵；未重新执行SpotBugs。73已先单独提交/推送，不因75差异复核撤回。此处为开发修复交付，75是否关闭由PM安排原报告者差异复核；PR22继续draft，未合master，整体重构未完成。
