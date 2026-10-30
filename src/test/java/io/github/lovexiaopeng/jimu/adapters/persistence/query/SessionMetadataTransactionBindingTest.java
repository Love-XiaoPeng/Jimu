package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SessionMetadataTransactionBindingTest {
  private SessionQueryContext query() {
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    query.restrictLineage();
    return query;
  }

  @Test
  void borrowedQueryKeepsOneBindingWithoutSynchronizationSnapshots() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    f.transaction.required(
        q,
        () -> {
          var actual = SessionQueryTransaction.current(q);
          int[] orderReads = {0};
          for (int i = 0; i < 5; i++)
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  public int getOrder() {
                    orderReads[0]++;
                    return 0;
                  }
                });
          int before = orderReads[0];
          long operations = q.resources().operations();
          assertSame(actual, SessionQueryTransaction.metadataOwner(q));
          assertEquals(61, q.resources().operations() - operations);
          assertEquals(before, orderReads[0], "lookup must not sort a synchronization snapshot");
          f.transaction.required(
              q,
              () -> {
                assertSame(actual, SessionQueryTransaction.metadataOwner(q));
                return null;
              });
          return null;
        });
    assertNull(SessionQueryTransaction.metadataOwner(q));
    assertEquals(1, f.commits);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void requiresNewSameQueryAndDifferentQuerySuspendAndRestoreExactOwner() {
    for (boolean sameQuery : new boolean[] {false, true}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var outerQuery = query();
      var innerQuery = sameQuery ? outerQuery : query();
      f.transaction.required(
          outerQuery,
          () -> {
            var outer = SessionQueryTransaction.metadataOwner(outerQuery);
            var token = outer.token();
            long live = outerQuery.scratchLive();
            f.transaction.requiresNew(
                innerQuery,
                () -> {
                  var inner = SessionQueryTransaction.metadataOwner(innerQuery);
                  assertNotSame(outer, inner);
                  assertNotSame(token, inner.token());
                  assertSame(inner, SessionQueryTransaction.current(innerQuery));
                  if (sameQuery) assertTrue(outerQuery.scratchLive() > live);
                  return null;
                });
            assertSame(outer, SessionQueryTransaction.metadataOwner(outerQuery));
            assertSame(token, outer.token());
            assertEquals(live, outerQuery.scratchLive());
            return null;
          });
      assertNull(SessionQueryTransaction.metadataOwner(outerQuery));
      assertEquals(2, f.commits);
      assertEquals(65536, outerQuery.scratchLive());
      assertEquals(65536, innerQuery.scratchLive());
    }
  }

  @Test
  void failedInnerBeginRestoresOuterAndCannotInstallAnAncestorInInnerWork() {
    var f = new SessionQueryTransactionTest.Fixture();
    var outerQuery = query();
    f.transaction.required(
        outerQuery,
        () -> {
          var outer = SessionQueryTransaction.metadataOwner(outerQuery);
          f.setup = new SQLException("inner setup", "HY088");
          var innerQuery = query();
          assertThrows(
              RuntimeException.class,
              () ->
                  f.transaction.requiresNew(
                      innerQuery,
                      () -> {
                        throw new AssertionError("business must not run after setup failure");
                      }));
          f.setup = null;
          assertSame(outer, SessionQueryTransaction.metadataOwner(outerQuery));
          assertEquals(65536, innerQuery.scratchLive());
          return null;
        });
    assertNull(SessionQueryTransaction.metadataOwner(outerQuery));
    assertEquals(1, f.commits);
    assertEquals(1, f.rollbacks);
  }

  @Test
  void rollbackUnknownAndSetupFailureAlwaysRemoveBindingExactlyOnce() {
    for (String kind : new String[] {"setup", "business", "commit", "rollback"}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      if (kind.equals("setup")) f.setup = new SQLException("setup", "HY088");
      if (kind.equals("commit")) f.commit = new SQLException("commit ACK", "08006");
      if (kind.equals("rollback")) f.rollback = new SQLException("rollback ACK", "08006");
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    assertSame(
                        SessionQueryTransaction.current(q),
                        SessionQueryTransaction.metadataOwner(q));
                    if (kind.equals("business") || kind.equals("rollback"))
                      throw new IllegalStateException("business");
                    return null;
                  }));
      assertNull(SessionQueryTransaction.metadataOwner(q));
      assertEquals(65536, q.scratchLive());
      var next = query();
      f.setup = null;
      f.commit = null;
      f.rollback = null;
      f.transaction.required(
          next,
          () -> {
            assertSame(
                SessionQueryTransaction.current(next), SessionQueryTransaction.metadataOwner(next));
            return null;
          });
      assertNull(SessionQueryTransaction.metadataOwner(q));
      assertEquals(65536, next.scratchLive());
    }
  }

  @Test
  void changedDatasourceHolderLatchesFailureEvenWhenCallerCatches() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    RuntimeException[] first = {null};
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      var actual = SessionQueryTransaction.current(q);
                      Object original = TransactionSynchronizationManager.unbindResource(f.source);
                      try {
                        TransactionSynchronizationManager.bindResource(
                            f.source,
                            new org.springframework.jdbc.datasource.ConnectionHolder(f.connection));
                        assertSame(actual, SessionQueryTransaction.metadataOwner(q));
                        first[0] = q.recordedFailure();
                        assertTrue(first[0] != null);
                      } finally {
                        TransactionSynchronizationManager.unbindResource(f.source);
                        TransactionSynchronizationManager.bindResource(f.source, original);
                      }
                      return "caught";
                    }));
    assertSame(first[0], failure);
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
    assertSame(
        first[0],
        assertThrows(RuntimeException.class, () -> SessionQueryTransaction.metadataOwner(q)));
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void rejectedAccessCountsPastLimitWithoutReplacingOrReopeningFirstFailure() {
    var q = query();
    var first = new IllegalStateException("first");
    q.recordFailure(first);
    long before = q.resources().operations();
    for (int attempt = 0; attempt < 1100; attempt++) q.rejectedOperation(64);
    assertEquals(before + 70400, q.resources().operations());
    assertSame(first, q.recordedFailure());
    assertSame(first, assertThrows(RuntimeException.class, q::checkHealthy));
    assertSame(first, assertThrows(RuntimeException.class, () -> q.operation(1)));
  }

  @Test
  void foreignQueryCannotBorrowBindingOrReachBusiness() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                q,
                () -> {
                  var actual = SessionQueryTransaction.metadataOwner(q);
                  assertThrows(
                      IllegalStateException.class,
                      () ->
                          f.transaction.required(
                              query(),
                              () -> {
                                throw new AssertionError("foreign query must not run");
                              }));
                  assertSame(actual, SessionQueryTransaction.metadataOwner(q));
                  throw new IllegalStateException("rollback test");
                }));
    assertNull(SessionQueryTransaction.metadataOwner(q));
    assertEquals(0, f.commits);
  }
}
