# Fixed upstream evidence

`evidence/upstream/` contains unmodified first-party DeepSeek Harness source, tests and documentation from `deepseek-ai/deepseek-harness` commit `571902028a75c64c1d767f4ae5fd099485f0ddf0`.

Copyright (c) 2026 DeepSeek. These files are licensed under the MIT License. The complete, byte-preserved license is `evidence/upstream/LICENSE`; keep it with source copies and any substantial behavioral port. `evidence/upstream-sources.json` records the repository paths, URLs, Git blob IDs, byte sizes and SHA256 values. The evidence is not installed as a TypeScript/Cordis/SQLite runtime dependency.

The new Jimu contract deliberately differs from upstream in authorization scope, native event wire, capture redaction, step visibility boundary, output encoding, neighboring summary limits, read-result receipts and retention. The normative comparison is section 2 of `implementation-contract.md`. No claim of wire compatibility or execution of the upstream test suite is made.
