# Native bundle v2 — auditable rebuild

XIAO-47 explicitly replaces the historical npm rg pin with a separately versioned
Jimu rebuild. The old npm digest and its unclosed provenance remain historical
facts in `../source-inputs.json`, `../sbom.cdx.json`, and the XIAO-44 review.
They are not assertions about this build. No binary is committed to Git.

The first build slice uses unmodified ripgrep `3a612f88b8` (15.0.0), Rust 1.88.0,
the locked crate sources, musl 1.2.3, PCRE2 10.45 and upstream's musl jemalloc
allocator. Inputs are verified before extraction. Cargo runs frozen, locked and
offline; the local build is also executed in a network namespace without a
network interface. The C toolchain is extracted into a private sysroot.

Completion requires a new immutable input manifest, build/output receipts,
actual Cargo compilation graph, static/dynamic link inspection and applicable
notices; matching bubblewrap original source, Ubuntu patches and replacement
instructions; explicit runtime pins; and directed readiness, isolation,
lifecycle and tool-path verification. V12, observer identity, stop proof,
default-disabled admission and absence of runtime downloads remain required.

This draft does not approve external distribution or production enablement.
GitHub CI remains disabled and is not run or awaited.
