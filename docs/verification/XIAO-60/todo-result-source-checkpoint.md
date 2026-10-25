本片仅迁 `JdbcLedger.todoResultSource` 的完整待办结果源资格。parent `2f84f3a40386c92ae8fb2fa9a257c3f4708c8362`，沿原分支 `agent/agent/7aeccb14d7a9`、草稿 PR22；准确源码 SHA/tree、受测 JAR 由交付 manifest 与原 Git commit/tree/bundle 绑定。本片是 B2 部分检查点，不是独审 QA、总验收或 B2 完成。

## 实现与冻结范围

`TodoResultSourceService`／impl 完整承载八项 OR、查询结果严格 size1、PURGED 短路和原摘要比较。`TodoResultSources` 是类型化惰性端口；`MapperTodoResultSources` 构造只保存引用，不读 invocation/task/会话。原 applied 的 get 判空与 n 读取分别到达；Number.longValue、expected+1 的 signed long 溢出、String cast／NULL、四个成员只判断 Object 非 NULL均保持。查询后不读返回 id；PURGED 仍先查询，只跳 output/hash/digest。非 PURGED 保留 snapshot→原 Payloads.hash→digest→equals；无新 catch、翻译、flag、模型、身份或第二事务。

适配器只在查询到达后依次读取 task.tenant_id→task.id→invocation.todo_committed_attempt_id→invocation.todo_committed_fence(long)，随后沿原 nullable queryContext、同 DS/UoW 调用真实 MP `TaskAttemptReadMapper.selectMaps`。新增 execution 通用读 entity／BaseMapper 与空 namespace XML 注入，不借用 session 私有端口。不用 selectOne、COUNT、EXISTS 或全行实体读。

```sql
-- 原 JDBC
SELECT id FROM task_attempt WHERE tenant_id=? AND task_id=? AND id=? AND fence=?
-- 本片实际 MP
SELECT id FROM task_attempt WHERE (tenant_id = ? AND task_id = ? AND id = ? AND fence = ?)
```

实际 SQL 差异只有 WHERE 括号和空格，仍单列 id／完整四键，没有锁／排序／LIMIT／worker或状态过滤。前三个 String setter 均 setString；long 的原 setObject 变为 setLong，Java 值类型仍 Long。MyBatis 执行 API／getUpdateCount(-1) 的日志差异单列，不称全部 JDBC 调用相同。

原两处调用表达式及其他 Ledger 方法均未改：实际方法名 `replaceOrResult` 的 SUCCEEDED 重取分支先本片→输出原串比较→共享 commitFence→todos.output；新执行分支不调用本片。`getTodos` 仍 UUID→tx→visible/access→原联表／原先行 source 资格→本片→counts→PRESENT 输出检查→source/view，revision>0 的 PURGED 仍调用。原 projection.output_digest 与 invocation_digest 别名语义、固定 view 键序及时间不改。共享栅栏13处、session完成边界、所有已有 access/catch/outcome／scope afterCompletion 去重与 REQUIRES_NEW后记、queryView／累计实计／绝对两秒及调用者末尾deadline均未迁动。

598份其他生产 Java、421份既有资源及冻结文件逐字节不变，新增1 XML；相邻 Bean/bootstrap/POM、schema22/V1–V22、todoProjection21列／case／列序／NULL、全部已迁 Service不变。本片迁1旧JDBC点，SQL仍执行／消除0；库存静态165／动态29／bind552，不关闭整表。

## 定向验证与封存

本轮只执行一次完整定向 verify：12 Service＋7 port＋2追踪＋1架构＋1真实IT全部通过，命令0、原 Maven BUILD SUCCESS、SpotBugs bugs/errors0。没有本片测试／产品运行失败或重跑旧完整矩阵。先封存执行源码、生产class、driver编译件／compiler input list、原日志、JUnit与清理，再进行离线审计和交付；制品精确信息见 manifest／analysis.json，非后打包候选替换。

run1封存108项；受测JAR SHA256 `a0272aa514876e0ff6ff90dc5e26dd7f615deb4a3685151a22d248fc383289ad`，44,573,722字节／1,605生产entry。独立读取准确旧7df174b0／新a0272aa5原JAR全部entry：旧1,598中1,590原字节相同、7新增；既有变化仅Ledger主class及七嵌套，后七者解析只移除Code.LineNumberTable后其他字节完全相同。逐轮执行源码／编译件／生产class与受测JAR另核。

单测覆盖全部八项短路、独立 applied 读取、NULL state/tool、Number.longValue／cast／NULL、signed overflow、严格0/1/多行且不读id、PURGED仍查而跳输出、hash→digest次序及各回调原 RuntimeException／TaskFailure／Error传播；端口覆盖构造不读、存在字段不解码、原实参 cast／nullable fence先后及真实加载MP SQL／四个参数。多行、非常规Number、NULL类型和溢出仅合成单测，真实PK及CHECK下不冒充数据库事实。

准备阶段有 Windows Python alias、错误cwd／只读路径／rg通配符及生成离线class审计脚本的引号SyntaxError，均在有效脚本和执行原件之外，未改变产品或执行制品；有独立stderr者附存，工具回执中出现但未独立封存的完整只读stderr仍属缺件，不称齐全。封存脚本已在运行前纠正为精确本片助手，未夹带前片助手冒作执行证据。无业务SQL注入／自然08S01／UNKNOWN或回滚失败。

## 有界真实链与证据限界

私有 MySQL8.0.46／paid=false。HTTP仅新侧合法todo受理准备，旧新开始前复制同一原数据库。每笔外层事务内实际 claim 后，用显式SQL建立符合当前CHECK/FK的已存 SUCCEEDED invocation、RESPONDED step、projection及 tool.completed event；这些是合法夹具，不是工具执行、模型结算或可靠事件产生机制的新证明。没有 worker循环、provider请求或新工具执行。纯todo codec仅用于生成合法输入／输出。

旧新各七组完成：

- direct-valid：直接私有方法，一次原单列四键查询，size1成功。
- owner-present：真实 `ledger.getTodos`，原 owner／access／安装锁／联表后查询结果源，原固定view／counts／source可读。
- owner-purged：projection为PURGED、invocation.output_snapshot实际NULL，公开调用仍查询attempt后成功，todos为NULL；没有output摘要读取。
- owner-digest：同时改projection及invocation摘要，先行摘要相等门通过，随后实际本片查询后原hash比较409/SNAPSHOT_CORRUPT。
- direct-size-zero：合法正数 committed_fence增加1，四键查询实际空行→409/SNAPSHOT_CORRUPT；这是真实键不匹配，不是attempt缺行／DELETE。
- wrong-owner：carol实际getTodos先404/NOT_FOUND，本片查询0次。
- stored-replay：合法既存SUCCEEDED结果实际重取，单次本片查询→原输出原串判断→共享fence，未新执行todo工具、未增加reservation或工具用量。

七对原SQL次序及所有完整bind按本侧已核attempt ID规范化后相同，除明确本片MP括号／空格外无新SQL差异；actor、installation锁、原联表／projection键亦保留原件。每笔本片四键与各自真实fixture invocation／task／attempt核对，String／Long类型逐项验证；direct-size-zero的fence另核+1。生成attempt ID不同，不泛化全部原bind相同。

14份夹具原证明独立验算输入／响应及输出摘要、projection/source／attempt／fence／seq关系；14笔tenant-a二十表捕获事实在各自外层rollback后相同。worker_process不在这20表捕获范围，不称整库。唯一cleanup receipt确认子进程退出／fixture停止／容器及根目录移除、failures空。预期拒绝的完整主错、cause/suppressed与潜在SQLException next/state/vendor采集机制、rollback／证据落盘错误分离代码保留；本轮未出现SQL错误或次错。

公开view／重取输出原串、SHA256及UTF-8长度分别保存；跨侧仅对已证JSON字段和各侧已验attempt ID作规范化比较，Map.of原序／原时间均保留，不称HTTP wire字节相同。固定提交时间仅为夹具值，不当数据库时钟新验收。

未删除有FK引用的真实attempt，其他真实缺行继续OPEN；没有整库／持久提交／成功工具read／FAILED-read／独立后记／独审QA证明。既有工具新执行、todo受理及其他session／scope覆盖只引用原核收，不当本轮新head验收。

## 剩余与接续

下一具体候选为 `JdbcLedger.questionAccess` 完整pin／contract／installation／retention资格，需先核其真实调用与原错误顺序后由PM按原A主线接续；本轮未混迁。本片仅按当前唯一切片合同交付。

B2／B0–B5未完，60保持in_progress／PR22草稿；最终61/62 backlog。35表＋receipt、core最后beforeCommit、scope完整成功工具read、持久FAILED-read／独立后记、其他真实缺行、自然08S01／709ms／冷路径及全部历史失败／stderr缺件OPEN。63/64/66/70不重接，72固定旧head。没有CI／合master／部署／真实Key计费／共享数据服务／daemon代理FRP重置，T48和新功能暂停。

只回读既有规则 `01a1746c-49ed-76fc-a662-e463c42c5491`，绑定准确run `01a1746b-6133-7aac-a193-592a1462a61c`、唯一completed/failed/cancelled、once及7200秒；没有重复创建或恢复。
