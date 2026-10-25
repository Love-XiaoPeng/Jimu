package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionFacts;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionFactServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SessionFactServiceTest {
  private final SessionFactServiceImpl service = new SessionFactServiceImpl();
  private final List<String> order = new ArrayList<>();
  private final Map<String, Object> values = new java.util.HashMap<>();
  private Object[] appended;

  @SuppressWarnings("unchecked")
  private <T> T port(Class<T> type, String prefix) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, args) -> {
              String key = prefix + m.getName();
              order.add(key);
              if (key.equals("append")) {
                appended = args;
                return null;
              }
              if (!values.containsKey(key))
                throw new AssertionError("Unexpected eager access " + key);
              Object value = values.get(key);
              if (value instanceof RuntimeException failure) throw failure;
              if (value instanceof Function<?, ?> function)
                return ((Function<Object[], Object>) function).apply(args);
              return value;
            });
  }

  private SessionFacts.Fact records() {
    values.put("session", true);
    return port(SessionFacts.Fact.class, "");
  }

  private void lease() {
    values.put("attempt", port(SessionFacts.Attempt.class, "attempt."));
    values.put("tenant", "tenant");
    values.put("owner", "owner");
    values.put("taskId", "task");
    values.put("attempt.id", "attempt");
    values.put("attempt.fence", 7L);
    values.put("worker", "worker");
    values.put("deadline", Instant.EPOCH);
    values.put("goal", "goal");
    values.put("caps", List.of("session_event_read"));
    values.put("installationEpoch", 9L);
  }

  @Test
  void nonSessionDoesNotReadNullFact() {
    var records = records();
    values.put("session", false);
    service.record(null, records);
    assertEquals(List.of("session"), order);
  }

  @Test
  void sessionNullFactRetainsNullFailure() {
    assertThrows(NullPointerException.class, () -> service.record(null, records()));
    assertEquals(List.of("session"), order);
  }

  @Test
  void unknownFactDoesNotReadRowsOrAccount() {
    service.record("history:accepted-user", records());
    assertEquals(List.of("session"), order);
  }

  @Test
  void acceptedSourcePrecedesContentAndDigest() {
    values.put("goalText", new SessionFacts.Text("public", true));
    values.put("source", Map.of("kind", "request"));
    values.put("requestDigest", "digest");
    service.record("accepted", records());
    assertEquals(List.of("session", "goalText", "source", "requestDigest", "append"), order);
    assertEquals("accepted-user", appended[0]);
    assertEquals(Map.of("content", "public"), appended[3]);
    assertEquals(true, appended[4]);
    assertNull(appended[6]);
  }

  @Test
  void nullAcceptedContentFailsAfterSourceBeforeDigest() {
    values.put("goalText", new SessionFacts.Text(null, false));
    values.put("source", Map.of());
    assertThrows(NullPointerException.class, () -> service.record("accepted", records()));
    assertEquals(List.of("session", "goalText", "source"), order);
  }

  @Test
  void nonRespondedReturnsBeforeAttemptOrLease() {
    values.put("step", port(SessionFacts.Step.class, "step."));
    values.put("step.state", null);
    service.record("response:step", records());
    assertEquals(List.of("session", "step", "step.state"), order);
  }

  @Test
  void preparedDoesNotReadStateAndLeaseArgumentsStayOrdered() {
    values.put("step", port(SessionFacts.Step.class, "step."));
    values.put("step.ordinal", 2L);
    values.put("source", Map.of());
    values.put("step.requestDigest", "request");
    lease();
    service.record("prepared:step", records());
    assertEquals(
        List.of(
            "session",
            "step",
            "attempt",
            "tenant",
            "owner",
            "taskId",
            "attempt.id",
            "attempt.fence",
            "worker",
            "deadline",
            "goal",
            "caps",
            "installationEpoch",
            "step.ordinal",
            "source",
            "step.requestDigest",
            "append"),
        order);
    assertEquals(Map.of("ordinal", 2L), appended[3]);
    assertEquals(7L, ((Lease) appended[6]).fence());
  }

  @Test
  void fenceFailureDoesNotReadWorkerDeadlineOrResponse() {
    values.put("step", port(SessionFacts.Step.class, "step."));
    lease();
    values.put("attempt.fence", new NullPointerException("fence"));
    assertThrows(NullPointerException.class, () -> service.record("prepared:step", records()));
    assertFalse(order.contains("worker"));
    assertFalse(order.contains("deadline"));
    assertFalse(order.contains("step.response"));
  }

  @Test
  void responseProjectsBeforeRedactionShortCircuit() {
    values.put("step", port(SessionFacts.Step.class, "step."));
    values.put("step.state", "RESPONDED");
    values.put("step.response", port(SessionFacts.Response.class, "response."));
    values.put("response.text", new SessionFacts.Text("public", true));
    values.put("response.project", port(SessionFacts.Projection.class, "project."));
    values.put("project.calls", List.of());
    values.put("source", Map.of());
    values.put("step.responseDigest", "response");
    lease();
    service.record("response:step", records());
    assertFalse(order.contains("project.redacted"));
    assertTrue(order.indexOf("response.project") < order.indexOf("project.calls"));
    assertTrue(order.indexOf("project.calls") < order.indexOf("source"));
    assertEquals(Map.of("content", "public", "calls", List.of()), appended[3]);
  }

  @Test
  void terminalNullCodeAndFieldOrderRetained() {
    values.put("terminal", port(SessionFacts.Terminal.class, "terminal."));
    values.put("terminal.state", "CANCELLED");
    values.put("terminal.code", null);
    values.put("source", Map.of());
    values.put("terminal.digest", "four-column-digest");
    service.record("terminal", records());
    assertEquals(
        List.of(
            "session",
            "terminal",
            "terminal.state",
            "terminal.code",
            "source",
            "terminal.digest",
            "append"),
        order);
    var data = (LinkedHashMap<?, ?>) appended[3];
    assertEquals(List.of("state", "code"), new ArrayList<>(data.keySet()));
    assertTrue(data.containsKey("code"));
    assertNull(data.get("code"));
  }

  @Test
  void missingStepFailurePrecedesAttempt() {
    values.put("step", new IllegalStateException("original404"));
    assertThrows(IllegalStateException.class, () -> service.record("prepared:missing", records()));
    assertEquals(List.of("session", "step"), order);
  }

  @Test
  void appendAlwaysAccountsAfterIdempotentAppendAndHasNoSessionGuard() {
    values.put(
        "a.changed",
        (Function<Object[], Object>)
            args -> {
              assertEquals("history:null", args[0]);
              return null;
            });
    values.put("a.reliable", 17L);
    values.put(
        "a.append",
        (Function<Object[], Object>)
            args -> {
              assertEquals(17L, args[0]);
              return 2L;
            });
    values.put("a.terminal", true);
    values.put("a.account", null);
    assertEquals(2L, service.append(null, port(SessionFacts.Append.class, "a.")));
    assertEquals(List.of("a.changed", "a.reliable", "a.append", "a.terminal", "a.account"), order);
  }

  @Test
  void appendFailureDoesNotReadClosingOrAccount() {
    values.put("a.changed", null);
    values.put("a.reliable", 1L);
    values.put("a.append", new IllegalStateException("body"));
    assertThrows(
        IllegalStateException.class,
        () -> service.append("x", port(SessionFacts.Append.class, "a.")));
    assertEquals(List.of("a.changed", "a.reliable", "a.append"), order);
  }
}
