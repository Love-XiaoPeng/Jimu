package io.github.lovexiaopeng.jimu.kernel.artifact.api;

/** 报告草稿失败码的既有后缀；完整 code 仍为原 REPORT_DRAFT_ 前缀与后缀拼接。 */
public final class ReportDraftReason {
  public static final String VERSION_UNSUPPORTED = "VERSION_UNSUPPORTED";
  public static final String BLOCKS_INVALID = "BLOCKS_INVALID";
  public static final String TOO_LARGE = "TOO_LARGE";
  public static final String SOURCE_AMBIGUOUS = "SOURCE_AMBIGUOUS";
  public static final String URL_SET_MISMATCH = "URL_SET_MISMATCH";
  public static final String SHAPE_INVALID = "SHAPE_INVALID";
  public static final String SOURCE_UNKNOWN = "SOURCE_UNKNOWN";
  public static final String EVIDENCE_MISMATCH = "EVIDENCE_MISMATCH";
  public static final String ENDPOINT_INVALID = "ENDPOINT_INVALID";
  public static final String ENDPOINT_UNSUPPORTED = "ENDPOINT_UNSUPPORTED";
  public static final String TEXT_INVALID = "TEXT_INVALID";
  public static final String RAW_URL_FORBIDDEN = "RAW_URL_FORBIDDEN";

  private ReportDraftReason() {}
}
