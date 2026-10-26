package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OwnerDeliveryMapperTest {
  @Test
  void originalSqlAndAllSixBindPositionsAreLoaded() {
    var config =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    var ids =
        List.of(
            "task.mapper.TaskMapper.selectSessionOwner",
            "question.mapper.QuestionMessagesMapper.checkpoint");
    var sql =
        List.of(
            "SELECT * FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=? FOR UPDATE",
            "UPDATE user_question SET delivery_state='CHECKPOINTED',delivery_step_id=? WHERE tenant_id=? AND id=? AND delivery_state='PENDING'");
    var binds = List.of(List.of("tenant", "task", "actor"), List.of("step", "tenant", "question"));
    for (int i = 0; i < ids.size(); i++) {
      var bound =
          config
              .getMappedStatement("io.github.lovexiaopeng.jimu.adapters.persistence." + ids.get(i))
              .getBoundSql(Map.of());
      assertEquals(sql.get(i), bound.getSql().trim());
      assertEquals(
          binds.get(i), bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
  }
}
