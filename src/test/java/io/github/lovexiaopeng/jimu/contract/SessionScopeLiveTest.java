package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Authority;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.AuthorityTarget;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Caller;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Live;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Scope;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Validated;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 缓存不代替数据库到期、源切点、整scope和逐节点当前身份。 */
class SessionScopeLiveTest {
  private final LocalDateTime now = LocalDateTime.of(2026, 10, 28, 0, 0),
      retain = now.plusSeconds(5);
  private final Caller caller = caller("caller", "RUNNING"), source = caller("source", "SUCCEEDED");
  private int invalidations, reads;
  private String fault;

  private Caller caller(String id, String state) {
    return new Caller(
        "tenant",
        id,
        "owner",
        "session_event_read,session_search,session_trace",
        "tools",
        3,
        state,
        now,
        "request");
  }

  private Scope scope(String id, long count) {
    return new Scope(
        1, 1, "owner", "binding", "tools", "raw", id, 3, count, "ACTIVE", now, retain, null, null,
        now, "PRESENT", true);
  }

  private Validated proof() {
    return new Validated(
        scope("caller", 1),
        new Authority(
            "tenant",
            "caller",
            "owner",
            "tools",
            "binding",
            "",
            "",
            List.of(new AuthorityTarget("source", "tools", "binding", 4, "", "", ""))),
        List.of(source),
        Map.of("source", scope("source", 0)));
  }

  private SessionScopeEligibilityServiceImpl service() {
    var records =
        (SessionScopeRecords)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionScopeRecords.class},
                (p, m, a) -> {
                  if (m.getName().equals("invalidateAfterTransaction")) {
                    invalidations++;
                    return null;
                  }
                  if (!m.getName().equals("currentState")) throw new AssertionError(m.getName());
                  reads++;
                  Caller c = (Caller) a[0];
                  boolean source = c.id().equals("source");
                  return new Live(
                      source && "owner".equals(fault)
                          ? new Caller(
                              "tenant",
                              "source",
                              "other",
                              c.capabilities(),
                              "tools",
                              3,
                              c.state(),
                              now,
                              "request")
                          : c,
                      c.id(),
                      "binding",
                      "tools",
                      "owner",
                      1,
                      1,
                      source ? 0 : 1,
                      source && "cut".equals(fault) ? 5 : source ? 4 : 32,
                      "binding",
                      retain,
                      retain,
                      "expired".equals(fault) ? retain : now,
                      !"purged".equals(fault),
                      !"revoked".equals(fault),
                      !source || !"unknown".equals(fault));
                });
    return new SessionScopeEligibilityServiceImpl(records, v -> v);
  }

  @Test
  void currentProjectionUsesDatabaseTimeAndAllowsCallerAppendOnly() {
    long before = System.nanoTime();
    long end = service().live(caller, proof());
    assertTrue(end >= before + 4_900_000_000L);
    assertEquals(2, reads);
    assertEquals(0, invalidations);
  }

  @Test
  void anyLostNodeOrCutInvalidatesEntireScope() {
    for (String reason : List.of("owner", "cut", "expired", "purged", "revoked", "unknown")) {
      fault = reason;
      invalidations = 0;
      assertEquals(
          "SESSION_SCOPE_UNAVAILABLE",
          assertThrows(TaskFailure.class, () -> service().live(caller, proof())).code(),
          reason);
      assertEquals(1, invalidations, reason);
    }
  }
}
