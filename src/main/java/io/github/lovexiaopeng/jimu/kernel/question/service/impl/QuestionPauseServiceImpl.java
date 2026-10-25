package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionPause;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionPauseService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

public final class QuestionPauseServiceImpl implements QuestionPauseService {
  public void pause(QuestionPause port, String state, String continuation) {
    if (port.pauseAttempt(port.tenant(), port.attemptId(), port.fence()) != 1)
      throw new TaskFailure(409, "FENCE_REJECTED");
    if (port.pauseTask(state, continuation, port.tenant(), port.taskId(), port.fence()) != 1)
      throw new TaskFailure(409, "FENCE_REJECTED");
  }
}
