package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 只验证登记键交接差异：当前锁/来源集合、Work边界及引用释放，不替代真实SQL。 */
class SessionProjectionLockTest {
  private static SessionQueryTransactionTest.Fixture fixture() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    fixture.context.restrictLineage();
    return fixture;
  }

  private static SessionScopeRecords.Caller caller(String tenant, String id) {
    return new SessionScopeRecords.Caller(
        tenant, id, "owner", "caps", "tools", 1, "SUCCEEDED", null, "d");
  }

  private static SessionScopeRecords.SourceFact source(String tenant, String id) {
    return new SessionScopeRecords.SourceFact(
        caller(tenant, id),
        new SessionScopeRecords.History(1, "b", null),
        new SessionScopeRecords.Scope(
            1, 1, "owner", "b", "tools", "{}", "d", 2, 0, "ACTIVE", null, null, null, null, null,
            "PRESENT", true),
        new SessionScopeRecords.Authority(tenant, id, "owner", "tools", "b", "", "", List.of()),
        List.of());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> locks(SessionQueryWork work) {
    try {
      var field = SessionQueryWork.class.getDeclaredField("locks");
      field.setAccessible(true);
      return (Map<String, ?>) field.get(work);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  void bindingCannotInventCallerTenantOrTaskLocks() {
    for (String wrong : List.of("tenant", "task")) {
      var f = fixture();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  f.context,
                  () -> {
                    var w = SessionQueryTransaction.current(f.context);
                    w.locked("t", "c");
                    w.projectionLock(
                        f.context,
                        wrong.equals("tenant") ? "foreign" : "t",
                        wrong.equals("task") ? "foreign" : "c");
                    return null;
                  }));
      assertEquals(1, f.rollbacks);
      assertEquals(65536, f.context.scratchLive());
    }
  }

  @Test
  void everyReadRechecksCallerAndEveryCurrentSource() {
    for (String missing : List.of("c", "s1", "s2")) {
      var f = fixture();
      RuntimeException[] first = {null};
      var observed =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.transaction.required(
                      f.context,
                      () -> {
                        var w = SessionQueryTransaction.current(f.context);
                        w.admission();
                        for (String id : List.of("c", "s1", "s2")) w.locked("t", id);
                        var binding = w.projectionLock(f.context, "t", "c");
                        w.stageSource(caller("t", "c"), source("t", "s1"));
                        w.requireProjectionLocks(f.context, binding);
                        w.stageSource(caller("t", "c"), source("t", "s2"));
                        w.requireProjectionLocks(f.context, binding);
                        locks(w).remove("t:" + missing);
                        first[0] =
                            assertThrows(
                                RuntimeException.class,
                                () -> w.requireProjectionLocks(f.context, binding));
                        return null;
                      }));
      assertSame(first[0], observed);
      assertEquals(65536, f.context.scratchLive());
      assertEquals(1, f.rollbacks);
    }
  }

  @Test
  void replacementUsesItsOwnRegisteredKeyAndInvalidationDropsItsAlias() {
    var f = fixture();
    f.transaction.required(
        f.context,
        () -> {
          var w = SessionQueryTransaction.current(f.context);
          w.admission();
          w.locked("t", "c");
          w.locked("t", "s");
          w.locked("other", "s");
          var binding = w.projectionLock(f.context, "t", "c");
          w.stageSource(caller("t", "c"), source("t", "s"));
          w.stageSource(caller("t", "c"), source("other", "s"));
          locks(w).remove("t:s");
          w.requireProjectionLocks(f.context, binding);
          w.dependencyMutation(Set.of(new SessionQueryWork.Dependency("other", "s")));
          locks(w).remove("other:s");
          w.requireProjectionLocks(f.context, binding);
          return null;
        });
    assertEquals(1, f.commits);
    assertEquals(65536, f.context.scratchLive());
  }

  @Test
  void newOrReplacementSourceCannotBorrowAnotherRegisteredTaskKey() {
    for (boolean replacement : List.of(false, true)) {
      var f = fixture();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  f.context,
                  () -> {
                    var w = SessionQueryTransaction.current(f.context);
                    w.admission();
                    w.locked("t", "c");
                    w.locked("t", "s");
                    if (replacement) w.stageSource(caller("t", "c"), source("t", "s"));
                    w.stageSource(
                        caller("t", "c"),
                        source(replacement ? "foreign" : "t", replacement ? "s" : "missing"));
                    return null;
                  }));
      assertEquals(65536, f.context.scratchLive());
    }
  }

  @Test
  void foreignQueryWorkAndCompletedWorkCannotConsumeBinding() {
    for (String mode : List.of("query", "work")) {
      var f = fixture();
      var otherQuery = new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
      SessionQueryWork[] held = {null};
      SessionQueryWork.ProjectionLock[] binding = {null};
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  f.context,
                  () -> {
                    held[0] = SessionQueryTransaction.current(f.context);
                    held[0].locked("t", "c");
                    binding[0] = held[0].projectionLock(f.context, "t", "c");
                    if (mode.equals("query"))
                      held[0].requireProjectionLocks(otherQuery, binding[0]);
                    else
                      f.transaction.requiresNew(
                          otherQuery,
                          () -> {
                            var other = SessionQueryTransaction.current(otherQuery);
                            other.requireProjectionLocks(otherQuery, binding[0]);
                            return null;
                          });
                    return null;
                  }));
      assertThrows(
          IllegalStateException.class, () -> held[0].requireProjectionLocks(f.context, binding[0]));
      assertEquals(65536, f.context.scratchLive());
    }
  }
}
