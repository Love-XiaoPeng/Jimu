package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 实际装配固定语句、原全行/五列投影及两个参数位置。 */
class MetadataReportMapperTest {
  @Test
  void tenFullScansAndTwoSourceShapesLoadExactly() {
    var configuration =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    var owners =
        Map.ofEntries(
            Map.entry("task.mapper.TaskMapper", "task"),
            Map.entry("directory.mapper.TaskDirectoryReadMapper", "task_directory"),
            Map.entry("directory.mapper.DirectoryPathReadMapper", "invocation_path_binding"),
            Map.entry("todo.mapper.TodoProjectionReadMapper", "task_todo_projection"),
            Map.entry("execution.mapper.TaskAttemptMapper", "task_attempt"),
            Map.entry("execution.mapper.StepMapper", "step"),
            Map.entry("invocation.mapper.InvocationMapper", "invocation"),
            Map.entry("model.mapper.ModelDispatchMapper", "model_dispatch"),
            Map.entry("budget.mapper.ReservationMapper", "reservation"),
            Map.entry("event.mapper.EventMapper", "event"));
    owners.forEach(
        (owner, table) -> {
          var statement =
              configuration.getMappedStatement(
                  "io.github.lovexiaopeng.jimu.adapters.persistence." + owner + ".metadataRows");
          var sql = statement.getBoundSql(Map.of());
          assertEquals(
              "SELECT * FROM "
                  + table
                  + " WHERE tenant_id=? AND "
                  + (table.equals("task") ? "id" : "task_id")
                  + "=?",
              sql.getSql().trim());
          assertEquals(
              List.of("tenant", "task"),
              sql.getParameterMappings().stream().map(p -> p.getProperty()).toList());
          assertEquals(
              org.springframework.util.LinkedCaseInsensitiveMap.class,
              statement.getResultMaps().getFirst().getType());
        });
    for (boolean composed : List.of(false, true)) {
      var sql =
          configuration
              .getMappedStatement(
                  "io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper.InvocationMapper.report"
                      + (composed ? "Composed" : "Ordinary")
                      + "Sources")
              .getBoundSql(Map.of());
      assertEquals(
          "SELECT id,output_snapshot,output_digest,plugin_version,plugin_digest FROM invocation WHERE tenant_id=? AND task_id=? AND tool='web_fetch' AND state='SUCCEEDED'"
              + (composed ? "" : " AND plugin_version='0.3.0'")
              + " ORDER BY ordinal",
          sql.getSql().trim());
      assertEquals(
          List.of("tenant", "task"),
          sql.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
  }
}
