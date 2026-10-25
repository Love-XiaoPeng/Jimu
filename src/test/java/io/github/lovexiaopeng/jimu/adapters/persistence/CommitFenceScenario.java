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
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 基础资格实际调用／锁链；外层恢复、主错、回滚及证明错误分存。 */
public final class CommitFenceScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static DataSourceTransactionManager transactions;
  private static String task;
  private static boolean measuring;
  private static io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease;
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
            "installation",
            "task_directory")) {
      result.put(
          table,
          db
              .queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a' ORDER BY 1,2,3")
              .stream()
              .map(Object::toString)
              .sorted()
              .toList());
    }
    return Payloads.json(evidenceValue(result));
  }

  private static void errors(Throwable failure, String role) {
    if (failure == null) return;
    System.out.println("ERROR_ROLE " + role);
    failure.printStackTrace(System.out);
    errorChain(failure, role, new java.util.IdentityHashMap<>());
  }

  private static void errorChain(
      Throwable failure, String role, java.util.IdentityHashMap<Throwable, Boolean> seen) {
    if (failure == null || seen.put(failure, Boolean.TRUE) != null) return;
    if (failure instanceof SQLException sqlFailure) {
      System.out.println(
          "SQLERROR "
              + Payloads.json(
                  Map.of(
                      "role",
                      role,
                      "class",
                      failure.getClass().getName(),
                      "state",
                      String.valueOf(sqlFailure.getSQLState()),
                      "vendor",
                      sqlFailure.getErrorCode())));
      errorChain(sqlFailure.getNextException(), role + ".next", seen);
    }
    errorChain(failure.getCause(), role + ".cause", seen);
    for (var suppressed : failure.getSuppressed())
      errorChain(suppressed, role + ".suppressed", seen);
  }

  private static void trial(String name, boolean reject, boolean directory) {
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null, rollback = null, proofError = null;
    String raw = null;
    sql.clear();
    try {
      db.update(
          "UPDATE task SET deadline=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
          task);
      ledger.registerWorker(
          "fence-private", "fence-private", ProcessHandle.current().pid(), 1, "private", "private");
      lease = ledger.claim("fence-private").orElseThrow();
      require(
          task.equals(lease.taskId()), "exact queued directory task claimed without worker loop");
      if (reject)
        lease =
            new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
                lease.tenant(),
                lease.actor(),
                lease.taskId(),
                lease.attemptId(),
                lease.fence() - 1,
                lease.worker(),
                lease.deadline(),
                lease.goal(),
                lease.capabilities(),
                lease.installationEpoch());
      db.update(
          "UPDATE task SET updated_at='2020-01-01 00:00:00.000001' WHERE tenant_id='tenant-a' AND id=?",
          task);
      var first = taskRow();
      var clockBefore =
          db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.time.LocalDateTime.class);
      measuring = true;
      try {
        if (directory) ledger.directoryReady(lease);
        else ledger.todoCommitFence(lease);
      } catch (Throwable failure) {
        primary = failure;
        errors(failure, "production-" + name);
      } finally {
        measuring = false;
      }
      try {
        var after = taskRow();
        var clockAfter =
            db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.time.LocalDateTime.class);
        var time = (java.time.LocalDateTime) after.get("updated_at");
        if (reject)
          require(
              first.get("updated_at").equals(time), "rejected UPDATE leaves timestamp unchanged");
        else
          require(
              !time.isBefore(clockBefore)
                  && !time.isAfter(clockAfter)
                  && !time.equals(first.get("updated_at")),
              "actual database timestamp changed within observed DB interval");
        var projection =
            db.queryForMap(
                "SELECT * FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?", task);
        require(
            (directory ? "READY" : "UNINITIALIZED").equals(projection.get("workspace_state")),
            "shared non-todo directory result");
        var proof = new LinkedHashMap<String, Object>();
        proof.put("name", name);
        proof.put("beforeUpdatedAt", first.get("updated_at"));
        proof.put("afterUpdatedAt", time);
        proof.put("databaseClockBefore", clockBefore);
        proof.put("databaseClockAfter", clockAfter);
        proof.put("workspaceState", projection.get("workspace_state"));
        proof.put("taskState", after.get("state"));
        proof.put("capabilities", after.get("capabilities"));
        proof.put(
            "lease",
            Map.of(
                "tenant",
                lease.tenant(),
                "task",
                lease.taskId(),
                "attempt",
                lease.attemptId(),
                "worker",
                lease.worker(),
                "fence",
                lease.fence()));
        raw = Payloads.json(evidenceValue(proof));
        System.out.println("FENCE_PROOF " + raw);
      } catch (Throwable failure) {
        proofError = failure;
        errors(failure, "proof-" + name);
      }
    } catch (Throwable failure) {
      primary = failure;
      errors(failure, "setup-" + name);
    } finally {
      measuring = false;
      try {
        transactions.rollback(status);
        System.out.println("ROLLBACK " + name);
      } catch (Throwable failure) {
        rollback = failure;
        errors(failure, "rollback-" + name);
      }
      System.out.println("SQL " + name + " " + Payloads.json(sql));
    }
    require(rollback == null, "UNKNOWN rollback; no replay");
    String after = facts();
    System.out.println(
        "FACTS " + Payloads.json(Map.of("name", name, "before", before, "after", after)));
    require(before.equals(after), "captured tenant-a twenty-table outer restoration");
    System.out.println("RESTORED " + name);
    require(proofError == null, "separate proof failure");
    String code =
        primary == null
            ? "OK"
            : primary instanceof TaskFailure f ? f.code() : primary.getClass().getName();
    require((reject ? "FENCE_REJECTED" : "OK").equals(code), "unexpected original outcome " + code);
    var result = new LinkedHashMap<String, Object>();
    result.put("name", name);
    result.put("code", code);
    result.put("raw", raw);
    System.out.println("RESULT " + Payloads.json(result));
  }

  static Object evidenceValue(Object value) {
    if (value instanceof java.time.LocalDateTime) return bindValue(value);
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), evidenceValue(item)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(CommitFenceScenario::evidenceValue).toList();
    return value;
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
                      Object[] actual = a;
                      Object value = invoke(connection, m, actual);
                      if (value instanceof PreparedStatement statement
                          && m.getName().equals("prepareStatement")) {
                        String text = a[0].toString().replaceAll("\\s+", " ");
                        var binds = new java.util.TreeMap<Integer, Object>();
                        var setters = new java.util.TreeMap<Integer, String>();
                        var types = new java.util.TreeMap<Integer, String>();
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("set")
                                  && sv != null
                                  && sv.length > 1
                                  && sv[0] instanceof Integer index) {
                                setters.put(index, sm.getName());
                                types.put(
                                    index, sv[1] == null ? "null" : sv[1].getClass().getName());
                                binds.put(
                                    index,
                                    sm.getName().equals("setNull") ? null : bindValue(sv[1]));
                              }
                              if (measuring && sm.getName().startsWith("execute")) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text);
                                row.put("binds", new LinkedHashMap<>(binds));
                                row.put("setters", new LinkedHashMap<>(setters));
                                row.put("bindJavaTypes", new LinkedHashMap<>(types));
                                sql.add(row);
                              }
                              Object result = invoke(statement, sm, sv);
                              if (measuring
                                  && sm.getName().equals("executeUpdate")
                                  && !sql.isEmpty()) sql.getLast().put("updateCount", result);
                              if (measuring
                                  && sm.getName().equals("getUpdateCount")
                                  && !sql.isEmpty()) sql.getLast().put("updateCount", result);
                              return result;
                            });
                      }
                      return value;
                    });
          }
        };
    var shared =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(source);
    db = new JdbcTemplate(shared);
    transactions = new DataSourceTransactionManager(shared);
    ledger = new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    task = args[1];
    ledger.directoryEnabled(true);
    trial("direct-success", false, false);
    trial("direct-old-fence", true, false);
    trial("directory-ready", false, true);
  }
}
