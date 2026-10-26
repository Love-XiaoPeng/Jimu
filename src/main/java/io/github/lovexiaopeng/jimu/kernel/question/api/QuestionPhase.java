package io.github.lovexiaopeng.jimu.kernel.question.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class QuestionPhase {
  public static final String CLOSED = "CLOSED";
  public static final String CONTINUED = "CONTINUED";
  public static final String OPEN = "OPEN";

  private QuestionPhase() {}
}
