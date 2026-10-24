package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeExecutionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionScopeExecutionServiceTest {
  private final SessionScopeExecutionServiceImpl service = new SessionScopeExecutionServiceImpl();
  private final List<String> calls = new ArrayList<>();
  private String fail;
  private boolean budget;
  private int sources = 1, count = 2, eventBytes = 5;
  private long upper = 1;
  private boolean allowed = true;
  private final SessionScopeExecution.Value value = new SessionScopeExecution.Value() {};
  private final SessionScopeExecution.Ref ref = new SessionScopeExecution.Ref() {};

  private <T> T port(Class<T> type, String prefix, BiFunction<String, Object[], Object> answer) {
    return type.cast(
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              String call = prefix + "." + m.getName();
              calls.add(call);
              if (call.equals(fail)) throw new IllegalStateException(call);
              return answer.apply(m.getName(), a);
            }));
  }

  private SessionScopeExecution.Budget open() {
    budget = true;
    return () -> {
      budget = false;
      calls.add("close");
    };
  }

  private SessionScopeExecution.Event event(int seq) {
    return port(
        SessionScopeExecution.Event.class,
        "event" + seq,
        (m, a) ->
            switch (m) {
              case "seq" -> (long) seq;
              case "digest" -> "digest" + seq;
              case "snapshotBytes" -> eventBytes;
              case "storedBytes" -> (long) eventBytes;
              case "verify" -> value;
              default -> throw new AssertionError(m);
            });
  }

  private SessionScopeExecution.Source source() {
    return port(
        SessionScopeExecution.Source.class,
        "source",
        (m, a) ->
            switch (m) {
              case "scan" -> IntStream.range(0, count).mapToObj(this::event).toList();
              case "ref" -> ref;
              case "window" -> window((SessionEventPort.Input) a[0]);
              default -> throw new AssertionError(m);
            });
  }

  private SessionScopeExecution.Scope scope() {
    return port(
        SessionScopeExecution.Scope.class,
        "scope",
        (m, a) ->
            switch (m) {
              case "contains" -> allowed;
              case "size" -> sources;
              case "source", "find" -> source();
              case "cut" ->
                  port(SessionScopeExecution.Cut.class, "cut" + a[0], (method, args) -> upper);
              case "digest" -> "scope";
              default -> throw new AssertionError(m);
            });
  }

  private SessionScopeExecution.Prior prior() {
    return port(
        SessionScopeExecution.Prior.class,
        "prior",
        (m, a) ->
            switch (m) {
              case "require" -> scope();
              case "sessions" -> List.of("source");
              case "matcher" ->
                  port(
                      SessionScopeExecution.Matcher.class,
                      "matcher",
                      (method, args) -> method.equals("output") ? "output" : null);
              case "budget" -> open();
              case "caller" -> "self";
              case "scanDigest" -> "scan";
              default -> null;
            });
  }

  private SessionScopeExecution.Window window(SessionEventPort.Input input) {
    return port(
        SessionScopeExecution.Window.class,
        "window",
        (m, a) ->
            switch (m) {
              case "input" -> input;
              case "session" -> "self";
              case "upper" -> upper;
              case "events" -> IntStream.range(0, count).mapToObj(this::event).toList();
              case "ref" -> ref;
              case "summary" -> value;
              case "output" ->
                  new SessionScopeExecution.ReadResult(
                      "output",
                      (long) a[0],
                      (long) a[1],
                      (String) a[3],
                      "refs",
                      count * eventBytes);
              default -> null;
            });
  }

  @Test
  void priorPreservesOrderAndClosesBudget() {
    var result = service.prior(prior());
    assertEquals(new SessionScopeExecution.SearchResult("output", "scan", 2, 10), result);
    assertEquals(
        List.of(
            "prior.require", "prior.sessions", "scope.contains", "prior.matcher", "prior.budget"),
        calls.subList(0, 5));
    assertTrue(calls.indexOf("source.scan") < calls.indexOf("event0.verify"));
    assertTrue(calls.indexOf("prior.deadline") < calls.indexOf("event0.verify"));
    assertTrue(calls.indexOf("event0.verify") < calls.indexOf("source.ref"));
    assertTrue(calls.indexOf("source.ref") < calls.indexOf("matcher.accept"));
    assertFalse(budget);
    assertEquals("close", calls.getLast());
  }

  @Test
  void unauthorizedInputPrecedesMatcherAndBudget() {
    allowed = false;
    var failure = assertThrows(TaskFailure.class, () -> service.prior(prior()));
    assertEquals("SESSION_SCOPE_UNAVAILABLE", failure.code());
    assertEquals(404, failure.status());
    assertFalse(calls.contains("prior.matcher"));
    assertFalse(calls.contains("prior.budget"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "prior.deadline",
        "source.scan",
        "event0.verify",
        "matcher.accept",
        "prior.validate",
        "prior.scanDigest"
      })
  void failureAlwaysClearsBudget(String stage) {
    fail = stage;
    assertThrows(IllegalStateException.class, () -> service.prior(prior()));
    assertFalse(budget);
    assertEquals("close", calls.getLast());
  }

  @Test
  void sparseScanRejectedBeforeAnyEventProof() {
    upper = 2;
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> service.prior(prior())).code());
    assertFalse(calls.contains("event0.verify"));
    assertFalse(budget);
  }

  @Test
  void aggregateFourMiBStopsBeforeNextProof() {
    sources = 3;
    count = 128;
    upper = 127;
    eventBytes = 16384;
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> service.prior(prior())).code());
    assertEquals(256, calls.stream().filter(s -> s.endsWith(".verify")).count());
    assertFalse(budget);
  }

  @Test
  void oneEventLimitStopsBeforeProof() {
    eventBytes = 16385;
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> service.prior(prior())).code());
    assertFalse(calls.contains("event0.verify"));
    assertFalse(budget);
  }

  @Test
  void historyLockPrecedes404AndScan() {
    var error =
        assertThrows(
            TaskFailure.class,
            () -> service.window(window(new SessionEventPort.Input(2, "self", 0, 0))));
    assertEquals("SESSION_EVENT_NOT_FOUND", error.code());
    assertEquals("window.history", calls.getFirst());
    assertFalse(calls.contains("window.events"));
  }

  @Test
  void completeWindowHasOriginalBoundsAndSummaryOrder() {
    var result = service.window(window(new SessionEventPort.Input(0, "self", 1, 2)));
    assertEquals(0, result.from());
    assertEquals(1, result.to());
    assertTrue(calls.indexOf("event0.verify") < calls.indexOf("window.ref"));
    assertTrue(calls.indexOf("window.summary") < calls.indexOf("window.output"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"self", "source"})
  void scopeRequirePrecedesNormalizedAndBudget(String target) {
    var normalized =
        port(
            SessionScopeExecution.Normalized.class,
            "normalized",
            (m, a) ->
                switch (m) {
                  case "target" -> target;
                  case "input" -> new SessionEventPort.Input(0, "self", 0, 1);
                  case "upper" -> 1L;
                  default -> null;
                });
    var read =
        port(
            SessionScopeExecution.ScopeRead.class,
            "read",
            (m, a) ->
                switch (m) {
                  case "require" -> scope();
                  case "normalized" -> normalized;
                  case "callerId" -> "self";
                  case "caller" -> source();
                  case "budget" -> open();
                  default -> null;
                });
    service.read(read);
    assertEquals(
        List.of("read.require", "read.normalized", "normalized.target", "read.callerId"),
        calls.subList(0, 4));
    assertEquals(!target.equals("self"), calls.contains("scope.find"));
    assertTrue(calls.indexOf("read.budget") < calls.indexOf("normalized.input"));
    assertFalse(budget);
  }
}
