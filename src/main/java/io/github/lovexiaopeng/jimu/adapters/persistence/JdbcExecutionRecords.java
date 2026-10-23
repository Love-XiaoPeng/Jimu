package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** B1 具名记录端口；只保留同事务SQL及尚待B3/B4迁移的模块校验，B2替换真实Mapper。 */
final class JdbcExecutionRecords implements ExecutionRecords {
  private final JdbcLedger ledger;
  private final JdbcTemplate db;

  JdbcExecutionRecords(JdbcLedger ledger, JdbcTemplate db) {
    this.ledger = ledger;
    this.db = db;
  }

  @Override
  public Task lockSettlement(Lease lease) {
    var row = ledger.fenced(lease, false);
    return new Task() {
      public boolean hasEvaluationRun() {
        return row.get("evaluation_run_id") != null;
      }

      public boolean cancellationRequested() {
        return row.get("cancel_requested_at") != null;
      }

      public boolean pendingEffect() {
        return !ledger
            .mappers
            .invocations()
            .selectPendingEffects(ledger.queryContext(), lease.tenant(), lease.taskId())
            .isEmpty();
      }

      public void stopEvaluationRun() {
        ledger
            .mappers
            .evaluations()
            .stopRun(ledger.queryContext(), lease.tenant(), ledger.s(row, "evaluation_run_id"));
      }

      public void terminal(String state, String code) {
        ledger.terminal(row, state, code);
      }
    };
  }

  @Override
  public Admission lockAdmission(Lease lease) {
    var task = ledger.fenced(lease, true);
    return new Admission() {
      public Optional<StoredStep> latest() {
        return ledger
            .mappers
            .steps()
            .selectLatestStep(ledger.queryContext(), lease.tenant(), lease.taskId())
            .stream()
            .map(
                row ->
                    new StoredStep(
                        ledger.s(row, "id"),
                        (int) ledger.n(row, "ordinal"),
                        ledger.s(row, "request_snapshot"),
                        ledger.s(row, "response_snapshot"),
                        ledger.s(row, "dispatch_id"),
                        ledger.s(row, "request_cleanup_state"),
                        ledger.n(row, "record_version"),
                        ledger.s(row, "request_digest"),
                        ledger.s(row, "response_digest")))
            .findFirst();
      }

      public void validateMaterial(String request, int ordinal) {
        ledger.materialBody(task, request, ordinal);
      }

      public boolean unfinishedInvocations(String step) {
        return !ledger
            .mappers
            .invocations()
            .selectUnfinishedStepInvocations(ledger.queryContext(), lease.tenant(), step)
            .isEmpty();
      }

      public void validateModuleRequest(String request) {
        if (ledger.questionTask(task)) {
          if (request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new TaskFailure(429, "MODEL_INPUT_TOO_LARGE");
          ledger.questionStorage(
              task, request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        if (ledger.todoTask(task)) {
          var wire =
              Payloads.read(
                  request,
                  io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
          if (io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(request)
                  > 131072
              || io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(
                      wire.requestBody())
                  > 65536) throw new TaskFailure(429, "MODEL_INPUT_TOO_LARGE");
          ledger.todoStorage(
              task, io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(request));
        }
        if (ledger.directoryTask(task)) ledger.directoryContext(task, request);
      }

      public void reserveMedia(String stepId, String request) {
        if (ledger.n(task, "record_version") == 3) {
          var budget = ledger.bucket(lease.tenant());
          long bytes = request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          if (bytes > ledger.n(budget, "storage_limit") - ledger.storageHeld(budget))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          ledger
              .mappers
              .budgets()
              .holdMediaCheckpoint(ledger.queryContext(), bytes, lease.tenant());
          ledger.reserve(
              lease.tenant(),
              lease.taskId(),
              stepId + ":media-checkpoint",
              null,
              "MEDIA_CHECKPOINT",
              bytes,
              "SETTLED",
              "KNOWN");
          ledger
              .mappers
              .reservations()
              .settleReservedAmount(
                  ledger.queryContext(), lease.tenant(), stepId + ":media-checkpoint");
        }
      }

      public void insertStep(String stepId, int ordinal, String request) {
        ledger
            .mappers
            .steps()
            .insertPreparedStep(
                ledger.queryContext(),
                lease.tenant(),
                stepId,
                lease.taskId(),
                lease.attemptId(),
                ordinal,
                request,
                Payloads.hash(request),
                ledger.snapshot(task).model());
      }

      public void prepareProjections(String stepId, String request) {
        if (JdbcSessionRecords.task(task)) ledger.sessions.prepare(task, request, stepId);
        if (ledger.directoryTask(task)) {
          var p = ledger.directoryProjection(task);
          ledger
              .mappers
              .steps()
              .bindDirectoryProjection(
                  ledger.queryContext(),
                  ledger.n(p, "revision"),
                  ledger.s(p, "cwd"),
                  ledger.s(p, "binding_digest"),
                  lease.tenant(),
                  stepId);
        }
        if (ledger.questionTask(task)) {
          var wire =
              Payloads.read(
                  request,
                  io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
          for (var pending : ledger.questionMessages(task).entrySet()) {
            if (wire.messages().stream()
                .anyMatch(m -> m.role().equals("user") && pending.getValue().equals(m.content()))) {
              if (db.update(
                      "UPDATE user_question SET delivery_state='CHECKPOINTED',delivery_step_id=? WHERE tenant_id=? AND id=? AND delivery_state='PENDING'",
                      stepId,
                      lease.tenant(),
                      pending.getKey())
                  != 1) throw new TaskFailure(409, "QUESTION_DELIVERY_CONFLICT");
            }
          }
          ledger
              .mappers
              .tasks()
              .clearQuestionContinuation(ledger.queryContext(), lease.tenant(), lease.taskId());
        }
      }

      public void preparedEvent(String step) {
        ledger.event(task, "prepared:" + step, "model.prepared", null);
      }

      public void commitFence() {
        if (ledger.directoryTask(task) || JdbcSessionRecords.task(task))
          ledger.todoCommitFence(lease);
      }

      public void validateSummary(String summary) {
        if (JdbcSessionRecords.task(task)) {
          var publicSummary =
              ledger.sessions.contracts.publicText(summary, ledger.sessions.sensitive());
          if (!publicSummary.content().equals(summary))
            throw new TaskFailure(409, "SESSION_EVENT_UNSAFE");
          if (io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(summary)
              > 8192) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          ledger.sessions.body(
              task,
              io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(summary));
        }
        if (ledger.directoryTask(task)) {
          io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.unicode(summary);
          long bytes = summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          if (bytes > 8192) throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
          ledger.directoryStorage(task, bytes);
        }
        if (ledger.todoTask(task)) {
          io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.unicode(summary);
          int bytes =
              io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(summary);
          if (bytes > 8192) throw new TaskFailure(409, "SUMMARY_TOO_LARGE");
          ledger.todoStorage(task, bytes);
        }
        if (ledger.questionTask(task)) {
          if (summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192)
            throw new TaskFailure(409, "SUMMARY_TOO_LARGE");
          ledger.questionStorage(
              task, summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
      }

      public boolean pendingQuestions() {
        return ledger.questionTask(task) && !ledger.questionMessages(task).isEmpty();
      }

      public String latestStepId() {
        return ledger.s(
            JdbcLedger.first(
                ledger
                    .mappers
                    .steps()
                    .selectLatestStepId(ledger.queryContext(), lease.tenant(), lease.taskId())),
            "id");
      }

      public void pauseQuestions(String step) {
        ledger.questionPause(task, lease, "RESUME_READY", step);
        ledger.event(task, "question-final-resume:" + step, "task.resumable", null);
      }

      public boolean publicReport() {
        return ledger.publicReport(task);
      }

      public boolean publishedReport() {
        return !ledger
            .mappers
            .artifacts()
            .selectPublishedReport(ledger.queryContext(), lease.tenant(), lease.taskId())
            .isEmpty();
      }

      public boolean unresolvedInvocations() {
        return !ledger
            .mappers
            .invocations()
            .selectUnfinishedTaskInvocations(ledger.queryContext(), lease.tenant(), lease.taskId())
            .isEmpty();
      }

      public boolean unresolvedArtifacts() {
        return !ledger
            .mappers
            .artifacts()
            .selectUnpublishedArtifacts(ledger.queryContext(), lease.tenant(), lease.taskId())
            .isEmpty();
      }

      public void finishSummary(String summary) {
        ledger
            .mappers
            .tasks()
            .finishSummary(ledger.queryContext(), summary, lease.tenant(), lease.taskId());
      }

      public void terminal(String state, String code) {
        ledger.terminal(task, state, code);
      }
    };
  }
}
