package io.github.lovexiaopeng.jimu.kernel.todo.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class TodoContentState {
  public static final String PRESENT = "PRESENT";
  public static final String PURGED = "PURGED";

  private TodoContentState() {}
}
