package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionOpening;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionOpeningServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
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

class QuestionOpeningServiceTest {
  final QuestionOpeningServiceImpl service = new QuestionOpeningServiceImpl();
  static final Instant CREATED = Instant.parse("2026-10-26T23:59:59.123456Z");

  static class Row extends AbstractMap<String, Object> {
    final H h;
    final String name;
    final Map<String, Object> v = new HashMap<>();
    final Map<String, List<Object>> changes = new HashMap<>();
    final Map<String, Integer> reads = new HashMap<>();

    Row(H h, String n) {
      this.h = h;
      name = n;
    }

    public Object get(Object key) {
      h.hit(name + "." + key);
      int n = reads.merge(key.toString(), 1, Integer::sum);
      var list = changes.get(key);
      return list == null ? v.get(key) : list.get(Math.min(n - 1, list.size() - 1));
    }

    public Set<Entry<String, Object>> entrySet() {
      throw new AssertionError("map copied or decoded");
    }
  }

  static class H {
    final List<String> trace = new ArrayList<>();
    final Map<String, Integer> counts = new HashMap<>();
    final Row task = new Row(this, "task"),
        budget = new Row(this, "budget"),
        inv = new Row(this, "inv");
    final Object input = new Object(),
        pin = new Object(),
        batch = new Object(),
        stamp = new Object(),
        deadline = new Object();
    final Map<String, Object[]> args = new HashMap<>();
    final Map<String, Object> values = new HashMap<>();
    final Map<String, List<Object>> changingCalls = new HashMap<>();
    final QuestionOpening<Object, Object, Object, Object, Object> p;
    List<Map<String, Object>> rows;
    String failAt;
    Throwable failure;
    Object returned;

    @SuppressWarnings("unchecked")
    H() {
      task.v.putAll(
          Map.of(
              "capabilities",
              "x,ask_user_question,ask_user_question",
              "tools_used",
              0,
              "max_tools",
              4L,
              "question_binding_digest",
              "binding",
              "created_at",
              LocalDateTime.ofInstant(CREATED, java.time.ZoneOffset.UTC),
              "deadline",
              deadline));
      budget.v.putAll(Map.of("tool_used", 1, "tool_limit", 10));
      inv.v.putAll(
          Map.of(
              "id",
              "invocation",
              "tool",
              "ask_user_question",
              "provider_id",
              "provider",
              "input_digest",
              "hash:raw",
              "plugin_version",
              "0.2.0",
              "plugin_digest",
              "digest",
              "installation_epoch",
              3,
              "state",
              "PENDING",
              "output_snapshot",
              "out",
              "output_digest",
              "hash:out"));
      rows = List.of(inv);
      values.putAll(
          Map.of(
              "enabled",
              true,
              "version",
              "0.2.0",
              "digest",
              "digest",
              "ordinal",
              8,
              "timeout",
              7,
              "insert",
              0,
              "waiting",
              -1,
              "budget",
              3,
              "waitingInvocation",
              1));
      p =
          (QuestionOpening<Object, Object, Object, Object, Object>)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {QuestionOpening.class},
                  (proxy, m, a) -> {
                    String n = m.getName();
                    hit(n);
                    args.put(n, a);
                    if (n.equals("transaction")) {
                      returned = ((Supplier<?>) a[0]).get();
                      hit("tx-return");
                      return returned;
                    }
                    if (changingCalls.containsKey(n)) {
                      var choices = changingCalls.get(n);
                      return choices.get(Math.min(counts.get(n) - 1, choices.size() - 1));
                    }
                    if (values.containsKey(n)) return values.get(n);
                    return switch (n) {
                      case "fenced" -> task;
                      case "bucket" -> budget;
                      case "tenant" -> "tenant" + counts.get(n);
                      case "taskId" -> "task";
                      case "actor" -> "actor";
                      case "step" -> "step";
                      case "slot" -> "slot";
                      case "tool" -> "ask_user_question";
                      case "providerId" -> "provider";
                      case "rawInput" -> "raw";
                      case "input" -> input;
                      case "invocation" -> rows;
                      case "pin" -> pin;
                      case "pinVersion" -> "0.2.0";
                      case "pinDigest" -> "digest";
                      case "pinEpoch" -> 3L;
                      case "hash" -> "hash:" + a[0];
                      case "readOutput" -> batch;
                      case "id" -> "request";
                      case "mode" -> "timed";
                      case "timestamp" -> stamp;
                      case "result" -> Arrays.asList(a);
                      default -> null;
                    };
                  });
    }

    void hit(String at) {
      String label = at + "#" + counts.merge(at, 1, Integer::sum);
      trace.add(label);
      if (label.equals(failAt)) {
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
      }
    }
  }

  void code(H h, String code) {
    assertEquals(code, assertThrows(TaskFailure.class, () -> service.openOrResult(h.p)).code());
  }

  @Test
  void exactNewQuestionOrderBindingsIgnoredCountsAndReferences() {
    var h = new H();
    assertEquals(Arrays.asList(true, null), service.openOrResult(h.p));
    assertEquals(
        List.of(
            "transaction#1",
            "fenced#1",
            "tenant#1",
            "bucket#1",
            "task.capabilities#1",
            "enabled#1",
            "version#1",
            "rawInput#1",
            "input#1",
            "tenant#2",
            "taskId#1",
            "step#1",
            "slot#1",
            "invocation#1"),
        h.trace.subList(0, 14));
    assertEquals(
        List.of("tenant2", "task", "step", "slot"), Arrays.asList(h.args.get("invocation")));
    assertEquals(2, h.inv.reads.get("state"));
    assertEquals(2, h.counts.get("timeout"));
    assertEquals(2, h.task.reads.get("created_at"));
    assertSame(h.task, h.args.get("pin")[0]);
    assertSame(h.input, h.args.get("timeout")[0]);
    assertEquals(List.of("tenant3", "task"), Arrays.asList(h.args.get("ordinal")));
    assertEquals(
        Arrays.asList(
            "tenant4",
            "request",
            "task",
            "actor",
            "invocation",
            8,
            "binding",
            "timed",
            7,
            "raw",
            "hash:raw",
            7,
            7,
            7,
            h.deadline,
            h.stamp),
        Arrays.asList(h.args.get("insert")));
    assertEquals(CREATED.plusSeconds(604800), h.args.get("timestamp")[0]);
    assertEquals(List.of("request", "tenant5", "task"), Arrays.asList(h.args.get("waiting")));
    assertEquals(
        List.of("tenant7", "task", "invocation", "invocation", "TOOL", 1L, "RESERVED", "KNOWN"),
        Arrays.asList(h.args.get("reserve")));
    assertEquals(List.of("tenant8", "invocation"), Arrays.asList(h.args.get("waitingInvocation")));
    assertSame(h.task, h.args.get("pause")[0]);
    assertNull(h.args.get("pause")[2]);
    assertEquals(2, h.counts.get("event"));
    assertEquals("task.waiting_user", h.args.get("event")[2]);
    assertEquals(Arrays.asList(true, null), h.returned);
  }

  @Test
  void historicalResultHasThreeIndependentOutputReadsAndNoPendingWork() {
    var h = new H();
    h.inv.v.put("state", "SUCCEEDED");
    h.inv.changes.put("output_snapshot", List.of("out", "codec-out", "read-out"));
    assertEquals(Arrays.asList(false, h.batch), service.openOrResult(h.p));
    assertEquals(3, h.inv.reads.get("output_snapshot"));
    assertEquals(1, h.inv.reads.get("state"));
    assertEquals(List.of("0.2.0", "codec-out"), Arrays.asList(h.args.get("output")));
    assertEquals("read-out", h.args.get("readOutput")[0]);
    for (String n : List.of("ordinal", "storage", "timeout", "insert", "reserve", "pause", "event"))
      assertFalse(h.counts.containsKey(n), n);
    h = new H();
    h.inv.v.put("state", "SUCCEEDED");
    h.inv.v.put("output_digest", "bad");
    code(h, "SNAPSHOT_CORRUPT");
    assertFalse(h.counts.containsKey("output"));
  }

  @Test
  void listFirstAndExactMismatchPinShortCircuit() {
    var h = new H();
    h.rows = List.of();
    code(h, "NOT_FOUND");
    assertFalse(h.counts.containsKey("tool"));
    h = new H();
    h.rows = Arrays.asList(h.inv, null);
    service.openOrResult(h.p);
    String[] keys = {"tool", "provider_id", "input_digest", "plugin_version", "plugin_digest"};
    for (String key : keys) {
      h = new H();
      h.inv.v.put(key, "wrong");
      code(h, "INVOCATION_MISMATCH");
      assertFalse(h.counts.containsKey("pin"));
    }
    for (String method : List.of("pinVersion", "pinDigest", "pinEpoch")) {
      h = new H();
      h.values.put(method, method.equals("pinEpoch") ? 4L : "wrong");
      code(h, "PLUGIN_PIN_MISMATCH");
      assertFalse(h.inv.reads.containsKey("id"));
    }
    h = new H();
    h.values.put("tool", "wrong");
    code(h, "INVOCATION_MISMATCH");
    assertFalse(h.inv.reads.containsKey("tool"));
    h = new H();
    h.inv.changes.put("state", List.of("OTHER", "PENDING"));
    service.openOrResult(h.p);
  }

  @Test
  void capabilityOrdinalWaitingAndBudgetBlockInOriginalOrder() {
    var h = new H();
    h.task.v.put("capabilities", "web_fetch");
    code(h, "QUESTION_NOT_AUTHORIZED");
    assertFalse(h.counts.containsKey("enabled"));
    h = new H();
    h.values.put("enabled", false);
    code(h, "QUESTION_NOT_AUTHORIZED");
    assertFalse(h.counts.containsKey("input"));
    h = new H();
    h.values.put("ordinal", 9);
    code(h, "QUESTION_BATCH_LIMIT");
    assertFalse(h.task.reads.containsKey("waiting_question_id"));
    h = new H();
    h.task.v.put("waiting_question_id", "other");
    code(h, "QUESTION_ALREADY_WAITING");
    assertFalse(h.task.reads.containsKey("tools_used"));
    h = new H();
    h.task.v.put("tools_used", 4);
    code(h, "TOOL_BUDGET_EXCEEDED");
    assertFalse(h.budget.reads.containsKey("tool_used"));
    h = new H();
    h.budget.v.put("tool_used", 10);
    code(h, "TOOL_BUDGET_EXCEEDED");
    assertFalse(h.counts.containsKey("storage"));
    h = new H();
    h.inv.v.put("state", "FAILED");
    code(h, "INVOCATION_STATE");
    assertFalse(h.counts.containsKey("ordinal"));
  }

  @Test
  void nullableScalarNullMembersAndOriginalCastsFailAtArrival() {
    for (String n : List.of("ordinal", "tool", "providerId", "version", "pinVersion")) {
      var h = new H();
      h.values.put(n, null);
      assertThrows(NullPointerException.class, () -> service.openOrResult(h.p));
      assertFalse(h.counts.containsKey("insert"));
    }
    for (String n : List.of("capabilities", "created_at")) {
      var h = new H();
      h.task.v.put(n, null);
      assertThrows(NullPointerException.class, () -> service.openOrResult(h.p));
      assertFalse(h.counts.containsKey("insert"));
    }
    var h = new H();
    h.task.v.put("tools_used", "0");
    assertThrows(ClassCastException.class, () -> service.openOrResult(h.p));
  }

  @Test
  void defaultBlockingAndMutableTimeoutPreserveAllFourBindings() {
    var h = new H();
    h.values.put("timeout", null);
    service.openOrResult(h.p);
    assertEquals(1, h.counts.get("timeout"));
    assertEquals(120, h.args.get("insert")[8]);
    h = new H();
    h.values.put("version", "0.1.0");
    h.values.put("pinVersion", "0.1.0");
    h.inv.v.put("plugin_version", "0.1.0");
    service.openOrResult(h.p);
    assertFalse(h.counts.containsKey("timeout"));
    for (int i : List.of(8, 11, 12, 13)) assertNull(h.args.get("insert")[i]);
    for (int count : List.of(0, -1, 2)) {
      h = new H();
      h.values.put("waitingInvocation", count);
      code(h, "INVOCATION_STATE");
      assertTrue(h.counts.containsKey("reserve"));
      assertFalse(h.counts.containsKey("pause"));
    }
    for (int count : List.of(0, -1, 2)) {
      h = new H();
      h.values.put("insert", count);
      h.values.put("waiting", count);
      h.values.put("budget", count);
      service.openOrResult(h.p);
      assertEquals(2, h.counts.get("event"));
    }
  }

  @Test
  void timeoutSecondReadIsNotCachedAndOriginalVersionSkipsIt() {
    var h = new H();
    h.changingCalls.put("timeout", List.of(7, 9));
    service.openOrResult(h.p);
    assertEquals(2, h.counts.get("timeout"));
    for (int index : List.of(8, 11, 12, 13)) assertEquals(9, h.args.get("insert")[index]);
  }

  @Test
  void everyReachedOperationPropagatesSameRuntimeAndErrorAndStopsLaterWork() {
    for (boolean historical : List.of(false, true)) {
      var baseline = new H();
      if (historical) baseline.inv.v.put("state", "SUCCEEDED");
      service.openOrResult(baseline.p);
      for (String point : baseline.trace) {
        for (Throwable original :
            List.of(
                new IllegalStateException(point),
                new AssertionError(point),
                new TaskFailure(409, point))) {
          var h = new H();
          if (historical) h.inv.v.put("state", "SUCCEEDED");
          h.failAt = point;
          h.failure = original;
          assertSame(
              original, assertThrows(original.getClass(), () -> service.openOrResult(h.p)), point);
          assertEquals(
              baseline.trace.subList(0, baseline.trace.indexOf(point) + 1), h.trace, point);
        }
      }
    }
  }
}
