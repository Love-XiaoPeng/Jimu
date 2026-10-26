package io.github.lovexiaopeng.jimu.kernel.mcp.api;

/** MCP 只读资源 v1 的冻结协议和资源限额；页面、快照、正文与 wire 分别计量。 */
public final class McpResourcePolicyV1 {
  public static final String PROTOCOL_VERSION = "2025-06-18";
  public static final int SNAPSHOT_BYTES = 1048576;
  public static final int WIRE_STRING_UTF16_UNITS = 1048576;
  public static final int WIRE_BYTES = 1048576;
  public static final int REQUEST_BYTES = 8192;
  public static final int PROFILE_BYTES = 8192;
  public static final int PAGE_BYTES = 65536;
  public static final int BINDING_BYTES = 65536;
  public static final int READ_OUTPUT_BYTES = 65536;
  public static final int LIST_ENTRIES = 100;
  public static final int RAW_LIST_ENTRIES = 1000;
  public static final int URI_UTF16_UNITS = 2048;
  public static final int DESCRIPTION_UTF16_UNITS = 2048;
  public static final int MIME_UTF16_UNITS = 128;
  public static final int CURSOR_UTF16_UNITS = 1024;
  public static final int DECODED_RESOURCE_BYTES = 262144;
  public static final int BASE64_RESOURCE_UTF16_UNITS = 349528;
  public static final int PREVIEW_BYTES = 16384;
  public static final int CURSOR_METADATA_BYTES = 96;
  public static final int TASK_SNAPSHOTS = 64;
  public static final int CLEANUP_TASK_SNAPSHOTS = 20;

  private McpResourcePolicyV1() {}
}
