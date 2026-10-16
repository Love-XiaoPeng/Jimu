# P3 stage: worker convergence and accepted tool snapshots

This stage extends the accepted public fetch adapter without selecting it for runtime traffic.
Public egress and real paid model/search requests remain disabled. Workspace CAS, report
production, the five-tool agent assembly and report download evidence belong to the next stage;
this stage does not establish P3 acceptance.

## Worker lifecycle

`OperationThreads` registers work before starting its virtual thread and removes it only when
the operation's Runnable exits. Cancelling a `FutureTask` does not remove the registration.
`PublicWebReader` accepts this tracker explicitly; future worker assembly must pass the same
worker-scoped instance used by `WorkerLoop` (the standalone adapter constructor owns its own).

The worker keeps renewal running while awaiting actual operation convergence. It neither marks
the attempt quiescent nor claims another task while an operation remains live. If shutdown
interrupts this wait, the worker leaves quiescence unknown and permanently stops admission in
that process. Close reports the incomplete drain. The existing supervisor still resolves this
state through registered PID plus Linux start ticks, with cancellation grace enforced on that
exact process. TTL/lease expiry and cancelled futures remain insufficient evidence of exit.

These semantics deliberately allow an uninterruptible resolver to retain a worker until it
exits or the supervisor stops the process. R4's closed token continues to prevent a late DNS
answer from starting transport. No new daemon, kill-by-name operation or external privilege is
introduced.

## Tool snapshot and schema V5

Acceptance stores a versioned `tools_snapshot` and SHA-256 in the same transaction as the task,
model snapshot, queue reservation and accepted event. It records each capability's plugin
version, output Schema digest and installation epoch under the existing installation locks.
An idempotent retry returns the original task before consulting current installations.

Before any model dispatch or checkpoint restore, the worker verifies snapshot integrity and
availability of its packaged implementation. Missing implementations fail with
`PLUGIN_PIN_MISMATCH`. Each response invocation copies its pin from the accepted task snapshot;
admission checks the task snapshot, invocation and current installation before returning cached
output or causing an effect. Current access revocation and existing installation drift rejection
remain authoritative. This stage retains the historical fixture runtime (`web_fetch` 0.2.0);
a 0.3.0 task cannot fall back to 0.2.0 on that worker.

V1–V4 migrations remain unchanged. V5 reconstructs a historical task snapshot only when every
capability has unanimous invocation receipts for version/digest/epoch. Historical unstarted or
ambiguous tasks retain an unknown snapshot and fail with `PLUGIN_SNAPSHOT_UNAVAILABLE` before
any model request. They are never rebound to the current installation. Completed tasks,
events and downloads keep their existing read formats. Upgrade with admission stopped and
dedicated workers converged; mixed V4/V5 role operation is unsupported by the strict schema gate.
Do not use the test-only destructive downgrade as an operational rollback.

## Default verification

`WorkerLoopTest` covers normal return, cancellation and interrupted drain with an uninterruptible
child. The public reader regression checks that a deadline returns while the resolver remains
registered, late transport count stays zero, and the registration clears only after actual exit.
The existing local TLS tests and R4 success/cancel races remain in the default unit suite.

`WorkerSnapshotIT` runs `tools/p3_lifecycle_verify.py` by default against isolated real MySQL:
0.3.0 acceptance without a step, control restart/idempotency, unavailable worker before network,
corrupt/future snapshots, actual V4 cache migration and recovery without repeated fetch, and
unknown legacy queued rejection. It exports raw snapshots, fixture request counts, SQL and
cleanup receipts. The complete P2 and model protocol/budget gates remain enabled; the P2 V2
upgrade case also removes V5 in its isolated fixture before exercising all migrations again.
