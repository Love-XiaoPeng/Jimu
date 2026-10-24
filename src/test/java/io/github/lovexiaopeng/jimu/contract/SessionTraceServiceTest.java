package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionTraces;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionTraceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 溯源特有拒绝顺序、可靠event回执证明及NULL失败结算。 */
class SessionTraceServiceTest {
  private final Records records = new Records();
  private final SessionTraceServiceImpl service = new SessionTraceServiceImpl(records);
  private final Lease lease =
      new Lease(
          "tenant",
          "owner",
          "task",
          "attempt",
          1,
          "worker",
          Instant.now().plusSeconds(60),
          "goal",
          List.of(),
          1);

  private static final class Records implements SessionTraces {
    final Map<String, Object> values = new HashMap<>();
    final List<String> log = new ArrayList<>();
    String failAt, code;
    Commit commit;
    String outcome = "PENDING",
        failure,
        content = "PRESENT",
        policy = "read-receipt-sources-v1",
        task = "task";
    long upper = 3, target = 0;

    Records() {
      values.put("enabled", true);
      values.put("originalSlot", new Invocation("inv", "PENDING", null, null));
      values.put("input", new Input(0, null));
      values.put("historyBinding", "binding");
      values.put("validatedStep", new Step("binding", 3));
      values.put("receipt", new Receipt("result:inv", "tool.completed", null));
      values.put("reservation", new Reservation("TOOL", "SETTLED", 1));
      values.put("toolsUsed", 0L);
      values.put("maxTools", 20L);
      values.put("budget", new Budget(0, 20));
      values.put("trace", new Trace("output", "scan", "relations", "target", 1, 19, 1));
      values.put("receiptSequence", 4L);
    }

    public <T> T required(Supplier<T> work) {
      log.add("begin");
      try {
        T result = work.get();
        log.add("commit");
        return result;
      } catch (RuntimeException failure) {
        log.add("rollback");
        throw failure;
      }
    }

    public Task lock(Lease lease) {
      log.add("lock");
      return (Task)
          Proxy.newProxyInstance(
              Task.class.getClassLoader(),
              new Class<?>[] {Task.class},
              (p, m, a) -> {
                String name = m.getName();
                log.add(name);
                if (name.equals(failAt)) throw new TaskFailure(409, code);
                if (name.equals("commitTrace")) commit = (Commit) a[1];
                if (name.equals("lockBinding"))
                  return new Binding(
                      1,
                      task,
                      "step",
                      "binding",
                      upper,
                      "hash:{}",
                      target,
                      policy,
                      content,
                      outcome,
                      failure,
                      "hash:output",
                      outcome.equals("PENDING")
                          ? null
                          : Long.valueOf(outcome.equals("FAILED") ? 0 : 6),
                      4L,
                      "attempt",
                      1L);
                return values.get(name);
              });
    }

    public String hash(String raw) {
      return "hash:" + raw;
    }

    public int bytes(String raw) {
      return raw.length();
    }
  }

  private String trace() {
    return service.traceOrResult(
        lease, "step", new Call("slot", "provider", "session_event_trace", "{}"), "1", "digest");
  }

  private void rejects(String code) {
    assertEquals(code, assertThrows(TaskFailure.class, this::trace).code());
  }

  private void replay(boolean success) {
    records.outcome = success ? "SUCCEEDED" : "FAILED";
    records.failure = success ? null : "SESSION_HISTORY_LIMIT";
    records.values.put(
        "originalSlot",
        new SessionTraces.Invocation(
            "inv", records.outcome, success ? "output" : null, success ? "hash:output" : null));
    records.values.put(
        "receipt",
        new SessionTraces.Receipt(
            "result:inv", success ? "tool.completed" : "tool.failed", records.failure));
  }

  @Test
  void successPublishesReliableEventBeforeBindingAndChecksDeadline() {
    assertEquals("output", trace());
    assertEquals(1, records.log.stream().filter("consumeTask"::equals).count());
    assertEquals("scan", records.commit.scanDigest());
    assertEquals(1, records.commit.rows());
    assertEquals("relations", records.commit.relationsDigest());
    assertEquals("target", records.commit.targetDigest());
    assertEquals(1, records.commit.edges());
    assertTrue(records.log.indexOf("event") < records.log.indexOf("receiptSequence"));
    assertTrue(records.log.indexOf("receiptSequence") < records.log.indexOf("commitTrace"));
    assertEquals(
        List.of("commitFence", "deadline", "account", "commit"),
        records.log.subList(records.log.size() - 4, records.log.size()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SESSION_EVENT_NOT_FOUND",
        "SESSION_HISTORY_CORRUPT",
        "SESSION_HISTORY_VERSION_UNSUPPORTED",
        "SESSION_HISTORY_LIMIT"
      })
  void knownFailureCommitsChargeAndNullScanBeforeThrowing(String code) {
    records.failAt = "trace";
    records.code = code;
    rejects(code);
    assertEquals("FAILED", records.commit.outcome());
    assertNull(records.commit.scanDigest());
    assertNull(records.commit.relationsDigest());
    assertNull(records.commit.targetDigest());
    assertNull(records.commit.edges());
    assertNull(records.commit.rows());
    assertNull(records.commit.eventBytes());
    assertEquals(0, records.commit.outputBytes());
    assertEquals("commit", records.log.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SESSION_QUERY_TIMEOUT", "UNEXPECTED"})
  void unexpectedFailureRollsBackBeforeCharge(String code) {
    records.failAt = "trace";
    records.code = code;
    rejects(code);
    assertFalse(records.log.contains("consumeTask"));
    assertEquals("rollback", records.log.getLast());
  }

  @Test
  void toolMismatchPrecedesInputParsing() {
    records.failAt = "input";
    records.code = "TOOL_INPUT_INVALID";
    assertEquals(
        "INVOCATION_MISMATCH",
        assertThrows(
                TaskFailure.class,
                () ->
                    service.traceOrResult(
                        lease, "step", new Call("s", "p", "wrong", "bad"), "1", "d"))
            .code());
    assertFalse(records.log.contains("input"));
  }

  @Test
  void disabledPrecedesOriginalSlot() {
    records.values.put("enabled", false);
    rejects("SESSION_TRACE_DISABLED");
    assertFalse(records.log.contains("originalSlot"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"content", "target", "policy", "tenantTask", "upper"})
  void bindingProofRejectsBeforeBudget(String field) {
    switch (field) {
      case "content" -> records.content = "PURGED";
      case "target" -> records.target = 99;
      case "policy" -> records.policy = "other";
      case "tenantTask" -> records.task = "other";
      case "upper" -> records.upper = 4;
      default -> throw new AssertionError();
    }
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(records.log.contains("budget"));
  }

  @ParameterizedTest
  @ValueSource(longs = {2, 20})
  void taskAndHardBudgetRejectBeforeSearch(long used) {
    records.values.put("toolsUsed", used);
    records.values.put("maxTools", used == 2 ? 2L : 40L);
    rejects("TOOL_BUDGET_EXCEEDED");
    assertFalse(records.log.contains("trace"));
  }

  @Test
  void tenantBudgetRejectsBeforeSearch() {
    records.values.put("budget", new SessionTraces.Budget(20, 20));
    rejects("TOOL_BUDGET_EXCEEDED");
    assertFalse(records.log.contains("trace"));
  }

  @Test
  void replayProvesReceiptReservationAttemptOutputInOrderWithoutCharge() {
    replay(true);
    assertEquals("output", trace());
    assertFalse(records.log.contains("budget"));
    assertFalse(records.log.contains("consumeTask"));
    assertTrue(records.log.indexOf("receipt") < records.log.indexOf("reservation"));
    assertTrue(records.log.indexOf("reservation") < records.log.indexOf("requireAttempt"));
    assertTrue(records.log.indexOf("validateOutput") < records.log.indexOf("commitFence"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SESSION_EVENT_NOT_FOUND",
        "SESSION_HISTORY_CORRUPT",
        "SESSION_HISTORY_VERSION_UNSUPPORTED",
        "SESSION_HISTORY_LIMIT"
      })
  void failedReplayProvesThenCommitsBeforeRejection(String code) {
    replay(false);
    records.failure = code;
    records.values.put("receipt", new SessionTraces.Receipt("result:inv", "tool.failed", code));
    var rejection = assertThrows(TaskFailure.class, this::trace);
    assertEquals(code, rejection.code());
    assertEquals(
        code.equals("SESSION_EVENT_NOT_FOUND")
            ? 404
            : code.equals("SESSION_HISTORY_LIMIT") ? 429 : 409,
        rejection.status());
    assertTrue(records.log.contains("requireAttempt"));
    assertFalse(records.log.contains("consumeTask"));
    assertEquals("commit", records.log.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fact", "type", "code", "reservation", "attempt", "output"})
  void replayProofFailureNeverCharges(String proof) {
    replay(true);
    switch (proof) {
      case "fact" ->
          records.values.put("receipt", new SessionTraces.Receipt("other", "tool.completed", null));
      case "type" ->
          records.values.put(
              "receipt", new SessionTraces.Receipt("result:inv", "tool.failed", null));
      case "code" ->
          records.values.put(
              "receipt", new SessionTraces.Receipt("result:inv", "tool.completed", "bad"));
      case "reservation" ->
          records.values.put("reservation", new SessionTraces.Reservation("TOOL", "HELD", 1));
      case "attempt" -> {
        records.failAt = "requireAttempt";
        records.code = "NOT_FOUND";
      }
      case "output" ->
          records.values.put(
              "originalSlot",
              new SessionTraces.Invocation("inv", "SUCCEEDED", "tampered", "hash:output"));
      default -> throw new AssertionError();
    }
    rejects(proof.equals("attempt") ? "NOT_FOUND" : "SESSION_HISTORY_CORRUPT");
    assertFalse(records.log.contains("consumeTask"));
    assertEquals("rollback", records.log.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"commitFence", "deadline"})
  void finalFailureRollsBackStagedSettlement(String stage) {
    records.failAt = stage;
    records.code = stage.equals("deadline") ? "SESSION_QUERY_TIMEOUT" : "FENCE_REJECTED";
    rejects(records.code);
    assertNotNull(records.commit);
    assertFalse(records.log.contains("account"));
    assertEquals("rollback", records.log.getLast());
  }
}
