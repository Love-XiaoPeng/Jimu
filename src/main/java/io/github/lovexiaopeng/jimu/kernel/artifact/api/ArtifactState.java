package io.github.lovexiaopeng.jimu.kernel.artifact.api;

/** 本聚合既有持久状态/原因的显式字符值；同名值不与其他聚合状态合并。 */
public final class ArtifactState {
  public static final String VERIFIED = "VERIFIED";
  public static final String PUBLISHED = "PUBLISHED";

  private ArtifactState() {}
}
