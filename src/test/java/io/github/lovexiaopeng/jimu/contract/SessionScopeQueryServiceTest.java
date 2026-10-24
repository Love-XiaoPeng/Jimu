package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeQueryServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 拒绝提交／回滚、完整绑定证明、回执拒绝及一次结算的规则验证。 */
class SessionScopeQueryServiceTest {
  private final Records records = new Records();
  private final SessionScopeQueryServiceImpl service = new SessionScopeQueryServiceImpl(records);
  private final Lease lease =
      new Lease(
          "tenant",
          "owner",
          "caller",
          "attempt",
          1,
          "worker",
          Instant.now().plusSeconds(60),
          "goal",
          List.of(),
          1);

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }

  private static final class Records implements SessionScopeQueries {
    final List<String> log = new ArrayList<>();
    final Map<String, Object> values = new HashMap<>();
    String fail;
    Commit committed;

    Records() {
      values.put("scopeTask", true);
      values.put("toolsUsed", 0L);
      values.put("maxTools", 4L);
      values.put("originalSlot", new Invocation("inv", "PENDING", hash("{}"), null, null));
      values.put("validatedStep", new Step("binding", "context"));
      values.put("requireScope", "authority");
      values.put("cut", new Cut("authority", "context", "snapshot"));
      values.put(
          "lockBinding",
          new Binding(
              1,
              "caller",
              "step",
              "SEARCH",
              "binding",
              hash("{}"),
              "authority",
              "context",
              "{}",
              hash("{}"),
              2,
              "PRESENT",
              "PENDING",
              null,
              null,
              null,
              null,
              null,
              null,
              null));
      values.put("normalized", "{}");
      values.put("receipt", new Receipt("result:inv", "tool.completed", null));
      values.put("reservation", new Reservation("TOOL", "SETTLED", 1));
      values.put("budget", new Budget(0, 10));
      values.put("search", new Result("output", null, "scan", 3, 30));
      values.put("read", new Result("output", "[]", null, 0, 30));
      values.put("receiptSequence", 2L);
      values.put("nextNativeSequence", 3L);
      values.put("refCount", 0);
    }

    public <T> T required(long end, Supplier<T> work) {
      assertTrue(end > System.nanoTime() && end <= System.nanoTime() + 2_000_000_000L);
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

    public Task lock(Lease ignored) {
      log.add("lock");
      return (Task)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Task.class},
              (proxy, method, args) -> {
                String name = method.getName();
                log.add(name);
                if (name.equals(fail)) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
                if (name.equals("commitQuery")) committed = (Commit) args[1];
                if (method.getReturnType() == void.class) return null;
                if (!values.containsKey(name))
                  throw new AssertionError("Unexpected method " + name);
                return values.get(name);
              });
    }

    public String hash(String raw) {
      return raw.equals("snapshot") ? "context" : SessionScopeQueryServiceTest.hash(raw);
    }

    public int bytes(String raw) {
      return raw.getBytes(StandardCharsets.UTF_8).length;
    }

    public boolean sameJson(String left, String right) {
      return left.equals(right);
    }

    Binding binding() {
      return (Binding) values.get("lockBinding");
    }

    void change(String name, Object replacement) throws Exception {
      var parts = Binding.class.getRecordComponents();
      var types = new Class<?>[parts.length];
      var args = new Object[parts.length];
      for (int i = 0; i < parts.length; i++) {
        types[i] = parts[i].getType();
        args[i] =
            parts[i].getName().equals(name)
                ? replacement
                : parts[i].getAccessor().invoke(binding());
      }
      values.put("lockBinding", Binding.class.getDeclaredConstructor(types).newInstance(args));
    }
  }

  private String query() {
    return service.scopedOrResult(
        lease, "step", new Call("slot", "provider", "session_search", "{}"), "version", "digest");
  }

  private void rejected(String code) {
    assertEquals(code, assertThrows(TaskFailure.class, this::query).code());
  }

  private void replay() throws Exception {
    records.values.put(
        "originalSlot",
        new SessionScopeQueries.Invocation(
            "inv", "SUCCEEDED", hash("{}"), "output", hash("output")));
    records.change("outcome", "SUCCEEDED");
    records.change("outputDigest", hash("output"));
    records.change("outputBytes", 6L);
    records.change("receipt", 2L);
    records.change("attempt", "attempt");
    records.change("fence", 1L);
  }

  @Test
  void pendingSettlementBeforeFinalFenceAndAccounting() {
    assertEquals("output", query());
    assertTrue(records.log.indexOf("finishInvocation") < records.log.indexOf("commitQuery"));
    assertTrue(records.log.indexOf("commitQuery") < records.log.indexOf("commitFence"));
    assertTrue(records.log.indexOf("account") < records.log.indexOf("commit"));
    assertEquals("SUCCEEDED", records.committed.outcome());
    assertEquals(3, records.committed.rows());
  }

  @Test
  void onlyKnownMissingReadCommitsFailureBeforeReturningRejection() throws Exception {
    records.change("operation", "READ");
    records.fail = "read";
    assertEquals(
        "SESSION_EVENT_NOT_FOUND",
        assertThrows(
                TaskFailure.class,
                () ->
                    service.scopedOrResult(
                        lease,
                        "step",
                        new Call("slot", "provider", "session_event_read", "{}"),
                        "version",
                        "digest"))
            .code());
    assertEquals("FAILED", records.committed.outcome());
    assertEquals("[]", records.committed.refs());
    assertEquals(0, records.committed.outputBytes());
    assertTrue(records.log.contains("appendRead"));
    assertEquals("commit", records.log.getLast());
  }

  @Test
  void unrelatedFailureRollsBackWithoutSettlement() {
    records.fail = "requireScope";
    rejected("SESSION_EVENT_NOT_FOUND");
    assertEquals("rollback", records.log.getLast());
    assertFalse(records.log.contains("consumeTask"));
  }

  @Test
  void taskMismatchBeforeOriginalSlot() {
    records.values.put("scopeTask", false);
    rejected("INVOCATION_MISMATCH");
    assertFalse(records.log.contains("originalSlot"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "caller",
        "step",
        "operation",
        "binding",
        "input",
        "authority",
        "context",
        "normalized",
        "normalizedDigest",
        "content"
      })
  void bindingTamperingRollsBackBeforeBudget(String field) throws Exception {
    records.change(field, "tampered");
    rejected("SESSION_HISTORY_CORRUPT");
    assertFalse(records.log.contains("budget"));
    assertEquals("rollback", records.log.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "normalizedBytes"})
  void numericBindingTamperingRollsBack(String field) throws Exception {
    records.change(field, 99L);
    rejected("SESSION_HISTORY_CORRUPT");
    assertFalse(records.log.contains("consumeTask"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"task", "frozen", "tenant"})
  void exhaustedBudgetNeverConsumes(String kind) {
    if (kind.equals("task")) records.values.put("toolsUsed", 4L);
    if (kind.equals("frozen")) {
      records.values.put("toolsUsed", 20L);
      records.values.put("maxTools", 30L);
    }
    if (kind.equals("tenant")) records.values.put("budget", new SessionScopeQueries.Budget(10, 10));
    rejected("TOOL_BUDGET_EXCEEDED");
    assertFalse(records.log.contains("search"));
  }

  @Test
  void successfulReplayNeverConsumesAndRechecksCurrentScope() throws Exception {
    replay();
    assertEquals("output", query());
    assertFalse(records.log.contains("budget"));
    assertFalse(records.log.contains("consumeTask"));
    assertEquals(2, records.log.stream().filter("requireScope"::equals).count());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fact", "type", "code", "reservation", "actual", "output"})
  void replayProofTamperingCannotDiscloseOrConsume(String field) throws Exception {
    replay();
    switch (field) {
      case "fact" ->
          records.values.put(
              "receipt", new SessionScopeQueries.Receipt("other", "tool.completed", null));
      case "type" ->
          records.values.put(
              "receipt", new SessionScopeQueries.Receipt("result:inv", "tool.failed", null));
      case "code" ->
          records.values.put(
              "receipt", new SessionScopeQueries.Receipt("result:inv", "tool.completed", "other"));
      case "reservation" ->
          records.values.put(
              "reservation", new SessionScopeQueries.Reservation("MODEL", "SETTLED", 1));
      case "actual" ->
          records.values.put(
              "reservation", new SessionScopeQueries.Reservation("TOOL", "SETTLED", 2));
      case "output" -> records.change("outputDigest", "wrong");
      default -> throw new AssertionError(field);
    }
    rejected("SESSION_HISTORY_CORRUPT");
    assertFalse(records.log.contains("account"));
    assertFalse(records.log.contains("consumeTask"));
  }

  @Test
  void failedReplayCommitsProofChecksThenReissuesSameFailure() throws Exception {
    replay();
    records.values.put(
        "originalSlot",
        new SessionScopeQueries.Invocation("inv", "FAILED", hash("{}"), null, null));
    records.values.put(
        "receipt",
        new SessionScopeQueries.Receipt("result:inv", "tool.failed", "SESSION_EVENT_NOT_FOUND"));
    records.change("outcome", "FAILED");
    records.change("failure", "SESSION_EVENT_NOT_FOUND");
    records.change("outputBytes", 0L);
    rejected("SESSION_EVENT_NOT_FOUND");
    assertEquals("commit", records.log.getLast());
    assertFalse(records.log.contains("consumeTask"));
  }

  @Test
  void finalFenceFailureRollsBackAllStagedWrites() {
    records.fail = "commitFence";
    rejected("SESSION_EVENT_NOT_FOUND");
    assertTrue(records.log.contains("commitQuery"));
    assertEquals("rollback", records.log.getLast());
  }
}
