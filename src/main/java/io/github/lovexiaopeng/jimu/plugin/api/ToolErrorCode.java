package io.github.lovexiaopeng.jimu.plugin.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class ToolErrorCode {
  public static final String TOOL_BUDGET_EXCEEDED = "TOOL_BUDGET_EXCEEDED";
  public static final String TOOL_INPUT_INVALID = "TOOL_INPUT_INVALID";
  public static final String TOOL_NOT_AUTHORIZED = "TOOL_NOT_AUTHORIZED";
  public static final String TOOL_OUTPUT_INVALID = "TOOL_OUTPUT_INVALID";
  public static final String TOOL_OUTPUT_TOO_LARGE = "TOOL_OUTPUT_TOO_LARGE";

  private ToolErrorCode() {}
}
