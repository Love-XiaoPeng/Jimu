package io.github.lovexiaopeng.jimu.kernel.question.api;

/** 持久问答 v1 冻结存储、答复和保留政策；默认超时沿原受信输入选择时点。 */
public final class QuestionPolicyV1 {
  public static final int STORAGE_BYTES = 2097152;
  public static final int RECEIPT_RESERVE_BYTES = 16384;
  public static final int OWNER_RESPONSE_BYTES = 524288;
  public static final int DEFAULT_TIMEOUT_SECONDS = 120;
  public static final int BATCH_QUESTIONS = 8;
  public static final int RETENTION_SECONDS = 604800;

  public static final int INPUT_BYTES = 16384;

  public static final int TEXT_UTF8_BYTES = 4096;

  public static final int HEADER_UTF8_BYTES = 128;

  public static final int DESCRIPTION_UTF8_BYTES = 1024;

  public static final int CUSTOM_ANSWER_UTF8_BYTES = 2048;

  public static final int ANSWER_BYTES = 16384;

  public static final int ANSWER_HTTP_BYTES = 32768;

  public static final int OPTIONS = 4;

  public static final int MAX_TIMEOUT_SECONDS = 180;

  private QuestionPolicyV1() {}
}
