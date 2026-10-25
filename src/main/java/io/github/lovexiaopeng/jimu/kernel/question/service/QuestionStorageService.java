package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionStorage;

public interface QuestionStorageService {
  void requireCapacity(QuestionStorage port, long additional);
}
