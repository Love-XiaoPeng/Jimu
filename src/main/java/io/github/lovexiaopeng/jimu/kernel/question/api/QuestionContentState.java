package io.github.lovexiaopeng.jimu.kernel.question.api;

/** 问答持久正文的清理状态，不能当成问答 phase。 */
public final class QuestionContentState {
  public static final String PURGED = "PURGED";

  private QuestionContentState() {}
}
