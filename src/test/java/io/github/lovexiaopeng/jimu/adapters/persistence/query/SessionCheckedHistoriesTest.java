package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs.HistoryRow;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 真实Work所有权/尾部及证明依赖；数据库成功登记另由真实Mapper场景验证。 */
class SessionCheckedHistoriesTest {
  private static final String BINDING = "binding";
  private static final HistoryRow ROW = new HistoryRow(1L, 5L, BINDING, "digest", "PRESENT", 1L);
  private static final LocalDateTime UNTIL = LocalDateTime.of(2026, 11, 1, 0, 0);

  private SessionScopeRecords.Caller caller(String tenant, String id, String actor, long epoch) {
    return new SessionScopeRecords.Caller(
        tenant, id, actor, "caps", "tools", epoch, "RUNNING", null, "request");
  }

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private void publish(SessionQueryWork w, SessionScopeRecords.Caller c, Runnable live) {
    var scope =
        new SessionScopeRecords.Scope(
            1, 1, c.owner(), "binding", "tools", "{}", "digest", 2, 0, "ACTIVE", null, null, null,
            null, null, "PRESENT", true);
    var authority =
        new SessionScopeRecords.Authority(
            c.tenant(), c.id(), c.owner(), "tools", "binding", "", "", List.of());
    w.scope(c, new SessionScopeRecords.Validated(scope, authority, List.of()), live);
    w.fence("admission", () -> {});
  }

  @Test
  void requiresSuccessfulReadableHistoryLockPublishedScopeAndTailBeforeReuse() {
    var q = query();
    var w = new SessionQueryWork(q);
    var c = caller("tenant", "task", "owner", 1);
    try {
      w.checkedHistory(c, 5, BINDING, ROW, UNTIL);
      assertFalse(w.checkedHistory(c, 5, BINDING));
      w.locked(c.tenant(), c.id());
      w.checkedHistory(c, 5, BINDING, ROW, UNTIL);
      assertFalse(w.checkedHistory(c, 5, BINDING));
      int[] calls = {0};
      publish(w, c, () -> calls[0]++);
      assertTrue(w.checkedHistory(c, 5, BINDING));
      assertFalse(w.checkedHistory(c, 4, BINDING));
      assertFalse(w.checkedHistory(c, 5, "wrong"));
      assertFalse(w.checkedHistory(caller("other", "task", "owner", 1), 5, BINDING));
      assertFalse(w.checkedHistory(caller("tenant", "task", "other", 1), 5, BINDING));
      assertFalse(w.checkedHistory(caller("tenant", "task", "owner", 2), 5, BINDING));
      assertFalse(
          w.checkedHistory(
              new SessionScopeRecords.Caller(
                  c.tenant(),
                  c.id(),
                  c.owner(),
                  c.capabilities(),
                  c.tools(),
                  c.epoch(),
                  c.state(),
                  UNTIL,
                  c.requestDigest()),
              5,
              BINDING));
      // 原具名caller progress不会把动态history Map作为证明缓存。
      SessionDependencyWrites.before(
          "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionEventAppendMapper.advance",
          java.util.Map.of("tenant", "tenant", "taskId", "task"),
          w);
      assertTrue(w.checkedHistory(c, 5, BINDING));
      w.finish();
      assertEquals(1, calls[0]);
    } finally {
      w.complete();
    }
    assertThrows(IllegalStateException.class, () -> w.checkedHistory(c, 5, BINDING));
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void stagingExactUnknownAndPublishedWritesInvalidateBeforeSql() {
    for (String mode : List.of("exact", "unknown", "other", "retain", "conflict")) {
      var q = query();
      var w = new SessionQueryWork(q);
      var c = caller("tenant", "task", "owner", 1);
      w.locked("tenant", "task");
      try {
        w.checkedHistory(c, 5, BINDING, ROW, UNTIL);
        if (mode.equals("retain"))
          SessionDependencyWrites.before(
              "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.retainAuthorityHistory",
              java.util.Map.of("tenant", "tenant", "id", "task"),
              w);
        else if (mode.equals("conflict"))
          SessionDependencyWrites.before(
              "unknown",
              java.util.Map.of("tenant", "tenant", "id", "task", "taskId", "different"),
              w);
        else
          w.dependencyMutation(
              mode.equals("unknown")
                  ? null
                  : Set.of(
                      new SessionQueryWork.Dependency(
                          "tenant", mode.equals("other") ? "other" : "task")));
        publish(w, c, () -> {});
        assertEquals(mode.equals("other"), w.checkedHistory(c, 5, BINDING), mode);
        if (!mode.equals("other")) {
          // 只有重新执行并成功的证明才能登记；不从写参数恢复。
          w.checkedHistory(c, 5, BINDING, ROW, UNTIL);
          assertTrue(w.checkedHistory(c, 5, BINDING));
        }
        assertThrows(
            IllegalStateException.class,
            () -> w.dependencyMutation(Set.of(new SessionQueryWork.Dependency("tenant", "task"))));
        assertThrows(IllegalStateException.class, () -> w.checkedHistory(c, 5, BINDING));
        assertThrows(IllegalStateException.class, w::finish);
      } finally {
        w.complete();
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void noWitnessCrossesNewTransactionAndTailRevocationStillRollsBack() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var c = caller("tenant", "task", "owner", 1);
    int[] calls = {0};
    assertThrows(
        IllegalStateException.class,
        () ->
            fixture.transaction.required(
                q,
                () -> {
                  var w = SessionQueryTransaction.current(q);
                  w.locked("tenant", "task");
                  w.checkedHistory(c, 5, BINDING, ROW, UNTIL);
                  publish(
                      w,
                      c,
                      () -> {
                        calls[0]++;
                        throw new IllegalStateException("current grant revoked");
                      });
                  assertTrue(w.checkedHistory(c, 5, BINDING));
                  return null;
                }));
    assertEquals(1, calls[0]);
    assertEquals(0, fixture.commits);
    assertEquals(1, fixture.rollbacks);
    var independent = query();
    fixture.transaction.required(
        independent,
        () -> {
          var w = SessionQueryTransaction.current(independent);
          w.locked("tenant", "task");
          publish(w, c, () -> {});
          assertFalse(w.checkedHistory(c, 5, BINDING));
          return null;
        });
    assertEquals(1, fixture.commits);
    assertEquals(65536, q.scratchLive());
    assertEquals(65536, independent.scratchLive());
  }
}
