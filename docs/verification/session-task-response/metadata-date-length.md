The two persistence metadata scalar paths keep the frozen
`uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` encoding. `Z` is literal; there is no timezone
conversion. Valid `LocalDateTime` years 0..9999 have exactly 27 ASCII bytes,
including year zero and fractions truncated to six digits without rounding.

Each path requests seven existing operation units before reading the year:
getter, primitive store, two comparisons, boolean merge, dispatch and return or
fallback handoff. Both comparisons execute. This removes the ordinary-year
formatter call, output String, UTF8 character scan and 2048-byte temporary
reservation; HistoryBatch also avoids its per-call formatter construction.
There was no whole-string UTF8 byte array here. The static formatter remains.
Negative and wide years keep the complete original fallback plus the seven new
units: -1 remains 28 bytes / 62 operations, 10000 remains 29 / 63; ordinary years
remain 27 bytes / 7 operations. Storage accounting, SQL date type validation,
NULL presence, original first-failure and batch lifetime checks remain in place.

`metadata-date-accounting.csv`, `metadata-date-objects.csv` and
`metadata-date-callgraph.json` record the actual requests, objects and callers.
They are inventories, not whole-heap/native/socket or A12 proof. The fallback
still creates its original temporary key before retain; this change only avoids
that allocation on the fast path. Other scalar helpers, raw proof/digest/schema
and response date encoding are outside this change.

`SessionMetadataDateLengthTest` covers formatter equivalence at year/fraction
boundaries, year-zero leap day, original Boolean differences and invalid types,
operation N-1/N/N+1 with sticky rejection, scratch 2047/2048/2049,
locale ROOT/ar-EG/th-TH/ja, no-query calls, closed batches and preserved
cause/suppressed failures. Real SQL terminal/response acceptance remains a
separate requirement; local savings do not imply complete business acceptance.
