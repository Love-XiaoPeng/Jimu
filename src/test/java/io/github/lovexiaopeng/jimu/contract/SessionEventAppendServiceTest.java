package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventAppend;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionEventAppendServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionEventAppendServiceTest {
  private final List<String> calls = new ArrayList<>();
  private boolean terminal;
  private Long prior;
  private long next;
  private String fail;

  private SessionEventAppend append() {
    return (SessionEventAppend)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SessionEventAppend.class},
            (p, m, a) -> {
              calls.add(m.getName());
              if (m.getName().equals(fail)) throw new IllegalStateException(fail);
              return switch (m.getName()) {
                case "terminal" -> terminal;
                case "history" -> {
                  assertEquals(!terminal, a[0]);
                  yield (SessionEventAppend.History)
                      () -> {
                        calls.add("next");
                        return next;
                      };
                }
                case "prior" -> prior;
                case "event" ->
                    (SessionEventAppend.Event)
                        () -> {
                          calls.add("bytes");
                          return 17;
                        };
                case "body" -> {
                  assertEquals(17, a[0]);
                  yield null;
                }
                default -> null;
              };
            });
  }

  @Test
  void duplicateSkipsNextClockSerializationBodyAndWrites() {
    prior = 5L;
    next = 999;
    assertEquals(5, new SessionEventAppendServiceImpl().append(append()));
    assertEquals(List.of("terminal", "history", "prior"), calls);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 125})
  void ordinaryAppend(long seq) {
    next = seq;
    assertEquals(seq, new SessionEventAppendServiceImpl().append(append()));
    assertEquals(
        List.of(
            "terminal",
            "history",
            "prior",
            "next",
            "event",
            "validate",
            "bytes",
            "body",
            "insert",
            "advance"),
        calls);
  }

  @ParameterizedTest
  @ValueSource(longs = {126, 127})
  void terminalUsesReservedSlots(long seq) {
    next = seq;
    terminal = true;
    assertEquals(seq, new SessionEventAppendServiceImpl().append(append()));
  }

  @ParameterizedTest
  @ValueSource(longs = {126, 127, 128})
  void ordinaryLimitBeforeClock(long seq) {
    next = seq;
    var failure =
        assertThrows(TaskFailure.class, () -> new SessionEventAppendServiceImpl().append(append()));
    assertEquals("SESSION_HISTORY_LIMIT", failure.code());
    assertEquals(List.of("terminal", "history", "prior", "next"), calls);
  }

  @Test
  void terminalLimitBeforeClock() {
    next = 128;
    terminal = true;
    assertThrows(TaskFailure.class, () -> new SessionEventAppendServiceImpl().append(append()));
    assertEquals(List.of("terminal", "history", "prior", "next"), calls);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"terminal", "history", "prior", "event", "validate", "body", "insert", "advance"})
  void failuresStopAtOriginalStage(String point) {
    fail = point;
    assertThrows(
        IllegalStateException.class, () -> new SessionEventAppendServiceImpl().append(append()));
    assertEquals(point, calls.getLast());
  }
}
