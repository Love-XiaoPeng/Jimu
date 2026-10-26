package io.github.lovexiaopeng.jimu.kernel.session.api;

/** 既有公开会话 v1–v4 共有的冻结存储与事件政策；保留 SQL 约束和计账标量字节。 */
public final class SessionHistoryPolicyV1 {
  public static final int TASK_RESERVE_BYTES = 8388608;
  public static final int OPEN_METADATA_BYTES = 61440;
  public static final int CLOSED_METADATA_BYTES = 65536;
  public static final int ACCOUNTING_ROUNDS = 4;
  public static final int ROW_HEADER_BYTES = 32;
  public static final int FIELD_HEADER_BYTES = 8;
  public static final int CLEANUP_BASE_BYTES = 1024;
  public static final int TASK_CLEANUP_BYTES = 768;
  public static final int STEP_CLEANUP_BYTES = 512;
  public static final int DISPATCH_CLEANUP_BYTES = 256;
  public static final int EVENT_BYTES = 16384;
  public static final int READ_INPUT_BYTES = 1024;
  public static final int READ_OUTPUT_BYTES = 32768;
  public static final int CONTEXT_BYTES = 4096;
  public static final int OWNER_BYTES = 4096;
  public static final int PUBLIC_EVENTS = 128;
  public static final int LAST_PUBLIC_SEQ = 127;
  public static final int READ_NEIGHBORS = 4;
  public static final int PUBLIC_PREFIX_CODEPOINTS = 256;
  public static final int PUBLIC_PREFIX_UTF8_BYTES = 1024;
  public static final int RESPONSE_BYTES = 16384;
  public static final int RESPONSE_CALLS = 4;
  public static final int REQUEST_SNAPSHOT_BYTES = 131072;
  public static final int WIRE_REQUEST_BYTES = 65536;
  public static final long QUERY_NANOS = 2_000_000_000L;

  public static final int NONTERMINAL_EVENTS = 126;

  private SessionHistoryPolicyV1() {}
}
