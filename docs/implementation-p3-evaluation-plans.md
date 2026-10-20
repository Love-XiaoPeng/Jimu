# XIAO-40 关闭评测计划与持久限制

本阶段只增加受信隔离评测的配置支持和本地验证。`qwen-main-v1` 与 `qwen-pro-optional-v1` 互斥，均关闭；500/650 元、63/76 次和 4096 输出均不是已经生效的执行授权。真实模型、搜索和生产公共出口继续关闭，原 PR 未合并。

## 冻结材料与入口

架构交付原件位于 `tools/evaluation/frozen/`，包括两份计划、逐项评分锚点、实际提示词、Schema/描述、来源及草稿摘要；保留 MANIFEST 原字节。运行时 `src/main/resources/evaluation/` 中的有限投影同时绑定原计划文件 SHA256 和共同材料摘要。它们不是任意 JSON 公式或可上传策略。默认未选择新计划；公共任务请求不能设置计划、阶段、样本、模型、端点、凭据、token 或宿主标识。

受信配置新增：

```properties
jimu.model.evaluation-run-id=<原持久run>
jimu.model.evaluation-plan-ref=qwen-main-v1
jimu.model.evaluation-phase=A
jimu.model.evaluation-sample=W1
jimu.model.paid-dispatch-enabled=false
```

另一个可选值为 `qwen-pro-optional-v1`，不能在首轮后切换。仍通过原 profile 配置保留普通/SSE 的不可变版本。新计划要求精确候选、1,000,000 上下文、固定最高费率、非思考、无内置搜索、完整普通/SSE usage 与 `max_completion_tokens`。全部计费输出4096、HTTP4086、误差余量10；没有注册真实提供方的8192小输入证明。真实云路径即使误开既有付费开关或 test-mode，也会被原件的关闭标志拒绝。唯一本地对照例外同时要求 test-mode、trusted-local-v1 和字面 loopback，不接触真实推理端点。

`tools/evaluation/prepare_closed_plan.py` 只生成供审阅的 SQL，不连接数据库、不创建 run、不启用开关。必须沿用原租户/run，保留 requests_used、已知费用和 UNKNOWN 占用及 STOPPED 状态；不能通过新 run 获得替代额度。原 run 仍需实际账号事实、执行额度与最终代码独立验收，不索取新密钥。

## 并发、任务与恢复

V7 是向前迁移，V1–V6 原字节不变。policy_version=1 保留20次/1元的数据库约束；policy_version=2 只能使用内置关闭计划及其上限。应用按 evaluation→tenant→task 顺序锁定，沿原 dispatch/reservation 账本原子计次与预留；没有第二套总账或模型循环。HTTP 与文件 IO 均在事务外。

第一次新计划任务准入必须能容纳完整矩阵及历史负债，无法容纳时前置拒绝，不静默缩样本。已有消费仍计入原总额；旧任务的模型/512输出/次数/费用/token快照不改。跨策略恢复不能证明原绑定时明确拒绝。新任务固定计划摘要、材料绑定、阶段、样本、精确模型及原 run 上限；重启不重新分配整套额度，恢复改参或换计划不能升级旧任务。新任务的同模型同样本只能绑定一次，不用换 task 取得重试池。

每次按完整1,000,000输入+4096输出预留1,004,096 tokens。W1最多7轮、7,028,672 tokens，C1检查嵌入其摘要；W2/W3/W4各最多6轮、6,024,576 tokens。四工具顺序必须是 web_fetch/read/write/present；没有独立无工具C1的产品路径。每个模型/样本任务的费用封顶为其逐次向上取整最坏费用乘对应轮数；HTTP原有最多1元的 budget 字段只能收紧，不能扩大宿主额度。

同时检查全局、阶段、候选、样本次数与费用，以及 tenant/task 的 token、费用限制；同 run 最多一个在途模型派发。阶段/候选/样本统计来自原 model_dispatch 及不可变任务绑定，受同一个 evaluation 行锁序列化，不另建可清零的计数池。派发失败也计次，HTTP自动重试0。

| 计划 | A/B次数 | A/B金额上限（元） | 总次数/金额上限 | 本轮最坏矩阵（元） | 隔离矩阵token预留 |
|---|---:|---:|---:|---:|---:|
| Qwen三档 | 39/24 | 260/240 | 63/500 | 471.999593 | 63,258,048 |
| 加Pro可选 | 52/24 | 380/270 | 76/650 | 626.584745 | 76,311,296 |

旧消费及UNKNOWN同时计入总额，余额不产生额外派发或搜索许可。未知分项用量依旧持久 STOPPED、保留费用预留；提额、切计划、worker重启都不会清除它。完整默认回归继续覆盖旧512路径。

## 共享 run 停发

`fenced` 与恢复 claim 均先锁同一 evaluation 行，再锁 tenant/task，持锁至事务提交。STOPPED 除模型派发外也拒绝同 run 其他任务的缓存 checkpoint/工具结果准入、工具执行准入、上传意图、fixture 发布、实际 present 原子提交及任务成功提交；恢复不会绕开它。任务自身的未知/超界拒绝码优先保留，其余同 run 拒绝沿用 MODEL_BUDGET_EXCEEDED。

已经准入的在途操作仍可记账，不能借停发丢弃已知结果或释放 UNKNOWN；已提交产物不撤销，下载仍检查原权限。实际 present 在发布事务前被共享停发拒绝属于已确认的发布拒绝；先前持久上传意图保留供原清理流程处理，不伪称工具效果未知。公共 reader、文件写入和网络 IO 仍在事务外。

默认 SharedStopIT 覆盖 fixture 与实际 present 的普通/SSE缓存、实际 control/worker 重启、双 worker，以及停止先提交/发布先提交/同步释放屏障的竞争。OPEN恢复、异run和已提交下载是正向对照。旧 a20cefb 反例与修复运行分开留存；这些是本地替身自验，不是独立验收。

## B 阶段

`phase_b_snapshot/digest` 由既有受信数据库准备流程记录，公共API或模型不能提供。其 Qualification 包含计划/材料摘要、全部候选已完成的A阶段裁定、W1/W2分项分数、嵌入C1结果、证据摘要、最多两名晋级者，以及裁定人、时间、原run和独立验收SHA。两份盲评分及分歧裁定原件保存在被绑定的证据 manifest 中；后端不代替人工做事实评分。

解锁要求每份报告总分≥85、工具≥27、来源≥27、C1通过且无硬失败；A已安排任务均为已知终态，晋级者W1/W2均实际成功，run为OPEN且无HELD_UNKNOWN。第一次B任务准入后，晋级证据摘要固定，修改名单/证据被拒绝；重复同一提交只读回原绑定。零人不派发B，一人只给其12次，两人合计24次，不能增加候选或补位。

材料准备与质量评测是不同证据。此阶段回归使用本地 TLS、替身模型、合成最坏usage及明确标注的合成评分/验收摘要；它们验证门禁，不表示任何真实候选质量合格。完整 system/history/tools 的实际发送正文及摘要继续在既有逐轮 step/dispatch 中持久固定。首次真实评测仍需来源预检、逐轮实际材料一致性核查、原run余额事实和实际批准，关闭配置不是派发许可。

## 验证与限制

默认 `./mvnw -B -ntp verify` 纳入 EvaluationPlanIT：两套完整本地矩阵、普通/SSE、真实 present/授权下载、额度与token边界、阶段拒绝/晋级、并发重复绑定、相同配置恢复及改参拒绝、UNKNOWN停发和其他租户配额对照。静态测试核对原材料文件摘要、当前宿主提示词模板、精确价目及能力与关闭标志。V2及V4/V5隔离重建已包含V7回退，保留默认完整P2。

历史自然readiness超时、旧CI SQL失败及早前瞬时事务的精确原因仍未确认，不用本阶段结果覆盖其限制。真实付费质量、账号准入及完整P3仍待独立流程。
