# Native bundle v2.0.0 — auditable rebuild

XIAO-47 explicitly replaces the historical npm rg pin with a separately versioned
Jimu rebuild. The old npm digest and its unclosed provenance remain historical
facts in `../source-inputs.json`, `../sbom.cdx.json`, and the XIAO-44 review.
They are not assertions about this build. No binary is committed to Git.

The rebuild uses unmodified ripgrep `3a612f88b8` (15.0.0), Rust 1.88.0,
the locked crate sources, musl 1.2.3, PCRE2 10.45 and upstream's musl jemalloc
allocator. Inputs are verified before extraction. Cargo runs frozen, locked and
offline; the local build is also executed in a network namespace without a
network interface. The C toolchain is extracted into a private sysroot.

`source-inputs.json` is independent of the old manifest. It pins the complete
offline source/lock/crate and private compiler/sysroot inputs by size and SHA256.
Only preparation may retrieve these URLs; neither builder nor application has a
download step. Retain the supplied `sources/` directory intact. It includes the
Rust distributions, matching Rust source, musl source, all locked resolution
inputs, bubblewrap source/.dsc/Ubuntu patches, and compiler/sysroot packages.

On Ubuntu 24.04 x86_64, with Python 3.12+, binutils, bash, perl, sed, awk, dpkg and
patch already available, build into two distinct new directories:

```text
unshare -n python3 tools/native_rebuild.py --inputs /operator/native-inputs --work /operator/new-build --output /operator/new-bundle
python3 tools/native_bundle_verify.py /operator/new-bundle --receipt-sha256 <independently recorded bundle.json SHA256>
```

`unshare -n` is a preparation supervisor boundary and requires permission to
create that private namespace. The builder itself needs no root installation.
It extracts compilers into its work directory, uses frozen/locked/offline Cargo
and a private registry replacement, and records commands, environment, exit codes,
compiler-artifact records (target versus build-only), the Cargo graph, GNU linker
maps, ELF inspection and the build-host package/tool identities. No global
toolchain, service or proxy configuration changes. A failed input check creates
neither work nor output. Failed builds keep their evidence and must be retried in
fresh directories. No bit identity across arbitrary build hosts is promised;
repository binary pins admit only the reviewed build. A differing rebuild is an
explicit new review/pin change, never an automatic fallback.

The actual rg closure includes the compiled target crates, Rust's linked standard
libraries, musl 1.2.3, PCRE2 10.45 and jemalloc 5.3.0-e13ca993. Build-only crate
records are labelled separately; the uncompiled lock entries are offline
resolution inputs, not claimed linked components. `evidence/rg.map` binds archive
members to the binary; `notices/Rust-COPYRIGHT-library.html` supplies the official
Rust library in-tree attribution. The exact linked std rlibs are recorded in
`evidence/rust-std-units.json`; the 11 external std-crate sources are pinned
separately, with their own licences/notices. PCRE2 is the actual pcre2-sys 0.2.10
vendored source, including SLJIT 0.95 and its BSD notice. `pcre2_match.c` differs
from the PCRE2 10.45 tag; the complete difference and per-file comparison are
retained in `evidence/pcre2-vendor-diff.patch` and `pcre2-source-comparison.json`.
The canonical PCRE2 licence comes from that pinned tag, supplemented by the actual
vendored-source copyright headers. Generated headers/tables and SLJIT are bound
directly to the crate input, not misrepresented as tag-identical. Jemalloc's
embedded COPYING is included. The old Microsoft vendor patch is not applied.

Bubblewrap `0.9.0-1ubuntu0.3+jimu.1` is rebuilt from the original Ubuntu .dsc pair,
with its two patches applied in `debian/patches/series` order. The receipt records
the configuration and complete commands. Its executable uses the same four C
translation units as upstream Meson, SELinux >=2.3 enabled and require_userns=false;
it is PIE with RELRO/BIND_NOW, has no setuid installation, and is a separate
LGPL-2.0-or-later executable. Source, config, patch series, Ubuntu packaging and
the recipe are supplied; this is a Jimu rebuild, not a claim to reproduce Ubuntu's
vendor binary. Both binary pins change explicitly. The launcher is rebuilt from
the adjacent first-party C source, with a configured exact hash as before.

The C executables dynamically use host glibc; bwrap also uses host libcap and
libselinux, whose dynamic dependency is host PCRE2. These system libraries are
prerequisites, not copied into the search namespace or redistributed in `bin/`.
The tested package versions and exact loaded files are recorded with the bundle.
Host-package copyrights are in `notices/`; the C link maps also identify any
GCC runtime/glibc startup objects. Keep the GCC Runtime Library Exception and
applicable LGPL/system-library notices; npm MIT does not cover this closure.

Keep `THIRD_PARTY_NOTICES.txt`, `notices/`, the corresponding sources and build
records together with the binaries. LGPL modification/replacement is permitted:
extract `sources/bwrap.orig.tar.xz`, overlay `sources/bwrap.debian.tar.xz`, apply
the series and use the recorded config/compile command to rebuild or modify the
separate executable. Point a rebuilt application at the replacement and update
`NativeRunner.BWRAP_HASH` only through an explicit reviewed pin change; configure
the replacement launcher's digest when rebuilding it. There is no signing key
or remote activation requirement. Integrity, namespace readiness, V12/observer
checks and stop proof still apply to modified builds. Never replace a live bundle
in place; drain its processes first and retain outstanding UNKNOWN/legacy receipts.

The upstream rg version, regex engine/features and task plugin/schema pins stay
the same. The artifact identity changes from npm to this unpatched source build;
compiler/profile and vendor hardening-patch differences require directed tool,
isolation and lifecycle validation. Old bundles fail the new executable pins.
Existing immutable result pages and task snapshots are not rewritten or rerun;
the runtime switch does not authorize replay of an unknown invocation.

Default-disabled admission, V12, observer binding and all stop/cleanup conditions
remain unchanged. The first-party Jimu launcher source is supplied for audit and
rebuild; this change does not grant a repository-wide external distribution
license. Full product/QA acceptance is a separate recorded result.

This draft does not approve external distribution or production enablement.
GitHub CI remains disabled and is not run or awaited.
