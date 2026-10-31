package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;

/** 本次同 Result 行交接的差分与失效验证；真实 SQL 路径另由冻结两节点业务覆盖。 */
class SessionMetadataScalarSharingTest {
  private static final String STATEMENT =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadataTaskAttempt";

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private Map<String, Object> row(String text, LocalDateTime time) {
    var row = new LinkedHashMap<String, Object>();
    row.put("tenant_id", text);
    row.put("id", "id");
    row.put("task_id", "task");
    row.put("worker_id", "worker");
    row.put("fence", Long.MIN_VALUE);
    row.put("state", "state");
    row.put("started_at", time);
    row.put("ended_at", null);
    row.put("record_version", Integer.MIN_VALUE);
    row.put("quiescent", true);
    return row;
  }

  private SessionMetadataAccounting.FinalTable measure(
      SessionQueryContext q, List<Map<String, Object>> rows, String mode) {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    return SessionCursorMetadataBatch.measureFinal(
        q, Table.TASK_ATTEMPT, p, () -> new MeasuredRows(q, rows, mode));
  }

  private final class MeasuredRows implements Cursor<Map<String, Object>> {
    final SessionQueryContext q;
    final ResultSet result;
    final List<Map<String, Object>> rows;
    final String mode;
    int index;
    Map<String, Object> pending;
    boolean closed;

    MeasuredRows(SessionQueryContext q, List<Map<String, Object>> rows, String mode) {
      this.q = q;
      this.rows = rows;
      this.mode = mode;
      var keys =
          new java.util.ArrayList<>(
              (rows.isEmpty() ? row("empty", LocalDateTime.of(2026, 1, 1, 0, 0)) : rows.getFirst())
                  .keySet());
      int[] at = {-1};
      var metadata =
          (ResultSetMetaData)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ResultSetMetaData.class},
                  (p, m, a) ->
                      switch (m.getName()) {
                        case "getColumnCount" -> keys.size();
                        case "getColumnLabel" ->
                            mode.equals("column") ? "foreign" : keys.get((int) a[0] - 1);
                        default -> null;
                      });
      var raw =
          (ResultSet)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ResultSet.class},
                  (p, m, a) ->
                      switch (m.getName()) {
                        case "getMetaData" -> metadata;
                        case "next" -> ++at[0] < rows.size();
                        case "getObject" -> {
                          if (mode.equals("error") && (int) a[0] == 2)
                            throw new AssertionError("source-error");
                          yield rows.get(at[0]).get(keys.get((int) a[0] - 1));
                        }
                        case "close" -> null;
                        default ->
                            throw new AssertionError("Repeated driver getter: " + m.getName());
                      });
      try {
        result =
            mode.equals("no-sql")
                ? null
                : QueryResults.meter(raw, q, mode.equals("statement") ? "foreign" : STATEMENT);
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    public boolean isOpen() {
      return !closed;
    }

    public boolean isConsumed() {
      return index == rows.size();
    }

    public int getCurrentIndex() {
      return index - 1;
    }

    public void close() {
      closed = true;
      pending = null;
      closeResult();
    }

    private void closeResult() {
      if (result == null) return;
      try {
        result.close();
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    public Iterator<Map<String, Object>> iterator() {
      return new Iterator<>() {
        public boolean hasNext() {
          if (pending != null) return true;
          if (index == rows.size()) {
            if (result != null) {
              try {
                assertFalse(result.next());
              } catch (SQLException failure) {
                throw new IllegalStateException(failure);
              }
            }
            closeResult();
            return false;
          }
          try {
            if (!result.next()) throw new AssertionError("Missing source row");
            var row = new LinkedHashMap<String, Object>();
            for (var e : rows.get(index).entrySet()) {
              Object original = e.getValue();
              Object value;
              if (original instanceof Integer) value = result.getInt(e.getKey());
              else if (original instanceof Long) value = result.getLong(e.getKey());
              else if (original instanceof Boolean) value = result.getBoolean(e.getKey());
              else if (original instanceof String) value = result.getString(e.getKey());
              else if (original instanceof LocalDateTime)
                value = result.getObject(e.getKey(), LocalDateTime.class);
              else value = result.getObject(e.getKey());
              assertEquals(original == null, result.wasNull());
              row.put(e.getKey(), value);
            }
            pending = row;
          } catch (SQLException failure) {
            throw new IllegalStateException(failure);
          }
          return true;
        }

        public Map<String, Object> next() {
          if (!hasNext()) throw new NoSuchElementException();
          var row = pending;
          pending = null;
          index++;
          if (mode.equals("changed-value")) {
            row = new LinkedHashMap<>(row);
            row.put("tenant_id", new String((String) row.get("tenant_id")));
          }
          if (mode.equals("missing-null")) {
            row = new LinkedHashMap<>(row);
            row.remove("ended_at");
          }
          q.metadataMapped(mode.equals("row") ? new LinkedHashMap<>(row) : row);
          if (mode.equals("mutation")) SessionQueryTransaction.currentOwner().mutation();
          if (mode.equals("closed")) closeResult();
          if (mode.equals("foreign")) {
            var other = query();
            new SessionQueryTransactionTest.Fixture()
                .transaction.requiresNew(
                    other,
                    () -> {
                      q.metadataMapped(new Object());
                      return null;
                    });
          }
          return row;
        }
      };
    }
  }

  @Test
  void productionMapperUsesOnlyDefaultMapScalarMapping() throws Exception {
    var configuration = new org.apache.ibatis.session.Configuration();
    io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.configure(
        configuration);
    try (var input =
        getClass().getResourceAsStream("/mapper/session/SessionHistoryRetentionMapper.xml")) {
      new org.apache.ibatis.builder.xml.XMLMapperBuilder(
              input, configuration, "metadata", configuration.getSqlFragments())
          .parse();
    }
    var resultMaps = configuration.getMappedStatement(STATEMENT).getResultMaps();
    assertEquals(1, resultMaps.size());
    assertEquals(Map.class, resultMaps.getFirst().getType());
    assertEquals(List.of(), resultMaps.getFirst().getResultMappings());
  }

  @Test
  void originalSqlAndMetadataFormulasAgreeAcrossScalarBoundariesAndNewRows() {
    for (int year : List.of(0, 2026, 9999, -1, 10000, -999999999, 999999999)) {
      var q = query();
      var first = row("a中😀", LocalDateTime.of(year, 12, 31, 23, 59, 59, 999999999));
      var second = row("another", LocalDateTime.of(2026, 1, 1, 0, 0));
      second.put("quiescent", false);
      var rows = List.of(first, second);
      long expected = 0, wire = 0;
      for (var row : rows) {
        expected += 32;
        for (var e : row.entrySet()) {
          expected += 8 + SessionMetadataScalars.bytes(e.getValue(), null);
          wire +=
              8
                  + SessionQueryContext.scalarBytes(e.getKey())
                  + SessionQueryContext.scalarBytes(e.getValue());
        }
      }
      long wanted = expected, transport = wire;
      var fixture = new SessionQueryTransactionTest.Fixture();
      var finalTable = fixture.transaction.required(q, () -> measure(q, rows, "ok"));
      assertEquals(2, finalTable.rows());
      assertEquals(wanted, finalTable.bytes());
      assertEquals(transport, q.resources().bytes());
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void absentNullableColumnRetainsTheOriginalMetadataMembershipFormula() {
    var q = query();
    var row = row("text", LocalDateTime.of(2026, 1, 1, 0, 0));
    var fixture = new SessionQueryTransactionTest.Fixture();
    var observed = fixture.transaction.required(q, () -> measure(q, List.of(row), "missing-null"));
    long expected = 32;
    for (var e : row.entrySet())
      if (!e.getKey().equals("ended_at"))
        expected += 8 + SessionMetadataScalars.bytes(e.getValue(), null);
    assertEquals(expected, observed.bytes());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void additionalColumnMeterStillSeesEveryOriginalValue() {
    var q = query();
    var row = row("原值😀", LocalDateTime.of(2026, 1, 1, 0, 0));
    var seen = new LinkedHashMap<String, Object>();
    try (var extra = q.meter(() -> seen::put)) {
      new SessionQueryTransactionTest.Fixture()
          .transaction.required(q, () -> measure(q, List.of(row), "ok"));
    }
    assertEquals(row, seen);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void statementColumnsRowIdentityCloseWriteAndForeignWorkCannotReuseLengths() {
    for (String mode :
        List.of("statement", "column", "row", "closed", "mutation", "foreign", "changed-value")) {
      var q = query();
      var fixture = new SessionQueryTransactionTest.Fixture();
      assertThrows(
          RuntimeException.class,
          () ->
              fixture.transaction.required(
                  q,
                  () -> measure(q, List.of(row("text", LocalDateTime.of(2026, 1, 1, 0, 0))), mode)),
          mode);
      assertNotNull(q.recordedFailure(), mode);
      assertEquals(65536, q.scratchLive(), mode);
      assertFalse(q.metadataMeasuring(), mode);
    }
  }

  @Test
  void emptyResultStillRequiresANewSqlResultAndCannotFallbackFromMissingProof() {
    var q = query();
    var result =
        new SessionQueryTransactionTest.Fixture()
            .transaction.required(q, () -> measure(q, List.of(), "ok"));
    assertEquals(0, result.rows());
    assertEquals(0, result.bytes());
    assertEquals(1, q.resources().reads());
    assertEquals(65536, q.scratchLive());
    var missing = query();
    assertThrows(
        TaskFailure.class,
        () ->
            new SessionQueryTransactionTest.Fixture()
                .transaction.required(missing, () -> measure(missing, List.of(), "no-sql")));
    assertNotNull(missing.recordedFailure());
    assertEquals(65536, missing.scratchLive());
  }

  @Test
  void newCostsArePrepaidAndQuotaRefusalCannotBeCaughtToCommit() {
    var baseline = query();
    var row = row("text", LocalDateTime.of(2026, 1, 1, 0, 0));
    new SessionQueryTransactionTest.Fixture()
        .transaction.required(baseline, () -> measure(baseline, List.of(row), "ok"));
    long cost = baseline.resources().operations();
    for (long allowance : new long[] {cost - 1, cost, cost + 1}) {
      var q = query();
      q.operation(65536 - allowance);
      var fixture = new SessionQueryTransactionTest.Fixture();
      if (allowance < cost) {
        var first =
            assertThrows(
                TaskFailure.class,
                () -> fixture.transaction.required(q, () -> measure(q, List.of(row), "ok")));
        assertSame(first, q.recordedFailure());
        assertSame(first, assertThrows(TaskFailure.class, () -> q.checkHealthy()));
        assertEquals(0, fixture.commits);
      } else fixture.transaction.required(q, () -> measure(q, List.of(row), "ok"));
      assertEquals(65536, q.scratchLive());
      assertFalse(q.metadataMeasuring());
    }
  }

  @Test
  void malformedUnicodeFailsStrictlyAndKeepsFirstFailure() {
    for (String text : List.of("x\ud800", "x\udc00", "\ud800x")) {
      var q = query();
      var fixture = new SessionQueryTransactionTest.Fixture();
      var failure =
          assertThrows(
              TaskFailure.class,
              () ->
                  fixture.transaction.required(
                      q,
                      () ->
                          measure(
                              q, List.of(row(text, LocalDateTime.of(2026, 1, 1, 0, 0))), "ok")));
      assertEquals("SESSION_HISTORY_CORRUPT", failure.code());
      assertSame(failure, q.recordedFailure());
      assertSame(failure, assertThrows(TaskFailure.class, () -> q.operation(1)));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void errorReleasesBothResultAndCollectorOwnersAndPoisonsFinish() {
    var q = query();
    var fixture = new SessionQueryTransactionTest.Fixture();
    var error =
        assertThrows(
            AssertionError.class,
            () ->
                fixture.transaction.required(
                    q,
                    () ->
                        measure(
                            q, List.of(row("text", LocalDateTime.of(2026, 1, 1, 0, 0))), "error")));
    assertEquals("source-error", error.getMessage());
    assertNotNull(q.recordedFailure());
    assertEquals(65536, q.scratchLive());
    assertFalse(q.metadataMeasuring());
  }
}
