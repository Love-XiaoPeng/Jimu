package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** 同事务事实、codec和明确数据变更；准入、限额及结算顺序归执行Service。 */
final class JdbcExecutionRecords implements ExecutionRecords {
  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionDeliveryService
      questionDeliveries =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionDeliveryServiceImpl();
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

      public boolean question() {
        return ledger.questionTask(task);
      }

      public boolean todo() {
        return ledger.todoTask(task);
      }

      public boolean directory() {
        return ledger.directoryTask(task);
      }

      public boolean session() {
        return JdbcSessionRecords.task(task);
      }

      public long recordVersion() {
        return ledger.n(task, "record_version");
      }

      public java.util.function.Supplier<String> requestBody(String request) {
        var wire =
            Payloads.read(
                request,
                io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
        return () -> wire.requestBody();
      }

      public String publicSummary(String summary) {
        return ledger.sessions.contracts.publicText(summary, ledger.sessions.sensitive()).content();
      }

      public io.github.lovexiaopeng.jimu.kernel.question.service.QuestionStorageService.Facts
          questionStorage() {
        return new MapperQuestionStorage(ledger, task);
      }

      public io.github.lovexiaopeng.jimu.kernel.todo.service.TodoStorageService.Facts
          todoStorage() {
        return new MapperTodoStorage(ledger, task);
      }

      public io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryStorageService.Facts
          sessionStorage() {
        return new MapperSessionHistoryStorage(ledger.sessions, task);
      }

      public long directoryStorageBytes() {
        return ledger.directoryStorageBytes(task);
      }

      public DirectoryRequest directoryRequest(String request) {
        var p = ledger.directoryProjection(task);
        var wire =
            Payloads.read(
                request,
                io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
        return new DirectoryRequest() {
          public int version() {
            return wire.version();
          }

          public String requestBody() {
            return wire.requestBody();
          }

          public boolean messagesEmpty() {
            return wire.messages().isEmpty();
          }

          public String lastRole() {
            return wire.messages().getLast().role();
          }

          public String lastContent() {
            return wire.messages().getLast().content();
          }

          public String expectedContext() {
            return ledger.directories.context(ledger.s(p, "cwd"), ledger.n(p, "revision"));
          }

          public String cwd() {
            return ledger.s(p, "cwd");
          }
        };
      }

      public MediaBudget lockMediaBudget() {
        var budget = ledger.bucket(lease.tenant());
        return new MediaBudget() {
          public long limit() {
            return ledger.n(budget, "storage_limit");
          }

          public long held() {
            return ledger.storageHeld(budget);
          }

          public void hold(long bytes) {
            ledger
                .mappers
                .budgets()
                .holdMediaCheckpoint(ledger.queryContext(), bytes, lease.tenant());
          }

          public void reserve(
              String reservation, long bytes, String category, String state, String quality) {
            ledger.reserve(
                lease.tenant(), lease.taskId(), reservation, null, category, bytes, state, quality);
          }

          public void settle(String reservation) {
            ledger
                .mappers
                .reservations()
                .settleReservedAmount(ledger.queryContext(), lease.tenant(), reservation);
          }
        };
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
        questionDeliveries.prepare(
            stepId, request, new MapperQuestionDeliveries(ledger, task, lease));
      }

      public void preparedEvent(String step) {
        ledger.event(task, "prepared:" + step, "model.prepared", null);
      }

      public void commitFence() {
        if (ledger.directoryTask(task) || JdbcSessionRecords.task(task))
          ledger.todoCommitFence(lease);
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
        ledger.questionPause(task, lease, TaskState.RESUME_READY, step);
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
