# 业务持久化迁移明细

状态及固定基线同 [总方案](README.md)。这是实施设计，下面的Mapper/Entity/Service是目标名称；当前产品仍是JDBC，不能写成迁移完成。

## 1. 版本、依赖和装配决定

| 层 | 基线实解析 / 本次候选 | 决定和证据 |
| --- | --- | --- |
| 工具链 | Eclipse Temurin `21.0.12.1`，Maven Wrapper `3.9.9` | 沿用JDK21/release21，不启用preview；Enforcer范围不放宽 |
| Boot / Spring | Boot `4.1.1`，Framework `7.0.9` | 保持父POM；本次不切Boot3，不混javax.servlet/Jakarta |
| AI | Spring AI BOM、model、client-chat均 `2.0.1` | 保持唯一AI链和现有工具advisor |
| MP | **新增 `com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17`** | 官方安装页明确Boot4从3.5.13支持；Maven Central stable release为3.5.17。不用Boot2/3 starter，不另加mybatis-spring-boot-starter |
| MyBatis / Spring桥 | 候选解析 `mybatis:3.5.19`、`mybatis-spring:4.0.0` | 由MP starter传递，不手工漂移版本；MP starter自身BOM用Boot4.0.1，最终由本项目parent管理为4.1.1 |
| DB / 驱动 | 现有私有夹具钉MySQL `8.0.46` / InnoDB；Connector/J `9.7.0` | 本次不升级数据库，不把该旧服务器钉版本解释为已独立证明长期维护状态 |
| 迁移 | Flyway core/mysql `12.4.0`，schema精确22 | 原V1–V22不改；本次结构迁移推荐无DDL |
| JSON | Jackson2 `2.21.7` + annotations `2.21`；Jackson3 `3.1.7` | 两条JSON依赖在基线真实存在：冻结快照/严格协议用2、MVC/AI/validator用3；不借MP升级合并序列化器 |
| JDBC/连接池 | MP starter传递spring-boot-starter-jdbc和HikariCP `7.0.2` | 替换显式spring-jdbc依赖；**继续显式DriverManagerDataSource**及有界wrapper，不同时切池化 |

官方URL、抓取时间、正文SHA256和摘录在 `docs/verification/XIAO-59/evidence/official-sources.json`。基线/candidate依赖树、effective-POM摘要及隔离JVM装配证据在同目录。候选解析和原Enforcer四项均通过；Java21中实际启动了无DB与有DS/MP两个非web上下文，验证复合键Entity可注入insert/selectList而不会生成selectById。**这只证明依赖/最小装配，尚未证明MySQL、事务、插件或业务兼容。** 没有把线上“最新版”文档当精确兼容矩阵。

实施POM最小差异如下，不额外导入第二个Boot BOM：

```xml
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
  <version>3.5.17</version>
</dependency>
```

所有MP组件如以后确需增加，版本统一取一个 `mybatis-plus.version=3.5.17`。当前公开接口没有IPage/offset分页需求，保留已有keyset/cursor，不为迁移先加JSqlParser、分页/租户/乐观锁插件。未来真实需要分页插件时，再配同版本`mybatis-plus-jsqlparser`，设置有限pageSize并重验SQL；不能让total/count或offset扫描突破当前资源预算。

装配步骤：

1. 在bootstrap明确排除Boot的 `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` 自动猜测；只在 `jimu.durable.enabled=true` 创建受信 `ledgerDataSource`，避免默认仅 `/readyz` 的装配因新增starter要求数据库。`CompatibilityProbe`已核此配置形状。
2. `SessionQueryDeadline.source(rawDataSource)` **在唯一位置包装一次**；同一个最终DataSource实例供 `SqlSessionFactory`、`SqlSessionTemplate`、唯一 `DataSourceTransactionManager` 和 `LedgerUnitOfWork` 使用。不能Mapper走池、事务走另一个wrapper，或每个Service new事务管理器。
3. `@MapperScan` 仅扫描批准的 `adapters.persistence.*.mapper`，与durable条件绑定；XML用 `classpath*:mapper/**/*.xml`，mapUnderscoreToCamelCase=true。显式关闭二级缓存 `cacheEnabled=false`，一级缓存 `localCacheScope=STATEMENT`；授权、计量和原槽读不能由缓存跳过。
4. 单一control迁移身份运行Flyway，worker及应用身份只validate/check精确22；禁止auto schema update、Flyway clean/repair和普通账号DDL。全体新旧worker升级前不改数据库。
5. 沿UTC配置 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，保留DATETIME(6)、DB时钟及相同字符集/collation。不把Java默认时区或MP自动fill当DB时间权威。

## 2. 全部表映射和访问方式

每行的Java名前缀对应 `<Name>Entity`、`<Name>Mapper`，放 `adapters/persistence/<模块>/{entity,mapper}`；XML同名位于 `resources/mapper/<模块>/`。默认CRUD仅是BaseMapper.insert及显式列LambdaQueryWrapper/LambdaUpdateWrapper；表中列出的锁/计账/聚合/多表/精确更新由参数化XML执行。Mapper外没有业务SQL字符串。

| 表（首次迁移） | 主键 | 模块 / Java名前缀 | 必需XML语义 |
| --- | --- | --- | --- |
| tenant_budget（V1） | tenant_id | budget / TenantBudget | FOR UPDATE、各held/used计数的带界原子增减 |
| actor_access（V1） | tenant_id,actor_id | identity / ActorAccess | 当前enabled/permission版本、FOR SHARE |
| installation（V1） | tenant_id,plugin_id | plugin / Installation | epoch/version/digest当前核验、FOR SHARE |
| task（V1，V3–V22演进） | tenant_id,id | task / Task | 候选keyset、精确候选SKIP LOCKED、fence/lease/deadline/CAS、终态 |
| task_attempt（V1/V3） | tenant_id,id | execution / TaskAttempt | attempt/fence唯一性、过期与quiescent、关联worker收据 |
| step（V1，后续扩展） | tenant_id,id | execution / Step | task内ordinal、固定request/response原字节、checkpoint和digest |
| invocation（V1，后续扩展） | tenant_id,id | invocation / Invocation | 原step/slot/provider/effect绑定、恢复/UNKNOWN、一次消费 |
| event（V1） | tenant_id,task_id,seq | event / Event | fact_id幂等、next_event_seq分配与事实提交同事务、seq keyset |
| reservation（V1，后续扩展） | tenant_id,id | budget / Reservation | RESERVED/SETTLED/HELD_UNKNOWN/RELEASED等原状态及精确一次释放 |
| artifact（V1/V6） | tenant_id,id | artifact / Artifact | stage/verify/publish fence、不可变快照、到期/中止清理 |
| worker_process（V2/V12） | id | worker / WorkerProcess | host/PID/startTicks/bootId/namespace及停止观测；跨tenant设施记录 |
| evaluation_budget（V4/V7） | tenant_id,id | model / EvaluationBudget | run锁、真实授权阶段和额度、结算；不自动创建付费许可 |
| model_dispatch（V4） | tenant_id,id | model / ModelDispatch | dispatch唯一、预留与actual/usage质量、原request摘要 |
| evaluation_execution_scope（V9） | tenant_id,run_id | model / ExecutionScope | 显式operator bindOnce、原账本证明、插入一次 |
| native_search_run（V11） | tenant_id,root_invocation | search / NativeSearchRun | fencing、observer证明、阶段/存储预留与cursor |
| native_search_stop（V11） | tenant_id,root_invocation,identity_digest | search / NativeSearchStop | 准确进程身份的停止证明，不推断Future.cancel为停止 |
| task_image_input（V13） | tenant_id,input_id | media / ImageInput | 原图/路径归属、预留及原hash/字节 |
| task_image_object（V13） | tenant_id,image_id | media / ImageObject | 归一化/引用、生产fence、清理标记/结算 |
| mcp_server（V15） | tenant_id,server_id | mcp / McpServer | 当前启用/epoch/profile digest与owner授权 |
| mcp_io（V15） | tenant_id,task_id,invocation_id,ordinal | mcp / McpIo | 调用意图/phase、每次IO预算、异常收据 |
| mcp_result（V15） | tenant_id,task_id,root_invocation_id | mcp / McpResult | 固定list/read快照、有限保留和实计 |
| mcp_cursor（V15） | tenant_id,task_id,token_digest | mcp / McpCursor | query/root/page/expiry绑定和opaque token验证 |
| user_question（V16） | tenant_id,id | interaction / UserQuestion | owner先核权、revision/答复幂等/暂停恢复、迟答与清理 |
| task_todo_projection（V17） | tenant_id,task_id | todo / TodoProjection | revision-CAS、counts及原输出、UNWRITTEN/PURGED区别 |
| task_directory（V18） | tenant_id,task_id | directory / TaskDirectory | cwd/revision/namespace/policy、PRESENT/PURGED与workspace状态 |
| invocation_path_binding（V18） | tenant_id,invocation_id | directory / InvocationPathBinding | 接纳槽的固定路径/目录版本、禁止恢复重新解析 |
| task_session_history（V19–22） | tenant_id,task_id | session / SessionHistory | history版本、保留期/内容状态、metadata/BODY/预留固定点 |
| session_public_event（V19–22） | tenant_id,task_id,seq | session / PublicEvent | 原事件字节/hash、read证明、公开类型与seq上界 |
| invocation_session_read（V19/V21） | tenant_id,invocation_id | session / SessionRead | 切点/输入/digest/原输出及原来源证明 |
| invocation_session_search（V20） | tenant_id,invocation_id | session / SessionEventSearch | normalized查询、扫描摘要/计数、失败/成功原槽 |
| invocation_session_trace（V21） | tenant_id,invocation_id | session / SessionEventTrace | 原read来源关系、PENDING/PURGED、不递归扩旧收据 |
| task_session_scope（V22） | tenant_id,caller_task_id | session / SessionScope | 宿主scope、不可逆撤销/失效、最早保留、validation keyset |
| task_session_scope_target（V22） | tenant_id,caller_task_id,ordinal | session / ScopeTarget | 排序来源、上界/pins/digest、清理NULL源ID |
| step_session_scope（V22） | tenant_id,step_id | session / StepScope | 原step/context固定字节，不能按新JVM重序列化 |
| invocation_session_scope_query（V22） | tenant_id,invocation_id | session / ScopeQuery | SEARCH/READ，normalized/read_refs、receipt/fence、一次消费 |

外部 `jimu_provisioning_receipt` 在 `JdbcExecutionScopes.java:31,79` 读取，夹具DDL证据为 `tools/p3_execution_scope_verify.py:128`、`tools/p3_business_verify.py:260`，不是Flyway资产。用 **只读 `ProvisioningReceiptMapper` + XML** 明列9字段；先查表存在、0/1/多行原失败语义不变，不造主键/回填/自动provision。运维 `ExecutionScopeBinding` 和 `WebFetchReconciliation` 同样属于真实业务数据入口，必须迁Mapper，不能借“operator”保留另一套JdbcTemplate。

## 3. 复合键、精确SQL和数据转换

35表中仅tenant_budget和worker_process是单列主键。其余Entity **不能只给id标@TableId**，不能把tenantId丢掉，也不改DDL加代理id。复合表无@TableId，使用BaseMapper.insert、带全键的Lambda Wrapper查询/更新/删除；不调用selectById/updateById/deleteById/saveOrUpdate及MP默认批量by-id API。隔离探针已证明无主键配置仍支持所需语句，MP警告保留，属于明确设计而不是漏配。单键表可用 `IdType.INPUT`，宿主继续分配UUID/tenant ID，不用Snowflake更换外部标识。

普通CRUD也必须有tenant与所有键、必要owner条件，列选择明确。复杂查询统一命名为 `selectTaskForUpdate`、`claimExactCandidate`、`commitOriginalSlot`、`releaseReservationOnce` 等有限参数方法，使用 `#{}`，不接受任意SQL/Wrapper/排序片段从HTTP或模型传入。XML无 `${}`、无 `SELECT *`；锁子句和表/列名固定于代码。对于 `<=>`、`OCTET_LENGTH`、`COALESCE`、`FIND_IN_SET`、条件计账和时间表达式保留原MySQL语义，不能粗改为泛型CRUD。

显式SQL示意（字段名/条件按原操作逐条迁移，不是新的通用更新模板）：

```sql
UPDATE task
SET state = #{nextState}, revision = revision + 1
WHERE tenant_id = #{tenant}
  AND id = #{taskId}
  AND current_attempt_id = #{attemptId}
  AND fence = #{fence}
  AND lease_until > CURRENT_TIMESTAMP(6)
  AND state = #{expectedState}
```

受影响行数0要按原契约区分fence拒绝、已提交原槽、竞争或不存在；不能返回success，不能统一重试。更新同值的affected/found rows差异应在真实Connector/J配置下验证，不把所有0都当fence。MyBatis批处理返回的特殊计数不能当1使用。

- Entity仅含列，nullable字段用包装类型；BIGINT费用微单位/字节/seq保持long，不用double。DATETIME(6)使用显式UTC转换和6位微秒格式；DB computed字段使用projection，不能自动fill覆盖DB权威时间。
- body/snapshot列存 **String原文或原byte[]**，不用JacksonTypeHandler解析再写回、`Map.toString()`、实体默认序列化或新的字段排序。先核旧字节/digest/长度，再做结构值比较。保留Payloads原mixins与Checkpoint版本字段。
- 不启用@TableLogic/通用软删，PENDING/PURGED不是同一个deleted flag；不用@Version插件替代任务fence、投影revision和复杂CAS。
- NULL写入用明确XML `SET col=NULL` 或Wrapper.set(field,null)，不用updateById的默认忽略null。尤其清理 `source_task_id`、全部snapshot/read_refs与DB CHECK配对字段必须精确更新。
- 关闭实体通用日志和SQL参数输出；业务code映射成安全错误，内部异常保留受限cause，公开错误不带SQL/DS URL。不要以新Result外壳改变当前JSON/数组/SSE/原始字符串响应。

## 4. 事务归属和查询资源

Spring/MyBatis官方要求SqlSessionFactory与事务管理器使用同一DataSource；由Spring管理SqlSession，不手动commit/close或另开SqlSession。本项目用原生TransactionTemplate适配UoW，不新造事务引擎。

| 操作 | 原策略与必须保持的归属 |
| --- | --- |
| submit/claim/dispatch/settlement/commit | ServiceImpl操作所有者开一次UoW；READ_COMMITTED，通常transaction10s/query5s；原可选evaluation run→tenant budget→scope相关排序锁→caller task→子账本。逐路径照原代码锁序表，不推定所有路径都有run或相同scope锁位置 |
| session公开/模型上下文/search/read/trace | 同一2秒绝对deadline，包含认证后的读取/连接/SQL/解析/证明/commit；锁等待1s，server max_execution_time及网络socket截止，剩余时间持续缩短；不增加ORM重试 |
| 持久拒绝 | 原 `tx:4236` 的3个明确code可能提交完整性/结算拒绝事实后抛出；保留值结果再抛出机制，不能统一改成所有RuntimeException回滚或全部TaskFailure提交 |
| scope失效 | 原 `invalidateScopeAfterTransaction:5275` 在原事务结束后单独记单调invalidated_at，仍受原deadline；不通过同一个已回滚事务写失效，不用无期限REQUIRES_NEW掩盖故障 |
| operator绑定 / assessment | bindOnce有独立operator DS/账户，assessment保持REPEATABLE_READ只读10s；与应用账本同表Mapper复用，但不混同执行授权 |
| FS/HTTP/LLM/进程 | UoW外执行；意图→实际效果→核验/账本提交多阶段，错误未知时保留原恢复语义；不跨长IO持锁 |

`JdbcLedger:4186` 中JdbcTemplate还承担计量/语句配置，迁移时必须保留这些行为。实施须同时交付：

1. 显式 `SessionQueryContext(deadline, cumulativeReads, bytes, operations, …)` 随同步调用传递；异步body前授权返回的QueryResources必须计入后半段。模型查询的匹配比较预算与证明读取预算是不同计数，不合并。
2. MyBatis StatementHandler/连接适配器在每条语句前更新剩余network/query/server时限；commit前也检查。DataSource有界socket适配仍控制DNS、connect/handshake和2秒截止，不能只用MyBatis整秒timeout替代。
3. 私有session proof/accounting Mapper采用有界显式投影和ResultHandler逐行计量，读取结果进入集合/解析树前检查；BaseMapper普通查询同样经过批准的计量路径。每次逻辑查询包括空结果计一次read，禁止一级/二级缓存让授权/计量绕行，禁止按实体序列化后估算原JDBC行字节。
4. 计费原语保持 `8 + UTF8(columnName) + scalarBytes`，NULL=1、固定UTC时间格式和实际标量类型；metadata按原列顺序/排除BODY定义计数。允许持久适配器内一个有界RawAccountingRow保留原JDBC标量，不能将Map用作跨请求权限上下文。
5. 原SELECT-prefix的正文长度约束迁为具体XML WHERE条件。所有查询仍有限投影/行数；先limit再在Java过滤不能扩大读取。未命中事件也进入scan_digest/资源计数。
6. 驱动通过类名实例化socket factory确需同步ThreadLocal（`SessionQueryDeadline:31,123`）。只在基础设施适配器保留这一窄例外；不存tenant/actor，不跨异步线程，close/finally移除，timeout后不能出现迟到DB写。其余业务预算不再新增ThreadLocal。

具体session历史限额、原始字节/固定点和下游公开封锁在 [值治理](magic-values.md)；每个迁移批次保存Mapper SQL/绑定参数结构（不含秘密/正文）、事务连接ID、读/字节计数及commit收据作差异对照。**Mapper是调用路径，原子性仍由DB锁、WHERE条件、事务和行数证明。**

## 5. 租户、分页、批量和基础设施例外

租户来自Authenticator/lease，owner/actor current enabled、installation pins及whole scope仍在真实读取/提交处核验，模型传来的tenant/ID不能授予权限。不默认安装TenantLineInnerInterceptor：它不表达owner/fence/scope，还可能改写跨tenant维护和FOR UPDATE；本批采用显式tenant XML/Wrapper及ArchUnit/SQL+双tenant负测。任何跨tenant查询限定为control maintenance的有界候选或host worker元数据查询，进入实际操作后仍按tenant重新核权/锁定。不同数据库账户只产生权限上限，不替代业务条件。

分页沿现有 `event.seq > cursor ORDER BY seq LIMIT bound`、scope validation复合keyset和native/MCP opaque cursor；禁止新增公网offset/pageSize或无界count。单次cleanup批次和累计deadline见值表。批量优先原有小界限循环/参数化多行XML，必须回读/验证每槽行数；不把一个8目标scope分成8次提交。确需ExecutorType.BATCH时独立短UoW、明确flushStatements/异常整体回滚并测部分失败；不得在同事务混用SIMPLE/BATCH SqlSession或以批量性能为由改变锁序。

最终生产代码仅以下JDBC基础设施例外，不准用它们保留业务存储：

| 位置/能力 | 例外依据 | 边界/必验 |
| --- | --- | --- |
| DataSource/事务/Statement/socket adapter | JDBC连接设置、驱动截止、Spring事务需要原生连接 | 无task/账本CRUD；连接reset和超时/提交故障实测 |
| schema/DB能力探针 | `flyway_schema_history`版本22、`information_schema`、`@@server_uuid`、`DATABASE()`、max_allowed_packet；非业务数据 | 放infrastructure并列有限固定SQL；也可Mapper，只能读取批准能力，无任意SQL入口 |
| Flyway执行及校验 | 唯一迁移框架职责 | 独立DDL身份，validate失败readyz DOWN；无业务兼容回填 |
| tools/test私有夹具 | 故障注入/建库/原始账本断言 | 不打入应用业务路径，不接生产、不重置共享库 |

metadata计账改静态列清单/显式projection时，须对照V22真实 `SHOW CREATE TABLE` 和原JDBC scalar字节；不再允许运行期拼接任意表列SQL。35表和外部receipt的SQL迁移完成后删除11文件中的业务JDBC，`Payloads`/`ModelCosts`/证明拆到合适位置。门禁对例外按**类+方法+固定SQL用途**维护，不对整个persistence包豁免。

## 6. 升级和回退

推荐只改代码和新增XML，无V23、无数据改写，应用仍严格schema22。先在私有库做空库V1→V22与现有V22带历史快照启动，不能改历史迁移/checksum或用repair。新版本先关闭feature flags做无DB/P1与durable control/worker readiness，再打开私有夹具已有profile；不是部署授权。

将来获准部署时由产品经理指定唯一环境写入者：记录旧JAR/hash、配置引用、DB备份/版本、进程和未决任务→停止新受理→按原语义drain/保留UNKNOWN→停旧worker→部署同一新制品→DB/存储/manifest/readiness→必要业务探针→恢复受理。不要直接在活跃旧worker间试混不同业务持久实现。无DDL/无协议变更时可退回已接受schema22的XIAO-55制品及原配置；flags关闭只能阻新能力，**不能替代whole-scope对旧正文的撤权/过期检查**。

若实施发现必须新增列/索引/快照版本，先回父项说明触发、访问模式/EXPLAIN、兼容与最小迁移；采用追加V23和能读旧新版本的回退制品。旧精确schema22 gate不会接受23，不能承诺直接回退旧JAR或降库。不得借结构重构扩大来源种类/FTS语义、收费授权或安装多版本。
