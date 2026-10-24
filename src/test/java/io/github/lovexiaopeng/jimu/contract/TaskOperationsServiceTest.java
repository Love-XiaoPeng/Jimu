package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskOperationsRecords;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskOperationsServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TaskOperationsServiceTest {
  private final List<String> calls = new ArrayList<>();
  private String state = "QUEUED";
  private boolean scope, history, quiescent = true, access = true;
  private long next = 2, oldest = 1;
  private int pageLimit;

  private TaskOperationsServiceImpl service() {
    var row =
        (TaskOperationsRecords.Row)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TaskOperationsRecords.Row.class},
                (p, m, a) -> {
                  calls.add(m.getName());
                  return switch (m.getName()) {
                    case "snapshot" ->
                        new TaskOperationsRecords.Snapshot(
                            "id",
                            state,
                            1,
                            Instant.EPOCH,
                            Instant.EPOCH,
                            null,
                            "CODE",
                            "private summary",
                            next,
                            scope,
                            history,
                            false);
                    case "artifacts" ->
                        List.of(
                            new Tasks.Artifact("artifact", "text/plain", 1, "hash", Instant.EPOCH));
                    case "requireAccess" -> {
                      if (!access) throw new TaskFailure(403, "CAPABILITY_REVOKED");
                      yield null;
                    }
                    case "historyRetained" -> false;
                    case "writersQuiescent" -> quiescent;
                    case "requestCancel" -> {
                      calls.add("changeState=" + a[0]);
                      yield null;
                    }
                    case "event" -> {
                      calls.add("fact=" + a[0]);
                      yield null;
                    }
                    case "reload" -> p;
                    case "oldestEvent" -> oldest;
                    case "events" -> {
                      pageLimit = (int) a[1];
                      yield List.of();
                    }
                    case "setTerminal",
                            "markUnknownTools",
                            "releaseQueue",
                            "releaseRun",
                            "releaseQuestionTask",
                            "holdUnknownModels",
                            "stopAttempt" ->
                        null;
                    default -> throw new AssertionError(m);
                  };
                });
    var records =
        (TaskOperationsRecords)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TaskOperationsRecords.class},
                (p, m, a) -> {
                  calls.add(m.getName());
                  return switch (m.getName()) {
                    case "scopeHint" -> scope;
                    case "lock", "visible" -> row;
                    case "lockBudget", "lockScope" -> null;
                    default -> throw new AssertionError(m);
                  };
                });
    return new TaskOperationsServiceImpl(
        records,
        new LedgerUnitOfWork() {
          public <T> T required(Supplier<T> operation) {
            calls.add("transaction");
            return operation.get();
          }
        });
  }

  private final Tasks.Identity owner = new Tasks.Identity("tenant", "owner");

  @Test
  void scopeGetRedactsWithoutChangingPublicState() {
    scope = true;
    access = false;
    var view = service().get(owner, "id");
    assertEquals("QUEUED", view.state());
    assertEquals("SESSION_SCOPE_UNAVAILABLE", view.code());
    assertEquals(null, view.summary());
    assertTrue(view.artifacts().isEmpty());
    assertFalse(calls.contains("artifacts"));
  }

  @Test
  void scopeEventsRefuseBeforeCursorLookup() {
    scope = true;
    access = false;
    assertEquals(
        "CAPABILITY_REVOKED",
        assertThrows(TaskFailure.class, () -> service().events(owner, "id", 0, 64)).code());
    assertFalse(calls.contains("oldestEvent"));
  }

  @Test
  void historyExpiryRedactsOnlySummary() {
    history = true;
    var view = service().get(owner, "id");
    assertEquals(null, view.summary());
    assertEquals(1, view.artifacts().size());
  }

  @Test
  void unknownActiveWriterGetsStopIntentOnly() {
    state = "NEEDS_RECONCILIATION";
    quiescent = false;
    service().cancel(owner, "id");
    assertTrue(calls.contains("changeState=false"));
    assertFalse(calls.contains("setTerminal"));
    assertFalse(calls.contains("releaseQueue"));
  }

  @Test
  void unknownQuiescentDoesNotWrite() {
    state = "NEEDS_RECONCILIATION";
    service().cancel(owner, "id");
    assertFalse(calls.contains("requestCancel"));
    assertFalse(calls.contains("event"));
  }

  @Test
  void runningCancelDoesNotPrematurelySettle() {
    state = "RUNNING";
    service().cancel(owner, "id");
    assertTrue(calls.contains("changeState=true"));
    assertFalse(calls.contains("setTerminal"));
    assertTrue(calls.indexOf("lockBudget") < calls.indexOf("visible"));
    assertTrue(calls.indexOf("visible") < calls.indexOf("lock"));
  }

  @Test
  void futureCursorPrecedesRetentionAndPage() {
    assertEquals(
        "FUTURE_CURSOR",
        assertThrows(TaskFailure.class, () -> service().events(owner, "id", 3, 64)).code());
    assertFalse(calls.contains("oldestEvent"));
  }

  @Test
  void expiredCursorNeverReturnsPage() {
    oldest = 3;
    assertEquals(
        "CURSOR_EXPIRED",
        assertThrows(TaskFailure.class, () -> service().events(owner, "id", 0, 64)).code());
    assertFalse(calls.contains("events"));
  }

  @Test
  void eventPageIsBoundedAfterAccess() {
    service().events(owner, "id", 0, 1000);
    assertEquals(64, pageLimit);
    assertTrue(calls.indexOf("requireAccess") < calls.indexOf("oldestEvent"));
  }
}
