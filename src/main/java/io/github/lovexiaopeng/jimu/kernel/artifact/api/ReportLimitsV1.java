package io.github.lovexiaopeng.jimu.kernel.artifact.api;

/** 已发布报告与 source-bound-report 的冻结输出政策；文本上限沿原 UTF16 单位，正文沿 UTF8 字节。 */
public final class ReportLimitsV1 {
  public static final int REPORT_BYTES = 1048576;
  public static final int SUBJECT_UTF16_UNITS = 120;
  public static final int LIMITATION_UTF16_UNITS = 400;
  public static final int SEGMENT_UTF16_UNITS = 600;
  public static final int QUOTE_UTF16_UNITS = 800;
  public static final int IDENTIFIER_UTF16_UNITS = 300;
  public static final int IMAGE_PATH_UTF16_UNITS = 512;

  private ReportLimitsV1() {}
}
