package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class SessionSqlLengthDispatchTest {
  @Test
  void historyActualProviderRoute() throws Exception {
    var f = new SessionHistoryLengthTest.Fixture();
    var q = SessionEventHistoryLengthTest.query();
    f.work(
        q,
        () -> {
          var rows = f.load(q);
          assertEquals(1, rows.size());
          return null;
        });
    assertEquals(2, q.resources().reads());
    assertEquals(65536, q.scratchLive());
    assertEquals(2114, q.resources().operations());
    System.out.println("DISPATCH_HISTORY total=" + q.resources().operations());
  }

  @Test
  void eventActualProviderRoute() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = SessionEventHistoryLengthTest.query();
    f.transaction.required(
        q,
        () -> {
          try {
            var slot = SessionEventHistoryLengthTest.start(q);
            SessionEventHistoryLengthTest.size(q, slot, 3L);
            SessionEventHistoryLengthTest.raw(q, slot, "abc");
            return null;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    assertEquals(2, q.resources().reads());
    assertEquals(65536, q.scratchLive());
    assertEquals(1361, q.resources().operations());
    System.out.println("DISPATCH_EVENT total=" + q.resources().operations());
  }

  @Test
  void historyThresholdIncludingTransactionBinding() throws Exception {
    for (int remaining : new int[] {2113, 2114, 2115}) {
      var f = new SessionHistoryLengthTest.Fixture();
      var q =
          new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
              System.nanoTime() + 2_000_000_000L,
              new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                  .QueryResources(0, 0, 65536 - remaining));
      q.restrictLineage();
      Throwable thrown = null;
      try {
        f.work(q, () -> f.load(q));
      } catch (RuntimeException e) {
        thrown = e;
      }
      assertEquals(remaining < 2114, thrown != null);
      assertEquals(65536, q.scratchLive());
      System.out.println(
          "DISPATCH_HISTORY_BOUNDARY remaining=" + remaining + " refused=" + (thrown != null));
    }
  }

  @Test
  void eventThresholdIncludingTransactionBinding() {
    for (int remaining : new int[] {1360, 1361, 1362}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q =
          new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
              System.nanoTime() + 2_000_000_000L,
              new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                  .QueryResources(0, 0, 65536 - remaining));
      q.restrictLineage();
      Throwable thrown = null;
      try {
        f.transaction.required(
            q,
            () -> {
              try {
                var slot = SessionEventHistoryLengthTest.start(q);
                SessionEventHistoryLengthTest.size(q, slot, 3L);
                SessionEventHistoryLengthTest.raw(q, slot, "abc");
                return null;
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
      } catch (RuntimeException e) {
        thrown = e;
      }
      assertEquals(remaining < 1361, thrown != null);
      assertEquals(65536, q.scratchLive());
      System.out.println(
          "DISPATCH_EVENT_BOUNDARY remaining=" + remaining + " refused=" + (thrown != null));
    }
  }
}
