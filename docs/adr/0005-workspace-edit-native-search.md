# ADR-0005：原子编辑与原生搜索

日期：2026-10-22；状态：accepted（XIAO-42 产品经理实施交接）。

工作区是带观察、操作意图及收据的原子文档，不是宿主目录。edit 在原锁内执行字面非重叠替换与 CAS；不能由插件拼接 read/write。新任务从 pins 确定 format3，持久单调 UTC 纳秒 modifiedAt，旧 format2 不迁移。

glob/grep 只搜索同任务不可变普通文件投影。采用固定摘要 rg、bubblewrap 与窄 C/POSIX launcher，逐层 NOFOLLOW、dev/ino 核验、FD 挂载及空根 user/mount/pid/net namespace。独立启动/状态/放行握手，创建任何子进程前落盘启动身份，原 lease 持久身份后放行。

沿用唯一调用循环、存储 reservation 和 worker supervisor。完整有界结果绑定 tenant/task/query/pin/snapshot，重复页不重搜；STOPPED 必须有真实 namespace、launcher、bwrap 和流采集退出证据。缺少 bundle、namespace 或停止证明均关闭准入，禁止宿主执行降级。

排除 Java regex、任意 shell/argv、宿主路径挂载和另一套重试/配额机制。新端口为兼容加法，数据库只前向迁移，回退保留 format3、pin、收据及 UNKNOWN。完整限额、行为差异和验证要求见 `docs/implementation-p4-workspace.md`；该文件保存架构交接原文，实施状态及实际验证另记，不把探针当产品验收。
