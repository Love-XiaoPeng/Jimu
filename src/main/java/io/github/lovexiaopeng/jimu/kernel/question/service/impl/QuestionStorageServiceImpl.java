package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionStorage;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionStorageService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** 原存储资格；保留可空汇总的拆箱、加法及有符号溢出语义。 */
public final class QuestionStorageServiceImpl implements QuestionStorageService {
  private static final int STORAGE_LIMIT_BYTES = 2097152;

  public void requireCapacity(QuestionStorage port, long additional) {
    if (!port.capabilities().contains("ask_user_question")) return;
    long bytes = port.bytes();
    if (bytes + additional > STORAGE_LIMIT_BYTES)
      throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
  }
}
