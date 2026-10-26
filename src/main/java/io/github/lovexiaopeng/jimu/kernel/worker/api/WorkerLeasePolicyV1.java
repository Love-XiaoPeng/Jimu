package io.github.lovexiaopeng.jimu.kernel.worker.api;

/** 既有 worker 调度与租约配对；数据库 lease 固定 30 秒，心跳固定 10 秒，本轮不可调。 */
public final class WorkerLeasePolicyV1 {
  public static final int POLL_MILLIS = 100;
  public static final int HEARTBEAT_SECONDS = 10;
  public static final int MAINTENANCE_SECONDS = 10;

  private WorkerLeasePolicyV1() {}
}
