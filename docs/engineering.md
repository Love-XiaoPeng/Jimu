# 积木工程规范补充：业务分层与持久化

2026-10-23，XIAO-59。状态：**accepted，XIAO-58产品经理于2026-10-23核定，授权范围见XIAO-60正文**。B0/B1开始实施，后续迁移和总验收尚未完成。原 `docs/baseline/XIAO-33-工程规范-v0.2.md` 保持原字节；目标结构不能写成全部当前实现。

本补充采用 [ADR-0015](adr/0015-business-services-and-mybatis-plus.md) 与 [全仓方案](refactoring/XIAO-58/README.md)。历史命名、插件Schema和冻结证据沿既有ADR；开发只按批准范围迁移，不预生成空层。

1. 业务模块发布 `service/XxxService`，实现 `service/impl/XxxServiceImpl`。有业务规则、短事务和用例编排才是Service实现；Record/DTO/纯函数不配空接口，不使用万能BaseService/Manager/全局Context。模块之间只引用发布service/api，不穿透impl或内部domain/Records。
2. Controller负责HTTP/SSE适配、严格协议解析和身份传递；Service负责能力/预算/资源/业务校验及事务。Controller不访问Mapper/Entity/Records/ArtifactStore、不调用具体插件/模型、不创建执行线程。严格body长度/重复键/UTF8等是协议边界合理职责；保留先认证/路径核权再解析body的既有契约。
3. 内核ServiceImpl/domain保持纯Java；通过真实Records、模型/存储端口及统一LedgerUnitOfWork访问外部。事务适配直接复用Spring TransactionTemplate，同DataSource供MyBatis/事务；不在事务内等LLM/网络/进程/存储IO。不因拆模块丢原run/budget/task锁序、fence、幂等、特殊拒绝提交或scope失效记录。
4. 业务持久化通过MP Boot4 starter，普通CRUD用BaseMapper与Lambda Wrapper，复杂联查/锁/CAS/计账/NULL更新用参数化Mapper/XML。Entity、对外DTO/VO、内核值对象职责分开；复合键全键+tenant，禁by-id和saveOrUpdate误用，禁SELECT *、`${}`、外部SQL片段和模型任意SQL。
5. 显式tenant/owner/current pin/whole-scope与数据权限，不能以缓存、查到ID或拦截器自动条件代替。keyset/cursor保持已发布行为；批量有界、原子、真实flush/回滚及行数核验。不默认新增分页/软删/乐观锁插件。
6. 业务枚举/code保持原数据库和公开值；runtime配置声明单位/默认/校验和冻结时机，策略/Schema/字节限额不能热改旧task。Entity默认序列化、Jackson升级、MP忽略null不得改变原snapshot/cleanup字节。已冻结资源从权威源读取，不复制可漂移常量表。
7. 一个Spring AI ToolCallingAdvisor循环；模型/消息/工具用原生API，必要自定义wire adapter按ADR所列真实缺口局限。AI attempt状态显式传递并有生命周期；不增加跨请求Map/ThreadLocal，驱动socket同步上下文按准确类方法例外。无Graph/RAG需求不增加相应设施。
8. 沿现有HTTP/SSE形状和 `code/message/traceId` 错误，不给旧数组/原始JSON包新Result。异常分类并保留内部cause，边界输出安全code；禁止吞错/成功fallback/无界重试。正文/Key/凭据/原SQL cause不进公开日志；保留必要task/attempt/invocation/fence定位信息。
9. 类、接口、核心规则和AI执行节点用简洁中文说明实际用途/输入边界/失败语义，复杂证明/恢复说明依据；注释不替代实际行为。配置使用构造注入/类型绑定/校验，秘密仍为受信引用，不提交实值。
10. 保留现有格式/静态/依赖和ArchUnit门禁，并落实Controller窄依赖、内核/插件叶子、业务slice无环、SQL入口/租户/复合键及冻结摘要检查。规则例外精确到类/方法/用途，不对整个包放行。必要本地verify按本批实际受影响矩阵执行，未验/失败如实保留；不运行或等待GitHub CI，不重跑无变化旧完整矩阵。

实现交接锁完整源码SHA/tree/同一JAR摘要、必要本地命令/退出码及原始证据、旧新入口/表/值关闭清单、风险/回退。独审和QA在同版独立实例并行，开发自测不替代；产品经理验收合并后才算XIAO-58完成。未授权生产/付费/共享服务动作不因规范变更取得授权。
