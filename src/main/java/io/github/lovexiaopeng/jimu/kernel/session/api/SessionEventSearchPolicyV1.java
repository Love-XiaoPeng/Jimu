package io.github.lovexiaopeng.jimu.kernel.session.api;

/** ADR-0012 旧公开事件搜索政策；独立于 ADR-0014 的跨会话搜索预算，序列化限额包含 JSON 转义。 */
public final class SessionEventSearchPolicyV1 {
  public static final int COMPARISONS = 8388608;
  public static final int DEADLINE_CHECK_MASK = 1023;
  public static final int SNIPPET_BEFORE_CODEPOINTS = 80;
  public static final int SNIPPET_WINDOW_CODEPOINTS = 240;
  public static final int SNIPPET_JSON_BYTES = 1536;
  public static final int HIT_JSON_BYTES = 2048;
  public static final int CANDIDATES = 128;
  public static final int RETURNED_HITS = 16;
  public static final int INPUT_BYTES = 2048;
  public static final int QUERY_CODEPOINTS = 256;
  public static final int QUERY_UTF8_BYTES = 1024;
  public static final int OUTPUT_BYTES = 32768;
  public static final int OUTPUT_VERSION = 1;
  public static final int DEFAULT_HISTORY_VERSION = 2;
  public static final String CONTENT_POLICY = "public-v1";
  public static final String MATCH_POLICY = "public-content-literal-v1";
  public static final String ORDERING = "occurrences-desc,length-asc,time-desc,seq-desc";

  private SessionEventSearchPolicyV1() {}
}
