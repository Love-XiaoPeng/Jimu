交产品经理：本片完成 JdbcLedger.sessionFact/sessionAppend 的完整 Service、类型化惰性端口及实际 Mapper 接线，属于 B2 部分检查点，仅开发自测。准确源码/tree、同版制品、原始执行证据与远端回读由附件 manifest 绑定；60 保持 in_progress，PR22 保持草稿。

sessionAppend 保留 history:fact 的可靠 event 递归、完整键取 seq、原 append 和独立 account；幂等 append 仍计量。sessionFact 保留非 session 短路、accepted/prepared/responded/terminal 的求值及 NULL 顺序，非 RESPONDED 先返回而不查 attempt。Lease 参数、正文来源及原 Map.of 不变；terminal 仍四列 event_id/type/status/code，显式恢复原 LinkedCaseInsensitiveMap 列顺序后计算原摘要。event 方法及可靠事件上限、重复 fact 提前返回、递归内外重复 account 未改写。

三个原静态 JDBC SELECT 实际删除，step/attempt 的 SELECT * 和 terminal 四列改走真实 MP selectMaps，保持完整 tenant/id/task/fact 键、同 DS/UoW、显式 query，未新增事务、证明 SELECT、双写或 CAS。库存从182/26/587变为179/29/579；新增三个动态扫描匹配为固定 MP selectMaps 的受审入口，不声称整表迁移完成。其余529份既有生产 Java、413份基线资源/冻结输入及schema22/V1–V22同字节，另新增三份空 Mapper 注册XML。HTTP、owner、account、gateway/readiness接收代码不变。

最终受测制品复用第三轮 JAR：SHA256 d13f81ee3078d169213c3187c7336a532cb15a790ed7d57d9f180edf9d83ab4d，44,463,423字节，1,503个编译/资源entry。对106b0c1b的准确5419610d旧JAR，1,470个既有entry同字节，新增25个entry；JdbcLedger及七个嵌套class原始字节变化。独立class解析证明后七者仅Code.LineNumberTable变化，未将其写成原始class同字节。三轮产品全部1,503个entry内容一致而ZIP摘要不同，准确制品分列，后续试验不重打包。

前三轮完整定向verify均退出1，原件封存：首轮75项单测/契约/架构通过，IT因夹具误用reliable_seq字段失败；第二轮2项追踪通过，IT因证据编码未支持LocalDateTime失败；第三轮1项证据合同和2项追踪通过，IT在response夹具null usage进入原成本校验时失败。仅修夹具和证据编码，不改产品codec、截止或拒绝；第三轮实际已完成native/scope旧新各admission、prepared两项及外层恢复，另行逐笔核原摘要/长度和SQL次序。首个制品分析器误把七个行号变化当成产品行为变化的失败也保留，后补class结构证明，不改写首次失败。

第四轮复用准确第三轮JAR，仅补其余20项：2项夹具证据单测和1项IT通过；native/scope旧新各20条RESULT、20笔19张捕获tenant事实表全列外层恢复。覆盖实际responded、terminal，可靠/正文幂等，未知/NULL/非session/非RESPONDED短路，缺step/attempt/terminal的404，254/256可靠事件与126/127/128正文边界，以及SELECT/event/append/account各一次HY000/1644执行前故障。前三轮及第四轮SpotBugs均bugs/errors=0；单次合成SQL故障不代表自然08S01、失效连接或UNKNOWN验证。

每轮执行source/class、编译回执、原SQL/绑定、返回/主异常/证明错误/外层rollback及cleanup分存。事件原串分别核摘要、长度、来源及可靠seq后，仅对证明范围比较JSON值和规范化SQL顺序/重复计量次数；生成UUID及Map.of次序/原摘要差异保留，不扩称全部wire字节、绑定或物理往返相同。原始NULL及完整键绑定另有真实MP加载测试；13项Service单测核惰性/异常顺序，但不替代真实入口。

第五轮仅补实际read追加链，复用同一准确第三轮JAR，不重跑上述22项，IT退出1：native旧版2,076,470μs、scope旧版2,073,352μs均报SESSION_QUERY_TIMEOUT，随后外层rollback为08007/UNKNOWN，各0条RESULT/0笔恢复，不能作旧新成功差异通过。新版native一次1,746,073μs、scope self read一次1,799,446μs正常返回，各1条RESULT/1笔19表外层恢复，原output→binding→event摘要/长度单侧证明保留；旧侧无输出可对照，未生成跨版read-byte-proof，不能据新侧成功推定改善或关闭冷路径。两份完整容器清理成功，UNKNOWN实例未恢复/重放/原槽重试。第五轮未运行SpotBugs，不挪用第四轮为本轮结果；五轮共10份完整清理成功。完成组是第三轮2项＋第四轮20项，read另列，不能写23项旧新全过。

试验仅新私有冻结副本，原事件时钟SELECT替换为固定夹具时间，窗口续期及边界UPDATE在外层回滚；累计query、queryView计时起点、两秒绝对deadline和socket/network/事务设置均未改变。Response为显式known usage的夹具来源，不冒称worker转换原串或provider重放。受理setup与试验内受理分列，无caller试验提交；捕获19表全列恢复不是整库回滚、全任务、持久FAILED-read或独立afterCompletion后记证明。

本片完成Service/惰性端口/三个实际MP查询迁移与受影响链验证。下一具体候选为JdbcLedger.sessionAccess/sessionAdmission资格编排，交产品经理排定，本run不混做。B2其余、35表+receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read、独立后记、真实08S01/709ms、冷路径/冷锁排列及全部历史失败/缺件继续OPEN，最终61/62仍backlog。无新增授权阻塞；未CI/合master/部署/真实Key计费/共享数据/daemon代理FRP重置，维护交主控，T48及新功能暂停。
