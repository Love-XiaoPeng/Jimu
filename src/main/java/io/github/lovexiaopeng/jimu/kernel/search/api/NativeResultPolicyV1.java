package io.github.lovexiaopeng.jimu.kernel.search.api;

/** 原生搜索 v1 的冻结结果、分页和投影政策；字节均为 UTF8，不能由部署配置放宽。 */
public final class NativeResultPolicyV1 {
  public static final int COLLECTED_ENTRY_BYTES = 4194304;
  public static final int DOCUMENT_BYTES = 4194304;
  public static final int PAGE_BYTES = 65536;
  public static final int GLOB_PAGE_ENTRIES = 100;
  public static final int GREP_PAGE_ENTRIES = 250;
  public static final int DIVERSE_SAMPLE_ENTRIES = 100;
  public static final int PATH_PREVIEW_UTF8_BYTES = 2048;
  public static final int LINE_PREVIEW_UTF8_BYTES = 2000;
  public static final int CURSOR_RANDOM_BYTES = 16;
  public static final int RAW_STDOUT_BYTES = 20000000;
  public static final int PARSER_STRING_UTF16_UNITS = 20000000;
  public static final int PARSER_DOCUMENT_LENGTH = 20000000;
  public static final int PARSER_DEPTH = 32;
  public static final int TASK_ROOTS = 20;
  public static final int TASK_STORAGE_BYTES = 8388608;
  public static final int CURSORS = 100000;

  private NativeResultPolicyV1() {}
}
