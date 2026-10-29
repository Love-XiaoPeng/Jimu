package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 真实MyBatis/拦截器/provider接线，脚本JDBC只提供行；真实业务SQL由独立私库批验证。 */
class SessionHistoryLengthTest {
  static final String[] LABELS = {
    "tenant_id", "task_id", "record_version", "history_version", "binding_snapshot",
    "binding_digest", "content_state", "next_seq", "body_bytes", "metadata_bytes",
    "reserved_bytes", "held_bytes", "retain_until", "cleanup_receipt", "retained"
  };

  static class Fixture {
    final SessionQueryTransactionTest.Fixture database = new SessionQueryTransactionTest.Fixture();
    final Connection connection;
    final SqlSessionTemplate sql;
    final SessionQueryTransaction transaction;
    Object[] row = {
      "t",
      "x",
      1L,
      5L,
      "x".repeat(2174),
      "d".repeat(64),
      "PRESENT",
      1L,
      0L,
      0L,
      8388608L,
      8388608L,
      java.sql.Timestamp.valueOf("2026-10-30 00:00:00.000000"),
      null,
      1L
    };
    boolean missing, forceEmptySize, nullSize, extraSize, extraRaw;
    boolean lateBindingLabel, rawColumnRead;
    Object overrideSize;
    String[] labels = LABELS;
    String failAt;
    final SQLException driver = new SQLException("private history provider failure", "HY099");
    int sizeQueries, rawQueries, closed;

    Fixture() throws Exception {
      connection =
          proxy(
              Connection.class,
              (p, m, a) -> {
                if (m.getName().equals("prepareStatement")) {
                  boolean size = ((String) a[0]).contains(" AS binding_snapshot_utf8_bytes");
                  if ((size ? "size-prepare" : "raw-prepare").equals(failAt)) throw driver;
                  if (size) sizeQueries++;
                  else rawQueries++;
                  var result = rows(size);
                  return proxy(
                      PreparedStatement.class,
                      (s, method, args) ->
                          switch (method.getName()) {
                            case "execute" -> true;
                            case "getResultSet" -> result;
                            case "getMoreResults", "isClosed" -> false;
                            case "getUpdateCount" -> -1;
                            case "getConnection" -> p;
                            case "hashCode" -> System.identityHashCode(s);
                            case "equals" -> s == args[0];
                            default -> defaultValue(method.getReturnType());
                          });
                }
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                return call(m, database.connection, a);
              });
      var source =
          new AbstractDataSource() {
            public Connection getConnection() {
              return connection;
            }

            public Connection getConnection(String user, String password) {
              return connection;
            }
          };
      var configuration = new MybatisConfiguration();
      LedgerMapperSessions.configure(configuration);
      configuration.setEnvironment(
          new Environment("history-provider", new SpringManagedTransactionFactory(), source));
      configuration.addInterceptor(new SessionQueryInterceptor());
      try (var xml =
          getClass().getResourceAsStream("/mapper/session/SessionHistoryRetentionMapper.xml")) {
        new XMLMapperBuilder(xml, configuration, "history.xml", configuration.getSqlFragments())
            .parse();
      }
      sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
      transaction = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    }

    ResultSet rows(boolean size) throws Exception {
      var data = javax.sql.rowset.RowSetProvider.newFactory().createCachedRowSet();
      var metadata = new javax.sql.rowset.RowSetMetaDataImpl();
      var names = size ? new String[] {"binding_snapshot_utf8_bytes"} : labels;
      Object bytes =
          nullSize
              ? null
              : overrideSize != null
                  ? overrideSize
                  : row[4] == null
                      ? Integer.valueOf(1)
                      : Long.valueOf(
                          ((String) row[4])
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                              .length);
      Object[] values = size ? new Object[] {bytes} : row;
      metadata.setColumnCount(names.length);
      for (int i = 1; i <= names.length; i++) {
        metadata.setColumnName(i, names[i - 1]);
        metadata.setColumnLabel(i, names[i - 1]);
        metadata.setColumnType(
            i,
            values[i - 1] instanceof java.sql.Timestamp
                ? Types.TIMESTAMP
                : values[i - 1] instanceof Integer
                    ? Types.INTEGER
                    : values[i - 1] instanceof Long
                        ? Types.BIGINT
                        : values[i - 1] instanceof Double ? Types.DOUBLE : Types.VARCHAR);
      }
      data.setMetaData(metadata);
      boolean empty =
          missing || size && (forceEmptySize || !"PRESENT".equals(row[6]) || row[13] != null);
      if (!empty) {
        int copies = (size ? extraSize : extraRaw) ? 2 : 1;
        for (int copy = 0; copy < copies; copy++) {
          data.moveToInsertRow();
          for (int i = 1; i <= names.length; i++) data.updateObject(i, values[i - 1]);
          data.insertRow();
          data.moveToCurrentRow();
        }
      }
      data.beforeFirst();
      return proxy(
          ResultSet.class,
          (p, m, a) -> {
            String prefix = size ? "size-" : "raw-";
            if (m.getName().equals("isClosed")) return false;
            if ((prefix + m.getName()).equals(failAt)) throw driver;
            if (!size && lateBindingLabel && m.getName().equals("getMetaData"))
              return proxy(
                  java.sql.ResultSetMetaData.class,
                  (metadataProxy, method, args) -> {
                    if (rawColumnRead
                        && method.getName().equals("getColumnLabel")
                        && args[0].equals(5)) return "binding_snapshoX";
                    return call(method, metadata, args);
                  });
            if (!size && m.getName().equals("getObject")) rawColumnRead = true;
            if (m.getName().equals("close")) closed++;
            return call(m, data, a);
          });
    }

    List<Map<String, Object>> load(SessionQueryContext q) {
      return SessionHistoryRows.load(sql, q, "t", "x");
    }

    <T> T work(SessionQueryContext q, java.util.function.Supplier<T> action) {
      return transaction.required(
          q,
          () -> {
            SessionQueryTransaction.current(q).locked("t", "x");
            return action.get();
          });
    }
  }

  static Object call(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  static Object defaultValue(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    return null;
  }

  static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  @Test
  void fifteenColumnsKeepRawBytesAndDynamicValuesForNullUnicodeAndAscii() throws Exception {
    for (String binding :
        new String[] {null, "", "abc", "中😀", "x\u0000尾  ", "x".repeat(2174), "中".repeat(1000)}) {
      var f = new Fixture();
      f.row[4] = binding;
      var q = SessionEventHistoryLengthTest.query();
      f.work(
          q,
          () -> {
            var rows = f.load(q);
            assertEquals(15, rows.getFirst().size());
            assertEquals(binding, rows.getFirst().get("binding_snapshot"));
            assertEquals(1L, rows.getFirst().get("next_seq"));
            var oracle = SessionEventHistoryLengthTest.query();
            try (var result = oracle.result(SessionHistoryRows.RAW)) {
              result.row();
              for (int i = 0; i < LABELS.length; i++) {
                Object value = f.row[i];
                if (value instanceof java.sql.Timestamp t) value = t.toLocalDateTime();
                result.column(LABELS[i], value);
              }
            }
            long size =
                binding == null
                    ? 1
                    : binding.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long sizeProof =
                8 + "binding_snapshot_utf8_bytes".length() + Long.toString(size).length();
            assertEquals(oracle.resources().bytes() + sizeProof, q.resources().bytes());
            assertEquals(2, q.resources().reads());
            assertTrue(q.scratchLive() > 65536);
            return null;
          });
      assertEquals(65536, q.scratchLive());
      assertEquals(2, f.closed);
    }
  }

  @Test
  void fourDistinctRawOwnersSurviveWritesAndFifthFallsBackWithoutReusingDynamicRows()
      throws Exception {
    var f = new Fixture();
    var q = SessionEventHistoryLengthTest.query();
    var kept = new ArrayList<List<Map<String, Object>>>();
    f.work(
        q,
        () -> {
          long previous = 65536;
          for (int i = 1; i <= 5; i++) {
            f.row[7] = (long) i;
            var rows = f.load(q);
            assertEquals((long) i, rows.getFirst().get("next_seq"));
            for (var prior : kept) assertNotSame(prior.getFirst(), rows.getFirst());
            if (i <= 4) {
              assertTrue(q.scratchLive() > previous);
              previous = q.scratchLive();
              kept.add(rows);
            }
            SessionQueryTransaction.current(q).mutation();
          }
          for (int i = 0; i < kept.size(); i++)
            assertEquals(i + 1L, kept.get(i).getFirst().get("next_seq"));
          assertEquals(4, f.sizeQueries);
          assertEquals(5, f.rawQueries);
          assertEquals(9, q.resources().reads());
          return null;
        });
    for (var rows : kept) assertTrue(rows.isEmpty());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void missingAndPurgedReceiptAlwaysReadTheOriginalRaw() throws Exception {
    for (boolean missing : new boolean[] {false, true}) {
      var f = new Fixture();
      f.missing = missing;
      f.row[6] = "PURGED";
      f.row[13] = "x".repeat(20000);
      var q = SessionEventHistoryLengthTest.query();
      f.work(
          q,
          () -> {
            var rows = f.load(q);
            assertEquals(missing, rows.isEmpty());
            if (!missing) {
              assertEquals(f.row[13], rows.getFirst().get("cleanup_receipt"));
              assertTrue(q.resources().bytes() > 20000);
            }
            assertEquals(2, q.resources().reads());
            assertEquals(1, f.rawQueries);
            return null;
          });
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void disabledAdditionalMeterAndUnownedPathsKeepOneFullRawScan() throws Exception {
    for (String mode : new String[] {"disabled", "additional", "unowned", "legacy"}) {
      var f = new Fixture();
      var q = SessionEventHistoryLengthTest.query();
      if (mode.equals("disabled")) q.disableSqlUtf8Lengths();
      if (mode.equals("legacy"))
        q =
            new SessionQueryContext(
                System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      var query = q;
      long[] columns = {0};
      try (var meter =
          mode.equals("additional") ? q.meter(() -> (label, value) -> columns[0]++) : null) {
        if (mode.equals("unowned")) f.load(q);
        else f.work(q, () -> f.load(query));
      }
      assertEquals(0, f.sizeQueries);
      assertEquals(1, f.rawQueries);
      assertEquals(1, q.resources().reads());
      assertEquals(mode.equals("additional") ? 15 : 0, columns[0]);
    }
  }

  @Test
  void invalidSizeRowsShapesBoundsAndChangedRawPoisonEvenIfCaught() throws Exception {
    for (String mode :
        new String[] {
          "negative",
          "string",
          "double",
          "null",
          "below",
          "above",
          "extra-size",
          "extra-raw",
          "labels",
          "binding-moved",
          "late-binding-label",
          "state",
          "receipt"
        }) {
      var f = new Fixture();
      switch (mode) {
        case "negative" -> f.overrideSize = -1L;
        case "string" -> f.overrideSize = "3";
        case "double" -> f.overrideSize = 3.0;
        case "null" -> f.nullSize = true;
        case "below" -> f.overrideSize = 0L;
        case "above" -> f.overrideSize = 100000L;
        case "extra-size" -> f.extraSize = true;
        case "extra-raw" -> f.extraRaw = true;
        case "labels" -> {
          f.labels = LABELS.clone();
          f.labels[2] = "wrong_version";
        }
        case "binding-moved" -> {
          f.labels = LABELS.clone();
          f.labels[3] = LABELS[4];
          f.labels[4] = LABELS[3];
        }
        case "late-binding-label" -> f.lateBindingLabel = true;
        // Size succeeds, but getObject changes the raw only after the SQL eligibility check.
        default -> f.failAt = mode;
      }
      var q = SessionEventHistoryLengthTest.query();
      var first =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.work(
                      q,
                      () -> {
                        var refusal =
                            assertThrows(
                                RuntimeException.class,
                                () -> {
                                  if (mode.equals("state") || mode.equals("receipt")) {
                                    // A template wrapper changes the scripted raw between the two
                                    // actual selects.
                                    var sql =
                                        new SqlSessionTemplate(f.sql.getSqlSessionFactory()) {
                                          @Override
                                          public <E> List<E> selectList(
                                              String statement, Object args) {
                                            var result = super.<E>selectList(statement, args);
                                            if (statement.equals(SessionHistoryRows.SIZE)) {
                                              if (mode.equals("state")) f.row[6] = "PURGED";
                                              else f.row[13] = "changed";
                                            }
                                            return result;
                                          }
                                        };
                                    SessionHistoryRows.load(sql, q, "t", "x");
                                  } else f.load(q);
                                });
                        assertSame(refusal, q.recordedFailure());
                        return null;
                      }));
      assertSame(first, q.recordedFailure());
      assertEquals(1, f.database.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void driverFailuresAtEachHandoffKeepSqlStateCauseAndRollbackAfterCatch() throws Exception {
    for (String stage :
        new String[] {
          "size-prepare",
          "size-getMetaData",
          "size-next",
          "size-getObject",
          "size-close",
          "raw-prepare",
          "raw-getMetaData",
          "raw-next",
          "raw-getObject",
          "raw-close"
        }) {
      var f = new Fixture();
      f.failAt = stage;
      var q = SessionEventHistoryLengthTest.query();
      var first =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.work(
                      q,
                      () -> {
                        assertThrows(RuntimeException.class, () -> f.load(q));
                        return null;
                      }));
      assertSame(first, q.recordedFailure());
      boolean cause = false;
      for (Throwable t = first; t != null; t = t.getCause()) if (t == f.driver) cause = true;
      assertTrue(cause, stage);
      assertEquals("HY099", f.driver.getSQLState());
      assertEquals(1, f.database.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void wrongStatementKeysConnectionResultAndNewTransactionCannotIssue() throws Exception {
    for (String mode :
        List.of("mapped", "tenant", "task", "query", "connection", "prepared", "result", "owner")) {
      var f = new Fixture();
      var q = SessionEventHistoryLengthTest.query();
      assertThrows(
          RuntimeException.class,
          () ->
              f.work(
                  q,
                  () -> {
                    try {
                      var owner = SessionQueryTransaction.current(q);
                      var slot = owner.beginHistory("t", "x");
                      var params =
                          Map.of(
                              "query",
                              mode.equals("query") ? SessionEventHistoryLengthTest.query() : q,
                              "tenant",
                              mode.equals("tenant") ? "other" : "t",
                              "taskId",
                              mode.equals("task") ? "other" : "x");
                      var prepared =
                          f.connection.prepareStatement(" AS binding_snapshot_utf8_bytes");
                      Runnable attempt =
                          () -> {
                            try {
                              slot.prepared(
                                  mode.equals("mapped")
                                      ? SessionEventHistoryRows.SIZE
                                      : SessionHistoryRows.SIZE,
                                  params,
                                  mode.equals("connection") ? null : f.connection,
                                  prepared);
                              var supplied =
                                  mode.equals("prepared")
                                      ? f.connection.prepareStatement(
                                          " AS binding_snapshot_utf8_bytes")
                                      : prepared;
                              var result =
                                  mode.equals("result") ? f.rows(true) : prepared.getResultSet();
                              try (var wrapped =
                                  QueryResults.meterHistory(
                                      result, q, SessionHistoryRows.SIZE, slot, params, supplied)) {
                                wrapped.next();
                              }
                            } catch (RuntimeException e) {
                              throw e;
                            } catch (Exception e) {
                              throw new IllegalStateException(e);
                            }
                          };
                      if (mode.equals("owner"))
                        f.transaction.requiresNew(
                            q,
                            () -> {
                              attempt.run();
                              return null;
                            });
                      else attempt.run();
                      throw new AssertionError("wrong source accepted: " + mode);
                    } catch (RuntimeException e) {
                      throw SessionQueryTransaction.current(q).poisonProof(e);
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  }));
      assertTrue(q.recordedFailure() != null);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void genuineCapabilitiesCannotCrossHistoryEventHistoryOrRepeatConsumption() throws Exception {
    for (String mode : List.of("event", "repeat", "write")) {
      var f = new Fixture();
      var q = SessionEventHistoryLengthTest.query();
      assertThrows(
          RuntimeException.class,
          () ->
              f.work(
                  q,
                  () -> {
                    try {
                      var owner = SessionQueryTransaction.current(q);
                      var slot = owner.beginHistory("t", "x");
                      var params = Map.of("query", q, "tenant", "t", "taskId", "x");
                      var prepared =
                          f.connection.prepareStatement(" AS binding_snapshot_utf8_bytes");
                      slot.prepared(SessionHistoryRows.SIZE, params, f.connection, prepared);
                      try (var result =
                          QueryResults.meterHistory(
                              prepared.getResultSet(),
                              q,
                              SessionHistoryRows.SIZE,
                              slot,
                              params,
                              prepared)) {
                        assertTrue(result.next());
                        assertFalse(result.next());
                      }
                      var field = slot.getClass().getDeclaredField("capability");
                      field.setAccessible(true);
                      var cap = (SessionQueryContext.SqlUtf8Length) field.get(slot);
                      if (mode.equals("write")) owner.mutation();
                      try (var raw =
                          q.result(
                              mode.equals("event")
                                  ? SessionEventHistoryRows.RAW
                                  : SessionHistoryRows.RAW)) {
                        if (mode.equals("repeat"))
                          raw.columnSqlUtf8("binding_snapshot", f.row[4], cap);
                        var first =
                            assertThrows(
                                IllegalStateException.class,
                                () -> raw.columnSqlUtf8("binding_snapshot", f.row[4], cap));
                        assertSame(first, q.recordedFailure());
                      }
                      return null;
                    } catch (RuntimeException e) {
                      throw e;
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  }));
      assertEquals(1, f.database.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void budgetRefusalsAcrossSlotAndRawConstructionReleaseAllRetainedOwners() throws Exception {
    var measured = new Fixture();
    var measuredQuery = SessionEventHistoryLengthTest.query();
    measured.work(measuredQuery, () -> measured.load(measuredQuery));
    long required = measuredQuery.resources().operations();
    for (long remaining : new long[] {1, 12, 32, 78, 128, 256, 512, 1024, required - 1}) {
      var f = new Fixture();
      var q =
          new SessionQueryContext(
              System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 65536 - remaining));
      q.restrictLineage();
      assertThrows(RuntimeException.class, () -> f.work(q, () -> f.load(q)));
      assertTrue(q.recordedFailure() != null);
      assertEquals(65536, q.scratchLive(), "remaining=" + remaining);
      assertEquals(1, f.database.rollbacks);
    }
  }
}
