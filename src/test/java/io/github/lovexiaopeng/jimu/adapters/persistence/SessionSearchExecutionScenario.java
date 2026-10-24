package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 同一实际PENDING search槽的工具/owner和故障外层回滚，不恢复历史全矩阵。 */
public final class SessionSearchExecutionScenario {
  private static JdbcTemplate db;
  private static JdbcLedger active;
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
            "invocation_session_search",
            "model_dispatch",
            "task_attempt",
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

  private static void proof(String trial, Object invocation) {
    var row =
        db.queryForMap(
            "SELECT outcome,failure_code,upper_seq,input_digest,normalized_query_snapshot,normalized_query_digest,normalized_query_bytes,scan_digest,scan_rows,scan_event_bytes,output_digest,output_bytes,receipt_event_seq,committed_attempt_id,committed_fence FROM invocation_session_search WHERE tenant_id='tenant-a' AND invocation_id=?",
            invocation);
    System.out.println("PROOF " + trial + " " + Payloads.json(row));
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
                                    && sql.contains("LIMIT 128")) {
                                  injected++;
                                  throw new SQLException(
                                      "PRIVATE_NATIVE_SCAN_FAILURE", "HY000", 1644);
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
    var slot =
        db.queryForMap(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND tool='session_event_search' ORDER BY ordinal LIMIT 1",
            id);
    for (String mode : List.of("owner", "empty", "tool", "sql", "corrupt")) {
      trace.clear();
      inject = mode.equals("sql");
      injected = 0;
      active = null;
      tx.execute(
          status -> {
            try {
              String output;
              if (mode.equals("owner") || mode.equals("empty")) {
                var identity =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", (String) task.get("owner_actor_id"));
                byte[] body =
                    (mode.equals("empty")
                            ? "{\"query\":\"native\",\"seq_from\":127}"
                            : "{\"query\":\"native\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output =
                    ledger.sessionSearch(
                        identity, id, end -> body, System.nanoTime() + 2_000_000_000L);
                require(
                    trace.stream().anyMatch(sql -> sql.contains("LIMIT 128"))
                        == mode.equals("owner"),
                    "owner empty range scan short circuit");
              } else {
                db.update(
                    "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 120 SECOND,deadline=CURRENT_TIMESTAMP(6)+INTERVAL 120 SECOND WHERE tenant_id='tenant-a' AND id=?",
                    id);
                var row =
                    db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
                var lease =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
                        "tenant-a",
                        (String) row.get("owner_actor_id"),
                        id,
                        (String) row.get("current_attempt_id"),
                        ((Number) row.get("fence")).longValue(),
                        (String) row.get("worker_id"),
                        ((java.time.LocalDateTime) row.get("deadline"))
                            .toInstant(java.time.ZoneOffset.UTC),
                        (String) row.get("goal"),
                        java.util.Arrays.asList(((String) row.get("capabilities")).split(",")),
                        ((Number) row.get("installation_epoch")).longValue());
                var method =
                    JdbcLedger.class.getDeclaredMethod(
                        "queryView",
                        io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease.class,
                        boolean.class);
                method.setAccessible(true);
                var view = (JdbcLedger) method.invoke(ledger, lease, false);
                active = view;
                if (mode.equals("corrupt"))
                  db.update(
                      "UPDATE session_public_event SET event_digest=? WHERE tenant_id='tenant-a' AND task_id=? AND seq=0",
                      "0".repeat(64),
                      id);
                output =
                    view.searchOrResult(
                        lease,
                        (String) slot.get("step_id"),
                        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                            (String) slot.get("slot"),
                            (String) slot.get("provider_id"),
                            (String) slot.get("tool"),
                            (String) slot.get("input_snapshot")),
                        (String) slot.get("plugin_version"),
                        (String) slot.get("plugin_digest"));
                require(mode.equals("tool"), "only successful tool returns");
                require(
                    "SUCCEEDED"
                        .equals(
                            db.queryForObject(
                                "SELECT outcome FROM invocation_session_search WHERE tenant_id='tenant-a' AND invocation_id=?",
                                String.class,
                                slot.get("id"))),
                    "actual tool settlement before rollback");
                proof(mode, slot.get("id"));
              }
              System.out.println("RESULT " + mode + " " + output);
            } catch (
                io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed
                    failure) {
              require(
                  mode.equals("sql") && injected == 1,
                  "single scan fault retains original unconfirmed boundary");
              System.out.println("RESULT sql " + Payloads.json("SessionCommitUnconfirmed"));
            } catch (TaskFailure failure) {
              require(
                  mode.equals("corrupt") && failure.code().equals("SESSION_HISTORY_CORRUPT"),
                  "original known corrupt rejection");
              require(
                  "FAILED"
                      .equals(
                          db.queryForObject(
                              "SELECT outcome FROM invocation_session_search WHERE tenant_id='tenant-a' AND invocation_id=?",
                              String.class,
                              slot.get("id"))),
                  "known rejection staged settlement before throw");
              proof(mode, slot.get("id"));
              System.out.println("RESULT corrupt " + Payloads.json(failure.code()));
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            } finally {
              status.setRollbackOnly();
            }
            return null;
          });
      require(
          before.equals(facts(id)),
          "all captured native search facts restored after outer rollback");
      if (active != null) {
        var budgetField = JdbcSessionRecords.class.getDeclaredField("searchBudget");
        budgetField.setAccessible(true);
        require(budgetField.get(active.sessions) == null, "native search budget finally cleared");
      }
      require(
          trace.stream()
              .anyMatch(sql -> sql.contains("task_session_history") && sql.contains("FOR UPDATE")),
          "history lock retained");
      System.out.println("TRACE " + mode + " " + Payloads.json(trace));
    }
  }
}
