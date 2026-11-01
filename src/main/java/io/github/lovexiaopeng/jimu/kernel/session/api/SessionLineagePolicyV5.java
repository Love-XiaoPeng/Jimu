package io.github.lovexiaopeng.jimu.kernel.session.api;

/** v5的不可热改策略值；只表示owner明确继续和受理时显式范围。 */
public final class SessionLineagePolicyV5 {
  public static final String CAPABILITIES = "session_event_read,session_search,session_trace";
  public static final String AUTHORITY = "explicit-terminal-closed-v5";
  public static final String POLICY = "owner-declared-continuation-v1";
  public static final String COVERAGE = "admission-scope";
  public static final int NODES = 9, EDGES = 8, DEPENDENCIES = 64;
  public static final int INPUT_BYTES = 1024, GENESIS_BYTES = 1024, GRAPH_BYTES = 16384;
  public static final int OUTPUT_BYTES = 8192, PROOF_READS = 256, PROOF_BYTES = 1048576;
  public static final long OPERATIONS = 65536;

  private SessionLineagePolicyV5() {}
}
