package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import org.junit.jupiter.api.Test;

/** 同时存活结果及入口收紧必须共享同一scratch总量。 */
class SessionQueryResourcesTest {
  private SessionQueryContext query() {
    return new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
  }

  @Test
  void restrictChecksAlreadyRetainedScratchImmediately() {
    var query = query();
    Object held = new Object();
    query.retainScratch(held, 1048576);
    assertEquals(
        "SESSION_HISTORY_LIMIT", assertThrows(TaskFailure.class, query::restrictLineage).code());
    query.releaseScratch(held);
    query.restrictLineage();
  }

  @Test
  void concurrentResultsAndCacheShareOneLimitAndReleaseIndependently() {
    var query = query();
    query.restrictLineage();
    Object cache = new Object();
    query.retainScratch(cache, 900000);
    try (var first = query.result();
        var second = query.result()) {
      first.column("first", "x".repeat(15000));
      assertEquals(
          "SESSION_HISTORY_LIMIT",
          assertThrows(TaskFailure.class, () -> second.column("second", "y".repeat(15000))).code());
    }
    query.releaseScratch(cache);
    try (var next = query.result()) {
      next.column("next", "z".repeat(15000));
    }
    assertEquals(3, query.resources().reads());
  }

  @Test
  void settlementAfterProtectedWriteRollsBackEvenWhenBusinessCatchesIt() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    assertThrows(
        IllegalStateException.class,
        () ->
            fixture.execute(
                () -> {
                  var owner = SessionQueryTransaction.current(fixture.context);
                  owner.protectedWrite();
                  owner.settlement();
                  return null;
                }));
    assertEquals(0, fixture.commits);
    assertEquals(1, fixture.rollbacks);
  }

  @Test
  void caughtProofFailureCannotBeClearedBySettlementOrFinalAccounting() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.execute(
                    () -> {
                      var owner = SessionQueryTransaction.current(fixture.context);
                      var caller =
                          new SessionScopeRecords.Caller(
                              "tenant", "task", "owner", "tools", "tools", 1, "RUNNING", null,
                              "digest");
                      var scope =
                          new SessionScopeRecords.Scope(
                              1, 1, "owner", "binding", "tools", "{}", "digest", 2, 0, "ACTIVE",
                              null, null, null, null, null, "PRESENT", true);
                      var authority =
                          new SessionScopeRecords.Authority(
                              "tenant",
                              "task",
                              "owner",
                              "tools",
                              "binding",
                              "",
                              "",
                              java.util.List.of());
                      owner.locked("tenant", "task");
                      owner.scope(
                          caller,
                          new SessionScopeRecords.Validated(scope, authority, java.util.List.of()),
                          () -> {});
                      assertThrows(IllegalStateException.class, owner::dependencyMutation);
                      assertThrows(IllegalStateException.class, owner::settlement);
                      owner.account("task", true, closing -> {});
                      return null;
                    }));
    assertEquals("Session proof dependency changed after publication", failure.getMessage());
    assertEquals(0, fixture.commits);
    assertEquals(1, fixture.rollbacks);
  }
}
