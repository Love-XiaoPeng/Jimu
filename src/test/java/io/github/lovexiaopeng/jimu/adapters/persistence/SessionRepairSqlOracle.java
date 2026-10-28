package io.github.lovexiaopeng.jimu.adapters.persistence;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 独立迁移列清单 oracle；不调用生产 Mapper、计账 Service 或其常量。 */
final class SessionRepairSqlOracle {
  private static final List<String> TABLES =
      List.of(
          "task",
          "task_session_history",
          "session_public_event",
          "invocation_session_read",
          "invocation_session_search",
          "invocation_session_trace",
          "task_session_scope",
          "task_session_scope_target",
          "step_session_scope",
          "invocation_session_scope_query",
          "task_session_lineage",
          "invocation_session_lineage_trace",
          "task_attempt",
          "step",
          "invocation",
          "model_dispatch",
          "reservation",
          "event");
  private static final Set<String> BODY =
      Set.of(
          "request_snapshot",
          "goal",
          "summary",
          "response_snapshot",
          "input_snapshot",
          "output_snapshot",
          "usage_snapshot",
          "event_snapshot",
          "normalized_query_snapshot",
          "read_sources_snapshot",
          "authority_snapshot",
          "target_snapshot",
          "source_task_id",
          "context_snapshot",
          "normalized_snapshot",
          "read_refs_snapshot",
          "genesis_snapshot",
          "graph_snapshot");
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  record Totals(long metadata, long body) {}

  /** 独立完整主键顺序的夹具导出，标量转成可离线复算的原UTF8文本。 */
  static void dump(JdbcTemplate db, String task, String label) {
    for (String table : TABLES) {
      var primary =
          db.queryForList(
              "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX",
              String.class,
              table);
      String key =
          table.equals("task")
              ? "id"
              : table.contains("scope") || table.equals("invocation_session_lineage_trace")
                  ? "caller_task_id"
                  : "task_id";
      var exported = new java.util.ArrayList<java.util.Map<String, String>>();
      for (var row :
          db.queryForList(
              "SELECT * FROM `"
                  + table
                  + "` WHERE tenant_id='tenant-a' AND `"
                  + key
                  + "`=? ORDER BY "
                  + primary.stream()
                      .map(name -> "`" + name + "`")
                      .collect(java.util.stream.Collectors.joining(",")),
              task)) {
        var values = new java.util.LinkedHashMap<String, String>();
        row.forEach(
            (name, value) ->
                values.put(
                    name,
                    value == null
                        ? null
                        : value instanceof java.sql.Timestamp stamp
                            ? UTC.format(stamp.toLocalDateTime())
                            : value instanceof java.time.LocalDateTime time
                                ? UTC.format(time)
                                : value.toString()));
        exported.add(values);
      }
      System.out.println(
          "ORACLE "
              + label
              + " "
              + table
              + " "
              + io.github.lovexiaopeng.jimu.adapters.codec.Payloads.json(exported));
    }
  }

  static Totals inspect(JdbcTemplate db, String task) {
    long metadata = 0, body = 0;
    for (String table : TABLES) {
      var columns =
          db.queryForList(
              "SELECT COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
              table);
      if (columns.isEmpty()) throw new AssertionError("missing migrated table " + table);
      String key =
          table.equals("task")
              ? "id"
              : table.contains("scope") || table.equals("invocation_session_lineage_trace")
                  ? "caller_task_id"
                  : "task_id";
      for (var row :
          db.queryForList(
              "SELECT * FROM `" + table + "` WHERE tenant_id='tenant-a' AND `" + key + "`=?",
              task)) {
        metadata += 32;
        for (var column : columns) {
          String name = (String) column.get("COLUMN_NAME");
          Object value = row.get(name);
          if (BODY.contains(name)) {
            if (value != null) body += value.toString().getBytes(StandardCharsets.UTF_8).length;
          } else {
            String text =
                value == null
                    ? null
                    : value instanceof java.sql.Timestamp stamp
                        ? UTC.format(stamp.toLocalDateTime())
                        : value instanceof java.time.LocalDateTime time
                            ? UTC.format(time)
                            : value.toString();
            metadata += 8 + (text == null ? 1 : text.getBytes(StandardCharsets.UTF_8).length);
          }
        }
      }
    }
    return new Totals(metadata, body);
  }
}
