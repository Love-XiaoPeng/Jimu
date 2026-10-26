package io.github.lovexiaopeng.jimu.plugin.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class FileErrorCode {
  public static final String FS_AMBIGUOUS_EDIT = "FS_AMBIGUOUS_EDIT";
  public static final String FS_BINDING_UNPROVEN = "FS_BINDING_UNPROVEN";
  public static final String FS_EDIT_NOT_FOUND = "FS_EDIT_NOT_FOUND";
  public static final String FS_NAMESPACE_CONFLICT = "FS_NAMESPACE_CONFLICT";
  public static final String FS_NOT_DIRECTORY = "FS_NOT_DIRECTORY";
  public static final String FS_NOT_FOUND = "FS_NOT_FOUND";
  public static final String FS_NOT_OBSERVED = "FS_NOT_OBSERVED";
  public static final String FS_NOT_TEXT = "FS_NOT_TEXT";
  public static final String FS_OPERATION_MISMATCH = "FS_OPERATION_MISMATCH";
  public static final String FS_PATH_DENIED = "FS_PATH_DENIED";
  public static final String FS_READ_ONLY = "FS_READ_ONLY";
  public static final String FS_STALE_VERSION = "FS_STALE_VERSION";
  public static final String WORKSPACE_LIMIT = "WORKSPACE_LIMIT";
  public static final String WORKSPACE_NOT_CONFIGURED = "WORKSPACE_NOT_CONFIGURED";
  public static final String WORKSPACE_UNAVAILABLE = "WORKSPACE_UNAVAILABLE";

  private FileErrorCode() {}
}
