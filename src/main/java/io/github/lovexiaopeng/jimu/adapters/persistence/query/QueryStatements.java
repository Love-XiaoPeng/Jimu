package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.SQLException;
import java.sql.Statement;

public final class QueryStatements {
  private QueryStatements() {}

  public static void apply(Statement statement, SessionQueryContext context) throws SQLException {
    int remaining = context.remainingMillis();
    statement.getConnection().setNetworkTimeout(Runnable::run, remaining);
    try (var settings = statement.getConnection().createStatement()) {
      settings.execute("SET SESSION max_execution_time=" + remaining);
    }
    statement.setQueryTimeout(Math.max(1, (remaining + 999) / 1000));
  }
}
