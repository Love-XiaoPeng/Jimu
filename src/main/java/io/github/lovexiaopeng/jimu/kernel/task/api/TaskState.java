package io.github.lovexiaopeng.jimu.kernel.task.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class TaskState {
  public static final String CANCELLED = "CANCELLED";
  public static final String FAILED = "FAILED";
  public static final String NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";
  public static final String QUEUED = "QUEUED";
  public static final String RESUME_READY = "RESUME_READY";
  public static final String RUNNING = "RUNNING";
  public static final String SUCCEEDED = "SUCCEEDED";
  public static final String WAITING_USER = "WAITING_USER";

  public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";

  private TaskState() {}
}
