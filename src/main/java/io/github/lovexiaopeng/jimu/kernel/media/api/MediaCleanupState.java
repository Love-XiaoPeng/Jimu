package io.github.lovexiaopeng.jimu.kernel.media.api;

/** 本聚合既有持久状态/原因的显式字符值；同名值不与其他聚合状态合并。 */
public final class MediaCleanupState {
  public static final String RETAINED = "RETAINED";
  public static final String DELETED = "DELETED";
  public static final String DELETING = "DELETING";

  private MediaCleanupState() {}
}
