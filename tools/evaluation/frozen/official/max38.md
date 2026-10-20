> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bailian.console.aliyun.com/llms.txt
> Use this file to discover all available pages before exploring further.

# qwen3.8-max

> 2.4万亿参数的MoE架构旗舰模型，在编程与办公场景能力提升，适用于法律、金融、设计等专业任务。具备原生视觉理解能力，支持超长文档与长视频的语义解析，并支持长程任务的自主规划与迭代。

<meta property="og:title" content="qwen3.8-max" />

模型调用 ID（`model` 参数取值）：`qwen3.8-max`

快照版本：`qwen3.8-max-0902`（别名 `qwen3.8-max-2026-09-02`）

## 模型能力 <span id="h-bd06671ad1" />

<Tabs>
  <Tab title="华北2（北京）">
    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="新加坡">
    部署范围：国际

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="德国（法兰克福）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>不支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="美国（弗吉尼亚）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>不支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>

    部署范围：美国

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>不支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="日本（东京）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>不支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="中国香港">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>不支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>
</Tabs>

## 上下文限制 <span id="h-3a176132ed" />

<table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>最大输入长度</p></td><td><p>991808</p></td><td><p>最大输出长度</p></td><td><p>131072</p></td></tr><tr><td><p>最大输入长度（思考模式下）</p></td><td><p>983616</p></td><td><p>最大输出长度（思考模式下）</p></td><td><p>131072</p></td></tr><tr><td><p>上下文长度</p></td><td><p>1000000</p></td><td><p>最大思维链长度</p></td><td><p>262144</p></td></tr></tbody></table>

> 在不同的API输入参数组合下，模型支持的长度可能会有变化。

## 模型价格 <span id="h-84300568d2" />

本文仅展示模型调用原价，不包含限时优惠等活动信息，请前往[百炼控制台](https://bailian.console.aliyun.com/cn-beijing/model/market)查看活动优惠。

<Tabs>
  <Tab title="华北2（北京）">
    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（Batch File）</p></td><td><p>6</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出（Batch File）</p></td><td><p>18</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（Batch Chat)</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出（Batch Chat)</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="新加坡">
    部署范围：国际

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>14.988</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>44.965</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.874</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>18.736</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1.274</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="德国（法兰克福）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="美国（弗吉尼亚）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr></tbody></table>

    部署范围：美国

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>14.988</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>44.965</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.874</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>18.736</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1.274</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="日本（东京）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>
</Tabs>

## 限流 <span id="h-9a524f93ff" />

<Note>
  该模型在华北2（北京）与新加坡地域采用[动态限流](/zh/model-studio/quota-management)机制：TPM 限额根据百炼月消费档位调整；RPM 限额较高，正常使用不会触发限流，无需单独设置。
</Note>

<Tabs>
  <Tab title="华北2（北京）">
    本地域采用动态限流机制，TPM 限流值按百炼平台月消费额度分档，RPM 限额较高、正常使用不会触发限流。各档位数值请参见[动态限流](/zh/model-studio/quota-management)。
  </Tab>

  <Tab title="新加坡">
    部署范围：国际

    本地域采用动态限流机制，TPM 限流值按百炼平台月消费额度分档，RPM 限额较高、正常使用不会触发限流。各档位数值请参见[动态限流](/zh/model-studio/quota-management)。
  </Tab>

  <Tab title="德国（法兰克福）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>5,000,000</p></td></tr></tbody></table>
  </Tab>

  <Tab title="美国（弗吉尼亚）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>5,000,000</p></td></tr></tbody></table>

    部署范围：美国

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>5,000,000</p></td></tr></tbody></table>
  </Tab>

  <Tab title="日本（东京）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>5,000,000</p></td></tr></tbody></table>
  </Tab>

  <Tab title="中国香港">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>5,000,000</p></td></tr></tbody></table>
  </Tab>
</Tabs>

## 快照版本 <span id="h-f9fda1040f" />

### qwen3.8-max-0902

Qwen3.8-Max-0902（别名qwen3.8-max-2026-09-02）是qwen3.8-max的快照版本。编码深度再突破，可驾驭更复杂的工程级项目与长程自主开发；协作智能体能力显著增强，在多工具调度与端到端交付中表现更从容；视觉理解全面精进，图表推理、文档解析与多模态感知更敏锐准确。延续 100 万上下文、思考模式与完整工具生态，在更高智能水平上持续进化。

#### 模型能力 <span id="h-c962fe8985" />

<Tabs>
  <Tab title="华北2（北京）">
    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="新加坡">
    部署范围：国际

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="德国（法兰克福）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="美国（弗吉尼亚）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="日本（东京）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>

  <Tab title="中国香港">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>能力项</p></th><th><p>支持情况</p></th><th><p>能力项</p></th><th><p>支持情况</p></th></tr></thead><tbody><tr><td><p>输入模态</p></td><td><p><strong>Image</strong> <strong>Text</strong> <strong>Video</strong></p></td><td><p>输出模态</p></td><td><p><strong>Text</strong></p></td></tr><tr><td><p>模型体验</p></td><td><p>支持</p></td><td><p>Function Calling</p></td><td><p>支持</p></td></tr><tr><td><p>结构化输出</p></td><td><p>支持</p></td><td><p>联网搜索</p></td><td><p>支持</p></td></tr><tr><td><p>前缀续写</p></td><td><p>支持</p></td><td><p>上下文缓存</p></td><td><p>支持</p></td></tr><tr><td><p>批量推理</p></td><td><p>不支持</p></td><td><p>模型调优</p></td><td><p>不支持</p></td></tr></tbody></table>
  </Tab>
</Tabs>

#### 上下文限制 <span id="h-8f945d4296" />

<table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /><col style={{ width: "25%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>最大输入长度</p></td><td><p>991808</p></td><td><p>最大输出长度</p></td><td><p>131072</p></td></tr><tr><td><p>最大输入长度（思考模式下）</p></td><td><p>983616</p></td><td><p>最大输出长度（思考模式下）</p></td><td><p>131072</p></td></tr><tr><td><p>上下文长度</p></td><td><p>1000000</p></td><td><p>最大思维链长度</p></td><td><p>262144</p></td></tr></tbody></table>

> 在不同的API输入参数组合下，模型支持的长度可能会有变化。

#### 模型价格 <span id="h-af832dad26" />

本文仅展示模型调用原价，不包含限时优惠等活动信息，请前往[百炼控制台](https://bailian.console.aliyun.com/cn-beijing/model/market)查看活动优惠。

<Tabs>
  <Tab title="华北2（北京）">
    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="新加坡">
    部署范围：国际

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>14.988</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>44.965</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.874</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>18.736</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1.274</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="德国（法兰克福）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="美国（弗吉尼亚）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>

  <Tab title="日本（东京）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /><col style={{ width: "33.333333%" }} /></colgroup><thead><tr><th><p>计费项</p></th><th><p>价格（元）</p></th><th><p>单位</p></th></tr></thead><tbody><tr><td><p>输入</p></td><td><p>12</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输出</p></td><td><p>36</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>输入（缓存命中）</p></td><td><p>1.5</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存创建</p></td><td><p>15</p></td><td><p>每百万tokens</p></td></tr><tr><td><p>显式缓存命中</p></td><td><p>1</p></td><td><p>每百万tokens</p></td></tr></tbody></table>
  </Tab>
</Tabs>

#### 限流 <span id="h-5c37114753" />

<Note>
  该模型在华北2（北京）与新加坡地域采用[动态限流](/zh/model-studio/quota-management)机制：TPM 限额根据百炼月消费档位调整；RPM 限额较高，正常使用不会触发限流，无需单独设置。
</Note>

<Tabs>
  <Tab title="华北2（北京）">
    本地域采用动态限流机制，TPM 限流值按百炼平台月消费额度分档，RPM 限额较高、正常使用不会触发限流。各档位数值请参见[动态限流](/zh/model-studio/quota-management)。
  </Tab>

  <Tab title="新加坡">
    部署范围：国际

    本地域采用动态限流机制，TPM 限流值按百炼平台月消费额度分档，RPM 限额较高、正常使用不会触发限流。各档位数值请参见[动态限流](/zh/model-studio/quota-management)。
  </Tab>

  <Tab title="德国（法兰克福）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>150,000</p></td></tr></tbody></table>
  </Tab>

  <Tab title="美国（弗吉尼亚）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>150,000</p></td></tr></tbody></table>
  </Tab>

  <Tab title="日本（东京）">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>150,000</p></td></tr></tbody></table>
  </Tab>

  <Tab title="中国香港">
    部署范围：全球

    <table style={{ display: "table", tableLayout: "fixed", width: "100%" }}><colgroup><col style={{ width: "50%" }} /><col style={{ width: "50%" }} /></colgroup><thead><tr><th><p>参数</p></th><th><p>值</p></th></tr></thead><tbody><tr><td><p>RPM（每分钟请求数）</p></td><td><p>30,000</p></td></tr><tr><td><p>TPM（每分钟tokens）</p></td><td><p>150,000</p></td></tr></tbody></table>
  </Tab>
</Tabs>
