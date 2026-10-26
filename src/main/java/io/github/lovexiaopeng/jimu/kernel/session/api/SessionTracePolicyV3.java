package io.github.lovexiaopeng.jimu.kernel.session.api;

/** ADR-0013 旧追溯及其证明预算，不能借跨会话版本扩大。 */
public final class SessionTracePolicyV3 {
  public static final int INPUT_BYTES = 1024;
  public static final int OUTPUT_BYTES = 32768;
  public static final int EVENT_BODY_BYTES = 2097152;
  public static final int EDGES = 180;
  public static final int OPERATIONS = 1048576;
  public static final int SUMMARY_BYTES = 2048;
  public static final int SUMMARY_JSON_BYTES = 1536;
  public static final int SCRATCH_BYTES = 1048576;

  private SessionTracePolicyV3() {}
}
