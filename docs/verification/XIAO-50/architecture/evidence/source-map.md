# 决定的源码依据

Jimu列出的行号均属于实际master `777a2e2ae47c3d5be7ac545648c0d90483124ff2`；SHA/tree及全部blob见master-baseline.json、jimu-tree.json、jimu-verified-blobs.json，实际阅读副本的摘要另见reviewed-jimu-sources.json。上游路径均固定571902028a75c64c1d767f4ae5fd099485f0ddf0，副本及摘要见sources.json。

| 决定 | 实际依据 |
| --- | --- |
| 字段与真实结果、单active实际语义 | Harness `packages/todo/tool-todo/src/index.ts:80` 的toTodoList、`:123`的apply、`:169`的output和`:212`的execute；`src/types.ts:22`只有content/status；工具tests/tool-todo.spec.ts覆盖真实ToolRuntime+Session |
| null/[]/替换/turn清空与结束保留 | 同index.ts中todos projection init/apply/stateVersion2；tests/projection.spec.ts 的真实provider snapshot、asOfSeq、turn/end保留、turn/start清空及未安装key不存在；tests/integration.spec.ts真实唯一loop+mock模型 |
| Session真实拥有者与append并不等于DB原子提交 | core/agent/src/index.ts:463校验agent.id=session.id；core/session/src/index.ts:764明确热append不等待IO、snapshot/deepFreeze；session-persistence-jsonl/src/index.ts:287明确首次append/flush前不落盘；不能拿内存append替代Jimu账本 |
| 真实projection/provider读取水位 | session/session-projection/src/index.ts:199注册服务监听session/event、:339 snapshot给同一log切面的asOfSeq、:633按连续prefix推进；没有用工具摘要猜测客户端状态 |
| 工具value与render分开 | core/tools/src/index.ts:1840 createSuccessResult对value先snapshot/Schema/deepFreeze，再render内容；本批持久化真实value，不把计数文本冒充完整返回 |
| 安装、actor与固定锁顺序 | Jimu JdbcLedger.java:1406 bucket、:1410 identity FOR SHARE、:1421 epoch installation FOR SHARE、:2071 claim、:2229 fenced；沿用tenant→task及授权锁，不引入新权限后门 |
| 原通用工具存在RUNNING间隙 | JdbcLedger.java:3448 admit将PENDING变RUNNING；:3665 toolSucceeded另事务。claim:2117遇RUNNING/UNKNOWN会核对，故本批必须专用原子分支，不能仅加一个插件body |
| M2槽与恢复不可重建 | JdbcLedger.java:modelResponded（约3150起）先持久完整response、再创建稳定invocation；DurableAgent.java:598 manager按持久Call槽绑定callback，:672 invoke先分专用工具，再admit；旧SUCCEEDED只取原结果 |
| 数据库结果不明必须避开普通stop | WorkerLoop.java:86附近catch所有RuntimeException后ledger.stop；DurableAgent.java:148的外层已有问答专用栈展开路径；本批另加窄TodoCommitUnconfirmed且不混入问答状态 |
| API与事件复用、同一快照必要性 | TaskController.java原Tasks.get/Last-Event-ID/envelope v1；JdbcLedger.java:1959 events、:2038 event；DatabaseEventFeed.java原每次重鉴权/25秒连接；JdbcLedger.java:1158显式READ_COMMITTED，所以不能用多SELECT伪造一致快照 |
| 旧profile/迁移字节/清理边界 | V16、ADR-0008、QuestionContracts/UserQuestionLedger；JdbcLedger.java:68 cleanupQuestions、:719 questionStorage、:4775严格schema版本gate；本批增V17与独立binding，不改旧序列化和V1–V16 |

上游归档note只是附加历史，不高于固定源码与真实测试；其名称中“projection”或“持久”也不等于Jimu现成能力。全部运行保证仍按合同的实际MySQL/双worker验收取证。
