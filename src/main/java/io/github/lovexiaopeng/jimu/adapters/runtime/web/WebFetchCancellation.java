package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Cancellation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.CheckpointCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Reason;

/** Per fetch only. Cleanup cannot replace a cause or manufacture one after completion. */
final class WebFetchCancellation {
  private final long started = System.nanoTime();
  private Phase phase = Phase.ADMISSION;
  private Cancellation first;
  private boolean finished;

  synchronized void phase(Phase next) {
    phase = next;
  }

  synchronized void capture(Reason reason, CheckpointCode code, long checkpointStarted) {
    if (finished || first != null) return;
    long now = System.nanoTime();
    first =
        new Cancellation(
            reason,
            phase,
            (now - started) / 1_000_000,
            code,
            checkpointStarted == 0 ? 0 : (now - checkpointStarted) / 1_000_000);
  }

  synchronized Cancellation finish() {
    finished = true;
    return first;
  }

  static CheckpointCode code(RuntimeException error) {
    if (error instanceof TaskFailure failure) {
      try {
        var code = CheckpointCode.valueOf(failure.code());
        return code == CheckpointCode.NONE ? CheckpointCode.UNKNOWN : code;
      } catch (IllegalArgumentException ignored) {
        // External or future codes are never copied into diagnostic events.
      }
    }
    return CheckpointCode.UNKNOWN;
  }
}
