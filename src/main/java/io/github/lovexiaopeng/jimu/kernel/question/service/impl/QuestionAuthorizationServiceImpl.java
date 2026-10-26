package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAuthorization;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionAuthorizationService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;

public final class QuestionAuthorizationServiceImpl implements QuestionAuthorizationService {
  public void authorize(QuestionAuthorization port) {
    port.transaction(
        () -> {
          port.bucket(port.tenant());
          port.visible();
          if (port.rows(port.tenant(), port.taskId(), port.requestId()).isEmpty())
            throw new TaskFailure(404, QuestionErrorCode.QUESTION_NOT_FOUND);
          return null;
        });
  }
}
