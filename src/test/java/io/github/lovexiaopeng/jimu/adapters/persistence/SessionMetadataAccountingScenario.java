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

/** 实际 context／terminal 和共享固定点账目；证明在调用返回后，主错与回滚分开。 */
public final class SessionMetadataAccountingScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Map<String, Object> selectedTask;
  private static boolean remaining;
  private static boolean terminalOnly;
  private static DataSourceTransactionManager transactions;
  private static String task;
  private static boolean measuring;
  private static String fault;
  private static io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease;
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
    if (terminalOnly && !name.equals("terminal")) return;
    if (remaining
        && !List.of(
                "metadata-sql",
                "counter-sql",
                "account-sql",
                "context",
                "context-account-sql",
                "terminal")
            .contains(name)) return;
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    Throwable rollback = null;
    Throwable proofFailure = null;
    Throwable evidenceFailure = null;
    String raw = null;
    sql.clear();
    injected = 0;
    long started = 0;
    long elapsed = 0;
    try {
      setup.run();
      selectedTask = taskRow();
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
      fault = null;
      if (primary == null
          && (name.startsWith("account-")
              || name.startsWith("context")
              || name.equals("terminal"))) {
        try {
          var row =
              db.queryForMap(
                  "SELECT metadata_bytes,held_bytes,content_state FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                  task);
          long actual = ledger.sessions.metadata(selectedTask, true);
          require(
              ((Number) row.get("metadata_bytes")).longValue() == actual,
              "fixed point after returned call");
          if ("PURGED".equals(row.get("content_state")))
            require(
                row.get("metadata_bytes").equals(row.get("held_bytes")),
                "PURGED held bytes follow metadata");
          else
            require(
                ((Number) row.get("held_bytes")).longValue() == 8388608,
                "PRESENT held bytes unchanged");
          System.out.println("PROOF " + name + " " + Payloads.json(row));
        } catch (Throwable failure) {
          proofFailure = failure;
          errors(failure, "post-return-proof-" + name);
        }
      }
      try {
        transactions.rollback(status);
        System.out.println("ROLLBACK " + name);
      } catch (Throwable failure) {
        rollback = failure;
        errors(failure, "rollback-" + name);
      }
      // Persist primary and SQL evidence before any assertion or proof read can fail.
      try {
        System.out.println("SQL " + name + " " + Payloads.json(sql));
      } catch (Throwable failure) {
        evidenceFailure = failure;
        errors(failure, "sql-evidence-" + name);
      }
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
    require(proofFailure == null, "post-return proof failure separate from production");
    require(rollback == null, "UNKNOWN/rollback failure ends this private instance");
    require(before.equals(facts()), "all captured tenant facts restored: " + name);
    System.out.println("RESTORED " + name);
    require(evidenceFailure == null, "SQL evidence failure kept separate from saved primary");
    String code =
        primary == null
            ? "OK"
            : primary instanceof TaskFailure failure
                ? failure.getMessage()
                : primary.getClass().getName();
    if (expected.equals("SQL_FAILURE")) {
      boolean original = false;
      for (Throwable cause = primary; cause != null; cause = cause.getCause())
        if (cause instanceof SQLException sqlFailure
            && "HY000".equals(sqlFailure.getSQLState())
            && sqlFailure.getErrorCode() == 1644) original = true;
      require(original, "original single SQL failure retained: " + name);
    } else require(expected.equals(code), name + " expected " + expected + " got " + code);
    if (name.endsWith("-sql")) require(injected == 1, "one SQL fault only");
    var result = new LinkedHashMap<String, Object>();
    result.put("name", name);
    result.put("code", code);
    result.put("raw", raw);
    result.put("injected", injected);
    System.out.println("RESULT " + Payloads.json(result));
  }

  private static Object bindValue(Object value) {
    // Evidence representation only; invoke(statement, method, args) receives unchanged JDBC args.
    if (value instanceof java.time.LocalDateTime time)
      return Map.of("javaType", value.getClass().getName(), "isoValue", time.toString());
    return value;
  }

  private static Map<String, Object> taskRow() {
    return db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
  }

  private static String account(boolean closing) {
    ledger.sessions.account(selectedTask, closing);
    return "accounted";
  }

  private static void forceCounter() {
    db.update(
        "UPDATE task_session_history SET metadata_bytes=0 WHERE tenant_id='tenant-a' AND task_id=?",
        task);
  }

  private static void purged(String receipt) {
    db.update(
        "UPDATE task_session_history SET content_state='PURGED',body_bytes=0,cleanup_receipt=?,held_bytes=metadata_bytes WHERE tenant_id='tenant-a' AND task_id=?",
        receipt,
        task);
  }

  private static void claim() {
    lease = ledger.claim("metadata-private").orElseThrow();
    require(task.equals(lease.taskId()), "only admitted queued task claimed");
    forceCounter();
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
                                binds.put(
                                    index,
                                    sm.getName().equals("setNull") ? null : bindValue(sv[1]));
                              if (measuring && sm.getName().startsWith("execute")) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text);
                                row.put("binds", new LinkedHashMap<>(binds));
                                sql.add(row);
                                if (fault != null && text.startsWith(fault)) {
                                  fault = null;
                                  injected++;
                                  throw new SQLException("PRIVATE_METADATA_FAILURE", "HY000", 1644);
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
    remaining = args.length > 2 && args[2].equals("remaining");
    terminalOnly = args.length > 2 && args[2].equals("terminal");
    trial(
        "metadata-open",
        () -> {},
        () -> Long.toString(ledger.sessions.metadata(selectedTask, false)),
        "OK");
    trial(
        "metadata-closing",
        () -> {},
        () -> Long.toString(ledger.sessions.metadata(selectedTask, true)),
        "OK");
    trial(
        "planned-limit",
        () -> {},
        () -> Long.toString(ledger.sessions.metadata(selectedTask, false, 61440)),
        "SESSION_HISTORY_LIMIT");
    trial(
        "planned-overflow",
        () -> {},
        () -> Long.toString(ledger.sessions.metadata(selectedTask, false, Long.MAX_VALUE)),
        "OK");
    trial("account-equal", () -> {}, () -> account(false), "OK");
    trial(
        "account-converge",
        SessionMetadataAccountingScenario::forceCounter,
        () -> account(false),
        "OK");
    trial("account-purged", () -> purged("{}"), () -> account(true), "OK");
    trial(
        "capacity",
        () -> purged("a".repeat(64000)),
        () -> Long.toString(ledger.sessions.metadata(selectedTask, false)),
        "SESSION_HISTORY_LIMIT");
    trial(
        "metadata-sql",
        () -> fault = "SELECT tenant_id,id,owner_actor_id",
        () -> Long.toString(ledger.sessions.metadata(selectedTask, false)),
        "SQL_FAILURE");
    trial(
        "counter-sql",
        () -> fault = "SELECT metadata_bytes FROM task_session_history",
        () -> account(false),
        "SQL_FAILURE");
    trial(
        "account-sql",
        () -> {
          forceCounter();
          fault = "UPDATE task_session_history SET metadata_bytes=";
        },
        () -> account(false),
        "SQL_FAILURE");
    trial(
        "context",
        SessionMetadataAccountingScenario::claim,
        () -> ledger.sessionContext(lease),
        "OK");
    trial(
        "context-account-sql",
        () -> {
          claim();
          fault = "UPDATE task_session_history SET metadata_bytes=";
        },
        () -> ledger.sessionContext(lease),
        "SESSION_QUERY_UNAVAILABLE");
    trial(
        "terminal",
        () -> {},
        () -> ledger.cancel(new Identity("tenant-a", "alice"), task).state(),
        "OK");
  }
}
