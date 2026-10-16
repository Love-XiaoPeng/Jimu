XIAO-40 完整计费输入准入

本次提供逐轮完整请求门禁和无证明时的拒绝路径。**尚无百炼两款快照完整计费输入 ≤8192 的可核查 tokenizer/服务模板证明，因此没有为真实提供方注册小输入计数策略，不能按条件低价预算启用真实派发。**条件预算不等于授权；生产开关、20 次/全部计费输出 ≤512/累计 1 元约束不变。

## 固定限制与执行边界

模型 profile 新增可选 `input-limit`，随整个 `ModelSnapshot` 在任务受理时保存并纳入 digest：

```properties
# 关闭派发的声明示例；该真实服务 proof 尚未获证，实际派发必拒绝。
jimu.model.paid-dispatch-enabled=false
jimu.model.profiles.reviewed.input-limit.version=1
jimu.model.profiles.reviewed.input-limit.max-tokens=8192
jimu.model.profiles.reviewed.input-limit.proof=bailian-complete-input-unproven
```

`max-tokens` 只允许 1–8192；proof 是宿主实现选择标识，不是运营者填写一句“已证明”便获许可。不存在的策略返回 `MODEL_INPUT_BOUND_UNAVAILABLE`，不会退化成按提示词长度或 context-limit 放行。

唯一 `ToolCallingAdvisor` 每轮生成最终 OpenAI HTTP JSON，包括全部 system/user/assistant/tool 消息、函数调用参数、所有历史/工具返回、实际工具 description/Schema、协议字段及 provider 选项。`JdbcLedger.dispatchModel` 在预算/派发落账前，验证请求 digest、固定配置 digest，并校验这个确切 `requestBody`。HTTP 边界再次检查同一正文，之后按该 UTF-8 字节原样发送。普通和 SSE 共用此路径；不裁剪或改写正文、不另起模型循环，不把 SSE 分段当作独立输入。

恢复要求原 profile（含 input-limit/proof/价格）仍相同；改变默认 profile 不影响旧任务，修改旧 profile 的限制或证明则 `MODEL_CONFIG_UNAVAILABLE`，不静默重新绑定。成功模型/工具缓存仍用原固定配置；后续新轮次重新检查增长后的全部历史。

可选字段缺失时在 canonical JSON 中省略，保留旧 v2 快照原有字段和摘要语义；没有为历史任务补造小上界。没有数据库迁移，V1–V6 和基线原字节保留。

## 可核查依据与明确缺口

真实云服务的计费输入不仅是可见文本。当前官方材料没有给这两个固定快照一个可设置的 8192 服务硬限额，也没有提供完整计费 tokenizer、chat template、Schema/隐藏 framing 的固定上界及版本保证。故以下方式均不能证明真实上界：改 context-limit、只计算 user prompt、UTF-8 字节+任意常数、事后 prompt_tokens、免费额度或小样本观察。没有实现以这些方法放行百炼的小输入策略。

唯一已实现证明标识 `test-whole-request-utf8-v1` 是**测试专用合成计费契约**：本地服务器把完整原始 HTTP JSON UTF-8 octet 数作为 synthetic prompt_tokens，额外输入 framing 为 0。宿主计数完整原始正文，恰好覆盖服务器的全部合成输入单位，包括结构字符/字段名等保守多计部分；不需要估算隐藏开销。服务器同时独立断言收到的正文 ≤8192，并返回实际 `len(raw)`，请求/响应账本可复算。

该策略只接受 `jimu.test-mode=true`、`trusted-local-v1`、字面 loopback `127.0.0.1` 或 `[::1]`，且 model/revision 均为 `local-byte-accounted-v1`。真实端点、其他型号或非测试装配，即使复制 proof 字符串也一律拒绝。**这里的 octet 合成单位不是 Qwen/OpenAI token，不证明任何真实模型的输入上界。**为未来真实服务增加证明实现仍需新的可审阅代码、来源及独立验证，不接受运行时传入任意公式/额外计数常数。

## 保守预留、输出及未知用量

未声明小输入门禁的旧策略继续要求 `price.input-token-upper == capabilities.context-limit`，沿用整个提供方上下文上界预留。已知百炼候选 `qwen3.7-plus-2026-05-26`、`qwen3.7-flash-2026-07-15` 的云配置必须保留官方 1000000 context，不能改成 8192。北京最高阶梯全上下文单次仍为 Plus 6012288、Flash 1202458 micro-CNY，单次已超 1 元，应在派发前拒绝。

若显式声明小门禁，可令价格输入上界等于该限制；这只是预留所用上界，必须通过相应 proof 的每次实际正文检查才可派发。proof 缺失/不可用时拒绝，不悄悄改用低价全上下文或高价后再派发。全上下文路径不被删除，也不因此提高原授权。

两款百炼云快照同时要求非思考、禁内置搜索、`max_completion_tokens`、output margin ≥10，内部全部计费输出 ≤512；配置 512 时实际发送 ≤502，覆盖架构附件所列官方最多 10 token 误差。context-limit 不是输入限制字段，max_tokens 不是这两个快照的全部输出依据。

显式小输入门禁的计费任务若缺输入/输出 usage 之一，费用仍 HELD_UNKNOWN，保留整个已证明输入+输出预留；在原事务持久记录 `USAGE_UNKNOWN/MODEL_USAGE_UNKNOWN` 并 STOPPED 评测 run，拒绝执行该响应的工具/后续派发。恢复读取这个持久决策；不能在“响应落账后、任务失败前”退出后再次请求。已知输入/输出越界仍记录原始事实并停发，不能钳制或伪造用量。旧无门禁策略的未知费用保留行为不变。

## 验证与使用限制

默认 verify 新增 `ModelInputBoundTest`、profile 反例及 `InputBoundIT`：完整 UTF-8/普通/SSE 的 8191、8192、8193 边界，system/Schema/assistant 参数/history/tool result 全量增长，真实 control/worker/MySQL 的二轮工具增长零派发，M0/T1 恢复、旧任务/新默认隔离、原 profile 改限制明确拒绝、未知用量在精确提交退出窗口恢复、全上下文超预算及观察输入/输出违例。

所有 accounting-enabled 正例只访问本地合成服务器，不发生真实计费。现有完整 P2、398 条模型回归继续执行，不删减门禁；实际退出码、数量、固定 SHA 和清理证据由交付附件报告。本说明不代替运行通过或独立验收。

账号侧仍需由有权限的控制台会话确认无密钥事实：地域、按量计费方案/部署范围/API Host、两精确快照在空间与 Key 的授权、出口 IP 规则是否满足。当前编码环境没有可用百炼控制台会话，不从 Key 或官方示例推断这些事实，不请求重发密钥或泛化文档。即使这些账号事实齐全，真实 8192 的证明仍是独立缺项；未齐全前真实模型/搜索继续关闭。
