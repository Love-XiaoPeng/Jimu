package io.github.lovexiaopeng.jimu.kernel.invocation.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class InvocationErrorCode {
  public static final String INVOCATION_MISMATCH = "INVOCATION_MISMATCH";
  public static final String INVOCATION_STATE = "INVOCATION_STATE";

  private InvocationErrorCode() {}
}
