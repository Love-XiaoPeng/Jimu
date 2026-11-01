package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 新绑定只能供当前FINAL使用；变更task或挂起Work在任何Mapper/SQL之前失败。 */
class SessionFinalLockOwnershipTest {
  @Test
  void querylessClaimAndOrdinaryAccountingKeepTheirOriginalReader() {
    for (var query : java.util.Arrays.asList(null, new SessionQueryContext(Long.MAX_VALUE, null))) {
      var accounting =
          new MapperSessionMetadataAccounting(
              new JdbcSessionRecords(null, query),
              Map.of(
                  "tenant_id",
                  "t",
                  "id",
                  "c",
                  "capabilities",
                  SessionLineagePolicyV5.CAPABILITIES));
      for (int round = 0; round < 4; round++) {
        try (var scope = accounting.finalization()) {
          assertFalse(
              accounting.consumeMetadata(
                  (t, r, b, h, hs, n, rs) -> {
                    throw new AssertionError("No synchronous Result outside lineage Work");
                  }));
        }
      }
    }
  }

  @Test
  void changedTenantTaskAndSuspendedWorkAreRejectedByActualFinalEntryBeforeSql() {
    for (String change : List.of("tenant_id", "id", "suspended")) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
      q.restrictLineage();
      var task =
          new HashMap<String, Object>(
              Map.of(
                  "tenant_id",
                  "t",
                  "id",
                  "c",
                  "capabilities",
                  SessionLineagePolicyV5.CAPABILITIES));
      // No Mapper exists: reaching it would throw a different exception instead of this refusal.
      var accounting =
          new MapperSessionMetadataAccounting(new JdbcSessionRecords(null, q), task, true);
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  f.transaction.required(
                      q,
                      () -> {
                        var w = SessionQueryTransaction.current(q);
                        w.locked("t", "c");
                        w.locked("other", "c");
                        w.locked("t", "other");
                        try (var scope = accounting.finalization()) {
                          if (change.equals("suspended")) {
                            var other =
                                new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
                            return f.transaction.requiresNew(
                                other,
                                () ->
                                    SessionFinalResultFoldTest.table(
                                        accounting, Table.TASK_ATTEMPT));
                          }
                          task.put(change, "other");
                          return SessionFinalResultFoldTest.table(accounting, Table.TASK_ATTEMPT);
                        }
                      }));
      assertEquals(
          change.equals("suspended")
              ? "Inactive or foreign metadata finalization"
              : "Changed FINAL task identity",
          failure.getMessage());
      assertEquals(0, q.resources().reads());
      assertEquals(65536, q.scratchLive());
    }
  }
}
