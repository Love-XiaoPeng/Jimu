package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionStorage;

public interface QuestionStorageService {
  /** 执行用例复用的惰性存储事实；不包含业务决定。 */
  interface Facts extends QuestionStorage {}

  default void requireCapacity(Facts facts, long additional) {
    requireCapacity((QuestionStorage) facts, additional);
  }

  void requireCapacity(QuestionStorage port, long additional);
}
