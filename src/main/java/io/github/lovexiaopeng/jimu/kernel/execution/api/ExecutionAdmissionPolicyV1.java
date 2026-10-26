package io.github.lovexiaopeng.jimu.kernel.execution.api;

/** 执行检查点既有冻结字节上限；本次仅迁移规则所有权，不热改旧任务。 */
public final class ExecutionAdmissionPolicyV1 {
  public static final int QUESTION_REQUEST_BYTES = 65536;
  public static final int TODO_CHECKPOINT_BYTES = 131072;
  public static final int TODO_REQUEST_BYTES = 65536;
  public static final int DIRECTORY_CHECKPOINT_BYTES = 131072;
  public static final int DIRECTORY_REQUEST_BYTES = 65536;
  public static final int DIRECTORY_CHECKPOINT_VERSION = 2;
  public static final int SUMMARY_BYTES = 8192;
  public static final int MEDIA_RECORD_VERSION = 3;

  private ExecutionAdmissionPolicyV1() {}
}
