## Cancellation observation increment

New fetches retain the first locally observed cancellation/rejection cause, its last confirmed
phase and monotonic elapsed milliseconds. The callback measures a rejected checkpoint with a
fixed allowlisted code and monotonic duration. Opaque transport cancellation and unrecognized
checkpoint codes explicitly remain UNKNOWN. These facts identify the observed boundary, not an
unobserved upstream root cause. Cleanup cannot invent or replace a cause.

`web-cancel:<invocation>:<original-fence>` identifies a three-event atomic receipt:

- `web.fetch_cancelled`: fixed phase and reason.
- `web.cancel_elapsed_ms`: elapsed milliseconds from fetch entry.
- `web.checkpoint_rejected`: fixed checkpoint code and elapsed milliseconds; NONE:0 means
  no checkpoint rejection was observed.

The diagnostic writer validates the invocation's original tenant/task/attempt/worker/fence
binding. It can append after that original lease expires or recovery marks the invocation
OUTCOME_UNKNOWN. This exception to the live-fence check only permits diagnostics: it cannot authorize
tool execution, supply a result, release a reservation or reconcile UNKNOWN. The task row lock
and stable fact key make the first receipt durable and prevent later writers replacing it.
The existing fixed failure event is retained alongside the receipt. Observations without the
new receipt continue using the live fence. Existing records are not
backfilled; tool pins, snapshots and plugin contracts are unchanged. No migration is needed.

Validation is restricted to the affected paths: the original five injected clock/transport
substitutes (including the real 30-second wait), 100 first-cause races, fixed-code canaries,
and private MySQL/local TLS/model substitutes for cancellation, expired-fence persistence,
foreign-fence rejection, process restart and recovery without replay or settlement.

Run the focused repository gates with Java 21 and a stable Linux Java 21 in `JIMU_IT_JAVA`:

```text
mvnw -o -B -ntp -Dtest=WebFetchCancellationTest,CancelBoundaryDiagnosticTest,WebFetchObservationTest,PublicWebCancellationRaceTest,PublicWebReaderTest,PinnedHttpsTransportTest,PackageBoundariesTest -Dit.test=WebFetchCancellationIT verify
```

This executes build/format/import/dependency/static gates and the selected integration; it is
not a claim that the unrelated P2/full integration matrix was rerun. No production service,
configuration, database, key, model or webpage is accessed. Historic UNKNOWN calls remain
unattributed. Database unavailability can prevent diagnostic persistence; no receipt implies
no conclusion about the cause and never authorizes replay.
