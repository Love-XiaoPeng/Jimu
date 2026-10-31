package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SessionFinalResultFoldTest {
  private static SessionQueryContext q() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private static MappedStatement command(Table table) {
    return new MappedStatement.Builder(
            new Configuration(), table.name(), p -> null, SqlCommandType.SELECT)
        .build();
  }

  private static Map<String, Object> row(SessionMetadataProjection p) {
    var row = new LinkedHashMap<String, Object>();
    for (var c : p.columns)
      row.put(
          c.name(),
          switch (c.kind()) {
            case 'S' -> "x".repeat(c.points());
            case 'I' -> 1;
            case 'L' -> 1L;
            case 'B' -> true;
            case 'D' -> LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123456789);
            default -> throw new AssertionError();
          });
    return row;
  }

  private static int jdbc(char kind) {
    return switch (kind) {
      case 'S' -> Types.VARCHAR;
      case 'I' -> Types.INTEGER;
      case 'L' -> Types.BIGINT;
      case 'B' -> Types.BIT;
      case 'D' -> Types.TIMESTAMP;
      default -> throw new AssertionError();
    };
  }

  static final class Driver {
    int index = -1, getters, labels, types;
    boolean closed;
    String fault = "";
    final SessionMetadataProjection p;
    final List<Map<String, Object>> rows;
    final ResultSet result;

    Driver(SessionMetadataProjection p, List<Map<String, Object>> rows) {
      this.p = p;
      this.rows = rows;
      var metadata =
          (ResultSetMetaData)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ResultSetMetaData.class},
                  (o, m, a) ->
                      switch (m.getName()) {
                        case "getColumnCount" ->
                            p.columns.size()
                                + (fault.equals("extra") ? 1 : fault.equals("missing") ? -1 : 0);
                        case "getColumnLabel" -> {
                          labels++;
                          yield fault.equals("label")
                              ? "bad"
                              : p.columns.get((int) a[0] - 1).name();
                        }
                        case "getColumnType" -> {
                          types++;
                          yield fault.equals("jdbc")
                              ? Types.BLOB
                              : jdbc(p.columns.get((int) a[0] - 1).kind());
                        }
                        default -> throw new AssertionError(m.getName());
                      });
      result =
          (ResultSet)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ResultSet.class},
                  (o, m, a) ->
                      switch (m.getName()) {
                        case "hashCode" -> System.identityHashCode(o);
                        case "equals" -> o == a[0];
                        case "getMetaData" -> metadata;
                        case "next" -> ++index < rows.size();
                        case "getObject" -> {
                          getters++;
                          Object v = rows.get(index).get(p.columns.get((int) a[0] - 1).name());
                          yield v instanceof LocalDateTime t && t.getYear() > 0
                              ? java.sql.Timestamp.valueOf(t)
                              : v;
                        }
                        case "close" -> {
                          closed = true;
                          yield null;
                        }
                        default -> throw new AssertionError(m.getName());
                      });
    }
  }

  static SessionMetadataAccounting.FinalTable direct(
      SessionQueryContext q, Table t, Driver d, boolean nulls) throws Exception {
    try (d.result) {
      return SessionCursorMetadataBatch.measureResultSet(q, t, d.result, command(t), nulls);
    } finally {
      q.releaseScratch(d.result);
    }
  }

  private static SessionMetadataAccounting.FinalTable legacy(
      SessionQueryContext q, Table t, List<Map<String, Object>> rows) throws Exception {
    var p = SessionMetadataProjection.forFinalTable(t);
    var d = new Driver(p, rows);
    var materialized = new ArrayList<Map<String, Object>>();
    try (var rs = QueryResults.meter(d.result, q, t.name())) {
      while (rs.next()) materialized.add(rows.get(d.index));
    }
    if (t == Table.TASK_SESSION_LINEAGE)
      return SessionLineageMetadataBatch.measureFinal(q, () -> materialized);
    var cursor =
        new org.apache.ibatis.cursor.Cursor<Map<String, Object>>() {
          public boolean isOpen() {
            return true;
          }

          public boolean isConsumed() {
            return false;
          }

          public int getCurrentIndex() {
            return 0;
          }

          public Iterator<Map<String, Object>> iterator() {
            return materialized.iterator();
          }

          public void close() {}
        };
    return SessionCursorMetadataBatch.measureFinal(q, t, p, () -> cursor);
  }

  @Test
  void everyFinalScalarProjectionPreservesSummarySqlBytesAndSingleGetter() throws Exception {
    int samples = 0;
    for (var t : Table.values()) {
      var p = SessionMetadataProjection.forFinalTable(t);
      if (p == null) continue;
      for (String shape : List.of("full", "null", "absent", "unicode", "wide")) {
        var values = row(p);
        for (var c : p.columns) {
          if (c.nullable() && shape.equals("null")) values.put(c.name(), null);
          if (c.nullable() && shape.equals("absent")) values.remove(c.name());
          if (shape.equals("unicode") && c.kind() == 'S')
            values.put(c.name(), "🧱".repeat(c.points()));
          if (shape.equals("wide")) {
            if (c.kind() == 'L') values.put(c.name(), Long.MIN_VALUE);
            if (c.kind() == 'I') values.put(c.name(), Integer.MIN_VALUE);
            if (c.kind() == 'D')
              values.put(c.name(), LocalDateTime.of(-10000, 1, 1, 0, 0, 0, 999999999));
            if (c.kind() == 'B') values.put(c.name(), false);
          }
        }
        boolean nulls = !shape.equals("absent");
        var old = q();
        var expected = legacy(old, t, List.of(values));
        var now = q();
        var d = new Driver(p, List.of(values));
        assertEquals(expected, direct(now, t, d, nulls), t + shape);
        assertEquals(old.resources().bytes(), now.resources().bytes(), t + shape);
        assertEquals(p.columns.size(), d.getters);
        assertEquals(p.columns.size(), d.labels);
        assertEquals(p.columns.size(), d.types);
        assertTrue(d.closed);
        assertEquals(65536, now.scratchLive());
        samples++;
      }
    }
    assertEquals(80, samples);
  }

  @Test
  void fullAffectedFinalCostIncludesEmptyAndFrozenOriginalPopulation() throws Exception {
    var frozen =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(getClass().getResourceAsStream("/session/v5/final-fold-frozen-r40.json"));
    for (String population : List.of("empty", "full", "frozen")) {
      long before = 0, after = 0;
      for (var t : Table.values()) {
        var p = SessionMetadataProjection.forFinalTable(t);
        if (p == null) continue;
        var rows = new ArrayList<Map<String, Object>>();
        if (population.equals("full")) rows.add(row(p));
        if (population.equals("frozen"))
          for (var json : frozen.path(t.name().toLowerCase(Locale.ROOT))) {
            var values = new LinkedHashMap<String, Object>();
            for (var c : p.columns) {
              var node = json.path(c.name());
              if (node.isMissingNode() || node.isNull()) continue;
              values.put(
                  c.name(),
                  switch (c.kind()) {
                    case 'S' -> node.asText();
                    case 'I' -> node.intValue();
                    case 'L' -> node.longValue();
                    case 'B' -> node.booleanValue();
                    case 'D' -> LocalDateTime.parse(node.asText());
                    default -> throw new AssertionError();
                  });
            }
            rows.add(values);
          }
        var old = q();
        var expected = legacy(old, t, rows);
        var now = q();
        assertEquals(expected, direct(now, t, new Driver(p, rows), false));
        assertEquals(old.resources().bytes(), now.resources().bytes());
        before += old.resources().operations();
        after += now.resources().operations();
      }
      System.out.println(
          "FINAL_SCALAR_COST population="
              + population
              + " before="
              + before
              + " after="
              + after
              + " saved="
              + (before - after));
      // Empty projections still validate static metadata; assess their full real cost too.
      if (!population.equals("empty")) assertTrue(after < before, population);
    }
  }

  @Test
  void malformedStaticAndDynamicInputsCloseAndRelease() throws Exception {
    var p = SessionMetadataProjection.forFinalTable(Table.TASK_ATTEMPT);
    for (String fault :
        List.of("extra", "missing", "label", "jdbc", "null", "type", "surrogate", "rows")) {
      var values = row(p);
      if (fault.equals("null")) values.put("tenant_id", null);
      if (fault.equals("type")) values.put("fence", 1);
      if (fault.equals("surrogate")) values.put("tenant_id", "\ud800");
      var d = new Driver(p, List.of(values));
      d.fault = fault;
      var query = q();
      if (fault.equals("rows")) continue;
      assertThrows(
          RuntimeException.class, () -> direct(query, Table.TASK_ATTEMPT, d, false), fault);
      assertTrue(d.closed);
      assertEquals(65536, query.scratchLive());
    }
    var lp = SessionMetadataProjection.forFinalTable(Table.TASK_SESSION_LINEAGE);
    var query = q();
    var d = new Driver(lp, List.of(row(lp), row(lp)));
    assertThrows(RuntimeException.class, () -> direct(query, Table.TASK_SESSION_LINEAGE, d, false));
    assertTrue(d.closed);
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void additionalMeterKeepsOriginalValuesAndFirstRefusal() throws Exception {
    var p = SessionMetadataProjection.forFinalTable(Table.TASK_ATTEMPT);
    var q = q();
    var seen = new ArrayList<Object>();
    try (var meter = q.meter(() -> (label, value) -> seen.add(value))) {
      direct(q, Table.TASK_ATTEMPT, new Driver(p, List.of(row(p))), false);
    }
    assertEquals(p.columns.size(), seen.size());
    assertEquals(65536, q.scratchLive());
    var fail = q();
    var primary = new IllegalStateException("extra meter");
    try (var meter =
        fail.meter(
            () ->
                (label, value) -> {
                  throw primary;
                })) {
      assertSame(
          primary,
          assertThrows(
              RuntimeException.class,
              () -> direct(fail, Table.TASK_ATTEMPT, new Driver(p, List.of(row(p))), false)));
    }
    assertSame(primary, fail.recordedFailure());
    assertEquals(65536, fail.scratchLive());
  }
}
