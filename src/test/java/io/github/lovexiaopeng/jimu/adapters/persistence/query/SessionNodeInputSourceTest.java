package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SessionNodeInputSourceTest {
  @Test
  void onlyTheExactImmutableNodeSourceCanOmitRepeatedSqlParsing() {
    var config = new Configuration();
    var driver = new SessionHistoryMetadataLanguageDriver();
    String sql =
        SessionLineageSourceColumns.NODE_SQL
            .replace("t.tenant_id=?", "t.tenant_id=#{tenant}")
            .replace("t.id=?", "t.id=#{id}");
    for (String shape : List.of("exact", "sql", "parameter", "history")) {
      String script =
          switch (shape) {
            case "sql" -> sql.replace("t.request_digest", "t.goal");
            case "parameter" -> sql.replace("#{id}", "#{other}");
            case "history" ->
                SessionHistorySizedMetadataBatch.SQL
                    .replace("tenant_id=?", "tenant_id=#{tenant}")
                    .replace("task_id=?", "task_id=#{taskId}");
            default -> sql;
          };
      var source = driver.createSqlSource(config, "<script>" + script + "</script>", Map.class);
      var query =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      query.restrictLineage();
      var command =
          new MappedStatement.Builder(
                  config,
                  "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.nodeInput",
                  source,
                  SqlCommandType.SELECT)
              .resultMaps(
                  List.of(new ResultMap.Builder(config, shape, Map.class, List.of()).build()))
              .build();
      if (shape.equals("exact")) {
        assertTrue(SessionHistoryMetadataLanguageDriver.ownsNode(source));
        assertFalse(SessionHistoryMetadataLanguageDriver.owns(source));
        QueryResults.verifyNodeInputCommand(command, query, Map.of());
        assertThrows(
            UnsupportedOperationException.class,
            () -> source.getBoundSql(null).getParameterMappings().clear());
        QueryResults.verifyNodeInputCommand(command, query, Map.of());
      } else {
        assertFalse(SessionHistoryMetadataLanguageDriver.ownsNode(source));
        assertThrows(
            IllegalStateException.class,
            () -> QueryResults.verifyNodeInputCommand(command, query, Map.of()));
      }
    }
  }
}
