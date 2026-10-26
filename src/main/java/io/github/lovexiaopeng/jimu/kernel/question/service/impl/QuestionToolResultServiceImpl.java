package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.BudgetErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionToolResult;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionToolResultService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

public final class QuestionToolResultServiceImpl implements QuestionToolResultService {
  public <P> void complete(QuestionToolResult<P> port, String output) {
    String tenant = port.tenant(), invocation = port.invocation();
    port.output(port.version(port.pin()), output);
    if (port.completeInvocation(output, port.hash(output), tenant, port.taskId(), invocation) != 1)
      throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
    if (port.settleReservation(tenant, invocation) != 1)
      throw new TaskFailure(409, BudgetErrorCode.RESERVATION_STATE);
    port.event("tool:" + invocation, "tool.completed", null);
  }
}
