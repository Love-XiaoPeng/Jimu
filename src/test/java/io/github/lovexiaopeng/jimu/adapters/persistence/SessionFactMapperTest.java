package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SessionFactMapperTest {
  @Test
  void actualMpStatementsRetainSelectShapeFullKeysAndBindingOrder() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    for (String kind : new String[] {"Step", "Attempt", "Terminal"}) {
      var wrapper = new QueryWrapper<>();
      boolean terminal = kind.equals("Terminal");
      wrapper.select(
          terminal ? new String[] {"event_id", "type", "status", "code"} : new String[] {"*"});
      wrapper.eq("tenant_id", "tenant");
      if (!terminal) wrapper.eq("id", "id");
      wrapper.eq("task_id", "task");
      if (terminal) wrapper.eq("fact_id", "terminal");
      var bound =
          session
              .getConfiguration()
              .getMappedStatement(
                  "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionFact"
                      + kind
                      + "Mapper.selectMaps")
              .getBoundSql(Map.of("ew", wrapper));
      String sql = bound.getSql().replaceAll("\\s+", " ").trim();
      String table = terminal ? "event" : kind.equals("Step") ? "step" : "task_attempt";
      assertEquals(
          "SELECT "
              + (terminal ? "event_id,type,status,code" : "*")
              + " FROM "
              + table
              + " WHERE (tenant_id = ? AND "
              + (terminal ? "task_id = ? AND fact_id = ?" : "id = ? AND task_id = ?")
              + ")",
          sql);
      assertEquals(3, bound.getParameterMappings().size());
      assertTrue(!sql.contains("LIMIT") && !sql.contains("FOR UPDATE"));
      for (int i = 0; i < 3; i++)
        assertEquals(
            "ew.paramNameValuePairs.MPGENVAL" + (i + 1),
            bound.getParameterMappings().get(i).getProperty());
    }
  }
}
