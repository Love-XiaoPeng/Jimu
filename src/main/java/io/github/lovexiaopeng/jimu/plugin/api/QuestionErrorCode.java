package io.github.lovexiaopeng.jimu.plugin.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class QuestionErrorCode {
  public static final String QUESTION_ALREADY_ANSWERED = "QUESTION_ALREADY_ANSWERED";
  public static final String QUESTION_ALREADY_WAITING = "QUESTION_ALREADY_WAITING";
  public static final String QUESTION_ANSWER_INVALID = "QUESTION_ANSWER_INVALID";
  public static final String QUESTION_BATCH_LIMIT = "QUESTION_BATCH_LIMIT";
  public static final String QUESTION_BINDING_MISMATCH = "QUESTION_BINDING_MISMATCH";
  public static final String QUESTION_CLOSED = "QUESTION_CLOSED";
  public static final String QUESTION_CONTENT_EXPIRED = "QUESTION_CONTENT_EXPIRED";
  public static final String QUESTION_DELIVERY_CONFLICT = "QUESTION_DELIVERY_CONFLICT";
  public static final String QUESTION_EXPIRED = "QUESTION_EXPIRED";
  public static final String QUESTION_FEATURE_DISABLED = "QUESTION_FEATURE_DISABLED";
  public static final String QUESTION_NOT_AUTHORIZED = "QUESTION_NOT_AUTHORIZED";
  public static final String QUESTION_NOT_FOUND = "QUESTION_NOT_FOUND";
  public static final String QUESTION_RESPONSE_TOO_LARGE = "QUESTION_RESPONSE_TOO_LARGE";
  public static final String QUESTION_REVISION_CONFLICT = "QUESTION_REVISION_CONFLICT";
  public static final String QUESTION_TASK_LIMIT = "QUESTION_TASK_LIMIT";
  public static final String QUESTION_VERSION_UNSUPPORTED = "QUESTION_VERSION_UNSUPPORTED";

  private QuestionErrorCode() {}
}
