package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionContinuation;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionContinuationService;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;

public final class QuestionContinuationServiceImpl implements QuestionContinuationService {
  public <R, P> void continueQuestion(QuestionContinuation<R, P> port) {
    var found = port.invocations(port.tenant(), port.invocationId(), port.taskId());
    if (found.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    var invocation = found.getFirst();
    String output = port.pending(port.version(port.pin()), port.provider(invocation));
    port.completeOutput(output);
    if (port.continuePhase(port.tenant(), port.questionId(), port.revision()) != 1)
      throw new TaskFailure(409, QuestionErrorCode.QUESTION_REVISION_CONFLICT);
    port.resume(port.questionId());
    port.event("question-continued:" + port.questionId(), "question.continued", null);
  }
}
