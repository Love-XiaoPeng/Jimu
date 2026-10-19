公共网页调用的阶段记录与结果凭据

事件关联使用 `intent:<invocationId>` 与 `result:<invocationId>`。工具尚未成功提交时，缺少 `result:` 事件并不能说明请求未执行。

公共读取新增固定阶段事件 `web.fetch_phase` 和 `web.fetch_failed`，只保存枚举阶段／错误类别，不保存 URL、请求头、页面正文或异常消息。阶段包括 ADMISSION、DNS、CONNECT_TLS、HEADERS、BODY、PROJECTION、REDIRECT；阶段表示最近确认的位置，CONNECT_TLS 合并连接和 TLS，不能据此独立判定远端故障根因。整段截止时间或取消也不能证明是哪一个底层阶段导致。

完整读取、投影及输出 Schema 验证通过后，受信 worker 在独立短事务中把结果保存在原 invocation 的受限 `output_snapshot`／`output_digest`，同时追加 `web-result:<invocationId>`（`web.result_preserved`）。事件仅含绑定摘要，正文不会进入事件、错误或运行日志。绑定涵盖 tenant/task、step/attempt/effect、原调用 ID/slot/参数、插件 pin/epoch、任务快照及请求／响应 checkpoint 摘要。结果写入不改变 RUNNING 状态，也不结算占用；随后仍沿原成功提交路径处理。历史迁移和快照字节不变。

进程在结果保存后、成功提交前退出时，恢复仍停在 NEEDS_RECONCILIATION，费用／HELD_UNKNOWN 保留，不自动重派、恢复或结算。`WebFetchReconciliation.assess` 是受信运维层使用的只读核对方法，要求同一原调用、参数、pin、checkpoint、结果摘要和原凭据事件全部相符；返回元数据及摘要，不返回正文。它不提供结果导入、状态变更或结算接口，不把存在凭据等同于可继续任务。缺失、重算后篡改、歧义调用、跨 tenant/task/invocation 均拒绝。没有此凭据的历史结果不能靠补写标签升级为可信结果。

隔离验证入口为 `WebFetchResultIT`：普通／SSE 的真实 worker、本地模型和 TLS 替身、专用临时 MySQL。覆盖保存与结算之间的崩溃、UNKNOWN 恢复不重派、只读核对不改账、输出／参数／pin／checkpoint 篡改和失败阶段；既有公共读取、报告发布／授权下载及历史恢复门禁继续执行。它不连接持久项目库或真实 provider。
