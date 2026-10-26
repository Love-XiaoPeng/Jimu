package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 本次17 XML/6 Wrapper完整投影与原星号的同连接、字段/类型/NULL/列序差异证据。 */
public final class ReviewProjectionScenario {
  private ReviewProjectionScenario() {}

  private static List<Object> rows(ResultSet rs) throws java.sql.SQLException {
    var result = new ArrayList<Object>();
    var metadata = rs.getMetaData();
    var columns = new ArrayList<Object>();
    for (int i = 1; i <= metadata.getColumnCount(); i++)
      columns.add(
          List.of(
              metadata.getColumnLabel(i),
              metadata.getColumnType(i),
              metadata.getColumnTypeName(i),
              metadata.isNullable(i)));
    result.add(columns);
    while (rs.next()) {
      var row = new ArrayList<Object>();
      for (int i = 1; i <= metadata.getColumnCount(); i++) {
        Object value = rs.getObject(i);
        var field = new LinkedHashMap<String, Object>();
        field.put(
            "value",
            value instanceof java.time.temporal.TemporalAccessor ? value.toString() : value);
        field.put("javaType", value == null ? null : value.getClass().getName());
        row.add(field);
      }
      result.add(row);
    }
    return result;
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.user"),
            properties.getProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    var session = LedgerMapperSessions.create(source);
    var inventory = Payloads.read(Files.readString(Path.of(args[1])), JsonNode.class);
    var columns = Payloads.read(Files.readString(Path.of(args[2])), JsonNode.class);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.execute(
        status -> {
          long connection = db.queryForObject("SELECT CONNECTION_ID()", Long.class);
          int nonempty = 0;
          for (var operation : inventory.path("operations")) {
            String id = operation.path("id").asText();
            String table = operation.path("table").asText();
            String task =
                db.queryForList(
                        "SELECT id FROM task WHERE tenant_id='tenant-a' ORDER BY created_at,id",
                        String.class)
                    .getLast();
            // Select a task whose relevant table is populated whenever such a row exists.
            if (!List.of("installation", "task", "evaluation_execution_scope").contains(table)) {
              var candidates =
                  db.queryForList(
                      "SELECT task_id FROM " + table + " WHERE tenant_id='tenant-a' LIMIT 1",
                      String.class);
              if (!candidates.isEmpty()) task = candidates.getFirst();
            }
            var params = new java.util.HashMap<String, Object>();
            params.put("tenant", "tenant-a");
            params.put("task", task);
            params.put("actor", "alice");
            params.put("plugin", "web_fetch");
            params.put("run", "none");
            var bound = session.getConfiguration().getMappedStatement(id).getBoundSql(params);
            Object[] values =
                bound.getParameterMappings().stream()
                    .map(p -> params.get(p.getProperty()))
                    .toArray();
            String old = operation.path("original").asText().replaceAll("#\\{\\w+}", "?");
            List<Object> before = db.query(old, ReviewProjectionScenario::rows, values);
            List<Object> after = db.query(bound.getSql(), ReviewProjectionScenario::rows, values);
            if (!Payloads.json(before).equals(Payloads.json(after)))
              throw new AssertionError("Raw projection mismatch: " + id);
            List<Map<String, Object>> actual = session.selectList(id, params);
            var expected = db.queryForList(old, values);
            if (!expected.equals(actual))
              throw new AssertionError("Actual Mapper values mismatch: " + id);
            if (!expected.isEmpty()) nonempty++;
            System.out.println(
                "PROJECTION "
                    + Payloads.json(
                        Map.of(
                            "id",
                            id,
                            "rows",
                            actual.size(),
                            "raw",
                            after,
                            "sameConnection",
                            db.queryForObject("SELECT CONNECTION_ID()", Long.class)
                                == connection)));
          }
          var wrappers =
              Map.of(
                  "step",
                  "session.mapper.SessionFactStepMapper",
                  "task_attempt",
                  "session.mapper.SessionFactAttemptMapper",
                  "invocation",
                  "session.mapper.HistoryInvocationReadMapper",
                  "installation",
                  "plugin.mapper.InstallationMapper",
                  "step_session_scope",
                  "session.mapper.ScopeQueryCutReadMapper",
                  "task_todo_projection",
                  "todo.mapper.TodoProjectionReadMapper");
          for (var entry : wrappers.entrySet()) {
            String table = entry.getKey();
            var names = new ArrayList<String>();
            columns.path(table).forEach(c -> names.add(c.asText()));
            var wrapper =
                new QueryWrapper<>()
                    .select(names.toArray(String[]::new))
                    .eq("tenant_id", "tenant-a");
            String id =
                "io.github.lovexiaopeng.jimu.adapters.persistence."
                    + entry.getValue()
                    + ".selectMaps";
            List<Map<String, Object>> actual = session.selectList(id, Map.of("ew", wrapper));
            var expected =
                db.queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a'");
            if (!actual.equals(expected))
              throw new AssertionError("Wrapper values mismatch: " + id);
            var old =
                db.query(
                    "SELECT * FROM " + table + " WHERE tenant_id='tenant-a'",
                    ReviewProjectionScenario::rows);
            var next =
                db.query(
                    "SELECT "
                        + String.join(",", names)
                        + " FROM "
                        + table
                        + " WHERE tenant_id='tenant-a'",
                    ReviewProjectionScenario::rows);
            if (!Payloads.json(old).equals(Payloads.json(next)))
              throw new AssertionError("Wrapper metadata mismatch: " + table);
            System.out.println(
                "WRAPPER "
                    + Payloads.json(Map.of("table", table, "rows", actual.size(), "raw", next)));
          }
          System.out.println(
              "PASS projections=17 wrappers=6 nonempty=" + nonempty + " connection=" + connection);
          status.setRollbackOnly();
          return null;
        });
  }
}
