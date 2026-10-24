package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionJdbcTemplate;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Actual new MP/XML reads share a connection/UoW and preserve original result/deadline metering.
 */
public final class InvocationQueryScenario {
  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var stream = Files.newInputStream(Path.of(args[0]))) {
      properties.load(stream);
    }
    var source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.user"),
            properties.getProperty("jimu.db.password"));
    var mappers = CoreMappers.from(LedgerMapperSessions.create(source));
    var work = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    check(
        mappers.selectInstallation(null, "tenant-a", "web_fetch").size() == 1,
        "ordinary null context remains supported");
    var old = context();
    var query = context();
    work.required(
        query,
        () -> {
          var rows = mappers.selectInstallation(query, "tenant-a", "web_fetch");
          long bytes = query.resources().bytes(), ops = query.resources().operations();
          check(
              rows.size() == 1 && query.resources().reads() == 1 && bytes > 0,
              "MP actual physical result metered once");
          var legacy =
              new SessionJdbcTemplate(source, old)
                  .queryForList(
                      "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                      "tenant-a",
                      "web_fetch");
          check(
              rows.equals(legacy) && query.resources().equals(old.resources()),
              "MP/JDBC exact maps and all-column read/byte/operation equality");
          mappers.selectInstallation(query, "tenant-a", "web_fetch");
          check(
              query.resources().reads() == 2
                  && query.resources().bytes() == bytes * 2
                  && query.resources().operations() == ops * 2,
              "repeated MP read is neither cached nor double charged");
          check(
              mappers.selectInstallation(query, "absent-tenant", "web_fetch").isEmpty()
                  && query.resources().reads() == 3,
              "full tenant/plugin predicate and empty-result accounting");
          check(
              mappers
                      .invocations()
                      .selectProtectedInvocation(
                          query, "tenant-a", "absent", "absent", "todo_write")
                      .isEmpty()
                  && query.resources().reads() == 4,
              "parameterized guard empty result counted once");
          return null;
        });
    var expired = new SessionQueryContext(System.nanoTime() - 1, new QueryResources(0, 0, 0));
    reject(() -> mappers.selectInstallation(expired, "tenant-a", "web_fetch"));
    check(expired.resources().reads() == 0, "expired absolute envelope performs no result read");
    var limited =
        new SessionQueryContext(System.nanoTime() + 5_000_000_000L, new QueryResources(4096, 0, 0));
    rejectLimit(() -> mappers.selectInstallation(limited, "tenant-a", "web_fetch"));
    System.out.println("PASS Invocation query context MySQL");
  }

  private static SessionQueryContext context() {
    return new SessionQueryContext(System.nanoTime() + 5_000_000_000L, new QueryResources(0, 0, 0));
  }

  private static void reject(Runnable action) {
    rejectCode("SESSION_QUERY_TIMEOUT", action);
  }

  private static void rejectLimit(Runnable action) {
    rejectCode("SESSION_HISTORY_LIMIT", action);
  }

  private static void rejectCode(String code, Runnable action) {
    try {
      action.run();
      throw new AssertionError("Accepted " + code);
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (!(cause instanceof TaskFailure) && cause.getCause() != null) cause = cause.getCause();
      if (!(cause instanceof TaskFailure task) || !task.code().equals(code)) throw failure;
      System.out.println("PASS " + code);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }
}
