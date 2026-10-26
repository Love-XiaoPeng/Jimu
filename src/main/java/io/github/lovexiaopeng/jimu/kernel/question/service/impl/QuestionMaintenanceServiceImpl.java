package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionMaintenance;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionMaintenanceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;
import java.util.List;
import java.util.Map;

/** Bounded global scan followed by the original per-task maintenance transaction. */
public final class QuestionMaintenanceServiceImpl implements QuestionMaintenanceService {
  public void maintain(QuestionMaintenance port) {
    for (var candidate : port.candidates(port.enabled())) {
      port.transaction(
          () -> {
            port.bucket(s(candidate, "tenant_id"));
            var task = port.locked(s(candidate, "tenant_id"), s(candidate, "id"));
            if (!List.of(TaskState.WAITING_USER, TaskState.RESUME_READY).contains(s(task, "state")))
              return null;
            String rejection = null;
            if (task.get("cancel_requested_at") != null) rejection = TaskErrorCode.CANCELLED;
            else if (n(task, "within_deadline") != 1) rejection = TaskErrorCode.DEADLINE_EXCEEDED;
            else if (!port.enabled()) rejection = QuestionErrorCode.QUESTION_FEATURE_DISABLED;
            else
              try {
                port.access(task);
              } catch (TaskFailure error) {
                rejection = error.code();
              }
            if (rejection != null) {
              port.close(task, rejection);
              port.terminal(
                  task,
                  rejection.equals(TaskErrorCode.CANCELLED)
                      ? TaskState.CANCELLED
                      : TaskState.FAILED,
                  rejection);
              return null;
            }
            for (var q : port.due(s(task, "tenant_id"), s(task, "id")))
              port.continueQuestion(task, q);
            port.settle(port.locked(s(task, "tenant_id"), s(task, "id")));
            return null;
          });
    }
  }

  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }
}
