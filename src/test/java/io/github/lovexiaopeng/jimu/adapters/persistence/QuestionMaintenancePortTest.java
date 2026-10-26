package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionMaintenanceMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionMaintenancePortTest {
  @Test
  void constructionStoresReferencesOnly() {
    var p = new MapperQuestionMaintenance(null, null, null, null);
    assertThrows(NullPointerException.class, p::enabled);
  }

  @Test
  void twoRealLoadedStatementsPreserveGlobalScanAndNullPredicates() {
    var c =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    assertFalse(c.isCacheEnabled());
    assertEquals(LocalCacheScope.STATEMENT, c.getLocalCacheScope());
    var expected =
        Map.of(
            "candidates",
            "SELECT t.tenant_id,t.id FROM task t LEFT JOIN actor_access a ON a.tenant_id=t.tenant_id AND a.actor_id=t.owner_actor_id LEFT JOIN installation i ON i.tenant_id=t.tenant_id AND i.plugin_id='ask_user_question' WHERE t.question_binding IS NOT NULL AND t.state IN ('WAITING_USER','RESUME_READY') AND (?=FALSE OR t.deadline<=CURRENT_TIMESTAMP(6) OR t.cancel_requested_at IS NOT NULL OR a.enabled=FALSE OR i.plugin_id IS NULL OR i.enabled=FALSE OR i.epoch<>t.installation_epoch OR i.plugin_version<>JSON_UNQUOTE(JSON_EXTRACT(t.tools_snapshot,'$.pins.ask_user_question.version')) OR i.plugin_digest<>JSON_UNQUOTE(JSON_EXTRACT(t.tools_snapshot,'$.pins.ask_user_question.digest')) OR EXISTS(SELECT 1 FROM user_question q WHERE q.tenant_id=t.tenant_id AND q.task_id=t.id AND q.phase='OPEN' AND q.foreground_due_at<=CURRENT_TIMESTAMP(6))) ORDER BY t.deadline,t.id LIMIT 64",
            "due",
            "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND phase='OPEN' AND foreground_due_at<=CURRENT_TIMESTAMP(6) FOR UPDATE");
    for (Object query :
        Arrays.asList(
            null,
            new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
                System.nanoTime() + 2_000_000_000L, null))) {
      for (String name : expected.keySet()) {
        var args = new HashMap<String, Object>();
        args.put("query", query);
        args.put("enabled", false);
        args.put("tenant", null);
        args.put("task", null);
        var st = c.getMappedStatement(QuestionMaintenanceMapper.class.getName() + "." + name);
        var bound = st.getBoundSql(args);
        assertEquals(expected.get(name), bound.getSql());
        assertEquals(
            name.equals("candidates") ? List.of("enabled") : List.of("tenant", "task"),
            bound.getParameterMappings().stream().map(v -> v.getProperty()).toList());
      }
    }
    assertEquals(
        List.of(
            io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.class,
            boolean.class),
        Arrays.asList(
            Arrays.stream(QuestionMaintenanceMapper.class.getMethods())
                .filter(m -> m.getName().equals("candidates"))
                .findFirst()
                .orElseThrow()
                .getParameterTypes()));
  }
}
