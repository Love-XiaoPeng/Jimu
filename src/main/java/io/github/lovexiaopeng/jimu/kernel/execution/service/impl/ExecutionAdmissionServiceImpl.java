package io.github.lovexiaopeng.jimu.kernel.execution.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.BudgetErrorCode;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryStorageLimit;
import io.github.lovexiaopeng.jimu.kernel.execution.api.ExecutionAdmissionPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords.Admission;
import io.github.lovexiaopeng.jimu.kernel.execution.service.ExecutionAdmissionService;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionStorageService;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryStorageService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoStorageService;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.nio.charset.StandardCharsets;

/** 保留原分支、惰性codec、首错和计费顺序；不另开事务。 */
public final class ExecutionAdmissionServiceImpl implements ExecutionAdmissionService {
  private final QuestionStorageService questions;
  private final TodoStorageService todos;
  private final SessionHistoryStorageService sessions;

  public ExecutionAdmissionServiceImpl(
      QuestionStorageService questions,
      TodoStorageService todos,
      SessionHistoryStorageService sessions) {
    this.questions = questions;
    this.todos = todos;
    this.sessions = sessions;
  }

  public void lease(
      io.github.lovexiaopeng.jimu.kernel.execution.port.LeaseAdmission facts,
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease) {
    String rejection = facts.modelRejection();
    if (rejection == null) rejection = facts.runRejection();
    if (rejection != null) throw new TaskFailure(409, rejection);
    facts.requireScope();
    facts.checkMaterials();
    facts.access();
    if (facts.session()) {
      if (!lease.actor().equals(facts.owner()))
        throw new TaskFailure(
            409,
            io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode
                .INVOCATION_MISMATCH);
      facts.sessionAdmission();
    }
    if (facts.directory()) {
      if (!lease.actor().equals(facts.owner()))
        throw new TaskFailure(
            409,
            io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode
                .INVOCATION_MISMATCH);
      facts.directoryAdmission();
    }
    if (facts.todo()) {
      if (!lease.actor().equals(facts.owner()))
        throw new TaskFailure(
            409,
            io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode
                .INVOCATION_MISMATCH);
      if (!facts.todosEnabled())
        throw new TaskFailure(
            403, io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode.TODO_FEATURE_DISABLED);
      if (io.github.lovexiaopeng.jimu.kernel.todo.api.TodoContentState.PURGED.equals(
          facts.todoContentState()))
        throw new TaskFailure(
            410, io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode.TODO_CONTENT_EXPIRED);
      facts.requireTodoModel();
    }
    if (facts.question() && !facts.questionsEnabled())
      throw new TaskFailure(
          403, io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode.QUESTION_FEATURE_DISABLED);
    if (facts.cancelled()) throw new TaskFailure(409, TaskErrorCode.CANCEL_REQUESTED);
    if (!facts.withinDeadline()) throw new TaskFailure(409, TaskErrorCode.DEADLINE_EXCEEDED);
  }

  public void request(Admission task, String request) {
    if (task.question()) {
      if (bytes(request) > ExecutionAdmissionPolicyV1.QUESTION_REQUEST_BYTES)
        throw new TaskFailure(429, ModelErrorCode.MODEL_INPUT_TOO_LARGE);
      questions.requireCapacity(task.questionStorage(), bytes(request));
    }
    if (task.todo()) {
      var body = task.requestBody(request);
      if (bytes(request) > ExecutionAdmissionPolicyV1.TODO_CHECKPOINT_BYTES
          || bytes(body.get()) > ExecutionAdmissionPolicyV1.TODO_REQUEST_BYTES)
        throw new TaskFailure(429, ModelErrorCode.MODEL_INPUT_TOO_LARGE);
      todos.requireCapacity(task.todoStorage(), bytes(request));
    }
    if (task.directory()) {
      var directory = task.directoryRequest(request);
      if (directory.version() != ExecutionAdmissionPolicyV1.DIRECTORY_CHECKPOINT_VERSION
          || bytes(request) > ExecutionAdmissionPolicyV1.DIRECTORY_CHECKPOINT_BYTES
          || bytes(directory.requestBody()) > ExecutionAdmissionPolicyV1.DIRECTORY_REQUEST_BYTES)
        throw new TaskFailure(429, ModelErrorCode.MODEL_INPUT_TOO_LARGE);
      if (directory.messagesEmpty()
          || !directory.lastRole().equals("user")
          || !directory.expectedContext().equals(directory.lastContent()))
        throw new TaskFailure(409, DirectoryErrorCode.DIRECTORY_REVISION_CONFLICT);
      long additional = bytes(request) + bytes(directory.cwd());
      DirectoryStorageLimit.check(task.directoryStorageBytes(), additional);
    }
  }

  public void media(Admission task, String step, String request) {
    if (task.recordVersion() == ExecutionAdmissionPolicyV1.MEDIA_RECORD_VERSION) {
      var budget = task.lockMediaBudget();
      long bytes = bytes(request);
      if (bytes > budget.limit() - budget.held())
        throw new TaskFailure(429, BudgetErrorCode.STORAGE_BUDGET_EXCEEDED);
      budget.hold(bytes);
      String reservation = step + ":media-checkpoint";
      budget.reserve(
          reservation,
          bytes,
          ReservationCategory.MEDIA_CHECKPOINT,
          ReservationState.SETTLED,
          UsageQuality.KNOWN);
      budget.settle(reservation);
    }
  }

  public void summary(Admission task, String summary) {
    if (task.session()) {
      if (!task.publicSummary(summary).equals(summary))
        throw new TaskFailure(409, SessionErrorCode.SESSION_EVENT_UNSAFE);
      if (bytes(summary) > ExecutionAdmissionPolicyV1.SUMMARY_BYTES)
        throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
      sessions.body(task.sessionStorage(), bytes(summary));
    }
    if (task.directory()) {
      unicode(summary);
      long bytes = bytes(summary);
      if (bytes > ExecutionAdmissionPolicyV1.SUMMARY_BYTES)
        throw new TaskFailure(429, DirectoryErrorCode.DIRECTORY_STORAGE_LIMIT);
      DirectoryStorageLimit.check(task.directoryStorageBytes(), bytes);
    }
    if (task.todo()) {
      unicode(summary);
      int bytes = bytes(summary);
      if (bytes > ExecutionAdmissionPolicyV1.SUMMARY_BYTES)
        throw new TaskFailure(409, TaskErrorCode.SUMMARY_TOO_LARGE);
      todos.requireCapacity(task.todoStorage(), bytes);
    }
    if (task.question()) {
      if (bytes(summary) > ExecutionAdmissionPolicyV1.SUMMARY_BYTES)
        throw new TaskFailure(409, TaskErrorCode.SUMMARY_TOO_LARGE);
      questions.requireCapacity(task.questionStorage(), bytes(summary));
    }
  }

  private static int bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  private static void unicode(String text) {
    for (int index = 0; index < text.length(); index++) {
      char c = text.charAt(index);
      if (Character.isHighSurrogate(c)) {
        if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index)))
          throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
      } else if (Character.isLowSurrogate(c))
        throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    }
  }
}
