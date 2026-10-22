# ADR-0006：任务图片输入与媒体消息

日期：2026-10-22；事项 XIAO-46；状态：架构决定，可实施，等待产品实现/验收。

当前实际master为 `10be244360bb41ea92f2f078b1661ea4cf38c244`。工作区仅存UTF-8文档，模型持久消息仅有文本，报告v1的合法来源只有web_fetch精确正文。read_image需要真实媒体输入，不能通过Base64文本、文件名或图片的模型描述替代。

决定采用一次任务提交的有界PNG/JPEG输入。原图以本task的受限数据库记录原子接收，tenant/owner来自现有签名身份；图片的逻辑目录只读。新增ImagePort与规范PNG不可变对象，保留原FilePort和format2/3。JDK21内置ImageIO在现有独立任务worker内解码，限原始字节/像素/尺寸/时间与heap；不响应中断时复用已有supervisor证明worker退出，不能提前释放槽。没有新增服务、原生依赖或新监督循环。

新任务显式使用ModelSnapshot3、Checkpoint3、task record_version3。唯一ToolCallingAdvisor的宿主tool manager根据已确认read_image输出，先完成全部tool回复，再追加真实user图像parts。Chat Completions将规范PNG作为image_url/data URL发送；ledger在派发前重建并验证完整历史、媒体来源、实际字节和权限。旧v2快照、body及pins原字节保留，未知能力关闭；不得根据同名deepseek-flash自动升级或换模型。

官方当前deepseek-flash已声明视觉能力；本轮支持结论来自官方pricing/vision文档，未进行账户或付费视觉实测。代码允许新的显式图片profile，普通离线验收使用paid=false的本地受控provider，沿原scope、usage、预算、取消与UNKNOWN治理。真实派发继续需要既有授权与可证明费用上界，本批不部署或启用。

图片报告使用compose_report@0.2.0/draft2/renderer2，收据绑定实际带图request及产生compose call的response；图片文字明确为模型推断，纯图片来源无需伪造网页。旧web段仍要求精确引用和原URL集合检查。present按任务固定pin验证收据及文件确切版本，使用原产物/下载授权；旧v1不改。

取舍：直接拓展文本FileValue会改变旧存储/CAS并污染搜索投影；通用上传/对象服务引入当前不必要的生命周期；引入sharp/libvips扩大依赖与原生执行边界；第二模型循环破坏现有账本。上述方案本批不采用。代价是首批只支持PNG/JPEG、图片目录不参与文本glob/grep、需版本化codec与报告来源，并对有界输入保留额外存储。完整Schema、事务/兼容/错误与独立验收条件见 `implementation-p4-read-image.md`。

回退保留前向migration、task版本、媒体对象/摘要、pins、收据与UNKNOWN；排空新任务后旧binary仍不得领取record_version3。完成条件是同SHA独审/QA和受控纵切通过；协议替身通过不代表真实模型视觉质量验收。
