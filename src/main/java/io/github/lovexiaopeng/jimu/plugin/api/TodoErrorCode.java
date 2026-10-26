package io.github.lovexiaopeng.jimu.plugin.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class TodoErrorCode {
  public static final String TODO_BINDING_UNSUPPORTED = "TODO_BINDING_UNSUPPORTED";
  public static final String TODO_COMMIT_REQUIRED = "TODO_COMMIT_REQUIRED";
  public static final String TODO_CONTENT_EXPIRED = "TODO_CONTENT_EXPIRED";
  public static final String TODO_EXECUTION_LIMIT = "TODO_EXECUTION_LIMIT";
  public static final String TODO_FEATURE_DISABLED = "TODO_FEATURE_DISABLED";
  public static final String TODO_NOT_AUTHORIZED = "TODO_NOT_AUTHORIZED";
  public static final String TODO_REVISION_CONFLICT = "TODO_REVISION_CONFLICT";
  public static final String TODO_STORAGE_LIMIT = "TODO_STORAGE_LIMIT";

  private TodoErrorCode() {}
}
