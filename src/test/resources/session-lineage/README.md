`architecture-19.json.gz` contains the exact 19 synthetic Fact/Stored inputs and
expected results from XIAO-127 attachment `01a183f2-f4fb-7028-b8eb-3d8e0c174a1c`
(SHA256 `c571224548b744909e90a3dc980cdf9df4270e5ac7d8b1e0c894b6d0756e77ff`).
The source probe is `offline/graph_probe.py`; its `check` calls were captured
without changing the inputs. Gzip uses mtime=0. No real tenant data is included.

SessionLineageSamplesTest runs these values through the production Java
MapperSessionLineageRecords codecs, Service and SessionQueryContext, with
fixture fact/load ports. It checks every target's output, strict rejection,
independent hashes and the original bounded nine-node refusal. It is neither
a SQL/current-permission test nor an A03, recovery or budget acceptance result.

Full work evidence separates encode/hash/parse/compare/copy/serialize/schema
input work and requested/completed counters. Diagnostic execution uses the
ordinary wider envelope; restricted execution retains 65536 and refuses.
