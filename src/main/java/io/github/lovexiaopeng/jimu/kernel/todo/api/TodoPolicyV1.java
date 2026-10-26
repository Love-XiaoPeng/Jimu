package io.github.lovexiaopeng.jimu.kernel.todo.api;

/** todo v1 的预留、元数据与保留政策；不改变公开清单和 CAS 语义。 */
public final class TodoPolicyV1 {
  public static final int STORAGE_BYTES = 2097152;
  public static final int METADATA_BYTES = 65536;
  public static final int LIST_BYTES = 8192;
  public static final int IN_PROGRESS_ITEMS = 1;

  public static final int INPUT_BYTES = 16384;

  public static final int ITEM_UTF8_BYTES = 1024;

  public static final int CLOSED_METADATA_BYTES = 65536;

  public static final int OPEN_METADATA_BYTES = 61440;

  private TodoPolicyV1() {}
}
