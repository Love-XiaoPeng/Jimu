package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.api.QuestionDeliveryState;
import io.github.lovexiaopeng.jimu.kernel.question.api.QuestionPhase;
import io.github.lovexiaopeng.jimu.kernel.question.api.QuestionPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAnswer;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAnswer.Outcome;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionAnswerService;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger.Submission;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class QuestionAnswerServiceImpl implements QuestionAnswerService {
  public <Q, A, B, T> Submission answer(QuestionAnswer<Q, A, B, T> port) {
    var outcome =
        port.transaction(
            () -> {
              port.bucket(port.tenant());
              var task = port.visible();
              var found = port.locked(port.tenant(), port.taskId(), port.requestId());
              if (found.isEmpty()) throw new TaskFailure(404, QuestionErrorCode.QUESTION_NOT_FOUND);
              var q = found.getFirst();
              var previous = port.previous(port.tenant(), port.taskId(), port.actor(), port.key());
              if (!previous.isEmpty()) {
                var old = previous.getFirst();
                String requestDigest;
                try {
                  requestDigest =
                      port.digest(port.requestId(), port.canonical(port.inputQuestions(q, task)));
                } catch (TaskFailure invalid) {
                  throw new TaskFailure(409, CommonErrorCode.IDEMPOTENCY_CONFLICT);
                }
                if (!requestDigest.equals(s(old, "answer_request_digest")))
                  throw new TaskFailure(409, CommonErrorCode.IDEMPOTENCY_CONFLICT);
                return new Outcome(new Submission(true, port.receipt(old)), null);
              }
              Instant now = port.now();
              if (!now.isBefore(time(q, "answer_expires_at"))) {
                port.close(task, TaskErrorCode.DEADLINE_EXCEEDED);
                if (!port.view(task).terminal())
                  port.terminal(task, TaskState.FAILED, TaskErrorCode.DEADLINE_EXCEEDED);
                return new Outcome(null, new TaskFailure(410, QuestionErrorCode.QUESTION_EXPIRED));
              }
              if (port.view(task).terminal()
                  || task.get("cancel_requested_at") != null
                  || QuestionPhase.CLOSED.equals(s(q, "phase")))
                throw new TaskFailure(409, QuestionErrorCode.QUESTION_CLOSED);
              if (!port.enabled())
                throw new TaskFailure(403, QuestionErrorCode.QUESTION_FEATURE_DISABLED);
              if (q.get("foreground_due_at") != null
                  && !now.isBefore(time(q, "foreground_due_at"))
                  && QuestionPhase.OPEN.equals(s(q, "phase"))) {
                port.continueQuestion(task, q);
                q = first(port.reload(port.tenant(), port.requestId()));
              }
              if (q.get("answer_id") != null)
                return new Outcome(
                    null, new TaskFailure(409, QuestionErrorCode.QUESTION_ALREADY_ANSWERED));
              if (port.questionVersion() != 1)
                return new Outcome(
                    null, new TaskFailure(409, QuestionErrorCode.QUESTION_VERSION_UNSUPPORTED));
              if (port.expectedRevision() != n(q, "revision"))
                return new Outcome(
                    null, new TaskFailure(409, QuestionErrorCode.QUESTION_REVISION_CONFLICT));
              A answers;
              try {
                answers = port.canonical(port.inputQuestions(q, task));
              } catch (TaskFailure invalid) {
                return new Outcome(null, invalid);
              }
              String answer = port.encode(answers);
              String requestDigest = port.digest(port.requestId(), answers);
              String answerId = port.id();
              boolean foreground = QuestionPhase.OPEN.equals(s(q, "phase"));
              int seq = port.sequence(port.tenant(), port.taskId());
              var receipt =
                  Map.<String, Object>of(
                      "version",
                      1,
                      "requestId",
                      port.requestId(),
                      "answerId",
                      answerId,
                      "questionVersion",
                      1,
                      "acceptedRevision",
                      n(q, "revision") + 1,
                      "answeredBy",
                      port.actor(),
                      "answeredAt",
                      now.toString(),
                      "delivery",
                      foreground ? "TOOL_RESULT" : "USER_MESSAGE");
              try {
                port.storage(
                    task,
                    answer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + port.encode(receipt)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            .length
                        + QuestionPolicyV1.RECEIPT_RESERVE_BYTES);
              } catch (TaskFailure invalid) {
                return new Outcome(null, invalid);
              }
              if (port.update(
                      answerId,
                      port.actor(),
                      port.timestamp(now),
                      seq,
                      answer,
                      port.hash(answer),
                      port.key(),
                      requestDigest,
                      port.expectedRevision(),
                      port.encode(receipt),
                      foreground
                          ? QuestionDeliveryState.CHECKPOINTED
                          : QuestionDeliveryState.PENDING,
                      port.tenant(),
                      port.requestId(),
                      n(q, "revision"))
                  != 1) throw new TaskFailure(409, QuestionErrorCode.QUESTION_REVISION_CONFLICT);
              if (foreground) {
                String output = port.encode(port.batch(answers));
                port.complete(task, q, output);
                port.resume(task, port.requestId());
              }
              port.event(task, "question-answer:" + answerId, "question.answered", null);
              return new Outcome(new Submission(false, receipt), null);
            });
    if (outcome.rejection() != null) throw outcome.rejection();
    return outcome.value();
  }

  private static Map<String, Object> first(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static Instant time(Map<String, Object> row, String key) {
    return row.get(key) == null
        ? null
        : ((java.time.LocalDateTime) row.get(key)).toInstant(java.time.ZoneOffset.UTC);
  }
}
