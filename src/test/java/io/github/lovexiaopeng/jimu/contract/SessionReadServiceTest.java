package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReads;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionReadServiceImpl;
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

/** 原生读取的拒绝顺序、事务提交语义与trace结算NULL契约。 */
class SessionReadServiceTest {
  private final Records records = new Records();
  private final SessionReadServiceImpl service = new SessionReadServiceImpl(records);
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

  private static final class Records implements SessionReads {
    final Map<String, Object> values = new HashMap<>();
    final List<String> log = new ArrayList<>();
    String failAt, code;
    Commit commit;

    Records() {
      values.put("enabled", true);
      values.put("trace", false);
      values.put("originalSlot", new Invocation("inv", "PENDING", "hash:{}", null));
      values.put("input", new Input(0, "task", 1, 2));
      values.put("historyBinding", "binding");
      values.put("validatedStep", new Step("binding", 3));
      values.put("lockBinding", binding("PENDING", null, 1));
      values.put("toolsUsed", 0L);
      values.put("maxTools", 20L);
      values.put("budget", new Budget(0, 20));
      values.put("read", new Read("output", 0, 2, "target", "[]"));
      values.put("nextSequence", 4L);
    }

    static Binding binding(String outcome, String code, long relation) {
      return new Binding(
          1, "task", "step", "binding", 3, "hash:{}", 0, 1, 2, relation, outcome, code, 4L);
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
              (proxy, method, args) -> {
                String name = method.getName();
                log.add(name);
                if (name.equals(failAt)) throw new TaskFailure(409, code);
                if (name.equals("commitRead")) commit = (Commit) args[1];
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

  private String read() {
    return service.readOrResult(
        lease, "step", new Call("slot", "provider", "session_event_read", "{}"), "1", "digest");
  }

  private void rejects(String code) {
    assertEquals(code, assertThrows(TaskFailure.class, this::read).code());
  }

  @Test
  void successSettlesOnceWithNativeNullSources() {
    assertEquals("output", read());
    assertEquals(1, records.log.stream().filter("consumeTask"::equals).count());
    assertEquals(null, records.commit.sources());
    assertEquals(null, records.commit.sourcesBytes());
    assertTrue(records.log.indexOf("commitRead") < records.log.indexOf("appendRead"));
    assertEquals(
        List.of("commitFence", "account", "commit"),
        records.log.subList(records.log.size() - 3, records.log.size()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SESSION_EVENT_NOT_FOUND",
        "SESSION_HISTORY_CORRUPT",
        "SESSION_HISTORY_VERSION_UNSUPPORTED",
        "SESSION_HISTORY_LIMIT"
      })
  void knownRefusalCommitsReceiptAndChargeBeforeThrowing(String code) {
    records.failAt = "read";
    records.code = code;
    rejects(code);
    assertEquals("FAILED", records.commit.outcome());
    assertEquals(0, records.commit.outputBytes());
    assertEquals(null, records.commit.from());
    assertEquals(null, records.commit.targetDigest());
    assertEquals("commit", records.log.getLast());
  }

  @Test
  void unexpectedRefusalRollsBackBeforeConsumption() {
    records.failAt = "read";
    records.code = "SESSION_QUERY_TIMEOUT";
    rejects(records.code);
    assertFalse(records.log.contains("consumeTask"));
    assertEquals("rollback", records.log.getLast());
  }

  @Test
  void traceFailurePersistsEmptySourcesAndChargesThem() {
    records.values.put("trace", true);
    records.failAt = "read";
    records.code = "SESSION_EVENT_NOT_FOUND";
    rejects(records.code);
    assertEquals("[]", records.commit.sources());
    assertEquals(2, records.commit.sourcesBytes());
    assertTrue(records.log.indexOf("commitRead") < records.log.indexOf("body"));
  }

  @Test
  void replayProvesReceiptAndDoesNotConsume() {
    records.values.put(
        "originalSlot", new SessionReads.Invocation("inv", "SUCCEEDED", "hash:{}", "original"));
    records.values.put("lockBinding", Records.binding("SUCCEEDED", null, 1));
    assertEquals("original", read());
    assertTrue(records.log.contains("verifyReceipt"));
    assertFalse(records.log.contains("consumeTask"));
    assertTrue(records.log.indexOf("account") < records.log.indexOf("validateOutput"));
  }

  @Test
  void failedReplayCommitsBeforeRejection() {
    records.values.put(
        "originalSlot", new SessionReads.Invocation("inv", "FAILED", "hash:{}", null));
    records.values.put("lockBinding", Records.binding("FAILED", "SESSION_HISTORY_LIMIT", 1));
    rejects("SESSION_HISTORY_LIMIT");
    assertEquals("commit", records.log.getLast());
    assertFalse(records.log.contains("reserve"));
  }

  @Test
  void invalidTraceRelationRejectsBeforeBudget() {
    records.values.put("trace", true);
    records.values.put("lockBinding", Records.binding("PENDING", null, 0));
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(records.log.contains("budget"));
  }

  @Test
  void inputRejectionPrecedesToolMismatch() {
    records.failAt = "input";
    records.code = "TOOL_INPUT_INVALID";
    assertEquals(
        records.code,
        assertThrows(
                TaskFailure.class,
                () ->
                    service.readOrResult(
                        lease, "step", new Call("s", "p", "wrong", "bad"), "1", "d"))
            .code());
    assertFalse(records.log.contains("historyBinding"));
  }

  @Test
  void currentTaskDisabledRejectsBeforeSlot() {
    records.values.put("enabled", false);
    rejects("SESSION_READ_DISABLED");
    assertFalse(records.log.contains("originalSlot"));
  }

  @Test
  void budgetRejectsBeforeRead() {
    records.values.put("budget", new SessionReads.Budget(20, 20));
    rejects("TOOL_BUDGET_EXCEEDED");
    assertFalse(records.log.contains("read"));
  }

  @ParameterizedTest
  @ValueSource(longs = {2, 20})
  void taskLimitAndTwentyToolCeilingRejectBeforeRead(long used) {
    records.values.put("toolsUsed", used);
    records.values.put("maxTools", used == 2 ? 2L : 40L);
    rejects("TOOL_BUDGET_EXCEEDED");
    assertFalse(records.log.contains("read"));
    assertTrue(records.log.indexOf("budget") < records.log.indexOf("toolsUsed"));
  }

  @Test
  void lastFenceFailureRollsBackStagedReceipt() {
    records.failAt = "commitFence";
    records.code = "FENCE_REJECTED";
    rejects(records.code);
    assertTrue(records.log.contains("appendRead"));
    assertFalse(records.log.contains("account"));
    assertEquals("rollback", records.log.getLast());
  }
}
