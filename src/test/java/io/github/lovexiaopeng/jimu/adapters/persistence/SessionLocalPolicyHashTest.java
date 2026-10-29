package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 实际Records共用入口；旧行proof短路另由原service测试和私库验证。 */
class SessionLocalPolicyHashTest {
  private static final Map<String, Object> TASK =
      Map.of("tenant_id", "t", "id", "x", "capabilities", SessionLineagePolicyV5.CAPABILITIES);

  @Test
  void storageBindingAndProofShareOnlyWithinExactCurrentTransaction() {
    var q = query();
    var records = new JdbcSessionRecords(new JdbcTemplate(), q);
    String local = new MapperSessionHistoryStorage(records, TASK).binding().snapshot();
    String expected = Payloads.hash(local);
    var proof = new MapperSessionHistoryProofs(records, TASK, null);
    var f = new SessionQueryTransactionTest.Fixture();
    for (int transaction = 0; transaction < 2; transaction++) {
      f.transaction.required(
          q,
          () -> {
            long start = q.resources().operations();
            assertEquals(expected, records.policyHash(TASK, local));
            long creation = q.resources().operations() - start;
            assertEquals(
                local.length() + local.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2,
                creation);
            long live = q.scratchLive();
            assertEquals(expected, proof.hash(proof.binding()));
            assertEquals(start + creation + 2, q.resources().operations());
            assertEquals(live, q.scratchLive());
            long beforeForeign = q.resources().operations();
            assertEquals(expected, proof.hash(new String(local)));
            assertEquals(creation - 1, q.resources().operations() - beforeForeign);
            return null;
          });
      assertEquals(65536, q.scratchLive());
    }
    assertEquals(2, f.commits);
  }

  @Test
  void noOwnerAndOldNonlineageKeepIndependentDigestAndNoCache() {
    for (var q :
        new SessionQueryContext[] {
          null, new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null), query()
        }) {
      var records = new JdbcSessionRecords(new JdbcTemplate(), q);
      long before = q == null ? 0 : q.scratchLive();
      String raw = records.contract(TASK).binding();
      assertEquals(Payloads.hash(raw), records.policyHash(TASK, raw));
      assertEquals(Payloads.hash(raw), records.policyHash(TASK, new String(raw)));
      if (q != null) assertEquals(before, q.scratchLive());
    }
  }

  @Test
  void wrongOwnerCannotSupplyPolicyAndFirstFailureCannotPopulateCache() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var foreign = query();
    var records = new JdbcSessionRecords(new JdbcTemplate(), foreign);
    assertThrows(
        IllegalStateException.class,
        () ->
            f.transaction.required(
                q, () -> records.policyHash(TASK, records.contract(TASK).binding())));
    assertEquals(1, f.rollbacks);
    assertEquals(65536, q.scratchLive());
    var current = new JdbcSessionRecords(new JdbcTemplate(), q);
    var first = new IllegalStateException("policy first marker");
    q.recordFailure(first);
    assertSame(
        first,
        assertThrows(
            RuntimeException.class,
            () -> current.policyHash(TASK, current.contract(TASK).binding())));
    assertTrue(q.completedWork().isEmpty());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void nullRawNeverCreatesPolicyCacheOrChangesLegacyNullFailure() {
    assertThrows(
        NullPointerException.class,
        () -> new JdbcSessionRecords(new JdbcTemplate()).policyHash(TASK, null));
    var q = query();
    var records = new JdbcSessionRecords(new JdbcTemplate(), q);
    var f = new SessionQueryTransactionTest.Fixture();
    assertThrows(
        NullPointerException.class,
        () -> f.transaction.required(q, () -> records.policyHash(TASK, null)));
    assertEquals(65536, q.scratchLive());
  }

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }
}
