package io.github.lovexiaopeng.jimu.kernel.budget.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class ReservationState {
  public static final String HELD_UNKNOWN = "HELD_UNKNOWN";
  public static final String RELEASED = "RELEASED";
  public static final String RESERVED = "RESERVED";
  public static final String SETTLED = "SETTLED";

  private ReservationState() {}
}
