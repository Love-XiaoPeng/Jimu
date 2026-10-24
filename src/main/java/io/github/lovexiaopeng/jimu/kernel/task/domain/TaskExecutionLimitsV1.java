package io.github.lovexiaopeng.jimu.kernel.task.domain;

/** 普通任务既有冻结准入政策；写入后不随运行配置改变。 */
public final class TaskExecutionLimitsV1 {
  public static final int GOAL_UTF8_BYTES = 8192;
  public static final int CAPABILITIES = 7;
  public static final int DEADLINE_SECONDS = 180;
  public static final int MODEL_REQUESTS = 8;
  public static final int TOOL_INVOCATIONS = 20;

  private TaskExecutionLimitsV1() {}
}
