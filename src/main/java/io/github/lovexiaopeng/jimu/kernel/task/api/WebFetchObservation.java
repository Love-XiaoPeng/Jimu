package io.github.lovexiaopeng.jimu.kernel.task.api;

/** Fixed metadata only. Never retain exception messages, URLs, headers or page bodies here. */
public record WebFetchObservation(Phase phase, Failure failure, Cancellation cancellation) {
  public WebFetchObservation(Phase phase, Failure failure) {
    this(phase, failure, null);
  }

  /** First observed cause, not a claim about an unobserved upstream root cause. */
  public record Cancellation(
      Reason reason,
      Phase phase,
      long elapsedMillis,
      CheckpointCode checkpointCode,
      long checkpointMillis) {
    public Cancellation {
      java.util.Objects.requireNonNull(reason);
      java.util.Objects.requireNonNull(phase);
      java.util.Objects.requireNonNull(checkpointCode);
      if (elapsedMillis < 0 || checkpointMillis < 0) throw new IllegalArgumentException("elapsed");
    }
  }

  public enum Reason {
    ELAPSED_LIMIT,
    FETCH_DEADLINE,
    CALLER_INTERRUPT,
    THREAD_INTERRUPT,
    CHECKPOINT_REJECTED,
    UNKNOWN
  }

  public enum CheckpointCode {
    NONE,
    FENCE_REJECTED,
    CANCEL_REQUESTED,
    DEADLINE_EXCEEDED,
    SNAPSHOT_CORRUPT,
    CAPABILITY_REVOKED,
    BUSINESS_POLICY_CONFLICT,
    BUSINESS_POLICY_UNAVAILABLE,
    EVALUATION_SCOPE_UNAVAILABLE,
    EVALUATION_SCOPE_MISMATCH,
    EVALUATION_SCOPE_NOT_ALLOWED,
    EVALUATION_SCOPE_LEGACY_UNBOUND,
    EVALUATION_MATERIAL_MISMATCH,
    MODEL_BUDGET_EXCEEDED,
    MODEL_COST_BOUND_EXCEEDED,
    MODEL_USAGE_UNKNOWN,
    UNKNOWN
  }

  public enum Phase {
    ADMISSION,
    DNS,
    CONNECT_TLS,
    HEADERS,
    BODY,
    PROJECTION,
    REDIRECT
  }

  public enum Failure {
    NONE,
    TIMEOUT,
    DNS,
    TLS,
    IO,
    CANCELLED,
    POLICY,
    INTERNAL
  }
}
