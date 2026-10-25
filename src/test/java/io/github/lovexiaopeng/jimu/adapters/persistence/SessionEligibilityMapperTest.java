package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SessionEligibilityMapperTest {
  @Test
  void loadedSharedLockKeepsSelectStarFullKeysAndOnlyTwoBinds() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var bound =
        session
            .getConfiguration()
            .getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionEligibilityMapper.lockInstallation")
            .getBoundSql(Map.of("tenant", "tenant", "plugin", "plugin"));
    assertEquals(
        "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE", bound.getSql());
    assertEquals(
        java.util.List.of("tenant", "plugin"),
        bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
  }
}
