package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskVisibilityMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionVisibilityPortTest {
  @Test
  void constructionReadsNoIdentityOrEnvironment() {
    new MapperQuestionVisibility(null, null, null);
  }

  @Test
  void nullIdentityMembersAreLazyBeforeQuery() {
    var port = new MapperQuestionVisibility(null, null, "id");
    assertEquals("id", port.taskId());
    var failure = assertThrows(NullPointerException.class, port::tenant);
    assertTrue(failure.getMessage().contains("Identity.tenant()"));
    assertThrows(NullPointerException.class, port::actor);
  }

  @Test
  void realMpSelectsOnlyIdWithThreeOrderedNullableKeys() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    for (var args :
        List.of(
            Arrays.asList("tenant", "task", "owner"), Arrays.<String>asList(null, null, null))) {
      var wrapper = MapperQuestionVisibility.ownerQuery(args.get(0), args.get(1), args.get(2));
      var parameters = Map.of("ew", wrapper);
      var sql =
          session
              .getConfiguration()
              .getMappedStatement(TaskVisibilityMapper.class.getName() + ".selectMaps")
              .getBoundSql(parameters);
      assertEquals(
          "SELECT id FROM task WHERE (tenant_id = ? AND id = ? AND owner_actor_id = ?)",
          sql.getSql().replaceAll("\\s+", " ").trim());
      assertEquals(
          args,
          sql.getParameterMappings().stream()
              .map(v -> SystemMetaObject.forObject(parameters).getValue(v.getProperty()))
              .toList());
    }
  }
}
