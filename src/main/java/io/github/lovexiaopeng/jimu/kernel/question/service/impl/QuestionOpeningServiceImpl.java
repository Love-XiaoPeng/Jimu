package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionOpening;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionOpeningService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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
                  .contains("ask_user_question")
              || !port.enabled()) throw new TaskFailure(403, "QUESTION_NOT_AUTHORIZED");
          var input = port.input(port.version(), port.rawInput());
          var invocation =
              first(port.invocation(port.tenant(), port.taskId(), port.step(), port.slot()));
          if (!port.tool().equals("ask_user_question")
              || !port.tool().equals(s(invocation, "tool"))
              || !port.providerId().equals(s(invocation, "provider_id"))
              || !port.hash(port.rawInput()).equals(s(invocation, "input_digest"))
              || !port.version().equals(s(invocation, "plugin_version"))
              || !port.digest().equals(s(invocation, "plugin_digest")))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var pin = port.pin(task);
          if (!port.pinVersion(pin).equals(port.version())
              || !port.pinDigest(pin).equals(port.digest())
              || port.pinEpoch(pin) != n(invocation, "installation_epoch"))
            throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
          String invocationId = s(invocation, "id");
          if ("SUCCEEDED".equals(s(invocation, "state"))) {
            if (!port.hash(s(invocation, "output_snapshot")).equals(s(invocation, "output_digest")))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            port.output(port.version(), s(invocation, "output_snapshot"));
            return port.result(false, port.readOutput(s(invocation, "output_snapshot")));
          }
          if (!"PENDING".equals(s(invocation, "state")))
            throw new TaskFailure(409, "INVOCATION_STATE");
          int ordinal = port.ordinal(port.tenant(), port.taskId());
          if (ordinal > 8) throw new TaskFailure(429, "QUESTION_BATCH_LIMIT");
          if (task.get("waiting_question_id") != null)
            throw new TaskFailure(409, "QUESTION_ALREADY_WAITING");
          if (n(task, "tools_used") >= n(task, "max_tools")
              || n(budget, "tool_used") >= n(budget, "tool_limit"))
            throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
          port.storage(task, 16384);
          String requestId = port.id();
          Integer timeout =
              port.version().equals("0.1.0")
                  ? null
                  : port.timeout(input) == null ? 120 : port.timeout(input);
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
              port.timestamp(time(task, "created_at").plusSeconds(604800)));
          port.waiting(requestId, port.tenant(), port.taskId());
          port.budget(port.tenant());
          port.reserve(
              port.tenant(),
              port.taskId(),
              invocationId,
              invocationId,
              "TOOL",
              1,
              "RESERVED",
              "KNOWN");
          if (port.waitingInvocation(port.tenant(), invocationId) != 1)
            throw new TaskFailure(409, "INVOCATION_STATE");
          port.pause(task, "WAITING_USER", null);
          port.event(task, "question-created:" + requestId, "question.created", null);
          port.event(task, "question-wait:" + requestId, "task.waiting_user", null);
          return port.result(true, null);
        });
  }

  private static Map<String, Object> first(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
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
