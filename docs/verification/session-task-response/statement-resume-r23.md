# statement 来源重复校验与事务恢复清理

XIAO-126 接 XIAO-127 对 `d6c814c` 的窄差异核收。本批只落实三个生产文件及三个新定向测试；保持原 SQL、schema、V23 checksum、长度来源、raw digest、动态权限、锁和末端 fence。

`SessionHistoryRows.Slot` 在全部来源校验通过后借用 SIZE/RAW 的不可变 statement ID 引用。只有同 Slot、同 String 对象的重复调用省去完整 equals；首次和异对象同文仍支付并完成 equals。每次重新检查当前 Work/query/token、物理连接、Map 的三个动态值和 projection locks。新引用在 clear 中先断开再退款，不复制文本或缓存权限。

基础费用由 15 改为 23，未命中另付 `2 + ID.length`；发布条件每次付 1，实际发布另付 3。Slot 分配前 owner 为 4112 bytes，构造和清理预付 68 项；raw owner 为 `16400 + 8 * bytes`，替换前付 5 项。tenant=t/task=x 时 SIZE 首次 146、RAW 首次 135、同对象重复 29；单次 scripted JDBC load 1844，仅为局部证据。

原 resume 在同 query 内层失败后再次调用严格 attachMetadata，首错会阻止外层同步器恢复，实际回滚后仍遗留 owner 并返回未确认结果。现在 resume 只恢复自身已经 retained 的 Settings：同 monitor 下按实际当前人口 C 逐次预付 `44 + 3C`，登记 RuntimeException/Error 首错后继续清理恢复。初次 install 仍严格；业务 checkHealthy、finish、beforeCommit 仍拒绝失败 query，不能借恢复授权提交。44 包含原 12 及失败分派/临时引用/登记的保守 32，不替代真实人口扫描。

三个正常 JUnit 测试类合计 14 方法，覆盖真实 MyBatis ID、动态反例、跨事务、分配前付款、N−1/N/N+1、首错/cause/SQLState/suppressed、恢复预算拒绝及真实另一线程人口（helper 必须 join）。同 query HY778 反例修后保持 0 commit / 2 rollback、原首错且 scratchLive 回到 65536。恢复剩余 43 项时外层拒绝，44/45 时可正常提交；内层若已提交，不声称两事务原子。

动作与生命周期分别见 `statement-resume-accounting-r23.csv` 和 `statement-resume-ownership-r23.csv`。测试仅覆盖本批变更，开发自测不代替独审、QA 或业务验收。真实第二准入与同 query 完整响应按新 SHA/tree/JAR 独立采证；局部收益不推算整链通过。完整 A12、九节点一笔绝对 2 秒全链、M2 全出口、独立 DB 新 fence 及真实网络/客户端 ACK 仍须分别验证。所有原限额不变，不运行或等待 GitHub CI。
