package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeSearchExecution;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionNativeSearchExecutionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionNativeSearchExecutionServiceTest {
  private final List<String> calls = new ArrayList<>();
  private String fail;
  private boolean budget, enabled = true;
  private long upper = 1, next = 2;
  private Long from, to;
  private int count = 2, size = 4, offset;

  private <T> T proxy(Class<T> type, String prefix, BiFunction<String, Object[], Object> answer) {
    return type.cast(
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              String label = prefix + m.getName();
              calls.add(label);
              if (label.equals(fail)) throw new IllegalStateException(label);
              return answer.apply(m.getName(), a);
            }));
  }

  private SessionNativeSearchExecution execution() {
    return proxy(
        SessionNativeSearchExecution.class,
        "",
        (m, a) ->
            switch (m) {
              case "history" ->
                  proxy(SessionNativeSearchExecution.History.class, "history.", (n, b) -> next);
              case "enabled" -> enabled;
              case "normalized", "deadline", "validate" -> null;
              case "from" -> from;
              case "to" -> to;
              case "upper" -> upper;
              case "budget" -> {
                budget = true;
                yield (SessionNativeSearchExecution.Budget)
                    () -> {
                      budget = false;
                      calls.add("close");
                    };
              }
              case "scan" ->
                  java.util.stream.IntStream.range(0, count)
                      .mapToObj(
                          i ->
                              proxy(
                                  SessionNativeSearchExecution.Event.class,
                                  "e" + i + ".",
                                  (n, b) ->
                                      switch (n) {
                                        case "seq" -> (long) i + offset;
                                        case "verify" ->
                                            new SessionNativeSearchExecution.Value() {};
                                        case "snapshotBytes" -> size;
                                        case "digest" -> "digest" + i;
                                        default -> throw new AssertionError(n);
                                      }))
                      .toList();
              case "matcher" ->
                  proxy(
                      SessionNativeSearchExecution.Matcher.class,
                      "matcher.",
                      (n, b) -> n.equals("output") ? "output" : null);
              case "scanDigest" -> {
                assertEquals(count, ((List<?>) a[0]).size());
                yield "scan";
              }
              default -> throw new AssertionError(m);
            });
  }

  private SessionNativeSearchExecution.Result run() {
    return new SessionNativeSearchExecutionServiceImpl().search(execution());
  }

  @Test
  void successKeepsProofBytesScanMatcherOrder() {
    var result = run();
    assertEquals(8, result.eventBytes());
    assertEquals(2, result.count());
    assertFalse(budget);
    assertTrue(calls.indexOf("history") < calls.indexOf("enabled"));
    assertTrue(calls.indexOf("enabled") < calls.indexOf("normalized"));
    assertTrue(calls.indexOf("normalized") < calls.indexOf("matcher"));
    assertTrue(calls.indexOf("matcher") < calls.indexOf("budget"));
    assertTrue(calls.indexOf("deadline") < calls.indexOf("e0.verify"));
    assertTrue(calls.indexOf("e0.verify") < calls.indexOf("e0.snapshotBytes"));
    assertTrue(calls.indexOf("e0.snapshotBytes") < calls.indexOf("e0.digest"));
    assertTrue(calls.indexOf("e0.digest") < calls.indexOf("matcher.accept"));
    assertTrue(calls.indexOf("validate") < calls.indexOf("scanDigest"));
  }

  @Test
  void emptyRangeSkipsNextAndScanButStillOutputs() {
    from = 3L;
    count = 0;
    fail = "history.next";
    run();
    assertFalse(calls.contains("scan"));
    assertFalse(budget);
  }

  @Test
  void negativeEmptyUpperSkipsNext() {
    upper = -1;
    next = 0;
    count = 0;
    fail = "history.next";
    run();
    assertFalse(calls.contains("scan"));
  }

  @Test
  void unavailableKeepsHistoryBeforeRefusal() {
    enabled = false;
    assertEquals("SESSION_SEARCH_UNAVAILABLE", assertThrows(TaskFailure.class, this::run).code());
    assertFalse(calls.contains("normalized"));
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 128, 129})
  void invalidUpperRefusesBeforeNext(long value) {
    upper = value;
    from = -2L;
    fail = "history.next";
    assertEquals("SESSION_HISTORY_CORRUPT", assertThrows(TaskFailure.class, this::run).code());
    assertFalse(budget);
  }

  @Test
  void upperMustBeBeforeNext() {
    next = 1;
    assertEquals("SESSION_HISTORY_CORRUPT", assertThrows(TaskFailure.class, this::run).code());
    assertFalse(calls.contains("scan"));
  }

  @Test
  void missingRowsRefuseBeforeProof() {
    count = 1;
    assertEquals("SESSION_HISTORY_CORRUPT", assertThrows(TaskFailure.class, this::run).code());
    assertFalse(calls.contains("e0.verify"));
  }

  @Test
  void sparseRowsRefuseBeforeProof() {
    offset = 1;
    assertEquals("SESSION_HISTORY_CORRUPT", assertThrows(TaskFailure.class, this::run).code());
    assertFalse(calls.contains("e0.verify"));
  }

  @Test
  void exactTwoMiBAllowed() {
    size = 1048576;
    assertEquals(2097152, run().eventBytes());
  }

  @Test
  void overTwoMiBAfterProofBeforeDigest() {
    size = 1048577;
    assertEquals("SESSION_HISTORY_LIMIT", assertThrows(TaskFailure.class, this::run).code());
    assertTrue(calls.contains("e1.verify"));
    assertFalse(calls.contains("e1.digest"));
    assertFalse(budget);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "history",
        "normalized",
        "matcher",
        "scan",
        "deadline",
        "e0.verify",
        "e0.snapshotBytes",
        "matcher.accept",
        "matcher.output",
        "validate",
        "scanDigest"
      })
  void failuresKeepBoundaryAndFinally(String point) {
    fail = point;
    assertThrows(IllegalStateException.class, this::run);
    assertFalse(budget);
    if (calls.contains("budget")) assertEquals("close", calls.getLast());
  }
}
