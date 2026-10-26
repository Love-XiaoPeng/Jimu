package io.github.lovexiaopeng.jimu.plugin.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class SearchErrorCode {
  public static final String SEARCH_CURSOR_INVALID = "SEARCH_CURSOR_INVALID";
  public static final String SEARCH_FAILED = "SEARCH_FAILED";
  public static final String SEARCH_ISOLATION_UNAVAILABLE = "SEARCH_ISOLATION_UNAVAILABLE";
  public static final String SEARCH_NOT_CONFIGURED = "SEARCH_NOT_CONFIGURED";
  public static final String SEARCH_PROCESS_UNPROVEN = "SEARCH_PROCESS_UNPROVEN";
  public static final String SEARCH_RESULT_LIMIT = "SEARCH_RESULT_LIMIT";
  public static final String SEARCH_WORKSPACE_LIMIT = "SEARCH_WORKSPACE_LIMIT";
  public static final String SEARCH_WORKSPACE_UNREPRESENTABLE = "SEARCH_WORKSPACE_UNREPRESENTABLE";

  public static final String SEARCH_RAW_OUTPUT_OVERFLOW = "SEARCH_RAW_OUTPUT_OVERFLOW";

  public static final String SEARCH_INVALID_PATTERN = "SEARCH_INVALID_PATTERN";

  private SearchErrorCode() {}
}
