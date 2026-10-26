package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionListing;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionListingServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class QuestionListingServiceTest {
  final QuestionListingServiceImpl service = new QuestionListingServiceImpl();
  static final Instant NOW = Instant.parse("2026-10-25T23:59:59.123456Z");
  static final LocalDateTime END = LocalDateTime.parse("2026-10-26T00:00:01.654321");

  static class Row extends AbstractMap<String, Object> {
    final H h;
    final String name;
    final Map<String, Object> values = new HashMap<>();
    final Map<String, Integer> counts = new HashMap<>();
    final Map<String, List<Object>> changes = new HashMap<>();

    Row(H h, String name) {
      this.h = h;
      this.name = name;
    }

    public Object get(Object key) {
      h.at(name + "." + key);
      int n = counts.merge(key.toString(), 1, Integer::sum);
      var list = changes.get(key);
      return list == null ? values.get(key) : list.get(Math.min(n - 1, list.size() - 1));
    }

    public Set<Entry<String, Object>> entrySet() {
      throw new AssertionError("No whole-row reads or copy");
    }
  }

  static class H implements QuestionListing<Object, Object> {
    final List<String> trace = new ArrayList<>();
    final Row task = new Row(this, "task"), q = new Row(this, "q");
    List<Map<String, Object>> rows;
    boolean enabled = true, terminal = false;
    String encoded = "{}";
    Map<String, Object> result;
    final Object questions = new Object(), answer = new Object();
    String failAt;
    Throwable failure;
    int enabledCount, views;

    H() {
      task.values.put("capabilities", "ask_user_question");
      q.values.putAll(
          Map.of(
              "id",
              "q",
              "question_version",
              2,
              "revision",
              3L,
              "invocation_id",
              "i",
              "provider_id",
              "call",
              "mode",
              "timed",
              "phase",
              "OPEN",
              "answer_expires_at",
              END));
      rows = List.of(q);
    }

    void at(String s) {
      trace.add(s);
      if (s.equals(failAt)) {
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
      }
    }

    public Map<String, Object> transaction(Supplier<Map<String, Object>> work) {
      at("tx");
      return work.get();
    }

    public String tenant() {
      at("tenant");
      return "identity-tenant";
    }

    public String taskId() {
      at("taskId");
      return "original-task";
    }

    public void bucket(String tenant) {
      assertEquals("identity-tenant", tenant);
      at("bucket");
    }

    public Map<String, Object> visible() {
      at("visible");
      return task;
    }

    public Instant now() {
      at("now");
      return NOW;
    }

    public List<Map<String, Object>> rows(String tenant, String id) {
      assertEquals("identity-tenant", tenant);
      assertEquals("original-task", id);
      at("query");
      return rows;
    }

    public Object questions(Map<String, Object> row, Map<String, Object> actual) {
      assertSame(q, row);
      assertSame(task, actual);
      at("input");
      return questions;
    }

    public boolean enabled() {
      at("flag");
      enabledCount++;
      return enabled;
    }

    public View view(Map<String, Object> actual) {
      assertSame(task, actual);
      at("view");
      views++;
      return new View(
          null, terminal ? "FAILED" : "WAITING_USER", 0, null, null, null, null, null, List.of());
    }

    public Object readAnswer(String raw) {
      assertEquals("raw-answer", raw);
      at("readAnswer");
      return answer;
    }

    public String encode(Map<String, Object> actual) {
      at("encode");
      result = actual;
      return encoded;
    }
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> items(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("items");
  }

  @Test
  void completeOrderMutableFieldsAndOriginalReferences() {
    var h = new H();
    var result = service.list(h);
    var item = items(result).getFirst();
    assertSame(result, h.result);
    assertEquals(1, result.get("version"));
    assertEquals(
        List.of(
            "requestId",
            "questionVersion",
            "revision",
            "invocationId",
            "callId",
            "mode",
            "phase",
            "questions",
            "foregroundDueAt",
            "answerExpiresAt",
            "answerable"),
        new ArrayList<>(item.keySet()));
    assertSame(h.questions, item.get("questions"));
    assertNull(item.get("foregroundDueAt"));
    assertEquals(2L, item.get("questionVersion"));
    assertEquals(3L, item.get("revision"));
    assertEquals("2026-10-26T00:00:01.654321Z", item.get("answerExpiresAt"));
    assertEquals(true, item.get("answerable"));
    assertEquals(
        List.of(
            "tx",
            "tenant",
            "bucket",
            "visible",
            "task.capabilities",
            "now",
            "tenant",
            "taskId",
            "query",
            "q.id",
            "q.question_version",
            "q.revision",
            "q.invocation_id",
            "q.provider_id",
            "q.mode",
            "q.phase",
            "input",
            "q.foreground_due_at",
            "q.answer_expires_at",
            "q.answer_expires_at",
            "flag",
            "view",
            "task.cancel_requested_at",
            "q.answer_expires_at",
            "q.answer_expires_at",
            "q.phase",
            "q.answer_id",
            "q.close_code",
            "encode"),
        h.trace);
    item.put("mutable", null);
    items(result).add(new HashMap<>());
    assertEquals(2, items(result).size());
  }

  @Test
  void nonQuestionAndZeroRowsKeepDistinctPaths() {
    var h = new H();
    h.task.values.put("capabilities", "todo_write");
    assertTrue(items(service.list(h)).isEmpty());
    assertEquals(List.of("tx", "tenant", "bucket", "visible", "task.capabilities"), h.trace);
    assertNull(h.result);
    h = new H();
    h.rows = List.of();
    assertTrue(items(service.list(h)).isEmpty());
    assertEquals(
        List.of(
            "tx",
            "tenant",
            "bucket",
            "visible",
            "task.capabilities",
            "now",
            "tenant",
            "taskId",
            "query",
            "encode"),
        h.trace);
    h = new H();
    h.task.values.put("capabilities", "todo_write,ask_user_question,ask_user_question");
    h.rows = List.of(h.q, h.q);
    assertEquals(2, items(service.list(h)).size());
    assertEquals(1, h.trace.stream().filter("now"::equals).count());
    assertEquals(2, h.enabledCount);
    assertEquals(2, h.views);
  }

  @Test
  void everyAnswerableShortCircuitRemainsAfterDisplayAndInput() {
    for (int blocked = 0; blocked < 5; blocked++) {
      var h = new H();
      if (blocked == 0) h.enabled = false;
      if (blocked == 1) h.terminal = true;
      if (blocked == 2) h.task.values.put("cancel_requested_at", NOW);
      if (blocked == 3)
        h.q.values.put("answer_expires_at", LocalDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
      if (blocked == 4) h.q.values.put("phase", "CLOSED");
      assertEquals(false, items(service.list(h)).getFirst().get("answerable"));
      assertTrue(h.trace.indexOf("input") < h.trace.indexOf("flag"));
      assertEquals(blocked > 0 ? 1 : 0, h.views);
      assertEquals(blocked > 1 ? 1 : 0, h.task.counts.getOrDefault("cancel_requested_at", 0));
      assertEquals(blocked > 2 ? 4 : 2, h.q.counts.get("answer_expires_at"));
      assertEquals(blocked > 3 ? 2 : 1, h.q.counts.get("phase"));
    }
  }

  @Test
  void eachRowFlagAndRepeatedReadsAreNotCached() {
    var h =
        new H() {
          public boolean enabled() {
            boolean result = super.enabled();
            return result && enabledCount == 1;
          }
        };
    h.rows = List.of(h.q, h.q);
    h.q.changes.put("phase", List.of("OPEN", "CLOSED", "CONTINUED"));
    h.q.changes.put("answer_expires_at", List.of(END, END, END, END));
    var list = items(service.list(h));
    assertEquals("OPEN", list.getFirst().get("phase"));
    assertEquals(false, list.getFirst().get("answerable"));
    assertEquals("CONTINUED", list.get(1).get("phase"));
    assertEquals(1, h.views);
    assertEquals(2, h.enabledCount);
    assertEquals(6, h.q.counts.get("answer_expires_at"));
  }

  @Test
  void optionalAnswerAndCloseUseIndependentReadsAndOriginalTimeNullSemantics() {
    var h = new H();
    h.q.values.putAll(
        Map.of(
            "foreground_due_at",
            END,
            "answered_actor_id",
            "actor",
            "answered_at",
            END,
            "answer_snapshot",
            "raw-answer",
            "delivery_state",
            "PENDING"));
    h.q.changes.put("answer_id", java.util.Arrays.asList("present", null));
    h.q.changes.put("close_code", java.util.Arrays.asList("present", null));
    var item = items(service.list(h)).getFirst();
    assertEquals(
        List.of(
            "requestId",
            "questionVersion",
            "revision",
            "invocationId",
            "callId",
            "mode",
            "phase",
            "questions",
            "foregroundDueAt",
            "answerExpiresAt",
            "answerable",
            "answerId",
            "answeredBy",
            "answeredAt",
            "answers",
            "deliveryState",
            "closeCode"),
        new ArrayList<>(item.keySet()));
    assertNull(item.get("answerId"));
    assertNull(item.get("closeCode"));
    assertSame(h.answer, item.get("answers"));
    assertEquals(3, h.q.counts.get("foreground_due_at"));
    assertEquals(2, h.q.counts.get("answered_at"));
    h = new H();
    h.q.changes.put("answer_expires_at", java.util.Arrays.asList(END, null));
    final var bad = h;
    assertThrows(NullPointerException.class, () -> service.list(bad));
    assertFalse(h.trace.contains("flag"));
  }

  @Test
  void firstErrorsKeepOriginalInstancesAndStopAtEveryOperation() {
    var happy = new H();
    service.list(happy);
    for (String at : happy.trace.stream().distinct().toList())
      for (Throwable failure :
          List.of(
              new IllegalStateException(at),
              new AssertionError(at),
              new TaskFailure(409, "ORIGINAL"))) {
        var h = new H();
        h.failAt = at;
        h.failure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> service.list(h)));
        assertEquals(happy.trace.subList(0, happy.trace.indexOf(at) + 1), h.trace);
      }
    var answered = new H();
    answered.q.values.putAll(
        Map.of(
            "answer_id",
            "a",
            "answered_actor_id",
            "u",
            "answered_at",
            END,
            "answer_snapshot",
            "raw-answer",
            "delivery_state",
            "PENDING",
            "close_code",
            "EXPIRED"));
    service.list(answered);
    for (String at :
        List.of(
            "q.answered_actor_id",
            "q.answered_at",
            "q.answer_snapshot",
            "readAnswer",
            "q.delivery_state",
            "q.close_code")) {
      var h = new H();
      h.q.values.putAll(answered.q.values);
      h.failAt = at;
      h.failure = new AssertionError(at);
      assertSame(h.failure, assertThrows(AssertionError.class, () -> service.list(h)));
      assertEquals(answered.trace.subList(0, answered.trace.indexOf(at) + 1), h.trace);
    }
  }

  @Test
  void castsNullAndNumberLongValueStayAtOriginalReach() {
    for (String key :
        List.of(
            "id",
            "question_version",
            "revision",
            "invocation_id",
            "provider_id",
            "mode",
            "phase",
            "answer_expires_at")) {
      var h = new H();
      h.q.values.put(key, new Object());
      assertThrows(ClassCastException.class, () -> service.list(h));
      assertFalse(h.trace.contains("encode"));
    }
    var h = new H();
    h.q.values.put("question_version", null);
    final var missing = h;
    assertThrows(NullPointerException.class, () -> service.list(missing));
    assertEquals("q.question_version", h.trace.getLast());
    h = new H();
    h.task.values.put("capabilities", null);
    final var caps = h;
    assertThrows(NullPointerException.class, () -> service.list(caps));
    assertFalse(h.trace.contains("now"));
    h = new H();
    h.q.values.put("revision", -1.9);
    assertEquals(-1L, items(service.list(h)).getFirst().get("revision"));
  }

  @Test
  void finalLimitIsExactUtf8AndOnlyAfterCompleteRows() {
    for (String raw : List.of("x".repeat(524288), "中".repeat(174762) + "xx")) {
      var h = new H();
      h.encoded = raw;
      var actual = service.list(h);
      assertSame(actual, h.result);
      assertEquals(524288, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      assertEquals("encode", h.trace.getLast());
    }
    var h = new H();
    h.encoded = "中".repeat(174763);
    var failure = assertThrows(TaskFailure.class, () -> service.list(h));
    assertEquals(409, failure.status());
    assertEquals("QUESTION_RESPONSE_TOO_LARGE", failure.code());
    assertInstanceOf(java.util.LinkedHashMap.class, items(h.result).getFirst());
  }
}
