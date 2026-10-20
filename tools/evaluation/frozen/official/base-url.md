> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bailian.console.aliyun.com/llms.txt
> Use this file to discover all available pages before exploring further.

# Base URL总览

> Base URL 是模型 API 的调用地址。Base URL必须与同一计费方案的 API Key 配套使用，否则会报错 401。各地域的 API Key 相互独立、不能跨地域使用。

<meta property="og:title" content="Base URL总览" />

## 按量付费 <span id="187a3e1ee50yt" />

根据使用场景选择合适的域名：

- Dashscope 域名（`dashscope.aliyuncs.com` 等）：原有中心化共享域名，当前可继续使用，建议迁移至业务空间专属域名。
- 业务空间专属域名（`[workspace-id].[region].maas.aliyuncs.com`）：推荐用于生产环境，提供更高吞吐、更低时延与业务空间级流量隔离。
- 试用域名（`trial.[region].maas.aliyuncs.com`）：适用于临时测试与快速验证，限流值较小。

### Dashscope 域名 <span id="0af2c6a67bsob" />

支持跨业务空间的 [API Key](https://bailian.console.aliyun.com/cn-beijing/settings/api-key) 调用。

<table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p><strong>地域</strong></p></th><th><p><strong>OpenAI 兼容</strong></p></th><th><p><strong>Anthropic 兼容</strong></p></th></tr></thead><tbody><tr><td><p>华北2（北京）</p></td><td><p><code>[https://dashscope.aliyuncs.com/compatible-mode/v1](https://dashscope.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://dashscope.aliyuncs.com/apps/anthropic](https://dashscope.aliyuncs.com/apps/anthropic)</code></p></td></tr><tr><td><p>新加坡</p></td><td><p><code>[https://dashscope-intl.aliyuncs.com/compatible-mode/v1](https://dashscope-intl.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://dashscope-intl.aliyuncs.com/apps/anthropic](https://dashscope-intl.aliyuncs.com/apps/anthropic)</code></p></td></tr><tr><td><p>美国（弗吉尼亚）</p></td><td><p><code>[https://dashscope-us.aliyuncs.com/compatible-mode/v1](https://dashscope-us.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://dashscope-us.aliyuncs.com/apps/anthropic](https://dashscope-us.aliyuncs.com/apps/anthropic)</code></p></td></tr><tr><td><p>中国香港</p></td><td><p><code>[https://cn-hongkong.dashscope.aliyuncs.com/compatible-mode/v1](https://cn-hongkong.dashscope.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://cn-hongkong.dashscope.aliyuncs.com/apps/anthropic](https://cn-hongkong.dashscope.aliyuncs.com/apps/anthropic)</code></p></td></tr></tbody></table>

如需使用 DashScope 原生 API，请将上表 URL 中域名后的路径替换为`/api/v1`，例如北京地域为`https://dashscope.aliyuncs.com/api/v1`。

### 业务空间专属域名 <span id="8d565e2318auv" />

仅限业务空间所属 [API Key](https://bailian.console.aliyun.com/cn-beijing/settings/api-key) 调用。

<table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p><strong>地域</strong></p></th><th><p><strong>OpenAI 兼容</strong></p></th><th><p><strong>Anthropic 兼容</strong></p></th></tr></thead><tbody><tr><td><p>华北2（北京）</p></td><td><p><code>https\://\{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1</code></p></td><td><p><code>https\://\{WorkspaceId}.cn-beijing.maas.aliyuncs.com/apps/anthropic</code></p></td></tr><tr><td><p>新加坡</p></td><td><p><code>https\://\{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1</code></p></td><td><p><code>https\://\{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/apps/anthropic</code></p></td></tr><tr><td><p>日本（东京）</p></td><td><p><code>https\://\{WorkspaceId}.ap-northeast-1.maas.aliyuncs.com/compatible-mode/v1</code></p></td><td><p><code>https\://\{WorkspaceId}.ap-northeast-1.maas.aliyuncs.com/apps/anthropic</code></p></td></tr><tr><td><p>德国（法兰克福）</p></td><td><p><code>https\://\{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1</code></p></td><td><p><code>https\://\{WorkspaceId}.eu-central-1.maas.aliyuncs.com/apps/anthropic</code></p></td></tr><tr><td><p>美国（弗吉尼亚）</p></td><td><p><code>https\://\{WorkspaceId}.us-east-1.maas.aliyuncs.com/compatible-mode/v1</code></p></td><td><p><code>https\://\{WorkspaceId}.us-east-1.maas.aliyuncs.com/apps/anthropic</code></p></td></tr><tr><td><p>中国香港</p></td><td><p><code>https\://\{WorkspaceId}.cn-hongkong.maas.aliyuncs.com/compatible-mode/v1</code></p></td><td><p><code>https\://\{WorkspaceId}.cn-hongkong.maas.aliyuncs.com/apps/anthropic</code></p></td></tr></tbody></table>

如需使用 DashScope 原生 API，请将上表 URL 中域名后的路径替换为`/api/v1`，例如北京地域为`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1`。

### 试用域名 <span id="d06552cd78c0j" />

支持跨业务空间的 [API Key](https://bailian.console.aliyun.com/cn-beijing/settings/api-key) 调用。

<table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p><strong>地域</strong></p></th><th><p><strong>OpenAI 兼容</strong></p></th><th><p><strong>Anthropic 兼容</strong></p></th></tr></thead><tbody><tr><td><p>华北2（北京）</p></td><td><p><code>[https://trial.cn-beijing.maas.aliyuncs.com/compatible-mode/v1](https://trial.cn-beijing.maas.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://trial.cn-beijing.maas.aliyuncs.com/apps/anthropic](https://trial.cn-beijing.maas.aliyuncs.com/apps/anthropic)</code></p></td></tr><tr><td><p>新加坡</p></td><td><p><code>[https://trial.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1](https://trial.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://trial.ap-southeast-1.maas.aliyuncs.com/apps/anthropic](https://trial.ap-southeast-1.maas.aliyuncs.com/apps/anthropic)</code></p></td></tr><tr><td><p>中国香港</p></td><td><p><code>[https://trial.cn-hongkong.maas.aliyuncs.com/compatible-mode/v1](https://trial.cn-hongkong.maas.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://trial.cn-hongkong.maas.aliyuncs.com/apps/anthropic](https://trial.cn-hongkong.maas.aliyuncs.com/apps/anthropic)</code></p></td></tr></tbody></table>

## Token Plan <span id="8a39cc5432j36" />

仅限 Claude Code、Codex 等 AI 工具交互式使用，不能用于后端服务。使用 Token Plan 的[专属 API Key](https://bailian.console.aliyun.com/cn-beijing/subscription/uac-admin/organization/members/list)。

<table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33%" }} /><col style={{ width: "33%" }} /><col style={{ width: "33%" }} /></colgroup><thead><tr><th><p><strong>地域</strong></p></th><th><p><strong>OpenAI 兼容</strong></p></th><th><p><strong>Anthropic 兼容</strong></p></th></tr></thead><tbody><tr><td><p>华北2（北京）</p></td><td><p><code>[https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1](https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1)</code></p></td><td><p><code>[https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic](https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic)</code></p></td></tr></tbody></table>

## Coding Plan <span id="8a5b9c6c8dnjo" />

仅限 Claude Code、Codex 等 AI 工具交互式使用，不能用于后端服务。使用 Coding Plan 的[专属 API Key](https://bailian.console.aliyun.com/cn-beijing/subscription/coding-plan) 。

<table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33%" }} /><col style={{ width: "33%" }} /><col style={{ width: "33%" }} /></colgroup><thead><tr><th><p><strong>地域</strong></p></th><th><p><strong>OpenAI 兼容</strong></p></th><th><p><strong>Anthropic 兼容</strong></p></th></tr></thead><tbody><tr><td><p>华北2（北京）</p></td><td><p><code>[https://coding.dashscope.aliyuncs.com/v1](https://coding.dashscope.aliyuncs.com/v1)</code></p></td><td><p><code>[https://coding.dashscope.aliyuncs.com/apps/anthropic](https://coding.dashscope.aliyuncs.com/apps/anthropic)</code></p></td></tr></tbody></table>

## 相关文档 <span id="4b46c08bb4vu9" />

- [获取API Key](/zh/model-studio/get-api-key)
- [选择地域、服务部署范围和接入域名](/zh/model-studio/regions)
