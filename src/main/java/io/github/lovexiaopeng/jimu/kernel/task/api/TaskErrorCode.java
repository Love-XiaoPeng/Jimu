package io.github.lovexiaopeng.jimu.kernel.task.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class TaskErrorCode {
  public static final String ALREADY_RESPONDED = "ALREADY_RESPONDED";
  public static final String CHECKPOINT_INCOMPLETE = "CHECKPOINT_INCOMPLETE";
  public static final String CHECKPOINT_MEDIA_PURGED = "CHECKPOINT_MEDIA_PURGED";
  public static final String CREDENTIAL_UNAVAILABLE = "CREDENTIAL_UNAVAILABLE";
  public static final String CURSOR_EXPIRED = "CURSOR_EXPIRED";
  public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
  public static final String EFFECT_OUTCOME_UNKNOWN = "EFFECT_OUTCOME_UNKNOWN";
  public static final String FUTURE_CURSOR = "FUTURE_CURSOR";
  public static final String INPUT_INVALID = "INPUT_INVALID";
  public static final String INVALID_BUDGET = "INVALID_BUDGET";
  public static final String INVALID_CAPABILITIES = "INVALID_CAPABILITIES";
  public static final String INVALID_CURSOR = "INVALID_CURSOR";
  public static final String PAYLOAD_VERSION_UNSUPPORTED = "PAYLOAD_VERSION_UNSUPPORTED";
  public static final String PLUGIN_PIN_MISMATCH = "PLUGIN_PIN_MISMATCH";
  public static final String PLUGIN_SNAPSHOT_UNAVAILABLE = "PLUGIN_SNAPSHOT_UNAVAILABLE";
  public static final String PUBLIC_WEB_NOT_CONFIGURED = "PUBLIC_WEB_NOT_CONFIGURED";
  public static final String QUEUE_LIMIT = "QUEUE_LIMIT";
  public static final String SNAPSHOT_CORRUPT = "SNAPSHOT_CORRUPT";
  public static final String SUMMARY_TOO_LARGE = "SUMMARY_TOO_LARGE";
  public static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";
  public static final String WEB_OBSERVATION_BINDING_REJECTED = "WEB_OBSERVATION_BINDING_REJECTED";
  public static final String WEB_RESULT_PROOF_INVALID = "WEB_RESULT_PROOF_INVALID";
  public static final String WEB_RESULT_PROOF_MISSING = "WEB_RESULT_PROOF_MISSING";

  public static final String CANCELLED = "CANCELLED";

  public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";

  public static final String EXECUTION_FAILED = "EXECUTION_FAILED";

  public static final String TASK_COMPLETED = "TASK_COMPLETED";

  private TaskErrorCode() {}
}
