package io.github.lovexiaopeng.jimu.kernel.directory.api;

/** 逻辑目录 v1 的冻结预留及输入政策；不得改变既有 retain_until。 */
public final class DirectoryPolicyV1 {
  public static final int STORAGE_BYTES = 8388608;
  public static final int INPUT_SNAPSHOT_BYTES = 65535;

  public static final int BODY_STORAGE_BYTES = 2097152;

  public static final int CLOSED_METADATA_BYTES = 65536;

  public static final int OPEN_METADATA_BYTES = 61440;

  private DirectoryPolicyV1() {}
}
