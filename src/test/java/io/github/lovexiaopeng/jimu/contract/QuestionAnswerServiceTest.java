package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAnswer;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionAnswerServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class QuestionAnswerServiceTest {
  final QuestionAnswerServiceImpl service = new QuestionAnswerServiceImpl();
  static final Instant NOW = Instant.parse("2026-10-26T01:02:03.456789Z");

  static class Row extends AbstractMap<String, Object> {
    final H h;
    final String name;
    final Map<String, Object> values = new HashMap<>();
    final Map<String, List<Object>> changes = new HashMap<>();
    final Map<String, Integer> counts = new HashMap<>();

    Row(H h, String name) {
      this.h = h;
      this.name = name;
    }

    public Object get(Object key) {
      h.hit(name + "." + key);
      int n = counts.merge(key.toString(), 1, Integer::sum);
      var v = changes.get(key);
      return v == null ? values.get(key) : v.get(Math.min(n - 1, v.size() - 1));
    }

    public Set<Entry<String, Object>> entrySet() {
      throw new AssertionError("whole row consumed or copied");
    }
  }

  static class H implements QuestionAnswer<Object, Object, Object, Object> {
    final List<String> trace = new ArrayList<>();
    final Map<String, Integer> counts = new HashMap<>();
    final Row task = new Row(this, "task"),
        q = new Row(this, "q"),
        old = new Row(this, "old"),
        fresh = new Row(this, "fresh");
    List<Map<String, Object>> found = List.of(q), previous = List.of(), reloaded = List.of(fresh);
    final Object questions = new Object(),
        answers = new Object(),
        batch = new Object(),
        stamp = new Object();
    final Map<String, Object> replayReceipt = new HashMap<>();
    Map<String, Object> receipt;
    Outcome returned;
    Object[] bindings;
    int updateCount = 1, version = 1, revision = 2;
    Integer sequence = 4;
    boolean enabled = true, terminal = false;
    String failAt;
    Throwable failure;
    long storageBytes;

    H() {
      q.values.putAll(
          Map.of(
              "phase",
              "OPEN",
              "revision",
              2L,
              "answer_expires_at",
              LocalDateTime.ofInstant(NOW.plusSeconds(10), java.time.ZoneOffset.UTC)));
      fresh.values.putAll(q.values);
      fresh.values.put("phase", "CONTINUED");
      fresh.values.put("revision", 3L);
      old.values.put("answer_request_digest", "digest");
    }

    void hit(String at) {
      String name = at + "#" + counts.merge(at, 1, Integer::sum);
      trace.add(name);
      if (name.equals(failAt)) {
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
      }
    }

    public Outcome transaction(Supplier<Outcome> work) {
      hit("tx");
      returned = work.get();
      hit("tx-return");
      return returned;
    }

    public String tenant() {
      hit("tenant");
      return "tenant" + counts.get("tenant");
    }

    public String actor() {
      hit("actor");
      return "actor" + counts.get("actor");
    }

    public String taskId() {
      hit("taskId");
      return "task";
    }

    public String requestId() {
      hit("requestId");
      return "request";
    }

    public String key() {
      hit("key");
      return "key";
    }

    public int questionVersion() {
      hit("questionVersion");
      return version;
    }

    public int expectedRevision() {
      hit("expectedRevision");
      return revision;
    }

    public void bucket(String t) {
      assertEquals("tenant1", t);
      hit("bucket");
    }

    public Map<String, Object> visible() {
      hit("visible");
      return task;
    }

    public List<Map<String, Object>> locked(String t, String id, String request) {
      hit("locked");
      assertEquals("tenant2", t);
      assertEquals("task", id);
      assertEquals("request", request);
      return found;
    }

    public List<Map<String, Object>> previous(String t, String id, String actor, String key) {
      hit("previous");
      assertEquals("tenant3", t);
      assertEquals("actor1", actor);
      return previous;
    }

    public List<Map<String, Object>> reload(String t, String request) {
      hit("reload");
      assertEquals("tenant4", t);
      return reloaded;
    }

    public Object inputQuestions(Map<String, Object> row, Map<String, Object> t) {
      hit("input");
      assertSame(task, t);
      assertSame(counts.containsKey("reload") ? fresh : q, row);
      return questions;
    }

    public Object canonical(Object input) {
      hit("canonical");
      assertSame(questions, input);
      return answers;
    }

    public String digest(String request, Object value) {
      hit("digest");
      assertSame(answers, value);
      return "digest";
    }

    public Map<String, Object> receipt(Map<String, Object> row) {
      hit("receipt");
      assertSame(old, row);
      return replayReceipt;
    }

    public Instant now() {
      hit("now");
      return NOW;
    }

    public void close(Map<String, Object> t, String code) {
      assertSame(task, t);
      assertEquals("DEADLINE_EXCEEDED", code);
      hit("close");
    }

    public View view(Map<String, Object> t) {
      assertSame(task, t);
      hit("view");
      return new View(
          "task", terminal ? "FAILED" : "WAITING_USER", 1, NOW, NOW, null, null, null, List.of());
    }

    public void terminal(Map<String, Object> t, String state, String code) {
      assertSame(task, t);
      assertEquals("FAILED", state);
      assertEquals("DEADLINE_EXCEEDED", code);
      hit("terminal");
    }

    public boolean enabled() {
      hit("enabled");
      return enabled;
    }

    public void continueQuestion(Map<String, Object> t, Map<String, Object> row) {
      assertSame(task, t);
      assertSame(q, row);
      hit("continue");
    }

    @SuppressWarnings("unchecked")
    public String encode(Object value) {
      if (value == answers) {
        hit("encode-answer");
        return "答案🙂";
      }
      if (value == batch) {
        hit("encode-output");
        return "output";
      }
      hit("encode-receipt");
      if (receipt == null) receipt = (Map<String, Object>) value;
      else assertSame(receipt, value);
      return counts.get("encode-receipt") == 1 ? "收据🙂" : "second-receipt";
    }

    public String id() {
      hit("id");
      return "answer";
    }

    public Integer sequence(String t, String id) {
      hit("sequence");
      return sequence;
    }

    public void storage(Map<String, Object> t, long bytes) {
      assertSame(task, t);
      storageBytes = bytes;
      hit("storage");
    }

    public String hash(String raw) {
      assertEquals("答案🙂", raw);
      hit("hash");
      return "answer-hash";
    }

    public Object timestamp(Instant time) {
      assertSame(NOW, time);
      hit("timestamp");
      return stamp;
    }

    public int update(
        String answerId,
        String actor,
        Object time,
        int seq,
        String answer,
        String hash,
        String key,
        String digest,
        int source,
        String encoded,
        String delivery,
        String tenant,
        String request,
        long revision) {
      hit("update");
      bindings =
          new Object[] {
            answerId, actor, time, seq, answer, hash, key, digest, source, encoded, delivery,
            tenant, request, revision
          };
      return updateCount;
    }

    public Object batch(Object value) {
      assertSame(answers, value);
      hit("batch");
      return batch;
    }

    public void complete(Map<String, Object> t, Map<String, Object> row, String output) {
      assertSame(task, t);
      assertSame(q, row);
      assertEquals("output", output);
      hit("complete");
    }

    public void resume(Map<String, Object> t, String request) {
      assertSame(task, t);
      assertEquals("request", request);
      hit("resume");
    }

    public void event(Map<String, Object> t, String fact, String type, String code) {
      assertSame(task, t);
      assertEquals("question-answer:answer", fact);
      assertEquals("question.answered", type);
      assertNull(code);
      hit("event");
    }
  }

  H route(String name) {
    var h = new H();
    switch (name) {
      case "replay":
        h.previous = List.of(h.old);
        break;
      case "expired":
        h.q.values.put("answer_expires_at", LocalDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
        break;
      case "due":
        h.q.values.put("foreground_due_at", LocalDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
        break;
      case "late":
        h.q.values.put("phase", "CONTINUED");
        break;
      default:
        break;
    }
    return h;
  }

  @Test
  void foregroundOwnsExactBindOrderRepeatedMembersReceiptEncodingsAndOriginalReferences() {
    var h = new H();
    h.q.changes.put("revision", List.of(2L, 7L, 11L));
    var result = service.answer(h);
    assertFalse(result.replay());
    assertSame(h.receipt, result.receipt());
    assertSame(result, h.returned.value());
    assertNull(h.returned.rejection());
    assertEquals(
        Map.of(
            "version",
            1,
            "requestId",
            "request",
            "answerId",
            "answer",
            "questionVersion",
            1,
            "acceptedRevision",
            8L,
            "answeredBy",
            "actor2",
            "answeredAt",
            NOW.toString(),
            "delivery",
            "TOOL_RESULT"),
        h.receipt);
    assertEquals(
        Arrays.asList(
            "answer",
            "actor3",
            h.stamp,
            4,
            "答案🙂",
            "answer-hash",
            "key",
            "digest",
            2,
            "second-receipt",
            "CHECKPOINTED",
            "tenant5",
            "request",
            11L),
        Arrays.asList(h.bindings));
    assertEquals(
        "答案🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + "收据🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + 16384,
        h.storageBytes);
    assertBefore(h, "timestamp#1", "hash#1");
    assertBefore(h, "hash#1", "expectedRevision#2");
    assertBefore(h, "expectedRevision#2", "encode-receipt#2");
    assertBefore(h, "encode-receipt#2", "tenant#5");
    assertBefore(h, "update#1", "batch#1");
    assertBefore(h, "complete#1", "resume#1");
    assertBefore(h, "resume#1", "event#1");
    assertBefore(h, "event#1", "tx-return#1");
  }

  @Test
  void previousFirstRowReplaysBeforeClockAndDoesNotConsumeAdditionalRows() {
    var h = route("replay");
    h.previous = Arrays.asList(h.old, null);
    h.found = Arrays.asList(h.q, null);
    h.enabled = false;
    h.version = 9;
    var result = service.answer(h);
    assertTrue(result.replay());
    assertSame(h.replayReceipt, result.receipt());
    assertFalse(h.trace.contains("now#1"));
    assertFalse(h.trace.contains("questionVersion#1"));
    var bad = route("replay");
    bad.old.values.put("answer_request_digest", "other");
    assertEquals(
        "IDEMPOTENCY_CONFLICT", assertThrows(TaskFailure.class, () -> service.answer(bad)).code());
    assertNull(bad.returned);
  }

  @Test
  void dueReplacesOriginalQuestionAndReturnsStaleRevisionOnlyAfterTransaction() {
    var h = route("due");
    final var first = h;
    var e = assertThrows(TaskFailure.class, () -> service.answer(first));
    assertEquals("QUESTION_REVISION_CONFLICT", e.code());
    assertSame(e, h.returned.rejection());
    assertEquals("tx-return#1", h.trace.getLast());
    assertBefore(h, "continue#1", "reload#1");
    assertFalse(h.trace.contains("input#1"));
    h = route("due");
    h.reloaded = List.of();
    final var empty = h;
    assertEquals("NOT_FOUND", assertThrows(TaskFailure.class, () -> service.answer(empty)).code());
    assertNull(h.returned);
  }

  @Test
  void expiryEqualClosesThenTerminalAndRejectsOutsideTx() {
    var h = route("expired");
    final var first = h;
    var e = assertThrows(TaskFailure.class, () -> service.answer(first));
    assertEquals(410, e.status());
    assertEquals("QUESTION_EXPIRED", e.code());
    assertSame(e, h.returned.rejection());
    assertBefore(h, "close#1", "view#1");
    assertBefore(h, "view#1", "terminal#1");
    assertEquals("tx-return#1", h.trace.getLast());
    h = route("expired");
    h.terminal = true;
    final var terminal = h;
    assertThrows(TaskFailure.class, () -> service.answer(terminal));
    assertFalse(h.trace.contains("terminal#1"));
  }

  @Test
  void originalShortCircuitsAndNoNewPhaseWhitelist() {
    var h = new H();
    h.terminal = true;
    assertEquals(
        "QUESTION_CLOSED", assertThrows(TaskFailure.class, () -> service.answer(h)).code());
    assertFalse(h.trace.contains("task.cancel_requested_at#1"));
    var cancelled = new H();
    cancelled.task.values.put("cancel_requested_at", NOW);
    assertThrows(TaskFailure.class, () -> service.answer(cancelled));
    assertFalse(cancelled.trace.contains("q.phase#1"));
    var closed = new H();
    closed.q.values.put("phase", "CLOSED");
    assertThrows(TaskFailure.class, () -> service.answer(closed));
    assertFalse(closed.trace.contains("enabled#1"));
    var disabled = new H();
    disabled.enabled = false;
    assertEquals(
        "QUESTION_FEATURE_DISABLED",
        assertThrows(TaskFailure.class, () -> service.answer(disabled)).code());
    assertFalse(disabled.trace.contains("q.foreground_due_at#1"));
    var unusual = new H();
    unusual.q.values.put("phase", "OTHER_SYNTHETIC");
    var result = service.answer(unusual);
    assertEquals("USER_MESSAGE", result.receipt().get("delivery"));
    assertFalse(unusual.trace.contains("complete#1"));
  }

  @Test
  void allReturnedRejectionsKeepExactOrderAndOutsideThrow() {
    for (String kind : List.of("answer", "version", "revision", "canonical", "storage")) {
      var h = new H();
      TaskFailure original = new TaskFailure(422, "ORIGINAL");
      switch (kind) {
        case "answer":
          h.q.values.put("answer_id", "already");
          break;
        case "version":
          h.version = 0;
          break;
        case "revision":
          h.revision = 9;
          break;
        default:
          h.failAt = kind + "#1";
          h.failure = original;
      }
      var e = assertThrows(TaskFailure.class, () -> service.answer(h));
      assertSame(e, h.returned.rejection());
      assertEquals("tx-return#1", h.trace.getLast());
      assertFalse(h.trace.contains("update#1"));
      if (kind.equals("canonical") || kind.equals("storage")) assertSame(original, e);
    }
  }

  @Test
  void exactOneUpdateOnlyAndNoCompensationOrLaterCallbacks() {
    for (int count : List.of(0, -1, 2)) {
      var h = new H();
      h.updateCount = count;
      var e = assertThrows(TaskFailure.class, () -> service.answer(h));
      assertEquals("QUESTION_REVISION_CONFLICT", e.code());
      assertEquals("update#1", h.trace.getLast());
      assertNull(h.returned);
    }
    var h = route("late");
    service.answer(h);
    assertFalse(h.trace.contains("batch#1"));
    assertFalse(h.trace.contains("resume#1"));
    assertEquals("PENDING", h.bindings[10]);
    assertTrue(h.trace.contains("event#1"));
  }

  @Test
  void originalNullCastsTimeAndSingleSequenceUnboxingFailAtReachedPoint() {
    var empty = new H();
    empty.found = List.of();
    assertEquals(
        "QUESTION_NOT_FOUND", assertThrows(TaskFailure.class, () -> service.answer(empty)).code());
    assertFalse(empty.trace.contains("previous#1"));
    for (Object expiry : Arrays.asList(null, "wrong")) {
      var h = new H();
      h.q.values.put("answer_expires_at", expiry);
      assertThrows(RuntimeException.class, () -> service.answer(h));
      assertFalse(h.trace.contains("view#1"));
    }
    var seq = new H();
    seq.sequence = null;
    assertThrows(NullPointerException.class, () -> service.answer(seq));
    assertFalse(seq.trace.contains("encode-receipt#1"));
    var type = new H();
    type.q.values.put("revision", "wrong");
    assertThrows(ClassCastException.class, () -> service.answer(type));
    assertFalse(type.trace.contains("input#1"));
  }

  @Test
  void everyReachedRuntimeAndErrorInstanceStopsAtTheOriginalOperation() {
    for (String route : List.of("foreground", "late", "replay", "expired", "due")) {
      var baseline = route(route);
      try {
        service.answer(baseline);
      } catch (TaskFailure expected) {
      }
      for (String at : baseline.trace)
        for (Throwable fail : List.of(new IllegalStateException(at), new AssertionError(at))) {
          var h = route(route);
          h.failAt = at;
          h.failure = fail;
          assertSame(
              fail, assertThrows(Throwable.class, () -> service.answer(h)), route + " " + at);
          assertEquals(
              baseline.trace.subList(0, baseline.trace.indexOf(at) + 1), h.trace, route + " " + at);
        }
    }
  }

  @Test
  void onlyIdempotencyTryTranslatesTaskFailureWhileCanonicalAndStorageKeepInstance() {
    for (String at : List.of("input#1", "canonical#1", "digest#1")) {
      var h = route("replay");
      var original = new TaskFailure(422, "ORIGINAL");
      h.failAt = at;
      h.failure = original;
      var actual = assertThrows(TaskFailure.class, () -> service.answer(h));
      assertNotSame(original, actual);
      assertEquals("IDEMPOTENCY_CONFLICT", actual.code());
      assertNull(h.returned);
    }
    for (String at :
        List.of(
            "encode-answer#1",
            "digest#1",
            "id#1",
            "sequence#1",
            "hash#1",
            "encode-receipt#2",
            "update#1",
            "batch#1",
            "complete#1",
            "resume#1",
            "event#1")) {
      var h = new H();
      var original = new TaskFailure(422, "ORIGINAL");
      h.failAt = at;
      h.failure = original;
      assertSame(original, assertThrows(TaskFailure.class, () -> service.answer(h)));
      assertNull(h.returned);
    }
    var h = new H();
    var original = new TaskFailure(422, "ENCODE_STORAGE");
    h.failAt = "encode-receipt#1";
    h.failure = original;
    assertSame(original, assertThrows(TaskFailure.class, () -> service.answer(h)));
    assertSame(original, h.returned.rejection());
  }

  private static void assertBefore(H h, String first, String second) {
    assertTrue(
        h.trace.indexOf(first) >= 0 && h.trace.indexOf(first) < h.trace.indexOf(second),
        h.trace.toString());
  }
}
