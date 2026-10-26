package io.github.lovexiaopeng.jimu.kernel.model.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class DispatchState {
  public static final String DISPATCHED = "DISPATCHED";

  public static final String BOUND_EXCEEDED = "BOUND_EXCEEDED";

  public static final String USAGE_UNKNOWN = "USAGE_UNKNOWN";

  public static final String RESPONDED = "RESPONDED";

  private DispatchState() {}
}
