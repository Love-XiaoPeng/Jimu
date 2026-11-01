package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 恢复仅用于清理；逐次费用、拒绝首槽及Error均不能变成提交许可。 */
class SessionQueryResumeAccountingTest {
  @Test
  void resumeChargesCurrentOtherThreadPopulationAndJoinsTheHelper() throws Exception {
    var ready = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();
    var helperQuery = SessionEventHistoryLengthTest.query();
    var helper =
        new Thread(
            () -> {
              try {
                var other = new SessionHistoryLengthTest.Fixture();
                other.work(
                    helperQuery,
                    () -> {
                      ready.countDown();
                      try {
                        assertTrue(done.await(10, TimeUnit.SECONDS));
                      } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                      }
                      return null;
                    });
              } catch (Throwable failure) {
                error.set(failure);
                ready.countDown();
              }
            },
            "test-only-resume-population");
    helper.start();
    try {
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      assertNull(error.get());
      var f = new SessionHistoryLengthTest.Fixture();
      var q = SessionEventHistoryLengthTest.query();
      f.work(
          q,
          () -> {
            long[] start = {0};
            f.transaction.requiresNew(
                q,
                () -> {
                  start[0] = q.resources().operations();
                  return null;
                });
            assertEquals(47, q.resources().operations() - start[0]);
            return null;
          });
      assertEquals(65536, q.scratchLive());
    } finally {
      done.countDown();
      helper.join(10000);
    }
    assertFalse(helper.isAlive());
    if (error.get() != null) throw new AssertionError(error.get());
    assertEquals(65536, helperQuery.scratchLive());
    System.out.println("RESUME_POPULATION C=1 cost=47 helperJoined=true");
  }

  @Test
  void healthyRepeatedResumePaysFull44AndRestoresOnlyItsOwnWork() throws Exception {
    var f = new SessionHistoryLengthTest.Fixture();
    var q = SessionEventHistoryLengthTest.query();
    f.work(
        q,
        () -> {
          var outer = SessionQueryTransaction.current(q);
          for (boolean shared : new boolean[] {true, false}) {
            var inner = shared ? q : SessionEventHistoryLengthTest.query();
            long[] beforeResume = {0};
            f.transaction.requiresNew(
                inner,
                () -> {
                  assertNotSame(outer, SessionQueryTransaction.current(inner));
                  beforeResume[0] = q.resources().operations();
                  return null;
                });
            assertEquals(44, q.resources().operations() - beforeResume[0]);
            assertSame(outer, SessionQueryTransaction.current(q));
            if (!shared) assertEquals(65536, inner.scratchLive());
          }
          return null;
        });
    assertEquals(3, f.database.commits);
    assertEquals(0, f.database.rollbacks);
    assertEquals(65536, q.scratchLive());
    System.out.println("RESUME_COST healthyEach=44 repeated=2 currentPopulation=0");
  }

  @Test
  void freshResumeBudgetRefusalAt43RestoresCleanupButCannotCommitOuter() throws Exception {
    for (long free : new long[] {43, 44, 45}) {
      var f = new SessionHistoryLengthTest.Fixture();
      var q = SessionEventHistoryLengthTest.query();
      RuntimeException[] first = {null};
      Runnable work =
          () ->
              f.work(
                  q,
                  () -> {
                    var outer = SessionQueryTransaction.current(q);
                    long[] beforeResume = {0};
                    f.transaction.requiresNew(
                        q,
                        () -> {
                          q.operation(65536 - q.resources().operations() - free);
                          beforeResume[0] = q.resources().operations();
                          return null;
                        });
                    assertEquals(44, q.resources().operations() - beforeResume[0]);
                    assertSame(outer, SessionQueryTransaction.current(q));
                    first[0] = q.recordedFailure();
                    return null;
                  });
      if (free == 43) {
        var caught = assertThrows(TaskFailure.class, work::run);
        assertSame(first[0], caught);
        assertEquals("SESSION_HISTORY_LIMIT", caught.code());
        assertEquals(65537, q.resources().operations());
        assertEquals(1, f.database.commits); // 内层在resume之前已确认提交。
        assertEquals(1, f.database.rollbacks);
      } else {
        work.run();
        assertNull(first[0]);
        assertEquals(2, f.database.commits);
        assertEquals(0, f.database.rollbacks);
      }
      assertEquals(65536, q.scratchLive());
    }
    System.out.println(
        "RESUME_THRESHOLD N=44 N-1=43 rejects outer; N/N+1 both transactions commit");
  }

  @Test
  void priorRuntimeOrErrorKeepsFirstFailureForMeteredAndUnmeteredQueries() throws Exception {
    for (boolean metered : new boolean[] {true, false}) {
      for (boolean fatal : new boolean[] {false, true}) {
        var f = new SessionHistoryLengthTest.Fixture();
        var q =
            metered
                ? SessionEventHistoryLengthTest.query()
                : new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
        var primary = new IllegalStateException("test-only first runtime");
        var error = new AssertionError("test-only first error");
        RuntimeException[] first = {null};
        var caught =
            assertThrows(
                RuntimeException.class,
                () ->
                    f.work(
                        q,
                        () -> {
                          f.transaction.requiresNew(
                              q,
                              () -> {
                                var inner = SessionQueryTransaction.current(q);
                                first[0] =
                                    fatal
                                        ? inner.recordMetadataError(error)
                                        : inner.poisonProof(primary);
                                return null;
                              });
                          return null;
                        }));
        assertSame(first[0], caught);
        if (fatal) assertSame(error, caught.getCause());
        else assertSame(primary, caught);
        assertEquals(0, f.database.commits);
        assertEquals(2, f.database.rollbacks);
        assertEquals(metered ? 65536 : 1048576, q.scratchLive());
      }
    }
  }

  @Test
  void errorDuringResumeMeteringIsRecordedBeforeRestorationAndNeverAuthorizesCommit()
      throws Exception {
    var f = new SessionHistoryLengthTest.Fixture();
    var q = SessionEventHistoryLengthTest.query();
    var original = new AssertionError("test-only metering failure during resume");
    var groups = (Map<String, long[]>) SessionHistoryStatementIdentityTest.field(q, "groups");
    var injected =
        new TreeMap<String, long[]>(groups) {
          @Override
          public long[] computeIfAbsent(
              String key, Function<? super String, ? extends long[]> factory) {
            throw original;
          }
        };
    var caught =
        assertThrows(
            RuntimeException.class,
            () ->
                f.work(
                    q,
                    () -> {
                      f.transaction.requiresNew(
                          q,
                          () -> {
                            // 仅测试副本：在内层最后一步注入，afterCompletion只遍历，下一次计量来自resume。
                            SessionHistoryStatementIdentityTest.put(q, "groups", injected);
                            return null;
                          });
                      assertNotNull(SessionQueryTransaction.current(q));
                      return null;
                    }));
    assertSame(caught, q.recordedFailure());
    assertSame(original, caught.getCause());
    assertEquals(1, f.database.commits);
    assertEquals(1, f.database.rollbacks);
    assertEquals(65536, q.scratchLive());
  }
}
