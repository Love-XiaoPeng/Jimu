package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Authority;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.AuthorityTarget;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Caller;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.History;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Scope;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Target;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 整体scope完整性、原拒绝次序与事务后记；真实SQL/事务另由私有MySQL验证。 */
class SessionScopeEligibilityServiceTest {
  private final LocalDateTime created = LocalDateTime.of(2026, 10, 24, 0, 0),
      retain = created.plusDays(7);
  private final Caller caller =
      new Caller(
          "tenant-a",
          "caller",
          "alice",
          "session_event_read,session_search",
          "tools",
          7,
          "SUCCEEDED",
          created,
          "request");
  private final Caller source =
      new Caller(
          "tenant-a",
          "source",
          "alice",
          "session_event_read,session_search",
          "tools",
          7,
          "SUCCEEDED",
          created,
          "request");
  private final AuthorityTarget target =
      new AuthorityTarget(
          "source",
          "tools",
          "binding",
          0,
          "2026-10-24T00:00:00.000000Z",
          "2026-10-31T00:00:00.000000Z",
          "target");
  private Scope scope = scope(1),
      own = change(change(scope(0), "raw", "own"), "digest", Payloads.hash("own"));
  private Caller sourceRow = source;
  private Target targetRow =
      new Target(1, 0, "source", "target", Payloads.hash("target"), 6, "PRESENT");
  private boolean unknown, extraTargets, admission = true;
  private final List<String> calls = new ArrayList<>();

  private Scope scope(long count) {
    return new Scope(
        1,
        1,
        "alice",
        Payloads.hash("expected"),
        "tools",
        "raw",
        Payloads.hash("raw"),
        3,
        count,
        "ACTIVE",
        created,
        retain,
        null,
        null,
        created,
        "PRESENT",
        true);
  }

  private Authority authority(boolean caller) {
    return new Authority(
        "tenant-a",
        caller ? "caller" : "source",
        "alice",
        "tools",
        Payloads.hash("expected"),
        "2026-10-24T00:00:00.000000Z",
        "2026-10-31T00:00:00.000000Z",
        caller ? List.of(target) : List.of());
  }

  @SuppressWarnings("unchecked")
  private static <T> T change(T value, String key, Object replacement) {
    try {
      var fields = value.getClass().getRecordComponents();
      var types = new Class<?>[fields.length];
      var values = new Object[fields.length];
      for (int i = 0; i < fields.length; i++) {
        types[i] = fields[i].getType();
        values[i] =
            fields[i].getName().equals(key) ? replacement : fields[i].getAccessor().invoke(value);
      }
      return (T) value.getClass().getDeclaredConstructor(types).newInstance(values);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private SessionScopeEligibilityServiceImpl service() {
    var records =
        (SessionScopeRecords)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionScopeRecords.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("utf8Bytes") || method.getName().equals("matchesRaw"))
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                  calls.add(method.getName());
                  return switch (method.getName()) {
                    case "scope" -> ((Caller) args[0]).id().equals("caller") ? scope : own;
                    case "expectedBinding" -> "expected";
                    case "authority" -> authority(args[1].equals("raw"));
                    case "validatedAuthority", "stagedSource", "stageSource" -> null;
                    case "decodeAuthority" -> authority(args[0].equals("raw"));
                    case "targets" -> List.of(targetRow);
                    case "history" -> new History(1, "binding", retain);
                    case "source" -> sourceRow;
                    case "writersQuiescent" -> true;
                    case "activeInvocations", "dispatchedModels" -> false;
                    case "unsafeReservations" -> unknown;
                    case "hasTargets" -> extraTargets;
                    case "admissionRetention" -> admission;
                    case "callerCapabilities" -> List.of("session_event_read,session_search");
                    case "lockTargets" -> java.util.Arrays.asList("z", "a", null, "a");
                    case "lockTask" -> {
                      calls.add("lock:" + args[0] + ":" + args[1]);
                      yield null;
                    }
                    case "validateAuthority", "invalidateAfterTransaction" -> null;
                    default -> throw new AssertionError(method.getName());
                  };
                });
    return new SessionScopeEligibilityServiceImpl(records, Payloads::hash);
  }

  private void rejects(String code) {
    var failure = assertThrows(TaskFailure.class, () -> service().require(caller));
    assertEquals(code, failure.code());
  }

  @Test
  void completeScopeReturnsExactSources() {
    assertEquals(List.of(source), service().require(caller).sources());
    assertFalse(calls.contains("invalidateAfterTransaction"));
  }

  @Test
  void versionCorruptionPrecedesRevocationAndDoesNotInvalidate() {
    scope = change(scope, "recordVersion", 2L);
    scope = change(scope, "grant", "REVOKED");
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(calls.contains("invalidateAfterTransaction"));
    assertFalse(calls.contains("validateAuthority"));
  }

  @Test
  void revokedScopeSchedulesPostTransactionBeforeRefusal() {
    scope = change(scope, "grant", "REVOKED");
    rejects("SESSION_SCOPE_UNAVAILABLE");
    assertEquals("invalidateAfterTransaction", calls.getLast());
    assertFalse(calls.contains("history"));
  }

  @Test
  void invalidatedScopeNeverResurrects() {
    scope = change(scope, "invalidated", created);
    rejects("SESSION_SCOPE_UNAVAILABLE");
  }

  @Test
  void expiredWholeScopeRefusesBeforeReadingSources() {
    scope = change(scope, "retained", false);
    rejects("SESSION_SCOPE_UNAVAILABLE");
    assertFalse(calls.contains("source"));
  }

  @Test
  void digestCorruptionDoesNotBecomeAuthorityLoss() {
    scope = change(scope, "digest", "bad");
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(calls.contains("decodeAuthority"));
    assertFalse(calls.contains("invalidateAfterTransaction"));
  }

  @Test
  void targetIdentityMismatchRejectsBeforeSourceRead() {
    targetRow = change(targetRow, "source", "other");
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(calls.contains("source"));
  }

  @Test
  void sourceOwnerLossInvalidatesWholeScope() {
    sourceRow = change(sourceRow, "owner", "bob");
    rejects("SESSION_SCOPE_UNAVAILABLE");
    assertTrue(calls.contains("invalidateAfterTransaction"));
  }

  @Test
  void unknownSourceReservationRefusesBeforeSourceHistory() {
    unknown = true;
    rejects("SESSION_SCOPE_UNAVAILABLE");
    assertEquals(1, calls.stream().filter("history"::equals).count());
  }

  @Test
  void sourceScopeCannotHideAdditionalTargets() {
    extraTargets = true;
    rejects("SESSION_SCOPE_UNAVAILABLE");
  }

  @Test
  void sourceRetentionFloorOnlyRunsForAdmission() {
    admission = false;
    assertThrows(TaskFailure.class, () -> service().source(caller, "source", true));
    assertTrue(calls.contains("admissionRetention"));
  }

  @Test
  void lockOrderIsTenantScopedDistinctSortedAndNullTolerant() {
    service().lock("tenant-a", "caller");
    assertEquals(
        List.of("lock:tenant-a:a", "lock:tenant-a:caller", "lock:tenant-a:z"),
        calls.stream().filter(s -> s.startsWith("lock:")).toList());
  }
}
