# Report admission time

New tasks reserve `task.report_time` once at admission using MySQL
`UTC_TIMESTAMP(6)`. This DATETIME stores UTC wall-clock fields; JDBC reads it as
`LocalDateTime` and the ledger interprets those fields as UTC. No JVM default
zone, JDBC connection zone or MySQL session zone is used to convert those fields.
This value is the report's fixed admission time, not its publication time.

The existing report context supplies the same persisted Instant to the model
instruction, deterministic composer and composition receipt. Idempotent submission,
control restart and worker recovery reuse it. No current clock is consulted when
restoring an existing report context.

This change affects only new admission writes. There is no migration, old DATETIME
reinterpretation, report regeneration, snapshot/pin/template change or backfill.
Existing reports with an incorrect Z label remain immutable and require a separate
erratum. Lease, deadline, heartbeat, live-fence and settlement SQL retain their
existing session-clock contract; this fix does not claim to correct the external
UTC representation of their historical DATETIME values.

`ReportTimeIT` runs `tools/p3_report_time_verify.py` on private MySQL 8.0.46 with
non-UTC session zones and multiple JVM zones. It checks the actual epoch,
composition/publication/download, fixed time through retries and recovery, and
preservation of a legacy row. Model and TLS endpoints are local substitutes only.
