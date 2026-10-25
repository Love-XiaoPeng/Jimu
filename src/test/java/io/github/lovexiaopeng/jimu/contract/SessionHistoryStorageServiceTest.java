package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryStorage;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionHistoryStorageServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionHistoryStorageServiceTest {
  private final List<String> calls = new ArrayList<>();
  private final SessionHistoryStorage.Binding binding =
      new SessionHistoryStorage.Binding("tenant", "task", "raw");
  private boolean session = true;
  private int changed = 1;
  private int bytes = 17;
  private long delta = 17;
  private String fail;

  private SessionHistoryStorage storage() {
    return (SessionHistoryStorage)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SessionHistoryStorage.class},
            (p, method, args) -> {
              String name = method.getName();
              calls.add(name);
              if (name.equals(fail)) throw new IllegalStateException(name);
              return switch (name) {
                case "binding" -> binding;
                case "insert" -> {
                  assertSame(binding, args[0]);
                  yield null;
                }
                case "initialBytes" -> bytes;
                case "session" -> session;
                case "add" -> {
                  assertEquals(delta, args[0]);
                  yield changed;
                }
                default -> null;
              };
            });
  }

  @Test
  void createPreservesValidationInsertAndAccountingOrder() {
    new SessionHistoryStorageServiceImpl().create(storage());
    assertEquals(
        List.of("binding", "validateGoal", "insert", "initialBytes", "session", "add"), calls);
  }

  @Test
  void nonSessionDoesNotResolveKeysOrTouchPersistence() {
    session = false;
    new SessionHistoryStorageServiceImpl().body(storage(), Long.MIN_VALUE);
    assertEquals(List.of("session"), calls);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 4294967297L})
  void bodyPreservesLongDelta(long additional) {
    delta = additional;
    new SessionHistoryStorageServiceImpl().body(storage(), additional);
    assertEquals(List.of("session", "add"), calls);
  }

  @Test
  void initialIntSumWidensWithItsOriginalSign() {
    bytes = Integer.MIN_VALUE;
    delta = Integer.MIN_VALUE;
    new SessionHistoryStorageServiceImpl().create(storage());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 2})
  void conditionalUpdateMustAffectExactlyOneRow(int count) {
    changed = count;
    var failure =
        assertThrows(
            TaskFailure.class, () -> new SessionHistoryStorageServiceImpl().body(storage(), 17));
    assertEquals(429, failure.status());
    assertEquals("SESSION_HISTORY_LIMIT", failure.code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"binding", "validateGoal", "insert", "initialBytes", "session", "add"})
  void failuresStopBeforeFollowingStages(String point) {
    fail = point;
    assertThrows(
        IllegalStateException.class,
        () -> new SessionHistoryStorageServiceImpl().create(storage()));
    assertEquals(point, calls.getLast());
  }
}
