# ADR-0015：业务Service分层与MyBatis-Plus账本迁移

日期：2026-10-23。事项：XIAO-59 / XIAO-58。状态：**accepted**。核定依据：XIAO-58 产品经理于2026-10-23核定，授权记录为 XIAO-60 当前实施范围（事项ID `01a167ef-01fb-70b3-a0b9-a7cfd8d3dbf3`）。实施仅启动 B0/B1，B2–B5 和总验收未完成；本ADR不表示代码、数据库或业务验收完成。

## 背景

基线master `620d5e0e5bfe7b5da530f10997936466159ba34f`，tree `ef29f391b8355a7d693a48cefdcc379e35c32929`。单制品模块边界、插件叶子API和唯一Spring AI循环已有实现；163个生产Java文件中，JdbcLedger 8828行同时实现10个业务接口，Service业务/SQL/事务集中，部分Controller保留受理策略和下载存储调用。35张Flyway管理表多数复合主键，不能由普通by-id CRUD替代其授权/锁/fence/计账语义。

小鹏及父项要求整体分层治理与真实MP迁移，实施/独审/QA仍待后续阶段激活；优先级暂停T48和其他新功能。全仓盘点和精确锚点见 `../refactoring/XIAO-58/README.md`。

## 决定

1. 延续ADR-0003的单Maven制品和无框架kernel。业务用例采用 `kernel/<业务>/service/XxxService` 与 `service/impl/XxxServiceImpl`；Controller仅HTTP/SSE、严格解析、身份传递、结果映射。拆现有接口/实现，避免再叠无规则转发壳。
2. 用实际Records端口隔离持久化，Mapper/Entity/XML仅在adapter。一个 `LedgerUnitOfWork` 端口映射Spring原生TransactionTemplate及相同DataSource，保留特殊拒绝提交、失效后记、短事务和2秒绝对截止。此端口对应已有复杂事务能力，非为整齐制造空层；不以注解代理自调用改变原提交语义。
3. 候选版本保持JDK21、Boot4.1.1、Spring AI2.0.1；新增MP **Boot4 starter3.5.17**，受管解析MyBatis3.5.19/mybatis-spring4.0.0。采用官方Boot4分支，不降级到Boot3、不同时引入另一starter或编排链。依赖收敛和隔离装配探针已通过，真实DB/产品装配仍待实施验证。
4. 普通CRUD用BaseMapper/Lambda Wrapper、显式投影和全租户/键条件；锁、联查、CAS、计账及精确NULL更新用参数化XML。复合键表不伪造单@TableId，不用by-id/saveOrUpdate；保留同库事务和已接纳原槽。迁移覆盖运维绑定/评估入口，最终无旧业务JdbcTemplate并行路径。
5. 沿用显式DriverManagerDataSource和驱动有界socket，暂不池化。禁止缓存绕过当前授权/计量；查询预算从JdbcTemplate override迁到声明的Mapper查询/结果计量路径。驱动所需同步ThreadLocal是窄基础设施例外，业务预算/identity不以ThreadLocal跨线程传递。
6. 拆DurableAgent为attempt执行、宿主网关、Spring AI session与checkpoint转换；保留ToolCallingAdvisor/DefaultToolCallingManager及唯一循环。现有自定义wire/HTTP仅限“原request字节证明、dispatch/usage结算、截断流安全、受控取消与无SDK重试”的适配缺口（DurableAgent:554、OpenAiProtocol:141、ProviderHttp:11）。不把它扩成通用模型SDK；本批保留已接受边界，未来替原生provider需先证明同样的请求/usage/取消/恢复能力，再独立迁移。
7. 业务状态/code用显式兼容枚举/常量；runtime可调值归校验配置，版本/字节/保留/审批等冻结值不热改。旧Schema、DDL、snapshot、manifest和历史证据不重写。普通HTTP/SSE响应保持现形状，不增加破坏旧调用者的Result外壳。

## 不采用的方案及原因

- 全仓拆多Maven模块/微服务：目前同库原子账本和单业务规模不需要网络事务/版本协调；包依赖检查更合适。
- 只加MP依赖、保留JdbcLedger业务：没有完成用户要求，也无法治理Controller/Service/SQL职责。
- ServiceImpl直接引用Mapper或让kernel导入Spring/MyBatis：打破现有可核验内核边界；本项目已有真实事务/外部能力，窄端口提供有效隔离。
- 自动租户/乐观锁/逻辑删插件包办：无法表达whole scope、owner/current pin、租约fence、PENDING/PURGED和一次释放，可能重写锁SQL。
- 本批改成连接池、Graph、Redis或向量库：没有与本次重构对应的需求；池化还会改变现有socket截止/连接状态生命周期，须另有必要证据。

## 兼容、回退和验证

推荐无DDL、schema精确22、公开协议/快照原字节不变。旧新worker不并发试写；将来获准部署时先停新受理/drain、保留UNKNOWN、停旧worker再切制品。无数据变化时回退接受schema22的XIAO-55 JAR；若实施需要V23，先补范围/前后兼容和能读23的回退制品，不能承诺旧精确22 binary可回退。

V01–V12矩阵、分批/初级切片/唯一集成人、完成标准见 `../refactoring/XIAO-58/delivery-and-validation.md`。架构规则落实到真实类、Mapper加载/SQL静态检查与双tenant/并发/计账实测；不运行/恢复/重跑/等待GitHub CI。原完整verify exit1及XIAO-55未知锁持有者/未穷举覆盖边界保留。方案通过后由产品经理激活原XIAO-60，稳定SHA/tree/JAR交原独审/QA；方案本身不代表总重构完成。

## 官方依据

- [MP安装与Boot4 starter](https://baomidou.com/en/getting-started/install/)、[Wrapper](https://baomidou.com/en/guides/wrapper/)、[字段/主键策略](https://baomidou.com/en/reference/annotation/)、[批处理和事务](https://baomidou.com/en/guides/batch-operation/)。
- [MyBatis-Spring事务](https://mybatis.org/spring/transactions.html)：同DataSource、Spring事务管理、不得自行commit/rollback SqlSession。
- [Spring声明式事务及代理边界](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)、[MVC异步/SseEmitter](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)、[Spring AI工具调用](https://docs.spring.io/spring-ai/reference/api/tools.html)。
- 版本特定Maven Central POM及本地依赖树优先于可变文档中的最新版示例；抓取摘要/时间/摘录与实际探针在 `../verification/XIAO-59/`。

本ADR在核定后补accepted及决策引用，不改原ADR-0001–0014或来源摘要。若与早期目录示例的application命名不同，本ADR只调整发布接口/service/impl的位置，继续保留其内核/插件/适配器隔离目标。
