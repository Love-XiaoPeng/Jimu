当前 session_trace 验收依据成员于 2026-10-31 批准的决定（Multica 评论 `01a1907f-f409-7486-b2e0-d45c922eedea`）。本说明覆盖历史材料中的九节点无条件成功要求；历史报告、附件和失败记录保留原字节。

九节点契约为：**最多九节点，每次 query 累计最多 65536 operations，并受原其余资源上限约束。资源内且所有其他门满足才可成功；超限必须明确、可识别拒绝，并完整回滚及清理 owner。** 不部分提交、不丢审计、不泄漏数据、不清除 UNKNOWN、不自动重放。schema 的 maxItems9 保持。

两节点真正第二受理仍须提交成功，随后新 query/Work 的独立 second-eventHistory 仍须成功；不能将九节点可拒绝套用于该最小成功路径。当前 R31 第二受理回滚，不能据本决定标为通过。

256 ResultSet、绝对 2 秒、1MiB proof scratch、65536 operations 及各费用单位保持；真实来源新读、动态 tools/权限、逐份 raw 长度/严格 UTF8/digest/schema/pin/关系校验、同 Work 结构交接与写前失效、全部并存 owner、history/eventHistory 双读、Settings/REQUIRES_NEW/resume 和末端 live/fence 保持。不得以跨事务证明、删除独立 raw、延后计量、跳过最终计账或提前退款取得成功。

完整 A12、九节点资源内成功与超额拒绝、M2 同 response 三 trace/全出口、独立数据库新 fence 冻结槽、真实网络 R1/R2 和 ACK UNKNOWN 仍各须必要实证。typed 保持 test-only，A01–A16 原 ID 保持。未执行或仅静态推导的项目不能标为通过。

沿原 feature/XIAO-125-session-lineage 与 draft PR24 交付；125/126 保持 in_progress，127 done。无 GitHub CI 运行/等待、部署、真实 Key、新付费、生产/共享数据库、daemon/代理或网络环境变更。

生产基线仍为 `b695e74a5e65417523fc187f069eecc3a598d228`，完整 JAR 的 SHA256 为 `399fa6e6532d70bf0f07981ed079e400ae5abcc52eedd0651ce0d918a2ab9f02`（45465373 bytes）。R31 保存事实见 `r31-node-input.md`；本次源码可实施性核查与准确限制见 `r33-end-to-end-cut.md`。本说明不回写历史 PASS，也不表示已经形成稳定候选。
