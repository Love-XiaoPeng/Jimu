package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionOpening;

public interface QuestionOpeningService {
  <I, P, B, R, T> R openOrResult(QuestionOpening<I, P, B, R, T> port);
}
