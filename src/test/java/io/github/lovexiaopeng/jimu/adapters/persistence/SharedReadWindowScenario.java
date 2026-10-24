package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 同一真实worker冻结事实上的共享原生窗口对照；外层只回滚，不扩为提交矩阵。 */
public final class SharedReadWindowScenario {
  private static JdbcTemplate db;
  private static boolean inject;
  private static int injected;
  private static final List<String> trace = new ArrayList<>();

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static String facts(String task) {
    var result = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "invocation_session_read",
            "reservation",
            "event",
            "task_session_history",
            "session_public_event")) {
      String column = table.equals("task") ? "id" : "task_id";
      result.put(
          table,
          db
              .queryForList(
                  "SELECT * FROM "
                      + table
                      + " WHERE tenant_id='tenant-a' AND "
                      + column
                      + "=? ORDER BY 1,2,3",
                  task)
              .stream()
              .map(row -> row.toString())
              .sorted()
              .toList());
    }
    result.put(
        "budget",
        db.queryForMap("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'").toString());
    return Payloads.json(result);
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var plain =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var source =
        new DelegatingDataSource(plain) {
          @Override
          public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object result = invoke(connection, m, a);
                      if (m.getName().equals("prepareStatement")
                          && result instanceof PreparedStatement statement) {
                        String sql = a[0].toString().replaceAll("\\s+", " ");
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("execute")) {
                                trace.add(sql);
                                if (inject
                                    && sql.contains("FROM session_public_event")
                                    && sql.contains(" BETWEEN ")
                                    && sql.contains("LIMIT 9")) {
                                  injected++;
                                  throw new SQLException(
                                      "PRIVATE_SHARED_WINDOW_FAILURE", "HY000", 1644);
                                }
                              }
                              return invoke(statement, sm, sv);
                            });
                      }
                      return result;
                    });
          }
        };
    db = new JdbcTemplate(source);
    var ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    String id = args[1];
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
    long upper =
        db.queryForObject(
            "SELECT next_seq-1 FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
            Long.class,
            id);
    require(upper >= 2, "actual immutable user/step/model facts available");
    String before = facts(id);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    for (String mode : List.of("success", "missing", "sql")) {
      trace.clear();
      inject = mode.equals("sql");
      injected = 0;
      var method =
          JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      method.setAccessible(true);
      long end = System.nanoTime() + 2_000_000_000L;
      var view = (JdbcLedger) method.invoke(ledger, end, null);
      tx.execute(
          status -> {
            try {
              String output =
                  view.sessionTransaction(
                      () -> {
                        var r =
                            view.sessions.read(
                                task,
                                new SessionEventPort.Input(
                                    mode.equals("missing") ? 127 : 1, id, 1, 1),
                                upper,
                                "pre-step",
                                () -> view.queryContext().remainingMillis());
                        return r.output();
                      },
                      end);
              require(mode.equals("success"), "only successful window returns output");
              System.out.println("RESULT window " + output);
            } catch (TaskFailure failure) {
              require(
                  mode.equals("missing") && failure.code().equals("SESSION_EVENT_NOT_FOUND"),
                  "original shared-window404");
              System.out.println("RESULT missing " + Payloads.json(failure.code()));
            } catch (DataAccessException failure) {
              require(inject && injected == 1, "single bounded SELECT fault");
              Throwable cause = failure;
              while (cause.getCause() != null) cause = cause.getCause();
              require(
                  cause instanceof SQLException
                      && cause.getMessage().contains("PRIVATE_SHARED_WINDOW_FAILURE"),
                  "actual injected window SQL fault");
              System.out.println("RESULT fault " + Payloads.json("HY000:1644"));
            } finally {
              status.setRollbackOnly();
            }
            return null;
          });
      require(before.equals(facts(id)), "all captured native facts unchanged after outer rollback");
      require(!view.sessions.priorProof(), "no prior budget after native read");
      require(
          trace.stream()
              .anyMatch(sql -> sql.contains("task_session_history") && sql.contains("FOR UPDATE")),
          "history lock retained");
      System.out.println("QUERY " + mode + " " + Payloads.json(view.queryContext().resources()));
    }
  }
}
