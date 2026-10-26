package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAuthorization;

public interface QuestionAuthorizationService {
  void authorize(QuestionAuthorization port);
}
