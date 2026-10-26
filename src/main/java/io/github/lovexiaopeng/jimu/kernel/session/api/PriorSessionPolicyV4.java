package io.github.lovexiaopeng.jimu.kernel.session.api;

/** ADR-0014 跨会话搜索的累计预算；与旧事件搜索分别冻结，保留原字节和码点计量。 */
public final class PriorSessionPolicyV4 {
  public static final int COMPARISONS = 16777216;
  public static final int OPERATIONS = 2097152;
  public static final int DEADLINE_CHECK_MASK = 1023;
  public static final int SNIPPET_BEFORE_CODEPOINTS = 80;
  public static final int SNIPPET_WINDOW_CODEPOINTS = 240;
  public static final int SNIPPET_JSON_BYTES = 1536;
  public static final int HIT_JSON_BYTES = 2048;
  public static final int SOURCES = 8;
  public static final int RETURNED_SOURCES = 4;
  public static final int ADMISSION_BYTES = 16384;
  public static final int INPUT_BYTES = 4096;
  public static final int GOAL_UTF8_BYTES = 8192;
  public static final int QUERY_CODEPOINTS = 256;
  public static final int QUERY_UTF8_BYTES = 1024;
  public static final int OUTPUT_BYTES = 16384;
  public static final int AUTHORITY_BYTES = 4096;
  public static final int REFS_BYTES = 2048;
  public static final int PROOF_READS = 4096;
  public static final int PROOF_BYTES = 16777216;
  public static final int LIVE_SCRATCH_BYTES = 16777216;
  public static final int SCRATCH_BATCH_COPIES = 3;
  public static final int EVENTS = 1024;
  public static final int EVENT_BODY_BYTES = 4194304;
  public static final String BOUNDARY = "scoped-admission";
  public static final String CONTENT_POLICY = "public-v1";
  public static final String MATCH_POLICY = "public-content-literal-v1";
  public static final String ORDERING =
      "occurrences-desc,length-asc,time-desc,session-id-asc,seq-desc";

  private PriorSessionPolicyV4() {}
}
