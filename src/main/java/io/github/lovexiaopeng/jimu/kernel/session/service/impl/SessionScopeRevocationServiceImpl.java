package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRevocations;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeRevocationService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.util.List;

/** 保留原owner／当前身份／输入短路次序及UNKNOWN不触发取消语义。 */
public final class SessionScopeRevocationServiceImpl implements SessionScopeRevocationService {
  private final SessionScopeRevocations records;

  public SessionScopeRevocationServiceImpl(SessionScopeRevocations records) {
    this.records = records;
  }

  public void revoke(Identity identity, String caller, boolean validRequest, long deadlineNanos) {
    records.required(
        deadlineNanos,
        () -> {
          records.lockBudget(identity.tenant());
          records.lockScope(identity.tenant(), caller);
          var found = records.owners(identity, caller);
          if (found.size() != 1 || !found.getFirst().scopeTask())
            throw new TaskFailure(404, SessionErrorCode.SESSION_SCOPE_UNAVAILABLE);
          var task = found.getFirst();
          try {
            records.requireIdentity(identity);
          } catch (TaskFailure failure) {
            task.invalidateAfterTransaction();
            throw new TaskFailure(404, SessionErrorCode.SESSION_SCOPE_UNAVAILABLE);
          }
          if (!validRequest) throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
          task.revoke();
          if (!List.of(
                  TaskState.SUCCEEDED,
                  TaskState.FAILED,
                  TaskState.CANCELLED,
                  TaskState.NEEDS_RECONCILIATION)
              .contains(task.state())) task.cancel(identity);
          task.account();
          return null;
        });
  }
}
