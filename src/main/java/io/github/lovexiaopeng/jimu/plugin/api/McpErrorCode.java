package io.github.lovexiaopeng.jimu.plugin.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class McpErrorCode {
  public static final String MCP_COMMIT_REQUIRED = "MCP_COMMIT_REQUIRED";
  public static final String MCP_CURSOR_INVALID = "MCP_CURSOR_INVALID";
  public static final String MCP_LIMIT_EXCEEDED = "MCP_LIMIT_EXCEEDED";
  public static final String MCP_NOT_CONFIGURED = "MCP_NOT_CONFIGURED";
  public static final String MCP_PROFILE_UNSUPPORTED = "MCP_PROFILE_UNSUPPORTED";
  public static final String MCP_PROTOCOL_INVALID = "MCP_PROTOCOL_INVALID";
  public static final String MCP_SERVER_NOT_AUTHORIZED = "MCP_SERVER_NOT_AUTHORIZED";
  public static final String MCP_SNAPSHOT_MISMATCH = "MCP_SNAPSHOT_MISMATCH";
  public static final String MCP_UNAVAILABLE = "MCP_UNAVAILABLE";
  public static final String MCP_UPSTREAM_ERROR = "MCP_UPSTREAM_ERROR";
  public static final String MCP_URI_NOT_AUTHORIZED = "MCP_URI_NOT_AUTHORIZED";

  public static final String MCP_RESOURCE_NOT_FOUND = "MCP_RESOURCE_NOT_FOUND";

  private McpErrorCode() {}
}
