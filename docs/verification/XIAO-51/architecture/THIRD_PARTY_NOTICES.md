# 固定来源与许可

DeepSeek Harness：`https://github.com/deepseek-ai/deepseek-harness`，固定 `571902028a75c64c1d767f4ae5fd099485f0ddf0`。

本包 evidence/upstream 保存研究所用源码、测试、文档和真实Session快照的原始字节；`upstream-sources.json` 对每个文件列出原路径、固定revision、Git blob、字节数和SHA256。`fetch-upstream.cjs` 逐一验证固定Git tree/blob，不以浮动分支为依据。

第一方 MIT，Copyright (c) 2026 DeepSeek。原始完整许可在 `evidence/upstream/LICENSE`；移植实质代码或fixture时，后端应随源码与制品保留此许可和notice。本包未引入npm运行依赖、native bundle或TS/Cordis运行时；现有native工具许可闭包沿既有交付保持。本包未运行上游测试，不把上游文档所载历史测试数视为本次执行结果。
