# Architecture verification, 2026-10-23

Product source inspected: `270a5d15611f5d7e59bbbd1d96c94d07567fc858`, tree `a64d3ac4593de748c50df15ebb53f71d4bdcc0c2`. Multica checkout created an independent clean task branch at that revision. Source hashes and original migration bytes are in `evidence/jimu-sources.json` and `evidence/frozen-migrations.json`.

| Command / check | Actual outcome | Scope |
| --- | --- | --- |
| `multica repo checkout https://github.com/Love-XiaoPeng/Jimu.git` | exit 0; branch `agent/agent/91979f333166` | Real checkout; no old edits overwritten |
| `node .../fetch-upstream.cjs` | exit 0; 41 fixed Git blobs verified against the complete upstream tree | Tool entry/input/operations/presentation/access/errors, real SessionQuery/SessionCorpus/SQLite provider, SessionProjections and turnBoundary provider, Session/persistence, relevant tests and MIT license; tests inspected, not executed |
| `node .../make-contracts.cjs` | exit 0 | Reproducible manifest, all new Schemas/catalog/binding and examples; no product registration |
| `mvnw.cmd -o -B -ntp dependency:build-classpath -DincludeScope=runtime ...` | exit 0 | Dependency classpath only; **not** a product build or `verify` |
| `javac` production `SchemaContract.java` + `ContractProbe.java` | exit 0 | Real existing validator and cached runtime JARs; dependency SHA256 values recorded |
| `java ... ContractProbe ...` | exit 0; 49 cases passed | Strict JSON types/unknown fields/duplicates/trailing values/depth/byte size/Unicode/safe integers/window bounds; actual new Schema examples and invalid versions/shapes/provider IDs containing paths, URLs or whitespace; normative public-v1 redaction and non-nested receipt examples |
| `node .../capture-evidence.cjs` | final exit 0; 31 Jimu files + V1–V18 and 41 upstream blobs checked | Existing worktree source bytes exactly match baseline Git objects; source line map captured |
| `docs/adr/README.md` / frozen `docs/adr/sources.json` | actual 3819 bytes; frozen expectation 3446 | Existing SourceIntegrityTest mismatch retained; neither record changed |

The first source-map capture exited 1 because its locator expected `export default class` in the upstream SQLite source. Inspection showed the actual declaration is `export class SqliteSessionQueryEngine extends SessionQueryEngine`. The locator was corrected and the capture reran successfully; the pinned upstream file was never changed. This was an evidence locator failure, not a product/DB test.

`evidence/schema-cases.json`, compile/classpath/JVM logs, runtime dependency hashes, Git/source manifests and `source-map.md` hold the architecture evidence. `package.cjs --verify` validates the delivered package and catalog, including receipt/wire byte correspondence and copied event schema identity.

Not executed: product Maven build/full verify, V19 MySQL execution or upgrade, old JAR rejection, control/dual-worker runtime, real model tool-call business flow, crash/commit/fence/permission/retention/storage timing tests, upstream TypeScript tests, independent review/QA and product acceptance. H01–H12 remain backend/independent acceptance work; this package does not assert any of them passed. No product JAR was built, so there is no architecture product-JAR digest.

No GitHub CI was run, enabled, rerun or awaited. Prior CI records, the known SourceIntegrityTest mismatch and historical Windows POSIX failures remain unchanged. No old full matrix was rerun; no deployment, production/real Key, paid provider, shared service or daemon change was made. This is a design handoff within the architect role.
