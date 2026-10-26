package io.github.lovexiaopeng.jimu.kernel.artifact.api;

/** 本聚合既有持久状态/原因的显式字符值；同名值不与其他聚合状态合并。 */
public final class ArtifactReleaseReason {
  public static final String EXPIRED = "EXPIRED";
  public static final String ABANDONED = "ABANDONED";

  private ArtifactReleaseReason() {}
}
