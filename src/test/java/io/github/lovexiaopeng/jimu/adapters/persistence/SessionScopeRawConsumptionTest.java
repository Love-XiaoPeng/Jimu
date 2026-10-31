package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionScopeRawConsumptionTest {
  private SessionQueryContext query() {
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    query.restrictLineage();
    return query;
  }

  @Test
  void unicodeLengthAndDigestShareOneEncodingPerConsumptionEvenForSameString() {
    String raw = "scope-小鹏-😀";
    int length = raw.getBytes(StandardCharsets.UTF_8).length;
    for (int work = 0; work < 2; work++) {
      var q = query();
      var fixture = new SessionQueryTransactionTest.Fixture();
      fixture.transaction.required(
          q,
          () -> {
            long live = q.scratchLive();
            for (int read = 0; read < 2; read++) {
              int actual =
                  JdbcSessionRecords.withScopeRaw(
                      q,
                      raw,
                      (bytes, hash) -> {
                        assertTrue(q.scratchLive() > live);
                        assertEquals(length, bytes);
                        assertEquals(Payloads.hash(raw), hash);
                        return bytes;
                      });
              assertEquals(length, actual);
              assertEquals(live, q.scratchLive());
            }
            assertEquals(2L * raw.length(), q.completedWork().get("scope.encode"));
            assertEquals(2L * length, q.completedWork().get("history.hash"));
            assertFalse(q.completedWork().containsKey("history.encode"));
            return null;
          });
      assertEquals(1, fixture.commits);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void consumerFailureAndErrorReleaseWorkspaceAndPoisonTheRealWork() {
    for (boolean error : new boolean[] {false, true}) {
      var q = query();
      var fixture = new SessionQueryTransactionTest.Fixture();
      Throwable primary =
          error ? new AssertionError("consumer") : new IllegalStateException("consumer");
      assertThrows(
          RuntimeException.class,
          () ->
              fixture.transaction.required(
                  q,
                  () -> {
                    long live = q.scratchLive();
                    var caught =
                        assertThrows(
                            Throwable.class,
                            () ->
                                JdbcSessionRecords.withScopeRaw(
                                    q,
                                    "raw",
                                    (bytes, hash) -> {
                                      if (primary instanceof Error fatal) throw fatal;
                                      throw (RuntimeException) primary;
                                    }));
                    assertSame(primary, caught);
                    assertEquals(live, q.scratchLive());
                    assertThrows(RuntimeException.class, q::checkHealthy);
                    return null;
                  }));
      assertEquals(0, fixture.commits);
      assertEquals(1, fixture.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void lengthMismatchShortCircuitsHashAndForeignQueryPoisonsItsWork() {
    var standalone = query();
    boolean matched =
        JdbcSessionRecords.withScopeRaw(
            standalone, "raw", 4, (bytes, digest) -> bytes == 4 && digest.equals("irrelevant"));
    assertFalse(matched);
    assertFalse(standalone.completedWork().containsKey("history.hash"));
    assertEquals(65536, standalone.scratchLive());
    var owned = query();
    var fixture = new SessionQueryTransactionTest.Fixture();
    assertThrows(
        IllegalStateException.class,
        () ->
            fixture.transaction.required(
                owned,
                () -> {
                  assertThrows(
                      IllegalStateException.class,
                      () ->
                          JdbcSessionRecords.withScopeRaw(
                              query(), "raw", (bytes, digest) -> bytes));
                  assertThrows(RuntimeException.class, owned::checkHealthy);
                  return null;
                }));
    assertEquals(1, fixture.rollbacks);
    assertEquals(0, fixture.commits);
    assertEquals(65536, owned.scratchLive());
  }

  @Test
  void malformedAndPrepaidRefusalCannotReachConsumerOrLeakScratch() {
    var called = new AtomicBoolean();
    var q = query();
    var fixture = new SessionQueryTransactionTest.Fixture();
    var failure =
        assertThrows(
            TaskFailure.class,
            () ->
                fixture.transaction.required(
                    q,
                    () ->
                        JdbcSessionRecords.withScopeRaw(
                            q,
                            "\ud800",
                            (bytes, hash) -> {
                              called.set(true);
                              return bytes;
                            })));
    assertEquals("SESSION_HISTORY_CORRUPT", failure.code());
    assertFalse(called.get());
    assertEquals(1, fixture.rollbacks);
    assertEquals(65536, q.scratchLive());

    var exhausted =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 65520));
    exhausted.restrictLineage();
    assertThrows(
        TaskFailure.class,
        () ->
            JdbcSessionRecords.withScopeRaw(
                exhausted,
                "raw",
                (bytes, hash) -> {
                  called.set(true);
                  return bytes;
                }));
    assertFalse(called.get());
    assertEquals(65536, exhausted.scratchLive());
  }
}
