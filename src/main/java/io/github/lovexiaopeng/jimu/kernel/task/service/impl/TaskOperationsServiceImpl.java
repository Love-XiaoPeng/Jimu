package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskOperationsRecords;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskOperationsRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskOperationsRecords.Snapshot;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskOperationsService;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.List;
import java.util.Locale;

/** 保留原锁序、scope脱敏/拒绝差异、取消停止意图和UNKNOWN成本语义。 */
public final class TaskOperationsServiceImpl implements TaskOperationsService {
  private final TaskOperationsRecords records;
  private final LedgerUnitOfWork work;

  public TaskOperationsServiceImpl(TaskOperationsRecords records, LedgerUnitOfWork work) {
    this.records = records;
    this.work = work;
  }

  public Tasks.View get(Tasks.Identity identity, String taskId) {
    return work.required(
        () -> {
          if (records.scopeHint(identity, taskId)) {
            records.lockBudget(identity.tenant());
            var row = records.lock(identity.tenant(), taskId);
            try {
              row.requireAccess();
              return view(row);
            } catch (TaskFailure failure) {
              if (!failure.code().equals("SESSION_SCOPE_UNAVAILABLE") && failure.status() != 403)
                throw failure;
              return response(row.snapshot(), "SESSION_SCOPE_UNAVAILABLE", null, List.of());
            }
          }
          var row = records.visible(identity, taskId);
          row.requireAccess();
          return view(row);
        });
  }

  public Tasks.View view(Row row) {
    var task = row.snapshot();
    var artifacts = row.artifacts();
    String summary = task.summary();
    if (task.historyTask() && !row.historyRetained()) summary = null;
    String code = task.code();
    if (task.scopeTask()) {
      try {
        row.requireAccess();
      } catch (TaskFailure failure) {
        summary = null;
        artifacts = List.of();
        code = "SESSION_SCOPE_UNAVAILABLE";
      }
    }
    return response(task, code, summary, artifacts);
  }

  private static Tasks.View response(
      Snapshot row, String code, String summary, List<Tasks.Artifact> artifacts) {
    return new Tasks.View(
        row.id(),
        row.state(),
        row.revision(),
        row.createdAt(),
        row.updatedAt(),
        row.cancelRequestedAt(),
        code,
        summary,
        artifacts);
  }

  public Tasks.View cancel(Tasks.Identity identity, String taskId) {
    return work.required(
        () -> {
          records.lockBudget(identity.tenant());
          records.visible(identity, taskId);
          var row = records.lock(identity.tenant(), taskId);
          var task = row.snapshot();
          if (task.cancelRequestedAt() != null) return view(row);
          if (view(row).terminal()) {
            // UNKNOWN is a terminal outcome, not evidence that the writer exited.
            if (!task.state().equals("NEEDS_RECONCILIATION") || row.writersQuiescent())
              return view(row);
            row.requestCancel(false);
            row.event("cancel", "task.cancel_requested", null);
            return view(row.reload());
          }
          row.requestCancel(true);
          row.event("cancel", "task.cancel_requested", null);
          if (task.questionTask()) closeQuestions(row, "CANCELLED");
          if (task.state().equals("QUEUED") || task.questionTask() && row.writersQuiescent())
            terminal(row, "CANCELLED", "CANCELLED");
          return view(row.reload());
        });
  }

  public List<Tasks.Event> events(Tasks.Identity identity, String taskId, long after, int limit) {
    return work.required(
        () -> {
          records.lockBudget(identity.tenant());
          records.lockScope(identity.tenant(), taskId);
          var row = records.visible(identity, taskId);
          row.requireAccess();
          long next = row.snapshot().nextEventSequence();
          if (after > next) throw new TaskFailure(400, "FUTURE_CURSOR");
          long oldest = row.oldestEvent();
          if (oldest > 0 && after < oldest - 1 || oldest == 0 && next > 0)
            throw new TaskFailure(410, "CURSOR_EXPIRED");
          return row.events(after, Math.min(limit, 64));
        });
  }

  public void closeQuestions(Row row, String code) {
    if (!row.snapshot().questionTask()) return;
    for (var question : row.lockQuestions()) {
      row.closeQuestion(question, code, code.equals("CANCELLED") ? "CANCELLED" : "FAILED");
      row.event("question-closed:" + question.id(), "question.closed", code);
    }
    row.discardQuestionDelivery(code);
    row.clearQuestionContinuation();
  }

  public void terminal(Row row, String state, String code) {
    if (state.equals("CANCELLED") && !row.writersQuiescent()) return;
    closeQuestions(row, code == null ? "TASK_COMPLETED" : code);
    row.setTerminal(state, code);
    if (state.equals("NEEDS_RECONCILIATION")) row.markUnknownTools();
    if (!state.equals("NEEDS_RECONCILIATION")) row.releaseQueue();
    if (row.writersQuiescent()) {
      row.releaseRun();
      row.releaseQuestionTask();
    }
    row.holdUnknownModels();
    row.stopAttempt(state.equals("SUCCEEDED") ? "SUCCEEDED" : "STOPPED");
    row.event(
        "terminal",
        state.equals("SUCCEEDED") ? "task.completed" : "task." + state.toLowerCase(Locale.ROOT),
        code);
  }
}
