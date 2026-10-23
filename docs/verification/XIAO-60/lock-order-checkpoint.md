# B0 历史锁序核查检查点（不得作为当前关闭依据）

基线为 `620d5e0e5bfe7b5da530f10997936466159ba34f`。以下行号仅相对旧 `adapters/persistence/JdbcLedger.java`，保留原检查点记录。后续代码已提取 Service/records 和 query adapter，旧行号、词法命中及本表的阶段预测不适用于当前关闭判断。当前按符号的分支策略、参数谓词及回调节点见 `core-workflow-checkpoint.md` 与 `evidence/reviewed-paths.json.gz`；实际 Mapper 切换全部 OPEN。

特别更正：不存在全仓统一的 evaluation→budget→task 锁序。当前 `reserveImage`、`imageSucceeded` 继承 budget→fenced(evaluation→budget→task) 分支，付费 run 下与 evaluation-first 路径交错的并发风险未在本批验证关闭。owner、cleanup、scope/query、worker 收据各自有不同顺序，必须使用具体方法/分支的有序 call plan。

| 路径 | 已核实顺序/行为 | 后续迁移验证 |
| --- | --- | --- |
| claim L5589–5783 | tenant 候选读取 → 事务 → evaluation run → tenant budget → scope/source 锁 → 精确 task 候选 `FOR UPDATE SKIP LOCKED` → 重验 evaluation_run_id/权限/原槽 | B2：两个真实 worker、重复候选、旧 fence 拒绝；本批只验证装配后双 worker 正常领取 |
| fenced L5784 起 | evaluation(lease, admission) → bucket → locked task → 当前 fence/lease/授权/scope 核验；不可提前用缓存授权 | B2：所有入账调用锁连接及 fence 差异 |
| model prepare L6622 起 | leaseTx → evaluation → fenced（含重复取得既有锁）→ bucket → input/proof/dispatch/reservation 原子写；模型 HTTP 在事务外 | B2：dispatch/UNKNOWN/usage 一次结算 |
| invocation admit L7247 起 | leaseTx → fenced → bucket → 固定 step/slot/安装 pin/预算准入 → invocation/reservation | B2：同 response 原槽、一消费、拒绝与结算 |
| invocation commit L7487 起 | tx → fenced(false)，需要新准入时 fenced(true) → 原 invocation/step 提交 | B2：取消/到期/旧槽竞争 |
| invocation unknown L7583 起 | tx → fenced(false) → UNKNOWN 原子记录，不释放不确定效果 | B2：外部效果不能重放 |
| artifact publish L8159 起 | leaseTx → fenced(true) → stage/verify/发布记录；存储效果在原阶段边界 | B2：发布和 artifact.created 事实同事务 |
| scoped query L410 起 | scopeQueryTransaction / sessionTransaction → fenced → scope require / 全来源核验 → 原 scope query `FOR UPDATE` → normalized proof / 原结果 | B4：显式查询上下文、真实实计、撤权竞争及两秒截止 |
| 通用 tx L4236 起 | READ_COMMITTED/10秒；仅 EVALUATION_SCOPE_MISMATCH、MODEL_SETTLEMENT_PROOF_INVALID、BUSINESS_POLICY_CONFLICT 返回拒绝事实后提交，事务外抛错 | 本批将普通事务执行交 Spring UoW，保留原 allowlist 和 AdmissionResult；不是所有 TaskFailure 都提交 |
| leaseTx L4454 起 | tx 内目录事务设置保留；EVALUATION_MATERIAL_MISMATCH 记录 STOPPED 后事务外抛错 | B2：原真实材料拒绝路径差异复验 |
| sessionTransaction L197 起 | 原绝对 end → 有界连接 → READ_COMMITTED/2秒；lock wait 1秒，剩余 network/server/statement timeout，beforeCommit 复核，finally reset/remove | 本批不改方法体；仅把其事务管理器与 Mapper 同 DS；B4 才迁显式业务预算 |
| ScopeInvalidation L5247 起 | afterCompletion 后新短事务；沿原 absolute end，不给新两秒；bucket → locked task → 单调 invalidated_at → metadata account | B4：回滚后记录及真实撤权交错 |

不能从该静态表推断原现场未知持锁者已定位、所有锁排列已穷举或完整 V03/V08/V09 已通过。B0 全入口锁序关闭、B1 Worker/Execution/Model/Invocation 业务拆分与 query adapter 仍是下一检查点工作。
