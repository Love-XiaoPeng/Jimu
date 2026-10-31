package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 身份发现只用真实事务节点；逐次费用、首错与清理不因旧batch或新线程绕过。 */
class SessionMetadataIdentityCostTest {
  static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  static SessionQueryWork lookup(SessionQueryContext payer) {
    return SessionQueryTransaction.metadataOwner(payer);
  }

  static SessionHistorySizedMetadataBatch closed(SessionQueryContext q) throws Exception {
    var owner = new SessionQueryWork(q);
    var ctor =
        SessionHistorySizedMetadataBatch.class.getDeclaredConstructor(
            SessionQueryContext.class, SessionQueryWork.class);
    ctor.setAccessible(true);
    var batch = ctor.newInstance(q, owner);
    batch.close();
    owner.complete();
    return batch;
  }

  static int threadLocalCount() throws Exception {
    var f = Thread.class.getDeclaredField("threadLocals");
    f.setAccessible(true);
    Object map = f.get(Thread.currentThread());
    if (map == null) return 0;
    var size = map.getClass().getDeclaredField("size");
    size.setAccessible(true);
    return size.getInt(map);
  }

  @Test
  void repeatedClosedOutsideWorkPaysEveryAttemptAndKeepsFirst() throws Exception {
    var q = query();
    var b = closed(q);
    RuntimeException first = null;
    for (int i = 0; i < 4; i++) {
      long before = q.resources().operations();
      var failure = assertThrows(RuntimeException.class, b::iterator);
      if (first == null) first = failure;
      else assertSame(first, failure);
      long cost = q.resources().operations() - before;
      System.out.println("REJECT_NO_WORK attempt=" + i + " cost=" + cost);
      assertEquals(12, cost, "every closed attempt pays before owner discovery");
    }
    assertSame(first, q.recordedFailure());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void freshThreadNoWorkDoesNotInitializeSpringThreadLocals() throws Exception {
    Class.forName(TransactionSynchronizationManager.class.getName());
    var q = query();
    var b = closed(q);
    var failure = new AtomicReference<Throwable>();
    Thread thread =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    int before = threadLocalCount();
                    assertThrows(RuntimeException.class, b::iterator);
                    int after = threadLocalCount();
                    System.out.println("FRESH_THREAD locals=" + before + "->" + after);
                    assertEquals(
                        before, after, "no owner discovery may initialize a Spring ThreadLocal");
                  } catch (Throwable e) {
                    failure.set(e);
                  }
                });
    thread.start();
    thread.join(1500);
    assertTrue(!thread.isAlive(), "test thread joined");
    if (failure.get() != null) throw new AssertionError(failure.get());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void currentHolderReplacementStillPoisonsCaughtTransaction() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      var current = SessionQueryTransaction.current(q);
                      Object old = TransactionSynchronizationManager.unbindResource(f.source);
                      try {
                        TransactionSynchronizationManager.bindResource(
                            f.source,
                            new org.springframework.jdbc.datasource.ConnectionHolder(f.connection));
                        assertSame(current, lookup(q));
                        assertNotNull(q.recordedFailure());
                      } finally {
                        TransactionSynchronizationManager.unbindResource(f.source);
                        TransactionSynchronizationManager.bindResource(f.source, old);
                      }
                      return "caught";
                    }));
    assertSame(q.recordedFailure(), thrown);
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
  }

  @Test
  void nestedSameAndDifferentQueryRestoresOnlyActualOwner() {
    for (boolean same : new boolean[] {false, true}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var outer = query();
      var inner = same ? outer : query();
      f.transaction.required(
          outer,
          () -> {
            var actual = lookup(outer);
            Object token = actual.token();
            long live = outer.scratchLive();
            f.transaction.requiresNew(
                inner,
                () -> {
                  var next = lookup(inner);
                  assertNotSame(actual, next);
                  assertNotSame(token, next.token());
                  return null;
                });
            assertSame(actual, lookup(outer));
            assertSame(token, actual.token());
            assertEquals(live, outer.scratchLive());
            return null;
          });
      assertEquals(2, f.commits);
      assertNull(lookup(outer));
      assertEquals(65536, outer.scratchLive());
      assertEquals(65536, inner.scratchLive());
    }
  }

  @Test
  void preexistingFirstSlotSurvivesRepeatedForeignAndEndedAttempts() throws Exception {
    var old = query();
    var b = closed(old);
    var first = new IllegalArgumentException("first old");
    old.recordFailure(first);
    var f = new SessionQueryTransactionTest.Fixture();
    var next = query();
    var outer =
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    next,
                    () -> {
                      var existing = new IllegalStateException("first current");
                      next.recordFailure(existing);
                      for (int i = 0; i < 3; i++) assertThrows(RuntimeException.class, b::iterator);
                      assertSame(existing, next.recordedFailure());
                      assertSame(first, old.recordedFailure());
                      return "caught";
                    }));
    assertSame(next.recordedFailure(), outer);
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
  }

  @Test
  void extraSpringResourcesHaveMeasuredCostWithoutAddedPrivateKey() {
    for (int extra : new int[] {0, 8, 64}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      var keys = new Object[extra];
      for (int i = 0; i < extra; i++) {
        keys[i] = new Object();
        TransactionSynchronizationManager.bindResource(keys[i], new Object());
      }
      try {
        f.transaction.required(
            q,
            () -> {
              int count = TransactionSynchronizationManager.getResourceMap().size();
              long ops = q.resources().operations();
              var owner = lookup(q);
              long cost = q.resources().operations() - ops;
              assertSame(SessionQueryTransaction.current(q), owner);
              System.out.println("RESOURCE_CARDINALITY entries=" + count + " lookup=" + cost);
              assertEquals(extra + 1, count, "roster must add no Spring key");
              assertEquals(61 + 4L * extra, cost);
              return null;
            });
      } finally {
        for (var key : keys) TransactionSynchronizationManager.unbindResource(key);
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void refusalAtOwnerLookupStillLatchesAndRollsBackAfterCatch() {
    for (int remainder : new int[] {14, 15, 16}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    q.operation(65536 - q.resources().operations() - remainder);
                    try {
                      lookup(q);
                    } catch (RuntimeException ignored) {
                    }
                    return "caught";
                  }));
      assertEquals(0, f.commits);
      assertEquals(1, f.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void exactOwnedLookupCostBoundary() {
    for (int remainder : new int[] {60, 61, 62}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      java.util.function.Supplier<String> work =
          () -> {
            q.operation(65536 - q.resources().operations() - remainder);
            lookup(q);
            return "done";
          };
      if (remainder < 61) {
        assertThrows(RuntimeException.class, () -> f.transaction.required(q, work));
        assertEquals(0, f.commits);
        assertEquals(1, f.rollbacks);
      } else {
        assertEquals("done", f.transaction.required(q, work));
        assertEquals(1, f.commits);
        assertEquals(0, f.rollbacks);
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void noWorkLookupNMinusOneNAndNPlusOne() throws Exception {
    for (int remainder : new int[] {11, 12, 13}) {
      var q = query();
      var b = closed(q);
      q.operation(65536 - q.resources().operations() - remainder);
      assertThrows(RuntimeException.class, b::iterator);
      if (remainder < 12) {
        assertNotNull(q.operationRejection());
        assertEquals(12, q.operationRejection().requested());
      } else assertNull(q.operationRejection());
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void foreignFailedReceiverChargesItsActualCurrentOwnerAsWell() throws Exception {
    var old = query();
    var b = closed(old);
    old.recordFailure(new IllegalStateException("old first"));
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                q,
                () -> {
                  for (int i = 0; i < 3; i++) {
                    long before = q.resources().operations();
                    assertThrows(RuntimeException.class, b::iterator);
                    assertEquals(8, q.resources().operations() - before);
                  }
                  return "caught";
                }));
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
  }

  @Test
  void setupAndUnknownReleaseIdentityAndScratch() {
    for (String kind : new String[] {"setup", "commit", "rollback"}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      if (kind.equals("setup")) f.setup = new java.sql.SQLException("setup", "HY088");
      if (kind.equals("commit")) f.commit = new java.sql.SQLException("ACK", "08006");
      if (kind.equals("rollback")) f.rollback = new java.sql.SQLException("ACK", "08006");
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    assertNotNull(lookup(q));
                    if (kind.equals("rollback")) throw new IllegalStateException("business");
                    return null;
                  }));
      assertEquals(65536, q.scratchLive());
      if (q.recordedFailure() == null) assertNull(lookup(q));
    }
  }

  @Test
  void otherThreadOwnerIsNeverBorrowedAndPopulationIsBilled() throws Exception {
    var ready = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var workerFailure = new AtomicReference<Throwable>();
    Thread worker =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  var f = new SessionQueryTransactionTest.Fixture();
                  var q = query();
                  try {
                    f.transaction.required(
                        q,
                        () -> {
                          ready.countDown();
                          try {
                            if (!release.await(1, java.util.concurrent.TimeUnit.SECONDS))
                              throw new AssertionError("coordination timeout");
                          } catch (InterruptedException e) {
                            throw new AssertionError(e);
                          }
                          return null;
                        });
                  } catch (Throwable e) {
                    workerFailure.set(e);
                    ready.countDown();
                  }
                });
    worker.start();
    try {
      assertTrue(ready.await(1, java.util.concurrent.TimeUnit.SECONDS));
      var orphan = query();
      long before = orphan.resources().operations();
      assertNull(lookup(orphan));
      assertEquals(15, orphan.resources().operations() - before);
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      f.transaction.required(
          q,
          () -> {
            long start = q.resources().operations();
            assertSame(SessionQueryTransaction.current(q), lookup(q));
            assertEquals(64, q.resources().operations() - start);
            return null;
          });
    } finally {
      release.countDown();
      worker.join(1500);
      assertTrue(!worker.isAlive(), "worker joined");
    }
    if (workerFailure.get() != null) throw new AssertionError(workerFailure.get());
    var count = SessionQueryTransaction.class.getDeclaredField("metadataCount");
    count.setAccessible(true);
    var head = SessionQueryTransaction.class.getDeclaredField("metadataHead");
    head.setAccessible(true);
    assertEquals(0, count.getInt(null));
    assertNull(head.get(null));
  }

  @Test
  void bootstrapRefusalAndInstallBoundaryAlwaysRefundsUnregisteredOwner() throws Exception {
    for (int remaining : new int[] {11, 12, 23, 24, 25, 60, 90, 127, 128, 129}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      q.operation(65536 - q.resources().operations() - remaining);
      if (remaining < 128) {
        assertThrows(RuntimeException.class, () -> f.transaction.required(q, () -> "done"));
        assertEquals(0, f.commits);
        assertEquals(1, f.rollbacks);
      } else {
        assertEquals("done", f.transaction.required(q, () -> "done"));
        assertEquals(1, f.commits);
        assertEquals(0, f.rollbacks);
      }
      assertEquals(65536, q.scratchLive(), "remaining=" + remaining);
      var count = SessionQueryTransaction.class.getDeclaredField("metadataCount");
      count.setAccessible(true);
      assertEquals(0, count.getInt(null));
    }
  }

  @Test
  void bootstrapScratchBoundaryAlwaysReleasesUnregisteredWork() {
    for (int delta : new int[] {-1, 0, 1}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      var filler = new Object();
      q.retainScratch(filler, 1048576 - 65536 - 1280 - 4160 - delta);
      long baseline = q.scratchLive();
      try {
        if (delta < 0)
          assertThrows(RuntimeException.class, () -> f.transaction.required(q, () -> "done"));
        else assertEquals("done", f.transaction.required(q, () -> "done"));
        assertEquals(baseline, q.scratchLive());
      } finally {
        q.releaseScratch(filler);
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void resourceViewScratchBoundaryClearsAliasBeforeRefund() {
    for (int free : new int[] {63, 64, 65}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      java.util.function.Supplier<String> work =
          () -> {
            var filler = new Object();
            long baseline = q.scratchLive();
            q.retainScratch(filler, 1048576 - baseline - free);
            try {
              lookup(q);
              return "done";
            } finally {
              q.releaseScratch(filler);
            }
          };
      if (free < 64) assertThrows(RuntimeException.class, () -> f.transaction.required(q, work));
      else assertEquals("done", f.transaction.required(q, work));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void contendedMonitorUsesOriginalDeadlineAndFinallyJoins() throws Exception {
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var elapsed = new java.util.concurrent.atomic.AtomicLong();
    Thread worker =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    synchronized (SessionQueryTransaction.class) {
                      started.countDown();
                      release.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                  } catch (Throwable caught) {
                    failure.set(caught);
                    started.countDown();
                  }
                });
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    try {
      f.transaction.required(
          q,
          () -> {
            var owner = SessionQueryTransaction.current(q);
            worker.start();
            try {
              assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
              throw new AssertionError(interrupted);
            }
            long before = System.nanoTime();
            assertSame(owner, lookup(q));
            elapsed.set(System.nanoTime() - before);
            return null;
          });
    } finally {
      release.countDown();
      worker.join(1500);
    }
    assertTrue(!worker.isAlive(), "contended worker joined");
    if (failure.get() != null) throw new AssertionError(failure.get());
    assertEquals(65536, q.scratchLive());
    System.out.println("IDENTITY_MONITOR lookupNanos=" + elapsed.get());
  }

  @Test
  void everyPhysicalProxyEdgeIsPaidBeforeUnwrap() {
    for (int depth : new int[] {0, 1, 4, 16}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      f.transaction.required(
          q,
          () -> {
            var holder =
                (org.springframework.jdbc.datasource.ConnectionHolder)
                    TransactionSynchronizationManager.getResource(f.source);
            java.sql.Connection wrapped = f.connection;
            for (int i = 0; i < depth; i++) {
              var target = wrapped;
              wrapped =
                  (java.sql.Connection)
                      java.lang.reflect.Proxy.newProxyInstance(
                          getClass().getClassLoader(),
                          new Class<?>[] {
                            org.springframework.jdbc.datasource.ConnectionProxy.class
                          },
                          (proxy, method, args) -> {
                            assertEquals("getTargetConnection", method.getName());
                            return target;
                          });
            }
            replaceHolderConnection(holder, wrapped);
            try {
              long before = q.resources().operations();
              assertSame(SessionQueryTransaction.current(q), lookup(q));
              assertEquals(61 + 4L * depth, q.resources().operations() - before);
            } finally {
              replaceHolderConnection(holder, f.connection);
            }
            return null;
          });
      assertEquals(65536, q.scratchLive());
    }
  }

  private static void replaceHolderConnection(
      org.springframework.jdbc.datasource.ConnectionHolder holder, java.sql.Connection connection) {
    try {
      var setter =
          org.springframework.jdbc.datasource.ConnectionHolder.class.getDeclaredMethod(
              "setConnection", java.sql.Connection.class);
      setter.setAccessible(true);
      setter.invoke(holder, connection);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  void lockWaitCannotRestartExpiredAbsoluteDeadline() throws Exception {
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    Thread blocker =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  synchronized (SessionQueryTransaction.class) {
                    locked.countDown();
                    try {
                      release.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                      failure.set(interrupted);
                    }
                  }
                });
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    blocker.start();
                    try {
                      assertTrue(locked.await(1, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                      throw new AssertionError(interrupted);
                    }
                    q.restrictDeadline(System.nanoTime() + 10_000_000L);
                    // metadataOwner only identifies/latches; the public batch.open boundary
                    // immediately checks the original query deadline before exposing rows.
                    lookup(q);
                    assertThrows(RuntimeException.class, q::checkHealthy);
                    return "caught";
                  }));
    } finally {
      release.countDown();
      blocker.join(1500);
    }
    assertTrue(!blocker.isAlive(), "deadline blocker joined");
    if (failure.get() != null) throw new AssertionError(failure.get());
    assertEquals(
        "SESSION_QUERY_TIMEOUT",
        ((io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure) q.recordedFailure()).code());
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
    assertEquals(65536, q.scratchLive());
  }
}
