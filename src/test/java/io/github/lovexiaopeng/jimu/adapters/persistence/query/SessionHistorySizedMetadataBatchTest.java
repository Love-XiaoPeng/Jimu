package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

public class SessionHistorySizedMetadataBatchTest {
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

  private long consume(SessionMetadataAccounting.Batch b) {
    long n = 0;
    for (var r : b) for (var v : r.values()) n += b.scalarBytes(v);
    return n;
  }

  private SessionMetadataAccounting.Batch original(SessionQueryContext q, Map<String, Object> row) {
    try {
      var type =
          Class.forName(
              "io.github.lovexiaopeng.jimu.adapters.persistence.SessionHistoryMetadataBatch");
      var ctor = type.getDeclaredConstructors()[0];
      ctor.setAccessible(true);
      java.util.function.Supplier<List<Map<String, Object>>> load =
          () -> {
            try (var r = q.result("original.metadataTaskSessionHistory")) {
              r.row();
              for (var e : row.entrySet()) r.column(e.getKey(), e.getValue());
            }
            return List.of(row);
          };
      return (SessionMetadataAccounting.Batch) ctor.newInstance(q, load);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void lengthsAndDynamicSemantics() throws Exception {
    for (Object length : List.of(0, 0L, 4, 4L, 27L, 65535L)) {
      var f = new Fixture();
      var q = q();
      var c = config(f, xml());
      owned(
          f,
          q,
          () -> {
            try (var b =
                SessionHistorySizedMetadataBatch.load(
                    session(f, q, c, List.of(row(length))), q, TENANT, TASK)) {
              var r = b.iterator().next();
              assertEquals(
                  ((Number) length).longValue(), b.scalarBytes(r.value("binding_snapshot")));
              assertEquals(2, b.scalarBytes(27L));
              assertEquals(27L, r.value("metadata_bytes"));
              assertEquals("PRESENT", r.value("content_state"));
              System.out.println("LENGTH " + length + " scalar=typed; ordinaryLong27=2");
              return null;
            }
          });
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void rejectBadShapesAndPoisonCaughtFailure() throws Exception {
    var bad = new ArrayList<Map<String, Object>>();
    for (Object v : Arrays.asList(null, -1, 65536L, "27", 27.0, new java.math.BigInteger("27")))
      bad.add(row(v));
    var missing = row(2);
    missing.remove("binding_snapshot");
    bad.add(missing);
    var label = row(2);
    label.put("binding_snapshot_utf8_bytes", label.remove("binding_snapshot"));
    bad.add(label);
    var mixed = row("raw old binding");
    bad.add(mixed);
    var tenant = row(2);
    tenant.put("tenant_id", "other");
    bad.add(tenant);
    var typed = row(2);
    typed.put("next_seq", 27);
    bad.add(typed);
    for (var r : bad) {
      var f = new Fixture();
      var q = q();
      var c = config(f, xml());
      var outer =
          assertThrows(
              RuntimeException.class,
              () ->
                  owned(
                      f,
                      q,
                      () -> {
                        var first =
                            assertThrows(
                                RuntimeException.class,
                                () ->
                                    SessionHistorySizedMetadataBatch.load(
                                        session(f, q, c, List.of(r)), q, TENANT, TASK));
                        assertSame(first, q.recordedFailure());
                        return "caught";
                      }));
      assertSame(q.recordedFailure(), outer);
      assertEquals(0, f.base.commits);
      assertEquals(1, f.base.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
    System.out.println("BAD_SHAPES " + bad.size() + " caught then rollback");
  }

  @Test
  void sqlStatementConnectionQueryAndLockRejection() throws Exception {
    for (String change :
        List.of("rawSQL", "statement", "DS", "connection", "query", "lock", "cache")) {
      var f = new Fixture();
      var q = q();
      var text = xml();
      if (change.equals("rawSQL"))
        text =
            text.replace(
                "OCTET_LENGTH(CONVERT(binding_snapshot USING utf8mb4)) AS binding_snapshot",
                "binding_snapshot");
      if (change.equals("statement"))
        text = text.replace("metadataTaskSessionHistorySized", "metadataTaskSessionHistoryOther");
      var c = config(f, text);
      if (change.equals("cache")) c.setCacheEnabled(true);
      if (change.equals("DS"))
        c.setEnvironment(
            new Environment(
                "other",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
                new Fixture().source));
      var s = session(f, q, c, List.of(row(27)));
      if (change.equals("connection")) {
        var delegate = s;
        s =
            proxy(
                SqlSession.class,
                (p, m, a) ->
                    m.getName().equals("getConnection")
                        ? new Fixture().connection
                        : m.invoke(delegate, a));
      }
      var chosen = s;
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    if (!change.equals("lock"))
                      SessionQueryTransaction.current(q).locked(TENANT, TASK);
                    return SessionHistorySizedMetadataBatch.load(
                        chosen, change.equals("query") ? q() : q, TENANT, TASK);
                  }));
      assertEquals(0, f.base.commits);
      assertEquals(1, f.base.rollbacks);
    }
    System.out.println("SOURCE_REJECTIONS 7");
  }

  @Test
  void closureCrossBatchAndEmptyPurged() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
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
                  var r = b.iterator().next();
                  var marker = r.value("binding_snapshot");
                  b.close();
                  b.close();
                  assertThrows(IllegalStateException.class, () -> r.value("body_bytes"));
                  assertThrows(IllegalStateException.class, () -> b.scalarBytes(marker));
                  return null;
                }));
    var f2 = new Fixture();
    var q2 = q();
    var c2 = config(f2, xml());
    owned(
        f2,
        q2,
        () -> {
          try (var empty =
              SessionHistorySizedMetadataBatch.load(
                  session(f2, q2, c2, List.of()), q2, TENANT, TASK)) {
            assertFalse(empty.iterator().hasNext());
          }
          var purged = row(0);
          purged.put("content_state", "PURGED");
          purged.put("cleanup_receipt", "receipt");
          try (var b =
              SessionHistorySizedMetadataBatch.load(
                  session(f2, q2, c2, List.of(purged)), q2, TENANT, TASK)) {
            assertEquals("PURGED", b.iterator().next().value("content_state"));
          }
          return null;
        });
  }

  @Test
  void localCostAndOperationThreshold() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    long[] cost = {0};
    owned(
        f,
        q,
        () -> {
          long before = q.resources().operations();
          try (var b =
              SessionHistorySizedMetadataBatch.load(
                  session(f, q, c, List.of(row(2174))), q, TENANT, TASK)) {
            consume(b);
          }
          cost[0] = q.resources().operations() - before;
          return null;
        });
    System.out.println("CANDIDATE_LOCAL_TOTAL " + cost[0]);
    var oldq = q();
    var oldrow = row("x".repeat(2174));
    long oldsum;
    try (var b = original(oldq, oldrow)) {
      oldsum = consume(b);
    }
    long oldcost = oldq.resources().operations();
    var checkf = new Fixture();
    var checkq = q();
    var checkc = config(checkf, xml());
    long newsum =
        owned(
            checkf,
            checkq,
            () -> {
              try (var b =
                  SessionHistorySizedMetadataBatch.load(
                      session(checkf, checkq, checkc, List.of(row(2174))), checkq, TENANT, TASK)) {
                return consume(b);
              }
            });
    assertEquals(oldsum, newsum);
    // Compare real costs below, without assuming the added lifecycle validation is cheaper.
    // Scalar equality and both operation/scratch refusal boundaries remain acceptance checks.
    System.out.println(
        "ORIGINAL_LOCAL_TOTAL "
            + oldcost
            + " CANDIDATE "
            + cost[0]
            + " DELTA "
            + (oldcost - cost[0])
            + " SAME_SCALAR_SUM "
            + newsum);
    for (int delta : List.of(-1, 0, 1)) {
      var fx = new Fixture();
      var qx = q();
      var cx = config(fx, xml());
      if (delta < 0)
        assertThrows(
            RuntimeException.class,
            () ->
                owned(
                    fx,
                    qx,
                    () -> {
                      qx.operation(65536 - qx.resources().operations() - cost[0] - delta);
                      try (var b =
                          SessionHistorySizedMetadataBatch.load(
                              session(fx, qx, cx, List.of(row(2174))), qx, TENANT, TASK)) {
                        consume(b);
                      }
                      return null;
                    }));
      else
        owned(
            fx,
            qx,
            () -> {
              qx.operation(65536 - qx.resources().operations() - cost[0] - delta);
              try (var b =
                  SessionHistorySizedMetadataBatch.load(
                      session(fx, qx, cx, List.of(row(2174))), qx, TENANT, TASK)) {
                consume(b);
              }
              return null;
            });
    }
    System.out.println("OP_BOUNDARIES " + (cost[0] - 1) + "/" + cost[0] + "/" + (cost[0] + 1));
  }

  @Test
  void crossBatchMarkerAndScratchBoundaries() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    assertThrows(
        RuntimeException.class,
        () ->
            owned(
                f,
                q,
                () -> {
                  try (var a =
                          SessionHistorySizedMetadataBatch.load(
                              session(f, q, c, List.of(row(4))), q, TENANT, TASK);
                      var b =
                          SessionHistorySizedMetadataBatch.load(
                              session(f, q, c, List.of(row(4))), q, TENANT, TASK)) {
                    var marker = a.iterator().next().value("binding_snapshot");
                    var first = assertThrows(RuntimeException.class, () -> b.scalarBytes(marker));
                    assertSame(q.recordedFailure(), first);
                  }
                  return null;
                }));
    var measuref = new Fixture();
    var measureq = q();
    var measurec = config(measuref, xml());
    long[] needed = {0};
    owned(
        measuref,
        measureq,
        () -> {
          long live = measureq.scratchLive();
          try (var b =
              SessionHistorySizedMetadataBatch.load(
                  session(measuref, measureq, measurec, List.of(row(2174))),
                  measureq,
                  TENANT,
                  TASK)) {
            consume(b);
          }
          needed[0] = measureq.scratchPeak() - live;
          return null;
        });
    for (int delta : List.of(-1, 0, 1)) {
      var fx = new Fixture();
      var qx = q();
      var cx = config(fx, xml());
      java.util.function.Supplier<String> work =
          () -> {
            Object block = new Object();
            qx.retainScratch(block, 1048576 - qx.scratchLive() - needed[0] - delta);
            try {
              try (var b =
                  SessionHistorySizedMetadataBatch.load(
                      session(fx, qx, cx, List.of(row(2174))), qx, TENANT, TASK)) {
                consume(b);
              }
            } finally {
              qx.releaseScratch(block);
            }
            return "ok";
          };
      if (delta < 0) assertThrows(RuntimeException.class, () -> owned(fx, qx, work));
      else assertEquals("ok", owned(fx, qx, work));
      assertEquals(65536, qx.scratchLive());
    }
    System.out.println(
        "SCRATCH_ADDITIONAL_BOUNDARIES "
            + (needed[0] - 1)
            + "/"
            + needed[0]
            + "/"
            + (needed[0] + 1));
  }

  @Test
  void firstFailureSqlCauseAndOutsideWork() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    var sql = new SQLException("provider", "42000", 123);
    var failure = new org.springframework.dao.DataAccessResourceFailureException("sql", sql);
    var cleanup = new SQLException("cleanup", "08003", 124);
    failure.addSuppressed(cleanup);
    var delegate = session(f, q, c, List.of(row(4)));
    var s =
        proxy(
            SqlSession.class,
            (p, m, a) -> {
              if (m.getName().equals("selectList")) throw failure;
              return m.invoke(delegate, a);
            });
    var first =
        assertThrows(
            RuntimeException.class,
            () ->
                owned(
                    f,
                    q,
                    () -> {
                      assertSame(
                          failure,
                          assertThrows(
                              RuntimeException.class,
                              () -> SessionHistorySizedMetadataBatch.load(s, q, TENANT, TASK)));
                      assertSame(
                          failure, assertThrows(RuntimeException.class, () -> q.operation(65536)));
                      return "caught";
                    }));
    assertSame(failure, first);
    assertSame(sql, first.getCause());
    assertSame(cleanup, first.getSuppressed()[0]);
    assertEquals("42000", ((SQLException) first.getCause()).getSQLState());
    assertEquals(65536, q.scratchLive());
    var f2 = new Fixture();
    var q2 = q();
    var c2 = config(f2, xml());
    var leaked =
        owned(
            f2,
            q2,
            () ->
                SessionHistorySizedMetadataBatch.load(
                    session(f2, q2, c2, List.of(row(4))), q2, TENANT, TASK));
    assertThrows(RuntimeException.class, leaked::iterator);
    assertEquals(
        65536, q2.scratchLive(), "ended Work access must release rows before poisonProof refuses");
    leaked.close();
    assertEquals(65536, q2.scratchLive());
    System.out.println("SQL_CAUSE_AND_ENDED_WORK_REJECTED");
  }

  @Test
  void freshRowsAndNullPresenceAndSmallInputCosts() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    var input = row(4);
    var s = session(f, q, c, List.of(input));
    owned(
        f,
        q,
        () -> {
          try (var a = SessionHistorySizedMetadataBatch.load(s, q, TENANT, TASK)) {
            assertEquals(27L, a.iterator().next().value("next_seq"));
          }
          input.put("next_seq", 28L);
          input.put("binding_snapshot", 5L);
          input.remove("cleanup_receipt");
          try (var b = SessionHistorySizedMetadataBatch.load(s, q, TENANT, TASK)) {
            var r = b.iterator().next();
            assertEquals(28L, r.value("next_seq"));
            assertEquals(5, b.scalarBytes(r.value("binding_snapshot")));
            int count = 0;
            for (var v : r.values()) count++;
            assertEquals(13, count);
          }
          return null;
        });
    for (String text : List.of("", "😀", "汉字\u0000  ", "x".repeat(27), "x".repeat(2174))) {
      long len = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      var oldq = q();
      long oldsum;
      try (var b = original(oldq, row(text))) {
        oldsum = consume(b);
      }
      var fx = new Fixture();
      var qx = q();
      var cx = config(fx, xml());
      long newsum =
          owned(
              fx,
              qx,
              () -> {
                try (var b =
                    SessionHistorySizedMetadataBatch.load(
                        session(fx, qx, cx, List.of(row(len))), qx, TENANT, TASK)) {
                  return consume(b);
                }
              });
      assertEquals(oldsum, newsum);
      System.out.println(
          "COST_MATRIX utf16="
              + text.length()
              + " utf8="
              + len
              + " original="
              + oldq.resources().operations()
              + " candidate="
              + qx.resources().operations()
              + " plusRoute=16");
    }
  }

  @Test
  void negotiatedCharsetMustMatch() throws Exception {
    for (String bad : List.of("latin1", "utf8mb3", "unknown")) {
      var f = new Fixture();
      f.charset = bad;
      var q = q();
      var c = config(f, xml());
      assertThrows(
          RuntimeException.class,
          () ->
              owned(
                  f,
                  q,
                  () ->
                      SessionHistorySizedMetadataBatch.load(
                          session(f, q, c, List.of(row(4))), q, TENANT, TASK)));
      assertEquals(0, f.base.commits);
      assertEquals(1, f.base.rollbacks);
      assertEquals(0, q.resources().reads());
    }
    var f = new Fixture();
    f.properties
        .getStringProperty(com.mysql.cj.conf.PropertyKey.characterEncoding)
        .setValue("ISO-8859-1");
    var q = q();
    var c = config(f, xml());
    assertThrows(
        RuntimeException.class,
        () ->
            owned(
                f,
                q,
                () ->
                    SessionHistorySizedMetadataBatch.load(
                        session(f, q, c, List.of(row(4))), q, TENANT, TASK)));
    System.out.println("CHARSET_REJECTIONS 4 before SELECT");
  }

  @Test
  void errorDuringProviderReleasesOwnerAndPreventsCaughtCommit() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    var original = session(f, q, c, List.of(row(4)));
    var error = new AssertionError("provider error");
    var s =
        proxy(
            SqlSession.class,
            (p, m, a) -> {
              if (m.getName().equals("selectList")) throw error;
              return m.invoke(original, a);
            });
    var first =
        assertThrows(
            RuntimeException.class,
            () ->
                owned(
                    f,
                    q,
                    () -> {
                      assertSame(
                          error,
                          assertThrows(
                              AssertionError.class,
                              () -> SessionHistorySizedMetadataBatch.load(s, q, TENANT, TASK)));
                      return "caught";
                    }));
    assertSame(error, first.getCause());
    assertEquals(0, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, q.scratchLive());
  }
}
