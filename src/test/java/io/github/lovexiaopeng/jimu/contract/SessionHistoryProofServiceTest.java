package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionHistoryProofServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 共享history锁读取只在原位置执行；Checkpoint/context短路不提前触碰历史。 */
class SessionHistoryProofServiceTest {
  private final SessionHistoryProofServiceImpl service = new SessionHistoryProofServiceImpl();
  private final List<String> calls = new ArrayList<>();
  private final Map<String, Object> values = new HashMap<>();
  private final TaskFailure timeout = new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
  private String failureAt;
  private SessionHistoryProofs.HistoryRow history =
      history(1L, 1L, "binding", "hash:binding", "PRESENT", 1L);
  private SessionHistoryProofs.StepRow step =
      step(1L, "task", 4L, "hash:request", "hash:context", "stored-binding");

  SessionHistoryProofServiceTest() {
    values.put("version", 1L);
    values.put("binding", "binding");
    values.put("task", "task");
    values.put("checkpointVersion", 2);
    values.put("messagesEmpty", false);
    values.put("lastContent", "context");
    values.put("context", new SessionHistoryProofs.Context("task", 4));
    values.put("lastRole", "user");
    values.put("historyBinding", "stored-binding");
  }

  private static SessionHistoryProofs.HistoryRow history(
      Long record, Long version, String binding, String digest, String state, Long retained) {
    return new SessionHistoryProofs.HistoryRow(record, version, binding, digest, state, retained);
  }

  private static SessionHistoryProofs.StepRow step(
      Long version,
      String task,
      Long upper,
      String requestDigest,
      String contextDigest,
      String bindingDigest) {
    return new SessionHistoryProofs.StepRow(
        version, task, upper, "request", requestDigest, contextDigest, bindingDigest);
  }

  private <T> T port(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              String name = method.getName();
              calls.add(name);
              if (name.equals(failureAt)) throw timeout;
              return switch (name) {
                case "load" -> history;
                case "step" -> step;
                case "hash" -> "hash:" + args[0];
                default -> values.get(name);
              };
            }));
  }

  private void rejectHistory(boolean readable, int status, String code) {
    var error =
        assertThrows(
            TaskFailure.class,
            () -> service.history(port(SessionHistoryProofs.History.class), readable));
    assertEquals(status, error.status());
    assertEquals(code, error.code());
  }

  private void rejectStep(String code) {
    var error =
        assertThrows(
            TaskFailure.class, () -> service.validateStep(port(SessionHistoryProofs.Step.class)));
    assertEquals(409, error.status());
    assertEquals(code, error.code());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void validHistoryLoadsOnceAtEntry(boolean readable) {
    service.history(port(SessionHistoryProofs.History.class), readable);
    assertEquals(List.of("load", "version", "binding", "binding", "hash"), calls);
  }

  @Test
  void unavailablePrecedesVersionsAndBinding() {
    history = null;
    rejectHistory(true, 409, "SESSION_HISTORY_UNAVAILABLE");
    assertEquals(List.of("load"), calls);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 2, 3})
  void recordVersionPrecedesTaskVersion(long version) {
    history = history(version, null, null, null, "PURGED", null);
    rejectHistory(true, 409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    assertEquals(List.of("load"), calls);
  }

  @ParameterizedTest
  @ValueSource(longs = {2, 3, 4})
  void historyVersionPrecedesBinding(long version) {
    history = history(1L, version, null, null, "PURGED", null);
    rejectHistory(true, 409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    assertEquals(List.of("load", "version"), calls);
  }

  @Test
  void snapshotPrecedesDigestAndExpiry() {
    history = history(1L, 1L, null, null, "PURGED", null);
    rejectHistory(true, 409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    assertEquals(List.of("load", "version", "binding"), calls);
  }

  @Test
  void digestPrecedesExpiry() {
    history = history(1L, 1L, "binding", null, "PURGED", null);
    rejectHistory(true, 409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    assertEquals("hash", calls.getLast());
  }

  @Test
  void unreadableSkipsRetentionOnlyAfterBinding() {
    history = history(1L, 1L, "binding", "hash:binding", "PURGED", null);
    service.history(port(SessionHistoryProofs.History.class), false);
    assertEquals("hash", calls.getLast());
  }

  @Test
  void purgedPrecedesNullRetention() {
    history = history(1L, 1L, "binding", "hash:binding", "PURGED", null);
    rejectHistory(true, 410, "SESSION_HISTORY_EXPIRED");
  }

  @Test
  void databaseRetentionFactControlsExpiry() {
    history = history(1L, 1L, "binding", "hash:binding", "PRESENT", 0L);
    rejectHistory(true, 410, "SESSION_HISTORY_EXPIRED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"record", "version", "retained"})
  void originalHistoryNullUnboxing(String field) {
    history =
        history(
            field.equals("record") ? null : 1L,
            field.equals("version") ? null : 1L,
            "binding",
            "hash:binding",
            "PRESENT",
            field.equals("retained") ? null : 1L);
    assertThrows(
        NullPointerException.class,
        () -> service.history(port(SessionHistoryProofs.History.class), true));
  }

  @Test
  void validStepAcquiresHistoryOnlyAfterContextProof() {
    service.validateStep(port(SessionHistoryProofs.Step.class));
    assertEquals(
        List.of(
            "step",
            "task",
            "hash",
            "checkpointVersion",
            "messagesEmpty",
            "lastContent",
            "validateContext",
            "context",
            "lastRole",
            "task",
            "hash",
            "historyBinding"),
        calls);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 2, 3})
  void stepVersionPrecedesTaskAndBody(long version) {
    step = step(version, "other", null, null, null, null);
    rejectStep("SESSION_HISTORY_VERSION_UNSUPPORTED");
    assertEquals(List.of("step"), calls);
  }

  @Test
  void taskAndNullUpperPrecedeRequest() {
    step = step(1L, "other", null, null, null, null);
    rejectStep("SESSION_HISTORY_VERSION_UNSUPPORTED");
    assertEquals(List.of("step", "task"), calls);
  }

  @Test
  void nullUpperPrecedesRequest() {
    step = step(1L, "task", null, null, null, null);
    rejectStep("SESSION_HISTORY_VERSION_UNSUPPORTED");
    assertEquals(List.of("step", "task"), calls);
  }

  @Test
  void requestDigestPrecedesCheckpointDecode() {
    step = step(1L, "task", 4L, "bad", null, null);
    rejectStep("SESSION_HISTORY_CORRUPT");
    assertEquals(List.of("step", "task", "hash"), calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3})
  void checkpointVersionPrecedesMessages(int version) {
    values.put("checkpointVersion", version);
    rejectStep("SESSION_HISTORY_CORRUPT");
    assertEquals("checkpointVersion", calls.getLast());
  }

  @Test
  void emptyMessagesPrecedeLastContent() {
    values.put("messagesEmpty", true);
    rejectStep("SESSION_HISTORY_CORRUPT");
    assertEquals("messagesEmpty", calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"role", "session", "digest", "upper", "binding"})
  void contextShortCircuitsBeforeLaterFacts(String mismatch) {
    if (mismatch.equals("role")) values.put("lastRole", "assistant");
    if (mismatch.equals("session"))
      values.put("context", new SessionHistoryProofs.Context("other", 4));
    if (mismatch.equals("digest")) step = step(1L, "task", 4L, "hash:request", "bad", null);
    if (mismatch.equals("upper"))
      values.put("context", new SessionHistoryProofs.Context("task", 5));
    if (mismatch.equals("binding")) values.put("historyBinding", "bad");
    rejectStep("SESSION_HISTORY_CORRUPT");
    assertEquals(mismatch.equals("binding"), calls.contains("historyBinding"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"step-version", "last-role"})
  void originalStepNullBoundary(String field) {
    if (field.equals("step-version"))
      step = step(null, "task", 4L, "hash:request", "hash:context", "stored-binding");
    else values.put("lastRole", null);
    assertThrows(
        NullPointerException.class,
        () -> service.validateStep(port(SessionHistoryProofs.Step.class)));
    assertTrue(!calls.contains("historyBinding"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"load", "version", "binding", "hash"})
  void historyFailurePropagatesWithoutRetry(String stage) {
    failureAt = stage;
    assertSame(
        timeout,
        assertThrows(
            TaskFailure.class,
            () -> service.history(port(SessionHistoryProofs.History.class), true)));
    assertEquals(stage, calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "step",
        "hash",
        "checkpointVersion",
        "messagesEmpty",
        "lastContent",
        "validateContext",
        "context",
        "lastRole",
        "historyBinding"
      })
  void stepFailurePropagatesWithoutLaterLookupOrRetry(String stage) {
    failureAt = stage;
    assertSame(
        timeout,
        assertThrows(
            TaskFailure.class, () -> service.validateStep(port(SessionHistoryProofs.Step.class))));
    assertEquals(stage, calls.getLast());
    assertEquals(1, calls.stream().filter(stage::equals).count());
  }
}
