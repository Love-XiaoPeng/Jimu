package io.github.lovexiaopeng.jimu.kernel.directory.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class DirectoryErrorCode {
  public static final String DIRECTORY_BINDING_UNSUPPORTED = "DIRECTORY_BINDING_UNSUPPORTED";
  public static final String DIRECTORY_COMMIT_REQUIRED = "DIRECTORY_COMMIT_REQUIRED";
  public static final String DIRECTORY_CONTENT_EXPIRED = "DIRECTORY_CONTENT_EXPIRED";
  public static final String DIRECTORY_EXECUTION_LIMIT = "DIRECTORY_EXECUTION_LIMIT";
  public static final String DIRECTORY_FEATURE_DISABLED = "DIRECTORY_FEATURE_DISABLED";
  public static final String DIRECTORY_NOT_AUTHORIZED = "DIRECTORY_NOT_AUTHORIZED";
  public static final String DIRECTORY_REVISION_CONFLICT = "DIRECTORY_REVISION_CONFLICT";
  public static final String DIRECTORY_SLOT_ORDER = "DIRECTORY_SLOT_ORDER";
  public static final String DIRECTORY_STORAGE_LIMIT = "DIRECTORY_STORAGE_LIMIT";

  private DirectoryErrorCode() {}
}
