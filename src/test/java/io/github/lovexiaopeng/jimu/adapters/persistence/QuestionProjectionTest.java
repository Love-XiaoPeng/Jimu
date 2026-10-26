package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 五个实际 Mapper 与既有 schema22 列清单对照，并经 MyBatis 实测标签、类型、NULL 及全列计量。 */
class QuestionProjectionTest {
  static String columns(String table) {
    try {
      var inventory =
          Payloads.read(
              Files.readString(
                  Path.of("docs/verification/XIAO-60/evidence/b2-invocation-removed-sql.json")),
              JsonNode.class);
      var names = new ArrayList<String>();
      inventory.path("columns").path(table).forEach(column -> names.add(column.asText()));
      assertEquals(table.equals("invocation") ? 27 : 33, names.size());
      return String.join(",", names);
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  void fiveLoadedProjectionsKeepSchema22OrderAndMapAllTypesAndNulls() throws Exception {
    var c =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    assertTrue(c.isCallSettersOnNulls());
    assertTrue(c.isUseColumnLabel());
    for (String method :
        List.of(
            "Answer.lockQuestion",
            "Answer.previous",
            "Answer.reload",
            "Opening.invocation",
            "Maintenance.due")) {
      String[] parts = method.split("\\.");
      String table = parts[0].equals("Opening") ? "invocation" : "user_question";
      var statement =
          c.getMappedStatement(
              "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.Question"
                  + parts[0]
                  + "Mapper."
                  + parts[1]);
      var bound = statement.getBoundSql(Map.of());
      assertTrue(
          bound.getSql().startsWith("SELECT " + columns(table) + " FROM " + table + " WHERE "));
      assertFalse(bound.getSql().contains("*"));
      assertEquals(LinkedHashMap.class, statement.getResultMaps().getFirst().getType());
      for (boolean nulls : List.of(false, true)) {
        var parameters = new java.util.HashMap<String, Object>();
        var expectedBinds = new ArrayList<Object>();
        for (var mapping : bound.getParameterMappings()) {
          Object value = nulls ? null : "binding-" + mapping.getProperty();
          parameters.put(mapping.getProperty(), value);
          expectedBinds.add(value);
        }
        var boundValues = new ArrayList<Object>();
        var jdbcParameters =
            (PreparedStatement)
                Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, call, args) -> {
                      assertEquals(nulls ? "setNull" : "setString", call.getName());
                      assertEquals(boundValues.size() + 1, args[0]);
                      if (nulls) assertEquals(c.getJdbcTypeForNull().TYPE_CODE, args[1]);
                      boundValues.add(nulls ? null : args[1]);
                      return null;
                    });
        c.newParameterHandler(statement, parameters, bound).setParameters(jdbcParameters);
        assertEquals(expectedBinds, boundValues);
      }
      String[] names = columns(table).split(",");
      for (boolean nulls : List.of(false, true)) {
        try (var rows = RowSetProvider.newFactory().createCachedRowSet()) {
          var metadata = new RowSetMetaDataImpl();
          metadata.setColumnCount(names.length);
          var expected = new LinkedHashMap<String, Object>();
          for (int i = 0; i < names.length; i++) {
            String name = names[i];
            int type = type(name);
            metadata.setColumnName(i + 1, name);
            metadata.setColumnLabel(i + 1, name);
            metadata.setColumnType(i + 1, type);
            metadata.setNullable(i + 1, ResultSetMetaData.columnNullable);
            Object value =
                switch (type) {
                  case Types.INTEGER -> Integer.valueOf(i + 1);
                  case Types.BIGINT -> Long.valueOf(9_000_000_000L + i);
                  case Types.TIMESTAMP -> Timestamp.valueOf("2026-10-26 01:02:03.123456");
                  default -> name + "-选择🙂";
                };
            expected.put(name, nulls && i % 2 == 0 ? null : value);
          }
          rows.setMetaData(metadata);
          rows.moveToInsertRow();
          for (int i = 0; i < names.length; i++) rows.updateObject(i + 1, expected.get(names[i]));
          rows.insertRow();
          rows.moveToCurrentRow();
          rows.beforeFirst();
          var query =
              new SessionQueryContext(
                  System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
          // JDK CachedRowSet omits isClosed; expose only that JDBC lifecycle method here.
          var jdbcRows =
              (ResultSet)
                  Proxy.newProxyInstance(
                      ResultSet.class.getClassLoader(),
                      new Class<?>[] {ResultSet.class},
                      (proxy, call, args) -> {
                        if (call.getName().equals("isClosed")) return false;
                        try {
                          return call.invoke(rows, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                      });
          var metered = QueryResults.meter(jdbcRows, query);
          var jdbc =
              (Statement)
                  Proxy.newProxyInstance(
                      Statement.class.getClassLoader(),
                      new Class<?>[] {Statement.class},
                      (proxy, call, args) ->
                          switch (call.getName()) {
                            case "getResultSet" -> metered;
                            case "getMoreResults", "isClosed" -> false;
                            case "getUpdateCount" -> -1;
                            case "getConnection" -> singleResultConnection();
                            case "close" -> null;
                            default -> throw new AssertionError(call.getName());
                          });
          var handler =
              c.newResultSetHandler(
                  null,
                  statement,
                  RowBounds.DEFAULT,
                  c.newParameterHandler(statement, Map.of(), bound),
                  null,
                  bound);
          var actual = handler.handleResultSets(jdbc);
          assertEquals(List.of(expected), actual, method);
          assertEquals(List.of(names), new ArrayList<>(((Map<?, ?>) actual.getFirst()).keySet()));
          long bytes =
              expected.entrySet().stream()
                  .mapToLong(
                      entry ->
                          8L
                              + entry
                                  .getKey()
                                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                  .length
                              + SessionQueryContext.scalarBytes(
                                  entry.getValue() instanceof Timestamp time
                                      ? time.toLocalDateTime()
                                      : entry.getValue()))
                  .sum();
          assertEquals(new QueryResources(1, bytes, names.length + 1), query.resources());
        }
      }
    }
  }

  private static int type(String name) {
    if (name.endsWith("_at") || name.endsWith("_until")) return Types.TIMESTAMP;
    if (Set.of(
            "installation_epoch",
            "todo_expected_revision",
            "todo_applied_revision",
            "todo_committed_fence",
            "todo_event_seq")
        .contains(name)) return Types.BIGINT;
    if (Set.of(
            "ordinal",
            "record_version",
            "question_version",
            "revision",
            "effective_timeout",
            "accepted_seq",
            "answer_source_revision",
            "projection_version")
        .contains(name)) return Types.INTEGER;
    return Types.VARCHAR;
  }

  private static java.sql.Connection singleResultConnection() {
    return (java.sql.Connection)
        Proxy.newProxyInstance(
            java.sql.Connection.class.getClassLoader(),
            new Class<?>[] {java.sql.Connection.class},
            (proxy, method, args) -> {
              assertEquals("getMetaData", method.getName());
              return Proxy.newProxyInstance(
                  java.sql.DatabaseMetaData.class.getClassLoader(),
                  new Class<?>[] {java.sql.DatabaseMetaData.class},
                  (metadata, call, values) -> {
                    assertEquals("supportsMultipleResultSets", call.getName());
                    return false;
                  });
            });
  }
}
