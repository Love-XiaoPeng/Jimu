package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionListing;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionListingService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class QuestionListingServiceImpl implements QuestionListingService {
  public <Q, A> Map<String, Object> list(QuestionListing<Q, A> port) {
    return port.transaction(
        () -> {
          port.bucket(port.tenant());
          var task = port.visible();
          if (!java.util.Arrays.asList(s(task, "capabilities").split(","))
              .contains("ask_user_question")) return Map.of("version", 1, "items", List.of());
          Instant now = port.now();
          var items = new java.util.ArrayList<Map<String, Object>>();
          for (var q : port.rows(port.tenant(), port.taskId())) {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("requestId", s(q, "id"));
            item.put("questionVersion", n(q, "question_version"));
            item.put("revision", n(q, "revision"));
            item.put("invocationId", s(q, "invocation_id"));
            item.put("callId", s(q, "provider_id"));
            item.put("mode", s(q, "mode"));
            item.put("phase", s(q, "phase"));
            item.put("questions", port.questions(q, task));
            item.put(
                "foregroundDueAt",
                q.get("foreground_due_at") == null
                    ? null
                    : time(q, "foreground_due_at").toString());
            item.put("answerExpiresAt", time(q, "answer_expires_at").toString());
            item.put(
                "answerable",
                port.enabled()
                    && !port.view(task).terminal()
                    && task.get("cancel_requested_at") == null
                    && now.isBefore(time(q, "answer_expires_at"))
                    && List.of("OPEN", "CONTINUED").contains(s(q, "phase")));
            if (q.get("answer_id") != null) {
              item.put("answerId", s(q, "answer_id"));
              item.put("answeredBy", s(q, "answered_actor_id"));
              item.put("answeredAt", time(q, "answered_at").toString());
              item.put("answers", port.readAnswer(s(q, "answer_snapshot")));
              item.put("deliveryState", s(q, "delivery_state"));
            }
            if (q.get("close_code") != null) item.put("closeCode", s(q, "close_code"));
            items.add(item);
          }
          var result = Map.<String, Object>of("version", 1, "items", items);
          if (port.encode(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 524288)
            throw new TaskFailure(409, "QUESTION_RESPONSE_TOO_LARGE");
          return result;
        });
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
