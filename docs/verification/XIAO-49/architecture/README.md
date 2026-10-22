# XIAO-49 架构交接包 v1

可实施的最小契约已固定，供产品经理接续原后端唯一派工。

- `ADR-0008-durable-user-questions.md`：当前基线、技术取舍、默认blocking/宿主timed、HTTP入口及无需前端改动的决定。
- `implementation-contract.md`：鉴权、请求/响应/失败、持久状态、事务/幂等、运行槽/quiescent、迟到回答/最终文本竞争、V16兼容/保留及真实纵切完成条件。
- `contracts/`：两版manifest/输入输出Schema，HTTP读取/回答/receipt、binding和迟到消息Schema、例子及摘要catalog。默认0.1.0 blocking；0.2.0 timed由宿主选，同task只选一个版本。
- `VERIFICATION.md`、`evidence/`：固定源码/许可和实际探针证据。原始源文件保持字节；`PACKAGE-MANIFEST.json`核包内摘要。

基线：Jimu master `0e630f28e39c157788886694e2b16dc0831bb571`，tree `8850a9d11f610257d87962585221b91a6bc19948`。上游Harness `571902028a75c64c1d767f4ae5fd099485f0ddf0`，G04/T11；上游包版本0.2.1-alpha.3，第一方MIT。

本回合只交付架构制品，没有修改产品代码、创建实现PR、迁移数据库、运行真实产品问答或新增用户/安装/外部服务。由原后端把本包纳入其唯一实现分支/草稿PR（base master）；不要修改冻结基线文件。无需前端派工，本批真实入口是现有鉴权API的扩展。完成后的同SHA独审、QA和业务验收由产品经理接续。

静态验证：`python3 make-contracts.py`后执行`python3 verify-contracts.py`。Windows已有本项目Java/Maven/WSL环境时，`verify.ps1 -RepoRoot <Jimu checkout>`执行离线依赖解析、原始SchemaContract编译验证和真实Spring AI暂停探针；它不是产品verify或业务验收。

包装前运行`package.py`，生成逐文件摘要及ZIP，明确排除机器classpath文件、编译class和其他运行临时件。运行验证不会启动本产品control/worker、访问付费模型、共享数据库、部署或GitHub CI。
