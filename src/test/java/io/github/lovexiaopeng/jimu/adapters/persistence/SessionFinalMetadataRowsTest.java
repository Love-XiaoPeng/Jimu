package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryInterceptor;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

/** 真实 MyBatis handler/参数链，确定性 JDBC 行及重入/关闭故障；真实 Connector 另由私库场景覆盖。 */
class SessionFinalMetadataRowsTest {
  private static final String TENANT = "tenant", TASK = "task";

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h);
  }

  @org.apache.ibatis.plugin.Intercepts({
    @org.apache.ibatis.plugin.Signature(
        type = org.apache.ibatis.executor.Executor.class,
        method = "query",
        args = {
          org.apache.ibatis.mapping.MappedStatement.class,
          Object.class,
          org.apache.ibatis.session.RowBounds.class,
          org.apache.ibatis.session.ResultHandler.class
        })
  })
  private static final class Capture implements org.apache.ibatis.plugin.Interceptor {
    SessionFinalMetadataRows.Request request;

    public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      if (invocation.getArgs()[1] instanceof SessionFinalMetadataRows.Request actual)
        request = actual;
      return invocation.proceed();
    }
  }

  private static final class Fixture {
    final SessionQueryTransactionTest.Fixture base = new SessionQueryTransactionTest.Fixture();
    final Connection connection;
    final org.springframework.jdbc.datasource.AbstractDataSource source;
    final SessionQueryTransaction tx;
    final org.mybatis.spring.SqlSessionTemplate session;
    final List<Map<String, Object>> rows = new ArrayList<>();
    final List<String> keys;
    int index = -1, getters, closes, selects;
    boolean closed;
    String mode = "ok", charset = "utf8mb4";
    Runnable atNext = () -> {}, atGetter = () -> {}, atClose = () -> {};
    final AssertionError fatal = new AssertionError("source-error");
    final SQLException closeFailure = new SQLException("close-error");
    final Capture capture = new Capture();

    Fixture(Table table) throws Exception {
      this(table, false);
    }

    Fixture(Table table, boolean changedSql) throws Exception {
      var projection =
          table == Table.TASK
              ? SessionMetadataProjection.taskSized()
              : SessionMetadataProjection.forTable(table);
      keys =
          new ArrayList<>(
              projection.columns.stream().map(SessionMetadataProjection.Column::name).toList());
      var properties = new com.mysql.cj.jdbc.JdbcPropertySetImpl();
      properties
          .getStringProperty(com.mysql.cj.conf.PropertyKey.characterEncoding)
          .setValue("UTF-8");
      var server =
          proxy(
              com.mysql.cj.protocol.ServerSession.class,
              (p, m, a) -> m.getName().equals("getServerVariable") ? charset : null);
      var driver =
          proxy(
              com.mysql.cj.Session.class,
              (p, m, a) -> m.getName().equals("getServerSession") ? server : null);
      var f = SessionQueryTransactionTest.Fixture.class.getDeclaredField("connection");
      f.setAccessible(true);
      var delegate = (Connection) f.get(base);
      connection =
          proxy(
              com.mysql.cj.jdbc.JdbcConnection.class,
              (p, m, a) -> {
                if (m.getName().equals("getPropertySet")) return properties;
                if (m.getName().equals("getSession")) return driver;
                if (m.getName().equals("prepareStatement")) {
                  selects++;
                  return statement();
                }
                try {
                  return m.invoke(delegate, a);
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
      tx =
          new SessionQueryTransaction(
              source, new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
      var c = new Configuration();
      LedgerMapperSessions.configure(c);
      c.setEnvironment(
          new Environment(
              "test",
              new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
              source));
      c.addInterceptor(new SessionQueryInterceptor());
      c.addInterceptor(capture);
      String xml =
          java.nio.file.Files.readString(
              java.nio.file.Path.of(
                  "src/main/resources/mapper/session/SessionHistoryRetentionMapper.xml"));
      if (changedSql)
        xml =
            xml.replace(
                "OCTET_LENGTH(CONVERT(capabilities USING utf8mb4)) AS capabilities",
                "1 AS capabilities");
      try (var in =
          new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
        new XMLMapperBuilder(in, c, "original", c.getSqlFragments()).parse();
      }
      session = new org.mybatis.spring.SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
    }

    private PreparedStatement statement() {
      ResultSetMetaData metadata =
          proxy(
              ResultSetMetaData.class,
              (p, m, a) ->
                  switch (m.getName()) {
                    case "getColumnCount" -> keys.size();
                    case "getColumnLabel" -> keys.get((int) a[0] - 1);
                    default ->
                        throw new AssertionError("Unexpected metadata traversal " + m.getName());
                  });
      ResultSet result =
          proxy(
              ResultSet.class,
              (p, m, a) ->
                  switch (m.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> {
                      atNext.run();
                      yield ++index < rows.size();
                    }
                    case "getObject" -> {
                      atGetter.run();
                      getters++;
                      if (mode.startsWith("error")) throw fatal;
                      if (mode.equals("sql-error")) throw new SQLException("get-error");
                      yield rows.get(index).get(keys.get((int) a[0] - 1));
                    }
                    case "close" -> {
                      closes++;
                      closed = true;
                      atClose.run();
                      if (mode.contains("close-error")) throw closeFailure;
                      yield null;
                    }
                    case "isClosed" -> closed;
                    default ->
                        throw new AssertionError(
                            "Legacy Map getter/mapping must disappear: " + m.getName());
                  });
      return proxy(
          PreparedStatement.class,
          (p, m, a) ->
              switch (m.getName()) {
                case "getResultSet" -> result;
                case "getConnection" -> connection;
                case "execute" -> true;
                case "getMoreResults", "isClosed" -> false;
                case "getUpdateCount" -> -1;
                default -> null;
              });
    }

    SessionMetadataAccounting.FinalTable measure(SessionQueryContext q, Table table) {
      return tx.required(
          q,
          () -> {
            SessionQueryTransaction.current(q).locked(TENANT, TASK);
            return SessionFinalMetadataRows.measure(session, q, table, TENANT, TASK);
          });
    }
  }

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private Map<String, Object> row() {
    var r = new LinkedHashMap<String, Object>();
    r.put("tenant_id", TENANT);
    r.put("id", TASK);
    r.put("task_id", TASK);
    r.put("worker_id", "a😀界");
    r.put("fence", Long.MIN_VALUE);
    r.put("state", "SUCCEEDED");
    r.put("started_at", LocalDateTime.of(10000, 1, 1, 0, 0));
    r.put("ended_at", null);
    r.put("record_version", 1);
    r.put("quiescent", false);
    return r;
  }

  @Test
  void directFoldEqualsOriginalFormulaAndUsesOnlyOneGetterPerColumn() throws Exception {
    for (boolean nulls : List.of(true, false)) {
      var f = new Fixture(Table.TASK_ATTEMPT);
      f.session.getConfiguration().setCallSettersOnNulls(nulls);
      var row = row();
      f.rows.add(row);
      var q = query();
      var summary = f.measure(q, Table.TASK_ATTEMPT);
      long expected = 32, sql = 0;
      for (var entry : row.entrySet()) {
        if (nulls || entry.getValue() != null)
          expected += 8 + SessionMetadataScalars.bytes(entry.getValue(), null);
        sql += 8 + entry.getKey().length() + SessionQueryContext.scalarBytes(entry.getValue());
      }
      assertEquals(expected, summary.bytes());
      assertEquals(1, summary.rows());
      assertEquals(sql, q.resources().bytes());
      assertEquals(row.size(), f.getters);
      assertEquals(1, f.closes);
      assertEquals(1, f.selects);
      assertEquals(1, q.resources().reads());
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void emptyTablesDoNoPerColumnWorkButStillReadAndValidateSource() throws Exception {
    var f = new Fixture(Table.TASK_ATTEMPT);
    var q = query();
    var summary = f.measure(q, Table.TASK_ATTEMPT);
    assertEquals(0, summary.rows());
    assertEquals(0, summary.bytes());
    assertEquals(0, f.getters);
    assertEquals(1, f.closes);
    assertEquals(1, q.resources().reads());
    assertEquals(65536, q.scratchLive());
    var broken = new Fixture(Table.TASK_ATTEMPT);
    broken.charset = "latin1";
    broken.rows.add(row());
    var bad = query();
    assertThrows(RuntimeException.class, () -> broken.measure(bad, Table.TASK_ATTEMPT));
    assertEquals(1, broken.selects);
    assertEquals(65536, bad.scratchLive());
  }

  @Test
  void wrongTypesMissingExtraColumnsAndMalformedUnicodePoisonAndClose() throws Exception {
    for (String mode :
        List.of("type", "missing", "extra", "surrogate", "long-string", "null-required")) {
      var f = new Fixture(Table.TASK_ATTEMPT);
      var row = row();
      switch (mode) {
        case "type" -> row.put("fence", 1);
        case "missing" -> f.keys.remove("ended_at");
        case "extra" -> f.keys.add("foreign");
        case "surrogate" -> row.put("worker_id", "\ud800");
        case "long-string" -> row.put("worker_id", "a".repeat(37));
        case "null-required" -> row.put("state", null);
      }
      f.rows.add(row);
      var q = query();
      assertThrows(RuntimeException.class, () -> f.measure(q, Table.TASK_ATTEMPT), mode);
      assertNotNull(q.recordedFailure());
      assertEquals(0, f.base.commits);
      assertEquals(1, f.base.rollbacks);
      assertTrue(f.closed);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void writeDuringCurrentRowOrAfterResultCloseCannotCommit() throws Exception {
    for (boolean afterClose : List.of(false, true)) {
      var f = new Fixture(Table.TASK_ATTEMPT);
      f.rows.add(row());
      var q = query();
      Runnable write = () -> SessionQueryTransaction.current(q).mutation();
      if (afterClose) f.atClose = write;
      else f.atGetter = write;
      assertThrows(RuntimeException.class, () -> f.measure(q, Table.TASK_ATTEMPT));
      assertEquals(0, f.base.commits);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void sourceErrorAndCloseFailureCannotBeCaughtToCommit() throws Exception {
    var f = new Fixture(Table.TASK_ATTEMPT);
    f.rows.add(row());
    f.mode = "error";
    var q = query();
    assertThrows(AssertionError.class, () -> f.measure(q, Table.TASK_ATTEMPT));
    assertNotNull(q.recordedFailure());
    assertEquals(0, f.base.commits);
    assertEquals(65536, q.scratchLive());
    var close = new Fixture(Table.TASK_ATTEMPT);
    close.rows.add(row());
    close.mode = "close-error";
    var cq = query();
    assertThrows(RuntimeException.class, () -> close.measure(cq, Table.TASK_ATTEMPT));
    assertNotNull(cq.recordedFailure());
    assertEquals(0, close.base.commits);
    assertEquals(65536, cq.scratchLive());
  }

  @Test
  void reservationDynamicValuesUseCurrentRowAndColumnSemantics() throws Exception {
    var f = new Fixture(Table.RESERVATION);
    var r = new LinkedHashMap<String, Object>();
    for (var c : SessionMetadataProjection.forTable(Table.RESERVATION).columns)
      r.put(
          c.name(),
          switch (c.kind()) {
            case 'I' -> 1;
            case 'L' -> 32L;
            default -> "x";
          });
    r.put("category", "SESSION_HISTORY_STORAGE");
    r.put("actual", null);
    r.put("amount", Long.MIN_VALUE);
    r.put("state", "SETTLED");
    f.rows.add(r);
    var summary = f.measure(query(), Table.RESERVATION);
    assertEquals(1, summary.reservations());
    assertEquals(Long.MIN_VALUE, summary.reservation().amount());
    assertNull(summary.reservation().actual());
  }

  @Test
  void closedAndForeignRequestsPoisonCurrentWorkWithoutAnyNewRead() throws Exception {
    var f = new Fixture(Table.TASK_ATTEMPT);
    f.rows.add(row());
    var original = query();
    f.measure(original, Table.TASK_ATTEMPT);
    var stale = f.capture.request;
    int getters = f.getters;
    var other = query();
    assertThrows(
        RuntimeException.class,
        () ->
            f.tx.required(
                other,
                () -> {
                  try {
                    stale.consume(null, null);
                  } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                  }
                  return null;
                }));
    assertNotNull(other.recordedFailure());
    assertEquals(getters, f.getters);
    assertEquals(1, f.base.commits);
    assertEquals(1, f.base.rollbacks);
    assertEquals(65536, original.scratchLive());
    assertEquals(65536, other.scratchLive());
  }

  @Test
  void exactActionBudgetAndTwoRowsKeepFeesAndOwners() throws Exception {
    var baseline = new Fixture(Table.TASK_ATTEMPT);
    baseline.rows.add(row());
    var bq = query();
    baseline.measure(bq, Table.TASK_ATTEMPT);
    long cost = bq.resources().operations();
    for (long allowance : new long[] {cost - 1, cost, cost + 1}) {
      var f = new Fixture(Table.TASK_ATTEMPT);
      f.rows.add(row());
      var q = query();
      q.operation(65536 - allowance);
      if (allowance < cost)
        assertThrows(RuntimeException.class, () -> f.measure(q, Table.TASK_ATTEMPT));
      else assertEquals(1, f.measure(q, Table.TASK_ATTEMPT).rows());
      assertEquals(65536, q.scratchLive());
    }
    var empty = new Fixture(Table.TASK_ATTEMPT);
    var eq = query();
    empty.measure(eq, Table.TASK_ATTEMPT);
    var two = new Fixture(Table.TASK_ATTEMPT);
    two.rows.add(row());
    two.rows.add(row());
    var tq = query();
    var summary = two.measure(tq, Table.TASK_ATTEMPT);
    assertEquals(2, summary.rows());
    assertEquals(2 * baseline.getters, two.getters);
    assertEquals(65536, tq.scratchLive());
    System.out.println(
        "DIRECT_ACTIONS empty="
            + eq.resources().operations()
            + " one="
            + cost
            + " two="
            + tq.resources().operations()
            + " getters="
            + two.getters);
  }

  @Test
  void errorWithCloseFailureRetainsTheOriginalErrorAndSuppression() throws Exception {
    var f = new Fixture(Table.TASK_ATTEMPT);
    f.rows.add(row());
    f.mode = "error-close-error";
    var q = query();
    var error = assertThrows(AssertionError.class, () -> f.measure(q, Table.TASK_ATTEMPT));
    assertTrue(error == f.fatal);
    assertTrue(java.util.Arrays.asList(error.getSuppressed()).contains(f.closeFailure));
    assertNotNull(q.recordedFailure());
    assertEquals(0, f.base.commits);
    assertEquals(65536, q.scratchLive());
  }

  private Map<String, Object> taskRow() {
    var r = new LinkedHashMap<String, Object>();
    for (var c : SessionMetadataProjection.taskSized().columns)
      r.put(
          c.name(),
          c.nullable()
              ? null
              : switch (c.kind()) {
                case 'I' -> 1;
                case 'L', 'T', 'M', 'X' -> 31L;
                case 'D' -> LocalDateTime.of(2026, 1, 1, 0, 0);
                default -> "x";
              });
    r.put("tenant_id", TENANT);
    r.put("id", TASK);
    return r;
  }

  @Test
  void sizedTaskPreservesNumericSqlBytesAndBusinessLengthAndRejectsChangedSql() throws Exception {
    var f = new Fixture(Table.TASK);
    var row = taskRow();
    row.put("model_snapshot", 1642L);
    f.rows.add(row);
    var q = query();
    var result = f.measure(q, Table.TASK);
    long expected = 32, sql = 0;
    for (var c : SessionMetadataProjection.taskSized().columns) {
      var value = row.get(c.name());
      expected +=
          8
              + (value != null && (c.kind() == 'T' || c.kind() == 'M' || c.kind() == 'X')
                  ? ((Number) value).longValue()
                  : SessionMetadataScalars.bytes(value, null));
      sql += 8 + c.name().length() + SessionQueryContext.scalarBytes(value);
    }
    assertEquals(expected, result.bytes());
    assertEquals(sql, q.resources().bytes());
    assertEquals(row.size(), f.getters);
    assertEquals(65536, q.scratchLive());
    var tampered = new Fixture(Table.TASK, true);
    tampered.rows.add(row);
    var bad = query();
    assertThrows(RuntimeException.class, () -> tampered.measure(bad, Table.TASK));
    assertEquals(0, tampered.selects);
    assertEquals(65536, bad.scratchLive());
  }

  @Test
  void sizedTaskRejectsNegativeOversizedAndWrongTypeLengths() throws Exception {
    for (Object length : List.of(-1L, 65536L, 3.0)) {
      var f = new Fixture(Table.TASK);
      var row = taskRow();
      row.put("capabilities", length);
      f.rows.add(row);
      var q = query();
      assertThrows(RuntimeException.class, () -> f.measure(q, Table.TASK));
      assertNotNull(q.recordedFailure());
      assertTrue(f.closed);
      assertEquals(65536, q.scratchLive());
    }
  }
}
