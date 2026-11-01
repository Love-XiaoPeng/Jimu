package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SessionModelTaskSourceTest {
  @Test
  void taskPinRequiresExactSqlAndOrderedInputMappings() {
    taskPin(true);
    taskPin(false);
  }

  private void taskPin(boolean compact) {
    var config = new Configuration();
    var driver = new SessionHistoryMetadataLanguageDriver();
    String expected =
        compact
            ? SessionLineageSourceColumns.MODEL_TASK_SQL
            : SessionLineageSourceColumns.EXTENDED_MODEL_TASK_SQL;
    String sql =
        expected.replace("tenant_id=?", "tenant_id=#{tenant}").replace("id=?", "id=#{taskId}");
    for (String shape : List.of("exact", "sql", "parameter", "out", "plain")) {
      String script =
          switch (shape) {
            case "sql" -> sql.replace("max_tokens", "max_models");
            case "parameter" -> sql.replace("#{taskId}", "#{other}");
            case "out" -> sql.replace("#{taskId}", "#{taskId,mode=OUT,jdbcType=VARCHAR}");
            default -> sql;
          };
      var source =
          shape.equals("plain")
              ? new RawSqlSource(config, script, Map.class)
              : driver.createSqlSource(config, "<script>" + script + "</script>", Map.class);
      assertEquals(
          shape.equals("exact"),
          SessionHistoryMetadataLanguageDriver.ownsModelTask(source, compact));
      assertFalse(SessionHistoryMetadataLanguageDriver.ownsModelStep(source));
      if (shape.equals("exact")) {
        assertEquals(expected, source.getBoundSql(null).getSql());
        assertThrows(
            UnsupportedOperationException.class,
            () -> source.getBoundSql(null).getParameterMappings().clear());
      } else if (!shape.equals("plain")) {
        var query =
            new SessionQueryContext(
                System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
        query.restrictLineage();
        var command =
            new MappedStatement.Builder(
                    config,
                    compact
                        ? SessionLineageSourceColumns.MODEL_TASK
                        : SessionLineageSourceColumns.EXTENDED_MODEL_TASK,
                    source,
                    SqlCommandType.SELECT)
                .resultMaps(
                    List.of(new ResultMap.Builder(config, shape, Map.class, List.of()).build()))
                .build();
        var error =
            assertThrows(
                IllegalStateException.class,
                () ->
                    SessionLineageSourceColumns.verifyModelTaskCommand(
                        command, query, Map.of("query", query)));
        assertEquals("Changed model task SQL or parameters", error.getMessage());
      }
    }
  }
}
