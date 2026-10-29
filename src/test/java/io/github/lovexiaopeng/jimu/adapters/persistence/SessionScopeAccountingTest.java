package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** scope补账的独立字节oracle、原单槽fallback及拒绝前不执行编码。 */
class SessionScopeAccountingTest {
  static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  @Test
  void scopeHashUsesOnlyTheExistingLocalIdentitySlot() {
    var q = query();
    var r = new JdbcSessionRecords(new JdbcTemplate(), q);
    var f = new SessionQueryTransactionTest.Fixture();
    String local = r.v5.binding();
    long full = 1L + local.length() + local.getBytes(StandardCharsets.UTF_8).length;
    assertEquals(Payloads.hash(local), r.scopeHash(local));
    assertEquals(full, q.resources().operations());
    for (int i = 0; i < 2; i++)
      f.transaction.required(
          q,
          () -> {
            long start = q.resources().operations();
            assertEquals(Payloads.hash(local), r.scopeHash(local));
            assertEquals(full + 1, q.resources().operations() - start);
            start = q.resources().operations();
            r.scopeHash(local);
            assertEquals(2, q.resources().operations() - start);
            start = q.resources().operations();
            r.scopeHash(new String(local));
            assertEquals(full, q.resources().operations() - start);
            return null;
          });
    assertEquals(65536, q.scratchLive());
    f.transaction.required(
        q,
        () -> {
          var v4 = Map.<String, Object>of("capabilities", "session_event_read,session_search");
          r.policyHash(v4, r.v4.binding());
          for (int i = 0; i < 2; i++) {
            long before = q.resources().operations();
            r.scopeHash(local);
            assertEquals(full + 1, q.resources().operations() - before);
          }
          return null;
        });
  }

  @Test
  void everyUtf8LengthActuallyEncodesAndReleasesIncludingMalformedSurrogates() {
    for (String raw : new String[] {"", "abc", "中😀\u0000尾  ", "\ud800x\udc00"}) {
      var q = query();
      var f = new SessionQueryTransactionTest.Fixture();
      f.transaction.required(
          q,
          () -> {
            long live = q.scratchLive();
            for (int i = 0; i < 2; i++) {
              long before = q.resources().operations();
              assertEquals(
                  raw.getBytes(StandardCharsets.UTF_8).length,
                  JdbcSessionRecords.scopeUtf8Bytes(q, raw));
              assertEquals(raw.length() + 5, q.resources().operations() - before);
              assertEquals(live, q.scratchLive());
            }
            assertEquals(2L * raw.length(), q.completedWork().get("scope.encode"));
            return null;
          });
    }
    assertEquals(3, JdbcSessionRecords.scopeUtf8Bytes(null, "中"));
    assertThrows(NullPointerException.class, () -> JdbcSessionRecords.scopeUtf8Bytes(null, null));
    var q = query();
    assertThrows(NullPointerException.class, () -> JdbcSessionRecords.scopeUtf8Bytes(q, null));
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void wrongOrMissingOwnerQueryPoisonsFinishAndCannotUseLegacyFallback() {
    for (boolean hash : new boolean[] {false, true})
      for (var supplied : new SessionQueryContext[] {null, query()}) {
        var q = query();
        var f = new SessionQueryTransactionTest.Fixture();
        assertThrows(
            IllegalStateException.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      var first =
                          assertThrows(
                              IllegalStateException.class,
                              () -> {
                                if (hash)
                                  new JdbcSessionRecords(new JdbcTemplate(), supplied)
                                      .scopeHash("foreign");
                                else JdbcSessionRecords.scopeUtf8Bytes(supplied, "foreign");
                              });
                      assertSame(first, q.recordedFailure());
                      assertSame(first, assertThrows(RuntimeException.class, q::checkHealthy));
                      return null; // transaction.finish must rethrow the caught first error.
                    }));
      }
  }

  @Test
  void rejectedEncodingNeverExecutesAndCatchCannotRestoreTheBudget() {
    var q = query();
    q.operation(65530);
    assertThrows(RuntimeException.class, () -> JdbcSessionRecords.scopeUtf8Bytes(q, "abc"));
    assertTrue(q.completedWork().isEmpty());
    assertEquals(3L, q.requestedWork().get("scope.encode"));
    assertEquals(65536, q.scratchLive());
    var first = q.recordedFailure();
    assertSame(
        first,
        assertThrows(RuntimeException.class, () -> JdbcSessionRecords.scopeUtf8Bytes(q, "")));
  }
}
