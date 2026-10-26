package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionResume;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionResumeService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;

public final class QuestionResumeServiceImpl implements QuestionResumeService {
  public void resume(QuestionResume port, String requestId) {
    if (port.update(port.tenant(), port.taskId(), requestId) != 1)
      throw new TaskFailure(409, QuestionErrorCode.QUESTION_BINDING_MISMATCH);
    port.event("question-resume:" + requestId, "task.resumable", null);
  }
}
