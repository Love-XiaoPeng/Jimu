package io.github.lovexiaopeng.jimu.kernel.worker.api;

/** attempt 的停止和完成字符值，不能与任务终态或 native 进程 phase 合并。 */
public final class AttemptState {
  public static final String SUCCEEDED = "SUCCEEDED";
  public static final String STOPPED = "STOPPED";

  private AttemptState() {}
}
