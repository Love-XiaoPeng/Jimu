package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionVisibility;

public interface QuestionVisibilityService {
  <T> T visible(QuestionVisibility<T> port);
}
