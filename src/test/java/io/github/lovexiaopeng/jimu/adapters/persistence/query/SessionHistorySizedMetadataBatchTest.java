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
  private static final String LANGUAGE =
      " lang=\"io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistoryMetadataLanguageDriver\"";

  @Test
  void finalJointConsumptionKeepsStrictColumnTypesAndPresence() throws Exception {
    var valid = row(0L);
    for (var column : valid.keySet()) {
      if (column.equals("cleanup_receipt")) continue;
      for (String mode : List.of("absent", "null", "wrong")) {
        var values = row(0L);
        if (mode.equals("absent")) values.remove(column);
        else if (mode.equals("null")) values.put(column, null);
        else {
          Object before = values.get(column);
          Object wrong = Integer.valueOf(1);
          // No ternary numeric promotion: Integer and Long remain distinct objects.
          if (before instanceof Integer) wrong = Long.valueOf(1);
          if (column.equals("binding_snapshot")) wrong = "0";
          values.put(column, wrong);
        }
        assertFinalRejected(values);
      }
    }
    assertFinalRejected(row(-1L));
    assertFinalRejected(row(65536L));
    var extra = row(1L);
    extra.put("unexpected", null);
    assertFinalRejected(extra);
  }

  private void assertFinalRejected(Map<String, Object> values) throws Exception {
    var f = new Fixture();
    var query = q();
    var configuration = config(f, xml());
    assertThrows(
        RuntimeException.class,
        () ->
            owned(
                f,
                query,
                () ->
                    SessionHistorySizedMetadataBatch.measureFinal(
                        session(f, query, configuration, List.of(values)), query, TENANT, TASK)));
    assertEquals(0, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void finalJointConsumptionKeepsUnicodeAndUtf16Bounds() throws Exception {
    for (String text : List.of("", "汉\u0000  ", "😀".repeat(64), "x".repeat(64))) {
      var f = new Fixture();
      var query = q();
      var configuration = config(f, xml());
      var values = row(0);
      values.put("binding_digest", text);
      var actual =
          owned(
              f,
              query,
              () ->
                  SessionHistorySizedMetadataBatch.measureFinal(
                      session(f, query, configuration, List.of(values)), query, TENANT, TASK));
      long expected = 32;
      for (var e : values.entrySet())
        expected +=
            8
                + (e.getKey().equals("binding_snapshot")
                    ? 0
                    : SessionQueryContext.scalarBytes(e.getValue()));
      assertEquals(expected, actual.bytes());
      assertEquals(65536, query.scratchLive());
    }
    for (String text :
        List.of("x".repeat(65), "😀".repeat(63) + "xx", "\ud800", "\udc00", "a\ud800x")) {
      var values = row(0L);
      values.put("binding_digest", text);
      assertFinalRejected(values);
    }
    // The complete SQL/FINAL path rejects these large texts at its operation limit first.
    // Exercise the independent UTF16 cap directly, without widening that Work's budget.
    var constructor = SessionHistorySizedMetadataBatch.class.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    var batch = constructor.newInstance(null, null);
    var scalar =
        SessionHistorySizedMetadataBatch.class.getDeclaredMethod(
            "finalText", Object.class, int.class, int.class);
    scalar.setAccessible(true);
    assertEquals(131069L, scalar.invoke(batch, "😀".repeat(32767) + "x", 65535, 65535));
    var rejected =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> scalar.invoke(batch, "😀".repeat(32768), 65535, 65535));
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        ((io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure) rejected.getCause()).code());
  }

  @Test
  void finalJointConsumptionKeepsExtremeScalarsAndFreshReads() throws Exception {
    var f = new Fixture();
    var query = q();
    var configuration = config(f, xml());
    var values = row(65535L);
    values.put("record_version", Integer.MIN_VALUE);
    values.put("history_version", Integer.MAX_VALUE);
    values.put("metadata_bytes", Long.MIN_VALUE);
    values.put("body_bytes", Long.MAX_VALUE);
    owned(
        f,
        query,
        () -> {
          for (int year : List.of(-1, 0, 9999, 10000)) {
            values.put("retain_until", java.time.LocalDateTime.of(year, 1, 1, 0, 0, 0, 123456789));
            var actual =
                SessionHistorySizedMetadataBatch.measureFinal(
                    session(f, query, configuration, List.of(values)), query, TENANT, TASK);
            long expected = 32;
            for (var e : values.entrySet())
              expected +=
                  8
                      + (e.getKey().equals("binding_snapshot")
                          ? ((Number) e.getValue()).longValue()
                          : SessionQueryContext.scalarBytes(e.getValue()));
            assertEquals(expected, actual.bytes());
            assertEquals(Long.MIN_VALUE, actual.history().metadata());
            assertEquals(20, actual.history().metadataScalarBytes());
            values.put("binding_snapshot", 123L);
            values.put("reserved_bytes", (long) year);
          }
          return null;
        });
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void finalJointConsumptionErrorKeepsFirstFailureAndReleasesRows() throws Exception {
    var f = new Fixture();
    var query = q();
    var configuration = config(f, xml());
    var error = new AssertionError("history fold getter");
    error.addSuppressed(new IllegalStateException("original suppressed"));
    var delegate = session(f, query, configuration, List.of(row(1L)));
    var exploding =
        new LinkedHashMap<String, Object>(row(1L)) {
          @Override
          public Object get(Object key) {
            if (key.equals("metadata_bytes")) throw error;
            return super.get(key);
          }
        };
    var source =
        proxy(
            SqlSession.class,
            (p, method, args) -> {
              Object result = method.invoke(delegate, args);
              return method.getName().equals("selectList") ? List.of(exploding) : result;
            });
    var first =
        assertThrows(
            RuntimeException.class,
            () ->
                owned(
                    f,
                    query,
                    () -> {
                      assertSame(
                          error,
                          assertThrows(
                              AssertionError.class,
                              () ->
                                  SessionHistorySizedMetadataBatch.measureFinal(
                                      source, query, TENANT, TASK)));
                      return null;
                    }));
    assertSame(error, first.getCause());
    assertEquals(1, error.getSuppressed().length);
    assertEquals(0, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void frozenSecondFinalHistoryKeepsSummaryAndRemovesStaticScan() throws Exception {
    // R37 原 observer ordinal=2 的完整 history 行；无 SQL/provider/权限缓存。
    String tenant = "tenant-a", task = "0ccef5d9-1380-420d-be9a-7d23d7e495cb";
    long[] costs = new long[2];
    SessionMetadataAccounting.FinalTable expected = null;
    for (int pinned = 0; pinned < 2; pinned++) {
      var f = new Fixture();
      var query = q();
      var configuration = config(f, pinned == 0 ? xml().replace(LANGUAGE, "") : xml());
      var values = row(304L);
      values.put("tenant_id", tenant);
      values.put("task_id", task);
      values.put("history_version", 5);
      values.put(
          "binding_digest", "8f1637c630973253968efee96cbf8640de5f12c836ad8a1e2bfc86d34134f3d9");
      values.put("next_seq", 1L);
      values.put("body_bytes", 2923L);
      values.put("metadata_bytes", 0L);
      values.put("reserved_bytes", 8388608L);
      values.put("held_bytes", 8388608L);
      values.put("retain_until", java.time.LocalDateTime.parse("2026-11-07T14:25:27.752398"));
      var result =
          f.transaction.required(
              query,
              () -> {
                SessionQueryTransaction.current(query).locked(tenant, task);
                return SessionHistorySizedMetadataBatch.measureFinal(
                    session(f, query, configuration, List.of(values), tenant), query, tenant, task);
              });
      if (expected != null) assertEquals(expected, result);
      expected = result;
      costs[pinned] = query.resources().operations();
      assertEquals(65536, query.scratchLive());
      assertEquals(1, f.base.commits);
    }
    assertEquals(SessionHistorySizedMetadataBatch.SQL.length() + 28L, costs[0] - costs[1]);
    System.out.println(
        "HISTORY_FINAL_FROZEN ordinary="
            + costs[0]
            + " initialized="
            + costs[1]
            + " saved="
            + (costs[0] - costs[1]));
  }

  @Test
  void initializedSourceRemovesOnlyStaticWorkForEmptyAndPopulatedFinalHistory() throws Exception {
    for (boolean populated : List.of(false, true)) {
      long[] costs = new long[2];
      SessionMetadataAccounting.FinalTable prior = null;
      for (int pinned = 0; pinned < 2; pinned++) {
        var f = new Fixture();
        var query = q();
        var configuration = config(f, pinned == 0 ? xml().replace(LANGUAGE, "") : xml());
        var source =
            configuration
                .getMappedStatement(SessionHistorySizedMetadataBatch.STATEMENT)
                .getSqlSource();
        assertEquals(pinned == 1, SessionHistoryMetadataLanguageDriver.owns(source));
        var result =
            owned(
                f,
                query,
                () ->
                    SessionHistorySizedMetadataBatch.measureFinal(
                        session(f, query, configuration, populated ? List.of(row(27L)) : List.of()),
                        query,
                        TENANT,
                        TASK));
        if (prior != null) assertEquals(prior, result);
        prior = result;
        costs[pinned] = query.resources().operations();
        assertEquals(65536, query.scratchLive());
        assertEquals(1, f.base.commits);
      }
      assertEquals(SessionHistorySizedMetadataBatch.SQL.length() + 28L, costs[0] - costs[1]);
      System.out.println(
          "HISTORY_FINAL_SOURCE populated="
              + populated
              + " ordinary="
              + costs[0]
              + " initialized="
              + costs[1]
              + " saved="
              + (costs[0] - costs[1]));
    }
  }

  @Test
  void initializedSourceKeepsParametersImmutableAndRejectsChangedMapping() throws Exception {
    var f = new Fixture();
    var configuration = config(f, xml());
    var source =
        configuration.getMappedStatement(SessionHistorySizedMetadataBatch.STATEMENT).getSqlSource();
    var firstArgs = Map.of("tenant", TENANT, "taskId", TASK);
    var secondArgs = Map.of("tenant", "another", "taskId", "new-task");
    assertSame(firstArgs, source.getBoundSql(firstArgs).getParameterObject());
    assertSame(secondArgs, source.getBoundSql(secondArgs).getParameterObject());
    assertThrows(
        UnsupportedOperationException.class,
        () -> source.getBoundSql(firstArgs).getParameterMappings().clear());
    var changed = config(f, xml().replace("tenant_id=#{tenant}", "tenant_id=#{foreign}"));
    assertFalse(
        SessionHistoryMetadataLanguageDriver.owns(
            changed.getMappedStatement(SessionHistorySizedMetadataBatch.STATEMENT).getSqlSource()));
    var query = q();
    assertThrows(
        RuntimeException.class,
        () ->
            owned(
                f,
                query,
                () ->
                    SessionHistorySizedMetadataBatch.measureFinal(
                        session(f, query, changed, List.of(row(27L))), query, TENANT, TASK)));
    assertEquals(0, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void mapperParsedInsideWorkKeepsOriginalValidationAndPaysDispatch() throws Exception {
    var f = new Fixture();
    var query = q();
    owned(
        f,
        query,
        () -> {
          Configuration configuration;
          try {
            configuration = config(f, xml());
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
          assertFalse(
              SessionHistoryMetadataLanguageDriver.owns(
                  configuration
                      .getMappedStatement(SessionHistorySizedMetadataBatch.STATEMENT)
                      .getSqlSource()));
          return SessionHistorySizedMetadataBatch.measureFinal(
              session(f, query, configuration, List.of(row(27L))), query, TENANT, TASK);
        });
    assertEquals(1, f.base.commits);
    assertEquals(65536, query.scratchLive());
  }

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
    return session(f, q, c, inputs, TENANT);
  }

  private SqlSession session(
      Fixture f,
      SessionQueryContext q,
      Configuration c,
      List<Map<String, Object>> inputs,
      String expectedTenant) {
    return proxy(
        SqlSession.class,
        (p, m, a) ->
            switch (m.getName()) {
              case "getConfiguration" -> c;
              case "getConnection" -> f.connection;
              case "selectList" -> {
                assertEquals(SessionHistorySizedMetadataBatch.STATEMENT, a[0]);
                assertSame(q, ((Map<?, ?>) a[1]).get("query"));
                assertEquals(expectedTenant, ((Map<?, ?>) a[1]).get("tenant"));
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
  void valuesNextPaysOneGuardAndCaughtClosedAttemptStillRollsBack() throws Exception {
    var f = new Fixture();
    var q = q();
    var c = config(f, xml());
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                owned(
                    f,
                    q,
                    () -> {
                      try (var batch =
                          SessionHistorySizedMetadataBatch.load(
                              session(f, q, c, List.of(row(2174))), q, TENANT, TASK)) {
                        var values = batch.iterator().next().values().iterator();
                        long before = q.resources().operations();
                        assertEquals(TENANT, values.next());
                        assertEquals(62, q.resources().operations() - before);
                        batch.close();
                        assertThrows(RuntimeException.class, values::next);
                        return "caught";
                      }
                    }));
    assertSame(q.recordedFailure(), failure);
    assertEquals(0, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, q.scratchLive());
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

  @Test
  void finalHistoryMeasuresOriginalValuesAndDynamicCountersWithoutPublicFacades() throws Exception {
    for (String mode : List.of("full", "absent", "null", "wide")) {
      var f = new Fixture();
      var q = q();
      var c = config(f, xml());
      var values = row(65535L);
      values.put("reserved_bytes", 123456L);
      values.put("held_bytes", 987654L);
      if (mode.equals("absent")) values.remove("cleanup_receipt");
      if (mode.equals("wide")) {
        values.put("cleanup_receipt", "积木🧱");
        values.put("metadata_bytes", Long.MIN_VALUE);
        values.put("body_bytes", Long.MAX_VALUE);
        values.put("retain_until", java.time.LocalDateTime.of(-1, 1, 1, 0, 0));
      }
      long expected = 32;
      for (var e : values.entrySet())
        expected +=
            8
                + (e.getKey().equals("binding_snapshot")
                    ? 65535
                    : SessionQueryContext.scalarBytes(e.getValue()));
      var result =
          owned(
              f,
              q,
              () ->
                  SessionHistorySizedMetadataBatch.measureFinal(
                      session(f, q, c, List.of(values)), q, TENANT, TASK));
      assertEquals(expected, result.bytes());
      assertEquals(1, result.histories());
      assertEquals(values.get("metadata_bytes"), result.history().metadata());
      assertEquals(values.get("body_bytes"), result.history().body());
      assertEquals(123456L, result.history().reserved());
      assertEquals(987654L, result.history().held());
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void finalHistoryFailureAndProviderErrorPoisonCaughtWorkAndReleaseBootstrap() throws Exception {
    for (String mode :
        List.of("length", "extra", "missing", "type", "receipt", "error", "foreign")) {
      var f = new Fixture();
      var q = q();
      var c = config(f, xml());
      var values = row(27L);
      switch (mode) {
        case "length" -> values.put("binding_snapshot", 65536L);
        case "extra" -> values.put("unknown", null);
        case "missing" -> values.remove("held_bytes");
        case "type" -> values.put("metadata_bytes", 1);
        case "receipt" -> values.put("cleanup_receipt", "\ud800");
        default -> {}
      }
      var delegate = session(f, q, c, List.of(values));
      var error = new AssertionError("final metadata provider");
      var source =
          proxy(
              SqlSession.class,
              (p, m, a) -> {
                if (mode.equals("error") && m.getName().equals("selectList")) throw error;
                try {
                  return m.invoke(delegate, a);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
      assertThrows(
          RuntimeException.class,
          () ->
              owned(
                  f,
                  q,
                  () -> {
                    if (mode.equals("error"))
                      assertSame(
                          error,
                          assertThrows(
                              AssertionError.class,
                              () ->
                                  SessionHistorySizedMetadataBatch.measureFinal(
                                      source, q, TENANT, TASK)));
                    else
                      assertThrows(
                          RuntimeException.class,
                          () ->
                              SessionHistorySizedMetadataBatch.measureFinal(
                                  source, mode.equals("foreign") ? q() : q, TENANT, TASK));
                    return null;
                  }));
      assertEquals(0, f.base.commits);
      assertEquals(1, f.base.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }
}
