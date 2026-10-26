package io.github.lovexiaopeng.jimu.kernel.session.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class SessionGrantState {
  public static final String ACTIVE = "ACTIVE";

  private SessionGrantState() {}
}
