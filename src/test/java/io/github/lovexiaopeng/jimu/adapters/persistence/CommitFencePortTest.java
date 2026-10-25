package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.CommitFenceMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CommitFencePortTest {
  @Test
  void constructionIsLazyAndNullLeaseFailsAtFirstMemberBeforeSession() {
    var port = new MapperCommitFence(null, null);
    var failure = assertThrows(NullPointerException.class, port::update);
    org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("tenant()"));
  }

  @Test
  void nullStringMembersAndLongFenceAreNotValidatedDuringConstruction() {
    var lease = new Lease(null, null, null, null, Long.MIN_VALUE, null, null, null, List.of(), 0);
    new MapperCommitFence(null, lease);
  }

  @Test
  void loadedUpdatePreservesOriginalSqlAndFiveParameterMappings() throws Exception {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var statement =
        session
            .getConfiguration()
            .getMappedStatement(CommitFenceMapper.class.getName() + ".commitFence");
    var parameters = new HashMap<String, Object>();
    parameters.put("tenant", null);
    parameters.put("task", "task");
    parameters.put("attempt", "attempt");
    parameters.put("worker", null);
    parameters.put("fence", Long.MIN_VALUE);
    var bound = statement.getBoundSql(parameters);
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
      for (var row : capture.path("baseline").path("sql"))
        if (row.path("sql")
            .asText()
            .startsWith("UPDATE task SET updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id="))
          original = row.path("sql").asText();
      assertNotNull(original);
      assertEquals(original, bound.getSql());
    }
    assertEquals(
        List.of("tenant", "task", "attempt", "worker", "fence"),
        bound.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    var method = CommitFenceMapper.class.getDeclaredMethods()[0];
    assertEquals(
        List.of(
            io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.class,
            String.class,
            String.class,
            String.class,
            String.class,
            long.class),
        List.of(method.getParameterTypes()));
    assertEquals(org.apache.ibatis.mapping.SqlCommandType.UPDATE, statement.getSqlCommandType());
  }
}
