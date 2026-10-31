package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionHistoryRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionHistoryRetentionServiceTest {
  private static final class Fixture implements SessionHistoryRetentions, LedgerUnitOfWork {
    List<String> calls = new ArrayList<>();
    boolean active, scoped, retained, invalidated, searchable, stable = true;
    String state = "SUCCEEDED", content = "PRESENT", guard, failure, binding = "binding";
    long version = 1, recordVersion = 1, metadata = 9;
    int histories, passes;
    long released;
    Long fixedMetadata;
    long settledHistory, settledReservation;
    List<RetainedTable> receipt;

    SessionHistoryRetentionServiceImpl service() {
      return new SessionHistoryRetentionServiceImpl(this, this, Payloads::hash);
    }

    public List<Candidate> candidates() {
      assertFalse(active);
      return List.of(new Candidate("tenant", "task"));
    }

    public <T> T required(Supplier<T> work) {
      assertFalse(active);
      active = true;
      try {
        return work.get();
      } finally {
        active = false;
      }
    }

    public void lockBudget(String tenant) {
      assertTrue(active);
      calls.add("budget");
    }

    public LockedHistory lockTask(Candidate candidate) {
      assertTrue(active);
      calls.add("task");
      return task();
    }

    public String receipt(List<RetainedTable> tables, long bytes, String digest) {
      receipt = tables;
      assertEquals(15, bytes);
      assertEquals(Payloads.hash("binding"), digest);
      calls.add("receipt");
      return "receipt";
    }

    LockedHistory task() {
      return (LockedHistory)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {LockedHistory.class},
              (p, m, a) -> {
                if (m.getName().equals("matchesSnapshot"))
                  return java.lang.reflect.InvocationHandler.invokeDefault(p, m, a);
                String name = m.getName();
                if (name.equals("lineageMetadataBytes")) return 0L;
                calls.add(name);
                if (name.equals(failure)) throw new IllegalStateException("private failure");
                return switch (name) {
                  case "history" -> {
                    histories++;
                    yield new History(
                        recordVersion,
                        version,
                        binding,
                        Payloads.hash("binding"),
                        content,
                        retained,
                        15);
                  }
                  case "expectedVersion" -> 1;
                  case "expectedBinding" -> "binding";
                  case "scoped" -> scoped;
                  case "searchable" -> searchable;
                  case "invalidated" -> invalidated;
                  case "state" -> state;
                  case "writersQuiescent" -> !"writers".equals(guard);
                  case "activeInvocations" -> "invocations".equals(guard);
                  case "unsafeReservations" -> "reservations".equals(guard);
                  case "dispatchedModels" -> "models".equals(guard);
                  case "contents" ->
                      List.of(
                          new ContentTable(
                              "task",
                              List.of(
                                  new ContentRow(
                                      "task",
                                      List.of(
                                          new Body("goal", "中文😀"), new Body("summary", null))))));
                  case "metadata" -> {
                    passes++;
                    yield List.of(
                        new MetadataRow(
                            Arrays.asList(
                                "x".repeat((int) metadata),
                                null,
                                stable ? "100" : "x".repeat(passes))));
                  }
                  case "purgeMetadata" ->
                      fixedMetadata == null ? null : new PurgeMetadata(fixedMetadata);
                  case "settleHistory" -> {
                    settledHistory = (long) a[0];
                    yield null;
                  }
                  case "settleReservation" -> {
                    settledReservation = (long) a[0];
                    yield null;
                  }
                  case "releaseBudget" -> {
                    released = (long) a[0];
                    yield null;
                  }
                  default -> null;
                };
              });
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1, 88, 89, 90, 988, 9988, 65521})
  void lockedSnapshotDecimalFixedPointMatchesIndependentWireWidths(long fixed) {
    var f = new Fixture();
    f.fixedMetadata = fixed;
    f.service().cleanup();
    long settled = f.settledHistory;
    assertEquals(
        fixed
            + 3L * Long.toString(settled).getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        settled);
    assertEquals(settled, f.settledReservation);
    assertEquals(8388608 - settled, f.released);
    assertEquals(0, f.passes);
    assertTrue(f.calls.indexOf("purgeMetadata") > f.calls.indexOf("event"));
  }

  @Test
  void lockedSnapshotOverCapDoesNotSettleOrRefund() {
    var f = new Fixture();
    f.fixedMetadata = 65522L;
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> f.service().cleanup()).code());
    assertFalse(f.calls.contains("settleHistory"));
    assertFalse(f.calls.contains("settleReservation"));
    assertFalse(f.calls.contains("releaseBudget"));
  }

  @Test
  void originalOrderNullableHashesAndExactClosingMetadata() {
    var f = new Fixture();
    f.service().cleanup();
    assertEquals(2, f.histories);
    assertEquals(2, f.passes);
    assertTrue(f.calls.indexOf("event") > f.calls.indexOf("markPurged"));
    assertTrue(f.calls.indexOf("settleHistory") > f.calls.indexOf("event"));
    assertTrue(f.calls.indexOf("settleReservation") > f.calls.indexOf("settleHistory"));
    assertEquals(8388608 - (32 + 8 + 9 + 8 + 1 + 8 + 3), f.released);
    var body = f.receipt.getFirst().rows().getFirst().bodies();
    assertEquals(1, body.size());
    assertEquals(Payloads.hash("中文😀"), body.getFirst().sha256());
    assertEquals(10, body.getFirst().bytes());
    assertFalse(f.calls.contains("purgeScope"));
    assertFalse(f.calls.contains("purgeSearch"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"writers", "invocations", "reservations", "models"})
  void blockersDoNotReadBodiesOrWrite(String guard) {
    var f = new Fixture();
    f.guard = guard;
    f.service().cleanup();
    assertFalse(f.calls.contains("contents"));
    assertEquals(1, f.histories);
  }

  @ParameterizedTest
  @ValueSource(strings = {"RUNNING", "WAITING_USER", "NEEDS_RECONCILIATION"})
  void originalTerminalSet(String state) {
    var f = new Fixture();
    f.state = state;
    f.service().cleanup();
    assertFalse(f.calls.contains("writersQuiescent"));
  }

  @Test
  void retainedScopeOnlyPurgesWhenAlreadyInvalidated() {
    var f = new Fixture();
    f.retained = true;
    f.service().cleanup();
    assertFalse(f.calls.contains("contents"));
    assertFalse(f.calls.contains("invalidated"));
    f = new Fixture();
    f.retained = true;
    f.scoped = true;
    f.service().cleanup();
    assertFalse(f.calls.contains("contents"));
    f.invalidated = true;
    f.searchable = true;
    f.calls.clear();
    f.service().cleanup();
    assertTrue(f.calls.indexOf("purgeScopeQuery") < f.calls.indexOf("receipt"));
    assertTrue(f.calls.contains("purgeSearch"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"record", "version", "binding"})
  void badBindingFailsBeforeStateGuard(String bad) {
    var f = new Fixture();
    f.content = "PURGED";
    if (bad.equals("record")) f.recordVersion = 2;
    else if (bad.equals("version")) f.version = 2;
    else f.binding = "corrupt";
    assertEquals(
        "SESSION_HISTORY_VERSION_UNSUPPORTED",
        assertThrows(TaskFailure.class, () -> f.service().cleanup()).code());
    assertFalse(f.calls.contains("state"));
  }

  @Test
  void alreadyPurgedShortCircuitsRetainedScope() {
    var f = new Fixture();
    f.content = "PURGED";
    f.service().cleanup();
    assertFalse(f.calls.contains("scoped"));
    assertFalse(f.calls.contains("contents"));
  }

  @Test
  void exactMetadataLimitIsCheckedAfterEventBeforeSettlement() {
    var f = new Fixture();
    f.metadata = 65536;
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> f.service().cleanup()).code());
    assertTrue(f.calls.contains("event"));
    assertFalse(f.calls.contains("settleHistory"));
  }

  @Test
  void fivePassFailureDoesNotRefund() {
    var f = new Fixture();
    f.stable = false;
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> f.service().cleanup()).code());
    assertEquals(10, f.passes);
    assertFalse(f.calls.contains("releaseBudget"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"contents", "purgeRead", "event", "settleReservation", "releaseBudget"})
  void infrastructureFailuresPropagate(String point) {
    var f = new Fixture();
    f.failure = point;
    assertThrows(IllegalStateException.class, () -> f.service().cleanup());
    assertFalse(f.active);
  }
}
