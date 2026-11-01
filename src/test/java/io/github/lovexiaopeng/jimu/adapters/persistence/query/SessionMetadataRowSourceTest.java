package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SessionMetadataRowSourceTest {
  @Test
  void exactRowSqlAndParameterMappingsStayImmutableAndCannotMoveBetweenTables() throws Exception {
    var configuration = new Configuration();
    String resource = "mapper/session/SessionHistoryRetentionMapper.xml";
    try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments())
          .parse();
    }
    String prefix =
        "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.";
    for (String name :
        new String[] {
          "metadataEventRow",
          "metadataReservationRow",
          "metadataTaskSizedRow",
          "metadataOrdinaryRows"
        }) {
      var original = configuration.getMappedStatement(prefix + name);
      int table =
          name.equals("metadataOrdinaryRows")
              ? -2
              : name.equals("metadataEventRow")
                  ? Table.EVENT.ordinal()
                  : name.equals("metadataTaskSizedRow")
                      ? Table.TASK.ordinal()
                      : Table.RESERVATION.ordinal();
      var q =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      q.restrictLineage();
      var args = Map.of("tenant", "tenant-a", "taskId", "task", "nullBytes", 0L);
      SessionHistoryMetadataLanguageDriver.verifyRowCommand(original, table, q, args);
      assertThrows(
          UnsupportedOperationException.class,
          () -> original.getBoundSql(args).getParameterMappings().clear());
      assertThrows(
          IllegalStateException.class,
          () ->
              SessionHistoryMetadataLanguageDriver.verifyRowCommand(
                  original, Table.STEP.ordinal(), q, args));
      String sql = original.getBoundSql(args).getSql();
      var changed =
          new MappedStatement.Builder(
                  configuration,
                  original.getId(),
                  new RawSqlSource(configuration, sql + " /*changed*/", Map.class),
                  SqlCommandType.SELECT)
              .resultMaps(original.getResultMaps())
              .build();
      assertThrows(
          IllegalStateException.class,
          () -> SessionHistoryMetadataLanguageDriver.verifyRowCommand(changed, table, q, args));
      var parameters =
          new MappedStatement.Builder(
                  configuration,
                  original.getId(),
                  new RawSqlSource(configuration, sql.replaceFirst("\\?", "#{foreign}"), Map.class),
                  SqlCommandType.SELECT)
              .resultMaps(original.getResultMaps())
              .build();
      assertThrows(
          IllegalStateException.class,
          () -> SessionHistoryMetadataLanguageDriver.verifyRowCommand(parameters, table, q, args));
    }
  }
}
