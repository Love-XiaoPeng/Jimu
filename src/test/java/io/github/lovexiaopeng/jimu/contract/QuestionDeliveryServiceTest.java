package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionDeliveries;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionDeliveryServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionDeliveryServiceTest {
  record Message(String role, String content) {}

  static class Port implements QuestionDeliveries<Object, Message> {
    final List<String> calls = new ArrayList<>();
    final Map<String, String> pending = new LinkedHashMap<>();
    final Object wire = new Object();
    List<Message> messages =
        List.of(
            new Message("assistant", "answer"),
            new Message("user", "answer"),
            new Message(null, null));
    boolean question = true;
    int count = 1;
    int failedUpdate = 0;
    int updates;
    String fail;
    final RuntimeException error = new IllegalStateException("original");

    void hit(String n) {
      calls.add(n);
      if (n.equals(fail)) throw error;
    }

    public boolean questionTask() {
      hit("question");
      return question;
    }

    public Object decode(String request) {
      assertEquals("request", request);
      hit("decode");
      return wire;
    }

    public Map<String, String> pending() {
      hit("pending");
      return pending;
    }

    public List<Message> messages(Object wire) {
      assertSame(this.wire, wire);
      hit("messages");
      return messages;
    }

    public String role(Message m) {
      hit("role");
      return m.role();
    }

    public String content(Message m) {
      hit("content");
      return m.content();
    }

    public String tenant() {
      hit("tenant");
      return "tenant";
    }

    public int checkpoint(String step, String tenant, String question) {
      assertEquals("step", step);
      assertEquals("tenant", tenant);
      hit("update:" + question);
      return ++updates == failedUpdate ? 0 : count;
    }

    public void clearContinuation() {
      hit("clear");
    }
  }

  private final QuestionDeliveryServiceImpl service = new QuestionDeliveryServiceImpl();

  void prepare(Port p) {
    service.prepare("step", "request", p);
  }

  @Test
  void nonQuestionNeverDecodes() {
    var p = new Port();
    p.question = false;
    prepare(p);
    assertEquals(List.of("question"), p.calls);
  }

  @Test
  void duplicateContentConsumesEachInEncounterOrderAndShortCircuits() {
    var p = new Port();
    p.pending.put("b", "answer");
    p.pending.put("a", "answer");
    prepare(p);
    assertEquals(
        List.of(
            "question",
            "decode",
            "pending",
            "messages",
            "role",
            "role",
            "content",
            "tenant",
            "update:b",
            "messages",
            "role",
            "role",
            "content",
            "tenant",
            "update:a",
            "clear"),
        p.calls);
  }

  @Test
  void emptyStillDecodesAndClears() {
    var p = new Port();
    prepare(p);
    assertEquals(List.of("question", "decode", "pending", "clear"), p.calls);
  }

  @Test
  void unmatchedStillClears() {
    var p = new Port();
    p.pending.put("a", "other");
    p.messages = List.of(new Message("user", "answer"));
    prepare(p);
    assertEquals(0, p.updates);
    assertEquals("clear", p.calls.getLast());
  }

  @Test
  void laterConflictStopsBeforeClear() {
    var p = new Port();
    p.pending.put("b", "answer");
    p.pending.put("a", "answer");
    p.failedUpdate = 2;
    assertEquals(
        "QUESTION_DELIVERY_CONFLICT", assertThrows(TaskFailure.class, () -> prepare(p)).code());
    assertEquals("update:a", p.calls.getLast());
    assertEquals(2, p.updates);
  }

  @Test
  void everyNonOneCountConflicts() {
    for (int count : List.of(0, 2, -1)) {
      var p = new Port();
      p.pending.put("a", "answer");
      p.count = count;
      assertEquals(
          "QUESTION_DELIVERY_CONFLICT", assertThrows(TaskFailure.class, () -> prepare(p)).code());
      assertEquals("update:a", p.calls.getLast());
    }
  }

  @Test
  void originalFailuresPropagateWithoutClear() {
    for (String stage :
        List.of(
            "question",
            "decode",
            "pending",
            "messages",
            "role",
            "content",
            "tenant",
            "update:a",
            "clear")) {
      var p = new Port();
      p.pending.put("a", "answer");
      p.fail = stage;
      assertSame(p.error, assertThrows(RuntimeException.class, () -> prepare(p)));
      assertEquals(stage, p.calls.getLast());
    }
  }

  @Test
  void originalNullOrderRemains() {
    var p = new Port();
    p.pending.put("a", null);
    p.messages = List.of(new Message("assistant", null));
    prepare(p);
    assertEquals(0, p.updates);
    p.calls.clear();
    p.messages = List.of(new Message("user", null));
    assertThrows(NullPointerException.class, () -> prepare(p));
    assertEquals("content", p.calls.getLast());
  }
}
