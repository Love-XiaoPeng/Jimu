package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionVisibility;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionVisibilityService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** owner存在行仅决定可见性；随后仍锁原完整行并核当前权限。 */
public final class QuestionVisibilityServiceImpl implements QuestionVisibilityService {
  public <T> T visible(QuestionVisibility<T> port) {
    port.identity();
    if (port.owned(port.tenant(), port.taskId(), port.actor()).isEmpty())
      throw new TaskFailure(404, TaskErrorCode.TASK_NOT_FOUND);
    var task = port.locked(port.tenant(), port.taskId());
    port.access(task);
    return task;
  }
}
