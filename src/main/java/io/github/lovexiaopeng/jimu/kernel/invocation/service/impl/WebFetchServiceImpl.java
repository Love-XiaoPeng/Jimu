package io.github.lovexiaopeng.jimu.kernel.invocation.service.impl;

import io.github.lovexiaopeng.jimu.kernel.invocation.port.WebFetchRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.service.WebFetchService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;

/** Original transaction, qualifications, first failure, and diagnostic/receipt event order. */
public final class WebFetchServiceImpl implements WebFetchService {
  private final WebFetchRecords records;

  public WebFetchServiceImpl(WebFetchRecords records) {
    this.records = records;
  }

  @Override
  public void observe(Lease lease, String invocationId, WebFetchObservation observation) {
    records.transaction(
        () -> {
          if (observation.cancellation() != null) {
            observeCancellation(lease, invocationId, observation);
            return null;
          }
          var task = records.fenced(lease, false);
          var invocation = records.invocation(lease, invocationId);
          if (!"web_fetch".equals(invocation.tool())
              || !"0.3.0".equals(invocation.version())
              || !"RUNNING".equals(invocation.state()))
            throw new TaskFailure(409, "INVOCATION_STATE");
          boolean failed = observation.failure() != WebFetchObservation.Failure.NONE;
          task.event(
              (failed ? "web-failure:" : "web-phase:") + invocationId + ":" + observation.phase(),
              failed ? "web.fetch_failed" : "web.fetch_phase",
              observation.phase() + "_" + observation.failure());
          return null;
        });
  }

  /** An original expired attempt authorizes diagnostics only, without a live fence check. */
  private void observeCancellation(
      Lease lease, String invocationId, WebFetchObservation observation) {
    var cancellation = observation.cancellation();
    var task = records.lockDiagnosticTask(lease);
    if (records.diagnosticBindings(lease, invocationId) != 1)
      throw new TaskFailure(409, "WEB_OBSERVATION_BINDING_REJECTED");
    String fact = "web-cancel:" + invocationId + ":" + lease.fence();
    if (records.cancellationRecorded(lease, fact)) return;
    task.event(fact, "web.fetch_cancelled", cancellation.phase() + "_" + cancellation.reason());
    task.event(
        fact + ":elapsed", "web.cancel_elapsed_ms", Long.toString(cancellation.elapsedMillis()));
    task.event(
        fact + ":checkpoint",
        "web.checkpoint_rejected",
        cancellation.checkpointCode() + ":" + cancellation.checkpointMillis());
    task.event(
        "web-failure:" + invocationId + ":" + observation.phase(),
        "web.fetch_failed",
        observation.phase() + "_" + observation.failure());
  }

  @Override
  public void preserve(Lease lease, String invocationId, String output) {
    records.transaction(
        () -> {
          var task = records.fenced(lease, false);
          var invocation = records.invocation(lease, invocationId);
          if (!"RUNNING".equals(invocation.state())) throw new TaskFailure(409, "INVOCATION_STATE");
          var step = records.step(lease, invocation.stepId());
          String digest = records.proof(task, step, invocation, output);
          var existing = records.receipts(lease, "web-result:" + invocationId);
          if (!existing.isEmpty()) {
            if (!digest.equals(existing.getFirst().code())
                || !output.equals(invocation.output())
                || !records.hash(output).equals(invocation.outputDigest()))
              throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
            return null;
          }
          if (invocation.output() != null || invocation.outputDigest() != null)
            throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
          records.preserve(lease, invocationId, output, records.hash(output));
          task.event("web-result:" + invocationId, "web.result_preserved", digest);
          return null;
        });
  }
}
