package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.question.api.QuestionPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionOpening;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionOpeningService;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 完整新问题受理和历史结果资格，沿原租约事务执行。 */
public final class QuestionOpeningServiceImpl implements QuestionOpeningService {
  public <I, P, B, R, T> R openOrResult(QuestionOpening<I, P, B, R, T> port) {
    return port.transaction(
        () -> {
          var task = port.fenced();
          var budget = port.bucket(port.tenant());
          if (!java.util.Arrays.asList(s(task, "capabilities").split(","))
                  .contains(ToolCapability.ASK_USER_QUESTION)
              || !port.enabled())
            throw new TaskFailure(403, QuestionErrorCode.QUESTION_NOT_AUTHORIZED);
          var input = port.input(port.version(), port.rawInput());
          var invocation =
              first(port.invocation(port.tenant(), port.taskId(), port.step(), port.slot()));
          if (!port.tool().equals(ToolCapability.ASK_USER_QUESTION)
              || !port.tool().equals(s(invocation, "tool"))
              || !port.providerId().equals(s(invocation, "provider_id"))
              || !port.hash(port.rawInput()).equals(s(invocation, "input_digest"))
              || !port.version().equals(s(invocation, "plugin_version"))
              || !port.digest().equals(s(invocation, "plugin_digest")))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
          var pin = port.pin(task);
          if (!port.pinVersion(pin).equals(port.version())
              || !port.pinDigest(pin).equals(port.digest())
              || port.pinEpoch(pin) != n(invocation, "installation_epoch"))
            throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
          String invocationId = s(invocation, "id");
          if (InvocationState.SUCCEEDED.equals(s(invocation, "state"))) {
            if (!port.hash(s(invocation, "output_snapshot")).equals(s(invocation, "output_digest")))
              throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
            port.output(port.version(), s(invocation, "output_snapshot"));
            return port.result(false, port.readOutput(s(invocation, "output_snapshot")));
          }
          if (!InvocationState.PENDING.equals(s(invocation, "state")))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          int ordinal = port.ordinal(port.tenant(), port.taskId());
          if (ordinal > QuestionPolicyV1.BATCH_QUESTIONS)
            throw new TaskFailure(429, QuestionErrorCode.QUESTION_BATCH_LIMIT);
          if (task.get("waiting_question_id") != null)
            throw new TaskFailure(409, QuestionErrorCode.QUESTION_ALREADY_WAITING);
          if (n(task, "tools_used") >= n(task, "max_tools")
              || n(budget, "tool_used") >= n(budget, "tool_limit"))
            throw new TaskFailure(429, ToolErrorCode.TOOL_BUDGET_EXCEEDED);
          port.storage(task, QuestionPolicyV1.RECEIPT_RESERVE_BYTES);
          String requestId = port.id();
          Integer timeout =
              port.version().equals("0.1.0")
                  ? null
                  : port.timeout(input) == null
                      ? QuestionPolicyV1.DEFAULT_TIMEOUT_SECONDS
                      : port.timeout(input);
          port.insert(
              port.tenant(),
              requestId,
              port.taskId(),
              port.actor(),
              invocationId,
              ordinal,
              s(task, "question_binding_digest"),
              port.mode(port.version()),
              timeout,
              port.rawInput(),
              port.hash(port.rawInput()),
              timeout,
              timeout,
              timeout,
              task.get("deadline"),
              port.timestamp(
                  time(task, "created_at").plusSeconds(QuestionPolicyV1.RETENTION_SECONDS)));
          port.waiting(requestId, port.tenant(), port.taskId());
          port.budget(port.tenant());
          port.reserve(
              port.tenant(),
              port.taskId(),
              invocationId,
              invocationId,
              ReservationCategory.TOOL,
              1,
              ReservationState.RESERVED,
              UsageQuality.KNOWN);
          if (port.waitingInvocation(port.tenant(), invocationId) != 1)
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          port.pause(task, TaskState.WAITING_USER, null);
          port.event(task, "question-created:" + requestId, "question.created", null);
          port.event(task, "question-wait:" + requestId, "task.waiting_user", null);
          return port.result(true, null);
        });
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
