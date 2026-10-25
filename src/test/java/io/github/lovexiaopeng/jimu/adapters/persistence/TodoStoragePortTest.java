package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.apache.ibatis.type.LongTypeHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.SingleColumnRowMapper;

class TodoStoragePortTest {
  @Test
  void constructionReadsNothing() {
    new MapperTodoStorage(null, null);
  }

  @Test
  void cardinalityAndNullableSingleValueMatchJdbcTemplate() {
    var empty =
        assertThrows(
            EmptyResultDataAccessException.class, () -> MapperTodoStorage.single(List.of()));
    assertEquals(1, empty.getExpectedSize());
    assertEquals(0, empty.getActualSize());
    var multiple =
        assertThrows(
            IncorrectResultSizeDataAccessException.class,
            () -> MapperTodoStorage.single(List.of(1L, 2L)));
    assertEquals(1, multiple.getExpectedSize());
    assertEquals(2, multiple.getActualSize());
    assertNull(MapperTodoStorage.single(Arrays.asList((Long) null)));
    assertEquals(17L, MapperTodoStorage.single(List.of(17L)));
  }

  @Test
  void argumentsCastTenantThenTaskBeforeSession() {
    for (String bad : List.of("tenant_id", "id")) {
      var calls = new ArrayList<String>();
      var task =
          new HashMap<String, Object>() {
            public Object get(Object key) {
              calls.add((String) key);
              return key.equals(bad) ? 17 : "value";
            }
          };
      assertThrows(ClassCastException.class, () -> new MapperTodoStorage(null, task).bytes());
      assertEquals(
          bad.equals("tenant_id") ? List.of("tenant_id") : List.of("tenant_id", "id"), calls);
    }
  }

  @Test
  void jdbcLongTypeHandlerPreservesOriginalLongConversionAndNull() throws Exception {
    for (boolean isNull : List.of(false, true)) {
      var calls = new ArrayList<String>();
      var result =
          (ResultSet)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ResultSet.class},
                  (p, m, a) -> {
                    calls.add(m.getName());
                    if (m.getName().equals("getLong")) return isNull ? 0L : 17L;
                    if (m.getName().equals("wasNull")) return isNull;
                    throw new AssertionError(m.getName());
                  });
      Long mapped = new LongTypeHandler().getResult(result, "total");
      assertEquals(isNull ? null : 17L, mapped);
      assertEquals(isNull ? List.of("getLong", "wasNull") : List.of("getLong"), calls);
      calls.clear();
      var original =
          new SingleColumnRowMapper<Long>(Long.class) {
            Long read(ResultSet rows) throws Exception {
              return (Long) getColumnValue(rows, 1, Long.class);
            }
          };
      assertEquals(mapped, original.read(result));
      assertEquals(List.of("getLong", "wasNull"), calls);
    }
  }

  @Test
  void loadedAggregateMatchesOriginalSqlAndParameterOrder() throws Exception {
    var session =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var statement =
        session
            .getConfiguration()
            .getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoStorageMapper.bytes");
    var bound = statement.getBoundSql(java.util.Map.of("tenant", "tenant-a", "task", "task"));
    try (var input =
        new java.util.zip.GZIPInputStream(
            java.nio.file.Files.newInputStream(
                java.nio.file.Path.of(
                    "docs/verification/XIAO-60/evidence/path-tracking.json.gz")))) {
      var capture =
          io.github.lovexiaopeng.jimu.adapters.codec.Payloads.read(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
              com.fasterxml.jackson.databind.JsonNode.class);
      String original = null;
      for (var row : capture.path("baseline").path("sql")) {
        String sql = row.path("sql").asText();
        if (sql.startsWith("SELECT COALESCE(OCTET_LENGTH(t.request_snapshot),0)+")
            && sql.contains("task_todo_projection")) original = sql;
      }
      assertNotNull(original);
      assertEquals(original, bound.getSql());
    }
    assertEquals(
        List.of("tenant", "task"),
        bound.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    assertEquals(Long.class, statement.getResultMaps().getFirst().getType());
  }
}
