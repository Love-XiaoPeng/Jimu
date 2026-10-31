package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Seeded proof checks consumption only; real SQL provenance remains in the private DB scenario. */
class SessionJointConsumptionTest {
  private final List<String> sql = new ArrayList<>();
  private long stored = 9;
  private RuntimeException balanceFailure;
  private int changed = 1;

  private JdbcLedger ledger() throws Exception {
    var source =
        new AbstractDataSource() {
          public Connection getConnection() {
            throw new AssertionError("No raw SQL in consumption fixture");
          }

          public Connection getConnection(String user, String password) {
            return getConnection();
          }
        };
    var mapper =
        new SqlSessionTemplate(
            new DefaultSqlSessionFactory(
                new Configuration(
                    new Environment("consumption", new JdbcTransactionFactory(), source)))) {
          @SuppressWarnings("unchecked")
          public <T> T getMapper(Class<T> type) {
            return (T)
                Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    (p, m, a) -> {
                      sql.add(m.getName());
                      if (m.getName().equals("account")) {
                        stored = ((Number) a[1]).longValue();
                        return changed;
                      }
                      if (m.getName().equals("balance")) {
                        if (balanceFailure != null) throw balanceFailure;
                        var row = new HashMap<String, Object>();
                        row.put("metadata_bytes", stored);
                        row.put("body_bytes", 3L);
                        row.put("reserved_bytes", 8388608L);
                        row.put("held_bytes", 8388608L);
                        row.put("amount", 8388608L);
                        row.put("reservation_state", "RESERVED");
                        row.put("content_state", "PRESENT");
                        return List.of(row);
                      }
                      throw new AssertionError(m);
                    });
          }
        };
    var root = new JdbcLedger(source, null, null, null, null, mapper);
    var factory = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    factory.setAccessible(true);
    var v =
        (JdbcLedger)
            factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    v.queryContext().restrictLineage();
    return v;
  }

  private SessionScopeRecords.Caller caller(String id) {
    return new SessionScopeRecords.Caller(
        "tenant",
        id,
        "owner",
        SessionLineagePolicyV5.CAPABILITIES,
        "tools",
        1,
        "QUEUED",
        LocalDateTime.of(2026, 10, 29, 0, 0),
        "digest");
  }

  private SessionScopeRecords.Validated seed(JdbcLedger v, Runnable live) {
    var c = caller("caller");
    var work = SessionQueryTransaction.current(v.queryContext());
    work.locked("tenant", "caller");
    work.locked("tenant", "source");
    var s =
        new SessionScopeRecords.Scope(
            1,
            1,
            "owner",
            "digest",
            "tools",
            "{}",
            "authority",
            2,
            1,
            "ACTIVE",
            c.created(),
            c.created().plusDays(1),
            null,
            null,
            c.created(),
            "PRESENT",
            true);
    var p =
        new SessionScopeRecords.Validated(
            s,
            new SessionScopeRecords.Authority(
                "tenant", "caller", "owner", "tools", "digest", "", "", List.of()),
            List.of(caller("source")));
    work.scope(c, p, live);
    return p;
  }

  @Test
  void ensureAndDigestBorrowProofWhileRealConsumerMaterializesOriginalFacade() throws Exception {
    var v = ledger();
    var q = v.queryContext();
    var f = new SessionQueryTransactionTest.Fixture();
    int[] live = {0};
    f.transaction.required(
        q,
        () -> {
          var proof = seed(v, () -> live[0]++);
          var task = MapperSessionScopeRecords.row(caller("caller"));
          long before = q.resources().operations();
          v.sessionScopes.ensure(task);
          assertSame(proof, v.sessionScopes.requireProof(task));
          long typed = q.resources().operations() - before;
          var facade = v.sessionScopes.require(task);
          assertEquals("authority", facade.row().get("authority_digest"));
          assertEquals(1, facade.sources().size());
          assertEquals("source", facade.sources().getFirst().get("id"));
          assertTrue(facade.authority().isObject());
          assertTrue(q.resources().operations() - before > typed);
          var eligibility =
              new StoredSessionInputEligibility(v.sessions, task, null).requireScope();
          assertTrue(eligibility.contains("source"));
          return null;
        });
    assertEquals(1, live[0]);
    assertEquals(1, f.commits);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void newProofEntryRejectsCallerMutationForeignQueryAndCaughtError() throws Exception {
    for (String mode : List.of("caller", "foreign", "error", "write")) {
      var v = ledger();
      var q = v.queryContext();
      var f = new SessionQueryTransactionTest.Fixture();
      var foreign = ledger();
      var error = new AssertionError("proof caller Error");
      var task = MapperSessionScopeRecords.row(caller("caller"));
      Map<String, Object> input =
          mode.equals("error")
              ? new HashMap<>(task) {
                @Override
                public Object get(Object key) {
                  throw error;
                }
              }
              : task;
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    seed(v, () -> {});
                    if (mode.equals("caller")) task.put("tools_digest", "changed");
                    if (mode.equals("write"))
                      assertThrows(
                          RuntimeException.class,
                          () -> SessionQueryTransaction.current(q).dependencyMutation(null));
                    if (mode.equals("error"))
                      assertSame(
                          error,
                          assertThrows(
                              AssertionError.class, () -> v.sessionScopes.requireProof(input)));
                    else
                      assertThrows(
                          RuntimeException.class,
                          () ->
                              (mode.equals("foreign") ? foreign : v)
                                  .sessionScopes.requireProof(input));
                    return null;
                  }));
      assertEquals(0, f.commits);
      assertEquals(1, f.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void accountAndReadBackAlwaysQueriesAfterConditionalOriginalUpdate() throws Exception {
    var v = ledger();
    var q = v.queryContext();
    var f = new SessionQueryTransactionTest.Fixture();
    f.transaction.required(
        q,
        () -> {
          var records =
              new MapperSessionMetadataAccounting(
                  v.sessions, MapperSessionScopeRecords.row(caller("caller")), true);
          try (var hold = records.finalization()) {
            assertEquals(27, records.accountAndReadBack(9, 27).metadata());
            assertEquals(27, records.accountAndReadBack(27, 27).metadata());
            assertEquals(List.of("account", "balance", "balance"), sql);
          }
          return null;
        });
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void finalizationRejectsRepeatedClosedForeignAndFailedBalanceWithoutCommitting()
      throws Exception {
    for (String mode : List.of("reenter", "closed", "foreign", "balance", "changed")) {
      var v = ledger();
      var q = v.queryContext();
      var f = new SessionQueryTransactionTest.Fixture();
      var nested = ledger();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    var records =
                        new MapperSessionMetadataAccounting(
                            v.sessions, MapperSessionScopeRecords.row(caller("caller")), true);
                    var hold = records.finalization();
                    try {
                      if (mode.equals("closed")) hold.close();
                      if (mode.equals("balance"))
                        balanceFailure = new IllegalStateException("fresh readback");
                      if (mode.equals("changed")) changed = 0;
                      if (mode.equals("foreign")) {
                        assertThrows(
                            RuntimeException.class,
                            () ->
                                f.transaction.requiresNew(
                                    nested.queryContext(),
                                    () -> {
                                      records.finalTable(Table.EVENT);
                                      return null;
                                    }));
                      } else
                        assertThrows(
                            RuntimeException.class,
                            () -> {
                              if (mode.equals("reenter")) records.finalization();
                              else if (mode.equals("closed")) records.finalTable(Table.EVENT);
                              else records.accountAndReadBack(9, 27);
                            });
                    } finally {
                      hold.close();
                    }
                    return null;
                  }));
      assertEquals(0, f.commits);
      assertEquals(65536, q.scratchLive());
      balanceFailure = null;
      changed = 1;
    }
  }

  @Test
  void finalFactoriesRejectForeignQueryBeforeLoaderAndPoisonActualWork() throws Exception {
    for (boolean cursor : List.of(false, true)) {
      var v = ledger();
      var q = v.queryContext();
      var f = new SessionQueryTransactionTest.Fixture();
      var foreign = ledger();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    assertThrows(
                        RuntimeException.class,
                        () -> {
                          if (cursor)
                            SessionCursorMetadataBatch.measureFinal(
                                foreign.queryContext(),
                                Table.EVENT,
                                SessionMetadataProjection.forTable(Table.EVENT),
                                () -> {
                                  throw new AssertionError("foreign loader");
                                });
                          else
                            SessionLineageMetadataBatch.measureFinal(
                                foreign.queryContext(),
                                () -> {
                                  throw new AssertionError("foreign loader");
                                });
                        });
                    return null;
                  }));
      assertEquals(0, f.commits);
      assertEquals(1, f.rollbacks);
      assertEquals(65536, q.scratchLive());
      assertEquals(65536, foreign.queryContext().scratchLive());
    }
  }
}
