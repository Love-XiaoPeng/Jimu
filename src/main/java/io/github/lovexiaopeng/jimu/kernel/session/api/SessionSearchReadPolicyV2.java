package io.github.lovexiaopeng.jimu.kernel.session.api;

/** 旧公开搜索的原始事件扫描和证明读取预算，与匹配比较次数分开计量。 */
public final class SessionSearchReadPolicyV2 {
  public static final int EVENT_BODY_BYTES = 2097152;
  public static final int PROOF_BYTES = 8388608;
  public static final int SCRATCH_BYTES = 8388608;
  public static final int SCRATCH_HEADROOM_BYTES = 524288;
  public static final int SEARCH_COPIES = 2;
  public static final int TRACE_COPIES = 3;

  public static final int PROOF_READS = 1024;

  private SessionSearchReadPolicyV2() {}
}
