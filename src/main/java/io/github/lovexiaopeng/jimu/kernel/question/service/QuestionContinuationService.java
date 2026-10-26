package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionContinuation;

public interface QuestionContinuationService {
  <R, P> void continueQuestion(QuestionContinuation<R, P> port);
}
