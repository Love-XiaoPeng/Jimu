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
  @Test
  void proofAndBudgetFailuresKeepOneThrowableInEitherOrderAndStopLaterWork() {
    for (boolean proofFirst : java.util.List.of(true, false)) {
      var fixture = new SessionQueryTransactionTest.Fixture();
      var q = query();
      q.restrictLineage();
      RuntimeException[] first = {null};
      var observed =
          assertThrows(
              RuntimeException.class,
              () ->
                  fixture.transaction.required(
                      q,
                      () -> {
                        var owner = SessionQueryTransaction.current(q);
                        var corrupt = new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
                        if (proofFirst) {
                          owner.poisonProof(corrupt);
                          first[0] = corrupt;
                        } else first[0] = assertThrows(TaskFailure.class, () -> q.operation(65537));
                        long operations = q.resources().operations();
                        owner.poisonProof(corrupt);
                        org.junit.jupiter.api.Assertions.assertSame(
                            first[0],
                            assertThrows(RuntimeException.class, () -> q.operation(65537)));
                        org.junit.jupiter.api.Assertions.assertSame(
                            first[0], assertThrows(RuntimeException.class, q::checkHealthy));
                        org.junit.jupiter.api.Assertions.assertSame(
                            first[0],
                            assertThrows(
                                RuntimeException.class,
                                () ->
                                    q.primitive(
                                        "later",
                                        1,
                                        () -> {
                                          throw new AssertionError();
                                        })));
                        assertEquals(operations, q.resources().operations());
                        assertEquals(java.util.Map.of(), q.requestedWork());
                        return null;
                      }));
      org.junit.jupiter.api.Assertions.assertSame(first[0], observed);
      assertEquals(0, fixture.commits);
      assertEquals(1, fixture.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

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
    assertThrows(TaskFailure.class, query::restrictLineage);
    assertThrows(TaskFailure.class, query::checkHealthy);
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
    assertThrows(TaskFailure.class, query::result);
    var independent = query();
    independent.restrictLineage();
    try (var next = independent.result()) {
      next.column("next", "z".repeat(15000));
    }
    assertEquals(2, query.resources().reads());
    assertEquals(1, independent.resources().reads());
  }

  @Test
  void caughtBudgetAndDeadlineFailuresCannotCommitOrBeClearedByCleanup() {
    for (String phase : java.util.List.of("business", "account", "live", "fence", "beforeCommit")) {
      for (String resource :
          java.util.List.of("operations", "scratch", "deadline", "reads", "bytes")) {
        var fixture = new SessionQueryTransactionTest.Fixture();
        var q = query();
        q.restrictLineage();
        TaskFailure[] first = {null};
        Runnable refusal =
            () -> {
              Object pressure = new Object();
              try {
                switch (resource) {
                  case "operations" -> q.operation(65537);
                  case "scratch" -> q.retainScratch(pressure, 1048576);
                  case "deadline" -> {
                    q.restrictDeadline(System.nanoTime() - 1);
                    q.remainingMillis();
                  }
                  case "reads" -> {
                    for (int i = 0; i < 257; i++) q.result().close();
                  }
                  case "bytes" -> {
                    try (var result = q.result()) {
                      for (int i = 0; i < 1000; i++) result.column("bytes", new byte[4096]);
                    }
                  }
                  default -> throw new AssertionError();
                }
                throw new AssertionError("Expected refusal");
              } catch (TaskFailure failure) {
                first[0] = failure;
              } finally {
                q.releaseScratch(pressure);
              }
            };
        var failure =
            assertThrows(
                TaskFailure.class,
                () ->
                    fixture.transaction.required(
                        q,
                        () -> {
                          var owner = SessionQueryTransaction.current(q);
                          switch (phase) {
                            case "business" -> refusal.run();
                            case "account" -> owner.account("task", true, closing -> refusal.run());
                            case "live" -> owner.expiry(refusal);
                            case "fence" -> owner.fence("fence", refusal);
                            case "beforeCommit" ->
                                org.springframework.transaction.support
                                    .TransactionSynchronizationManager.registerSynchronization(
                                    new org.springframework.transaction.support
                                        .TransactionSynchronization() {
                                      public int getOrder() {
                                        return -1;
                                      }

                                      public void beforeCommit(boolean readOnly) {
                                        refusal.run();
                                      }
                                    });
                            default -> throw new AssertionError();
                          }
                          return "caught";
                        }));
        org.junit.jupiter.api.Assertions.assertSame(first[0], failure, phase + "/" + resource);
        assertEquals(0, fixture.commits, phase + "/" + resource);
        assertEquals(1, fixture.rollbacks, phase + "/" + resource);
        assertEquals(65536, q.scratchLive());
        org.junit.jupiter.api.Assertions.assertSame(
            failure, assertThrows(TaskFailure.class, q::checkHealthy));
      }
    }
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

  @Test
  void resultCloseFailureStillReleasesItsCursorRetention() throws Exception {
    var query = query();
    query.restrictLineage();
    var close = new java.sql.SQLException("private-close-marker", "08003", 9001);
    var metadata =
        (java.sql.ResultSetMetaData)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.sql.ResultSetMetaData.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getColumnCount" -> 1;
                      case "getColumnLabel" -> "value";
                      default -> null;
                    });
    var delegate =
        (java.sql.ResultSet)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.sql.ResultSet.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getMetaData" -> metadata;
                      case "next" -> true;
                      case "getObject" -> "retained";
                      case "close" -> throw close;
                      default -> null;
                    });
    var result = QueryResults.meter(delegate, query);
    org.junit.jupiter.api.Assertions.assertTrue(result.next());
    org.junit.jupiter.api.Assertions.assertTrue(query.scratchLive() > 65536);
    org.junit.jupiter.api.Assertions.assertSame(
        close, assertThrows(java.sql.SQLException.class, result::close));
    assertEquals(65536, query.scratchLive());
  }
}
