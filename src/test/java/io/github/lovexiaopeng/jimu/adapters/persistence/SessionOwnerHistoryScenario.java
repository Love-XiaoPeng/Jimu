package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
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
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 真实 owner 入口与共享 recent；证明读取在产品计时之外，异常与外层回滚分别保留。 */
public final class SessionOwnerHistoryScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static DataSourceTransactionManager transactions;
  private static String task;
  private static boolean measuring;
  private static boolean fault;
  private static int injected;
  private static final List<Map<String, Object>> sql = new ArrayList<>();

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static String facts() {
    var result = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "invocation_session_read",
            "invocation_session_trace",
            "invocation_session_search",
            "invocation_session_scope_query",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "model_dispatch",
            "task_attempt",
            "reservation",
            "event",
            "task_session_history",
            "session_public_event",
            "tenant_budget",
            "actor_access",
            "installation")) {
      result.put(
          table,
          db
              .queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a' ORDER BY 1,2,3")
              .stream()
              .map(Object::toString)
              .sorted()
              .toList());
    }
    return Payloads.json(result);
  }

  private static void errors(Throwable failure, String role) {
    if (failure == null) return;
    System.out.println("ERROR_ROLE " + role);
    failure.printStackTrace(System.out);
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException value)
        System.out.println(
            "SQLERROR "
                + Payloads.json(
                    Map.of(
                        "role",
                        role,
                        "class",
                        value.getClass().getName(),
                        "state",
                        String.valueOf(value.getSQLState()),
                        "vendor",
                        value.getErrorCode())));
    }
  }

  private static void trial(String name, Runnable setup, Supplier<String> call, String expected) {
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    Throwable rollback = null;
    String raw = null;
    sql.clear();
    injected = 0;
    long started = 0;
    long elapsed = 0;
    try {
      setup.run();
      measuring = true;
      started = System.nanoTime();
      raw = call.get();
      elapsed = System.nanoTime() - started;
      System.out.println("RETURN " + name + " " + elapsed);
    } catch (Throwable failure) {
      elapsed = started == 0 ? 0 : System.nanoTime() - started;
      primary = failure;
      errors(failure, "production-or-setup-" + name);
    } finally {
      measuring = false;
      fault = false;
      try {
        transactions.rollback(status);
        System.out.println("ROLLBACK " + name);
      } catch (Throwable failure) {
        rollback = failure;
        errors(failure, "rollback-" + name);
      }
      // Persist primary and SQL evidence before any assertion or proof read can fail.
      System.out.println("SQL " + name + " " + Payloads.json(sql));
      System.out.println(
          "OUTCOME "
              + Payloads.json(
                  Map.of(
                      "name",
                      name,
                      "returned",
                      primary == null,
                      "elapsedNanos",
                      elapsed,
                      "primary",
                      primary == null ? "none" : primary.getClass().getName(),
                      "rollback",
                      rollback == null ? "complete" : rollback.getClass().getName())));
    }
    require(rollback == null, "UNKNOWN/rollback failure ends this private instance");
    require(before.equals(facts()), "all captured tenant facts restored: " + name);
    System.out.println("RESTORED " + name);
    String code =
        primary == null
            ? "OK"
            : primary instanceof TaskFailure failure
                ? failure.getMessage()
                : primary.getClass().getName();
    require(expected.equals(code), name + " expected " + expected + " got " + code);
    if (name.equals("recent-sql")) require(injected == 1, "one SELECT fault only");
    if (name.equals("expired")) {
      if (raw != null) {
        var value = Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class);
        require(
            !value.path("readable").asBoolean() && value.path("recent").isEmpty(),
            "expired native history skips recent");
      }
      require(
          sql.stream().noneMatch(r -> r.get("text").toString().startsWith("SELECT seq,type")),
          "unreadable has no recent SELECT");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("name", name);
    result.put("code", code);
    result.put("raw", raw);
    result.put("injected", injected);
    System.out.println("RESULT " + Payloads.json(result));
  }

  private static String owner() {
    // Keep queryView construction inside the original public ledger entry and its timer.
    return ledger.sessionHistory(new Identity("tenant-a", "alice"), task);
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
            Connection connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object value = invoke(connection, m, a);
                      if (value instanceof PreparedStatement statement
                          && m.getName().equals("prepareStatement")) {
                        String text = a[0].toString().replaceAll("\\s+", " ");
                        var binds = new java.util.TreeMap<Integer, Object>();
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("set")
                                  && sv != null
                                  && sv.length > 1
                                  && sv[0] instanceof Integer index)
                                binds.put(index, sm.getName().equals("setNull") ? null : sv[1]);
                              if (measuring && sm.getName().startsWith("execute")) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text);
                                row.put("binds", new LinkedHashMap<>(binds));
                                sql.add(row);
                                if (fault && text.startsWith("SELECT seq,type")) {
                                  fault = false;
                                  injected++;
                                  throw new SQLException(
                                      "PRIVATE_OWNER_RECENT_FAILURE", "HY000", 1644);
                                }
                              }
                              return invoke(statement, sm, sv);
                            });
                      }
                      return value;
                    });
          }
        };
    db = new JdbcTemplate(source);
    transactions = new DataSourceTransactionManager(source);
    ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    task = args[1];
    boolean scoped =
        JdbcSessionScopes.task(
            db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task));
    trial("owner", () -> {}, SessionOwnerHistoryScenario::owner, "OK");
    trial(
        "wrong-owner",
        () -> {},
        () -> ledger.sessionHistory(new Identity("tenant-a", "carol"), task),
        "SESSION_EVENT_NOT_FOUND");
    trial(
        "wrong-tenant",
        () -> {},
        () -> ledger.sessionHistory(new Identity("tenant-b", "bob"), task),
        "SESSION_EVENT_NOT_FOUND");
    trial(
        "malformed",
        () -> {},
        () -> ledger.sessionHistory(new Identity("tenant-a", "alice"), "BAD"),
        "SESSION_EVENT_NOT_FOUND");
    trial(
        "absent",
        () -> {},
        () ->
            ledger.sessionHistory(
                new Identity("tenant-a", "alice"), "00000000-0000-0000-0000-000000000000"),
        "SESSION_EVENT_NOT_FOUND");
    trial(
        "expired",
        () ->
            db.update(
                "UPDATE task_session_history SET retain_until='2000-01-01' WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        SessionOwnerHistoryScenario::owner,
        scoped ? "SESSION_HISTORY_EXPIRED" : "OK");
    trial(
        "window8",
        () -> {
          // Projection-only rows; no event proof or append/settlement claim is made for them.
          for (int seq = 1; seq < 10; seq++)
            db.update(
                "INSERT INTO session_public_event(tenant_id,task_id,seq,fact_key,type,occurred_at,event_snapshot,event_digest,event_bytes,source_digest,reliable_event_seq) SELECT tenant_id,task_id,?,CONCAT('projection-fixture-',?),type,occurred_at,event_snapshot,event_digest,event_bytes,source_digest,reliable_event_seq FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND seq=0",
                seq,
                seq,
                task);
          db.update(
              "UPDATE task_session_history SET next_seq=10 WHERE tenant_id='tenant-a' AND task_id=?",
              task);
        },
        SessionOwnerHistoryScenario::owner,
        "OK");
    trial(
        "missing-row",
        () ->
            db.update(
                "UPDATE task_session_history SET next_seq=2 WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        SessionOwnerHistoryScenario::owner,
        "SESSION_HISTORY_CORRUPT");
    trial(
        "recent-sql",
        () -> fault = true,
        SessionOwnerHistoryScenario::owner,
        "SESSION_QUERY_UNAVAILABLE");
    trial(
        "context-recent",
        () -> {},
        () -> {
          var row = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
          var context = new MapperSessionContexts(ledger).bind(row);
          return Payloads.json(context.recent(context.upper()));
        },
        "OK");
  }
}
