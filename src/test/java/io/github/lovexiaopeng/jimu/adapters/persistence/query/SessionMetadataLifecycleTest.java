package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

public class SessionMetadataLifecycleTest {
  private static final class Fixture {
    final SessionQueryTransactionTest.Fixture base = new SessionQueryTransactionTest.Fixture();
    String charset = "utf8mb4";
    final com.mysql.cj.jdbc.JdbcPropertySetImpl properties =
        new com.mysql.cj.jdbc.JdbcPropertySetImpl();
    final Connection connection;
    final javax.sql.DataSource source;
    final SessionQueryTransaction transaction;

    Fixture() {
      properties
          .getStringProperty(com.mysql.cj.conf.PropertyKey.characterEncoding)
          .setValue("UTF-8");
      var server =
          (com.mysql.cj.protocol.ServerSession)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {com.mysql.cj.protocol.ServerSession.class},
                  (p, m, a) -> m.getName().equals("getServerVariable") ? charset : null);
      var driverSession =
          (com.mysql.cj.Session)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {com.mysql.cj.Session.class},
                  (p, m, a) -> m.getName().equals("getServerSession") ? server : null);
      connection =
          (Connection)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {com.mysql.cj.jdbc.JdbcConnection.class},
                  (p, m, a) -> {
                    if (m.getName().equals("getPropertySet")) return properties;
                    if (m.getName().equals("getSession")) return driverSession;
                    try {
                      return m.invoke(base.connection, a);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                      throw e.getCause();
                    }
                  });
      source =
          new org.springframework.jdbc.datasource.AbstractDataSource() {
            public Connection getConnection() {
              return connection;
            }

            public Connection getConnection(String u, String p) {
              return connection;
            }
          };
      transaction =
          new SessionQueryTransaction(
              source, new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    }
  }

  private static final String TENANT = "tenant", TASK = "task";

  private SessionQueryContext q() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private Map<String, Object> row(Object length) {
    var r = new LinkedHashMap<String, Object>();
    r.put("tenant_id", TENANT);
    r.put("task_id", TASK);
    r.put("record_version", 1);
    r.put("history_version", 1);
    r.put("binding_snapshot", length);
    r.put("binding_digest", "a".repeat(64));
    r.put("content_state", "PRESENT");
    for (var name :
        List.of("next_seq", "body_bytes", "metadata_bytes", "reserved_bytes", "held_bytes"))
      r.put(name, 27L);
    r.put("retain_until", java.time.LocalDateTime.of(2026, 10, 29, 1, 2, 3));
    r.put("cleanup_receipt", null);
    return r;
  }

  private Configuration config(Fixture f, String xml) throws Exception {
    var c = new Configuration();
    io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.configure(
        c);
    c.setEnvironment(
        new Environment(
            "prototype",
            new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
            f.source));
    try (var in =
        new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      new XMLMapperBuilder(in, c, "prototype", c.getSqlFragments()).parse();
    }
    return c;
  }

  private String xml() throws Exception {
    return java.nio.file.Files.readString(
        java.nio.file.Path.of(
            "src/main/resources/mapper/session/SessionHistoryRetentionMapper.xml"));
  }

  @SuppressWarnings("unchecked")
  private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h);
  }

  private SqlSession session(
      Fixture f, SessionQueryContext q, Configuration c, List<Map<String, Object>> inputs) {
    return proxy(
        SqlSession.class,
        (p, m, a) ->
            switch (m.getName()) {
              case "getConfiguration" -> c;
              case "getConnection" -> f.connection;
              case "selectList" -> {
                assertEquals(SessionHistorySizedMetadataBatch.STATEMENT, a[0]);
                assertSame(q, ((Map<?, ?>) a[1]).get("query"));
                assertEquals(TENANT, ((Map<?, ?>) a[1]).get("tenant"));
                var keys =
                    inputs.isEmpty()
                        ? List.<String>of()
                        : new ArrayList<>(inputs.getFirst().keySet());
                int[] at = {-1};
                var metadata =
                    proxy(
                        ResultSetMetaData.class,
                        (v, method, args) ->
                            switch (method.getName()) {
                              case "getColumnCount" -> keys.size();
                              case "getColumnLabel" -> keys.get((int) args[0] - 1);
                              default -> null;
                            });
                var raw =
                    proxy(
                        ResultSet.class,
                        (v, method, args) ->
                            switch (method.getName()) {
                              case "getMetaData" -> metadata;
                              case "next" -> ++at[0] < inputs.size();
                              case "getObject" ->
                                  inputs.get(at[0]).get(keys.get((int) args[0] - 1));
                              case "close" -> null;
                              default -> null;
                            });
                var result = new ArrayList<Map<String, Object>>();
                try (var metered = QueryResults.meter(raw, q, (String) a[0])) {
                  while (metered.next()) result.add(new LinkedHashMap<>(inputs.get(at[0])));
                }
                yield result;
              }
              default -> throw new UnsupportedOperationException(m.getName());
            });
  }

  private <T> T owned(Fixture f, SessionQueryContext q, java.util.function.Supplier<T> s) {
    return f.transaction.required(
        q,
        () -> {
          SessionQueryTransaction.current(q).locked(TENANT, TASK);
          return s.get();
        });
  }

  private void ended(SessionQueryWork owner, boolean value) {
    try {
      var f = SessionQueryWork.class.getDeclaredField("completed");
      f.setAccessible(true);
      f.setBoolean(owner, value);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private long retainedBatches(SessionQueryContext q) {
    try {
      var f = SessionQueryContext.class.getDeclaredField("retainedScratch");
      f.setAccessible(true);
      var m = (Map<?, ?>) f.get(q);
      return m.keySet().stream().filter(x -> x instanceof SessionHistorySizedMetadataBatch).count();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void caughtStaleBatchMustPoisonCurrentWork() throws Exception {
    var f = new Fixture();
    var q1 = q();
    var c = config(f, xml());
    var b =
        owned(
            f,
            q1,
            () ->
                SessionHistorySizedMetadataBatch.load(
                    session(f, q1, c, List.of(row(4))), q1, TENANT, TASK));
    var q2 = q();
    RuntimeException[] caught = {null};
    RuntimeException outer = null;
    try {
      owned(
          f,
          q2,
          () -> {
            try {
              b.iterator();
            } catch (RuntimeException e) {
              caught[0] = e;
            }
            return "caught";
          });
    } catch (RuntimeException e) {
      outer = e;
    }
    System.out.println(
        "CROSS_WORK caught="
            + caught[0]
            + " secondCommitted="
            + (f.base.commits == 2)
            + " newFirst="
            + q2.recordedFailure()
            + " liveOld="
            + q1.scratchLive());
    assertSame(caught[0], outer, "catching cross-Work proof failure must not permit commit");
    assertEquals(1, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, q1.scratchLive());
    assertEquals(65536, q2.scratchLive());
  }

  @Test
  void closedBatchMustPoisonNewWork() throws Exception {
    var f = new Fixture();
    var q1 = q();
    var c = config(f, xml());
    var b =
        owned(
            f,
            q1,
            () ->
                SessionHistorySizedMetadataBatch.load(
                    session(f, q1, c, List.of(row(4))), q1, TENANT, TASK));
    b.close();
    var q2 = q();
    RuntimeException[] caught = {null};
    var outer =
        assertThrows(
            RuntimeException.class,
            () ->
                owned(
                    f,
                    q2,
                    () -> {
                      try {
                        b.iterator();
                      } catch (RuntimeException e) {
                        caught[0] = e;
                      }
                      return "caught";
                    }));
    assertSame(caught[0], outer);
    assertSame(outer, q2.recordedFailure());
  }

  @Test
  void closedBatchRepeatedAcrossNewWorkKeepsEachCurrentFirstSlot() throws Exception {
    var f = new Fixture();
    var originalQuery = q();
    var c = config(f, xml());
    var batch =
        owned(
            f,
            originalQuery,
            () ->
                SessionHistorySizedMetadataBatch.load(
                    session(f, originalQuery, c, List.of(row(4))), originalQuery, TENANT, TASK));
    batch.close();
    for (int transaction = 0; transaction < 3; transaction++) {
      var currentQuery = q();
      RuntimeException[] first = {null};
      var outer =
          assertThrows(
              RuntimeException.class,
              () ->
                  owned(
                      f,
                      currentQuery,
                      () -> {
                        long before = currentQuery.resources().operations();
                        for (int access = 0; access < 100; access++) {
                          var caught = assertThrows(RuntimeException.class, batch::iterator);
                          if (access == 0) first[0] = caught;
                          assertSame(first[0], caught);
                          assertSame(caught, currentQuery.recordedFailure());
                        }
                        return "caught";
                      }));
      assertSame(first[0], outer);
      assertEquals(65536, currentQuery.scratchLive());
    }
    assertEquals(1, f.base.commits);
    assertEquals(3, f.base.rollbacks);
    assertEquals(65536, originalQuery.scratchLive());
  }

  @Test
  void scalarErrorAfterEndedWorkReleasesBeforePoison() throws Exception {
    scalarFault(true);
  }

  @Test
  void scalarErrorInActiveWorkKeepsErrorAndRollsBack() throws Exception {
    scalarFault(false);
  }

  @Test
  void scalarClosedAfterEndedWorkReleasesAndRecordsFirst() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    var b =
        owned(
            f,
            q,
            () ->
                SessionHistorySizedMetadataBatch.load(
                    session(f, q, c, List.of(row(4))), q, TENANT, TASK));
    var e = assertThrows(RuntimeException.class, () -> b.scalarBytes(27L));
    assertSame(e, q.recordedFailure());
    assertEquals(65536, q.scratchLive());
  }

  private void scalarFault(boolean terminate) throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    var error = new AssertionError("scalar injected error");
    var groups = SessionQueryContext.class.getDeclaredField("groups");
    groups.setAccessible(true);
    Object originalGroups = groups.get(q);
    var outer =
        assertThrows(
            RuntimeException.class,
            () ->
                owned(
                    f,
                    q,
                    () -> {
                      var b =
                          SessionHistorySizedMetadataBatch.load(
                              session(f, q, c, List.of(row(4))), q, TENANT, TASK);
                      var owner = SessionQueryTransaction.current(q);
                      try {
                        var throwing =
                            new java.util.TreeMap<String, long[]>() {
                              public long[] computeIfAbsent(
                                  String key,
                                  java.util.function.Function<? super String, ? extends long[]>
                                      fn) {
                                if (terminate) ended(owner, true);
                                throw error;
                              }
                            };
                        try {
                          groups.set(q, throwing);
                        } catch (Exception e) {
                          throw new AssertionError(e);
                        }
                        Throwable actual = null;
                        try {
                          b.scalarBytes(27L);
                        } catch (Throwable t) {
                          actual = t;
                        } finally {
                          try {
                            groups.set(q, originalGroups);
                          } catch (Exception e) {
                            throw new AssertionError(e);
                          }
                          ended(owner, false);
                        }
                        System.out.println(
                            "SCALAR_ERROR ended="
                                + terminate
                                + " actual="
                                + actual
                                + " batches="
                                + retainedBatches(q)
                                + " first="
                                + q.recordedFailure());
                        assertSame(error, actual);
                        assertEquals(0, retainedBatches(q));
                        assertSame(error, q.recordedFailure().getCause());
                      } finally {
                        b.close();
                      }
                      return "caught";
                    }));
    assertSame(error, outer.getCause());
    assertEquals(0, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void endedWorkLoadRuntimeMustReleaseAndKeepSqlCause() throws Exception {
    loadEnded(false);
  }

  @Test
  void endedWorkLoadErrorMustReleaseAndKeepError() throws Exception {
    loadEnded(true);
  }

  private void loadEnded(boolean error) throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    var delegate = session(f, q, c, List.of(row(4)));
    var sql = new SQLException("provider", "HY088", 88);
    var original = new org.springframework.dao.DataAccessResourceFailureException("provider", sql);
    var suppressed = new SQLException("cleanup", "08003", 89);
    original.addSuppressed(suppressed);
    var fatal = new AssertionError("provider ended Work");
    int[] connections = {0};
    SessionQueryWork[] work = {null};
    var s =
        proxy(
            SqlSession.class,
            (p, m, a) -> {
              if (m.getName().equals("getConnection") && ++connections[0] == 2) {
                ended(work[0], true);
                if (error) throw fatal;
                throw original;
              }
              try {
                return m.invoke(delegate, a);
              } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
              }
            });
    try {
      owned(
          f,
          q,
          () -> {
            work[0] = SessionQueryTransaction.current(q);
            Throwable actual = null;
            try {
              SessionHistorySizedMetadataBatch.load(s, q, TENANT, TASK);
            } catch (Throwable t) {
              actual = t;
            } finally {
              ended(work[0], false);
            }
            long batches = retainedBatches(q);
            System.out.println(
                "ENDED_LOAD error="
                    + error
                    + " caught="
                    + actual
                    + " batchesBeforeExplicitClose="
                    + batches
                    + " scratch="
                    + q.scratchLive()
                    + " first="
                    + q.recordedFailure());
            assertEquals(0, batches, "batch must release even if poisonProof rejects ended Work");
            if (error) assertSame(fatal, actual);
            else {
              assertSame(original, actual);
              assertSame(sql, actual.getCause());
              assertSame(suppressed, actual.getSuppressed()[0]);
            }
            if (q.recordedFailure() != null) throw q.recordedFailure();
            throw new IllegalStateException("end test");
          });
    } catch (RuntimeException e) {
    }
  }
}
