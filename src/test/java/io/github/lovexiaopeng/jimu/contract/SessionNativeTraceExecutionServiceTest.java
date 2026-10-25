package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeTraceExecution;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionNativeTraceExecutionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionNativeTraceExecutionServiceTest {
  private final List<String> calls = new ArrayList<>();
  private String fail, requested;
  private boolean enabled = true, exists = true, budget;
  private long upper = 1, next = 2, seq = 0, source = 0;
  private int count = 2, offset, size = 4, characters = 4, sourceCount = 1, summary = 20;

  private <T> T proxy(Class<T> type, String prefix, BiFunction<String, Object[], Object> result) {
    return type.cast(
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              String name = prefix + m.getName();
              calls.add(name);
              if (name.equals(fail)) throw new IllegalStateException(name);
              return result.apply(m.getName(), a);
            }));
  }

  private SessionNativeTraceExecution execution() {
    return proxy(
        SessionNativeTraceExecution.class,
        "",
        (m, a) ->
            switch (m) {
              case "history" ->
                  proxy(SessionNativeTraceExecution.History.class, "history.", (n, b) -> next);
              case "enabled" -> enabled;
              case "requestedSession" -> requested;
              case "session" -> "session";
              case "seq" -> seq;
              case "upper" -> upper;
              case "exists" -> exists;
              case "budget" -> {
                budget = true;
                yield new SessionNativeTraceExecution.Budget() {
                  public void operation(long amount) {
                    calls.add("operation");
                    if ("operation".equals(fail)) throw new IllegalStateException("operation");
                  }

                  public void close() {
                    budget = false;
                    calls.add("close");
                  }
                };
              }
              case "scan" ->
                  IntStream.range(0, count)
                      .mapToObj(
                          i ->
                              proxy(
                                  SessionNativeTraceExecution.Event.class,
                                  "e" + i + ".",
                                  (n, b) ->
                                      switch (n) {
                                        case "seq" -> (long) i + offset;
                                        case "snapshotBytes" -> size;
                                        case "digest" -> "d" + i;
                                        case "read" -> i > 0;
                                        case "receipt" ->
                                            new SessionNativeTraceExecution.Receipt(
                                                "r" + i, "SUCCEEDED");
                                        case "characters" -> characters;
                                        case "verify" ->
                                            new SessionNativeTraceExecution.Value() {
                                              public Iterable<SessionNativeTraceExecution.Source>
                                                  sources() {
                                                return IntStream.range(0, sourceCount)
                                                    .mapToObj(
                                                        j ->
                                                            (SessionNativeTraceExecution.Source)
                                                                () -> source)
                                                    .toList();
                                              }

                                              public int sourceCount() {
                                                return i > 0 ? sourceCount : 0;
                                              }
                                            };
                                        default -> throw new AssertionError(n);
                                      }))
                      .toList();
              case "summary" -> (SessionNativeTraceExecution.Summary) () -> summary;
              case "output" -> {
                assertEquals(1, ((List<?>) a[2]).size());
                yield "output";
              }
              case "validate", "deadline" -> null;
              case "scanDigest", "relationsDigest" -> m;
              default -> throw new AssertionError(m);
            });
  }

  private SessionNativeTraceExecution.Result run() {
    return new SessionNativeTraceExecutionServiceImpl().trace(execution());
  }

  private void order(String a, String b) {
    assertTrue(calls.indexOf(a) >= 0 && calls.indexOf(a) < calls.indexOf(b), calls.toString());
  }

  private void failure(String code) {
    assertEquals(code, assertThrows(TaskFailure.class, this::run).code());
    assertFalse(budget);
  }

  @Test
  void preservesOrderAndRelations() {
    var result = run();
    assertEquals(8, result.eventBytes());
    assertEquals(1, result.edges());
    order("history", "enabled");
    order("exists", "budget");
    order("deadline", "e0.seq");
    order("e0.seq", "e0.snapshotBytes");
    order("e0.snapshotBytes", "e0.verify");
    order("e0.verify", "e0.digest");
    assertTrue(calls.indexOf("e1.receipt") < calls.lastIndexOf("operation"));
    order("validate", "scanDigest");
    assertEquals("close", calls.getLast());
  }

  @Test
  void unavailableBeforeInputs() {
    enabled = false;
    fail = "requestedSession";
    failure("SESSION_TRACE_UNAVAILABLE");
  }

  @Test
  void foreignSessionBeforeUpper() {
    requested = "other";
    fail = "upper";
    failure("SESSION_EVENT_NOT_FOUND");
  }

  @Test
  void seqBeforeUpperGuard() {
    seq = 2;
    next = 0;
    fail = "history.next";
    failure("SESSION_EVENT_NOT_FOUND");
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 128})
  void upperBeforeNext(long value) {
    upper = value;
    seq = -2;
    fail = "history.next";
    failure("SESSION_HISTORY_CORRUPT");
  }

  @Test
  void nextTooLarge() {
    next = 129;
    failure("SESSION_HISTORY_CORRUPT");
    assertFalse(calls.contains("exists"));
  }

  @Test
  void upperNotBeforeNext() {
    next = 1;
    failure("SESSION_HISTORY_CORRUPT");
  }

  @Test
  void missingProbeBeforeBudget() {
    exists = false;
    fail = "budget";
    failure("SESSION_EVENT_NOT_FOUND");
  }

  @Test
  void sparseBeforeBytes() {
    offset = 1;
    fail = "e0.snapshotBytes";
    failure("SESSION_HISTORY_CORRUPT");
  }

  @Test
  void missingRowsBeforeDeadline() {
    count = 1;
    fail = "deadline";
    failure("SESSION_HISTORY_CORRUPT");
  }

  @Test
  void exactBytesAllowed() {
    size = 1048576;
    assertEquals(2097152, run().eventBytes());
  }

  @Test
  void excessBytesBeforeVerify() {
    size = 1048577;
    fail = "e1.verify";
    failure("SESSION_HISTORY_LIMIT");
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 1, 2})
  void invalidSources(long value) {
    source = value;
    failure("SESSION_HISTORY_CORRUPT");
  }

  @Test
  void receiptsLimit() {
    upper = 21;
    next = 22;
    count = 22;
    failure("SESSION_HISTORY_LIMIT");
    assertFalse(calls.contains("e21.characters"));
  }

  @Test
  void edgesLimit() {
    upper = 2;
    next = 3;
    count = 3;
    sourceCount = 100;
    failure("SESSION_HISTORY_LIMIT");
  }

  @Test
  void derivedLimit() {
    sourceCount = 21;
    failure("SESSION_HISTORY_LIMIT");
    assertFalse(calls.contains("summary"));
  }

  @Test
  void operationCounterLimitAfterBudget() {
    characters = 524257;
    failure("SESSION_HISTORY_LIMIT");
    assertEquals(2, calls.stream().filter("operation"::equals).count());
  }

  @Test
  void summaryLimitBeforeOutput() {
    summary = 2049;
    fail = "output";
    failure("SESSION_HISTORY_LIMIT");
  }

  @Test
  void summaryExactAllowed() {
    summary = 2048;
    assertEquals("output", run().output());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "history",
        "requestedSession",
        "history.next",
        "exists",
        "scan",
        "deadline",
        "e0.seq",
        "e0.snapshotBytes",
        "e0.verify",
        "e0.digest",
        "e1.receipt",
        "e1.characters",
        "operation",
        "summary",
        "output",
        "validate",
        "scanDigest",
        "relationsDigest"
      })
  void exceptionFinally(String point) {
    fail = point;
    assertThrows(IllegalStateException.class, this::run);
    assertFalse(budget);
    if (calls.contains("budget")) assertEquals("close", calls.getLast());
  }
}
