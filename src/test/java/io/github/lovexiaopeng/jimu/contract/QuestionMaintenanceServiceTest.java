package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionMaintenance;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionMaintenanceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class QuestionMaintenanceServiceTest {
  final QuestionMaintenanceServiceImpl service = new QuestionMaintenanceServiceImpl();

  static class Row extends AbstractMap<String, Object> {
    final H h;
    final String name;
    final Map<String, Object> v = new HashMap<>();
    final Map<String, List<Object>> changes = new HashMap<>();
    final Map<String, Integer> reads = new HashMap<>();

    Row(H h, String name) {
      this.h = h;
      this.name = name;
    }

    public Object get(Object key) {
      h.hit(name + "." + key);
      int n = reads.merge(key.toString(), 1, Integer::sum);
      var a = changes.get(key);
      return a == null ? v.get(key) : a.get(Math.min(n - 1, a.size() - 1));
    }

    public Set<Entry<String, Object>> entrySet() {
      throw new AssertionError("copied map");
    }
  }

  static class H {
    final List<String> trace = new ArrayList<>();
    final Map<String, Integer> counts = new HashMap<>();
    final List<Object[]> continuations = new ArrayList<>();
    final Map<String, Object[]> args = new HashMap<>();
    final Row candidate = new Row(this, "candidate"),
        task = new Row(this, "task"),
        fresh = new Row(this, "fresh");
    final Map<String, Object> q1 = new HashMap<>(), q2 = new HashMap<>();
    List<Map<String, Object>> candidates, due;
    List<Boolean> flags = List.of(true, true);
    QuestionMaintenance p;
    String failAt;
    Throwable failure;
    boolean inTx;

    @SuppressWarnings("unchecked")
    H() {
      candidate.v.putAll(Map.of("tenant_id", "t", "id", "c"));
      task.v.putAll(
          Map.of("tenant_id", "t", "id", "task", "state", "WAITING_USER", "within_deadline", 1L));
      candidates = List.of(candidate);
      due = List.of(q1, q2);
      p =
          (QuestionMaintenance)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {QuestionMaintenance.class},
                  (proxy, m, a) -> {
                    String n = m.getName();
                    hit(n);
                    args.put(n, a);
                    return switch (n) {
                      case "enabled" -> flags.get(Math.min(counts.get(n) - 1, flags.size() - 1));
                      case "candidates" -> {
                        assertFalse(inTx);
                        yield candidates;
                      }
                      case "transaction" -> {
                        assertFalse(inTx);
                        inTx = true;
                        try {
                          Object result = ((Supplier<?>) a[0]).get();
                          assertNull(result);
                          hit("tx-return");
                          yield result;
                        } finally {
                          inTx = false;
                        }
                      }
                      case "locked" -> counts.get(n) % 2 == 1 ? task : fresh;
                      case "due" -> {
                        assertTrue(inTx);
                        yield due;
                      }
                      case "continueQuestion" -> {
                        continuations.add(a);
                        yield null;
                      }
                      default -> null;
                    };
                  });
    }

    void hit(String n) {
      String label = n + "#" + counts.merge(n, 1, Integer::sum);
      trace.add(label);
      if (label.equals(failAt)) {
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
      }
    }
  }

  @Test
  void completeOrderIndependentReadsAndSameReferences() {
    var h = new H();
    h.candidate.changes.put("tenant_id", List.of("bucket-t", "lock-t"));
    h.task.changes.put("tenant_id", List.of("due-t", "tail-t"));
    h.task.changes.put("id", List.of("due-id", "tail-id"));
    service.maintain(h.p);
    assertEquals(
        List.of(
            "enabled#1",
            "candidates#1",
            "transaction#1",
            "candidate.tenant_id#1",
            "bucket#1",
            "candidate.tenant_id#2",
            "candidate.id#1",
            "locked#1",
            "task.state#1",
            "task.cancel_requested_at#1",
            "task.within_deadline#1",
            "enabled#2",
            "access#1",
            "task.tenant_id#1",
            "task.id#1",
            "due#1",
            "continueQuestion#1",
            "continueQuestion#2",
            "task.tenant_id#2",
            "task.id#2",
            "locked#2",
            "settle#1",
            "tx-return#1"),
        h.trace);
    assertEquals(List.of("bucket-t"), Arrays.asList(h.args.get("bucket")));
    assertEquals(List.of("due-t", "due-id"), Arrays.asList(h.args.get("due")));
    assertEquals(List.of("tail-t", "tail-id"), Arrays.asList(h.args.get("locked")));
    assertSame(h.task, h.args.get("access")[0]);
    assertSame(h.fresh, h.args.get("settle")[0]);
    for (int i = 0; i < 2; i++) {
      assertSame(h.task, h.continuations.get(i)[0]);
      assertSame(i == 0 ? h.q1 : h.q2, h.continuations.get(i)[1]);
    }
  }

  @Test
  void emptyScanAndZeroDueAndSequentialCandidates() {
    var h = new H();
    h.candidates = List.of();
    service.maintain(h.p);
    assertEquals(List.of("enabled#1", "candidates#1"), h.trace);
    h = new H();
    h.due = List.of();
    h.candidates = List.of(h.candidate, h.candidate);
    service.maintain(h.p);
    assertEquals(2, h.counts.get("transaction"));
    assertEquals(2, h.counts.get("settle"));
    assertFalse(h.counts.containsKey("continueQuestion"));
    assertEquals(3, h.counts.get("enabled"));
  }

  @Test
  void nonCandidateStateReturnsBeforeAnyEligibilityAndNullKeepsListOfFailure() {
    var h = new H();
    h.task.v.put("state", "RUNNING");
    service.maintain(h.p);
    assertFalse(h.task.reads.containsKey("cancel_requested_at"));
    assertFalse(h.counts.containsKey("access"));
    assertFalse(h.counts.containsKey("settle"));
    h = new H();
    h.task.v.remove("state");
    var nil = h;
    assertThrows(NullPointerException.class, () -> service.maintain(nil.p));
  }

  @Test
  void rejectionPriorityAndFreshFlagAndTerminalMapping() {
    for (String reason :
        List.of("CANCELLED", "DEADLINE_EXCEEDED", "QUESTION_FEATURE_DISABLED", "ACCESS_CODE")) {
      var h = new H();
      if (reason.equals("CANCELLED")) h.task.v.put("cancel_requested_at", new Object());
      else if (reason.equals("DEADLINE_EXCEEDED")) h.task.v.put("within_deadline", 0);
      else if (reason.equals("QUESTION_FEATURE_DISABLED")) h.flags = List.of(true, false);
      else {
        h.failAt = "access#1";
        h.failure = new TaskFailure(403, reason);
      }
      service.maintain(h.p);
      assertEquals(List.of(h.task, reason), Arrays.asList(h.args.get("close")));
      assertEquals(
          List.of(h.task, reason.equals("CANCELLED") ? "CANCELLED" : "FAILED", reason),
          Arrays.asList(h.args.get("terminal")));
      assertFalse(h.counts.containsKey("due"));
      assertFalse(h.counts.containsKey("settle"));
      assertEquals(
          reason.equals("CANCELLED") || reason.equals("DEADLINE_EXCEEDED") ? 1 : 2,
          h.counts.get("enabled"));
      if (reason.equals("CANCELLED")) assertFalse(h.task.reads.containsKey("within_deadline"));
    }
  }

  @Test
  void onlyAccessTaskFailureIsCaughtAndNullCodeContinues() {
    var h = new H();
    h.failAt = "access#1";
    h.failure = new TaskFailure(499, null);
    service.maintain(h.p);
    assertTrue(h.counts.containsKey("due"));
    assertFalse(h.counts.containsKey("close"));
    h = new H();
    h.failAt = "access#1";
    h.failure = new TaskFailure(404, "CANCELLED");
    service.maintain(h.p);
    assertEquals("CANCELLED", h.args.get("terminal")[1]);
    for (Throwable failure :
        List.of(new IllegalArgumentException("same"), new AssertionError("same"))) {
      var a = new H();
      a.failAt = "access#1";
      a.failure = failure;
      assertSame(failure, assertThrows(failure.getClass(), () -> service.maintain(a.p)));
      assertFalse(a.counts.containsKey("close"));
    }
  }

  @Test
  void everyUncaughtOperationFailureStopsAtOriginalInstance() {
    for (String at :
        List.of(
            "enabled#1",
            "candidates#1",
            "transaction#1",
            "candidate.tenant_id#1",
            "bucket#1",
            "candidate.tenant_id#2",
            "candidate.id#1",
            "locked#1",
            "task.state#1",
            "task.cancel_requested_at#1",
            "task.within_deadline#1",
            "enabled#2",
            "task.tenant_id#1",
            "task.id#1",
            "due#1",
            "continueQuestion#1",
            "continueQuestion#2",
            "task.tenant_id#2",
            "task.id#2",
            "locked#2",
            "settle#1")) {
      var h = new H();
      h.failAt = at;
      var error = new TaskFailure(418, "same");
      h.failure = error;
      assertSame(error, assertThrows(TaskFailure.class, () -> service.maintain(h.p)), at);
      assertEquals(at, h.trace.getLast());
    }
  }

  @Test
  void closeAndTerminalFailuresDoNotReachLaterWork() {
    for (String at : List.of("close#1", "terminal#1")) {
      var h = new H();
      h.task.v.put("cancel_requested_at", new Object());
      h.failAt = at;
      var failure = new IllegalStateException("same");
      h.failure = failure;
      assertSame(failure, assertThrows(IllegalStateException.class, () -> service.maintain(h.p)));
      assertEquals(at, h.trace.getLast());
    }
  }

  @Test
  void candidateFailureDoesNotUndoEarlierReturnOrContinueBatch() {
    var h = new H();
    h.candidates = List.of(h.candidate, h.candidate, h.candidate);
    h.failAt = "bucket#2";
    h.failure = new AssertionError("second");
    assertSame(h.failure, assertThrows(AssertionError.class, () -> service.maintain(h.p)));
    assertEquals(1, h.counts.get("tx-return"));
    assertEquals(2, h.counts.get("transaction"));
    assertEquals(2, h.candidate.reads.get("tenant_id") - 1);
    assertEquals(1, h.candidate.reads.get("id"));
  }

  @Test
  void originalCastAndNumberFailuresRemainAtArrival() {
    for (Object value : Arrays.asList(null, "1")) {
      var h = new H();
      h.task.v.put("within_deadline", value);
      Class<? extends RuntimeException> expected =
          value == null ? NullPointerException.class : ClassCastException.class;
      assertThrows(expected, () -> service.maintain(h.p));
      assertEquals(1, h.counts.get("enabled"));
    }
    var h = new H();
    h.candidate.v.put("tenant_id", 2);
    assertThrows(ClassCastException.class, () -> service.maintain(h.p));
    assertFalse(h.counts.containsKey("bucket"));
  }
}
